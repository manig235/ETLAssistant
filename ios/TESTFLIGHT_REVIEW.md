# TestFlight 심사 접근 준비

상태: **준비 중, 제출하지 않음**. 이 문서는 현재 코드를 읽어 작성한 초안이다.
실제 Release IPA 설치, ChatGPT 로그인, 전체 심사 경로 검증은 아직 완료되지 않았다.
App Store Connect 업로드는 별도 **iOS TestFlight** 워크플로가 담당하며 아직 실제로 올리지 않았다.

## 현재 제공하는 가상 eTL

- 앱 첫 실행에서 **가상 eTL로 테스트하기**를 선택한다. `YoungminApp.swift`의 이 진입점은 Debug에 한정되지 않는다.
- 실제 eTL 토큰 없이 가상 과목·과제·제출 상태·공지·시간표를 조회하고 샘플 PDF/텍스트 자료를 읽는다.
- 기존 eTL 파서와 비서 도구를 사용한다. 등록되지 않은 가상 경로는 오류를 반환하며 실제 학교 서버로 대체 요청하지 않는다.
- 채팅 답변은 실제 AI가 생성한다. **ChatGPT 로그인 또는 OpenAI API 키가 별도로 필요하다.**
- 내 일정 변경은 사용자 승인 후 가상 저장소에 적용한다. 실제 데이터와 대화·파일·일정 저장소가 분리되어 있다.
- 화면 상단에 가상 eTL 사용을 표시한다. 설정 → 학습 데이터에서 다음 실행의 모드 변경·가상 데이터 초기화를 예약할 수 있다.
- 가상 모드에서는 실제 캘린더 연동과 위젯 연결을 제한한다. 실제 학교 사이트 링크도 열지 않는다.
- Debug 전용 `DemoSeed`는 고정 답변을 보여 주는 별도 UI 시연이다. 심사용 실제 기능 검증 경로로 사용하지 않는다.

관련 코드: `Youngmin/App/YoungminApp.swift`, `YoungminKit/Sources/ETLKit/FakeEtl.swift`,
`YoungminKit/Sources/ETLKit/StudyDataProfile.swift`.
기존 `FakeEtlTests`와 `FakeEtlToolTests`는 파서·샘플 다운로드·요청 경계·쓰기 승인 등을 검사한다.
테스트 코드가 있다는 사실을 이번 빌드에서 해당 테스트가 통과했다는 뜻으로 해석하지 않는다.

## 제출 전에 해결할 것

1. 서명된 Release 빌드를 실제 기기에 설치해 아래 경로를 확인한다.
2. 심사자가 AI 기능에 접근할 방법을 정한다. 심사자의 개인 ChatGPT 계정이나 개인 API 키 사용을 전제로 준비 완료 처리하지 않는다.
   개발자의 ChatGPT 비밀번호나 API 비밀키를 앱에 내장하지 않는다.
   별도 심사 계정/인증 경로 또는 비용을 제한한 별도 데모 서비스를 검토할 경우, 범위와 운영 방식을 먼저 합의한다.
3. 가상 모드에서 제한한 캘린더·위젯 및 실제 학교 로그인 기능을 심사자가 확인할 방법을 준비한다.
4. 학교 계정을 제공하기 어려운 사유를 설명하고, 내장 데모를 계정 대신 사용하려면 Apple과 사전 협의한다.
   아직 Apple의 사전 승인이나 심사 승인을 받았다고 표시하지 않는다.
5. App Store Connect용 프로파일·API 키를 등록하고 **iOS TestFlight** 워크플로로 업로드한다([배포 안내](DISTRIBUTION.md#testflight-업로드-워크플로)). 경로는 준비됐지만 실제 서명·업로드는 미검증이다.
6. App Store Connect의 TestFlight 테스트 정보, 연락처, 개인정보·수출 규정 입력과 실제 빌드를 일치시킨다.

Apple은 심사에 필요한 전체 접근을 요구한다. 지침 2.1(a)는 법적·보안상 데모 계정을 제공하기 어려운 경우
사전 승인된 내장 데모 모드로 대체할 수 있다고 설명하며, 전체 기능을 보여 주어야 한다고 명시한다.
가상 eTL 구현 자체가 TestFlight 심사를 대체하거나 승인을 보장하지 않는다.

## 기기에서 검증할 경로

- 새 설치 → 가상 eTL 선택 → 온보딩 → 준비한 AI 접근 방법으로 인증.
- “이번 주 마감 과제를 알려줘” → 실제 도구 호출로 가상 과목·과제를 조회하는지 확인.
- “회로이론 강의자료를 찾아 읽고 요약해줘” → 샘플 파일 다운로드·열람·요약 확인.
- “내일 오후 2시에 가상 시험 일정을 추가해줘” → 승인 전에는 저장되지 않고 승인 후 가상 일정에 반영되는지 확인.
- 가상 데이터 초기화/실제 모드 전환 후 두 저장소가 섞이지 않는지 확인.
- 실제 eTL 모드의 로그인·캘린더·위젯·공유 확장을 별도로 확인하고, 심사자의 재현 방법도 기록.

## Review Notes 초안 — 미완성, 그대로 제출하지 말 것

Youngmin is an unofficial study assistant for Seoul National University eTL.
To explore synthetic school data without a university account, select
“가상 eTL로 테스트하기” (Test with virtual eTL) on the first-launch data selection screen.
The app displays a virtual eTL banner. Courses, assignments, announcements, schedules,
and sample files in this mode are synthetic. The normal app UI, parsers, assistant tools,
and write-approval flow are used. Real university data is stored separately.

AI chat still requires ChatGPT authentication or an OpenAI API key. Reviewer access:
**TO BE ARRANGED AND VERIFIED BEFORE SUBMISSION.**
Calendar integration and home-screen widgets are restricted in virtual eTL mode;
review instructions for these features and university sign-in:
**TO BE ARRANGED BEFORE SUBMISSION.**
Any approval to use this built-in mode in place of a university demo account:
**NOT YET OBTAINED.**

## 참고

- [Apple App Review Guidelines 2.1(a)](https://developer.apple.com/app-store/review/guidelines/#app-completeness)
- [TestFlight 테스트 정보](https://developer.apple.com/help/app-store-connect/test-a-beta-version/provide-test-information/)
