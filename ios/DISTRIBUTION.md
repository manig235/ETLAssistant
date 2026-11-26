# iOS 배포 안내

현재 구현된 워크플로는 지인용 Ad Hoc / Firebase 배포다.
기기 등록 없이 일반 사용자에게 링크를 전달할 때의 TestFlight·App Store 절차는 문서 후반에 정리한다.

첫 실기기 로그인 확인에는 **publish를 끄고** 생성한 서명 IPA를 사용한다.
이 모드에서는 Firebase에 업로드하거나 테스터에게 배포하지 않는다.
기존 **iOS Native Codex IPA** 워크플로와 Windows 개인 서명 설치 경로도 사용할 수 있다.
Apple Developer Program 가입 후 아래 서명 설정을 채우고, 로그인 확인 뒤 Firebase 배포를 진행한다.

새 워크플로 **iOS Ad Hoc Firebase** (`.github/workflows/ios-firebase.yml`)는 앱을 Release로 archive하고,
Apple Distribution 인증서로 Ad Hoc 서명한 뒤 Firebase App Distribution으로 전달한다.
앱 본체·위젯·공유 확장과 내장 Codex를 포함한다. 기존 unsigned Debug IPA 워크플로는 그대로 사용한다.
Swift 앱은 Release 설정을 사용한다. Rust 라이브러리는 기존 검증 경로의 `dev-small` 프로파일을
재사용하며 최적화 수준 1, debug 정보·debug assertion·overflow check를 끄고 별도 캐시에 빌드한다.
이는 Rust `release`/LTO 빌드를 검증했다는 의미는 아니다.
네이티브 링크에는 해당 Rust 빌드가 생성한 `native-static-libs.txt`를 사용하고, IPA의 Code Mode 런타임이
현재 내장 실행기인 QuickJS인지도 확인한다. 캐시 키에는 JavaScript 브리지 소스와 시스템 라이브러리 목록을 포함한다.
엔진 컴파일을 건너뛰는 재사용은 성공한 컴파일 표시와 정확한 소스·빌드 옵션 키가 모두 일치할 때만 허용한다.
일부만 일치하는 캐시나 이전 표시가 없는 캐시는 Cargo 빌드를 거친다.

## 나중에 한 번 준비할 것

1. Apple Developer Program 개인 멤버십을 활성화한다. 본인 확인·활성화에 시간이 걸릴 수 있다.
2. Apple Distribution 인증서와 해당 개인키를 비밀번호가 있는 `.p12`로 묶는다.
3. 아래 세 App ID와 App Group을 등록하고, **세 App ID 모두 같은 App Group을 활성화**한다.
4. 설치할 기기들의 UDID를 Apple Developer의 Devices에 등록한다.
5. **세 App ID마다 Ad Hoc 프로파일을 하나씩** 생성한다. 같은 배포 인증서와 같은 기기 목록을 선택하고,
   이름은 `Youngmin AdHoc App`, `Youngmin AdHoc Widgets`, `Youngmin AdHoc Share`처럼 서로 다르게 한다.
6. Firebase 프로젝트에 Bundle ID `kr.snu.etlassistant.ios`로 iOS 앱을 등록하고,
   App Distribution의 **Get started**를 완료한다. 배포에만 사용하므로 Firebase SDK나
   `GoogleService-Info.plist`를 앱에 추가하지 않는다.
7. Firebase App Distribution에서 테스터 그룹(예: alias `friends`)을 만들고 지인을 추가한다.
8. 해당 Firebase 프로젝트에 **Firebase App Distribution Admin** 역할을 가진 전용 서비스 계정을 만들고,
   비공개 JSON 키를 생성한다. 이 키는 앱에 넣지 않고 GitHub Secret으로만 등록한다.

| 대상 | Bundle ID / Group ID |
| --- | --- |
| 앱 | `kr.snu.etlassistant.ios` |
| 위젯 | `kr.snu.etlassistant.ios.widgets` |
| 공유 확장 | `kr.snu.etlassistant.ios.share` |
| 공통 App Group | `group.kr.snu.etlassistant.ios` |

테스터에게 Mac이나 유료 개발자 계정은 필요 없다. 다만 **출시 버전이 없는 그룹 초대 링크는
이메일을 테스터 그룹에 등록하는 단계에서 끝난다. 가입 완료는 UDID 수집 완료가 아니다.**
IPA 완성을 기다리는 동안 다른 지인의 UDID도 먼저 받을 수 있다. PC 없이 확인하는 선택 경로와
공유용 안내는 [테스터 UDID 안내](TESTER_UDID_GUIDE.md)를 따른다. 이 경로는 Firebase 자동 수집과 별개다.
최초 배포 전에는 본인 기기 한 대의 UDID를 먼저 확보하고 Apple Devices에 등록한 뒤 세 프로파일에 포함한다.
publish를 끄고 IPA를 생성해 본인 기기에서 로그인 동작을 확인한다.

