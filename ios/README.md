# 영민이 iOS

안드로이드 앱 영민이 0.3.3(저장소 루트)을 Swift·SwiftUI로 옮긴 iOS 판이다. 외부 라이브러리는 쓰지 않는다. 포팅 규칙은 `PORTING.md`에 있다.

## 실행

저장소의 `ios/` 폴더에서 Xcode 프로젝트를 연다.

```bash
cd ios && open Youngmin.xcodeproj
```

Xcode에서 iPhone 시뮬레이터를 고르고 실행한다(⌘R). 명령줄로 빌드하려면 다음과 같이 한다.

```bash
cd ios && xcodebuild -project Youngmin.xcodeproj -scheme Youngmin -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath build/DD-main build
```

- 실제 계정 없이 둘러보려면 첫 화면을 아래로 내려 **"샘플 데이터로 둘러보기"**를 누른다(Debug 빌드에만 있다).
- 실제로 쓰려면 eTL 토큰을 넣고 "ChatGPT로 계속하기" 또는 "API 키로 연결"을 고른다.
- 로직 테스트는 macOS에서 돌린다: `cd ios/YoungminKit && swift test`(테스트 732개).
- 요구 사항: Xcode 16 이상(Xcode 26.6에서 확인), iOS 17 이상.

## 구조

- `YoungminKit/`는 플랫폼에 의존하지 않는 로직을 담은 Swift 패키지다. 각 모듈의 API 요약은 `YoungminKit/Docs/`에 있다.
  - ETLKit: eTL, 시간표, 게시판, 파일 읽기(hwp·docx·pptx)
  - NotifyKit: 내 일정, 알림 계획, 브리핑, 캘린더 연동 로직
  - ChatKit: 대화, 마크다운·수식, Responses API, ChatGPT 로그인
  - AssistantKit: 비서 도구, 지침, 쓰기 승인
- `Youngmin/`은 앱 본체다.
  - SwiftUI 화면: 첫 실행 안내, 채팅·서랍, 내 일정, 시간표, 알림 기록, 과목별 알림, 설정, 캘린더 연동
  - iOS 서비스: 로컬 알림, 백그라운드 갱신, 로그인 시트, EventKit
- `YoungminWidgets/`는 홈 화면 위젯이다. 일정, 시간표, 다음 수업 위젯이 있다.
- `YoungminShare/`는 공유 확장("공유 → 영민이")이다. 앱과 App Group(`group.kr.snu.etlassistant.ios`)으로 넘긴다.
- `Docs/screens/`에는 안드로이드 화면 명세와 와이어프레임 JSON이, `Docs/integration/`에는 화면 통합 안내가 있다.

## 안드로이드와 다른 점

- **AI:** Codex 실행 파일 대신 OpenAI Responses API를 앱이 직접 호출한다.
  - iOS에서는 앱 안에서 다른 실행 파일을 띄울 수 없기 때문이다.
  - 로그인은 Sign in with ChatGPT(오픈소스·로컬 앱 경로)를 기본으로 하고, API 키를 예비로 둔다.
  - 토큰은 Keychain에만 둔다.
- **알림:**
  - 백그라운드 확인 시점은 iOS가 정한다. 늦어지거나 건너뛸 수 있다.
  - 제시간 알림은 앞으로 7일분을 미리 예약한다. iOS의 로컬 알림 한도가 64개다.
- **캘린더:** EventKit을 쓴다.
  - 영민이 → 캘린더 방향은 자동으로 넣기와 캘린더에서 고친 것 가져오기다.
  - 캘린더 → 영민이 방향은 내 캘린더 일정 보기와 비서 캘린더 도구다.
- **수식:** 기기 안에서 직접 배치해 그린다. 말풍선보다 넓은 블록 수식은 가로로 밀어 본다.
- **iOS에 해당하지 않아 뺀 것:** 업데이트 확인(APK), 배터리 최적화 예외, 정확한 알람 권한.

## 확인 상태

- 시뮬레이터(iPhone 17, iOS 26.5)에서 실제 eTL·ChatGPT 계정으로 확인한 것:
  - 채팅과 비서 도구
  - 내 일정과 시간표
  - 캘린더 연동
  - 공유 받기
  - 수식
- 아직 확인하지 못한 것:
  - 실제 아이폰에서의 동작
  - 알림 동작 버튼 화면
  - 캘린더에서 고친 내 마감을 되가져오는 동작
  - 백그라운드 장기 동작

## 기기 설치·배포

- **서명:** 세 타깃(Youngmin, YoungminWidgets, YoungminShare)의 Signing & Capabilities에서 Team을 지정한다. 번들 ID나 App Group이 이미 쓰이고 있으면 세 타깃 모두 같은 규칙으로 바꾼다.
- **무료 Apple ID:** 본인 기기에만 설치할 수 있고, 7일마다 다시 설치해야 한다.
- **다른 사람에게 배포:** Apple Developer Program 계정으로 Archive를 만들어 TestFlight나 App Store에 올린다.
