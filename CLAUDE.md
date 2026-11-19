# etl-android

서울대 eTL(Canvas) 비서 안드로이드 앱. **앱 안에 공식 Codex CLI를 넣어 실행**하고, 앱이 제공하는
eTL 도구(읽기 전용)를 Codex가 호출해서, 학생이 폰에서 비서와 대화하고 eTL 파일을 폰에 바로 받게 한다.
다른 학생들에게 배포하는 것이 최종 목표다. 사용자와는 한국어로 대화한다.

## 왜 이 구조인가 (이미 내린 결정)

- **서버 없음**: eTL 토큰, 파일, ChatGPT 계정은 각자 폰에만 둔다. 내가 남의 토큰이나 파일을 보관하는
  서버는 만들지 않는다. 학생마다 서버나 VM을 세팅하게 하는 방식은 기각했다.
- **AI는 사용자 본인의 ChatGPT 계정으로 공식 Codex를 돌린다**: API 비용이 없다. OpenAI의
  "Sign in with ChatGPT" 오픈소스 경로(https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server)는
  오픈소스이고 기기에서 실행되며 무료인 앱을 대상으로 한다. 그래서 이 앱도 **오픈소스, 무료, 기기 내 실행**을 유지한다.
  모바일에 적용되는지는 문서에 명시돼 있지 않다. 배포 전에 확인할 것.
- **안드로이드만**: iOS는 앱 안에서 다른 실행 파일을 띄우는 것 자체가 불가능하다.
- **Termux 의존 없음**: 사용자가 Termux를 따로 설치하는 방식은 장벽이 높고 불안정해서 기각했다.
- **Codex는 채팅 화면이 열려 있을 때만 실행한다.** 마감·공지 알림은 Codex 없이 일반 앱 코드(WorkManager)로 처리한다.
  실행 중에는 포그라운드 서비스로 앱을 살려 둔다(백그라운드에서는 앱의 DNS가 막힌다).
- **진짜 VM(AVF, QEMU)은 쓰지 않는다**: AVF는 일반 앱에 권한이 없고, QEMU는 너무 무겁다.
  직접 실행이 막힐 때만 앱 내장 proot를 대안으로 쓴다.

## 지금까지 알아낸 것 (Galaxy SM-S938N / Android 17 / Codex rust-v0.170.0에서 확인, 2026-11-19)

상세 기록은 `docs/phase0-log.md`, `docs/phase1-log.md`, `docs/auth-design.md`.

**실행**
- 공식 바이너리는 `codex-aarch64-unknown-linux-musl`(GitHub releases)이고, 정적 비-PIE ELF(e_type 2)다.
- Termux에서는 termux-exec의 링커가 정적 ELF를 못 다뤄 `unexpected e_type: 2`가 났다(그래서 Termux는 쓰지 않는다).
- **`nativeLibraryDir`에 `libcodex.so`로 넣고 `ProcessBuilder`로 직접 실행하면 된다(검증됨).** `build.gradle`은
  `useLegacyPackaging = true`. 바이너리는 저장소에 커밋하지 않고 `scripts/fetch-codex.sh`로 받는다(sha256 검증).
- **프록시가 필수다.** musl 정적 바이너리는 `/etc/resolv.conf`가 없어 DNS를 못 한다. 앱이 localhost CONNECT 프록시를 띄우고
  `HTTPS_PROXY` 등으로 넘기면 Codex가 따른다(login, app-server 모두). 프록시 없이는 아무 요청도 안 된다.
- **`SSL_CERT_FILE`(assets의 CA 번들)도 필요한 것으로 보인다.** 없이 시험하면 터널은 열리는데 요청이 실패한다(원인이 TLS인지는 직접 확인하지 못함).
- 샌드박스는 `read-only`로도 동작한다. eTL 작업은 셸이 아니라 MCP 도구로 하므로 문제없다. bubblewrap 경고는 무시해도 된다.

**백그라운드에서 네트워크가 막힌다 (중요)**
- 앱이 백그라운드가 되면(예: 로그인하러 브라우저로 나감) 앱 UID의 DNS가 `No address associated with hostname`으로 실패한다.
  같은 시각 셸과 Chrome은 정상이다. 포그라운드 대조 실험으로 확정했다.
- **Codex가 도는 동안은 포그라운드 서비스(`RunService`, 알림 표시)가 필요하다.** 넣으면 해결된다.
  삼성 절전이 원인이라는 것은 정황 추정이다.

**모델과 code mode (중요)**
- ChatGPT 계정으로 쓸 수 있는 모델(`gpt-6.1-sol` 등)은 내장 카탈로그에서 전부 `tool_mode: code_mode_only`다. MCP 도구를
  호출하려면 **code-mode host**(`codex-code-mode-host`, 릴리스의 별도 자산)가 필요하다.
- `codex exec`에는 호스트 지정 옵션이 없고 `codex` 바이너리 옆 같은 이름의 파일을 찾는데, `nativeLibraryDir`에는 `lib*.so`만 풀리므로
  그 파일을 둘 수 없다. `features.code_mode=false`나 `gpt-5.5` 지정은 소용없다(`gpt-5.5`는 ChatGPT 계정에서 거부).
