# chat-onboarding — 시작 화면·첫 실행 안내·채팅 화면 명세

원본: `$ANDROID` = PORTING.md에 적힌 읽기 전용 사본. 아래 경로는 `$SRC/` = `$ANDROID/app/src/main/java/kr/snu/etlassistant/`, `$RES/` = `$ANDROID/app/src/main/res/` 기준.
JSON 와이어프레임: `wireframe-chat-onboarding.json` (같은 폴더).

---

## 0. 공통 토큰 (모든 화면이 참조)

### 0.1 색 (Montage `DesignSystemTheme.colors`)

Compose 화면은 전부 Montage(`com.github.wanteddev:montage-android:v4.0.3`)의 `DesignSystemTheme { }` 안에서 그린다. `res/values/colors.xml`의 `bubble_*`, `chip_*`, `banner_*`는 **옛 View 화면용이라 Compose 채팅에서는 쓰이지 않는다** (iOS `Theme.swift`의 `YMColor.bubbleUser = #2F6FED/#3D7BFF`는 실제 화면과 다름, 아래 참고).
Montage 라이브러리는 사본에 들어 있지 않다. "확정"은 원본 주석·문서에 hex가 있는 값, "추정"은 Montage 공개 팔레트에서 옮긴 값이라 실기기 스크린샷과 맞춰 봐야 한다.

| 토큰 | 라이트 | 다크 | 근거 |
|---|---|---|---|
| backgroundNeutralPrimary (화면 배경) | #FFFFFF | #1B1C1E | 확정 (ChatComponents.kt:254 주석 "다크 배경(#1B1C1E)") = `YMColor.background` |
| foregroundNeutralPrimary (본문) | #171719 | #F7F7F8 | 추정 = `YMColor.label` |
| foregroundNeutralSecondary | #37383C α0.61 | #C2C4C8 α0.88 | 추정 = `YMColor.labelSecondary` |
| foregroundNeutralTertiary | #37383C α0.28 | #C2C4C8 α0.43 | 추정 = `YMColor.labelTertiary` |
| foregroundNeutralQuaternary (입력창 placeholder) | ≈ #37383C α0.16–0.28 | ≈ #C2C4C8 α0.28 | 추정 |
| surfaceNeutralPrimary | = 배경과 같은 흰색 | | 확정 (ChatComponents.kt:235 주석) |
| surfaceNeutralSecondary (카드·말풍선·입력창) | ≈ #F4F4F5 | ≈ #2E2F33 | 추정 (흰 배경 위에 옅은 회색) |
| surfaceNeutralTertiary (코드 배경) | ≈ #EAEBEC | ≈ #37383C | 추정 |
| surfaceNeutralInverse / foregroundNeutralInverse | #171719 / #FFFFFF | #F7F7F8 / #171719 | 추정 |
| surfaceBrandPrimary (보내기 버튼, 단계 번호 원) | ≈ #0066FF (blue_50) | ≈ #3385FF (blue_60) | 반확정 (ChatComponents.kt:251-255: 라이트 흰글씨 대비 4.8:1, 다크 = blue_60) |
| surfaceBrandStrong | #005EEB (blue_45) | | 확정 (ChatComponents.kt:252) |
| surfaceBrandSubtle (아이콘 타일, 선택된 대화 배경) | ≈ #EAF2FE | ≈ #0066FF α~0.16 | 추정 |
| foregroundBrandPrimary / lineBrandPrimary | ≈ #0066FF | ≈ #3385FF | 추정 |
| surfaceCautionaryPrimary / foregroundCautionaryPrimary (배너, 시험 태그) | ≈ #FFF4E5 / #FF9200 | ≈ #FF9200 α~0.16 / #FFA938 | 추정 |
| surfacePositivePrimary / foregroundPositivePrimary (성공 카드) | ≈ #E5F9EC / #00BF40 | ≈ #00BF40 α~0.16 / #1ED45A | 추정 |
| surfaceNegativePrimary / foregroundNegativePrimary (실패 카드, 오류 문구) | ≈ #FFEDED / #FF4242 | ≈ #FF4242 α~0.16 / #FF6363 | 추정 |
| surfaceDisablePrimary / foregroundDisablePrimary | ≈ #F4F4F5 / #37383C α0.16 | ≈ #2E2F33 / #C2C4C8 α0.16 | 추정 |
| staticWhite | #FFFFFF | #FFFFFF | 확정 |
| **사용자 말풍선** `UserBubbleBlue` | **#005EEB** | **#005EEB** (두 테마 같음) | 확정 (ChatComponents.kt:256, docs/phase2-log.md:308) |
| 사용자 말풍선 선택 띠 `UserSelectionNavy` | #181B35 | #181B35 | 확정 (ChatComponents.kt:259) |
| brand_navy / brand_robot_blue / brand_ivory (로고) | #181B35 / #85BDE2 / #FFF4DF | 같음 | 확정 ($RES/values/colors.xml) |

### 0.2 글꼴 (Montage `DesignSystemTheme.typography`, Pretendard 기준)

| 토큰 | 크기/줄높이(sp) | 굵기 | 비고 |
|---|---|---|---|
| title1Bold | 36/48 (추정) | Bold | 온보딩 단계 제목, 로그인 코드, 빈 채팅 "무엇을 도와드릴까요?" |
| title2Bold | 28/38 (Montage 스펙 추정) | Bold | 상단바 "영민이", 서랍 "대화", 시트 "AI 모델". **iOS Theme.swift는 22pt로 적혀 있음 → 실기기와 대조 필요** |
| headline1Bold | 18/26 | Bold | 환영 기능 행 제목 |
| headline2Bold | 17/24 | Bold | 대화상자 제목, 공지 시트 제목 |
| body1Bold / body1Medium / body1Regular | 16/24 | 700/500/400 | |
| body1ReadingRegular | 16/26 | 400 | 말풍선 본문, 부제, 개인정보 처리방침 본문 |
| body2Bold / body2Medium / body2Regular | 15/22 | | |
| caption1Bold / caption1Medium / caption1Regular | 12/16 | | 칩, 안내문, NOTICE 줄 |

Montage는 글자 간격이 약간 음수(-0.2~-1%)다. iOS는 `.system(size:weight:)` + `.tracking(-0.2)` 정도로 맞추고 Dynamic Type은 `relativeTo:`로.

### 0.3 Montage 컴포넌트 (iOS에서 직접 만들 것)

| Android | 모양 (추정 포함) | SwiftUI |
|---|---|---|
| `WantedButton(PRIMARY, LARGE, SOLID)` | 높이 48, 모서리 12, 채움 surfaceBrandPrimary, 글씨 흰색 body1Bold, 가로 꽉 참, `isLoading`이면 글자 자리에 흰 스피너 | `Button` + 커스텀 `ButtonStyle` |
| `WantedButton(ASSISTIVE, LARGE, OUTLINED)` | 높이 48, 모서리 12, 1dp 선(line 중립 α0.22), 글씨 foregroundNeutralPrimary body1Bold | 같은 스타일의 outline 변형 |
| `WantedButton(PRIMARY, SMALL, SOLID)` | 높이 32, 모서리 8, label2Bold(13) | |
| `WantedTextButton` | 배경 없음, 글씨 foregroundBrandPrimary body2Bold(추정), 최소 터치 40 | `.buttonStyle(.plain)` + 파란 글씨 |
| `WantedChip(text, isActive)` | 높이 ≈32, 모서리 ≈8, 비활성 = 테두리/옅은 채움 + 중립 글씨, 활성 = 진한 채움(반전) + 흰 글씨 (추정) | `Capsule`이 아니라 `RoundedRectangle(8)` |
| `WantedTextField(title, description, trailingButton, status)` | 위 제목(label1Bold), 모서리 12 테두리 상자, 오른쪽 안에 텍스트 버튼, 아래 설명(caption1), status=Negative면 테두리·설명 빨강 | `VStack{Text; HStack{SecureField; Button}; Text}` |
| `WantedCheckBox(style = Switch)` | Montage 스위치(켜짐 = 브랜드 파랑) | `Toggle` + `.tint(brand)` |
| `WantedLinearProgressIndicator` | 높이 ≈4, 둥근 끝, 트랙 회색·채움 브랜드 | `ProgressView(value:)` 커스텀 또는 `Capsule` 두 겹 |
| `WantedCircularProgressIndicator` | 지름 ≈24 브랜드 스피너 | `ProgressView().tint(brand)` |

### 0.4 Material 아이콘 → SF Symbols

Menu→`line.3.horizontal`, AddComment→`square.and.pencil`(또는 `plus.bubble`), Settings→`gearshape`, DarkMode→`moon`, LightMode→`sun.max`, Tune→`slider.horizontal.3`, KeyboardArrowDown→`chevron.down`, KeyboardArrowRight→`chevron.right`, Info→`info.circle`, Close→`xmark`, ChatBubbleOutline→`bubble.left`, Event→`calendar`, Campaign→`megaphone`, AttachFile→`paperclip`, TaskAlt→`checkmark.circle`, ContentCopy→`doc.on.doc`, Add→`plus`, PhotoLibrary→`photo.on.rectangle`, PhotoCamera→`camera`, Send(outlined, automirrored)→`paperplane`, Stop(outlined)→`stop`, EventNote→`note.text`, CalendarViewWeek→`calendar`, NotificationsNone→`bell`, Edit→`pencil`, DeleteOutline→`trash`, ExpandMore/Less→`chevron.down`/`chevron.up`, PictureAsPdf→`doc.richtext`, Image→`photo`, BrokenImage→`photo.badge.exclamationmark`, Slideshow→`play.rectangle`, TableChart→`tablecells`, Description→`doc.text`, Article→`doc.plaintext`, FolderZip→`doc.zipper`, Code→`chevron.left.forwardslash.chevron.right`, InsertDriveFile→`doc`, Share→`square.and.arrow.up`, Refresh→`arrow.clockwise`, CheckCircle→`checkmark.circle`, ArrowBack→`chevron.left`(iOS 관례) 또는 `arrow.left`(원본 모양), FileDownload→`arrow.down.to.line`, Lock→`lock`.

### 0.5 토스트

Android `Toast`(화면 아래 검은 알약, 약 2초/LONG 3.5초)는 iOS에 없다. 공용 `ToastOverlay`(하단 safe area 위 24pt, 캡슐, surfaceNeutralInverse 배경 + 흰 글씨 body2Medium, 2초 페이드)를 만들어 아래 표의 문구를 그대로 띄운다.

---

## splash — 시작 화면 (런치 스크린)

