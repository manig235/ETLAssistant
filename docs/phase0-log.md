# Phase 0 실행 검증 기록

앱(`kr.snu.etlassistant.phase0`)에서 각 단계를 실행하고 결과를 기록한다.
로그는 앱의 **Copy log** 버튼으로 복사해 아래 "상세 로그"에 붙인다.
로그에 토큰이나 인증 코드가 섞여 있으면 붙이기 전에 지운다.

## 환경

| 항목 | 값 |
|---|---|
| 기기 / Android 버전 (SDK) | Samsung SM-S938N / Android 17 (SDK 37), arm64-v8a |
| Codex 버전 (릴리스 태그) | rust-v0.170.0 (`codex-cli 0.170.0`) |
| 바이너리 | `codex-aarch64-unknown-linux-musl` → `libcodex.so` (ELF e_type 2, static) |
| 앱 빌드 (commit / 날짜) | git 저장소 아님 / 2026-11-19. 2차 빌드부터 `RunService`(포그라운드 서비스) 포함 |

토글 표기: **P** = 프록시 사용, **S** = SSL_CERT_FILE 지정, **D** = `--sandbox danger-full-access`. 꺼짐은 `-`로 적는다(예: `P S -`).

## 결과

| # | 단계 | 토글 (P S D) | 결과 (성공/실패) | 종료 코드 | 에러 메시지 / 비고 | 날짜 |
|---|---|---|---|---|---|---|
| 1 | `codex --version` | - - - | **성공** | 0 | `codex-cli 0.170.0`, 5ms. nativeLibraryDir에서 직접 실행하면 정적 ELF가 그대로 돈다(`unexpected e_type` 없음). | 11-19 |
| 2 | `codex login` (URL 추출 → 브라우저 → localhost:1455 콜백) | - - - | **실패** | 1 | URL 추출, 브라우저 열기, 콜백 수신까지는 성공. 토큰 교환에서 `error sending request for url (https://auth.openai.com/oauth/token)`. 22초 걸림(DNS 타임아웃으로 추정). 프록시 없이는 musl이 DNS를 못 한다. | 11-19 |
| 2 | 〃 | P S - (`RunService` 없음) | **실패** | 1 | 같은 에러. 프록시 로그: `CONNECT auth.openai.com:443 -> DNS 실패: No address associated with hostname`. **앱이 백그라운드(Chrome이 앞)일 때** 앱 UID의 DNS가 실패. 같은 시각 셸과 Chrome에서는 정상 해석. 2회 재현. | 11-19 |
| 2 | 〃 | P S - (`RunService` 있음) | 미실행 | | localhost 콜백 방식은 서비스 적용 후 재시험하지 않음. | |
| 2a | `codex login --device-auth` | P S - (`RunService` 없음) | **실패** | 1 | 코드 발급은 성공(앱이 포그라운드, `CONNECT ... OK`). 브라우저로 나간 약 10초 뒤 폴링에서 DNS 실패 → `error sending request for url (.../api/accounts/deviceauth/token)`. **포그라운드 vs 백그라운드 대조로 원인 확정.** | 11-19 |
| 2a | 〃 | P S - (`RunService` 있음) | **성공** | 0 | 앱이 백그라운드인 약 2.5분 동안 `CONNECT ... OK`. 사용자가 브라우저에서 코드를 승인한 뒤 `Successfully logged in`. 150초. | 11-19 |
| 2b | `codex login status` | - - - (로그인 전) | 예상대로 | 1 | `Not logged in`, 12ms | 11-19 |
| 2b | 〃 | (로그인 후) | 미확인 | | 실행은 했으나 출력을 확인하지 못함. 아래 exec가 성공했으므로 인증은 유효. | |
| 3 | `codex exec` ("exec 안녕" 버튼) | - - - | **실패** | (Stop) | 프록시 로그 없음. `error sending request for url (https://chatgpt.com/backend-api/ps/mcp)`가 5초 간격으로 반복. 25초 뒤 수동 Stop. | 11-19 |
| 3 | 〃 | P - - | **실패** | 1 | 프록시 터널은 `CONNECT chatgpt.com:443 ... OK`인데 `Reconnecting... 5/5` 후 `workspace routing discovery failed`, `failed to refresh available models`로 종료. 17초. TLS 에러 문구는 로그에 없음. CA 번들(SSL_CERT_FILE) 없음이 원인으로 **추정**(직접 확인 안 함). | 11-19 |
| 3 | 〃 | P S - | **성공** | 0 | 응답 `안녕`, 10초, 토큰 14,365 사용. 경고 2건(아래). | 11-19 |
| 3 | 〃 | P S D | **성공** | 0 | 응답 `안녕`, 5.4초, 토큰 14,355 사용. 인자는 `exec --skip-git-repo-check --sandbox danger-full-access '안녕이라고만 답해'`. 로그에서 bubblewrap 경고는 보이지 않음(code-mode-host 경고만 있음). | 11-19 |
| 3 | 〃 | - S D | **실패** | (Stop) | `- - -`와 같은 증상: `chatgpt.com/backend-api/ps/mcp` 요청 실패 반복, `ERROR: Reconnecting... 2/5`에서 50초 넘게 진행 중이라 수동 Stop. **프록시가 필수.** | 11-19 |

