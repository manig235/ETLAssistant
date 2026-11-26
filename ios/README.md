# 영민이 iOS

지인에게 심사 없이 링크로 배포하는 Release Ad Hoc 워크플로와 나중에 등록할 인증서·프로파일·Firebase 설정은
[배포 안내](DISTRIBUTION.md)에 정리했다. **iOS Ad Hoc Firebase**는 수동 실행이며 기본값은 IPA 생성만 한다.

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

## 맥 없이 GitHub Actions에서 테스트

저장소의 **Actions → iOS**에서 로직 테스트와 iPhone 시뮬레이터 UI 테스트 결과를 확인한다.
macOS 실행 환경은 GitHub에서 제공하므로 개인 맥, Apple 계정, 서명 인증서, Apple Developer Program 가입은 필요 없다.

- `ios/` 변경 PR과 `main`, `ios-port` 브랜치의 push에서 자동 실행한다. 작업 브랜치에서는 PR 검사로 실행해 push와 PR의 중복 검사를 피한다.
- 수동 실행은 **Actions → iOS → Run workflow**에서 브랜치를 선택한다. 버튼은 워크플로가 기본 브랜치(`main`)에도 있어야 나타난다. 머지 전에는 PR의 **Checks**에서 자동 실행 결과를 본다.
- 자동 실행과 수동 실행은 서로 취소하지 않는다. 같은 종류·같은 브랜치에서 다시 실행하면 최신 실행을 남기고 이전 실행을 취소하므로, `Canceling since a higher priority waiting request…` / `The operation was canceled` 표시는 코드 테스트 실패와 구분해서 본다. macOS 대기 시간 안내는 GitHub 실행 환경의 용량 안내다.
- `logic-tests`: `YoungminKit`의 전체 Swift 테스트를 실행한다.
- `simulator-tests`: 설치된 최신 iOS 런타임의 iPhone을 골라 앱·위젯·공유 확장을 빌드하고 앱의 UI 테스트를 실행한다.
- UI 테스트는 새 시뮬레이터에서 환영 화면 → **샘플 데이터로 둘러보기** → 채팅 → 다크/라이트 전환 → 설정 → 내 일정 → 시간표 → 알림 기록을 확인한다. 실제 eTL/OpenAI 계정이나 네트워크 응답을 검증하는 테스트는 아니다.

실행 페이지 아래 **Artifacts**에서 `ios-simulator-tests-…`를 내려받으면 다음 파일을 볼 수 있다. 결과물은 7일간 보관한다.

- `attachments/`: UI 테스트의 단계별 화면 캡처와 진단 첨부파일. `01-welcome`~`07-notifications` 이름은 첨부파일 목록(`manifest.json`)에도 기록된다.
- `xcodebuild.log`: 앱·확장·UI 테스트의 빌드 및 실행 로그.
- `Youngmin.xcresult/`: 전체 Xcode 테스트 결과. macOS에서 Xcode로 열 수 있다.
- `last-screen.png`: 테스트가 끝난 순간의 화면(수집 가능한 경우). 단계별 캡처는 `attachments/`를 본다.
- `devices.json`, `destination.log`, `toolchain.log`: 선택된 기기와 Xcode 정보.

직접 해야 하는 일:

1. 이 설정이 들어간 브랜치를 GitHub에 올리거나 PR을 머지한다.
2. Actions가 비활성화되어 있으면 저장소 **Settings → Actions → General**에서 활성화한다. `actions/checkout`, `actions/upload-artifact` 사용을 허용해야 한다.
3. 외부 기여자의 PR 실행에 승인이 필요하다고 나오면 해당 실행에서 **Approve and run**을 누른다.
4. 실패하면 실행 링크를 이 채팅에 전달한다. GitHub 접근이 연결된 상태에서 로그·캡처를 받아 수정하고 다시 실행할 수 있다.

