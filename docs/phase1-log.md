# Phase 1 기록: eTL 도구(MCP)

기기: Samsung SM-S938N / Android 17, Codex rust-v0.170.0. 2026-11-19.

## 구현한 것

| 파일 | 역할 |
|---|---|
| `etl/EtlClient.kt` | eTL GET 전용 클라이언트. XSSI(`while(1);`) 제거, Link 헤더 페이지네이션(최대 20쪽, origin 밖이면 중단), 다운로드는 리다이렉트를 직접 따라가며 **서명 URL에는 Authorization을 붙이지 않음**, 300MB 상한. 오류 메시지에는 경로(쿼리 제외)와 예외 클래스명만 넣음. |
| `etl/EtlLogic.kt` | 학기 판정, 제출/온라인/누락 판정, KST 표시, HTML→텍스트, 파일명 정리, `etl_get` 경로 필터(`..` 차단), 제출 확인 판정. 참고 구현(`etl_digest.py`)의 규칙을 그대로 옮김. |
| `etl/TokenStore.kt` | Android Keystore AES-GCM(256)으로 토큰 암호화 저장. |
| `etl/EtlTools.kt` | 도구 7개(`etl_digest`, `etl_courses`, `etl_assignment`, `etl_search_files`, `etl_download`, `etl_verify_submission`, `etl_get`). 전부 `readOnlyHint`. 다운로드는 MediaStore로 `Download/etl/`. |
| `mcp/McpServer.kt` | 127.0.0.1 전용 MCP 서버(Streamable HTTP, JSON 응답). 실행마다 새 64자 비밀값, `Authorization: Bearer` 필수(상수 시간 비교), `Origin` 헤더가 있으면 거부, `/mcp`·POST만. |
| `etl/EtlService.kt` | 토큰 저장소·도구·서버 수명, `config.toml`과 `AGENTS.md` 작성(매 실행마다 덮어씀). |
| `phase0/AppServerDriver.kt` | `codex app-server`(stdio JSON-RPC) 최소 클라이언트. Phase 2의 바탕. |

단위 테스트 39개(판정 규칙, 경로 필터, 페이지 링크, 토큰 형식, MCP 인증·프로토콜·keep-alive·chunked) 통과.

## 검증 결과

| 항목 | 결과 |
|---|---|
| Keystore 토큰 저장, 로그에 토큰 미노출 | 성공 |
| 앱에서 도구 직접 호출 → eTL 도달 | 성공(가짜 토큰이라 401, 오류 문구 정상, ~200ms) |
| Codex ↔ MCP 서버 연결 | **성공**: `MCP etl: ready`, 도구 7개 |
| 모델이 도구 호출 → 앱 처리 → 응답 | **성공**: `etl.etl_courses` 호출, 401 오류를 받아 모델이 한국어로 설명 |
| **실제 eTL 토큰으로 호출** | **성공**(아래 표) |

### 실제 토큰으로 도구별 검증 (채팅 경로: 모델 → MCP → eTL)

| 도구 | 결과 | 비고 |
|---|---|---|
| `etl_courses` | 성공 | 이번 학기 강의 8개, 1.3초 |
| `etl_digest` | 성공 | 마감 임박 4건·누락 0건·최근 공지. 3.8초. 시각(KST)과 남은 시간 계산이 맞음 |
| `etl_assignment` | 성공 | 0.3초 |
| `etl_verify_submission` | 성공 | 미제출·마감 전 → `problem: 제출 기록 없음`(판정 규칙대로). `etl_assignment`와 병렬 호출됨 |
| `etl_search_files` | 성공 | 강의 5곳 시험. 3곳에서 결과(13/50/2개), 1곳은 0개, 1곳은 **401 권한 없음**(아래 버그 1) |
| `etl_download` | 성공 | `Download/etl/`에 저장, 크기 일치, **기기의 `sha256sum`이 앱이 보고한 값과 일치** |
| `etl_get` | 성공 | `/api/v1/users/self` 통과, `/api/v1/../../logout`은 8ms 만에 거부 |

모델은 프롬프트를 보고 도구를 스스로 골라 호출했고, 독립적인 호출은 병렬로 냈다. 승인 요청은 한 번도 오지 않았다.

## 시험 중 찾아 고친 버그

