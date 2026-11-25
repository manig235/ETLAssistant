# TestFlight 준비와 가상 eTL 테스트

학교 계정 없이 동작하는 가상 eTL, Apple 비공개 JavaScriptCore 함수 제거, 배포 빌드(Release archive·서명·업로드) 경로를 정리한다. 실제 TestFlight 업로드는 아직 하지 않았다.

## 테스터가 시작하는 방법

첫 실행의 **가상 eTL로 테스트하기**를 선택한다. 기존 사용자는 **설정 → 학습 데이터 → 가상 eTL로 전환 예약**을 누르고 앱 전환 화면에서 영민이를 완전히 종료한 뒤 다시 연다. 화면 위의 **가상 eTL** 표시로 확인한다. Release에서도 사용할 수 있다.

학교 eTL 토큰은 필요 없다. 채팅에는 본인의 ChatGPT 로그인 또는 OpenAI API 키가 필요하다. 기존 네이티브 ChatGPT 로그인은 공유하지만 실제·가상 대화는 구분한다. 계정별로 서버가 허용하는 모델과 사용량이 적용된다.

다음 질문으로 확인한다.

- “수강 과목과 앞으로 7일 마감, 누락 과제를 알려줘.”
- “회로이론 RC 회로 과제 설명을 읽어줘.”
- “기말시험 공지를 읽고 날짜·장소를 알려줘.”
- “회로이론 강의자료를 찾아 RC 회로 내용을 요약해줘.” (가상 PDF의 영어 본문을 읽는다.)
- “글쓰기 에세이 초안이 제대로 제출됐는지 확인해줘.”
- “내일 오후 6시까지 가상 복습 일정을 추가해줘.” → 변경 승인 후 내 일정 확인. 취소하면 추가되지 않아야 한다.

과목 3개, 과제 5개(미제출·누락·제출·채점 상태), 공지와 PDF/TXT 자료가 있다. 날짜는 실행 시점을 기준으로 생성한다. 고정 답변을 재생하지 않고 실제 AI가 기존 eTL 도구를 호출한다. 지원하지 않는 가상 API는 오류로 끝내며 실제 학교 서버로 대체 요청하지 않는다. 외부 LearningX 게시판은 이 가상 데이터셋에서 제공하지 않는다.

가상 모드의 대화·첨부·내 마감·시간표·캐시는 별도 UserDefaults suite와 `Application Support/Youngmin/FakeETL`, 사용자 다운로드는 `Documents/etl-fake`에 보관한다. 실제 eTL 토큰은 읽거나 덮어쓰지 않는다. 캘린더 접근·내보내기와 위젯 공유 데이터 접근은 가상 모드에서 막는다. 프로필 전환 때 이전 알림 예약을 지우고 현재 프로필 기준으로 다시 계획한다.

**설정 → 학습 데이터 → 가상 데이터 초기화 예약** 후 완전히 종료·재실행하면 가상 데이터만 초기화한다. 실제 데이터와 ChatGPT 로그인은 유지된다. **실제 eTL로 전환 예약** 후 재실행하면 원래 데이터로 돌아간다. 전환을 즉시 적용하지 않는 이유는 진행 중인 도구 호출과 저장소가 다른 프로필에 섞이지 않게 하기 위해서다.

## 비공개 API 제거 확인

JavaScriptCore의 비공개 실행 시간 제한 함수 대신 앱에 소스로 포함한 QuickJS의 공개 interrupt API를 쓴다. QuickJS OS 모듈·JIT·외부 실행 파일을 포함하지 않는다. 비동기 네이티브 도구·쓰기 승인·중단을 유지하고 메모리·실행 시간도 제한한다. CI에서 macOS/iOS 시뮬레이터의 실행 테스트, 실제 Codex delegate 계약, 최종 arm64 앱 링크를 검증한다. 최종 앱의 import에 제거한 두 SPI가 있으면 빌드를 실패시킨다.

## 배포 빌드: Release는 항상 Codex