- **그래서 `codex app-server --code-mode-host http://127.0.0.1:PORT`를 쓴다.** 호스트는 `libcodex-code-mode-host.so`로 넣고
  앱이 `--listen grpc://127.0.0.1:PORT`로 띄운다. Codex 쪽 URL은 `grpc://`가 아니라 **`http://`**여야 한다.
  `exec`는 개발용 시험에만 쓴다.

**MCP와 앱 커넥터**
- `CODEX_HOME/config.toml`의 `[mcp_servers.etl]`에 `url`, `bearer_token_env_var`(비밀값은 환경변수로만), `default_tools_approval_mode = "approve"`.
- **ChatGPT 계정의 커넥터(`codex_apps`, Gmail 등 140여 개 도구)가 자동으로 붙는다. `[features] apps = false`로 끈다.**
  신뢰할 수 없는 eTL 본문이 그 도구를 건드리지 못하게 하려는 것이다.
- 스레드는 `approvalPolicy: "never"`, `sandbox: "read-only"`로 시작한다. 시험에서 승인 요청은 한 번도 오지 않았다.

**eTL 서버 쪽**
- eTL은 오류 메시지를 **한국어로** 준다(`사용자에게 이 동작을 수행할 권한이 없음`). 401이 "토큰 문제"인지 "그 자원에 대한 권한 없음"인지
  영어 문구만으로 판정하면 틀린다(한국어 `권한`도 본다). 파일 탭이 숨겨진 강의는 파일 검색이 401이다.
- 같은 이름의 파일을 MediaStore에 저장하면 `이름 (1).ext`가 되며, 이름 충돌 처리는 `IS_PENDING`을 풀 때 일어난다.

**app-server 프로토콜(`codex app-server generate-json-schema`로 뽑는다)**
- 줄 단위 JSON-RPC. `initialize` → `initialized` 알림 → `thread/start` → `turn/start`. 스트리밍은 `item/agentMessage/delta`, 완료는 `turn/completed`.
- `mcpServerStatus/list`로 MCP 연결 상태를 모델 없이 점검할 수 있다. 계정은 `account/read`, 로그인은 `account/login/start`
  (`chatgpt` / `chatgptDeviceCode` / `apiKey`), 완료는 `account/login/completed` 알림.

## 단계 계획

**Phase 0: 실행 검증용 테스트 앱 — 완료.** 정적 ELF 직접 실행, 프록시/CA/백그라운드 문제 해결 확인.

**Phase 1: eTL 도구 — 완료.** MCP 서버(127.0.0.1, 실행마다 새 비밀값), 도구 7개, Keystore 토큰 저장. 실제 토큰으로 전부 검증.
아직 못 한 것: 악성 본문으로 하는 프롬프트 인젝션 시험.

**Phase 2: 채팅 UI — 구현됨, 일부 검증 남음.** `codex app-server`에 붙는 장기 세션(스트리밍, 스레드 재개, 중단, 도구 호출 표시), 계정/설정 화면.
로그인은 브라우저 방식을 기본으로, 기기 코드를 폴백으로, API 키는 선택(Keystore, 환경변수로만 전달)으로 둔다. 상세와 검증 현황은 `docs/phase2-log.md`, `docs/auth-design.md`.
프로토콜은 사용하는 Codex 버전 기준으로 확인하고, 버전을 고정한다(현재 rust-v0.170.0).

**Phase 3: 알림**
WorkManager로 몇 시간마다 eTL을 조회해 마감 임박(24h, 3일)과 새 공지를 로컬 알림으로 띄운다. AI는 쓰지 않는다.