로그인 확인 후 첫 Ad Hoc 빌드를 Firebase의 테스터 그룹에 배포하면 지인은 다음 순서로 참여한다.

1. 설치할 iPhone/iPad의 Safari에서 그룹 초대 링크를 열고 이메일을 등록한다.
2. Firebase 테스트 초대 이메일을 같은 기기의 Safari에서 열고 Google 계정으로 로그인해 초대를 수락한다.
3. **Register device(기기 등록)**를 누르고 Firebase 구성 프로파일을 다운로드한다.
4. 기기의 설정 앱에서 안내에 따라 프로파일을 설치한다. 이때 Firebase가 UDID를 수집한다.
5. 개발자는 **Testers & Groups → All testers → Export Apple UDIDs**에서 수집된 기기를 확인하고,
   Apple Developer의 Devices에 등록한다. **앱·위젯·공유 확장 프로파일을 모두 갱신**해 같은 기기 목록을 넣고,
   해당 GitHub Secrets 세 개를 교체한 뒤 재빌드·재배포한다.
6. 지인은 새 빌드 준비 알림을 받은 뒤 앱을 설치한다. 기기에서 요구하는 Developer Mode 설정도 따른다.

이 흐름에서 지인이 PC에 기기를 연결할 필요는 없다. 새 기기는 프로파일 갱신·재배포가 끝난 뒤 설치 가능하다.

## GitHub 설정

저장소 **Settings → Secrets and variables → Actions**에 등록한다.

**Secrets**:

| 이름 | 값 |
| --- | --- |
| `IOS_DISTRIBUTION_P12_BASE64` | 개인키가 포함된 배포용 `.p12` 파일의 Base64 |
| `IOS_DISTRIBUTION_P12_PASSWORD` | `.p12`를 내보낼 때 설정한 비밀번호 |
| `IOS_APP_PROFILE_BASE64` | 앱 본체 Ad Hoc `.mobileprovision`의 Base64 |
| `IOS_WIDGETS_PROFILE_BASE64` | 위젯 Ad Hoc `.mobileprovision`의 Base64 |
| `IOS_SHARE_PROFILE_BASE64` | 공유 확장 Ad Hoc `.mobileprovision`의 Base64 |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | 서비스 계정 JSON 파일의 **내용 그대로** (Base64 아님) |

실제 파일은 저장소 밖에 보관한다. GitHub CLI를 사용할 경우 Base64 값을 화면이나 채팅에 출력하지 않고
아래처럼 Secret 입력으로 직접 전달한다. 각 파일이 올바른 저장소로 전달되는지 먼저 확인한다.

```bash
base64 -w 0 /path/to/apple-distribution.p12 | gh secret set IOS_DISTRIBUTION_P12_BASE64 --repo manig235/ETLAssistant
base64 -w 0 /path/to/Youngmin-App.mobileprovision | gh secret set IOS_APP_PROFILE_BASE64 --repo manig235/ETLAssistant
base64 -w 0 /path/to/Youngmin-Widgets.mobileprovision | gh secret set IOS_WIDGETS_PROFILE_BASE64 --repo manig235/ETLAssistant
base64 -w 0 /path/to/Youngmin-Share.mobileprovision | gh secret set IOS_SHARE_PROFILE_BASE64 --repo manig235/ETLAssistant
```

p12 비밀번호는 GitHub의 `IOS_DISTRIBUTION_P12_PASSWORD` Secret 입력란에 직접 입력한다.
비밀번호·개인키·p12 내용은 채팅, 워크플로 입력, 로그에 남기지 않는다.

**Variables**:

| 이름 | 값 |
| --- | --- |
| `IOS_TEAM_ID` | Apple 계정 Membership details의 10자리 Team ID |
| `FIREBASE_APP_ID` | Firebase 프로젝트 설정의 iOS App ID (`1:123456789:ios:abc123` 형태), Bundle ID 아님 |
| `FIREBASE_GROUPS` | 테스터 그룹 alias. 여러 개면 쉼표로 구분 (`friends,qa`) |