- 앱 타깃의 Release 구성은 `ios/Config/NativeCodex.xcconfig`를 기본 구성으로 쓴다. Xcode의 Product → Archive나 `xcodebuild archive -configuration Release`가 스크립트 없이 내장 Codex 엔진을 연결한다.
- 엔진 라이브러리(`ios/NativeCodex/Generated/iphoneos/arm64`)가 없으면 링크 단계에서 실패한다. 설정을 명령줄에서 지우면 `App/ReleaseEngineGuard.swift`가 컴파일을 멈춘다. 예전 Swift 인증·채팅 경로로 조용히 돌아가지 않는다. Debug와 시뮬레이터 개발 흐름은 그대로다.
- 버전은 `ios/Config/Youngmin.xcconfig` 한 곳(`MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`)에서 관리한다. 업로드 빌드 번호는 `<프로젝트 빌드>.<실행 번호>`(예: `26.3`)로 매번 달라진다.
- Codex 소스 커밋은 `ios-codex-probe.sh`의 `REVISION`이 기준이다. xcconfig 값이 다르면 CI가 실패한다.
- `ITSAppUsesNonExemptEncryption = NO`. 앱은 HTTPS(시스템·rustls), SHA-256 해시, 서명 검증, Keychain만 쓰고 자체 암호화를 하지 않는다. 제출 전에 App Store Connect의 수출 규정 답과 맞는지 한 번 더 본다.
- Codex·MXC·QuickJS 라이선스는 앱 번들에 넣는다(`Youngmin/Resources/Licenses`, 고정 소스와 바이트 비교).

## Apple 쪽에서 할 일

1. Apple Developer Program에 가입한다(유료).
2. Certificates, Identifiers & Profiles → Identifiers에서 App ID `kr.snu.etlassistant.ios`를 만든다. **앱만(app-only, 기본)** 배포에는 추가 기능이 필요 없다(App Group 권한도 빼고 서명한다).
   **확장 포함(full)** 배포에는 App ID `kr.snu.etlassistant.ios.widgets`, `kr.snu.etlassistant.ios.share`와 App Group `group.kr.snu.etlassistant.ios`를 만들고 세 App ID 모두 App Groups에 이 그룹을 연결한다.
3. Apple Distribution 인증서를 Linux에서 만든다. 키 파일과 `.p12`는 저장소에 넣지 말고 따로 백업한다.
   ```bash
   openssl genrsa -out dist.key 2048
   openssl req -new -key dist.key -out dist.csr -subj "/CN=Youngmin Distribution/C=KR"
   # Certificates → + → Apple Distribution → dist.csr 업로드 → distribution.cer 받기
   openssl x509 -inform der -in distribution.cer -out dist.pem
   openssl pkcs12 -export -legacy -inkey dist.key -in dist.pem -name "Apple Distribution" -out dist.p12
   ```
   `-legacy`가 없으면 macOS 키체인이 `.p12`를 못 읽을 수 있다.
4. Profiles에서 배포용 프로파일을 만든다. TestFlight는 **App Store Connect**, Ad Hoc 설치는 **Ad Hoc**(기기 UDID 등록 필요). 위 인증서를 고른다. full은 확장 App ID마다 하나씩 더 만든다.
5. App Store Connect → 앱 → + 새로운 앱: iOS, 번들 ID `kr.snu.etlassistant.ios`, 이름(이미 쓰인 이름이면 다른 이름), SKU는 아무 값.
6. App Store Connect → 사용자 및 액세스 → 통합 → App Store Connect API에서 팀 키를 만든다(역할: 앱 관리). Key ID, Issuer ID를 적고 `.p8`을 받는다(한 번만 받을 수 있다).

## GitHub Secrets

저장소 Settings → Secrets and variables → Actions에 넣는다. 파일은 `base64 -w0 파일`의 출력을 넣는다.

