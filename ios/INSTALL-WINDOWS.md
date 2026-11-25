# Windows에서 아이패드·아이폰에 영민이 설치하기

Windows PC와 USB 케이블, iPadOS/iOS 17 이상 기기, 무료 Apple 계정으로 앱 본체를 설치하는 테스트 경로다. 맥이나 유료 Apple Developer Program 가입은 필요 없다. 기기용 앱은 GitHub Actions에서 빌드하고, 기기에 설치할 서명은 Windows의 Sideloadly에서 한다.

## 1. IPA 받기

1. [GitHub Actions → iOS Device IPA](https://github.com/manig235/ETLAssistant/actions/workflows/ios-device-ipa.yml)를 연다. GitHub에 로그인해야 결과물을 내려받을 수 있다.
2. 최신 성공 실행을 열거나, **Run workflow → Branch: main → Run workflow**를 누른다. 수동 실행 버튼은 이 워크플로가 main에 머지된 뒤 표시된다. PR 검증 실행에서도 IPA를 내려받을 수 있다.
3. `build-device-ipa`가 성공하면 실행 페이지 아래 **Artifacts → youngmin-device-ipa-…**를 누른다.
4. 다운로드된 바깥 ZIP의 압축을 푼다. `Youngmin-unsigned.ipa`와 이 안내문이 나온다. **IPA 파일 자체는 압축을 풀지 않는다.**

IPA는 아직 서명되지 않은 파일이므로 아이패드의 파일 앱에서 탭하는 것만으로 설치되지 않는다. 다음 단계에서 Windows로 서명·설치한다. `Youngmin-unsigned.json`에는 버전·최소 OS·빌드 커밋·실기기 플랫폼 정보가, `.sha256`에는 파일 확인용 해시가 들어 있다. GitHub 결과물은 7일간 보관한다.

## 2. Windows 준비

1. [Sideloadly 공식 사이트](https://sideloadly.io/)에서 Windows용 프로그램을 설치한다.
2. 같은 사이트 **Before you install**에 연결된 Apple의 웹 설치본 **iTunes와 iCloud**를 설치한다. Sideloadly는 Microsoft Store판 대신 웹 설치본을 안내한다. 이미 설치된 Apple 기기 프로그램과 충돌하면 [Sideloadly FAQ](https://sideloadly.io/faq.html)의 Windows 연결 오류 항목을 확인한다.
3. 아이패드를 USB로 연결하고 잠금을 해제한다. 아이패드에 **이 컴퓨터를 신뢰하겠습니까?**가 나오면 신뢰를 선택하고 기기 암호를 입력한다.
4. iTunes에서 기기가 인식되는지 확인한 뒤 Sideloadly를 연다.

## 3. IPA 서명·설치

1. Sideloadly의 기기 선택에서 아이패드를 고른다.
2. `Youngmin-unsigned.ipa`를 Sideloadly 창으로 끌어 놓는다.
3. **Apple ID**에 본인 Apple 계정을 입력하고 **Start**를 누른다.
4. 요청되는 비밀번호와 2단계 인증은 본인 PC의 Sideloadly/Apple 인증 화면에서 입력한다. GitHub Secrets나 이 채팅에는 계정 비밀번호·인증 코드를 넣을 필요가 없다.
5. 서명과 설치가 끝날 때까지 기다린다. 서명용 번들 ID는 기본 자동 처리를 사용하고, 이후 갱신에도 같은 Apple 계정과 앱 ID를 유지한다.
6. 아이패드에서 **설정 → 일반 → VPN 및 기기 관리**로 가서 해당 개발자 계정을 신뢰한다. iPadOS 버전에 따라 메뉴 이름은 다를 수 있다.
7. **설정 → 개인정보 보호 및 보안 → 개발자 모드**를 켠다. 안내에 따라 재시동하고 다시 확인한다. 메뉴가 없으면 우선 설치·기기 연결을 마친 뒤 확인한다.
8. 홈 화면에서 **영민이**를 연다.

일반 Apple 계정으로 설치하는 경로이며, 탈옥은 필요 없다. 설치·서명 중 나온 구체적인 오류는 Sideloadly 로그에서 확인할 수 있다.

## 4. 처음 실행해서 확인하기

- 계정 연결 없이 먼저 보려면 환영 화면을 아래로 내려 **샘플 데이터로 둘러보기**를 누른다. 이 IPA는 해당 메뉴가 있는 Debug 빌드다.
- 실제 eTL 조회를 확인하려면 샘플 모드를 켜기 전에 정상 온보딩에서 eTL 토큰과 비서 계정을 연결한다. 샘플 모드에서는 조회·답변을 흉내 낸다.
- 이 설치 파일은 **앱 본체만 포함**한다. 무료 서명에서 App Group과 추가 앱 ID가 필요한 위젯·공유 확장을 피하려고 패키징할 때 제외한다. 다른 앱의 공유 메뉴에서 영민이로 보내기와 홈 화면 위젯은 이 IPA로 확인할 수 없다. 앱 안에서 파일을 첨부하는 흐름은 별개다.
- 앱은 현재 아이폰 UI 기준이므로 아이패드에서 아이폰 호환 화면으로 표시될 수 있다. 아이패드 전용 레이아웃을 추가한 빌드는 아니다.
- 실제 기기의 설치 성공, 인증, 권한, 백그라운드 동작은 사용자 기기에서 확인해야 한다. 클라우드 빌드 성공은 실기기 실행 검증을 뜻하지 않는다.

## 5. 7일 만료와 갱신

무료 계정 서명은 보통 7일 뒤 만료된다. 만료 전에 같은 계정·같은 앱 ID로 Sideloadly에서 갱신한다. 자동 갱신도 설정할 수 있지만 PC가 실행 중이고 기기가 USB나 지원되는 Wi-Fi 연결로 감지되어야 한다. 앱이 안 열린다고 먼저 삭제하면 앱 데이터가 사라질 수 있으니 우선 같은 앱으로 다시 서명·설치를 시도한다.

[Sideloadly FAQ의 갱신 안내](https://sideloadly.io/faq.html)를 참고한다. 무료 계정은 기기당 설치 앱 수와 일정 기간 생성 가능한 앱 ID 수에도 제한이 있다.

## 오류가 나면

- 기기가 안 보임: USB 연결·잠금 해제·컴퓨터 신뢰·iTunes 인식을 확인한다.
- `Developer Mode Required`: 개발자 모드를 켜고 재시동한다.
- 개발자를 신뢰할 수 없음: VPN 및 기기 관리에서 본인 개발자 계정을 신뢰한다.
- `IncorrectArchitecture`: 시뮬레이터 결과물이 아닌 `iOS Device IPA`의 IPA를 받았는지 확인한다.
- 설치 앱 수/앱 ID 제한: [Sideloadly FAQ](https://sideloadly.io/faq.html)의 해당 오류를 확인한다.
- 설치는 됐지만 실행 중 종료됨: 기기 모델·iPadOS 버전·종료되는 단계를 알려 준다. Sideloadly 기기 로그나 오류 화면에서 Apple 계정·인증 정보는 가리고 공유한다.

## 빌드와 검증 범위

`.github/scripts/ios-device-ipa.sh`가 `iphoneos` SDK와 `generic/platform=iOS` 대상으로 arm64 앱을 빌드한다. 서명과 App Group entitlement는 이 빌드에서 비활성화하며, 프로젝트의 정식 앱·위젯·공유 확장 설정은 그대로 둔다.

패키징 스크립트는 앱 사본에서 확장과 기존 서명 파일을 제외하고 `Payload/Youngmin.app/…` 구조로 묶는다. Info.plist의 `iPhoneOS`, 실행 파일의 arm64 아키텍처와 Mach-O iOS 플랫폼을 함께 확인하므로, 같은 arm64라도 시뮬레이터 바이너리는 거부한다.