1. **401 분류 오류.** eTL은 오류 메시지를 **한국어로** 돌려준다(`사용자에게 이 동작을 수행할 권한이 없음`). 영어 `not authorized`만 보던 판정 규칙이 이를 "토큰 거부"로 잘못 분류했다. 한국어 `권한`도 인식하고(본문에 `token`/`토큰`이 있으면 토큰 문제), 서버 메시지 일부(토큰 제거, 160자)를 오류에 덧붙이도록 했다. 파일 탭이 숨겨진 강의의 401이 "토큰 만료"로 보이면 사용자가 불필요하게 토큰을 다시 만들게 되므로 중요하다.
2. **`etl_download`의 `saved_to` 오류.** 같은 이름의 파일이 이미 있으면 MediaStore가 `이름 (1).ext`로 바꾸는데, 이 이름 변경이 `IS_PENDING`을 풀 때 일어나서 저장 전에 읽은 이름이 틀렸다. 저장을 마친 뒤 다시 읽도록 수정했고, 같은 파일을 두 번 받아 `... (1).pdf`가 정확히 보고되는 것을 확인했다.

단위 테스트는 41개(401 분류와 오류 문구 추출 테스트 추가).

## 막혔던 것과 해결

1. **`codex exec`로는 도구를 못 쓴다.** ChatGPT 계정으로 쓸 수 있는 모델(`gpt-6.1-sol` 등)은 내장 카탈로그에서 전부 `tool_mode: code_mode_only`다(`gpt-5.5`만 아니지만 ChatGPT 계정에서 `not supported`). 이 모델들은 MCP 도구를 **code mode**로만 호출하는데, 그러려면 `codex` 바이너리 옆에 `codex-code-mode-host` 실행 파일이 있어야 한다(`.../lib/arm64/codex-code-mode-host`). nativeLibraryDir에는 `lib*.so`만 풀리므로 그 이름의 파일을 둘 수 없다.
   - `features.code_mode=false`, `code_mode_only=false`: 효과 없음(모델 메타데이터가 우선).
   - `model = "gpt-5.5"`: ChatGPT 계정에서 거부됨.
2. **해결: `codex app-server --code-mode-host <URL>`로 원격 호스트에 연결.** 호스트(`codex-code-mode-host`, 릴리스에 별도 자산)는 `libcodex-code-mode-host.so`로 넣고 앱이 `--listen grpc://127.0.0.1:PORT`로 직접 띄운다. 단, Codex 쪽 URL은 `grpc://`가 아니라 **`http://127.0.0.1:PORT`**여야 한다(`code-mode host URL must use http:// or https://`). 이 옵션은 `exec`에는 없고 `app-server`에만 있다. `--enable code_mode_host`도 같이 넘김(없이 시험해 보지는 않음).
3. **`codex_apps`(ChatGPT 커넥터, Gmail 등 도구 140여 개)가 자동으로 붙는다.** eTL 비서에는 불필요하고 신뢰할 수 없는 본문에 의한 오용 위험만 키우므로 `[features] apps = false`로 껐다. 끈 뒤 `etl` 서버 하나만 남는 것을 확인.

## 설정(앱이 쓰는 `CODEX_HOME/config.toml`)

```toml
[features]
apps = false

[mcp_servers.etl]
url = "http://127.0.0.1:<랜덤 포트>/mcp"
bearer_token_env_var = "ETL_MCP_SECRET"
startup_timeout_sec = 20
tool_timeout_sec = 120
default_tools_approval_mode = "approve"
```

`approvalPolicy: "never"`로 스레드를 시작하고 서버→클라이언트 요청(승인 등)은 거절한다. 시험에서 승인 요청은 오지 않았다.

## 남은 일 / 주의

- 같은 이름의 파일을 다시 받으면 매번 `(1)`, `(2)`가 늘어난다. 같은 파일(ID)이면 덮어쓸지 정책이 필요하다.
- 시험으로 폰의 `Download/etl/`에 PDF 4개가 남아 있다(직접 지워도 된다).
- 호스트 바이너리 때문에 APK가 약 268MB(디버그). 릴리스에서는 줄이는 방법을 따로 봐야 한다(ABI는 이미 arm64만).
- `--enable code_mode_host`가 꼭 필요한지는 확인하지 않았다.
- bubblewrap 경고(`could not find bubblewrap on PATH`)는 계속 나옴. 샌드박스 `read-only`로 동작은 함.
- 도구 호출이 `tool_timeout_sec`(120초)을 넘는 경우(아주 큰 파일 다운로드)는 시험하지 않았다.
- 앱이 백그라운드일 때도 MCP 서버·호스트가 살아 있어야 하므로 채팅 중에는 `RunService`가 필요(Phase 0 결론과 같음).
- 프롬프트 인젝션 방어(AGENTS.md와 도구 설명의 "신뢰할 수 없는 데이터" 문구)는 **실제 악성 본문으로 시험하지 않았다.**
- 한글 프롬프트는 `adb input`으로 넣을 수 없어 영어로 시험했다. 한국어 입력 경로는 UI에서 직접 확인해야 한다.
- CLAUDE.md의 "지금까지 알아낸 것"에 위 내용을 반영할 것(아직 반영하지 않음).