| 이름 | 내용 | 필요할 때 |
|---|---|---|
| `IOS_DIST_P12_BASE64` | `dist.p12`의 base64 | 서명 |
| `IOS_DIST_P12_PASSWORD` | `.p12` 암호 | 서명 |
| `IOS_TEAM_ID` | 팀 ID(10자리) | 서명 |
| `IOS_PROFILE_APP_BASE64` | 앱 프로파일 `.mobileprovision` | 서명 |
| `IOS_PROFILE_WIDGETS_BASE64` | 위젯 확장 프로파일 | full |
| `IOS_PROFILE_SHARE_BASE64` | 공유 확장 프로파일 | full |
| `ASC_KEY_ID` | API Key ID | 업로드 |
| `ASC_ISSUER_ID` | Issuer ID | 업로드 |
| `ASC_KEY_P8` | `.p8` 파일 내용(텍스트 그대로) | 업로드 |

서명 값은 넷 다 있거나 넷 다 없어야 한다(일부만 있으면 실패). 값은 로그에 출력하지 않는다.

## 워크플로 실행

Actions → **iOS Release Archive** → Run workflow(버튼은 이 워크플로가 `main`에 있어야 보인다).

- `method`: `app-store-connect`(TestFlight) 또는 `release-testing`(Ad Hoc).
- `extensions`: `app-only`(기본, 위젯·공유 확장 제외) 또는 `full`.
- `upload`: 켜면 서명한 IPA를 App Store Connect에 올린다(`app-store-connect`만).
- `fast`: 배포에는 끈다(엔진 최적화 빌드, 첫 실행은 오래 걸린다). `build_number`, `runtime_proof_run`은 선택.

순서: 엔진 실행 검증(QuickJS·Codex delegate) → 엔진 빌드 → Release archive → archive 검사(엔진 포함·Info.plist·비공개 API·개인정보 매니페스트·라이선스) → 서명 export → 업로드. Secrets가 없으면 서명 없는 archive까지 만들고 검사한 뒤, 임시 인증서로 키체인·프로파일·ExportOptions 처리만 시험하고 export는 건너뛴다. 결과는 Artifacts의 `youngmin-release-…`(`Youngmin.xcarchive.zip`, 서명했으면 `Youngmin.ipa`)다. 로컬 Mac에서는 엔진을 빌드한 뒤 `bash .github/scripts/ios-archive.sh`로 같은 과정을 돌린다.

## 남는 수동 작업

- TestFlight → 테스트 정보: 베타 앱 설명, 피드백 이메일, 개인정보 처리방침 URL, 심사 연락처와 메모(가상 eTL 진입 방법, 채팅에 OpenAI 로그인이 필요하다는 점; 실제 학교 계정은 넣지 않는다).
- 외부 테스트 그룹을 만들고 빌드를 추가해 Beta App Review에 제출한다(그룹의 첫 빌드는 심사가 필요하고, 내부 테스터는 심사 없이 받는다).
- 심사 통과 후 공개 링크를 켠다.
- 앱 정보·아이콘·스크린샷·개인정보 공개 내용(앱 개인정보) 작성. 앱과 확장에는 required reason API 선언을 넣었지만 서명한 archive에서 한 번 더 확인한다. [Apple 문서](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api)
- 위젯·공유 확장은 full 모드에서만 들어간다. app-only로 낸 뒤 full로 바꾸면 앱 App ID에 App Group을 켜고 앱 프로파일을 다시 만들어야 한다.

**아직 확인하지 못한 것:** 유료 Apple 계정이 없어 실제 서명 export와 업로드는 실행해 보지 못했다. CI는 서명 없는 Release archive 생성·검사와 임시 인증서로 한 서명 준비 과정까지만 확인한다.

이 구현과 자동 테스트는 Apple의 심사 통과나 OpenAI 로그인 경로의 제3자 iOS 배포 승인을 보장하지 않는다. 실제 설치본에서 로그인·AI 도구 호출·승인/취소·모드 전환·초기화를 한 번 더 확인해야 한다.
