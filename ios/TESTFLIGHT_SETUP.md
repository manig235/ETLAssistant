# TestFlight 제출을 위한 Apple 설정

이 문서는 이번 앱의 Bundle ID와 CI 설정 이름을 기준으로 작성했다. 심사 담당자가 본인 ChatGPT 계정으로 로그인하고 FakeETL을 테스트하는 경로를 사용한다.

## 1. App Store Connect에 앱 만들기

[App Store Connect → 앱](https://appstoreconnect.apple.com/apps)에서 기존 앱을 먼저 확인한다. 이미 `kr.snu.etlassistant.ios` 앱이 있으면 그대로 사용한다.
없으면 **+ → 신규 앱(New App)**에서 아래처럼 입력한다.

| 항목 | 값 |
| --- | --- |
| 플랫폼 | iOS |
| 이름 | 영민이 (이름 사용 가능 여부는 Apple 화면에서 확인) |
| 기본 언어 | 한국어 |
| 번들 ID | `kr.snu.etlassistant.ios` |
| SKU | `youngmin-ios` (계정 안에서 중복되지 않는 내부 식별자) |

생성 후 **일반 → 앱 정보 → Apple ID**의 숫자를 확인한다. GitHub Variable `ASC_APP_ID`에는 이 숫자를 넣는다. Bundle ID나 Apple 로그인 이메일을 넣지 않는다.
최신 계약 동의 안내가 나오면 계정 소유자가 먼저 처리한다.
[Apple 공식 절차](https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app)

## 2. App Store Connect용 프로파일 세 개 만들기

[Apple Developer → Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/profiles/list)에서 **Profiles → + → Distribution → App Store Connect**를 선택한다. **Ad Hoc을 선택하지 않는다.**

아래 세 개를 각각 만든다. 인증서는 현재 Ad Hoc 서명에 사용하는 **동일한 Apple Distribution 인증서**를 선택한다.

| 대상 | 선택할 App ID / Bundle ID | 프로파일 이름 예시 | 저장할 GitHub Secret |
| --- | --- | --- | --- |
| 앱 | `kr.snu.etlassistant.ios` | `Youngmin AppStore App` | `IOS_APPSTORE_APP_PROFILE_BASE64` |
| 위젯 | `kr.snu.etlassistant.ios.widgets` | `Youngmin AppStore Widgets` | `IOS_APPSTORE_WIDGETS_PROFILE_BASE64` |
| 공유 확장 | `kr.snu.etlassistant.ios.share` | `Youngmin AppStore Share` | `IOS_APPSTORE_SHARE_PROFILE_BASE64` |

각각 **Continue → 인증서 선택 → 이름 입력 → Generate → Download**로 `.mobileprovision`을 받는다.
App Store Connect 프로파일에는 테스터 기기 선택 단계가 없다.

세 App ID 모두 **App Groups**에 `group.kr.snu.etlassistant.ios`가 연결돼 있어야 한다. 변경했다면 변경 후 프로파일을 다시 생성한다.
설정 권한은 Account Holder 또는 Admin이 필요하다.
[프로파일 공식 절차](https://developer.apple.com/help/account/provisioning-profiles/create-an-app-store-provisioning-profile),
[App Groups 설정](https://developer.apple.com/help/account/identifiers/enable-app-capabilities/)

## 3. App Store Connect 팀 API 키 만들기

[App Store Connect → 사용자 및 액세스](https://appstoreconnect.apple.com/access/users)에서 **통합(Integrations) → App Store Connect API → 팀 키(Team Keys)**로 들어간다.

1. 처음이면 계정 소유자가 **접근 권한 요청(Request Access)**을 완료한다. 승인 대기 중에는 다음 단계로 진행할 수 없다.
2. **+ / Generate API Key**를 누른다. 이름 예: `Youngmin CI Upload`.
3. 현재 CI의 빌드 업로드 용도에는 **Developer** 역할을 선택한다. 심사 제출 등 추가 관리 권한도 필요할 때만 App Manager를 선택한다.
4. 키를 생성한 뒤 `.p8` 파일을 다운로드한다. **한 번만 다운로드할 수 있다.**
5. 화면의 **Key ID**, **Issuer ID**도 기록한다. 현재 CI는 Issuer ID를 사용하는 팀 키 경로로 구성돼 있다.

| GitHub 항목 | 넣을 값 |
| --- | --- |
| Secret `ASC_KEY_P8` | 다운로드한 `AuthKey_<KeyID>.p8` 파일 전체 내용. Base64로 바꾸지 않음 |
| Variable `ASC_KEY_ID` | 화면의 Key ID |
| Variable `ASC_ISSUER_ID` | 화면의 Issuer ID |

[API 키 공식 절차](https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-api),
[업로드 가능 역할](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds)

## 4. GitHub에 등록하기

[저장소 Settings → Secrets and variables → Actions](https://github.com/manig235/ETLAssistant/settings/secrets/actions)를 연다.

- **Secrets 탭**: 프로파일용 Secret 세 개와 `ASC_KEY_P8`을 등록한다.
- **Variables 탭**: `ASC_APP_ID`, `ASC_KEY_ID`, `ASC_ISSUER_ID`를 등록한다.
- 기존 `IOS_TEAM_ID`, `IOS_DISTRIBUTION_P12_BASE64`, `IOS_DISTRIBUTION_P12_PASSWORD`를 사용한다.
- 비밀번호와 `.p8` 내용을 채팅이나 Git 저장소에 붙이지 않는다.

현재 Linux 작업 환경에서 GitHub CLI를 사용할 때는, 다운로드한 파일의 실제 경로를 넣어 아래처럼 등록할 수 있다. Base64 결과나 키 내용을 화면에 출력하지 않는다.

```bash
base64 -w 0 /path/to/Youngmin_AppStore_App.mobileprovision | gh secret set IOS_APPSTORE_APP_PROFILE_BASE64 --repo manig235/ETLAssistant
base64 -w 0 /path/to/Youngmin_AppStore_Widgets.mobileprovision | gh secret set IOS_APPSTORE_WIDGETS_PROFILE_BASE64 --repo manig235/ETLAssistant
base64 -w 0 /path/to/Youngmin_AppStore_Share.mobileprovision | gh secret set IOS_APPSTORE_SHARE_PROFILE_BASE64 --repo manig235/ETLAssistant
gh secret set ASC_KEY_P8 --repo manig235/ETLAssistant < /path/to/AuthKey_XXXXXXXXXX.p8
gh variable set ASC_KEY_ID --repo manig235/ETLAssistant --body '화면의 Key ID'
gh variable set ASC_ISSUER_ID --repo manig235/ETLAssistant --body '화면의 Issuer ID'
gh variable set ASC_APP_ID --repo manig235/ETLAssistant --body '앱의 숫자 Apple ID'
```

## 5. 설정 후 확인할 순서

1. 이 브랜치의 PR을 main에 반영해 **iOS TestFlight** 수동 워크플로를 기본 브랜치에서 사용할 수 있게 한다. 반영 전에는 `Run workflow`가 표시되지 않을 수 있다.
2. **Actions → iOS TestFlight → Run workflow**에서 제출할 브랜치를 고르고 **upload는 끈다**. 이 실행은 필수 설정·P12 검사 → 프로파일·서명 검사 → 네이티브 빌드 → Swift Release 테스트 → App Store Connect용 IPA 서명·검증 순서다. 필수 설정이 빠지면 Mac을 실행하기 전에 빠진 이름을 알려준다. 첫 실행에는 `ASC_*` 없이도 빌드를 확인할 수 있다.
3. 빌드 검사 성공 후 **upload를 켜고** 실행한다. App Store Connect용 IPA는 Ad Hoc IPA처럼 직접 기기에 설치하지 않는다.
4. Apple의 빌드 처리가 끝나면 App Store Connect의 내부 테스트 그룹에 빌드를 배정해 본인 기기에 설치한다.
5. [심사 메모](TESTFLIGHT_REVIEW_NOTES.txt)의 FakeETL·ChatGPT 로그인·과제 조회·자료 요약·일정 승인/취소를 실제 설치본에서 확인한다.
6. **TestFlight → 테스트 정보**에서 피드백 이메일·심사 연락처 등 화면에 요구되는 정보를 입력한다. 개인정보처리방침 URL은 로그인 없이 열리는 최신 iOS용 문서를 사용한다.
7. **베타 앱 심사 정보 → 메모**에 [영문 심사 메모](TESTFLIGHT_REVIEW_NOTES.txt)를, 빌드의 **테스트할 내용**에 [한글 테스트 안내](TESTFLIGHT_WHAT_TO_TEST.txt)를 붙여 넣는다.
8. 외부 그룹에 빌드를 추가하고 베타 심사에 제출한다.

정식 앱스토어 출시는 별도다. TestFlight 빌드 업로드만으로 외부 심사 제출이나 지인 배포가 자동으로 진행되지는 않는다.

## 인증서 오류별 조치

- `PKCS12_PASSWORD_REJECTED`: 현재 `.p12`와 GitHub의 비밀번호가 맞지 않는다. `.p12`를 내보낼 때 지정한 비밀번호로 수정한다. 파일도 교체했다면 Base64 Secret도 함께 교체한다.
- `PKCS12_UNSUPPORTED_ENCRYPTION`: 구형 PKCS#12 암호화 형식일 수 있다. 수정된 스크립트는 OpenSSL의 legacy provider로 다시 읽되 MAC·비밀번호 검증은 유지한다.
- `security import ... PKCS12_PASSWORD_OR_FORMAT_REJECTED`: macOS 가져오기가 거부한 경우 원본 검증 후 임시 호환 사본을 사용한다. 원본 인증서·개인키는 바꾸지 않는다.

비밀번호를 잊었고 암호화된 개인키의 비밀번호도 모른다면 재발급 경로를 준비해야 한다. 이 경우 기존 인증서를 먼저 취소하지 않고 새 CSR·인증서·프로파일을 마련한다.
[OpenSSL PKCS#12 설명](https://docs.openssl.org/3.0/man1/openssl-pkcs12/)