Apple 계정 비밀번호나 2단계 인증 코드는 CI에 등록하지 않는다. 이 수동 서명 방식은 App Store Connect API 키도 필요 없다.
서명 자료는 임시 keychain·프로파일 경로에만 설치하고 성공·오류·정상 취소 시 정리한다.
강제 프로세스 종료 시에는 GitHub가 폐기하는 일회용 hosted runner가 정리 경계다. Self-hosted runner용 구성은 아니다.
아티팩트는 검증된 IPA·해시·빌드 정보·dSYM만 포함하고, 비밀키·원본 프로파일·서비스 계정 JSON·원본 Firebase 로그는
별도로 업로드하지 않는다. **IPA 내부에는 설치에 필요한 프로비저닝 프로파일과 등록 기기 정보가 포함**된다.

## 실행

1. 워크플로가 기본 브랜치에도 반영된 뒤 **Actions → iOS Ad Hoc Firebase → Run workflow**를 연다.
2. 배포할 코드가 있는 브랜치를 선택한다. 처음에는 **publish를 끈 채** 실행하면 서명된 IPA만 생성한다.
   이 모드에는 Firebase 설정이 없어도 된다. Apple 서명 설정과 세 프로파일은 필요하다.
3. native Codex 빌드 → Swift Release 단위 테스트 → Release archive → 수동 Ad Hoc export 순서로 실행한다.
   빠진 설정은 시간이 오래 걸리는 컴파일 전에 항목명만 출력하고 실패한다.
4. Artifacts의 `youngmin-adhoc-<run>-<attempt>`를 받아 `Youngmin-AdHoc.ipa`를 확인한다.
5. 배포할 때 **publish를 켜고** 변경 사항을 입력해 실행한다. 지정한 Firebase 그룹으로 전달되며
   실행 Summary에 테스터 설치 링크가 표시된다. push·PR로는 서명 빌드나 배포가 실행되지 않는다.

첫 실행의 **가상 eTL로 테스트하기**는 Release에도 포함되며 실제 AI와 가상 학교 자료를 사용한다.
별개인 Debug 전용 **샘플 데이터로 둘러보기**는 고정 답변을 보여 주는 UI 시연으로, Release에 포함되지 않는다.
빌드 번호는 Actions 실행 번호와 재실행 번호(`<run>.<attempt>`)를 앱과 확장에 동일하게 적용한다.
같은 workflow를 재실행해도 새 빌드 번호가 생긴다. 버전 이름은 프로젝트의 `MARKETING_VERSION`을 따른다.
아티팩트 보관 기간은 7일이다. IPA와 dSYM은 필요하면 따로 보관한다.
인증서·프로파일 만료 또는 기기 추가 시 갱신한 자료로 같은 과정을 다시 실행한다.

## 검증과 현재 한계

로컬에서 `python3 scripts/test-ios-firebase.py`로 잘못된 프로파일, 배포 종류, 기기 목록 불일치,
설정 누락, 업로드 파일 변조, 비밀 파일 정리 경로를 합성 데이터로 검사한다. PR에서도 이 검사만 실행한다.
내보낸 IPA는 실제 서명 검증, 앱·위젯·공유 확장 존재, embedded profile의 Team ID·Bundle ID·인증서·기기 목록·만료,
App Group, 디버거 허용 해제, 내장 Codex 설정, 버전 일치와 파일 해시를 확인한 뒤 전달한다.
실제 배포용 인증서·프로파일을 연결한 **macOS 서명·export와 Firebase 업로드는 미검증**이다.
첫 유료 계정 설정 후 publish를 끄고 실제 빌드를 확인하고, 등록 기기에서 설치·로그인·확장 동작을 확인한다.