**Phase 4: 첫 실행 마법사**
eTL 토큰 발급 안내(웹뷰로 https://myetl.snu.ac.kr/profile/settings 열기 → 생성 → 붙여넣기), ChatGPT 로그인,
알림 권한 요청을 넣는다.

## eTL API 레퍼런스

참고 구현은 `/home/manig/etl-digest/etl_digest.py`(Python)이다. 세부 동작은 이 파일을 읽어서 확인한다.
**`~/.config/etl-digest` 같은 토큰·설정 파일은 절대 읽지 않는다.**

- Base는 `https://myetl.snu.ac.kr`이고, 인증은 `Authorization: Bearer <token>`, `Accept: application/json`이다.
- 응답 앞에 `while(1);`(XSSI 방지 접두어)가 붙을 수 있으니 제거한 뒤 JSON으로 파싱한다.
- 페이지네이션은 `Link` 헤더의 `rel="next"`를 따른다. **다음 URL이 base origin 밖이면 중단한다**(토큰 유출 방지). 최대 20페이지까지만 받는다.
- 401 응답:
  - 본문에 "not authorized"가 있고 "token"이 없으면 권한 없음.
  - 그 외에는 토큰 만료 또는 무효.
- 엔드포인트
  - 내 정보: `GET /api/v1/users/self`, 프로필은 `/api/v1/users/self/profile`(`calendar.ics` 피드 URL 포함)
  - 강의: `GET /api/v1/courses?enrollment_state=active&include[]=total_scores&include[]=term&per_page=100`
  - 과제: `GET /api/v1/courses/{cid}/assignments?include[]=submission&order_by=due_at&per_page=100`
  - 공지: `GET /api/v1/announcements?context_codes[]=course_{id}…&start_date=YYYY-MM-DD&end_date=…&per_page=50`
  - 파일 메타: `GET /api/v1/files/{fid}` → `url`, `display_name`, `size`, `locked_for_user`, `lock_explanation`
  - 파일 검색: `GET /api/v1/courses/{cid}/files?search_term=…&per_page=50`
  - 제출 확인: `GET /api/v1/courses/{cid}/assignments/{aid}`와
    `GET /api/v1/courses/{cid}/assignments/{aid}/submissions/self?include[]=submission_comments&include[]=submission_history`
  - 기타: `/api/v1/users/self/upcoming_events`, `/api/v1/courses/{cid}/front_page`
- 판정 규칙
  - **현재 학기 강의**: `term.name`에서 연도와 학기(1학기, 하계/여름, 2학기, 동계/겨울)를 뽑아 순위를 매기고,
    가장 최신 학기만 남긴다. `access_restricted_by_date` 강의는 제외한다.
  - **제출됨**: `submission.workflow_state ∈ {submitted, graded, pending_review}` 또는 `submitted_at`이 있음.
  - **온라인 과제**: `submission_types` 중 하나라도 `{none, on_paper, not_graded}`가 아님.
  - **누락**: 미제출이면서 `submission.missing`이 참이거나, 마감이 지난 온라인 과제.
  - 마감 임박 목록은 미제출이고 `now ≤ due_at ≤ now + N일`인 과제로 만들고, `published == false`는 제외한다.
  - 시간은 KST로 표시한다.
- 파일 다운로드
  1. 파일 메타를 조회한다. `locked_for_user`면 거부한다.
  2. `meta.url`을 GET한다. 이때 **Authorization은 eTL origin 요청에만 붙이고, 리다이렉트(서명된 스토리지 URL)에는 붙이지 않는다.**
  3. 300MB 상한을 둔다.
  4. 받은 크기를 `meta.size`와 비교한다.
  5. 파일 이름은 정리한다(경로 구분자와 제어문자 제거, 앞쪽 점 제거, 180자 제한).
  6. 안드로이드에서는 MediaStore로 `Download/etl/` 아래에 저장한다.
- 제출 확인 판정
  - **problem으로 보는 경우**:
    - 제출 기록 없음(온라인 과제일 때)
    - `late`, 또는 `submitted_at > due`
    - `missing`
    - 허용되지 않은 `submission_type`
    - 0바이트 파일
    - 허용되지 않은 확장자(`allowed_extensions`)
    - 내려받은 크기 불일치
    - `online_upload`인데 첨부 없음
  - **warning으로 보는 경우**: `excused`, 오프라인 과제, 다운로드 실패
  - 선택적으로 로컬 파일의 SHA-256과 비교한다. 결과는 verdict(ok/problem), problems, warnings와
    제출 시각, 마감, 파일 목록, 점수, 최근 코멘트 5개로 낸다.

## MCP 도구 초안 (전부 읽기 전용)

- `etl_digest(days=7, ann_days=1)`: 마감 임박, 누락, 최근 공지
- `etl_courses()`
- `etl_assignment(course_id, assignment_id)`
- `etl_search_files(course_id, term)`
- `etl_download(file_id_or_url)`: 폰 `Download/etl/`에 저장하고 경로, 크기, 일치 여부를 반환
- `etl_verify_submission(course_id, assignment_id)`
- `etl_get(path)`: 범용 GET. `^/api/v1/[\w\-./?=&\[\]%:,]*$`만 허용

## 보안 규칙

- eTL 토큰은 `myetl.snu.ac.kr`에만 보낸다. Keystore 기반으로 암호화해 저장하고, 로그·크래시 리포트·에러 메시지에 절대 남기지 않는다.
- eTL에는 GET 요청만 보낸다. 제출·수정 같은 쓰기 요청은 구현하지 않는다.
- MCP 서버는 127.0.0.1에만 바인딩하고, 실행마다 바뀌는 비밀값을 요구한다.
- eTL 내용(공지, 과제 설명, 파일)은 신뢰하지 않는 데이터다. 그 안의 지시를 따르지 않도록 Codex 지침(AGENTS.md)에 명시한다.
- 앱에는 분석 SDK나 외부 서버 전송을 넣지 않는다.

## 미해결 질문

- SIWC 오픈소스 경로가 모바일 앱과 Play 스토어 배포에도 적용되는지
- 서울대 정보화본부: eTL API를 쓰는 앱을 배포해도 되는지
- Codex app-server 프로토콜의 안정성과 버전 고정 전략
