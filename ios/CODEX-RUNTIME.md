# iOS 앱 내부 Codex 실행

목표는 Android처럼 공식 Codex 엔진을 앱 안에서 실행하면서 ChatGPT 계정으로 로그인하는 것이다. 설치한 뒤 PC나 중계 서버를 켜 둘 필요가 없어야 한다. AI 추론과 eTL 접속에는 인터넷이 필요하다.

현재 배포한 IPA는 Swift Responses 클라이언트와 Sign in with ChatGPT를 사용한다. 이 문서와 빌드 검증을 추가해도 기존 앱의 로그인 방식은 바뀌지 않는다. SNU Edu 로그인 성공 여부는 실제 기기에서 별도로 확인해야 한다.

## 첫 검증: 공식 엔진의 iOS 컴파일

Actions → **iOS Codex Runtime Probe** → **Run workflow**.

- Android와 같은 Codex `rust-v0.170.0`의 커밋 `29eb79e9866b3262a777fdf8195c1b9e63684c91`을 SHA-256 검증 후 받는다.
- Rust 1.95.0으로 `codex-app-server` 라이브러리를 `aarch64-apple-ios` 대상으로 검사한다.
- 공식 `app-server::in_process` API는 별도 실행 파일이나 로컬 서버 없이 메모리 채널로 요청·이벤트를 주고받는다.
- 빌드 로그는 `ios-codex-probe-*` 아티팩트에 남긴다. 이 워크플로에는 Apple 개발자 계정이나 사용자 ChatGPT 토큰이 필요 없다.

컴파일 성공만으로 동작하는 앱이 완성되는 것은 아니다. 다음 작업이 남는다.

1. Swift에서 호출할 C 인터페이스와 정적 라이브러리 링크, 실제 초기화·종료 검증.
2. 앱 내부 Codex 인증과 Keychain 저장·갱신 연결. 학교 워크스페이스 정책은 그대로 적용한다.
3. 기존 eTL 도구를 Codex 요청·응답에 연결하고 쓰기 승인을 유지.
4. iOS가 지원하지 않는 자식 프로세스·셸·PTY를 호출하지 않도록 실행 기능을 구성. 해당 모델이 요구하는 code-mode 실행 방식도 검증.
5. 시뮬레이터 테스트와 실기기 로그인·대화·토큰 갱신 검증 후 IPA 생성.

기존 SIWC 토큰을 Codex 서버로 보내거나, 로그인 client ID만 바꾸는 방식으로 대체하지 않는다. 공식 Codex 인증과 실제 엔진을 함께 통합한다.

참고: [공식 app-server 인증 문서](https://learn.chatgpt.com/docs/app-server.md), [고정한 공식 소스](https://github.com/openai/codex/tree/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/app-server/src/in_process.rs).