- Android 진입: `$ANDROID/app/src/main/AndroidManifest.xml:107` (ChatActivity가 LAUNCHER, `android:theme="@style/Theme.ETL.Starting"`), `$RES/values/themes.xml:4`, `$RES/values-v31/themes.xml:2`, `$RES/drawable/etl_launch_background.xml:1`, `$RES/drawable/etl_splash_icon.xml:3`, `$RES/drawable/etl_logo.xml:1`. 분기: `$SRC/chat/ChatActivity.kt:87-96`.
- 목적: 앱 첫 프레임. 그다음 온보딩을 끝냈거나 eTL 토큰이 있으면 채팅, 아니면 첫 실행 안내로 간다 (`OnboardingLogic.shouldSkip(completed, hasEtlToken)` = `completed || hasEtlToken`, `$SRC/onboarding/OnboardingLogic.kt:29`).
- 레이아웃:
  - 전체 배경 `brand_navy #181B35`. 상태바·내비게이션바도 같은 남색, 아이콘은 밝은색(`windowLightStatusBar=false`).
  - Android 12+ (`values-v31`): 시스템 스플래시. 배경 `#181B35`, 가운데 아이콘 = `etl_logo`를 사방 25% 안쪽으로 넣은 것(`etl_splash_icon`), 원형 마스크. 로고 타일 자체도 남색이라 타일 경계는 보이지 않고 **남색 위에 하늘색 로봇만 약 144dp 크기로 가운데** 보인다.
  - Android 11 이하: `windowBackground` = 남색 + 가운데 `etl_logo` 144×144dp (같은 모습).
  - 로고(108×108 뷰포트, 전체 `translateY=-1`):
    - 배경 사각형 `#181B35`.
    - 로봇 몸(하늘색 `#85BDE2`): 머리 꼭대기 (54,14), 몸 폭 24–84, 양옆 팔(바깥 끝 x≈10/98, y 63–77), 아래 두 다리(35–50, 58–73, 바닥 y=96). path는 `etl_logo.xml`·`robot_b3_mark.xml`의 두 번째 path 그대로.
    - 얼굴 창(아이보리 `#FFF4DF`): x 31–77, y 32–58, 양 끝 반원(반지름 13)인 알약.
    - 눈 두 개(남색): 중심 (44,45), (64,45), 반지름 3.5.
  - `robot_b3_mark.xml`은 남색 배경 사각형만 뺀 같은 로봇(투명 배경).