### exec 경고 (P S -, 기본 샌드박스)

- `bubblewrap` 관련 경고가 나옴(로그 앞부분은 화면에서 잘려 정확한 문구 미확보. "use the bundled bubblewrap in the meantime" 부분만 확인). 샌드박스가 실제로 적용됐는지, 무시하고 진행했는지 D 토글 비교로 확인 필요.
- `warning: Code Mode is unavailable because failed to spawn code-mode host .../lib/arm64/codex-code-mode-host: host executable was not found.` 코드 모드 기능을 못 쓴다는 경고이고 eTL 용도에는 영향이 작을 것으로 보이나, 필요한지 판단 필요.

## 핵심 발견

1. **정적 ELF 직접 실행 가설은 맞다.** nativeLibraryDir의 `libcodex.so`를 `ProcessBuilder`로 실행하면 된다(Android 17, Samsung).
2. **프록시 방식은 동작한다.** `HTTPS_PROXY`를 지정하면 Codex가 로그인과 exec 모두에서 앱 프록시를 따른다(아래 호스트 목록). `SSL_CERT_FILE`로 지정한 CA 번들로 TLS도 통과했다.
3. **앱이 백그라운드가 되면 앱 UID의 DNS가 실패한다.** 로그인은 브라우저로 나가야 해서 반드시 백그라운드가 되므로, **Codex 실행 중에는 포그라운드 서비스가 필요하다.** `RunService`(foregroundServiceType=dataSync, 알림 표시)를 넣자 해결됐다. 근거는 포그라운드/백그라운드 대조 로그이며, 삼성 절전(Freecess)이 원인이라는 것은 정황 추정이고 직접 확인하지는 않았다.
4. 로그인은 `login --device-auth`로 성공. 코드는 하이픈 포함 9자(`XXXX-XXXX`)이고 15분 유효.

## 프록시로 접속한 호스트

`[proxy] CONNECT host:port` 로그에서 모은다. 프록시를 켰는데 여기 안 보이는 접속이 있다면(직접 DNS 실패 등),
Codex에 프록시 환경변수를 따르지 않는 경로가 있다는 뜻이다.

| 호스트:포트 | 어느 단계에서 | 비고 |
|---|---|---|
| auth.openai.com:443 | login, login --device-auth | 해석 결과가 IPv6(2606:4700:...)이었음 |
| chatgpt.com:443 | exec | 여러 번 접속 |
| ab.chatgpt.com:443 | exec | |
| sdmntprcentralus.oaiusercontent.com:443 | exec | 종료 직전. 다른 실행에서는 sdmntprsouthcentralus, sdmntprwestus 변형도 나옴 |

exec 중 프록시를 거치지 않은 직접 DNS 실패 로그는 보이지 않았다. 다만 로그 앞부분은 확인하지 못했다.

## 남은 확인

- `P - -` 실패의 정확한 원인(TLS 인증서 검증인지) 확인. 지금은 추정.
- 기본 샌드박스(D 없음)에서 나온 bubblewrap 경고의 정확한 문구와 영향. D를 켜면 경고가 보이지 않았고 5.4초로 더 빨랐다. 다만 eTL 용도에서는 샌드박스를 쓸 일이 없어 D 사용이 현실적이다.
- code-mode-host 경고는 모든 실행에서 나오지만 응답에는 영향이 없었다.
- exec 중 `chatgpt.com/backend-api/ps/mcp`(원격 MCP 커넥터 경로)에도 접속한다. Phase 1에서 앱 MCP 서버를 등록할 때 이 경로와 충돌하지 않는지 볼 것.
- localhost:1455 콜백 방식 `login`이 `RunService`와 함께 성공하는지(device-auth가 되므로 우선순위는 낮음)
- 백그라운드에서 포그라운드 서비스 없이도 되는 조건이 있는지는 조사하지 않음. 알림 권한을 거부해도 서비스가 유지되는지 미확인.

## 상세 로그

(로그 원문은 앱의 Copy log로 복사해 붙인다. 이번 세션은 화면 캡처로만 확인했다. 인증 코드와 OAuth state 값은 기록하지 않는다.)
