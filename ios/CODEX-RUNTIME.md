# iOS 앱 내부 Codex 실행

목표는 Android처럼 공식 Codex 엔진을 앱 안에서 실행하면서 ChatGPT 계정으로 로그인하는 것이다. 설치한 뒤 PC나 중계 서버를 켜 둘 필요가 없다. AI 추론과 eTL 접속에는 인터넷이 필요하다.

## 구현과 검증 상태

- 공식 `codex-app-server::in_process`를 호출하는 Rust 정적 라이브러리와 C 인터페이스를 작성했다. Swift와는 메모리 큐로 JSON-RPC 요청·이벤트를 주고받는다.
- Codex가 제공하는 `account/login/start`의 브라우저 로그인을 사용하며, OAuth 콜백·토큰 갱신도 엔진이 처리한다. iOS용 native Keychain 백엔드를 활성화했고 파일 저장으로 대체하지 않는다.
- Swift 채팅 화면에 답변 스트리밍·대화 이어가기·eTL 도구 호출·중단을 연결했다. eTL 쓰기 승인은 기존 실행기를 그대로 사용한다.
- Swift 로직 테스트 745개가 통과했다. 여기에는 실제 계정 없이 검증하는 통신·도구 호출·대화 복원·중단 테스트가 포함된다.
- build 23은 iOS arm64 컴파일·링크·패키징을 통과했고 사용자 기기에서 로그인 성공이 보고됐다. GPT-5.5만 남기던 필터로 모델 목록이 비는 문제가 확인됐다.
- 시스템 JavaScriptCore로 비동기 도구 실행·오류·중단·격리 테스트 10개를 macOS와 iOS 시뮬레이터에서 통과했다. 이를 Codex에 연결한 build 25는 별도로 검증한다.
- build 25에서 SNU Edu 계정으로 실제 iPad 채팅 성공이 사용자에게 확인됐다. eTL 전체 도구와 장기 백그라운드 동작까지 검증됐다는 뜻은 아니다.
- build 26부터 실행기를 내장 QuickJS로 교체한다. Apple 비공개 시간 제한 함수를 제거했고, 로컬 Linux에서 실행·취소·메모리 제한을 포함한 16개 테스트가 통과했다. macOS·iOS·Codex 연결과 기기용 빌드는 CI에서 별도로 확인한다.

### 두 번째 메시지부터 실패하던 문제 (2026-11-26)

- 증상: 대화의 첫 메시지는 되는데 같은 대화의 다음 메시지(추천 질문에 답한 뒤 포함)가 모두 실패했다.
- 원인: 브리지가 `InProcessStartArgs`에 `state_db: None`을 넘겼다. 두 번째 메시지부터 앱은 `thread/resume`을 부르는데, 고정 버전의 대화 기록은 페이지 방식이라 상태 DB 없이 기록을 읽지 못해 오류가 났다(`excludeTurns: true`여도 내부 조회는 일어난다). 앱은 같은 스레드 ID로 계속 재개를 시도해 매번 실패했다. Android의 독립 `codex app-server`는 시작할 때 DB를 직접 연다.
- 수정: 브리지가 공식 app-server와 같이 `codex_rollout::state_db::try_init`으로 CODEX_HOME 아래 상태 DB를 열어 넘긴다. 손상된 DB는 공식 서버처럼 CODEX_HOME의 `db-backups`로 옮기고 rollout 파일에서 다시 만든다. 그래도 열지 못하면 DB 없이 돌지 않고 "대화 기록 저장소를 열지 못했어요" 오류로 멈춘다. 진단 로그 DB 기록(`log_db`)은 재개에 필요하지 않아 계속 끈다.
- 앱: 엔진이 `thread/resume`을 오류 코드로 거절하면 새 스레드로 이어 가고 "이전 대화의 맥락은 이어지지 않아요" 안내를 한 번 남긴다. 시간 초과·연결 종료는 그대로 실패하고 ID를 유지한다. 교체된 스레드 ID는 대화 기록에 함께 남겨 대화를 지울 때 엔진 기록도 지운다.
- 검증: Actions → **iOS JavaScript Tool Probe**의 `codex-thread-resume` 작업이 macOS에서 실제 브리지를 C 인터페이스로 띄우고, 로컬 가짜 모델로 첫 턴 → 같은 실행 안 `thread/resume` → 둘째 턴 → 브리지 재시작 뒤 `thread/resume` → 셋째 턴(앞 대화가 요청에 포함되는지 확인) → `thread/delete`를 돈다(`bridge/src/resume_tests.rs`). 계정·외부 네트워크가 필요 없다. 이 작업은 IPA 빌드 전 실행 검증에도 포함된다. 실제 iPad에서의 확인은 따로 해야 한다.

