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

기존 **iOS Device IPA** 워크플로는 SIWC 인증을 사용하는 앱을 만든다. 내장 Codex를 검증하는 동안 두 빌드를 구분하기 위해 새 워크플로를 사용한다.

## 네이티브 설치용 IPA

Actions → **iOS Native Codex IPA** → **Run workflow**. 빠른 기기 테스트 옵션이 기본이다.

1. 빠른 빌드는 Rust 최적화와 이 작업 안의 중복 Swift 테스트를 생략한다. Swift 로직 검증은 별도 iOS CI에서 실행한다.
2. Android와 같은 Codex `rust-v0.170.0`의 커밋 `29eb79e9866b3262a777fdf8195c1b9e63684c91`을 SHA-256 검증 후 받는다.
3. QuickJS 실행기와 Codex 세션·도구 delegate 연결 테스트가 통과하면, Rust 1.95.0으로 iOS arm64용 정적 라이브러리를 빌드한다.
4. 앱 타깃에만 `YOUNGMIN_NATIVE_CODEX`를 활성화해 연결하고, 서명하지 않은 Release 구성 build 26 IPA를 만든다.
5. 성공하면 아티팩트의 `Youngmin-iPad-test.ipa` 한 파일을 Windows Sideloadly로 설치한다. 바깥쪽 ZIP으로 다시 묶지 않고 업로드한다. 설치 과정은 [INSTALL-WINDOWS.md](INSTALL-WINDOWS.md)를 따른다.

기기용 IPA 생성을 우선한다. 추가 네이티브 시뮬레이터 실행 검증은 별도로 진행할 수 있으며, 새 실행기의 실제 SNU 로그인·채팅은 사용자 iPad에서 다시 확인해야 한다.

빌드에는 Apple 개발자 계정이나 사용자 ChatGPT 토큰이 필요 없다. 실제 로그인은 설치한 iPad의 공식 OpenAI 로그인 페이지에서 진행한다. 학교 워크스페이스 정책은 그대로 적용된다.

## 내장 Codex 라이브러리 재사용

앱 빌드에 필요한 것은 완성된 정적 라이브러리(`libyoungmin_codex_bridge.a`), Rust가 알려 준 시스템 라이브러리 목록과 라이선스 파일뿐이다. 이 파일들만 작은 캐시(`ios-codex-lib-v1-…`)에 manifest와 함께 저장하고(`.github/actions/ios-codex-lib`, `.github/scripts/ios-codex-lib.py`), 정확히 같은 키일 때 SHA-256을 확인한 뒤 그대로 링크한다. 이때 Rust 설치, Codex 소스 다운로드, 4GB 컴파일 캐시 복원을 모두 건너뛴다.

다음 중 하나라도 바뀌면 키가 달라져 다시 컴파일한다.

- 고정한 Codex·V8·MXC 커밋과 Rust 버전, 소스 패치(`ios-codex-probe.sh`, `prepare-ios-codex.py`)
- `ios/NativeCodex/bridge`, `javascript`(QuickJS 포함), `code-mode-check`, `native-static-libs.txt`의 내용
- 빌드 옵션: `fast`(빠른 IPA, 최적화 0, assertion 켬) / `release`(최적화 1, debug 정보·assertion·overflow check 끔, 최적화 IPA와 Ad Hoc 배포가 공유)
- 대상(iphoneos arm64)과 실행기의 Xcode·iOS SDK 버전

Swift 코드, Xcode 프로젝트, 워크플로의 다른 단계만 바뀐 경우에는 다시 컴파일하지 않는다. 새로 만든 라이브러리는 QuickJS·Codex delegate 실행 테스트를 통과한 경우에만 이 캐시에 저장되므로, 캐시가 있으면 같은 테스트를 다시 돌리지 않는다. 키에 맞지 않거나 파일 해시가 다른 캐시는 설치하지 않고 실패한다.

다시 컴파일할 때만 Cargo 컴파일 캐시(`ios-codex-build-v1-…`, 약 4GB)를 복원해 바뀐 부분만 빌드하고, 모든 워크플로가 이 접두사 하나를 함께 쓴다. GitHub 캐시는 브랜치별이므로 다른 브랜치의 첫 실행은 main의 캐시만 읽을 수 있다.

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