참고: [Apple Ad Hoc 프로파일](https://developer.apple.com/help/account/provisioning-profiles/create-an-ad-hoc-provisioning-profile),
[Firebase CLI 배포](https://firebase.google.com/docs/app-distribution/ios/distribute-cli),
[Firebase 서비스 계정](https://firebase.google.com/docs/app-distribution/authenticate-service-account?platform=ios),
[Firebase 기기 추가](https://firebase.google.com/docs/app-distribution/register-additional-devices).

## 기기 등록 없이 일반 사용자에게 링크로 배포하기

아래 두 방법은 사용자 UDID 수집·등록 없이 배포한다. 사용자에게 Mac이나 유료 개발자 계정이 필요하지 않다.
개발자는 현재 가입한 Apple Developer Program을 사용한다. 가입을 다시 할 필요는 없다.
아래는 추후 진행할 절차이며, 현재 추가한 Ad Hoc/Firebase 워크플로는 TestFlight·App Store에 업로드하지 않는다.

### TestFlight 외부 테스트: 정식 출시 전 베타 링크

1. App Store Connect에서 현재 앱의 Bundle ID로 앱 레코드를 만든다.
2. 앱·위젯·공유 확장의 App ID와 App Group을 맞추고, App Store Connect 배포용 서명을 준비한다.
   Apple Distribution 인증서는 사용할 수 있지만 Ad Hoc 프로파일을 그대로 쓰지 않는다.
3. GitHub의 macOS 빌드에서 Release archive를 App Store Connect용으로 export·업로드하는 경로를 추가한다.
   자동 업로드용 App Store Connect API 키 등을 별도로 설정한다. 로컬 Mac 소유는 필수가 아니다.
4. App Store Connect의 TestFlight에서 빌드 처리와 수출 규정 질문 등 필요한 입력을 완료한다.
5. 내부 테스트 그룹을 먼저 만들고, 외부 테스트 그룹을 만든다. 베타 설명·피드백 이메일·연락처·테스트 항목과
   심사자의 접근 방법을 입력하고 첫 외부 빌드를 TestFlight 심사에 제출한다.
6. 승인된 빌드를 외부 그룹에 배정하고 공개 초대 링크를 활성화한다.
7. 사용자는 링크를 열어 TestFlight 앱을 설치하고 베타 앱을 받는다. 이후 새 빌드도 TestFlight로 전달한다.

최대 외부 테스터 수는 10,000명이고, 각 빌드는 업로드 후 90일간 테스트 가능하다.
베타 사용을 계속하려면 새 빌드를 공급해야 한다. 첫 외부 빌드는 심사가 필요하며,
같은 버전의 후속 빌드는 전체 심사가 생략될 수 있다. TestFlight 승인은 App Store 정식 출시 승인과 별개다.
[외부 테스트 절차](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers),
[TestFlight 개요](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/).

### App Store 공개 배포: 정식 앱 설치 링크

1. 위와 같이 App Store Connect 앱 레코드와 업로드할 Release 빌드를 준비한다.
2. 앱 이름·설명·카테고리·스크린샷·지원 URL·개인정보처리방침 URL을 준비한다.
3. 앱 개인정보, 연령 등급, 콘텐츠 권리와 수출 규정 등 표시되는 질문을 정확히 작성한다.
4. 가격·배포 국가/지역·공개 배포 방법을 설정한다.
5. 심사 연락처, 로그인 방법 또는 협의된 데모 접근 방법, 외부 Canvas/AI 서비스 설명을 준비한다.
6. 출시 버전에 빌드를 선택해 App Review에 제출하고, 질문·반려가 있으면 답변하거나 수정 빌드를 제출한다.
7. 승인 후 수동 또는 설정한 출시 방식으로 공개하고 App Store 앱 페이지 링크를 공유한다.
   사용자는 App Store에서 설치하며 TestFlight나 기기 등록용 프로파일을 사용하지 않는다.

TestFlight를 거치지 않고 정식 심사를 신청할 수도 있다. 업데이트도 App Store Connect로 제출한다.
[App Store Connect 전체 절차](https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-workflow/),
[공개 배포 설정](https://developer.apple.com/help/app-store-connect/manage-your-apps-availability/set-distribution-methods/).

### 영민이 심사 접근 준비

eTL 계정이 없는 심사자가 기능을 확인할 수 있도록 접근 방법을 준비한다.
데모 계정을 보안·법적 사유로 제공하기 어려우면 Apple의 사전 승인을 받아 전체 기능을 보여 주는
내장 데모 모드로 대체할 수 있다는 지침을 확인하고 협의한다. fakeETL 샘플 데이터와 자료로
실제 UI·처리 흐름을 체험하도록 하는 방안을 검토하되, 가상 데이터 범위와 실제 Canvas 인증이 필요한 부분을
명시한다. 데모 모드 구현만으로 승인된다고 가정하지 않는다.
최신 앱은 Release에서도 첫 실행의 **가상 eTL로 테스트하기**로 학교 계정 없이 접근할 수 있다.
채팅에는 실제 ChatGPT 로그인 또는 OpenAI API 키가 필요하며 가상 모드에서는 캘린더·위젯 연결을 제한한다.
이 접근만으로 전체 심사 요건을 충족한다고 가정하지 않는다.
현재 기능, 심사 메모 초안과 남은 확인은 [TestFlight 심사 준비](TESTFLIGHT_REVIEW.md)를 따른다.
[Apple 심사 지침 2.1(a)](https://developer.apple.com/app-store/review/guidelines/#app-completeness).
