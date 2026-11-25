# 영민이 iOS 포팅 규칙

안드로이드 원본(Kotlin)은 같은 저장소의 루트(이하 `$ANDROID`, `ios/`에서 보면 `..`)에 있다. 원본 Kotlin 소스는 `$ANDROID/app/src/main/java/kr/snu/etlassistant/`, 테스트는 `$ANDROID/app/src/test/java/kr/snu/etlassistant/`.
iOS 판은 안드로이드 0.3.3 기준으로 옮겼다. iOS 작업은 `ios/` 안에서만 하고 안드로이드 파일은 고치지 않는다.

## 구조

- `YoungminKit/` — 플랫폼 독립 로직(Swift 패키지, iOS 17 / macOS 14). UIKit·SwiftUI를 import하지 않는다.
  - `ETLKit` ← Kotlin `etl` 패키지 (eTL 클라이언트, 판정 로직, 수강편람 시간표, 저장소 인터페이스)
  - `NotifyKit` ← Kotlin `notify` 패키지의 순수 로직 (내 마감, 일정 모음, 알림 계획, 브리핑, 과목 필터 …). ETLKit에 의존
  - `ChatKit` ← Kotlin `chat`·`codex`의 순수 로직 (대화 모델·저장, 마크다운·수식 파싱) + 새 AI 엔진(OpenAI Responses API) + ChatGPT 로그인(PKCE)
  - `AssistantKit` ← Kotlin `EtlTools`·`AiWriteApproval`·`CodexConfig`(지침) — 비서 도구 정의·실행. ETLKit·NotifyKit·ChatKit에 의존
- `Youngmin/` — iOS 앱 타깃(SwiftUI 화면, iOS 서비스: 알림·Keychain·로그인 시트·백그라운드 작업). Xcode 동기화 폴더라 파일만 추가하면 프로젝트에 들어간다.

## 규칙

1. **1:1로 옮긴다.** Kotlin 타입·함수 이름을 그대로 쓴다(`AssignmentItem`, `MyDeadline`, `EtlLogic.isSubmitted` …). Kotlin `object`는 Swift `enum`(정적 멤버) 또는 `final class`, `data class`는 `struct`(+`Codable, Hashable, Sendable` 가능하면).
   동작(경계값, 한국어 문구, 정렬, 시간대)을 바꾸지 않는다. 한국어 사용자 문구는 원문 그대로.
2. 다른 모듈에서 쓰는 선언은 `public`. 구조체의 public memberwise init은 직접 써 준다.
3. 시간: Kotlin `Instant` → `Date`, `LocalDate` → `DayDate`(ETLKit `Time.swift`, KST 기준 연·월·일), `DayOfWeek` → `Weekday`(1=월…7=일, ISO). 시간대는 원본처럼 `Asia/Seoul` 고정(`EtlLogic.kst`). 날짜 계산은 `Calendar(identifier: .gregorian)` + KST.
4. JSON: 원본은 `org.json`을 쓴다. Swift에서는 `JSONSerialization` 기반의 작은 헬퍼(`JSONValue` 또는 `[String: Any]`)나 `Codable`을 쓴다. 저장 형식(키 이름)은 원본과 같게.
5. 저장: SharedPreferences → `KeyValueStore`(ETLKit `Storage.swift`). 비밀값(eTL 토큰, ChatGPT 토큰, API 키) → `SecretStore`(Keychain 구현 `KeychainSecretStore`). 파일 → 앱 Application Support 아래 하위 폴더(`AppPaths`).
6. 네트워크: `URLSession` + async/await. 리다이렉트·헤더 규칙(토큰은 eTL 원본 호스트에만, 리다이렉트에는 Authorization 안 붙임)을 원본대로 지킨다. 테스트에서 실제 네트워크를 쓰지 않는다.
7. 외부 라이브러리 금지(SwiftPM 의존성 추가 금지).
8. 테스트: Swift Testing(`import Testing`, `@Test`, `#expect`). 원본의 해당 Kotlin 테스트를 같은 사례로 옮긴다. `cd YoungminKit && swift test` 로 macOS에서 돈다.
9. Swift 5 언어 모드. 동시성 경고를 줄이려고 공유 가변 상태는 `actor` 또는 `final class` + 잠금, 값 타입은 `Sendable`.
10. 맡은 폴더 밖의 파일은 고치지 않는다. 다른 모듈의 타입이 필요하면 그 모듈의 public API를 쓴다(없으면 보고).

## 빌드·확인

- 로직: `cd ios/YoungminKit && swift build && swift test`
- 앱(iOS 시뮬레이터): `cd ios && xcodebuild -project Youngmin.xcodeproj -scheme Youngmin -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath build/DerivedData build`
  (여러 작업이 동시에 빌드하면 DerivedData가 겹친다. 앱 빌드는 화면 작업만, 각자 `-derivedDataPath build/DD-<작업이름>`을 쓴다.)

## iOS에서 바뀌는 것 (확정)

- AI: Codex 실행 파일·로컬 프록시·MCP 서버 대신 **OpenAI Responses API를 앱이 직접 호출**한다. 인증은 "ChatGPT로 계속하기"(OpenAI Sign in with ChatGPT, 오픈소스·로컬 앱 경로, scope `chatgpt.tokens.use.direct`, 사용자 ChatGPT 플랜 과금)가 기본, OpenAI API 키가 예비. 도구 정의·지침(AGENTS.md)·쓰기 승인은 그대로 옮긴다.
- 업데이트 확인(GitHub APK)은 iOS에 없다(App Store/TestFlight).
- 1단계 범위 밖이던 캘린더 연동, 홈 화면 위젯, 공유 받기, PDF 만들기, hwp 읽기, LearningX 게시판, 받은 파일 자동 정리는 이후 단계에서 옮겼다.
