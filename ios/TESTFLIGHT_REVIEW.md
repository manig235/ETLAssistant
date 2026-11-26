# TestFlight 심사 접근 준비

상태: **심사 메모 작성 완료, 제출하지 않음**. 현재 코드를 기준으로 화면 문구와 재현 절차를 확인했다.
실제 Release IPA 설치, ChatGPT 로그인, 전체 심사 경로 검증은 아직 완료되지 않았다.
App Store Connect 업로드는 별도 **iOS TestFlight** 워크플로가 담당하며 아직 실제로 올리지 않았다.

## 제출 준비 점검 — 2026-11-26

판정: **심사 메모는 준비됐지만 지금 바로 심사 제출할 상태는 아님**.
이 점검은 저장소·GitHub Actions와 설정 이름을 조회한 결과이며 App Store Connect 화면과 실기기 동작은 확인하지 못했다.
Apple 계정에서 해야 하는 작업은 [TestFlight 최초 설정](TESTFLIGHT_SETUP.md)에 순서대로 정리했다.

확인한 항목:

- `9026668`에서 배포 스크립트 테스트 **32개 통과**. 실제 OpenSSL 3으로 생성한 현대·구형 PKCS#12의 정상 암호 검증, 잘못된 암호 거부, 임시 호환 사본의 인증서 동일성을 포함한다. 제거 대상 JavaScriptCore 비공개 API 소스 검사와 `git diff --check`도 통과했다.
- [수정 브랜치 TestFlight CI](https://github.com/manig235/ETLAssistant/actions/runs/45777028138)는 `distribution-checks`가 성공했다. `push` 실행이므로 `build-and-upload`는 **skipped**다. 서명된 제출용 빌드 생성이나 Apple 업로드 성공을 뜻하지 않는다.
- [실제 저장소 Secret을 사용한 검사](https://github.com/manig235/ETLAssistant/actions/runs/45777028459)에서 OpenSSL의 **P12 MAC·비밀번호 검사가 통과**했다. macOS keychain 가져오기·프로파일 검증·Release 빌드는 다음 작업의 결과를 확인한다. 이 실행의 `publish=false`는 테스터에게 배포하지 않는다.
- 공통 기반 `34b4f49`의 [iOS 검사](https://github.com/manig235/ETLAssistant/actions/runs/45771251265)는 Swift 로직·시뮬레이터 검사 모두 성공했다. 실제 ChatGPT 계정 로그인이나 현재 App Store 서명 빌드를 검증한 결과는 아니다.
- [이전 Release archive 실행](https://github.com/manig235/ETLAssistant/actions/runs/45732010508)은 성공 기록이 있다. 다른 커밋의 실행이며 해당 실행에서 인증·채팅 통합 테스트 단계는 skipped이므로 현재 제출 준비 완료의 근거로 삼지 않는다.
- 로컬 Apple 서명 폴더의 프로파일 세 개는 등록 기기가 포함된 **Ad Hoc** 프로파일이다.

남은 제출 조건:

1. **App Store Connect용 프로파일 세 개**를 생성하고 `IOS_APPSTORE_APP_PROFILE_BASE64`, `IOS_APPSTORE_WIDGETS_PROFILE_BASE64`, `IOS_APPSTORE_SHARE_PROFILE_BASE64`를 GitHub Secrets에 등록한다. 조회 시점에는 모두 없었다.
2. **Apple 업로드 인증 설정**을 등록한다. Secret `ASC_KEY_P8`, Variables `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_APP_ID`가 조회 시점에는 모두 없었다. 배포용 p12 및 비밀번호와 `IOS_TEAM_ID`는 등록돼 있다.
3. **macOS 서명 사전 검사 결과를 확인한다.** 비밀번호 수정 후 P12 검사는 통과했다. main의 안전한 진단·임시 호환 사본 처리를 TestFlight 브랜치에 통합했고, OpenSSL 3이 구형 암호화 형식을 읽는 재시도도 추가했다. TestFlight도 같은 인증서를 사용하지만 App Store Connect 프로파일 세 개에 대한 실제 macOS 서명 검사는 해당 파일 등록 후 확인해야 한다.
4. **업로드할 Release 빌드로 실기기 경로를 확인한다.** FakeETL 진입 → ChatGPT 로그인 → 과제 조회 → 자료 요약 → 일정 승인·취소를 확인한다. 이 작업 환경에는 Xcode와 Swift가 없어 여기서 실기기 검증을 대신하지 못했다.
5. **TestFlight 빌드 생성·업로드 및 App Store Connect 입력을 완료한다.** 먼저 upload=false로 서명·검증하고, 성공한 소스로 upload=true를 실행한다. Apple 측 빌드 처리 완료, 연락처·피드백 이메일·개인정보 URL·심사 메모·테스트할 내용을 확인해 외부 그룹의 심사에 제출한다. App Store Connect의 현재 앱 레코드·연락처·빌드 상태는 이 점검에서 직접 조회하지 못했다.

코드·심사 메모는 `codex/testflight-submission-prep` 브랜치에 push했다. 인증서 검사를 위해 publish=false 실행만 요청했으며 App Store Connect 입력·업로드·심사 제출은 수행하지 않았다.

## 이번 제출의 접근 방법

심사 담당자가 **본인 ChatGPT 계정으로 로그인**해 실제 AI와 FakeETL을 테스트하도록 안내하고 먼저 제출한다.
별도 GPT 서버나 대체 AI를 추가하지 않는다. Apple과의 사전 문의를 이번 제출의 선행 작업으로 두지 않는다.
심사 담당자의 계정 준비·사용 가능 여부를 확인받았다는 뜻은 아니다. 접근 관련 문의가 오면 그 내용에 맞춰 대응한다.

- **베타 앱 심사 정보 → 메모(Review Notes)**: [영문 심사 메모](TESTFLIGHT_REVIEW_NOTES.txt)의 전체 내용을 붙여 넣는다.
- **빌드 → 테스트할 내용(What to Test)**: [한글 테스트 안내](TESTFLIGHT_WHAT_TO_TEST.txt)의 전체 내용을 붙여 넣는다.
- 피드백 이메일과 심사 연락처는 App Store Connect의 해당 필드에 실제 개발자 연락처를 입력한다.
- 위 두 파일에는 계정 비밀번호나 API 키가 없으며, 워크플로가 내용을 자동으로 등록하지 않는다.

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
2. 심사 메모의 **ChatGPT로 계속하기** 경로를 업로드할 빌드에서 확인한다. 로그인이 끝나면 모델 목록·실제 AI 답변·도구 호출까지 확인한다.
   이번 경로에서는 심사자에게 API 키 입력을 요구하지 않으며 개발자 ChatGPT 계정을 공유하지 않는다.
3. 가상 모드에서 제한한 캘린더·위젯·외부 LearningX 게시판과 실제 학교 인증이 필요한 범위를 메모에 명시한다.
4. 아래 심사 메모와 테스트 안내를 App Store Connect에 입력한다. Apple의 별도 접근 요청이 오면 응답한다.
5. App Store Connect용 프로파일·API 키를 등록하고 **iOS TestFlight** 워크플로로 업로드한다([배포 안내](DISTRIBUTION.md#testflight-업로드-워크플로)). 경로는 준비됐지만 실제 서명·업로드는 미검증이다.
6. App Store Connect의 TestFlight 테스트 정보, 연락처, 개인정보·수출 규정 입력과 실제 빌드를 일치시킨다.

Apple은 심사에 필요한 전체 접근을 요구한다. 지침 2.1(a)는 법적·보안상 데모 계정을 제공하기 어려운 경우
사전 승인된 내장 데모 모드로 대체할 수 있다고 설명한다. 이번 제출에서는 가상 데이터 범위와 ChatGPT 로그인 요구를
투명하게 안내해 심사를 요청한다. Apple의 사전 승인이나 전체 심사 접근 요건 충족을 확인받았다고 표시하지 않는다.

## 기기에서 검증할 경로

- 새 설치 → **가상 eTL로 테스트하기** → **시작하기** → eTL 연결 화면의 **다음**(가상 토큰 자동 제공) → **ChatGPT로 계속하기** → 로그인 후 **다음** → 알림 설정 → **비서 시작하기**.
- “이번 주 마감 과제를 알려줘” → 실제 도구 호출로 가상 과목·과제를 조회하는지 확인.
- “회로이론 강의자료를 찾아 읽고 요약해줘” → 샘플 파일 다운로드·열람·요약 확인.
- “내일 오후 2시에 가상 시험 일정을 추가해줘” → 승인 전에는 저장되지 않고 승인 후 가상 일정에 반영되는지 확인.
- 가상 데이터 초기화/실제 모드 전환 후 두 저장소가 섞이지 않는지 확인.
- 실제 eTL 모드의 로그인·캘린더·위젯·공유 확장을 별도로 확인하고, 심사자의 재현 방법도 기록.

## 심사 메모와 테스트 안내

[영문 심사 메모](TESTFLIGHT_REVIEW_NOTES.txt)는 FakeETL 진입, 본인 ChatGPT 계정으로 인증,
질문별 예상 동작, 변경 승인·취소, 지원 범위와 초기화 절차를 포함한다.
[한글 테스트 안내](TESTFLIGHT_WHAT_TO_TEST.txt)는 외부 테스터에게 표시할 테스트 항목이다.
기기 검증 후 같은 빌드에 맞는지 확인해 붙여 넣는다. 심사 메모 작성 완료와 실제 기기 검증·업로드·승인은 각각 별도 상태다.

## 참고

- [Apple App Review Guidelines 2.1(a)](https://developer.apple.com/app-store/review/guidelines/#app-completeness)
- [TestFlight 테스트 정보](https://developer.apple.com/help/app-store-connect/test-a-beta-version/provide-test-information/)