별도 Secrets 등록이나 클라우드 계정 생성은 필요 없다. 공개 저장소의 표준 GitHub macOS 실행 환경은 무료이고, 비공개 저장소는 계정에 포함된 실행 시간을 사용한다.
이 워크플로는 화면을 실시간으로 직접 조작하는 원격 데스크톱이나 실제 아이폰 설치 파일을 제공하지 않는다.

UI 시나리오를 늘리려면 `YoungminUITests/YoungminUITests.swift`에 테스트를 추가한다.
macOS에서 CI와 같은 명령을 실행하려면 저장소 루트에서 `bash .github/scripts/ios-simulator.sh`를 사용한다(사용 가능한 새 시뮬레이터 권장).

## 구조

- `YoungminKit/`는 플랫폼에 의존하지 않는 로직을 담은 Swift 패키지다. 각 모듈의 API 요약은 `YoungminKit/Docs/`에 있다.
  - ETLKit: eTL, 시간표, 게시판, 파일 읽기(hwp·docx·pptx)
  - NotifyKit: 내 일정, 알림 계획, 브리핑, 캘린더 연동 로직
  - ChatKit: 대화, 마크다운·수식, Responses API, ChatGPT 로그인
  - AssistantKit: 비서 도구, 지침, 쓰기 승인, 시험 자료용 공개 기출 자료 찾기(MIT OpenCourseWare, `Study*.swift`, 2026-11-27)
- `Youngmin/`은 앱 본체다.
  - SwiftUI 화면: 첫 실행 안내, 채팅·서랍, 내 일정, 시간표, 알림 기록, 과목별 알림, 설정, 캘린더 연동
  - iOS 서비스: 로컬 알림, 백그라운드 갱신, 로그인 시트, EventKit
- `YoungminWidgets/`는 홈 화면·잠금 화면 위젯이다(크기는 2026-11-27 기준).
  - 일정: 작게·중간·크게, 잠금 화면. 높이와 글자 크기에 맞춰 들어가는 만큼 보인다.
  - 시간표: 중간(오늘 수업 목록)·크게(이번 주 격자)·아주 크게(iPad 전용, 더 큰 격자). 아이폰 위젯 갤러리에는 아주 크게가 나오지 않는다.
  - 다음 수업: 작게, 잠금 화면.
  - 앱은 위젯을 직접 추가할 수 없어서 설정 → 기타 → "홈 화면에 위젯 추가하기"가 추가 순서를 안내한다.
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
  - 대화마다 따로 답하기(두 대화 동시 답변, 서랍 표시, 끝남 막대·알림, 다른 대화의 승인 배너)의 실제 기기 동작
  - 공개 기출 자료 찾기(`study_search`·`study_fetch`)의 실제 MIT 응답(로직은 같은 실제 응답 고정 파일로 `swift test`, 실제 접속은 안드로이드 쪽 리눅스에서만 확인)
  - 실제 아이폰·아이패드 위젯 갤러리의 새 크기(일정 크게, 시간표 중간·아주 크게)와 iOS 18 모서리 끌기 크기 전환

## 기기 설치·배포

- **Windows PC로 본인 아이패드·아이폰에 설치:** [Windows 설치 안내](INSTALL-WINDOWS.md). **Actions → iOS Device IPA**에서 기기용 unsigned IPA를 받고 Sideloadly로 서명·설치한다. 이 테스트 패키지는 앱 본체만 포함하며 위젯·공유 확장은 제외한다.

- **서명:** 세 타깃(Youngmin, YoungminWidgets, YoungminShare)의 Signing & Capabilities에서 Team을 지정한다. 번들 ID나 App Group이 이미 쓰이고 있으면 세 타깃 모두 같은 규칙으로 바꾼다.
- **무료 Apple ID:** 본인 기기에만 설치할 수 있고, 7일마다 다시 설치해야 한다.
- **다른 사람에게 배포:** Apple Developer Program 계정으로 Archive를 만들어 TestFlight나 App Store에 올린다.