기존 **iOS Device IPA** 워크플로는 SIWC 인증을 사용하는 앱을 만든다. 내장 Codex를 검증하는 동안 두 빌드를 구분하기 위해 새 워크플로를 사용한다.

## 대화마다 따로 답하기 (2026-11-27)

- Android와 같이 답변 중 상태를 대화별로 둔다(`ChatController.runningChats`, 대화별 활동 줄). 답하는 동안 다른 대화를 열고, 새 대화를 시작하고, 다른 대화에서 보낼 수 있으며 동시에 도는 답변 수에 제한이 없다. 멈춤은 지금 대화의 답변만 멈춘다. 입력창 글과 보낼 첨부는 대화마다 따로 둔다.
- `CodexEngine` 하나가 여러 대화의 턴을 함께 돌린다. 턴마다 이벤트를 구독하고 자기 `threadId`의 알림·도구 호출만 처리한다. 스레드 없는 서버 요청은 한 턴만 거절한다. API 키 모드는 턴마다 `ResponsesEngine`을 새로 만든다.
- 앱 도구 실행기를 `ToolOrigin.$chatId`(ETLKit의 TaskLocal)로 감싸 부른다. 받은 파일·바뀐 데이터 알림(`EtlEvents`)과 쓰기 승인 요청(`WriteApprovalGate.Request.origin`)이 그 도구를 부른 대화에 붙는다.
- 서랍의 답하는 대화에 작은 `ProgressView`(접근성 값 "답변 중"). 답하는 중에도 이름 바꾸기가 되고, 지우면 답변을 먼저 멈춘 뒤 앱 기록과 엔진 스레드를 지운다.
- 보지 않는 대화가 끝나면 입력창 위 막대 "‘…’ 답변이 끝났어요 · 보기", 채팅 화면이 안 보이면(앱이 뒤로 감, 다른 화면) 로컬 알림(누르면 그 대화). 실패는 "‘…’ 답변을 마치지 못했어요".
- 쓰기 승인 창은 그 요청을 낸 대화를 볼 때만 뜨고, 다른 대화의 요청은 위쪽 배너 "다른 대화에서 확인을 기다려요"(누르면 그 대화로). 대화를 옮겨도 취소하지 않는다. 화면 이탈·90초·턴 끝은 그대로 거절한다(턴 끝은 그 대화의 요청만).
- 답하는 대화가 있는 동안 `beginBackgroundTask`를 잡아 앱이 뒤로 가도 iOS가 허락하는 만큼 더 돈다(Android 포그라운드 서비스에 해당). 시간이 다 되면 놓기만 한다.
- 검증: `swift test`에 판정(`ChatTurnsTests`), 대화별 승인 거절(`WriteApprovalGateTests`), TaskLocal 라우팅(`ToolOriginTests`), 가짜 엔진으로 두 스레드 동시 턴(`CodexEngineConcurrencyTests`)을 더했다. 실제 아이폰·아이패드에서의 동시 답변은 아직 확인하지 못했다.

## 네이티브 설치용 IPA

Actions → **iOS Native Codex IPA** → **Run workflow**. 빠른 기기 테스트 옵션이 기본이다.

1. 빠른 빌드는 Rust 최적화와 이 작업 안의 중복 Swift 테스트를 생략한다. Swift 로직 검증은 별도 iOS CI에서 실행한다.
2. Android와 같은 Codex `rust-v0.170.0`의 커밋 `29eb79e9866b3262a777fdf8195c1b9e63684c91`을 SHA-256 검증 후 받는다.
3. QuickJS 실행기와 Codex 세션·도구 delegate 연결 테스트가 통과하면, Rust 1.95.0으로 iOS arm64용 정적 라이브러리를 빌드한다.
4. 앱 타깃에만 `YOUNGMIN_NATIVE_CODEX`를 활성화해 연결하고, 서명하지 않은 Release 구성 build 26 IPA를 만든다.
5. 성공하면 아티팩트의 `Youngmin-iPad-test.ipa` 한 파일을 Windows Sideloadly로 설치한다. 바깥쪽 ZIP으로 다시 묶지 않고 업로드한다. 설치 과정은 [INSTALL-WINDOWS.md](INSTALL-WINDOWS.md)를 따른다.