- 상태: 없음(정적). 표시 시간은 시스템이 정한다(첫 Compose 프레임까지).
- 동작: 자동 → `chat` (안내 끝/토큰 있음), → `onboarding-welcome` (그 밖).
- SwiftUI 메모: Info.plist `UILaunchScreen` { `UIColorName` = `BrandNavy`(#181B35, 다크 모드도 같은 값), `UIImageName` = `robot_b3_mark`(투명 배경 PDF/SVG 벡터, 144pt 가운데), `UIImageRespectsSafeAreaInsets` = false }. 첫 화면 결정은 `RootView`에서 `OnboardingPrefs.completed || tokenStore.hasToken`으로 `ChatView`/`OnboardingView` 선택, 전환은 페이드. 상태바 스타일은 런치 동안 `.lightContent`.

---

## onboarding-welcome — 1. 환영

- Android 진입: `$SRC/onboarding/OnboardingScreens.kt:261` (`WelcomeStep`), 틀 `OnboardingRoot` :99, `TopProgress` :132, `StepFrame` :158. 액티비티 `$SRC/onboarding/OnboardingActivity.kt:31`.
- 목적: 앱 소개와 개인정보 안내.
- **공통 온보딩 틀** (모든 onboarding-* 단계 공통):
  - 루트 `Column` 배경 backgroundNeutralPrimary, 시스템바·키보드 패딩(edge-to-edge).
  - 상단 진행줄(높이 56, 좌우 8): [왼쪽] 환영 단계는 48 빈칸, 그 밖은 `IconButton(ArrowBack, "이전")` 48×48, 틴트 foregroundNeutralPrimary → [가운데] `WantedLinearProgressIndicator` 가로 꽉 참(좌우 8 안쪽 여백), 값 = (단계 인덱스+1)/5 → 0.2/0.4/0.6/0.8/1.0 → [오른쪽] 48 빈칸.
  - 단계 전환 애니메이션: `AnimatedContent` fadeIn 220ms + fadeOut 120ms(슬라이드 없음).
  - 하드웨어 뒤로: 환영 단계가 아니면 이전 단계(`BackHandler`), 환영 단계면 앱 종료.
  - `StepFrame`: 스크롤 영역(좌우 24) = 위 12 여백 → (header) → 제목 title1Bold foregroundNeutralPrimary → (부제 있으면 10 여백 + body1ReadingRegular foregroundNeutralSecondary) → 28 여백 → 내용 → 24 여백. 그 아래 **스크롤 밖 고정 하단 버튼 영역**(좌우 24, 위아래 16, 버튼 사이 6).
  - 카드 `Card`: 가로 꽉 참, 모서리 16, 배경 surfaceNeutralSecondary, 안쪽 16.
  - 상태 카드 `StatusCard(text, positive)`: 모서리 16, 안쪽 16, 배경 surfacePositivePrimary(실패면 surfaceNegativePrimary), 아이콘 `CheckCircle` 22(틴트 foregroundPositivePrimary/foregroundNegativePrimary — 실패여도 같은 체크 아이콘) + 10 + 글 body1Medium foregroundNeutralPrimary.
- 레이아웃(위→아래):
  1. header: 로고 이미지 `etl_logo`(남색 타일+로봇) 120×120dp, 모서리 24 클립, 가로 가운데. 접근성 "영민이 로고". 아래 28 여백.
  2. 제목: "안녕하세요,\n영민이에요" (두 줄, 왼쪽 정렬).
  3. 부제: "마감과 공지는 챙기고, 궁금한 건 물어보고, 강의 파일은 바로 받아 보세요."
  4. 기능 행 3개(사이 20). 각 행 = 아이콘 타일 48×48 모서리 14 배경 surfaceBrandSubtle, 아이콘 26 foregroundBrandPrimary → 16 → [제목 headline1Bold foregroundNeutralPrimary / 2 / 설명 body2Regular foregroundNeutralSecondary]:
     - `NotificationsNone` "마감·공지 알림" / "마감이 다가오면 미리 알려드려요"
     - `ChatBubbleOutline` "채팅으로 물어보기" / "이번 주 할 일, 제출 확인, 파일 내용까지"
     - `FileDownload` "강의 파일 받기" / "필요한 자료를 폰에 바로 저장해요"
  5. 28 여백 → 개인정보 카드(`Card`):
     - 행: `Lock` 20 foregroundNeutralSecondary + 8 + "개발자는 개인정보를 수집하지 않아요" body2Bold foregroundNeutralPrimary
     - 6 → 본문 caption1Regular foregroundNeutralSecondary: "개발자 서버는 없어요. eTL 토큰은 eTL로, ChatGPT 로그인 정보나 API 키는 OpenAI로 보내 인증해요. 비서에게 보낸 질문과 첨부, 비서가 불러온 강의 정보는 OpenAI로 전송돼요. 캘린더 연동을 켜고 계정 캘린더를 고르면 일정이 그 계정에 동기화될 수 있어요."
     - 10 → `WantedTextButton` "개인정보 처리방침 읽기" (왼쪽 정렬)
  6. 16 → "서울대학교 공식 앱이 아니에요." caption1Regular foregroundNeutralTertiary, 가운데 정렬.
  7. 하단 고정: PrimaryButton "시작하기".
- 상태: 없음.
- 동작: "시작하기" → `onboarding-token`; "개인정보 처리방침 읽기" → `privacy-policy`; 시스템 뒤로 → 앱 종료.
- SwiftUI 메모: 온보딩 전체를 하나의 `OnboardingView`(NavigationStack 쓰지 않음)로 만들고 `@State step`을 `switch`, `.transition(.opacity)` + `withAnimation(.easeOut(duration: 0.22))`. 상단 진행줄은 커스텀 HStack(왼쪽 `chevron.left` 44pt, 가운데 진행바). 스크롤 영역 `ScrollView` + 하단 버튼은 `.safeAreaInset(edge: .bottom)`. 스와이프 뒤로는 막고(원본에 없음) 버튼만. 개인정보 방침은 `.sheet`가 아니라 `fullScreenCover` 또는 push 모양(원본은 새 화면, 뒤로 화살표).

---

## onboarding-token — 2. eTL 토큰 연결

- Android 진입: `$SRC/onboarding/OnboardingScreens.kt:339` (`TokenStep`), 단계 번호 행 `Step` :401, 검증 `$SRC/onboarding/OnboardingViewModel.kt:122`, 형식 검사 `OnboardingLogic.kt:23`, 외부 링크 `OnboardingActivity.kt:47`.
- 목적: eTL(Canvas) 개인 액세스 토큰을 받아 실제 접속(`GET /api/v1/users/self`)으로 확인 후 Keystore(iOS Keychain)에 저장.
- 레이아웃 (A: 저장 전 — `tokenState` ≠ Saved):
  1. 제목 "eTL에 연결할게요"
  2. 부제 "eTL에서 내 토큰을 만들어 붙여넣으면, 앱이 eTL을 읽기 전용으로 조회해요. 제출이나 수정은 하지 않아요."
  3. 안내 카드(`Card`), 행 3개(사이 10). 각 행 = 번호 원 24×24 원형 배경 surfaceBrandPrimary, 숫자 caption1Bold staticWhite → 12 → 글 body2Medium foregroundNeutralPrimary:
     - "1" "아래 버튼으로 eTL 설정 페이지를 열어요"
     - "2" "'새 액세스 토큰'을 만들어요 (용도는 아무거나)"
     - "3" "만들어진 토큰을 복사해서 아래에 붙여넣어요"
  4. 12 → SecondaryButton(ASSISTIVE OUTLINED LARGE, 가로 꽉 참) "eTL 설정 페이지 열기"
  5. 24 → `WantedTextField`:
     - 제목 "eTL 토큰", placeholder "토큰을 붙여넣어 주세요"
     - 입력은 비밀번호 마스킹(●), 키보드 Password
     - 오른쪽 안 버튼 "붙여넣기" (클립보드 첫 항목 trim, 비어 있지 않으면 입력에 넣음)
     - 설명(아래): 기본 "이 폰의 Keystore에 암호화해서 저장해요. 설정에서 언제든 삭제할 수 있어요." / 오류 상태면 오류 문구 + status Negative(빨강)
  6. 하단 고정:
     - PrimaryButton "확인하고 저장" — enabled = 입력이 공백 없는 ASCII(0x21–0x7E) 8자 이상(`isPlausibleEtlToken`) && 확인 중 아님. 확인 중이면 버튼 로딩 스피너.
     - (이미 저장된 토큰이 있을 때만) `WantedTextButton` "기존 토큰 그대로 쓰기" (가로 꽉 참)
- 레이아웃 (B: 저장됨 — `TokenState.Saved`):
  1. 같은 제목·부제
  2. StatusCard(positive): 이름을 받았으면 "{이름}님으로 연결됐어요", 아니면(기존 토큰) "이미 연결돼 있어요". 이름 = users/self `name`(없으면 `short_name`)에서 "(" 앞부분 trim(예: "홍길동(2021-12345)" → "홍길동").
  3. 12 → "토큰은 이 폰에 암호화돼 저장됐어요. 이제 마감, 공지, 파일을 조회할 수 있어요." body2Regular foregroundNeutralSecondary
  4. 하단 고정: PrimaryButton "다음" + `WantedTextButton` "다른 토큰으로 바꾸기"
- 상태: Idle / Checking(버튼 로딩) / Saved(name) / Error(message). 처음 들어올 때 저장된 토큰이 있으면 Saved(null)로 시작.
  - 오류 문구(정확히): 형식 오류 "토큰 모양이 아니에요. 공백 없이 그대로 붙여넣어 주세요" / 401 등 인증 실패 "eTL이 이 토큰을 받아주지 않았어요. 다시 만들어서 붙여넣어 주세요" / 네트워크 "eTL에 연결하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요" / 그 밖 "확인하지 못했어요({예외 클래스 이름})".
  - 저장 성공하면 입력칸 비움.
- 동작:
  - "eTL 설정 페이지 열기" → `ext-browser` (`https://myetl.snu.ac.kr/profile/settings`).
  - "붙여넣기" → 클립보드에서 채움(같은 화면).
  - "확인하고 저장" → 네트워크 확인 → Saved 또는 Error(같은 화면).
  - "기존 토큰 그대로 쓰기" → Saved(null) 상태.
  - "다른 토큰으로 바꾸기" → Idle, 입력 비움.
  - "다음" → `onboarding-chatgpt`. 이 단계는 저장 전에는 다음으로 갈 수 없다(다음 버튼 자체가 없음).
  - "이전"(상단 화살표/뒤로) → `onboarding-welcome`.
- SwiftUI 메모: 링크는 `openURL`(Safari) — `SFSafariViewController`도 가능하지만 eTL 로그인 쿠키를 쓰려면 Safari 앱 쪽이 원본과 같다. 입력칸은 `SecureField` + `.textContentType(.password)` 빼고 `.oneTimeCode`도 쓰지 말 것(자동완성 막기: `.textContentType(nil)`, `.autocorrectionDisabled()`, `.textInputAutocapitalization(.never)`). "붙여넣기"는 `UIPasteboard.general.string`(iOS 16+ 붙여넣기 허용 알림이 뜬다) 또는 `PasteButton`. 저장은 `KeychainSecretStore`.

---

## onboarding-chatgpt — 3. ChatGPT 로그인

- Android 진입: `$SRC/onboarding/OnboardingScreens.kt:422` (`LoginStep`), 이벤트 처리 `OnboardingViewModel.kt:95`, 동작 `OnboardingActivity.kt:57-66`. 단계 진입 시 세션 시작(`LaunchedEffect(step==LOGIN) ensureSession`, :103).
- 목적: AI 비서 인증. 건너뛸 수 있다.
- 공통: 제목 "ChatGPT 계정으로\n로그인해요" (두 줄), 부제 "AI 비서는 내 ChatGPT 계정으로 실행돼요. 별도의 API 요금은 없고, 내 플랜의 사용량 안에서 쓰여요."
- 분기(우선순위 순서대로 첫 번째만):
  1. **로그인 진행 중** (`loginPrompt != null`, 계정 전환 포함)
     - 내용 카드:
       - 코드 방식(`DEVICE_CODE` && code 있음): "아래 코드를 브라우저에 붙여넣어 주세요" body2Regular secondary → 10 → 코드(예 `ABCD-EFGH`) title1Bold primary 가로 꽉 참(왼쪽 정렬) → 10 → "코드는 복사해 두었어요. 하이픈을 포함해 9자예요." caption1Regular secondary → 10 → `WantedChip` "코드 복사"
       - 브라우저 방식: 행[`WantedCircularProgressIndicator` + 12 + "브라우저에서 로그인을 마쳐 주세요" body1Medium primary] → 8 → "승인하면 이 화면이 자동으로 이어져요. 끝나면 알림도 드려요." caption1Regular secondary
     - 하단: SecondaryButton "브라우저 다시 열기" → (브라우저 방식일 때만) TextButton "브라우저가 안 되나요? 코드로 로그인" → TextButton "취소"
  2. **로그인됨** (`account.loggedIn`)
     - StatusCard(positive) "로그인됐어요 · {누구}" — 누구 = ChatGPT면 이메일(없으면 "ChatGPT"), API 키면 "API 키", 그 밖 "계정"
     - 12 → caption1Regular secondary: "다른 계정을 쓰고 싶으면 아래 '다른 계정으로 로그인'을 눌러요. 브라우저에 이미 로그인된 계정이 있으면 그 계정이 먼저 선택될 수 있어요. 원하는 계정이 아니면 로그인 화면에서 계정을 바꿔 주세요."
     - 하단: PrimaryButton "다음" → (세션 READY일 때만) TextButton "다른 계정으로 로그인"
  3. **준비됨, 로그인 안 됨** (READY)
     - 카드: "브라우저로 로그인하면 코드를 입력할 필요 없이 승인만 누르면 돼요." body2Regular secondary
     - 하단: PrimaryButton "ChatGPT로 로그인" → TextButton "나중에 할게요"
  4. **세션 실패** (FAILED)
     - StatusCard(negative) "비서를 시작하지 못했어요" + (detail 있으면 " · {detail}")
     - 하단: PrimaryButton "다시 시도" → TextButton "나중에 할게요"
  5. **준비 중** (그 밖: STOPPED/STARTING)
     - 행: 스피너 + 12 + "비서를 준비하고 있어요…" body1Medium primary
     - 하단: TextButton "나중에 할게요"만
  - 공통 꼬리: `loginMessage` 있으면 12 → body2Regular **foregroundNegativePrimary** (로그인 실패 문구, 기본 "로그인하지 못했어요" 또는 서버 오류).
- 동작:
  - "ChatGPT로 로그인" / "다른 계정으로 로그인" → 브라우저 로그인 시작 → `ext-browser`, 화면은 진행 중 분기로.
  - "브라우저 다시 열기" → 같은 URL로 `ext-browser`.
  - "브라우저가 안 되나요? 코드로 로그인" → 진행 중 로그인 취소 후 코드 방식 시작(코드 자동 복사, 브라우저로 인증 페이지 열림) → `ext-browser`.
  - "코드 복사" → 클립보드 + 토스트 "코드를 복사했어요".
  - "취소" → 진행 취소(분기 2/3로 돌아감).
  - "다시 시도" → 세션 재시작.
  - "다음" / "나중에 할게요" → `onboarding-notify`.
  - "이전" → `onboarding-token`.
  - 로그인 완료(LoginDone success) → 진행 카드 사라지고 로그인됨 분기.
- SwiftUI 메모 (iOS 확정 변경, PORTING.md 38행): Codex 세션·장치 코드 대신 **OpenAI Sign in with ChatGPT(PKCE)** + `ASWebAuthenticationSession`. 그래서 "브라우저에서 로그인을 마쳐 주세요" 진행 카드는 인증 시트가 떠 있는 동안만 의미가 있고, 코드 방식(분기 1의 코드 카드, "코드로 로그인")은 iOS에 대응 흐름이 없다 → API 키 입력을 예비로 둘지 결정 필요(아래 "결정 못 한 것"). "준비 중/세션 실패" 분기는 iOS에서는 거의 즉시 READY. 레이아웃·문구는 그대로 유지. 버튼 배치는 공통 틀의 하단 `safeAreaInset`.

---

## onboarding-notify — 4. 알림·방해 금지·받은 파일 정리

- Android 진입: `$SRC/onboarding/OnboardingScreens.kt:538` (`NotifyStep`), `SwitchRow` :633, 저장 `OnboardingViewModel.kt:164`, 권한·배터리 `OnboardingActivity.kt:35-39, 53-56, 93-113`, 시각 선택 :68-71.
- 목적: 알림 권한, 배터리 예외, 알림 종류·브리핑 시각·방해 금지·받은 파일 자동 정리 기간을 고른다. "다음"을 눌러야 저장된다.
- 레이아웃:
  1. 제목 "알림을 켜 볼까요?" / 부제 "마감과 공지는 AI 없이 앱이 한 시간마다 조용히 확인해서 알려드려요."
  2. 알림 권한:
     - 허용됨: StatusCard(positive) "알림이 허용돼 있어요"
     - 아님: Card[ "알림 권한이 필요해요" body1Bold primary / 4 / "허용하지 않으면 마감·공지 알림이 오지 않아요." body2Regular secondary / 12 / SecondaryButton "알림 허용하기" ]
  3. 12 → 배터리(Android 전용):
     - 예외됨: StatusCard(positive) "배터리 제한 없이 확인할 수 있어요"
     - 아님: Card[ "앱을 닫아도 알림이 오게 하기" body1Bold / 4 / "배터리 최적화가 켜져 있으면 앱을 닫아 둘 때 확인이 늦거나 끊길 수 있어요. 다음 창에서 '허용'을 눌러 주세요." body2Regular secondary / 4 / "삼성 폰은 설정 → 배터리 → 백그라운드 사용 제한에서 '절전 앱'이나 '딥 슬립 앱'에 영민이가 있으면 빼 주세요." caption1Regular tertiary / 12 / SecondaryButton "배터리 제한 풀기" ]
  4. (절전 모드 && 배터리 예외 아님) 8 → "절전 모드에서는 새 과제·공지 알림이 늦을 수 있어요. 이미 받아 둔 마감 알림은 그대로 와요." caption1Regular tertiary
  5. 20 → 스위치 행들(행 전체 눌러도 토글, 위아래 12, [제목 body1Medium primary / 2 / 설명 caption1Regular secondary] + 오른쪽 Montage 스위치):
     - "마감 임박 알림" / "{창 요약} (설정에서 바꿀 수 있어요)" — 기본 창이면 "3일, 24시간, 3시간, 10분 전에 알려줘요 (설정에서 바꿀 수 있어요)". 기본 켜짐.
     - "새 공지 알림" / "수강 중인 강의에 새 공지가 올라오면". 기본 켜짐.
     - "정기 브리핑" / "마감·누락·새 공지를 한 번에 요약해서". 기본 켜짐.
       - 켜져 있으면 8 → FlowRow(가로·세로 8) 칩: "오전 7:00" "오전 8:00"(기본 선택) "오전 9:00" "오후 8:00" + 마지막 칩 "직접 선택"(목록 밖 시각이면 "{오전/오후 h:mm} · 바꾸기", 활성). 시각 형식은 12시간제 "오전 7:00".
     - "방해 금지 시간" / "이 시간에는 마감·공지 알림을 미뤘다가 끝나면 보내줘요(브리핑과 직접 정한 알림은 그대로)". 기본 꺼짐.
       - 켜져 있으면 8 → Row(사이 8) 칩 두 개(둘 다 활성 모양): "오후 11:00 부터", "오전 7:00 까지" (기본 23:00~07:00).
  6. 20 → "받은 강의자료를 폰에 얼마나 둘까요?" body1Bold primary → 4 → "비서가 폰 Download/etl에 받아 둔 eTL 파일을 고른 기간이 지나면 지워요. 비서가 만든 파일(영민이가 만든 파일 폴더)은 지우지 않아요." body2Regular secondary → 10 → 칩 FlowRow(8): "2일"(기본) "7일" "30일" "정리 안 함" (단일 선택) → 6 → "계속 두고 싶은 자료는 파일 카드 옆 공유 아이콘을 눌러 다른 곳에 저장하세요." caption1Regular tertiary
  7. 하단 고정: PrimaryButton "다음" + TextButton "나중에 할게요"
- 상태: 기존 사용자(토큰 저장돼 있음)가 안내를 다시 보면 스위치·시각은 저장된 값으로 시작. 권한/배터리/절전은 화면이 다시 보일 때(onStart)마다 새로 읽는다.
- 동작:
  - "알림 허용하기" → (Android 13+ 권한 없음) 시스템 권한 대화상자 `onboarding-notify-permission`; 거절 후 다시 묻지 못하거나 권한은 있는데 앱 알림이 꺼져 있으면 → `ext-system-settings`(앱 알림 설정).
  - "배터리 제한 풀기" → 시스템 배터리 최적화 예외 창 `ext-system-settings` + 토스트(LONG) 삼성 안내 문구.
  - 브리핑 "직접 선택"/"… · 바꾸기", 방해 금지 "부터"/"까지" 칩 → 시각 선택 `onboarding-notify-time-picker`(시스템 시계 다이얼, 기기 24시간제 설정 따름).
  - "다음" → 설정 저장(알림 종류, 브리핑 시각 1개, 방해 금지, 정리 기간) + 예약 + (마감/공지 켰으면) 즉시 1회 확인 → `onboarding-done`.
  - "나중에 할게요" → 저장 없이 `onboarding-done`.
  - "이전" → `onboarding-chatgpt`.
- SwiftUI 메모: 배터리 카드·절전 문구·삼성 안내는 **iOS에 없음(생략)**. 권한은 `UNUserNotificationCenter.requestAuthorization`; 거절 상태(`.denied`)면 `UIApplication.openNotificationSettingsURLString`으로 `ext-system-settings`. 시각 선택은 `.sheet` + `DatePicker(.hourAndMinute, .wheel)` + presentationDetents([.height(300)]) 또는 칩 위에 `.popover`. 칩 줄바꿈은 커스텀 `FlowLayout`(Layout 프로토콜). 받은 파일 자동 정리는 PORTING.md 40행상 1단계 범위 밖 → 섹션 6을 숨기거나 보류.

### onboarding-notify-permission — 시스템 알림 권한 대화상자 (system)
- Android: `OnboardingActivity.kt:101-109`. iOS: `requestAuthorization` 시스템 알림. 허용/거절 후 `onboarding-notify`로 돌아와 카드가 갱신된다.

### onboarding-notify-time-picker — 시각 선택 (system dialog)
- Android: `android.app.TimePickerDialog` (`OnboardingActivity.kt:68-71`), 초기값 = 현재 값, 0..1439분으로 보정. 확인 → 해당 칩 값 갱신.

---

## onboarding-done — 5. 완료와 추천 질문

- Android 진입: `$SRC/onboarding/OnboardingScreens.kt:659` (`DoneStep`), 추천 질문 `$SRC/onboarding/SuggestionQuestions.kt:38`, 개인화 규칙 `OnboardingLogic.kt:88`, 끝내기 `OnboardingActivity.kt:115`.
- 목적: 안내 완료. 추천 질문을 누르면 그 질문을 채팅 입력창에 채운 채 시작.
- 레이아웃:
  1. 제목 "준비됐어요!" / 부제 "이렇게 물어볼 수 있어요. 눌러서 바로 시작해 보세요."
  2. 추천 질문 카드 6개(사이 10). 각 카드 = 가로 꽉 참, 모서리 16, 배경 surfaceNeutralSecondary, 안쪽 16/16, `ChatBubbleOutline` 20 foregroundBrandPrimary + 12 + 질문 body1Medium primary(여러 줄 가능). **완료 단계는 6개 모두 같은 아이콘**.
     - 기본 문구(과목을 모를 때): "이번 주 마감 과제 알려줘", "새 공지 중 중요한 내용만 정리해줘", "오늘 수업 시간과 강의실 알려줘", "최근 강의자료 핵심 내용 요약해줘", "최근 강의자료 파일 받아줘", "최근 과제가 제대로 제출됐는지 확인해줘"
     - 4·5·6번째는 과목명을 앞에 붙인다: "{과목} 최근 강의자료 핵심 내용 요약해줘", "{과목} 최근 강의자료 파일 받아줘", "{과목} 최근 과제가 제대로 제출됐는지 확인해줘". 먼저 폰 캐시로 바로 채우고, eTL을 조회해 다시 고른다(실패 시 10초·30초·60초 뒤 재시도). 같은 화면 수명 동안 같은 시드 → 회전해도 같은 과목.
  3. 하단 고정: PrimaryButton "비서 시작하기"
- 동작:
  - 질문 카드 → 안내 완료 저장 → `chat` (입력창에 그 질문이 채워짐, **자동 전송 안 함**, 커서 끝).
  - "비서 시작하기" → 완료 저장 → `chat` (빈 입력).
  - "이전" → `onboarding-notify`.
- SwiftUI 메모: 끝내면 루트에서 `OnboardingView`를 `ChatView`로 교체(뒤로 돌아갈 수 없음 = Android `finish()`), prefill은 `ChatViewModel.setInput`. 추천 질문 계산은 `rememberSuggestionQuestions`와 같은 비동기 Task(.task)로.

---

## privacy-policy — 개인정보 처리방침

- Android 진입: `$SRC/chat/PrivacyPolicyActivity.kt:33`. 본문은 빌드 때 `docs/privacy-policy.md`(약 23KB, 제목 "# 영민이 개인정보처리방침", 절 "## 들어가며" ~ "## 11. 방침 변경")를 assets로 복사한 것. 네트워크 없음.
- 레이아웃:
  1. 상단바 높이 56, 좌우 8: `IconButton(ArrowBack, "뒤로")` + "개인정보 처리방침" title2Bold primary.
  2. 스크롤 본문(전체 선택 가능): `MarkdownBody`, body1ReadingRegular primary, 좌우 24 위아래 16. 제목(#)은 굵게·1.1배, 인라인 코드 고정폭 0.92배 + surfaceNeutralTertiary 배경, 코드 블록 0.88배, 링크 foregroundBrandPrimary 밑줄.
- 동작: 뒤로 → 호출한 화면(`onboarding-welcome` 또는 `settings`); 본문 링크(http/https만) → `ext-browser`, 열 앱 없으면 토스트 "링크를 열 앱이 없어요".
- SwiftUI 메모: 번들 리소스 `privacy-policy.md`를 ChatKit의 마크다운 파서(MarkdownLite 이식)로 `AttributedString` 변환 → `Text(...)`.`textSelection(.enabled)`. 온보딩에서는 `fullScreenCover` + 커스텀 상단바(원본 모양), 설정에서는 NavigationStack push.

---

## chat — 채팅 화면

- Android 진입: `$SRC/chat/ChatActivity.kt:46` (onCreate :87), 화면 `$SRC/chat/ChatScreen.kt:120` (`ChatRoot`), :136 (`ChatScreen`), 부품 `$SRC/chat/ChatComponents.kt`, 첨부 `$SRC/chat/AttachmentViews.kt`.
- 목적: AI 비서와 대화. 앱의 홈.
- 전체 구조(위→아래, 배경 backgroundNeutralPrimary, edge-to-edge + 시스템바·키보드 패딩; 서랍이 이 전체를 덮는다):
  1. **상단바** (`ChatTopBar`, ChatComponents.kt:96): 높이 56, 시작 4 / 끝 8.
     - `IconButton(Menu)` 48 — 접근성 "대화 목록" → 서랍 열기
     - "영민이" title2Bold primary (남은 폭 차지, 왼쪽 정렬)
     - `IconButton(AddComment)` "새 대화"
     - 테마 토글 `IconButton`: 지금 다크면 `LightMode` "라이트 모드로 전환", 라이트면 `DarkMode` "다크 모드로 전환" (누르면 앱 테마를 반대로 고정 저장)
     - `IconButton(Settings)` "설정"
     - 아이콘 틴트 모두 foregroundNeutralPrimary, 기본 24.
  2. **칩 줄** (ChatScreen.kt:183): Row 좌우 16 위아래 4, 사이 8.
     - 모델 칩(`ModelChip`, :134): 알약(모서리 999), 배경 surfaceNeutralSecondary, 안쪽 시작12/끝8/위아래7. `Tune` 16 secondary → 6 → "AI 모델 · {선택}" caption1Medium primary 한 줄 → `KeyboardArrowDown` 16 secondary. {선택} = "자동" / "자동 · 높음" / "{모델 표시 이름}" / "{모델} · {강도}". 강도 라벨: none "끔(가장 빠름)", minimal "최소", low "낮음", medium "보통", high "높음", xhigh "매우 높음". 접근성 클릭 라벨 "AI 모델 고르기".
     - 공지 칩(등록 안 한 공지가 있을 때만): `WantedChip` "등록할 공지 {N}개" (비활성 모양), 접근성 "등록 안 한 공지 {N}개 보기".
  3. **상태 배너**(있을 때만, 하나만; 아래 `chat-banner-*` 절): 좌우 16 위아래 6.
  4. **메시지 영역**(남은 높이 전부):
     - 메시지 없음 → 빈 화면(아래 4-A).
     - 있음 → `LazyColumn(reverseLayout = true)` 맨 아래가 최신. 안쪽 좌우 16 위아래 8, 항목 사이 8. 말풍선 최대 폭 = 영역 폭 × 0.86.
     - 맨 아래를 보고 있으면 스트리밍 중에도 아래에 붙어 있고, 위로 올려 보는 중이면 위치 유지. 새 메시지 추가·대화 전환·화면 복귀 때 맨 아래로 이동(애니메이션 없음).
  5. **활동 줄**(`ActivityLine`, 답변 중일 때만): 좌우 24 위아래 4, caption1Medium secondary, 투명도 1.0↔0.35 700ms 선형 반복(깜빡임). 문구: "생각하는 중…", 도구별 "다운로드하는 중…"(etl_download), "파일 읽는 중…"(etl_read_file/etl_render_pages), "조회하는 중…"(etl_digest/courses/assignment/search_files/verify_submission/get), 그 밖 "처리하는 중…". 본문이 스트리밍되는 동안은 숨김.
  6. **보낼 첨부 줄**(`PendingAttachments`, AttachmentViews.kt:351; 첨부가 있거나 가져오는 중일 때만): 가로 스크롤, 좌우 16 위아래 4, 사이 8.
     - 사진: 64×64 모서리 12 배경 surfaceNeutralSecondary, 썸네일 crop(없으면 `Image` 아이콘 tertiary). 누르면 열기.
     - 파일: 모서리 12 배경 surfaceNeutralSecondary, 안쪽 시작10/끝28/위아래10, 최대 폭 200, 종류 아이콘 20 brand + 8 + 파일명 caption1Medium primary 한 줄 말줄임.
     - 각 항목 오른쪽 위(3 안쪽) 빼기 버튼: 20×20 원 surfaceNeutralInverse, `Close` 14 foregroundNeutralInverse, 접근성 "{이름} 빼기".
     - 가져오는 중이면 끝에 "가져오는 중…" caption1Regular secondary.
  7. **입력 바**(`InputBar`, ChatComponents.kt:390): 바깥 좌우 12 위아래 8, 안쪽 모서리 26 알약 모양 배경 surfaceNeutralSecondary, 최소 높이 52, 세로 정렬 아래(여러 줄이면 버튼은 바닥에 붙음).
     - 왼쪽 첨부 버튼(시작 6, 아래 6): 40×40 `Add`, 틴트 primary(비활성 foregroundDisablePrimary), 접근성 "첨부". 보낼 첨부+가져오는 중 ≥ 5개면 비활성. 누르면 `chat-attach-menu`.
     - 가운데 입력: body1Regular primary, 커서 foregroundBrandPrimary, 최대 5줄 후 내부 스크롤, 문장 첫 글자 대문자, 엔터 = 줄바꿈. 안쪽 시작4/끝8/위아래14. placeholder "메시지를 입력하세요" foregroundNeutralQuaternary.
     - 오른쪽 보내기 버튼(사방 6): 40×40 원.
       - SEND: 배경 surfaceBrandPrimary, `Send` 20 흰색, "보내기"
       - STOP(답변 중): 배경 surfaceNeutralInverse, `Stop` 20 foregroundNeutralInverse, "답변 중단"
       - DISABLED(글 비었고 첨부 없음, 또는 첨부 가져오는 중): 배경 surfaceDisablePrimary, `Send` foregroundDisablePrimary, 눌리지 않음
- 4-A. **빈 화면**(`EmptyState`, ChatComponents.kt:188): 세로 스크롤, 좌우 24 위아래 16, 가운데 정렬(세로 가운데).
  - 원 72×72 배경 surfaceBrandSubtle, `ChatBubbleOutline` 34 foregroundBrandPrimary
  - 20 → "무엇을 도와드릴까요?" title1Bold primary 가운데
  - 8 → "마감, 공지, 제출 확인, 강의 파일까지 물어보세요." body1ReadingRegular secondary 가운데
  - 28 → 추천 카드 6개(사이 10): 모서리 16, 배경 surfaceNeutralSecondary, 안쪽 16, 아이콘 20 brand + 12 + 질문 body1Medium primary(왼쪽 정렬). 아이콘 순서: `Event`, `Campaign`, `Event`, `ChatBubbleOutline`, `AttachFile`, `TaskAlt`. 질문 문구는 `onboarding-done`과 같은 계산(같은 6개, 과목명 개인화).
  - 카드 누르면 **바로 전송**(입력창은 건드리지 않음).
- 4-B. **메시지 행**(`MessageRow`, ChatComponents.kt:263) — 역할별:
  - **USER 말풍선**: 오른쪽 정렬, 최대 폭 0.86, 배경 #005EEB(두 테마 같음), 글 body1ReadingRegular staticWhite, 안쪽 14/10, 모서리 위왼20·위오른20·아래오른6(꼬리)·아래왼20. 길게 눌러 부분 선택(선택 띠 #181B35, 손잡이 foregroundNeutralPrimary). 접근성 길게 누르기 "전체 복사".
    - 첨부가 있으면: 오른쪽 정렬 Column(사이 6) = [첨부 묶음(오른쪽 정렬)] → [글이 있으면 말풍선].
  - **ASSISTANT 말풍선**: 왼쪽 정렬, 최대 폭 0.86, 배경 surfaceNeutralSecondary, 마크다운 본문 body1ReadingRegular primary, 안쪽 14/10, 모서리 위왼20·위오른20·아래오른20·아래왼6(꼬리). 빈 글이면 "…". 마크다운: **굵게**, 제목 굵게 1.1배, `코드` 고정폭 0.92배 + surfaceNeutralTertiary 배경, 코드 블록 0.88배, 링크 foregroundBrandPrimary 밑줄(http/https만), LaTeX 수식은 기기에서 그림으로(인라인 1.12배, 블록 1.18배 + 위아래 4dp; 폭이 넘치면 최대 0.78배까지 줄이고 남는 부분은 수식 위에서 가로로 밀어 봄). 문장·수식을 가로질러 부분 선택 가능.
    - 말풍선 아래 복사 버튼(스트리밍이 끝났고 글이 있을 때): `IconButton`(48 터치) 안 `ContentCopy` 18 foregroundNeutralTertiary, 접근성 "답변 전체 복사". 누르면 클립보드 + 토스트 "복사했어요".
  - **NOTICE 줄**(안내·오류): 가운데 정렬, caption1Regular foregroundNeutralTertiary, 좌우 24 위아래 4, 가로 꽉 참. 오류도 이 줄로 나온다(별도 빨간 오류 말풍선 없음). 나오는 문구 예: "보내지 못했어요: {오류}", "답변을 만들지 못했어요", "선택한 모델 설정 때문일 수 있어요. 설정에서 '자동'으로 되돌려 보세요.", "답변을 멈췄어요", "이전 대화의 맥락은 이어지지 않아요. 여기서부터 새로 이어 가요.", "지원하지 않는 요청이라 거절했어요: {method}", 서버 오류 메시지, 도구가 보낸 안내.
  - **FILES 묶음**(비서가 받은 파일·쪽 이미지; 왼쪽 정렬, `AttachmentGroup`, AttachmentViews.kt:95):
    - 보이는 첨부와 "참고 자료"(referenceOnly 또는 pagePreview)를 나눈다(사용자 쪽은 나누지 않음).
    - 이미지(쪽 미리보기 아님): FlowRow(사이 6, 사용자면 오른쪽 정렬), 1장이면 200×200, 여러 장이면 120×120, 모서리 14, 배경 surfaceNeutralSecondary, crop. 썸네일 없으면 `Image`(파일 있음) 또는 `BrokenImage`(없음) tertiary. 탭 = 열기, 길게 = 공유.
    - PDF 쪽 미리보기: "비서가 본 쪽 {N}장" caption1Regular tertiary → 가로 스크롤 Row(사이 6) 썸네일 폭 110, 높이는 쪽 실제 비율(로딩 전 110×155), 모서리 14, `Fit`.
    - 파일 카드(`FileCard`, :260): 최대 폭 0.86, Row(사이 4) = [카드] + [공유 버튼].
      - 카드: 모서리 16, 배경 surfaceNeutralSecondary, 안쪽 12/10, 탭 = 열기(파일이 있을 때만).
        - 행: 아이콘 타일 40×40 모서리 10 배경 surfaceBrandSubtle, 종류 아이콘 22 brand (PDF `PictureAsPdf`, 이미지 `Image`, 슬라이드 `Slideshow`, 표 `TableChart`, 문서 `Description`, 한글 `Article`, 압축 `FolderZip`, 코드 `Code`, 기타 `InsertDriveFile`) → 12 → [파일명 body2Medium primary 최대 2줄 말줄임 / 상세 caption1Regular secondary 1줄]
        - 상세 = " · "로 이음: 확장자 대문자(5자 이하, 아니면 "파일") · 크기("1.2MB"/"340KB"/"12B") · 상태: 자동 정리됨 "자동 정리된 파일이에요" / 없어짐 "폰에서 지워진 파일이에요"(폰 경로가 있던 파일) 또는 "정리된 파일이에요" / 있음+폰 사본 "내 폰 Download/etl" 또는 비서가 만든 파일 "내 폰 Download/etl/영민이가 만든 파일".
        - 파일이 없어졌고 폰 경로가 있던 파일이면 6 → 작은 버튼: `Refresh` 16 brand + 4 + "다시 받기"(비서가 만든 파일이면 "다시 만들기") caption1Bold brand, 모서리 10, 안쪽 8/6. 누르면 입력창에 `"{이름}" 파일 다시 받아줘` / `"{이름}" 다시 만들어줘`를 채움(전송 안 함).
      - 공유 버튼(파일이 있을 때만): `IconButton` `Share` 22 foregroundBrandPrimary "공유".
    - "참고 자료" 접기 줄(참고 자료가 있을 때): 모서리 8, 안쪽 4/8, `ExpandMore`(펼치면 `ExpandLess`) 18 tertiary + 4 + "참고 자료 {N}개" caption1Regular tertiary. 기본 접힘, 누르면 아래에 같은 방식으로 펼침. 접근성 "참고 자료 펼치기/접기", 상태 "접힘/펼쳐짐".
  - 참고: `AttachmentActions.onSaveCopy`("드라이브에 저장"/"다른 곳에 저장")는 ChatActivity에서 연결돼 있지만 **FileCard에 버튼이 그려지지 않는다**(현재 원본에서 보이지 않는 기능).
- 자동으로 뜨는 창(`ChatRoot`, ChatScreen.kt:120-131; 한 번 열 때 하나만, 우선순위 업데이트 > 정리 > 공지 시트): `chat-update-prompt`, `chat-cleanup-prompt`, `chat-pending-notices`. 그 밖에 화면이 보이는 동안 `chat-write-approval`. 첫 실행(Android 13+, 한 번만)에 알림 권한 시스템 창 `chat-notify-permission`.
- 상태: 빈 대화 / 메시지 있음 / 답변 중(보내기 → 중단 버튼, 활동 줄) / 첨부 가져오는 중 / 배너 상태들 / 모델 목록 로딩.
- 동작(→ 대상):
  - 메뉴 아이콘, 왼쪽 가장자리에서 끌기 → `chat-drawer`.
  - 새 대화 아이콘 → 새 빈 대화(같은 `chat`). 답변 중이면 토스트 "답변이 끝난 뒤에 새 대화를 시작할 수 있어요".
  - 테마 토글 → 라이트/다크 전환(같은 화면).
  - 설정 아이콘 → `settings`.
  - 모델 칩 → `chat-model-picker`.
  - "등록할 공지 N개" 칩 → `chat-pending-notices`.
  - 배너 → 배너별(아래).
  - 추천 카드 → 바로 전송(같은 화면).
  - 입력 + 보내기 → 전송(문제 있으면 이유 토스트, 입력·첨부 유지). 답변 중 중단 버튼 → 답변 중단(NOTICE "답변을 멈췄어요").
  - "+" → `chat-attach-menu`.
  - 사진 썸네일 탭 → Android는 외부 뷰어 `ext-file-viewer`(iOS는 `chat-image-preview`); 사진 길게 → `ext-share-sheet`.
  - 파일 카드 탭 → `ext-file-viewer`(열 앱 없으면 토스트 "이 파일을 열 수 있는 앱이 없어요. 길게 눌러 다른 앱으로 보낼 수 있어요", 파일이 없으면 "파일이 정리되어 더는 없어요", 보안 오류 "파일을 열 수 없어요").
  - 공유 아이콘 → `ext-share-sheet`(공유할 앱 없으면 "공유할 수 있는 앱이 없어요").
  - 답변 속 링크 → eTL 주소면 `ext-etl`(LearningX 앱/웹), 그 밖 `ext-browser`. 열 앱 없으면 "링크를 열 수 있는 앱이 없어요".
  - 복사 아이콘 → 클립보드 + "복사했어요".
  - 로그인 완료 이벤트 → 토스트 "로그인했어요".
- 첨부 관련 토스트: "한 번에 5개까지 첨부할 수 있어요", "카메라 앱을 찾지 못했어요", "파일을 고를 수 있는 앱이 없어요", "50MB보다 큰 파일은 첨부할 수 없어요", "파일을 열 수 없어요", "파일을 가져오지 못했어요", "첨부하지 못했어요".
- SwiftUI 메모:
  - 루트는 `ZStack { chatContent; drawerOverlay }` — **iPhone에서는 `NavigationSplitView`/사이드바 대신 커스텀 슬라이드 서랍**(아래 `chat-drawer`).
  - 상단바는 `NavigationStack`의 툴바 대신 커스텀 HStack(높이 56pt, 제목 왼쪽 정렬 큰 글씨 — 원본 모양). 다른 화면(settings 등)은 이 `NavigationStack` 위로 push.
  - 메시지 목록: `ScrollView` + `LazyVStack` + `.defaultScrollAnchor(.bottom)`(iOS 17) 또는 `ScrollViewReader.scrollTo(lastId, anchor: .bottom)`; reverseLayout 동작(맨 아래면 따라 내려감) 재현.
  - 사용자 말풍선은 `UnevenRoundedRectangle(topLeading:20, bottomLeading:20, bottomTrailing:6, topTrailing:20)`, 비서는 bottomLeading 6. 최대 폭은 `GeometryReader` 폭×0.86 또는 `containerRelativeFrame`.
  - 부분 선택: SwiftUI `Text.textSelection(.enabled)`는 전체 선택만 됨 → 원본처럼 부분 선택하려면 `UITextView`(편집 불가, 선택 가능) `UIViewRepresentable`. 수식은 `NSTextAttachment` 이미지.
  - 입력 바: `TextField(axis: .vertical).lineLimit(1...5)`, 배경 `RoundedRectangle(26)`. 키보드는 `.safeAreaInset(edge: .bottom)`에 입력 바를 두면 자동으로 따라 올라감.
  - 활동 줄 깜빡임: `.opacity` + `.animation(.linear(duration: 0.7).repeatForever(autoreverses: true))`.
  - 테마 토글은 `AppAppearanceStore.shared.mode = isDark ? .light : .dark` → 루트 `.preferredColorScheme`.

### chat-banner-* — 상태 배너 (banner, chat 안)
- Android: 그리기 `$SRC/chat/ChatComponents.kt:155` (`StatusBanner`), 문구·우선순위 `$SRC/chat/ChatLogic.kt:47-71`, 동작 `ChatActivity.kt:114-125, 492-556`.
- 공통 모양: 가로 꽉 참(바깥 좌우 16 위아래 6), 모서리 16, 배경 surfaceCautionaryPrimary, 안쪽 16/14. `Info` 20 foregroundCautionaryPrimary → 10 → 문구 body2Medium foregroundNeutralPrimary(여러 줄) → 끝: 닫기 가능하면 `IconButton` 32×32 안 `Close` 18 secondary("나중에"), 아니고 누를 수 있으면 `KeyboardArrowRight` 20 secondary, 아니면 없음. 누를 수 있으면 전체가 버튼.
- 우선순위(하나만 표시): 토큰 없음 → 세션 실패 → 준비 중 → 로그인 안 됨 → 알림 문제.

| id | 조건 | 문구(정확히) | 끝 | 누르면 |
|---|---|---|---|---|
| chat-banner-token | eTL 토큰 없음 | "eTL 토큰이 필요해요. 눌러서 설정으로 이동" | › | `settings` |
| chat-banner-failed | 세션 FAILED | "비서를 시작하지 못했어요: {detail}\n눌러서 다시 시도" | › | 세션 재시작(`chat`) |
| chat-banner-preparing | READY 아님 | "비서를 준비하는 중이에요…" | 없음 | 누를 수 없음 |
| chat-banner-login | 로그인 안 됨 | "로그인이 필요해요. 눌러서 설정으로 이동" | › | `settings` |
| chat-banner-notify-off | 켠 알림(마감/공지/브리핑) 중 권한·앱 알림·채널이 꺼짐 | "알림이 꺼져 있어서 마감·공지 알림이 오지 않아요. 눌러서 켜기" | × | 권한을 물을 수 있으면 `chat-notify-permission`, 아니면 `ext-system-settings`(채널 하나만 꺼졌으면 그 채널 설정) |
| chat-banner-battery | 알림은 되지만 배터리 최적화 대상 | "배터리 최적화 때문에 앱을 닫아 두면 알림이 늦거나 안 올 수 있어요. 눌러서 해제" | × | `ext-system-settings`(배터리 예외 요청) + 토스트(LONG) 삼성 안내 |
| chat-banner-power-save | 배터리 최적화 대상 + 절전 모드 | "절전 모드에서는 새 과제·공지 알림이 늦을 수 있어요(받아 둔 마감 알림은 와요). 눌러서 배터리 제한 풀기" | × | 위와 같음 |

- × "나중에" → 알림 문제 배너 3종을 7일 숨김(조건이 남아 있으면 7일 뒤 다시 표시). 화면이 다시 보일 때(onResume)마다 다시 판정.
- SwiftUI 메모: battery/power-save 배너는 **iOS에 없음**. notify-off는 `UNUserNotificationCenter.notificationSettings().authorizationStatus` 기준; 누르면 `.notDetermined`면 권한 요청, 그 밖 `openNotificationSettingsURLString`. "준비 중/실패"는 iOS에서 Responses API 호출 실패 상태로 대체될 수 있음. token·login 배너는 그대로.

### chat-attach-menu — 첨부 고르기 메뉴 (dropdown)
- Android: `$SRC/chat/ChatComponents.kt:435-463`, 처리 `ChatActivity.kt:321-366`.
- 모양: "+" 버튼에 붙은 Material3 `DropdownMenu`, 배경 backgroundNeutralPrimary(모서리 4, 그림자). 항목(앞 아이콘 secondary, 글 body1Regular primary):
  - `PhotoLibrary` "사진" → `chat-photo-picker` (시스템 사진 선택기, 이미지만, 최대 남은 개수(총 5))
  - `PhotoCamera` "사진 찍기" → `chat-camera` (시스템 카메라)
  - `AttachFile` "파일" → `ext-document-picker` (모든 형식, 여러 개)
- 고른 것은 작업 폴더로 복사돼 보낼 첨부 줄에 붙는다(자동 전송 안 함). 50MB 초과 거절.
- SwiftUI 메모: `Menu { Button(…, systemImage:) }` 로 "+" 버튼을 감싸면 같은 모양(iOS 팝업 메뉴). 사진 = `PhotosPicker(maxSelectionCount: 남은 수, matching: .images)`, 카메라 = `UIImagePickerController(sourceType: .camera)` 래핑 `fullScreenCover`, 파일 = `.fileImporter(allowedContentTypes: [.item], allowsMultipleSelection: true)`.

### chat-photo-picker / chat-camera (system)
- Android: `PickMultipleVisualMedia(5)` / `TakePicture` (ChatActivity.kt:56, 64). 결과 → `chat`.

### chat-notify-permission (system)
- Android: ChatActivity.kt:160-166 — Android 13+에서 권한이 없고 한 번도 묻지 않았으면 채팅을 처음 열 때 자동으로 시스템 권한 창. 알림 배너에서도 다시 묻는다(:535-543). 결과 후 배너 다시 판정.

---

## chat-drawer — 대화 목록 서랍

- Android 진입: `$SRC/chat/ChatScreen.kt:152-174` (`ModalNavigationDrawer` + `ModalDrawerSheet`), 내용 `$SRC/chat/ChatDrawer.kt:55`. 묶음 라벨 `$SRC/chat/ChatStore.kt:70`.
- 모양: Material3 모달 서랍. 왼쪽에서 밀려 들어오는 시트(폭 기본 360dp 상한, 폰에서는 화면 폭의 약 85%), 오른쪽 모서리 둥금(M3 기본 16), 배경 backgroundNeutralPrimary, 뒤 화면은 검은 반투명 막(scrim, M3 기본 약 32%). 시스템바 패딩 적용. 화면 왼쪽 가장자리에서 끌어 열 수 있고 막/뒤로/바깥 탭으로 닫힘. 열릴 때마다 목록을 새로 읽음.
- 레이아웃(위→아래, 위아래 12 패딩):
  1. "대화" title2Bold primary, 안쪽 좌우 20 위아래 8.
  2. 진입 행들(각: 바깥 좌우 12, 모서리 12 눌림 효과, 안쪽 12/12, 아이콘 20 + 12 + 글 body1Medium):
     - `AddComment` brand + "새 대화" **foregroundBrandPrimary**
     - `EventNote` + "내 일정" primary
     - `CalendarViewWeek` + "시간표" primary
     - `NotificationsNone` + "알림 기록" primary
  3. 8 여백. 대화가 하나도 없으면 "아직 대화가 없어요" body2Regular tertiary(좌우 24 위아래 12).
  4. 대화 목록(LazyColumn, 남은 높이): 묶음별
     - 묶음 머리: "오늘" / "어제" / "지난 7일" / "이전" — caption1Bold secondary, 안쪽 시작24 끝24 위16 아래6.
     - 대화 행: 바깥 좌우 12, 모서리 12, 지금 대화면 배경 surfaceBrandSubtle, 안쪽 시작12 끝2.
       - 제목 body2Medium(지금 대화면 brand, 아니면 primary) 한 줄 말줄임, 위아래 12.
       - `IconButton` 40×40 `Edit` 20 — 접근성 "'{제목}' 이름 바꾸기"
       - `IconButton` 40×40 `DeleteOutline` 20 — 접근성 "'{제목}' 지우기"
       - 두 아이콘 틴트: 평소 secondary, 답변 중이면 foregroundDisablePrimary(눌러도 막고 토스트).
       - 탭 = 열기, 길게 = `chat-drawer-menu`.
- 동작:
  - "새 대화" → 새 대화 + 서랍 닫힘 → `chat` (답변 중이면 토스트 "답변이 끝난 뒤에 새 대화를 시작할 수 있어요").
  - 대화 행 → 그 대화 열기 + 닫힘 + 맨 아래로 → `chat` (답변 중이면 "답변이 끝난 뒤에 다른 대화로 옮길 수 있어요").
  - "내 일정" → `my-schedule`; "시간표" → `timetable`; "알림 기록" → `notify-history` (모두 서랍 닫고 새 화면).
  - 연필 → `chat-drawer-rename`; 휴지통 → `chat-drawer-delete`; 길게 → `chat-drawer-menu`. 답변 중이면 토스트 "답변이 끝난 뒤에 바꾸거나 지울 수 있어요".
  - 막/뒤로 → `chat`.
- SwiftUI 메모: **커스텀 슬라이드 서랍**: `ZStack(alignment: .leading)` 안에 `Color.black.opacity(0.32 * progress)` 막 + 폭 `min(360, w*0.85)` 패널 `.offset(x: (progress-1)*panelWidth)`, `UnevenRoundedRectangle(topTrailing:16, bottomTrailing:16)`. 왼쪽 가장자리 20pt `DragGesture`로 열기, 패널 위 왼쪽 드래그로 닫기, `.spring(response: 0.3)`. 내비게이션 대상은 서랍을 닫은 뒤 `NavigationStack` path에 push. 목록은 `ScrollView+LazyVStack`(List의 inset 스타일 쓰지 말 것 — 원본 행 모양 유지). 길게 누르기는 `.contextMenu`가 아니라 원본처럼 대화상자로(또는 `.contextMenu`도 허용 — 원본 의미 같음).

### chat-drawer-menu — 대화 길게 누르기 메뉴 (dialog)
- Android: `$SRC/chat/ChatDrawer.kt:180-193`. M3 `AlertDialog`, 배경 backgroundNeutralPrimary(모서리 28).
- 제목: 대화 제목 headline2Bold primary 최대 2줄 말줄임. 내용: 세로로 TextButton "이름 바꾸기", "삭제". 확인 버튼 자리: TextButton "닫기".
- 동작: "이름 바꾸기" → `chat-drawer-rename`; "삭제" → `chat-drawer-delete`(답변 중이면 토스트); "닫기"/바깥 → `chat-drawer`.
- SwiftUI 메모: `.confirmationDialog(title, titleVisibility: .visible)` 또는 `.alert` 버튼 3개.

### chat-drawer-rename — 이름 바꾸기 (dialog)
- Android: `$SRC/chat/ChatDrawer.kt:195-218`.
- 제목 "이름 바꾸기" headline2Bold; 내용 `WantedTextField`(placeholder "대화 이름", 지금 제목으로 채움, 최대 40자에서 잘림); 확인 TextButton "저장"(비어 있지 않을 때만 반영), 취소 TextButton "취소".
- 동작: 저장/취소/바깥 → `chat-drawer`.
- SwiftUI 메모: `.alert("이름 바꾸기", isPresented:) { TextField("대화 이름", text:); Button("취소", role:.cancel); Button("저장") }` + `onChange`로 40자 자르기.

### chat-drawer-delete — 대화 지우기 확인 (dialog)
- Android: `$SRC/chat/ChatDrawer.kt:220-229`.
- 제목 "이 대화를 지울까요?" headline2Bold; 본문 "\"{제목}\" 대화가 목록에서 사라지고 되돌릴 수 없어요." body2Regular secondary; 확인 TextButton "삭제", 취소 TextButton "취소". (원본은 삭제 글씨도 파란 TextButton, 빨강 아님.)
- 동작: "삭제" → 삭제(지금 대화였으면 새 빈 대화) → `chat-drawer`; "취소" → `chat-drawer`. 답변 중이면 토스트 "답변이 끝난 뒤에 지울 수 있어요".
- SwiftUI 메모: `.alert` + `Button("삭제", role: .destructive)`(iOS 관례상 빨강 허용 여부 결정).

---

## chat-model-picker — AI 모델·생각의 깊이 시트

- Android 진입: 시트 `$SRC/chat/ChatScreen.kt:251-271` (`ModalBottomSheet(skipPartiallyExpanded = true)`, 배경 backgroundNeutralPrimary, M3 기본 드래그 손잡이·위 모서리 28), 머리 `$SRC/chat/ChatComponents.kt:494`, 내용 `$SRC/chat/ModelPicker.kt:46`.
- 목적: 다음 질문부터 쓸 모델과 추론 강도 선택. 고르는 즉시 저장.
- 레이아웃:
  1. 머리 행(시작 24, 끝 12): "AI 모델" title2Bold primary + 오른쪽 TextButton "완료".
  2. 스크롤 영역(좌우 24, 위 4 아래 24):
     - "다음 질문부터 적용돼요. 잘 모르겠으면 '자동'을 쓰세요." body2Regular secondary → 16
     - 옵션 카드 목록(사이 10). 카드 = 가로 꽉 참, 모서리 16, 배경 surfaceNeutralSecondary, 선택이면 2dp lineBrandPrimary 테두리, 안쪽 16, 위 정렬:
       - [제목 body1Bold primary (+ 태그 "기본": caption1Bold brand, 배경 surfaceBrandSubtle, 모서리 6, 안쪽 6/2)] / 4 / 설명 caption1Regular secondary / (선택이면 강도 선택기) — 오른쪽: 선택이면 12 + `CheckCircle` 24 brand "선택됨".
       - 첫 카드: "자동 (권장)" / "Codex가 정한 기본 모델을 써요" (모델 null일 때 선택)
       - 목록 비었고 불러오는 중: 행[스피너 + 12 + "모델 목록을 불러오는 중이에요…" body1Medium] 위아래 12
       - 목록 비었고 실패: 회색 상자(모서리 16, 안쪽 16) "아직 모델 목록을 불러오지 못했어요. 비서가 준비되고 로그인되면 보여요." body2Regular secondary
       - 모델마다 카드: 표시 이름 / 설명 / 기본 모델이면 태그 "기본"
       - 저장된 모델이 목록에 없으면 마지막에 흐린(투명도 0.55) 선택 카드: "{모델 id}" / "(지금은 쓸 수 없는 모델)"
     - 강도 선택기(선택 카드 안, 지원 강도가 있을 때): 14 → "생각의 깊이" body2Medium primary → 8 → FlowRow(8) 칩: "기본"(강도 null) + 모델이 지원하는 강도 라벨들("끔(가장 빠름)", "최소", "낮음", "보통", "높음", "매우 높음") → 8 → 설명 caption1Regular secondary: (자동 카드면 "기본 모델({이름}) 기준이에요. ") + "높을수록 오래 생각해서 어려운 문제에 좋고, 낮을수록 빨라요." + (강도 설명 있으면 " " + 강도 null일 때 "기본값은 {강도}이에요. {설명}", 아니면 "{설명}").
- 상태: 목록 로딩 / 실패 / 목록 / 알 수 없는 모델 선택. 시트를 열 때 목록이 비어 있고 로딩 중이 아니면 새로 불러옴.
- 동작: 카드 → 모델 선택(지원 안 하는 강도는 기본으로); 칩 → 강도 선택; "완료"/아래로 끌기/바깥 → `chat`.
- SwiftUI 메모: `.sheet` + `.presentationDetents([.large])`(부분 펼침 없음 = skipPartiallyExpanded) + `.presentationDragIndicator(.visible)` + `.presentationCornerRadius(28)` + `.presentationBackground(background)`. iOS에서는 모델 목록이 Responses API용 고정/조회 목록으로 바뀌므로 "Codex가 정한 기본 모델을 써요" 문구는 원문 유지 여부 결정 필요(1:1 규칙상 유지).

---

## chat-image-preview — 사진·쪽 미리보기 열기

- Android: 별도 화면 없음. 사진 썸네일·보낼 첨부·파일 카드를 누르면 `ChatActivity.openAttachment`(`$SRC/chat/ChatActivity.kt:385`)가 `ACTION_VIEW`로 **폰의 기본 앱(갤러리, PDF 뷰어 등)**을 연다 = `ext-file-viewer`. 폰 Download/etl 사본이 있으면 그것을, 없으면 앱 작업 폴더 파일을 넘김.
- 목적(iOS): 같은 경험을 앱 안에서. 
- SwiftUI 메모: `QLPreviewController`(`.quickLookPreview($url)` 수정자)로 전체 화면 미리보기 — 이미지·PDF·오피스 문서 모두 처리, 위 오른쪽 공유 버튼 → `ext-share-sheet`, 완료 → `chat`. 파일 카드 탭도 같은 QuickLook을 쓰면 `ext-file-viewer`와 같은 역할.

---

## chat-update-prompt — 새 버전 팝업 (dialog)

- Android 진입: `$SRC/update/UpdatePrompt.kt:34`, 표시 조건 :25, 호출 `ChatScreen.kt:124`. 문구 `$SRC/update/UpdateLogic.kt:153, 178, 211`.
- 모양: M3 `AlertDialog`, 배경 backgroundNeutralPrimary.
  - 제목 "새 버전 {버전}{이/가} 나왔어요" headline2Bold primary (조사는 버전 끝 글자에 따라).
  - 본문(있으면) body2Regular secondary: 릴리스 노트 "바뀐 점" 절의 글머리 최대 4줄을 "· {요약}"로, 마지막 줄 "크기 {nMB}".
  - 버튼: 확인 TextButton "받기", 취소 TextButton "나중에".
- 조건: 업데이트 알림 켜짐 && 최신 버전 > 현재 && 그 버전을 24시간 안에 미루지 않음. 뒤에서 확인이 끝나면 다시 판정.
- 동작: "받기" → 24시간 미룸 + APK 링크(허용 주소만)를 `ext-browser`로 (실패 시 토스트 "링크를 열 앱이 없어요: {url}"); "나중에"/바깥 → 24시간 미룸 → `chat`.
- SwiftUI 메모: **iOS에 없음**(PORTING.md 39행: App Store/TestFlight가 업데이트). 그래프에는 Android 참고용으로만 남긴다.

---

## chat-cleanup-prompt — 받은 파일 정리 기간 묻기 (dialog)

- Android 진입: `$SRC/files/CleanupUi.kt:76` (`CleanupPrompt`), 조건 :72 (`shouldAsk` = 기간 미선택 && 닫은 횟수 < 2), 호출 `ChatScreen.kt:129`(업데이트 팝업이 뜰 차례면 안 띄움).
- 대상: 안내를 이미 마친 기존 사용자(업데이트 후 처음).
- 모양: M3 `AlertDialog`, 배경 backgroundNeutralPrimary.
  - 제목 "받은 강의자료를 폰에 얼마나 둘까요?" headline2Bold primary
  - 본문 Column: "비서가 폰 Download/etl에 받아 둔 eTL 파일을 고른 기간이 지나면 지워요. 비서가 만든 파일(영민이가 만든 파일 폴더)은 지우지 않아요." body2Regular secondary → 12 → 칩 "2일"(기본 선택) "7일" "30일" "정리 안 함" → 8 → "계속 두고 싶은 자료는 파일 카드 옆 공유 아이콘을 눌러 다른 곳에 저장하세요. 설정 → 받은 파일에서 언제든 바꿀 수 있어요." caption1Regular tertiary
  - 버튼: TextButton "저장"만("나중에" 없음).
- 동작: "저장" → 기간 저장 → `chat`; 바깥 탭 → 고르지 않은 것으로 치고 닫은 횟수+1(2번 닫으면 더 묻지 않음) → `chat`.
- SwiftUI 메모: 1단계 범위 밖(받은 파일 자동 정리). 구현한다면 칩이 들어가야 해서 `.alert` 불가 → 작은 커스텀 카드 대화상자(`.overlay` + 막) 또는 `.sheet` + `.presentationDetents([.medium])`.

---

## chat-pending-notices — 등록 안 한 공지 시트

- Android 진입: `$SRC/chat/PendingNoticesSheet.kt:51` (시트), 행 :94, 자동 표시 `ChatActivity.kt:219-233` + `notify/PendingNoticesLogic.shouldAutoShow`.
- 목적: 알림 확인 때 받아 둔 시험·과제·보강/휴강 공지 중 아직 내 일정/시간표에 넣지 않은 것을 보여 주고 등록을 돕는다(네트워크 없음).
- 열리는 때: 칩 "등록할 공지 N개"를 누름, 또는 채팅을 열 때 **새(본 적 없는) 공지가 있고** 설정 "pendingSheet"가 켜져 있고 업데이트/정리 창이 뜰 차례가 아니면 자동. 목록이 비면 닫힘.
- 모양: `ModalBottomSheet(skipPartiallyExpanded)`, 배경 backgroundNeutralPrimary, 위 모서리 24, M3 기본 드래그 손잡이. 내용 세로 스크롤, 좌우 24.
  1. "등록 안 한 공지 {N}개" headline2Bold primary
  2. 4 → "누르면 공지 내용을 채운 창이 열려요(그 창에서 비서에게 채워 달라고 할 수도 있어요). eTL 과제로 이미 올라온 마감이면 넣지 않아도 돼요." caption1Regular secondary
  3. 12 → 공지 카드 최대 20개(사이 10). 카드 = 가로 꽉 참, 모서리 16, 배경 surfaceNeutralSecondary, 안쪽 14:
     - 행: 종류 태그(초안 분류 라벨, 없으면 시험이면 "시험" 아니면 "과제") caption1Bold, 모서리 6, 안쪽 6/1 — 시험: 글 foregroundCautionaryPrimary + 배경 surfaceCautionaryPrimary, 그 밖: 글 foregroundBrandPrimary + 배경 backgroundNeutralPrimary → 6 → "{과목 짧은 이름} · {게시 날짜(공백 앞 부분, 예 11/24(월))}" caption1Regular secondary
     - 4 → 공지 제목(최대 80자로 정리) body1Medium primary
     - (초안에 날짜가 있으면) "공지에서 찾은 날짜: {시작}~{끝 시각}" 또는 "공지에서 찾은 날짜: {날짜}" caption1Regular brand
     - 8 → FlowRow(8): `WantedButton(PRIMARY, SMALL, SOLID)` "{행동}" — 기본 "내 일정에 등록", 보강·휴강 초안이면 "시간표에 반영" → TextButton "무시"
  4. 오른쪽 정렬 TextButton "나중에" → 24 여백.
- 동작:
  - "내 일정에 등록" → 시트 닫고 내 일정의 미리 채운 추가 창 `schedule-draft` (같은 공지로 이미 넣었으면 그 항목 고치기 + 토스트 "이 공지로 이미 넣은 일정이에요").
  - "시간표에 반영" → 시트 닫고 시간표의 보강·휴강 추가 창 `timetable-change-edit` (이미 반영했으면 토스트 "이 공지는 이미 시간표에 반영했어요").
  - "무시" → 그 공지를 영구히 목록에서 뺌(같은 시트, 비면 닫힘).
  - "나중에"/아래로 끌기/바깥 → `chat` (새 공지가 생기기 전에는 자동으로 다시 안 뜸).
- 참고: 파일 머리 주석에는 "[비서에게 부탁](입력창 채우기)" 버튼이 적혀 있지만 **실제 코드에는 그 버튼이 없다**.
- SwiftUI 메모: `.sheet` + `.presentationDetents([.large])`(또는 내용이 짧으면 `.medium, .large`) + `.presentationCornerRadius(24)` + 드래그 표시. 대상 화면은 시트 닫힘 완료 후 NavigationStack push + 초안 전달.

---

## chat-write-approval — 비서의 쓰기 승인 (dialog)

- Android 진입: `$SRC/chat/ChatActivity.kt:252-273` (AppCompat `AlertDialog`, Compose 아님), 요청 모델 `$SRC/etl/AiWriteApproval.kt:10`.
- 목적: 비서 도구가 내 일정·시간표 등 앱 데이터를 바꾸려 할 때, 화면의 버튼으로만 한 번 승인(모델의 "승인했다"는 글은 무시).
- 모양: 시스템 Material(AppCompat DayNight) 알림창.
  - 제목 = `request.operation`(도구가 정한 짧은 작업 이름)
  - 본문 = `request.details` + "\n\n이 변경을 적용할까요? 취소하면 변경하지 않아요."
  - 버튼: 왼쪽(부정) "취소", 오른쪽(긍정) "변경 승인". 긍정 버튼은 다른 창이 덮여 있으면 터치 무시(`filterTouchesWhenObscured`).
- 조건: 채팅 화면이 보이는(onResume~onPause) 동안 대기 중인 첫 요청. 화면을 떠나면 창 닫힘(요청은 남음). 오류로 턴이 끝나면 모든 대기 요청 취소.
- 동작: "변경 승인" → 승인 → `chat`; "취소"/뒤로/바깥 → 거절 → `chat`.
- SwiftUI 메모: `.alert(operation, isPresented:) { Button("취소", role: .cancel){…}; Button("변경 승인"){…} } message: { Text(details + "\n\n이 변경을 적용할까요? 취소하면 변경하지 않아요.") }`. `destructive` 필드는 원본 UI에서 쓰지 않음.

---

## share-receiver — "공유 → 영민이" 받기 (system, 화면 없음)

- Android 진입: `$SRC/chat/ShareReceiverActivity.kt:70` (투명 테마, 최근 앱 목록 제외), 파싱 :31, 채팅 반영 `ChatActivity.kt:183-192`. 매니페스트 `AndroidManifest.xml:120-136` (SEND/SEND_MULTIPLE, `*/*`).
- 흐름(보이는 UI는 토스트뿐):
  1. 첫 설정 전이면 토스트(LONG) "먼저 첫 설정을 마쳐 주세요. 공유한 내용은 가져오지 않았어요" → `onboarding-welcome`.
  2. 가져올 것 없음 → 토스트 "가져올 수 있는 내용이 없어요", 종료.
  3. 파일이 있으면 토스트 "파일을 가져오는 중이에요…" → 뒤에서 작업 폴더로 복사(최대 5개, content:// 만, 이 앱 자신의 주소 제외; 글은 제목+본문 최대 4000자).
  4. `chat`을 열어 **새 대화**를 만들고(답변 중이면 지금 대화에 넣고 토스트 "답변 중이라 지금 대화의 입력창에 넣었어요") 첨부 줄·입력창에 채움. 자동 전송 없음. 실패 문구는 토스트로, 5개 초과면 "한 번에 5개까지 첨부할 수 있어요".
- 동작: `ext-share-sheet`(다른 앱의 공유) → `share-receiver` → `chat` / `onboarding-welcome`.
- SwiftUI 메모: iOS에서는 Share Extension 타깃 + App Group 컨테이너로 파일을 넘기고 `youngmin://share?key=` 딥링크로 앱을 연다. **1단계 범위 밖**(PORTING.md 40행).

---

## 결정 못 한 것 / 확인 필요

1. Montage 토큰의 정확한 hex·글꼴 크기(라이브러리 미포함). 특히 surfaceNeutralSecondary, surfaceBrandSubtle, surfaceCautionaryPrimary 계열, title2Bold 크기(Theme.swift 22 vs Montage 스펙 28). 실기기 스크린샷 픽셀 대조 필요.
2. `WantedChip` 활성/비활성 실제 모양, `WantedButton`·`WantedTextField` 정확한 높이·모서리(추정치).
3. iOS ChatGPT 로그인: 장치 코드 대체 흐름(코드 카드, "브라우저가 안 되나요? 코드로 로그인")을 API 키 입력으로 바꿀지.
4. 파일 카드의 "드라이브에 저장/다른 곳에 저장" — 연결만 되고 그려지지 않음(원본 버그인지 의도인지 불명).
5. 공지 시트 주석의 "비서에게 부탁" 버튼 — 코드에 없음.
6. `request.operation`/`details`의 실제 문구는 도구 쪽(EtlTools)에서 정해져 이 범위에서 확정 못 함.
