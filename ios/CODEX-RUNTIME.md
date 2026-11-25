# iOS 앱 내부 Codex 실행

목표는 Android처럼 공식 Codex 엔진을 앱 안에서 실행하면서 ChatGPT 계정으로 로그인하는 것이다. 설치한 뒤 PC나 중계 서버를 켜 둘 필요가 없다. AI 추론과 eTL 접속에는 인터넷이 필요하다.

## 구현과 검증 상태

- 공식 `codex-app-server::in_process`를 호출하는 Rust 정적 라이브러리와 C 인터페이스를 작성했다. Swift와는 메모리 큐로 JSON-RPC 요청·이벤트를 주고받는다.
- Codex가 제공하는 `account/login/start`의 브라우저 로그인을 사용하며, OAuth 콜백·토큰 갱신도 엔진이 처리한다. iOS용 native Keychain 백엔드를 활성화했고 파일 저장으로 대체하지 않는다.
- Swift 채팅 화면에 답변 스트리밍·대화 이어가기·eTL 도구 호출·중단을 연결했다. eTL 쓰기 승인은 기존 실행기를 그대로 사용한다.
- Swift 로직 테스트 744개가 통과했다. 여기에는 실제 계정 없이 검증하는 통신·도구 호출·대화 복원·중단 테스트가 포함된다.
- **네이티브 iOS 컴파일·링크·실행 및 실제 SNU Edu 로그인은 추가 검증 중이다. 이 문서 자체는 로그인 성공을 보장하지 않는다.**

기존 **iOS Device IPA** 워크플로는 SIWC 인증을 사용하는 앱을 만든다. 내장 Codex를 검증하는 동안 두 빌드를 구분하기 위해 새 워크플로를 사용한다.

## 네이티브 설치용 IPA

Actions → **iOS Native Codex IPA** → **Run workflow**.

1. Swift 테스트를 실행한다.
2. Android와 같은 Codex `rust-v0.170.0`의 커밋 `29eb79e9866b3262a777fdf8195c1b9e63684c91`을 SHA-256 검증 후 받는다.
3. Rust 1.95.0으로 iOS arm64용 정적 라이브러리를 빌드한다.
4. 앱 타깃에만 `YOUNGMIN_NATIVE_CODEX`를 활성화해 연결하고, 서명하지 않은 build 23 IPA를 만든다.
5. 성공하면 `youngmin-native-codex-ipa-*` 아티팩트의 `Youngmin-unsigned.ipa`를 Windows Sideloadly로 설치한다. 설치 과정은 [INSTALL-WINDOWS.md](INSTALL-WINDOWS.md)를 따른다.

빌드에는 Apple 개발자 계정이나 사용자 ChatGPT 토큰이 필요 없다. 실제 로그인은 설치한 iPad의 공식 OpenAI 로그인 페이지에서 진행한다. 학교 워크스페이스 정책은 그대로 적용된다.

## 현재 지원 범위

내장 엔진은 별도 자식 프로세스·셸·PTY·code-mode host를 실행하지 않도록 설정한다. 모델 목록도 고정한 공식 엔진 카탈로그에서 별도 code-mode host를 요구하지 않는 모델만 보여 준다. 현재 카탈로그 기준 GPT-5.5가 해당한다. 최신 모델 전체를 지원하려면 추가 실행 환경이 필요하다.

기존 SIWC 토큰을 Codex 서버로 보내거나 로그인 client ID만 교체하지 않는다. 공식 Codex의 인증과 실제 엔진을 함께 사용한다. API 키 모드는 기존 Swift Responses 클라이언트를 사용한다.

## 빌드 재현

가벼운 컴파일 검사: Actions → **iOS Codex Runtime Probe** → component `bridge`. 원본 라이브러리만 검사하려면 `upstream`.

공식 릴리스 소스에서 workspace 버전과 Cargo.lock 로컬 패키지 버전의 차이를 보정한다. 외부 의존성 버전·체크섬은 유지한다. 사용하지 않는 V8도 같은 고정 커밋의 검증된 아카이브에서 Cargo 정보를 읽도록 하여 Chromium 하위 저장소 다운로드를 피한다. 이 앱의 Rust 실행 의존성에는 V8이 포함되지 않는다.

참고: [공식 app-server 문서](https://learn.chatgpt.com/docs/app-server.md), [고정한 공식 소스](https://github.com/openai/codex/tree/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/app-server/src/in_process.rs).