기기용 IPA 생성을 우선한다. 추가 네이티브 시뮬레이터 실행 검증은 별도로 진행할 수 있으며, 새 실행기의 실제 SNU 로그인·채팅은 사용자 iPad에서 다시 확인해야 한다.

빌드에는 Apple 개발자 계정이나 사용자 ChatGPT 토큰이 필요 없다. 실제 로그인은 설치한 iPad의 공식 OpenAI 로그인 페이지에서 진행한다. 학교 워크스페이스 정책은 그대로 적용된다.

## 현재 지원 범위

내장 엔진은 별도 자식 프로세스·셸·PTY·code-mode host를 실행하지 않도록 설정한다. Code Mode는 앱에 소스와 함께 포함한 QuickJS 인터프리터를 사용하는 앱 내부 세션 provider에 연결한다. 셀마다 JavaScript context를 분리하고, 허용된 도구만 Codex가 제공한 delegate를 통해 호출하므로 eTL 쓰기 승인도 기존 경로를 따른다. 셀 사이의 `store`/`load`는 해당 Codex 세션 안에서만 공유한다. 중단·대기·비동기 도구 반환과 실행 시간 제한을 제공한다.

GPT-5.5 고정값과 모델 화이트리스트를 제거하고, 공식 엔진이 계정에 대해 반환한 모델 목록과 기본 선택을 사용한다. GPT-5.6·6·6.1 계열을 목록에서 임의로 제외하지 않는다. 실제 제공 모델과 사용 가능량은 서버 계정 설정에 따른다.

앱 내부 엔진은 시작할 때 자격 증명이나 외부 요청 없이 Code Mode 세션을 만들고, JavaScript에서 네이티브 도구를 호출해 `42`를 받는 자체 검사를 수행한다.

기존 SIWC 토큰을 Codex 서버로 보내거나 로그인 client ID만 교체하지 않는다. 공식 Codex의 인증과 실제 엔진을 함께 사용한다. API 키 모드는 기존 Swift Responses 클라이언트를 사용한다.

## 빌드 재현

가벼운 컴파일 검사: Actions → **iOS Codex Runtime Probe** → component `bridge`. 원본 라이브러리만 검사하려면 `upstream`.

공식 릴리스 소스에서 workspace 버전과 Cargo.lock 로컬 패키지 버전의 차이를 보정한다. 외부 의존성 버전·체크섬은 유지한다. 사용하지 않는 V8도 같은 고정 커밋의 검증된 아카이브에서 Cargo 정보를 읽도록 하여 Chromium 하위 저장소 다운로드를 피한다. 이 앱의 Rust 실행 의존성에는 V8이 포함되지 않는다.

참고: [공식 app-server 문서](https://learn.chatgpt.com/docs/app-server.md), [고정한 공식 소스](https://github.com/openai/codex/tree/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/app-server/src/in_process.rs).

MXC 의존성에는 데스크톱 전용 정책 로그 함수 참조 두 곳에 플랫폼 조건을 추가한다. iOS 실행 요청을 거부하는 기존 `ensure_host_supported` 검사는 유지한다. 같은 고정 커밋 소스를 검증해 사용하며, 샌드박스 실행을 허용하는 우회 경로는 추가하지 않는다.

iOS에서 데스크톱 인증서 파일을 찾을 수 없으면 기존 잠금 파일의 Mozilla 인증서 목록을 사용한다. 명시적인 SSL_CERT_FILE/SSL_CERT_DIR 설정은 그대로 우선하며, 인증서 체인과 호스트 이름 검증을 수행한다.

## 실행기 교체와 배포 준비

QuickJS 2026-06-04의 MIT 소스를 `javascript/vendor/quickjs`에 포함했다. 원본 URL·SHA-256은 `SOURCE.txt`, 라이선스는 `LICENSE`에 있다. OS·파일·네트워크 모듈(`quickjs-libc`), 외부 모듈 로더와 JIT는 연결하지 않는다. 실행 취소는 자체 인터프리터의 공개 `JS_SetInterruptHandler`로 처리하고, 셀마다 64MiB JS 힙과 120초 제한을 둔다.

`check-ios-private-apis.py`가 이전 JavaScriptCore SPI 두 개가 소스와 최종 실행 파일의 import에 남아 있지 않은지 검사한다. 이 검사는 알려진 두 함수의 재유입 검사이며 앱 전체의 App Review 적합성 인증은 아니다.

가상 eTL 사용법과 TestFlight에 남은 준비는 [TESTFLIGHT.md](TESTFLIGHT.md)를 따른다.
