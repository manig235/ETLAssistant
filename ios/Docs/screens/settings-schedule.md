# 설정 · 내 일정 · 시간표 · 캘린더 · 알림 · 위젯 (group: `settings-schedule`)

원본: `$ANDROID/app/src/main/java/kr/snu/etlassistant/` (아래 경로는 이 기준 상대 경로). 리소스는 `$ANDROID/app/src/main/res/`.
그래프: `wireframe-settings-schedule.json` (같은 id).

---

## 0. 공통 규칙 (모든 화면에 적용)

### 0.1 색·글꼴 토큰 (Montage `DesignSystemTheme`, montage-android v4.0.3)
iOS 대응은 이미 있는 `Youngmin/UI/Common/Theme.swift`(`YMColor`, `YMFont`)를 쓴다. Montage 라이브러리 원본은 오프라인으로 볼 수 없어
`surface*`·`foregroundBrand*` 의 정확한 hex는 확인하지 못했다(아래 "≈"는 근사).

| Montage 토큰 | 쓰임 | iOS |
|---|---|---|
| `backgroundNeutralPrimary` | 화면 배경, 대화상자 배경(`containerColor`), 입력칸 배경, 로그인 패널 배경 | `YMColor.background` (#FFFFFF / #1B1C1E) |
| `surfaceNeutralSecondary` | 카드(설정 카드, 목록 행 카드) 배경 | ≈ `YMColor.backgroundAlternative` (#F7F7F8 / 다크는 배경보다 밝은 회색 필요 — 다크에서 #0F0F10은 배경보다 어두워 카드가 "파인" 것처럼 보임, 확인 필요) |
| `surfacePositivePrimary` / `surfaceNegativePrimary` / `surfaceCautionaryPrimary` | 상태 배너 배경, 임박 요약 카드(주의), "시험" 배지 배경 | ≈ `YMColor.positive/negative/cautionary.opacity(0.08~0.12)` |
| `surfaceBrandSubtle` | 지울 수 있는 알림 시점 칩 배경 | ≈ `YMColor.brand.opacity(0.10)` |
| `foregroundNeutralPrimary` | 제목·본문 | `YMColor.label` |
| `foregroundNeutralSecondary` | 보조 설명, 아이콘(기본) | `YMColor.labelSecondary` |
| `foregroundNeutralTertiary` | 완료/비활성 글자 | `YMColor.labelTertiary` |
| `foregroundBrandPrimary` | 강조 글자(날짜 그룹 머리, + 아이콘, 하이퍼링크) | `YMColor.brand` |
| `foregroundPositivePrimary` / `foregroundNegativePrimary` / `foregroundCautionaryPrimary` | 상태 아이콘, 오류 문구, 시험 표시 | `YMColor.positive/negative/cautionary` |
| `lineNeutralPrimary` | 편집 가능 값 테두리(1dp) | `YMColor.line` |

글꼴(Montage 이름 → 크기 sp, Pretendard 기준; iOS는 `YMFont`·시스템 글꼴):
`title1Bold`(로그인 코드에만, 30sp 모노스페이스로 덮어씀) · `title2Bold`(상단바 제목) · `title3Bold`(편집 대화상자 제목, Montage 24) ·
`headline1Bold`(설정 섹션 제목, 18) · `headline2Bold`(확인 대화상자 제목·소제목, 17) · `body1Bold/Medium`(16) · `body2Medium/Regular`(15) ·
`caption1Regular/Bold`(12) · `caption2Regular`(11).
주의: `Theme.swift`는 `title2Bold`를 22pt로 정했다(Montage 명목값 28). 상단바 56dp에 맞춘 값이니 그대로 쓴다. `title3Bold`는 `Theme.swift`에 없음 → 20~24pt bold 추가 필요.

### 0.2 공통 컴포넌트 → SwiftUI
| Android | 모양 | SwiftUI |
|---|---|---|
| 상단바(각 화면 `Row` 56dp, 좌우 패딩 8dp) | `IconButton(ArrowBack, "뒤로")` + 제목(`title2Bold`, 시작 패딩 4dp) + 오른쪽 아이콘들 | 커스텀 `HStack`(height 56, `.padding(.horizontal, 8)`), 아이콘 버튼 44×44 hit. 시스템 내비게이션 바 대신 직접 그려야 똑같다(또는 `.toolbar` + `.navigationBarTitleDisplayMode(.inline)`이면 제목이 가운데로 가서 달라짐) |
| `IconButton` | 48dp 터치, 아이콘 24dp | `Button { } label: { Image(systemName:) }.frame(width: 48, height: 48)` |
| `WantedButton(PRIMARY, MEDIUM, SOLID)` = MainButton | 브랜드색 꽉 찬 버튼, 높이 ≈40dp, 모서리 ≈10dp | `.buttonStyle(YMPrimaryButtonStyle)` 높이 40, radius 10, 글자 `label1/body2 Bold` 흰색 |
| `WantedButton(ASSISTIVE, MEDIUM, OUTLINED)` = SoftButton/OutlineButton | 흰 배경 + 1dp 회색 테두리, 높이 ≈40dp | 테두리 `YMColor.line`, 글자 `YMColor.label` |
| `WantedButton(..., LARGE)` | 높이 ≈48dp, 가로 꽉 참 | 높이 48, `.frame(maxWidth: .infinity)` |
| `WantedTextButton` = DialogButton/TextBtn | 글자만(브랜드색), 대화상자 버튼 | `Button(…)`.`buttonStyle(.borderless)` 글자 `YMColor.brand` `body2Medium` |
| `WantedChip(text, isActive)` | 둥근 칩 높이 ≈32dp. 활성: 진한(브랜드/검정) 배경·흰 글자, 비활성: 회색 테두리/배경. `isEnable=false`면 흐림 | 커스텀 `ChipView(text:isActive:enabled:)` capsule/radius 8, padding h12 |
| `WantedCheckBox(style = Switch)` | 스위치 | `Toggle("", isOn:).labelsHidden().tint(YMColor.brand)` |
| `WantedCheckBox(style = CheckBox)` | 체크박스 | `Image(systemName: isOn ? "checkmark.square.fill" : "square")` |
| `WantedTextField(text, placeholder, title?, description?)` | 제목(위) + 입력칸(테두리, radius ≈12) + 설명(아래, caption) | `VStack { Text(title) ; TextField / SecureField ; Text(description) }`, 입력칸 높이 ≈48, radius 12, 1pt `YMColor.line` 테두리 |
| `WantedCircularProgressIndicator` | 작은 원형 스피너(≈20~24dp) | `ProgressView().controlSize(.small)` |
| `AlertDialog` (Material3) | 둥근 모서리 28dp 대화상자, 제목·본문·오른쪽 아래 글자 버튼(dismiss 왼쪽, confirm 오른쪽) | 본문이 문자열뿐이면 `.alert(title, isPresented:) { Button("취소", role: .cancel) ; Button("…") } message:`; 칩·입력칸이 들어가면 `.alert`로 안 됨 → `.sheet` + `.presentationDetents([.medium, .large])` 또는 커스텀 중앙 카드 오버레이(모양을 맞추려면 커스텀 카드: 폭 `min(화면-48, 560)`, radius 28, padding 24) |
| `ModalBottomSheet` | 위 모서리 24dp | `.sheet` + `.presentationDetents([.large])`(skipPartiallyExpanded) + `.presentationCornerRadius(24)` |
| Toast(짧게/길게) | 화면 아래 회색 캡슐 문구 2s/3.5s | iOS에 없음 → 공용 `ToastCenter` 오버레이(아래 중앙 capsule, 2.0s/3.5s) |
| `DatePickerDialog` / `TimePickerDialog` (시스템) | 달력/시계 다이얼 대화상자, 24시간제는 기기 설정 | `.sheet` 안 `DatePicker(…, displayedComponents: .date).datePickerStyle(.graphical)` / `.hourAndMinute` + `.wheel`, 확인·취소. 시간대는 KST 고정(`EtlLogic.kst`) — 설정의 브리핑/방해 금지만 기기 시간대 |

### 0.3 아이콘 (Material → SF Symbols 권장)
ArrowBack→`chevron.left`(또는 `arrow.left`로 똑같이) · KeyboardArrowRight→`chevron.right` · CheckCircle→`checkmark.circle` · ErrorOutline→`exclamationmark.circle` ·
WarningAmber→`exclamationmark.triangle` · Info→`info.circle` · Edit→`pencil` · Close→`xmark` · Refresh→`arrow.clockwise` · DeleteSweep→`trash.slash`(근사) ·
DeleteOutline→`trash` · Add→`plus` · CalendarViewWeek→`calendar.day.timeline.left`(근사) · RadioButtonUnchecked→`circle` · Assignment→`doc.text` ·
EditNote→`square.and.pencil` · Event→`calendar` · EventAvailable→`calendar.badge.plus` · FactCheck→`checklist` · PersonOutline→`person` ·
LocationOn→`mappin.and.ellipse` · Schedule→`clock` · ExpandMore/ExpandLess→`chevron.down`/`chevron.up`.

### 0.4 iPad(넓은 화면, 2026-11-27)
가로 size class·실제 폭 기준이며 아이폰 화면은 그대로다(규칙 전체는 `chat-onboarding.md` 0.6).
- `settings`·`calendar-settings`·`settings-widget-picker-dialog`(위젯 안내)·`course-alerts`·`notify-history`·`my-schedule`(상태 줄·칩·목록)·`privacy-policy`:
  상단바는 전체 폭, 본문은 가운데 최대 720pt(`ymReadableWidth`). regular 폭에서는 왼쪽에 대화 목록이 붙은 채로 오른쪽에 열린다.
- `timetable`: 격자는 전체 폭을 쓰고, regular 폭에서는 시간 칸 높이를 1.3배로 키운다.
- 편집 시트(`my-schedule-edit`, `timetable-custom-course`, `timetable-change-edit` 등)와 날짜·시각 고르기는 폼 시트로 뜬다(detent 무시). 가운데 카드 대화상자는 원래대로 최대 560pt.
- `.ics` 공유는 "공유하기" 버튼에 붙는 팝오버(iPad는 앵커 없으면 종료됨).
- 위젯은 iPad의 아주 크게(`systemExtraLarge`)까지 이미 있다. 위젯 안내 문구는 "iPhone·iPad에서는".

---

## settings — 설정

- Android 진입점: `chat/SettingsActivity.kt:46` (화면 `chat/SettingsScreen.kt:178` `SettingsRoot`)
- 목적: ChatGPT 계정, eTL 토큰, 알림(유형·시점·방해 금지·권한·배터리), 캘린더, 받은 파일 정리, 말투, AI 모델, API 키, 기타(화면 모드·업데이트·바로가기), 앱 정보.
- 들어오는 곳: 채팅 서랍 등(다른 그룹). 화면이 보이는 동안 `CodexSession`에 리스너로 붙어 로그인 이벤트를 받는다(`onStart` attach, `onStop` detach).

### 레이아웃 (위→아래)
루트 `Column` 배경 `backgroundNeutralPrimary`, `systemBarsPadding` + `imePadding`.

1. **상단바** 56dp: `ArrowBack`("뒤로", tint `foregroundNeutralPrimary`) · "설정"(`title2Bold`).
2. **스크롤 본문**: `verticalScroll`, 좌우 패딩 **24dp**, 맨 위 8dp, 섹션 사이 **32dp**, 맨 아래 40dp.
   각 섹션 = `Section(title)`: 제목 `headline1Bold` `foregroundNeutralPrimary` → 12dp → 카드들(카드 사이 12dp).
   카드 = `SettingsCard`: 가로 꽉 참, 모서리 **16dp**, 배경 `surfaceNeutralSecondary`, 안쪽 패딩 **16dp**.

#### 공통 행 모양
- **StatusBanner**(상태 줄): 가로 꽉 참, 모서리 12dp, 배경 = 톤별(POSITIVE `surfacePositivePrimary`, NEGATIVE `surfaceNegativePrimary`, CAUTION `surfaceCautionaryPrimary`, NEUTRAL `backgroundNeutralPrimary`), 패딩 h14 v12.
  왼쪽 아이콘 22dp(POSITIVE `CheckCircle` 초록, NEGATIVE `ErrorOutline` 빨강, CAUTION `WarningAmber` 주황, NEUTRAL `Info` 회색) 또는 로딩이면 스피너 → 10dp → 문구 `body2Medium` `foregroundNeutralPrimary`.
- **SwitchRow**: 줄 전체 클릭(토글), 세로 패딩 8dp. 왼쪽 제목 `body1Medium` + (설명 있으면 2dp 아래) `caption1Regular` `foregroundNeutralSecondary`; 12dp; 오른쪽 스위치.
- **LinkRow**: 줄 전체 클릭, 세로 패딩 12dp, 제목 `body1Medium` + (설명) `caption1Regular` 회색, 오른쪽 `KeyboardArrowRight` 회색.
- **EditableValue**(시각 값): 모서리 12dp, 1dp `lineNeutralPrimary` 테두리, 패딩 s14 e12 v9, 글자 `body2Medium` → 8dp → `Edit` 아이콘 16dp 회색.
- **RemovableChip**: 모서리 10dp, 배경 `surfaceBrandSubtle`, 시작 패딩 12dp, 글자 `body2Medium` `foregroundBrandPrimary`, 오른쪽 36×36 터치 영역 안 `Close` 16dp(브랜드색). 접근성 "{label} 지우기".
- **Caption**: `caption1Regular` `foregroundNeutralSecondary`.
- **HyperLink**: 최소 높이 48dp, `body2Medium` `foregroundBrandPrimary` + 밑줄(비활성이면 `foregroundNeutralTertiary`, 밑줄 없음).

#### 섹션 1 — "ChatGPT 계정" (`SettingsScreen.kt:470`)
카드 하나:
- StatusBanner = `SettingsLogic.accountStatus`:
  - 세션 FAILED: "비서를 시작하지 못했어요: {detail}" (NEGATIVE)
  - 세션 준비 전: "준비하는 중이에요…" (NEUTRAL, 스피너)
  - 계정 정보 없음: "확인하는 중이에요…" (NEUTRAL, 스피너)
  - ChatGPT: "로그인됨 · {email 또는 ChatGPT} · {plan}" (POSITIVE)
  - API 키: "API 키로 사용 중이에요" (POSITIVE) · UNKNOWN: "로그인됨" (POSITIVE) · 그 밖: "로그인되지 않았어요" (NEUTRAL)
- (로그인 진행 중일 때만) 12dp → **LoginPanel** (`:501`): 모서리 12dp, 배경 `backgroundNeutralPrimary`, 패딩 16dp.
  - 기기 코드 방식: 코드(선택 가능) `title1Bold` 모노스페이스 **30sp**, 자간 3sp, 가운데, 세로 패딩 8dp → 8dp →
    "코드를 클립보드에 복사해 두었어요. 브라우저의 코드 입력칸에 붙여넣으세요(하이픈 포함 9자)."(`body2Regular` 회색) → 6dp →
    "남은 시간 {m:ss}"(`body2Medium`, 1초마다 갱신, 15분 TTL).
  - 브라우저 방식: 스피너 + 12dp + "브라우저에서 ChatGPT 로그인을 마치세요. 끝나면 알림이 오고, 이 앱으로 돌아오면 돼요."(`body2Medium`).
  - 12dp → 버튼 FlowRow(간격 8dp): [기기 코드일 때] MainButton "코드 복사" · SoftButton "브라우저 다시 열기" · [브라우저일 때] SoftButton "브라우저가 안 되나요? 코드로 로그인" · SoftButton "취소".
- 12dp → 버튼 FlowRow(8dp):
  - 로그인 안 됨: MainButton(PRIMARY SOLID) "브라우저로 로그인" / 로그인됨: SoftButton(ASSISTIVE OUTLINED) "다른 계정으로 로그인". 활성 조건: 세션 READY && 로그인 진행 중 아님.
  - SoftButton "로그아웃" — 활성 조건: READY && 계정 종류 CHATGPT.

#### 섹션 2 — "eTL 토큰" (`:568`)
카드:
- StatusBanner: 토큰 있음 "저장됨 (Keystore로 암호화)"(POSITIVE) / 없음 "저장된 토큰이 없어요"(CAUTION).
- 12dp → SoftButton "eTL 토큰 만들러 가기" (→ 브라우저 `https://myetl.snu.ac.kr/profile/settings`).
- 16dp → WantedTextField: title = 토큰 있으면 "다른 토큰으로 바꾸기" / 없으면 "eTL 토큰"; placeholder "토큰을 붙여넣어 주세요"; description "이 폰의 Keystore에 암호화해서 저장해요."; 비밀번호 가림, 키보드 Password, 배경 `backgroundNeutralPrimary`.
- 12dp → FlowRow: MainButton "저장"(입력이 비어 있지 않을 때만 활성; 저장 성공 시 입력칸 비움) · SoftButton "eTL 연결 해제"(토큰 있을 때만 활성 → `settings-etl-disconnect-dialog`).
- 토스트: 형식 오류 "토큰 형식이 올바르지 않아요(공백 없는 ASCII 문자)"(길게; 규칙: `[\x21-\x7e]{8,}`), 성공 "eTL 토큰을 저장했어요", 예외 "저장하지 못했어요({예외명})", 해제 후 "eTL 연결을 해제했어요".

#### 섹션 3 — "알림" (`:616`) — 카드 3개
**카드 A (알림 종류)**
- (토큰 없을 때) StatusBanner "eTL 토큰을 먼저 저장해야 알림이 동작해요."(CAUTION) + 8dp.
- SwitchRow "마감 임박 알림" / 설명 = `windowsSummary`: 예 "3일, 24시간, 3시간, 10분 전에 알려줘요" (기본 켬).
  - 켜져 있으면: 4dp → FlowRow(8dp, 아래 패딩 4dp): 시점마다 RemovableChip "{label} 전"(가까운 것부터 오름차순: 예 "10분 전" "3시간 전" "24시간 전" "3일 전") + (12개 미만이면) WantedChip "+ 추가" → `settings-window-add-dialog`.
  - 마지막 남은 시점을 ×하면 지우지 않고 빨간 caption "알림 시점이 하나는 있어야 해요. 알림을 끄려면 위 스위치를 쓰세요".
  - 기본값(3일·24시간·3시간·10분)이 아니면 WantedTextButton "기본값으로 되돌리기". 4dp.
  - 시점 이름 규칙(`NotifySettings.windowLabel`): 60분 미만 "N분", 시간 단위 아님 "N시간 M분", 48시간 이상이고 일 단위 "N일", 그 외 "N시간"(그래서 1440 = "24시간").
- SwitchRow "새 공지 알림" / "수강 중인 강의에 새 공지가 올라오면 알려줘요(보강·휴강 공지는 따로 표시)" (기본 켬)
- SwitchRow "새 과제·마감 변경·성적 알림" / "과제가 새로 올라오거나, 마감이 바뀌거나, 점수가 등록되면 알려줘요" (기본 켬)
- SwitchRow "제출 직후 확인" / "과제를 내면 eTL 제출 기록을 살펴보고 빈 파일·허용되지 않은 형식·첨부 없음 같은 문제가 있으면 바로 알려줘요" (기본 켬)
  - 켜져 있을 때만: SwitchRow "정상 제출도 알리기" / "문제가 없을 때도 \"제출 확인됐어요\"를 소리·팝업 없이 알림창에 남겨요" (기본 켬)
- SwitchRow "새 강의자료 알림" / "4시간마다 과목별 파일 목록을 보고 새 자료가 올라오면 알려줘요. 누르면 비서에게 받아 달라고 할 수 있어요" (기본 켬)
- SwitchRow "앱을 열 때 등록할 공지 보여 주기" / "아직 넣지 않은 시험·과제·동영상 강의·보강·휴강 공지가 새로 오면 채팅 화면에서 한 번 보여 줘요" (기본 켬)
- SwitchRow "시험 끝나면 수고했어요 알림" / "내 일정에 넣은 시험에 끝 시각이 있으면 끝난 뒤 한 번 알려줘요(방해 금지 시간에는 보내지 않아요)" (기본 켬)
- SwitchRow "수업 알림" / "시간표(수강편람 + 보강·휴강)의 수업 시작 {N}분 전에 알려줘요" (기본 꺼짐, N 기본 10)
  - 켜져 있으면: 4dp → FlowRow(8dp, 아래 4dp): WantedChip "5분 전" "10분 전" "15분 전" "30분 전"(현재 값 활성).
- LinkRow "과목별 알림" / 설명 = `CourseFilterLogic.summary`: "모든 과목 알림 받는 중" 또는 "알림 끈 과목 N개 · 숨긴 과목 N개(시간표에서도 N개)" → `course-alerts`.
- SwitchRow "정기 브리핑" / "마감·누락·새 공지를 정해진 시각에 요약해서 보내줘요" (기본 꺼짐)
  - 켜져 있으면: 4dp → "브리핑 시각"(`body2Medium`) → 8dp → 시각마다 Row(아래 8dp): EditableValue "오전 8:00"(24시간제면 "08:00") → `settings-time-picker`; 두 번째 시각 옆 4dp + WantedTextButton "삭제".
    시각이 1개면 WantedTextButton "+ 시각 추가"(첫 시각 +12시간을 추가). 최대 2개, 정렬·중복 제거.

**카드 B (방해 금지)**
- SwitchRow "방해 금지" / "방해 금지 시간에는 마감·공지 알림을 미뤄요" (기본 꺼짐; 23:00~07:00)
  - 켜져 있으면: 4dp → FlowRow(8dp): EditableValue "시작 오후 11:00" · EditableValue "종료 오전 7:00" (→ `settings-time-picker`) → 8dp → Caption "정기 브리핑은 방해 금지와 무관하게 정해진 시각에 와요." → 8dp.
- SwitchRow "잠금화면에서 알림 내용 숨기기" (설명 없음, 기본 켬).

**카드 C (권한·상태)**
- 알림 권한: 허용 → StatusBanner "알림 권한: 허용됨"(POSITIVE) / 아니면 StatusBanner "알림 권한: 꺼져 있어요"(CAUTION) + 10dp + SoftButton "권한 요청" → `settings-notify-permission`.
- 10dp → StatusBanner "정확한 알림: 허용됨"(POSITIVE) / "정확한 알림: 허용 안 됨"(CAUTION) → 6dp → Caption
  "내 일정·마감·수업 알림 시각에 사용해요. 허용하지 않으면 절전 중 알림이 오래 늦어질 수 있어요. 서로 가까운 알림도 시스템 제한으로 늦어질 수 있어요. 정기 브리핑·새 공지 확인은 정확한 시각을 보장하지 않아요."
  → (허용 안 됨일 때) 10dp + SoftButton "정확한 알림 허용" → 시스템 설정(실패 토스트 "시스템 설정에서 영민이의 알람 및 리마인더를 허용해 주세요.").
- 10dp → 배터리: 예외 → StatusBanner "배터리: 제한 없음"(POSITIVE) / 아니면 StatusBanner "배터리 최적화가 켜져 있어 앱을 닫아 두면 알림이 늦거나 안 올 수 있어요"(CAUTION) + 10dp + SoftButton "배터리 제한 풀기" (→ 시스템 대화상자 + 긴 토스트 = SAMSUNG_HINT).
- 6dp → Caption "삼성 폰은 설정 → 배터리 → 백그라운드 사용 제한에서 '절전 앱'이나 '딥 슬립 앱'에 영민이가 있으면 빼 주세요."
- (절전 모드이고 배터리 예외 아님) 10dp + StatusBanner "절전 모드에서는 새 과제·공지 알림이 늦을 수 있어요. 이미 받아 둔 마감 알림은 그대로 와요."(CAUTION)
- 16dp → Caption 마지막 확인: "아직 확인한 적이 없어요" 또는 "마지막 확인: {KST 시각} · {결과}"(결과 예 "성공 · 마감 알림 1건", "실패 · eTL이 토큰을 거부했어요") → 2dp → Caption "아직 브리핑을 보낸 적이 없어요" / "마지막 브리핑: {KST 시각}".
- 12dp → FlowRow: SoftButton "지금 확인" · SoftButton "브리핑 미리 보내기".
  누르면: 권한 없으면 먼저 긴 토스트 "알림 권한이 꺼져 있어 알림이 뜨지 않아요. 위에서 권한을 허용하세요." → 실행 → 토스트 "요청했어요. 잠시 뒤 결과가 표시돼요" → 5초·15초 뒤 상태 줄 다시 읽기.

#### 섹션 4 — "캘린더" (`:795`)
카드: LinkRow "캘린더 연동" / 설명 = `CalendarExport.summary` (예 "꺼짐", "이 폰의 \"영민이\" 캘린더에 넣는 중", "구글 캘린더 \"{이름}\"에 넣는 중 · 내 캘린더 일정 보기 · 비서가 캘린더 사용") → `calendar-settings`.
Caption "과제·내 마감·시험을 삼성·구글 캘린더에 자동으로 넣고, 내 캘린더 일정을 내 일정과 브리핑에 함께 보여 줘요."

#### 섹션 5 — "받은 파일" (`files/CleanupUi.kt:42`)
카드: "받은 강의자료 자동 정리"(`body2Medium`) → 4dp → caption "비서가 폰 Download/etl에 받아 둔 eTL 파일을 고른 기간이 지나면 지워요. 비서가 만든 파일(영민이가 만든 파일 폴더)은 지우지 않아요."
→ caption "계속 두고 싶은 자료는 파일 카드 옆 공유 아이콘을 눌러 다른 곳에 저장하세요." → 10dp → 칩 FlowRow(8dp) "2일" "7일" "30일" "정리 안 함"(누르면 즉시 저장) → 8dp →
caption "아직 고르지 않았어요. 고르기 전에는 아무것도 지우지 않아요."(미선택) 또는 최근 정리 문구.

#### 섹션 6 — "대화" (`:807`)
카드: SwitchRow "반말로 대화" / "영민이가 친구처럼 반말로 답해요" (기본 꺼짐).

#### 섹션 7 — "AI 모델" (`:818`)
카드 없이 `ModelPickerContent`(chat/ModelPicker.kt:46, 그룹 `chat-model-picker`의 본문과 같음)를 그대로 넣는다: 안내 "다음 질문부터 적용돼요. 잘 모르겠으면 '자동'을 쓰세요.", "자동 (권장)" / "Codex가 정한 기본 모델을 써요", 모델 행(기본 표시 "기본"), "생각의 깊이" 칩 등. 상세는 chat 그룹 명세 참고.

#### 섹션 8 — "OpenAI API 키 (고급)" (`:834`)
카드:
- Caption "ChatGPT 계정 대신 본인의 API 키를 쓸 수 있어요. 사용한 만큼 본인의 OpenAI 계정에 요금이 청구돼요. 키는 이 폰의 Keystore에 암호화해 저장하고, 실행할 때만 환경변수로 넘겨요."
- 12dp → StatusBanner: 없음 "저장된 API 키가 없어요"(NEUTRAL) / 복호화 실패 "저장된 키를 읽지 못했어요(삭제 후 다시 저장하세요)"(CAUTION) / "저장됨 · sk-…abcd"(POSITIVE).
- 16dp → WantedTextField title "API 키", placeholder "sk-…", 가림.
- 12dp → FlowRow: MainButton "저장"(입력 있을 때) · SoftButton "삭제"(키 있을 때).
- 8dp → SwitchRow "API 키로 사용" / "끄면 ChatGPT 계정을 써요".
- 토스트: "API 키 형식이 아니에요(sk-로 시작, 공백 없음)", "API 키를 저장했어요", "먼저 API 키를 저장하세요"(키 없이 스위치 켬 → 스위치 꺼진 채 유지).

#### 섹션 9 — "기타" (`:876`)
카드:
- "화면 모드"(`body2Medium`) → 10dp → 칩 FlowRow "시스템 설정" "라이트" "다크"(즉시 적용·저장).
- 20dp → SwitchRow "업데이트 알림" / "새 버전이 나오면 알려줘요(GitHub에 최신 버전만 물어봐요)" (기본 켬).
- LinkRow "알림 기록" → `notify-history`
- LinkRow "시간표 · 보강/휴강" → `timetable`
- LinkRow "내 일정" → `my-schedule`
- LinkRow "홈 화면에 위젯 추가하기" → `settings-widget-picker-dialog`
- LinkRow "처음 안내 다시 보기" → `onboarding-welcome`

#### 섹션 10 — "앱 정보" (`:921`)
카드: "영민이"(`body1Bold`) → 4dp → Caption "만든 사람: 박영민 · 버전 {versionName}" → 10dp →
Caption "서울대학교의 공식 앱이 아니에요. 개발자는 개인정보를 수집하지 않아요. 질문·첨부와 비서가 불러온 정보는 OpenAI로 전송돼요." → 4dp →
LinkRow "개인정보 처리방침" → `privacy-policy` · HyperLink "건의사항 · 오류 제보" → `settings-feedback-dialog` ·
HyperLink: 확인 중 "확인하는 중…"(비활성) / 새 버전 있음 "새 버전 {v} 받기"(→ APK 링크) / 그 외 "업데이트 확인" · (결과 있으면) Caption "설치된 버전 {v} · 최신 공개 버전 {v}" 또는 "확인하지 못했어요({이유}). 인터넷 연결을 확인해 주세요".

### 상태
토큰 없음(eTL 배너·알림 배너 주의), 세션 준비 중/실패, 로그인 진행 중(패널), 알림 권한 꺼짐, 정확한 알림 꺼짐, 배터리 제한, 절전 모드, 업데이트 확인 중.

### 동작
| 누르는 것 | 일 | 대상 id |
|---|---|---|
| ← 뒤로 | finish | (이전 화면, 보통 `chat`) |
| 브라우저로 로그인 / 다른 계정으로 로그인 | ChatGPT 로그인 시작(브라우저) | `ext-browser` |
| 브라우저 다시 열기 | 로그인 URL 다시 열기 | `ext-browser` |
| 브라우저가 안 되나요? 코드로 로그인 | 취소 후 기기 코드 로그인(패널이 코드 모드로) | `settings` |
| 코드 복사 | 클립보드 + 토스트 "코드를 복사했어요" | `settings` |
| 취소 / 로그아웃 | 로그인 취소 / 로그아웃 | `settings` |
| 로그인 완료 이벤트 | 토스트 "로그인했어요" 또는 "로그인하지 못했어요: {오류}" | — |
| eTL 토큰 만들러 가기 | `https://myetl.snu.ac.kr/profile/settings` | `ext-browser` |
| 저장(토큰/키) | 검증·저장·세션 재시작 | `settings` |
| eTL 연결 해제 | 확인 대화상자 | `settings-etl-disconnect-dialog` |
| 시점 칩 × / + 추가 / 기본값으로 되돌리기 | 저장·재예약 / 추가 창 / 기본값 | `settings` / `settings-window-add-dialog` |
| 수업 알림 칩 | 분 저장 | `settings` |
| 과목별 알림 | | `course-alerts` |
| 브리핑·방해 금지 시각 값 | 시간 고르기 | `settings-time-picker` |
| 권한 요청 | Android 13+ 권한 대화상자 | `settings-notify-permission` |
| 정확한 알림 허용 / 배터리 제한 풀기 | 시스템 설정 | `ext-system-settings` |
| 지금 확인 / 브리핑 미리 보내기 | 워커 1회 실행 | `settings` (결과는 `notification`) |
| 캘린더 연동 | | `calendar-settings` |
| 화면 모드 칩 · 스위치들 | 즉시 저장 | `settings` |
| 알림 기록 / 시간표 · 보강/휴강 / 내 일정 | | `notify-history` / `timetable` / `my-schedule` |
| 홈 화면에 위젯 추가하기 | | `settings-widget-picker-dialog` |
| 처음 안내 다시 보기 | | `onboarding-welcome` |
| 개인정보 처리방침 | | `privacy-policy` |
| 건의사항 · 오류 제보 | | `settings-feedback-dialog` |
| 업데이트 확인 / 새 버전 받기 | GitHub 확인 / APK 링크 | `settings` / `ext-browser` |

### SwiftUI 메모
- `ScrollView { VStack(alignment: .leading, spacing: 0) }` + 섹션 사이 `Spacer().frame(height: 32)`; `List`/`Form`을 쓰면 iOS 그룹 스타일이 되어 모양이 달라진다 → 직접 그린 카드(`RoundedRectangle(16)` 배경 `surfaceNeutralSecondary`)를 쓴다. FlowRow → iOS 16+ `Layout` 기반 `FlowLayout`(공용으로 하나).
- **ChatGPT 계정**: PORTING 확정 — iOS는 OpenAI Responses API 직접 호출, "ChatGPT로 계속하기"(PKCE)가 기본. 로그인은 `ASWebAuthenticationSession`(앱 안 시트)이라 "브라우저 다시 열기"·기기 코드(9자 코드, 15분)는 필요 없을 수 있다 → 패널은 진행 중 스피너+취소만 남기는 것을 권장(다르게 됨). 상태 줄 문구(세션 준비 등)는 새 엔진 상태에 맞춰 같은 문구를 재사용.
- **Keystore 문구**: "저장됨 (Keystore로 암호화)", "이 폰의 Keystore에 …" — iOS는 Keychain. 원문 유지 규칙과 충돌하므로 결정 필요(권장: "Keychain"으로 바꿈).
- **API 키 설명**의 "실행할 때만 환경변수로 넘겨요"는 iOS(직접 호출)에서 사실이 아니다 → 문구 조정 필요.
- **알림 권한**: `UNUserNotificationCenter.requestAuthorization`; 거부 상태면 `UIApplication.openNotificationSettingsURLString`(iOS 16+)으로 이동.
- **정확한 알림 행**: iOS에 개념 없음(`UNCalendarNotificationTrigger`는 제시간에 울림, 단 대기 알림 64개 한도) → 행 전체 생략.
- **배터리 행·삼성 안내**: iOS에 없음 → 생략. 대신 선택적으로 `UIApplication.shared.backgroundRefreshStatus == .denied`일 때 CAUTION 배너 "백그라운드 앱 새로 고침이 꺼져 있어 …"(새 문구), 절전 모드는 `ProcessInfo.processInfo.isLowPowerModeEnabled`로 같은 POWER_SAVE_HINT 배너를 재현 가능.
- **지금 확인/브리핑 미리 보내기**: iOS는 WorkManager 대신 앱 안에서 바로 async 실행(BGTaskScheduler는 즉시 실행 불가).
- **잠금화면에서 알림 내용 숨기기**: iOS는 미리보기를 시스템 설정이 결정. 켜져 있으면 앱이 내용을 "영민이 알림/새 알림이 있어요"로 바꿔 게시하거나(다르게 보임), `UNNotificationCategory(hiddenPreviewsBodyPlaceholder: "새 알림이 있어요")`만 지정.
- **받은 파일** 섹션: PORTING 1단계 범위 밖(받은 파일 자동 정리) → 숨김.
- **캘린더** 섹션: 1단계 범위 밖(캘린더 연동) → 숨김 또는 "준비 중".
- **업데이트 알림 스위치·업데이트 확인 링크**: iOS에 없음(App Store/TestFlight) → 생략.
- **위젯 추가**: iOS는 앱이 위젯을 추가할 수 없다(`requestPinAppWidget` 없음) → 대화상자 대신 안내 시트.
- 시각 선택은 브리핑·방해 금지 모두 **기기 시간대**(원본이 기기 기준). 12/24시간 표시는 `DateFormatter.dateFormat(fromTemplate: "j", …)`에 'a' 포함 여부로 판단해 "오전 8:00" / "08:00" 직접 포맷.

---

## settings-window-add-dialog — 알림 시점 추가

- Android: `chat/SettingsScreen.kt:401` (`CustomWindowDialog`), kind dialog
- 레이아웃: AlertDialog(배경 `backgroundNeutralPrimary`). 제목 "알림 시점 추가"(`title3Bold`).
  본문: "자주 쓰는 시점"(`body2Medium`) → 8dp → FlowRow(8/8): 프리셋 칩 10분·30분·1시간·3시간·6시간·12시간·24시간·2일·3일·7일 → 글자 "{label} 전", 이미 있으면 "{label} 전 ✓" + 비활성(`isEnable=false`).
  → 16dp → "직접 입력"(`body2Medium`) → 8dp → WantedTextField placeholder "예: 30", 숫자 키보드, 숫자만 최대 4자리 → 10dp → 칩 FlowRow "분 전" "시간 전"(기본 활성) "일 전" → (오류) 8dp + `caption1Regular` 빨강.
  버튼: dismiss "취소" · confirm "추가".
- 오류 문구: "숫자를 입력해 주세요", "1 이상으로 입력해 주세요", "10분 이상으로 정해 주세요", "최대 14일 전까지 정할 수 있어요", "이미 있는 시점이에요", "알림 시점은 12개까지예요. 다른 시점을 먼저 지워 주세요".
- 동작: 프리셋 칩 → 검사 후 바로 추가·닫힘(`settings`). 추가 → 검사 후 추가·닫힘. 취소/바깥 → 닫힘.
- SwiftUI: 칩과 입력칸이 있어 `.alert` 불가 → `.sheet(.medium)` 또는 커스텀 중앙 카드. 숫자 입력 `TextField` `.keyboardType(.numberPad)`, `onChange`로 숫자만 4자리.

## settings-etl-disconnect-dialog — eTL 연결 해제 확인

- Android: `chat/SettingsScreen.kt:599`, kind dialog
- 제목 "eTL 연결을 해제할까요?" (기본 Material 스타일)
- 본문 "저장한 eTL 토큰과, 앱이 eTL에서 받아 둔 과제 목록·시간표·강의계획서 캐시를 이 폰에서 지워요. 대화, 받은 파일, 알림 기록, 내 마감은 그대로 남아요. eTL 웹사이트의 토큰은 eTL 설정에서 따로 지워 주세요."
- 버튼: "취소" · "해제"(→ 토큰·캐시 삭제, 위젯 갱신, 재예약, 세션 재시작, 토스트 "eTL 연결을 해제했어요").
- SwiftUI: `.alert` + `Button("해제", role: .destructive)`.

## settings-time-picker — 시각 고르기 (시스템)

- Android: `chat/SettingsActivity.kt:378` `TimePickerDialog`(기기 12/24시간제), kind system
- 쓰임: 브리핑 시각(1·2), 방해 금지 시작·종료. 확인 → 저장·재예약 → `settings`.
- SwiftUI: `.sheet` + `DatePicker("", selection:, displayedComponents: .hourAndMinute).datePickerStyle(.wheel)` + 확인/취소, 또는 iOS 기본 compact `DatePicker`를 EditableValue 자리에 두면 모양이 달라짐(권장하지 않음).

## settings-notify-permission — 알림 권한 요청 (시스템)

- Android: `chat/SettingsActivity.kt:58` (Android 13+ `POST_NOTIFICATIONS`), kind system
- 허용/거부 후 `settings`로. 거부하고 더 묻지 않는 상태면 앱 알림 설정(`ext-system-settings`) 열기; 실패 시 토스트 "알림 설정을 열지 못했어요. 시스템 설정에서 직접 허용해 주세요."
- SwiftUI: `requestAuthorization([.alert, .sound, .badge])`; `.denied`면 설정 앱 열기.

## settings-widget-picker-dialog — 홈 화면에 위젯 추가하기

- Android: `chat/SettingsScreen.kt:893`, kind dialog
- 제목 "홈 화면에 위젯 추가하기". 본문 LinkRow 5개: "일정 · 2×2", "일정 · 2×1", "시간표 · 4×4", "시간표 · 4×6", "다음 수업 · 2×1". 버튼 "닫기".
- 행 누르면 닫고 `requestPinAppWidget`(런처의 위젯 추가 확인 → `ext-widget-picker`). 지원 안 되면 긴 토스트 "홈 화면을 길게 누르고 위젯 목록에서 영민이를 선택해 주세요."
- SwiftUI: iOS는 앱에서 위젯을 추가할 수 없다 → 같은 제목의 안내 화면(`WidgetGuideView`, push, 2026-11-27 구현).
  - 홈 화면 단계: 빈 곳 길게 누르기 → 왼쪽 위 "편집" → "위젯 추가"(iOS 17은 왼쪽 위 +) → 목록에서 "영민이"(검색 가능) → 좌우로 넘겨 일정·시간표·다음 수업과 크기 고르기 → "위젯 추가" → "완료".
  - 위젯 종류 카드: 일정 "작게 · 중간 · 크게 · 잠금 화면", 시간표 "중간 · 크게 · 아주 크게(iPad)", 다음 수업 "작게 · 잠금 화면". 미리보기 그림은 넣지 않았다.
  - 메모: iOS 18부터 편집 중 위젯 모서리를 끌어 지원 크기 사이에서 바꿀 수 있음, 아주 크게는 iPad 전용, 위젯은 앱이 저장해 둔 정보로 그림.
  - 잠금 화면 단계: 잠금 화면 길게 누르기 → "사용자화" → "잠금 화면" → 시계 아래 위젯 영역 → "영민이"에서 일정·다음 수업.
  - 가상 eTL: 행 설명 "위젯에는 실제 eTL 자료만 보여요", 안내 화면 위 주의 배너.

## settings-feedback-dialog — 건의사항 · 오류 제보

- Android: `chat/SettingsScreen.kt:956`, kind dialog
- 제목 "건의사항 · 오류 제보"(`headline2Bold`), 본문 "앱에 오류가 있나요? 오픈채팅에 접속해서 혼내주세요!\n영민이한테 원하는 기능도 여기서 알려주세요!"(`body2Regular` 회색).
- 버튼: "닫기" · "오픈채팅 열기"(→ `https://open.kakao.com/o/sR3ebBYi` `ext-browser`; 실패 토스트 "링크를 열 앱이 없어요: {URL}").
- SwiftUI: `.alert` + `openURL`.

---

## course-alerts — 과목별 알림

- Android 진입점: `chat/CourseAlertsActivity.kt:71` (화면 `:148`)
- 목적: 이번 학기 과목마다 "알림 받기 / 알림 끄기 / 숨기기" 선택. 숨긴 과목은 "시간표·오늘 수업에서도 숨기기" 스위치 추가. 이 폰에만 저장.
- 들어오는 곳: 설정 → 과목별 알림, 내 일정·시간표의 "숨긴 과목 N개" 칩.
- 레이아웃:
  1. 상단바: ← "뒤로" · "과목별 알림"(`title2Bold`, weight 1) · 오른쪽: 받는 중이면 스피너, 아니고 토큰 있으면 `Refresh`("과목 목록 새로 받기", 회색).
  2. 설명(`caption1Regular` 회색, 좌우 24dp):
     "알림 끄기: 이 과목의 마감·공지·새 과제·성적·수업 알림을 보내지 않아요.\n숨기기: 알림도 끄고 내 일정·브리핑·일정 위젯에서도 과제와 공지를 빼요. 시간표에서도 뺄지는 과목마다 고를 수 있어요.\n비서에게 물어보면 숨긴 과목도 답해요. 설정은 이 폰에만 저장돼요."
  3. (받는 중이고 목록 있음) "과목 목록을 새로 받는 중… 새 과목은 맨 아래에 붙어요"(`caption1Regular` 브랜드색, h24 v4).
  4. 12dp.
  5. 빈 상태(가운데, `body1Medium` 회색): 토큰 없음 "eTL 토큰을 저장하면 수강 중인 과목이 보여요" / 받는 중 "과목 목록을 받는 중…" / "과목을 찾지 못했어요".
  6. 목록 `LazyColumn`(좌우 16dp, 간격 8dp, 끝 24dp): 과목 카드(모서리 16, `surfaceNeutralSecondary`, 패딩 16):
     과목 짧은 이름(`body1Medium`) → (목록에 없는 과목) "이번 학기 목록에 없는 과목"(caption 회색) → 8dp → 칩 FlowRow "알림 받기" "알림 끄기" "숨기기"(라디오처럼 하나 활성, 기본 "알림 받기") →
     (숨기기일 때) 8dp + 줄 전체 토글 Row(v4): "시간표·오늘 수업에서도 숨기기"(`body2Medium`) / "조교로 듣는 과목처럼 수업에 가지 않는 경우"(caption) + 12dp + 스위치(새로 숨기면 켜짐).
- 순서: 과목명순 고정, 새로 받은 과목은 끝에 붙음(보이던 줄 안 밀림). 6시간 지나면 열 때 자동 새로 받기.
- 토스트: "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요", "과목 목록을 새로 받지 못했어요: {메시지}", "과목 목록을 새로 받지 못했어요({예외명})".
- 동작: 칩 → 저장·다시 그림(`course-alerts`); 스위치 → 저장; ↻ → 새로 받기; ← → 이전 화면(`settings` 등).
- SwiftUI: `ScrollView`+`LazyVStack(spacing: 8)`; 칩 3개는 접근성 `.accessibilityAddTraits(.isSelected)`. 받는 중 스피너는 상단바 오른쪽 `ProgressView`.

---

## notify-history — 알림 기록

- Android 진입점: `chat/NotifyHistoryActivity.kt:50` (화면 `:93`)
- 목적: 최근 90일 동안 띄운 알림을 다시 보기, 공지 초안 등록.
- 레이아웃:
  1. 상단바: ← · "알림 기록" · (기록 있을 때) `DeleteSweep`("기록 지우기", 회색) → `notify-history-clear-dialog`.
  2. "최근 90일 동안 띄운 알림이에요. 이 폰에만 남아요."(`caption1Regular` 회색, h24) → 12dp.
  3. 빈 상태: 가운데 "아직 알림 기록이 없어요"(`body1Medium` 회색).
  4. 목록(좌우 16, 간격 8, 최신순): 카드(모서리 16, `surfaceNeutralSecondary`, 패딩 16, 눌러서 펼치기/접기):
     - "{MM/dd(요) HH:mm} · {채널}"(caption 회색). 채널 라벨: 마감 / 공지 / 브리핑 / 과제·성적 / 수업 / 업데이트 / 제출 확인 / 강의자료.
     - 2dp → 제목(`body1Medium`) → 본문(`body2Regular` 회색; 펼치면 bigText가 있으면 bigText).
     - 버튼 Row(8dp): 초안 있으면 WantedTextButton: 이미 넣음 → "등록됨 · 열기"(시간표면 "반영됨 · 열기"), 아니면 "내 일정에 등록"(보강·휴강이면 "시간표에 반영"); 펼쳤고 URL이 `https://myetl.snu.ac.kr/`로 시작하면 "eTL에서 열기".
- 동작: 카드 → 펼침 토글; "내 일정에 등록" → `schedule-draft`; "시간표에 반영" → `timetable-change-edit`(초안); "등록됨 · 열기" → `my-schedule-edit`(그 항목); "반영됨 · 열기" → `timetable`; "eTL에서 열기" → `ext-etl`; 휴지통 → `notify-history-clear-dialog`.
- SwiftUI: 카드 탭 `withAnimation` 펼침. iOS 알림 기록은 앱이 알림을 게시할 때 같은 저장소에 기록(원본 `NotifyHistory.record`).

## notify-history-clear-dialog — 알림 기록 지우기 확인

- Android: `chat/NotifyHistoryActivity.kt:151`, kind dialog
- 제목 "알림 기록을 지울까요?"(`headline2Bold`), 본문 "지운 기록은 되돌릴 수 없어요. 알림 설정은 그대로예요."(`body2Regular` 회색). 버튼 "취소" · "지우기".
- SwiftUI: `.alert`, "지우기" `.destructive`.

---

## my-schedule — 내 일정

- Android 진입점: `chat/MyDeadlinesActivity.kt:114` (화면 `AgendaScreen` `:366`)
- 목적: eTL 과제 + 내 마감 + 시험 (+ 고른 캘린더 일정) 목록. 임박 요약, 앞으로 7일, 그 이후. 내 마감 추가·수정·완료·삭제.
- 들어오는 곳: 설정 "내 일정", 위젯(헤더/행), 알림(내 마감·시험, 초안), 알림 기록, 캘린더 앱 "앱에서 열기"(CalendarOpenActivity), 채팅(다른 그룹).
  인텐트 `widget_deadline_id`가 있으면 그 항목의 편집 창(`my-schedule-edit`)을 바로 연다. 초안 extra가 있으면 `schedule-draft`(시간표 초안이면 `timetable`로 넘김; 이미 넣은 공지면 그 항목 편집 + 토스트 "이 공지로 이미 넣은 일정이에요").
- 30초마다 `now` 갱신(남은 시간). 열 때 캐시가 3시간 넘었으면 eTL 과제를 뒤에서 새로 받음.

### 레이아웃
1. 상단바 56dp: ← · "내 일정"(weight 1) · `CalendarViewWeek`("수업 시간표", 회색) · [받는 중 스피너 | 토큰 있으면 `Refresh`("eTL 과제 새로 받기", 회색)] · `Add`("내 마감·시험 추가", **브랜드색**).
2. 상태 줄(`caption1Regular` 회색, h24):
   토큰 없음 "eTL 토큰을 저장하면 eTL 과제 마감도 여기에 모여요." / 받은 적 없음 "eTL 과제를 받는 중…"(받는 중) 또는 "아직 eTL 과제를 받지 않았어요." / "eTL 과제 마지막 확인 {MM/dd(요) HH:mm}" + 마감 알림 꺼져 있으면 " · 마감 알림 꺼짐(설정에서 켤 수 있어요)".
3. 8dp → 칩 FlowRow(h20, 8/8): "전체"(기본) "과제" "내 마감" "시험" ["캘린더" — 내 캘린더 일정 보기 켰을 때만] · 토글 칩 "제출·완료 숨김"(기본, 비활성 모양) / "제출·완료 보기"(활성) · [숨긴 과목 > 0이고 필터 전체/과제] 칩 "숨긴 과목 N개" → `course-alerts`.
4. 8dp → `LazyColumn`(좌우 16, 간격 8):
   a. **임박 요약 카드**(모서리 16, 패딩 16, 간격 6; 배경: 임박 없음 `surfaceNeutralSecondary` / 있음 `surfaceCautionaryPrimary`):
      "임박한 마감 요약"(`body1Medium`) · "24시간 내 남은 마감이 없어요" 또는 "24시간 내 미완료 마감 N개"(caption 회색) ·
      최대 3줄: 제목(`body2Medium`, 1줄) + "{부제} · {언제}"(caption, 2줄) — 내 마감/URL 있으면 누를 수 있음(v6) · 넘치면 "외 N개" ·
      지난 미완료가 있으면 Row: "마감 지남 N개"(`body2Medium` 빨강) ↔ TextButton "펼치기"/"접기".
   b. (펼침) "마감 지남 · N개"(`body2Medium` 빨강, s8 t8) + 지난 행들.
   c. 섹션 머리(s8 t16 b4, 간격 4): "앞으로 7일 · N개"(`headline2Bold`) / "오늘 포함 · {M/d(요)}~{M/d(요)}"(caption) ; 두 번째 "그 이후 마감 · N개" / "{M/d(요)}부터".
   d. 빈 섹션: "{앞으로 7일 동안|그 이후에} {남은 }{시험이 없어요|캘린더 일정이 없어요|일정이 없어요}"(`body2Regular` 회색, h8 v16). "남은 "은 제출·완료 숨김일 때만.
   e. 날짜 머리: "오늘 · M/d(요)" / "내일 · M/d(요)" / "M/d(요)"(`body2Medium` 브랜드색, s8 t8).
   f. **AgendaRow**(모서리 16, `surfaceNeutralSecondary`, 패딩 s4 e4 t8 b8, 누를 수 있으면 버튼):
      - 왼쪽: 내 마감 → IconButton 체크(완료 `CheckCircle` 초록 / 아니면 `RadioButtonUnchecked` 회색; 접근성 "'{제목}' 완료"/"완료 취소") · 과제 → `Assignment` 24dp 브랜드색(제출이면 `CheckCircle` 초록), 패딩 12 · 시험 → `EditNote` 24dp 주황("시험") · 캘린더 → `Event` 24dp(캘린더 색).
      - 가운데(h4): [시험이면 배지 "시험" `caption1Bold` 주황, 배경 `surfaceCautionaryPrimary`, 모서리 6, 패딩 h6 v1, 6dp] + 제목(`body1Medium`, 2줄; 완료면 `foregroundNeutralTertiary` + 취소선) → 2dp → "{언제} · {부제}"(caption, 3줄; 지났으면 빨강).
        언제 = "MM/dd(요) HH:mm · N일 N시간 남음"(또는 "지남"); 시험 "MM/dd(요) HH:mm~HH:mm · …남음"; 캘린더 "MM/dd(요) HH:mm~…" 또는 "종일".
        부제: 과제 "{과목}{ · 오프라인| · 제출함| · 제출함(늦음)| · 제출함 · 확인 필요}"; 내 마감 "내 마감 · 🔁 {반복} · 🔔 {알림 시각} · {메모} · 완료"; 시험 "시험 · {장소} · 준비물: … · 🔔 … · {메모} · 진행 중"; 캘린더 "{캘린더 이름} · {장소} · 🔁".
      - 오른쪽 아이콘(20dp, 회색): [제출한 과제] `FactCheck`("'{제목}' 제출 확인"; 점검 문제 있으면 빨강) · [캘린더 아닌 행] `EventAvailable`("'{제목}' 캘린더에 추가") · [내 마감·시험] `DeleteOutline`("'{제목}' 지우기").
5. 끝 24dp.

### 상태
토큰 없음, 받는 중, 받은 적 없음, 빈 섹션, 임박 있음(주의 카드), 지난 미완료 있음, 캘린더 보기 켬/끔, 숨긴 과목 있음.

### 동작
| 누르는 것 | 일 | 대상 |
|---|---|---|
| ← | finish | 이전(`settings`/`chat`) |
| 수업 시간표 아이콘 | | `timetable` |
| ↻ | eTL 과제 새로 받기(토스트: "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요", "eTL 과제를 새로 받지 못했어요: …") | `my-schedule` |
| + | 추가 창(필터가 "시험"이면 시험으로 시작) | `my-schedule-edit` |
| 필터 칩 / 숨김 칩 | 필터 | `my-schedule` |
| 숨긴 과목 N개 | | `course-alerts` |
| 내 마감·시험 행(또는 요약 줄) | 고치기(반복이면 반복 전체) | `my-schedule-edit` |
| 과제 행 | eTL(LearningX 또는 웹) | `ext-etl` |
| 캘린더 일정 행 | 캘린더 앱에서 그 일정 열기(실패 토스트 "캘린더 앱을 열 수 없어요") | `ext-calendar-app` |
| 완료 체크 | 완료 토글(반복은 그 회차만) | `my-schedule` |
| 제출 확인 아이콘 | GET 두 번으로 점검(토큰 없으면 토스트 "eTL 토큰을 먼저 저장하세요") | `my-schedule-submission-check` |
| 캘린더에 추가 아이콘 | 캘린더 앱의 일정 추가 화면(권한 없이; 실패 "캘린더에 넣을 수 없어요"/"일정을 넣을 캘린더 앱이 없어요") | `ext-calendar-app` |
| 지우기 아이콘 | 확인 | `my-schedule-delete-dialog` |
| 펼치기/접기 | 지난 마감 보이기 | `my-schedule` |

### SwiftUI 메모
- `ScrollView { LazyVStack(spacing: 8) }` + 날짜 머리는 일반 행(고정 헤더 아님). 30초 타이머 `TimelineView(.periodic(from:by: 30))`.
- 캘린더 행·"캘린더" 필터·캘린더에 추가: EventKit 필요, 1단계 범위 밖 → 숨김(캘린더에 추가는 `EKEventEditViewController`로 같은 동작 가능).
- 과제 행 → LearningX 앱 대신 `openURL`(유니버설 링크면 LearningX iOS 앱이 열릴 수 있음).

## my-schedule-edit — 내 마감·시험 추가/고치기

- Android: `chat/MyDeadlinesActivity.kt:679` (`DeadlineDialog`), 반복 입력 `:944` (`RepeatInputs`), 계산 `notify/DeadlineForm.kt`. kind dialog
- 제목(`title3Bold`): 새로 "내 마감 추가"/"시험 추가", 고치기 "내 마감 고치기"/"시험 고치기".
- 본문(세로 스크롤):
  1. 종류 칩 Row(8dp): "마감" "시험"(접근성 "종류 마감, 선택됨"). 시험으로 바꾸면 기본 시각 23:59 → 10:00.
  2. 10dp → 제목 입력: placeholder 시험 "예: 중간고사" / 마감 "예: 장학금 서류 제출"(최대 60자). 12dp.
  3. **시험일 때**: "날짜와 시간"(`body2Medium`) → 6dp → FlowRow(8/8): OutlineButton "2026/11/26(목)"(날짜 → 날짜 선택) · "10:00 시작"(→ 시각) · "12:00 끝" 또는 "끝 시각 (선택)"(→ 시각, 기본 시작+2시간) · [끝 있으면] TextButton "끝 시각 지우기"
     → 10dp → 입력 "장소 (선택, 예: 301동 118호)"(40자) → 8dp → "과목 (선택)"(60자) → [과목 목록 있으면] 6dp + 칩 FlowRow(6/6) 최대 12개(누르면 선택/해제) → 8dp → "준비물 (선택, 예: 공학용 계산기, 신분증)"(100자).
  4. **마감일 때(RepeatInputs)**: "반복"(`body2Medium`) → 6dp → 칩 "없음"(기본) "매일" "매주" "격주" "매월" → 10dp →
     - 없음: OutlineButton 날짜 "yyyy/MM/dd(요)"(기본 내일) + 시각 "HH:mm"(기본 23:59).
     - 매일: 시각 + TextButton "시작 yyyy/MM/dd(요)" 또는 "시작 날짜 바꾸기".
     - 매주/격주: 요일 칩 "월"…"일"(6dp 간격, 여러 개) → 8dp → [격주] 칩 "이번 주부터"/"다음 주부터" + 8dp → 시각 → 요일 없으면 빨강 caption "요일을 하나 이상 골라 주세요."
     - 매월: Row "매월" · TextButton "−" · "{N}일"(`body1Medium`) · TextButton "+" · 8dp · 시각; N>28이면 caption "{N}일이 없는 달은 그달 마지막 날이 마감이에요."
     - 반복이면: "첫 마감: MM/dd(요) HH:mm"(caption 브랜드색, t6) → 10dp → "끝"(`body2Medium`) → 칩 "끝 없음"(기본) "날짜까지" "횟수" →
       날짜까지: OutlineButton "yyyy/MM/dd(요)까지" 또는 "끝 날짜 고르기"(기본 첫 날짜+1개월) / 횟수: TextButton "−" · "{N}번"(기본 10, 1~999) · "+" · "+10" →
       요약 caption("끝 없이 반복" 또는 "마지막 마감: yyyy/MM/dd(요) HH:mm · 총 N번") → [고치는 중이고 모양 같음] caption "반복 모양이 그대로면 시작 날짜는 바뀌지 않아요. 고치면 반복 전체에 적용돼요."
  5. 12dp → "알림 (선택)"(`body2Medium`) → 설명 caption: 시험 "[새 시험·안 건드림이면] 그대로 두면 전날 20:00과 시작 2시간 전에 알려요(응원 문구와 함께). " + "정한 시각에 꼭 울려요(방해 금지 시간에도)." / 마감 "설정의 기본 마감 알림과 별개로, 정한 시각에 꼭 울려요(방해 금지 시간에도)." + 반복이면 " 반복 마감은 회차마다 울려요."
     → 6dp → 칩 FlowRow: 정한 알림 칩 "{HH:mm} · {상대}  ✕" 또는 "{M/d} {HH:mm} · {상대}  ✕"(상대 = "N일 전"/"N시간 전"/"N시간 M분 전"/"N분 전"; 누르면 지움) + (5개 미만) "+10분 전" "+1시간 전" "+하루 전"(이미 있으면 숨김) "+시각 고르기"(시각 선택, 마감보다 늦으면 전날).
  6. 12dp → 입력 "메모 (선택)"(200자).
  7. [초안이 동영상 강의·대면 대체이고 날짜 있음] 10dp + 체크박스 행 "그날({M/d(요)}) 대면 수업은 휴강으로 표시"(`body2Regular`).
  8. 오류(빨강 caption): "요일을 하나 이상 골라 주세요", "제목을 입력하세요", "제목은 60자까지 쓸 수 있어요", "메모는 200자까지 쓸 수 있어요", "내 마감은 100개까지 등록할 수 있어요. 지난 항목을 지워 주세요", "첫 마감을 정할 수 없어요", "알림 시각은 5개까지 정할 수 있어요", "알림 시각은 마감보다 앞이어야 해요", "반복 끝 날짜는 시작 날짜보다 뒤여야 해요", "반복 횟수는 1~999번이어야 해요", "반복 기간 안에 해당하는 날이 없어요", "끝 시각은 시작 시각보다 뒤여야 해요", "시험 시간은 24시간을 넘을 수 없어요", "장소는 40자까지 쓸 수 있어요" 등.
- 버튼(confirm 슬롯에 Row 8dp): "취소" · "저장". 시험 제목 비면 "시험". 마감 시각을 바꾸면 새 id로 저장(옛 항목 삭제).
- 시간대: KST 고정.
- 동작: 저장 → 저장·알림 재예약·제시간 알람·(마감 알림 켬이면) 즉시 확인 → `my-schedule`; 취소/바깥 → `my-schedule`; 날짜 버튼 → `my-schedule-edit-date-picker`; 시각 버튼/"+시각 고르기" → `my-schedule-edit-time-picker`.
- SwiftUI: 내용이 길어 `.sheet` + `NavigationStack` 없는 `ScrollView` 폼 + 아래 버튼 Row 권장(`.alert` 불가). 모양을 AlertDialog처럼: 중앙 카드 radius 28, 최대 높이 화면의 ~85%.

## my-schedule-edit-date-picker / my-schedule-edit-time-picker — 날짜·시각 (시스템)

- Android: `chat/MyDeadlinesActivity.kt:321` `DatePickerDialog`, `:325` `TimePickerDialog`, kind system. 확인 → 값 반영 → `my-schedule-edit`(또는 `schedule-draft`).
- SwiftUI: 공용 날짜/시각 시트(0.2 표).

## my-schedule-delete-dialog — 지우기 확인

- Android: `chat/MyDeadlinesActivity.kt:507`, kind dialog
- 제목(`headline2Bold`): 시험 "이 시험을 지울까요?" / 반복 회차 "반복 마감을 지울까요?" / 그 외 "이 마감을 지울까요?"
- 본문(`body2Regular` 회색): 반복 "\"{제목}\"은(는) {반복 라벨} 반복돼요. {M/d(요)} 회차만 지울지, 반복 전체를 지울지 골라 주세요." / 그 외 "\"{제목}\"이(가) 목록과 알림에서 사라져요."
- 버튼: 반복 → **세로**(오른쪽 정렬, 간격 4): "이번 회차만 지우기" · "반복 전체 지우기" · "취소" / 그 외 가로: "취소" · "삭제". 지우면 토스트 "지웠어요".
- SwiftUI: 반복은 `.confirmationDialog`(액션 시트) 또는 버튼 3개 `.alert`(iOS는 3개 이상이면 세로로 놓아 원본 의도와 같음).

## my-schedule-submission-check — 제출 확인

- Android: `chat/SubmissionCheckDialog.kt:33`, kind dialog
- 제목(`headline2Bold`): 로딩 "제출 확인 중" / 실패 "제출을 확인하지 못했어요" / 결과: 문제·참고 없음 "제출에 문제 없어요", 참고만 "제출됐어요 · 참고할 점 있음", 제출 없음 "제출 기록이 없어요", 그 외 "확인이 필요해요".
- 본문(스크롤, 간격 6): 과제명(`body2Medium`) → 로딩: 스피너 + "eTL 제출 기록을 받는 중…" / 실패: 메시지("eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요", "eTL 요청 실패", "확인하지 못했어요({예외})") /
  결과: "제출 {KST 시각|없음} · 마감 {KST 시각}"(caption) · "제출한 파일: a, b"(caption) · 문제 "• …"(`body2Regular` 빨강) · 참고 "• …"(회색) · "eTL 제출 기록만 봤어요(파일 내용은 받지 않았어요)."(caption tertiary).
- 버튼: [URL 있으면] dismiss "eTL에서 열기"(→ `ext-etl`) · confirm "닫기".
- SwiftUI: 내용 가변 → 커스텀 카드 또는 `.sheet(.medium)`.

## schedule-draft — 공지 초안으로 내 일정에 등록

- Android: 같은 `DeadlineDialog`(`chat/MyDeadlinesActivity.kt:762` 초안 머리) — 알림의 "내 일정에 등록" 버튼, 알림 기록, 채팅 대기 공지(다른 그룹)에서 `DraftIntents.open` → `MyDeadlinesActivity` + EXTRA_DRAFT. 필드 원천 `notify/ScheduleDraft.kt`. kind dialog
- `my-schedule-edit`(새로 추가)와 같고 맨 위에 초안 머리가 붙는다:
  - 날짜를 못 찾음: "공지에서 날짜를 찾지 못했어요. 비서가 공지를 읽고 채워 드릴까요?"(`body2Medium` 주황) + 4dp.
  - prefill 있으면: TextButton "✨ 비서가 공지 읽고 채우기" + "공지 내용은 이걸 누를 때만 비서(내 ChatGPT 계정)로 가요."(`caption2Regular` 회색).
  - 브랜드색 caption: [날짜 찾음] "공지에서 찾은 날짜예요. 맞는지 확인해 주세요." + [마감 종류] "eTL 과제로 안 올라온 마감이면 등록해 두세요." → 8dp.
- 미리 채움: 종류(시험 공지 → 시험, 과제·동영상 → 마감), 제목(정리한 공지 제목; 동영상 "[동영상] {과목} {제목}"; 비면 "시험"/"과제"), 과목(짧은 이름), 메모("{과목} · 공지: {링크}" 또는 "공지: {링크}"), 준비물(시험), 날짜·시각(정확히 하나 찾았을 때만, 아니면 내일 23:59 / 시험 10:00), 끝 시각(시험), "그날 대면 수업은 휴강으로 표시"(동영상+대체 단서).
- 동작: 저장 → 내 마감 저장(+ 체크했으면 시간표 휴강 추가, 토스트 "시간표에 휴강도 넣었어요") → `my-schedule`; "✨ 비서가 공지 읽고 채우기" → 창 닫고 채팅 입력창에 요청을 채움(보내지 않음) → `chat`; 취소 → `my-schedule`.
- 보강·휴강 공지의 초안은 이 창이 아니라 `timetable-change-edit`(초안 모드)로 간다.

---

## timetable — 시간표

- Android 진입점: `chat/TimetableActivity.kt:75` (화면 `:177`), 격자 `chat/WeeklyTimetable.kt:76`, 배치 `chat/TimetableGridLogic.kt`
- 목적: 수강편람 주간 수업 + 직접 추가 수업 + 보강·휴강(오늘 이후).
- 들어오는 곳: 설정, 내 일정 상단 아이콘, 시간표/다음 수업 위젯, 알림 기록("반영됨 · 열기"), 보강·휴강 초안(→ `timetable-change-edit`; 이미 반영된 공지면 토스트 "이 공지는 이미 시간표에 반영했어요").
- 열 때 수강편람 캐시가 없거나 오래됐으면 뒤에서 새로 받음(토스트(길게): "eTL이 토큰을 거부했어요. 설정에서 토큰을 다시 저장하세요", "시간표를 가져오지 못했어요: …").

### 레이아웃
1. 상단바: ← · "시간표"(weight 1) · [받는 중 스피너] · `Add`("수업 직접 추가", 브랜드색) → `timetable-custom-course`.
2. 스크롤 본문(좌우 8dp), 8dp:
   - [숨긴 과목 > 0] 칩 "숨긴 과목 N개"(s16 b8) → `course-alerts`.
   - 데이터 없음: "가져오는 중…" 또는 "오른쪽 위 + 버튼으로 수업을 추가해 보세요. eTL을 연결하면 수강 중인 수업도 자동으로 가져와요."(`body2Regular` 회색, h16).
   - 블록 없음: "수업 시간이 없어요. 오른쪽 위 + 버튼으로 직접 추가할 수 있어요."
   - **주간 격자**(WeeklyTimetable):
     - 축 폭 32dp, 시간 높이 46dp×글꼴배율(1~1.6), 요일 열 폭 = max((폭−32)/요일수, 64dp×배율). 요일은 월~금 기본, 토·일 수업 있으면 늘어남. 시간 범위 min(9, 첫 수업 시) ~ max(18, 끝) (≤24).
     - 머리줄 44dp: 요일 글자 12sp Medium 회색(가운데). 가로 스크롤(머리줄·격자 함께).
     - 시간 축: "09" "10" … 10sp 회색, 오른쪽 정렬(축 폭−5dp).
     - 격자 선: 시 0.5dp `foregroundNeutralSecondary` α0.13, 반시 α0.06, 세로선 α0.13. 위아래 8dp 여백.
     - 수업 블록: x = 요일 열 + 레인 나눔 + 2dp, 폭 = 열/레인 − 4dp, y = 시작 비례 + 10dp, 높이 = 길이 비례 − 4dp, 모서리 6dp, 배경/글자 = 과목색 9쌍(courseId 해시):
       `#9ACBF0/#19364E` `#E5AFBC/#492A34` `#AFA9E4/#302C55` `#88CDBD/#183D35` `#ECC596/#49351E` `#E3CC8E/#473C1A` `#C6B1DD/#3E2D4D` `#B4CE9E/#2F4023` `#9DB3CF/#24364D`.
       패딩 h5 v5(60분 미만 v3). 과목 짧은 이름 11sp/줄높이 14sp Medium(줄 수: 60분 미만 또는 좁음 1, ≤90분 2, 그 외 4) → 강의실(미정 제외) 9sp/11sp α0.85(좁으면 2줄). 겹치는 수업은 레인으로 나눔.
     - 아래 caption "수업 블록을 누르면 자세한 정보를 볼 수 있어요"(s16 t10 b12).
   - [오류 있으면] "일부 정보를 확인하지 못했어요\n· …"(caption 회색, h16 v8).
   - 24dp → 섹션(h16): Row "보강 · 휴강"(`headline1Bold`, weight 1) + `Add`("보강·휴강 추가", 브랜드색) → `timetable-change-edit` → 8dp →
     없음 "예정된 보강·휴강이 없어요"(`body2Regular` 회색) / 행(모서리 16, `surfaceNeutralSecondary`, 아래 8dp, 패딩 s14 t6 b6 e4):
     "{M/d}({요}) {휴강|보강} · {과목}"(`body2Medium`) + "{HH:mm~HH:mm} · {강의실} · {메모}"(caption) + `DeleteOutline` 20dp("지우기"; 확인 없이 바로 지움).
   - 32dp.

### 동작
| 누르는 것 | 대상 |
|---|---|
| ← | 이전 화면 |
| 상단 + | `timetable-custom-course`(새로) |
| 수업 블록 | `timetable-course-sheet` |
| 숨긴 과목 N개 | `course-alerts` |
| 보강·휴강 + | `timetable-change-edit` |
| 보강·휴강 휴지통 | 바로 삭제(`timetable`) |

### SwiftUI 메모
- 격자: `ScrollView(.horizontal)` 하나 안에 머리줄과 격자를 함께 두고 시간 축은 밖에 고정(원본과 같은 구조) → `HStack { 축 ; ScrollView(.horizontal) { VStack { 머리줄 ; ZStack { Canvas(선) ; 블록들 .offset } } } }`. 블록은 `Button` + `.position/.offset`, 접근성 "{과목}, {요}요일, {시작}~{끝}, {강의실}".
- Dynamic Type 배율은 `@Environment(\.dynamicTypeSize)`를 1.0~1.6으로 근사.

## timetable-course-sheet — 수업 정보 (바텀 시트)

- Android: `chat/WeeklyTimetable.kt:159` `ModalBottomSheet`(skipPartiallyExpanded, 배경 `backgroundNeutralPrimary`, 위 모서리 24dp), kind sheet
- 내용(h24, 스크롤): Row: 9dp 원(과목색) · 7dp · "직접 추가한 수업"(courseId<0) / "수업 정보"(caption 회색) → 12dp → 과목명(`title2Bold`) → 24dp →
  상세 행 3개(v11; 아이콘 20dp 회색 · 12dp · 라벨 `body2Regular` 회색 폭 76dp · 값 `body2Medium`):
  `PersonOutline` "교수님" — 이름 / "등록된 교수 정보가 없어요"(직접 추가) / "교수 정보를 확인하지 못했어요";
  `LocationOn` "강의실" — 강의실들 줄바꿈("{동}동 {호}호" 변환; 없으면 "등록된 강의실 정보가 없어요");
  `Schedule` "수업 시간" — "{요}요일  HH:mm – HH:mm" 줄바꿈(요일·시작순).
  → 20dp → [직접 추가한 수업] LARGE OUTLINED 버튼 "수정 · 삭제"(가로 꽉 참) + 8dp → LARGE PRIMARY SOLID "확인"(가로 꽉 참) → 24dp.
- 동작: "수정 · 삭제" → `timetable-custom-course`(그 수업); "확인"/끌어내림 → `timetable`.
- SwiftUI: `.sheet(item:)` + `.presentationDetents([.large])`(또는 내용 높이 `.height`) + `.presentationCornerRadius(24)` + `.presentationDragIndicator(.visible)`.

## timetable-custom-course — 수업 직접 추가/수정

- Android: `chat/CustomCourseDialog.kt:34`, kind dialog
- 제목(`title3Bold`): "수업 직접 추가" / "직접 추가한 수업 수정".
- 본문(스크롤, 간격 12): "매주 반복되는 수업이나 일정을 추가해요."(caption 회색) · "과목명"(`body2Medium`) · 입력 "과목명 또는 일정 이름"(80자) ·
  "요일 · 여러 개 선택 가능"(`body2Medium`) · 칩 "월" "화" "수" "목" "금" "토" "일"(6/6, 기본 "월") ·
  FlowRow: OUTLINED 버튼 "시작 09:00" · "종료 10:15"(→ 시각 선택) · 입력 "강의실 (선택)" · 입력 "교수님 (선택)" · (오류 빨강 caption) · [수정일 때] TextButton "이 수업 삭제".
- 버튼: "취소" · "저장"(검증 `CustomTimetableLogic.validationError`).
- 동작: 저장 → `timetable`; "이 수업 삭제" → `timetable-custom-course-delete-dialog`; 시각 → `timetable-time-picker`.

## timetable-custom-course-delete-dialog — 수업 삭제 확인

- Android: `chat/CustomCourseDialog.kt:94`, kind dialog
- 제목 "수업을 삭제할까요?", 본문 = 과목명(회색). 버튼 "취소"(→ `timetable-custom-course`) · "삭제"(→ 삭제 후 `timetable`).

## timetable-change-edit — 보강·휴강 추가

- Android: `chat/TimetableActivity.kt:286` (`ChangeDialog`), 초안 미리 채움 `chat/TimetableDraftLogic.kt:23`, kind dialog
- 제목 "보강·휴강 추가"(`title3Bold`). (고치기는 없음: 지우고 다시 추가)
- 본문(스크롤):
  - [초안] 날짜 못 찾음 → "공지에서 날짜를 찾지 못했어요. 비서가 공지를 읽고 채워 드릴까요?"(`body2Medium` 주황) / 찾음 → "공지에서 찾은 날짜예요. 맞는지 확인해 주세요."(caption 브랜드색);
    prefill 있으면 TextButton "✨ 비서가 공지 읽고 채우기" + "공지 내용은 이걸 누를 때만 비서(내 ChatGPT 계정)로 가요."(`caption2Regular`); 8dp.
  - 칩 Row "휴강"(기본) "보강" → 12dp → "과목"(`body2Medium`) → 6dp → [시간표 과목 있으면] 과목 칩 FlowRow(누르면 선택, 보강이고 시작 비었으면 그날 그 과목 시간으로 채움) + 6dp →
    입력 placeholder "목록에 없으면 직접 입력"(과목 없으면 "과목명", 60자) → 12dp →
    Row: OUTLINED "M/d(요)"(날짜, 기본 오늘) · [보강] "HH:mm"/"시작" · "~" · "HH:mm"/"끝"(기본 시작+75분 또는 10:15) →
    [휴강] caption "그날 그 과목의 수업이 모두 빠져요." / [보강] 12dp + 입력 "강의실 (선택)"(40자) → 12dp → 입력 "메모 (선택)"(100자) → 오류(빨강 caption, `TimetableLogic.validateChange`).
- 버튼: "취소" · "저장".
- 초안 미리 채움: 종류(보강 공지 → 보강, 휴강 → 휴강), 과목(이름이 맞는 시간표 과목 또는 글), 날짜, 보강 시각(하나만 찾았을 때), 메모("공지: {링크}").
- 동작: 저장 → `timetable`; "✨ …" → `chat`(입력창 채움); 날짜 → `timetable-date-picker`; 시각 → `timetable-time-picker`.

## timetable-date-picker / timetable-time-picker (시스템)

- Android: `chat/TimetableActivity.kt:117`·`:120` `DatePickerDialog`/`TimePickerDialog`, kind system. 확인 → 호출한 창으로 복귀.

---

## calendar-settings — 캘린더 연동

- Android 진입점: `calendar/CalendarExportActivity.kt:80` (화면 `CalendarLinkScreen` `:417`)
- 목적: 영민이 → 캘린더(과제·내 마감·시험 자동 넣기, 캘린더에서 고친 것 가져오기), 캘린더 → 영민이(고른 캘린더 일정 보기, 비서가 캘린더 읽고 쓰기), 비서 변경 되돌리기, 고급(.ics).
- 레이아웃: 상단바 ← · "캘린더 연동"(weight 1) · [작업 중 스피너]. 본문 스크롤 h24, 끝 40dp. 소제목 = `headline2Bold`(t16 b8). 카드 = 모서리 16, `surfaceNeutralSecondary`, 패딩 16. Hint = caption 회색. SwitchLine = 설정의 SwitchRow와 같음.
  1. 소제목 "영민이 → 캘린더"
  2. 카드:
     - SwitchLine "영민이 일정을 캘린더에 자동으로 넣기" / "과제·내 마감이 바뀌면 캘린더도 따라 바뀌어요"(작업 중 비활성).
     - [note] 6dp + caption 주황 note. [켰는데 권한 없음] 6dp + caption 빨강 "캘린더 권한이 꺼져 있어요".
     - 12dp → "넣을 곳"(`body2Medium`) → 8dp → TargetOption(v4: 칩 + 2dp + Hint): 칩 "이 폰에만" / "삼성 캘린더에서 보여요(구글 캘린더 앱·웹에는 안 보일 수 있어요)"; 칩 "구글 계정 캘린더" / "삼성 캘린더와 구글 캘린더(앱·웹) 모두에서 보여요".
     - 4dp → Hint "두 곳에 함께 넣지는 않아요(삼성 캘린더에 같은 일정이 두 번 보이지 않게). 삼성 캘린더에서 안 보이면 삼성 캘린더 → 캘린더 관리에서 켜 주세요."
     - 이 폰: 4dp + Hint "이 폰에 \"영민이\" 캘린더를 만들어 넣어요. 서버로 올라가지 않아요."
       구글: 4dp + Hint "고른 구글 캘린더는 구글 계정과 동기화돼요(일정 제목과 과목 이름이 구글 서버로 가요). 구글 캘린더(웹)에서 \"영민이\" 같은 새 캘린더를 먼저 만들어 고르면 다른 일정과 섞이지 않아요." → 8dp → Row: "아직 고르지 않았어요" 또는 "\"{이름}\""(`body2Medium`, weight 1) + SoftButton "캘린더 고르기" → `calendar-settings-google-picker`.
  3. 12dp → 카드: "넣을 일정"(`body2Medium`) · SwitchLine "eTL 과제 마감" / "알림 확인 때 받아 둔 과제(숨긴 과목 제외)" · "내 마감·시험" / "시험은 시작~끝 시간과 장소로 들어가요" · "제출·완료한 항목도 넣기" / "끄면 제출하거나 완료한 항목은 캘린더에서 지워요" · "캘린더에서 고친 것 가져오기" / "캘린더 앱에서 내 마감 일정을 옮기거나 이름을 바꾸면 앱에도 반영해요. eTL 과제 일정은 원래대로 돌아가요" → 8dp → [마지막 결과] caption + 8dp → SoftButton "지금 맞추기"(켜져 있고 작업 중 아닐 때) → 12dp → Hint "끄면 \"영민이\" 캘린더를 지우거나(이 폰에만) 앱이 넣은 일정만 지워요(구글). 앱을 그냥 지우면 넣은 일정을 정리할 수 없으니, 지우기 전에 먼저 꺼 주세요."
  4. 소제목 "캘린더 → 영민이"
  5. 카드: SwitchLine "내 캘린더 일정 보기" / "고른 캘린더의 일정을 내 일정(\"캘린더\")과 아침 브리핑(오늘 일정)에 보여 줘요" · SwitchLine "비서가 캘린더 보고 등록하기" / "대화에서 부탁하면 비서가 고른 캘린더의 일정을 읽고, 일정을 넣거나 고치거나 지워요" · Hint "비서가 일정을 읽으면 그 내용(제목·시간·장소·메모)이 내 ChatGPT 계정으로 OpenAI에 전달돼요. 비서는 내가 부탁할 때만 쓰고, 바꿀 때마다 채팅에 한 줄로 알려요." · [켰는데 읽기 권한 없음] 빨강 caption "캘린더 권한이 꺼져 있어요" · 10dp → Row: 선택 라벨("고른 캘린더가 없어요" / "캘린더 N개(a, b, c)" / "캘린더 N개(a, b 외 N개)") + SoftButton "볼 캘린더 고르기" → `calendar-settings-read-picker` ·
     [비서 켬] 10dp + "비서가 새 일정을 넣을 캘린더"(`body2Medium`) + 6dp + [쓸 수 있는 것 없음] Hint "고른 캘린더 중 일정을 넣을 수 있는 캘린더가 없어요(읽기 전용만 골랐어요)." + 칩 FlowRow(쓸 수 있는 캘린더 이름, 하나 활성).
  6. [비서 변경 있음] 12dp → 카드: "비서가 바꾼 일정"(`body2Medium`) · Hint "최근 14일. 되돌리면 넣은 일정은 지우고, 고친 일정은 전 모양으로, 지운 일정은 다시 넣어요(알림·초대는 되살리지 않아요)." · 최대 10행(v6): "{넣음|고침|지움} · {요약}"(`body2Regular`; 되돌림이면 tertiary) + "{캘린더} · {MM/dd(요) HH:mm}{ · 되돌림}"(caption) + [안 되돌림] TextButton "되돌리기".
  7. 16dp → 접기 Row(모서리 12, v10): "고급"(`headline2Bold`, weight 1) + `ExpandMore`/`ExpandLess`(기본 접힘).
  8. [펼침] 카드: "일정 파일(.ics)로 공유·저장"(`body1Medium`) → 4dp → Hint "위에서 고른 일정을 .ics 파일 하나로 만들어요. 네이버 캘린더(웹의 일정 가져오기)나 삼성 캘린더 가져오기에 쓸 수 있어요. 만든 때의 내용이라 나중에 바뀐 것은 반영되지 않아요." → 10dp → SoftButton "공유하기" · "파일로 저장".
     12dp → 카드: "자세한 동작"(`body1Medium`) → 4dp → Hint 6개:
     "• 마감은 마감 시각에 0분짜리 일정, 시험은 시작~끝 일정으로 들어가요. 영민이가 넣는 일정에는 캘린더 알림을 달지 않아요." /
     "• 반복 마감은 앞으로 60일의 회차를 하나씩 넣어요. 캘린더에서 반복 회차를 옮겨도 반복 전체는 바뀌지 않고 원래대로 돌아가요(반복 마감은 앱에서 고쳐 주세요)." /
     "• 캘린더에서 지운 일정은 다시 넣지 않아요(내 마감은 지워지지 않아요). 마감이 14일 넘게 지난 일정은 그대로 둬요." /
     "• 캘린더가 바뀌면 몇 초~1분 안에 맞춰요. 같은 일정을 몇 번 되돌려도 캘린더가 다시 바꾸면 그 모양을 그대로 둬요." /
     "• 비서는 반복 일정과 다른 사람이 초대된 일정은 고치거나 지우지 않아요. 고른 캘린더 밖의 일정은 읽지 않아요." /
     "• 모두 폰 안에서 처리하고, 앱이 직접 인터넷으로 보내는 것은 없어요(구글 캘린더는 구글 계정 동기화가 올려요)."
- 토스트: "껐어요. 넣은 일정을 지웠어요", "켰어요", "옮겼어요", "\"{이름}\" 캘린더에 넣어요", "꺼져 있어요", "저장했어요", "보낼 앱이 없어요", "캘린더 권한이 필요해요", "하지 못했어요({예외})", "설정 → 권한에서 캘린더를 허용해 주세요", "캘린더 권한이 없어 켤 수 없어요", "설정을 열 수 없어요".
- 동작: 스위치·칩 → (권한 필요하면 `calendar-settings-permission`) → 저장/맞추기; 구글 대상인데 캘린더 미선택이면 `calendar-settings-google-picker`; 보기/비서 켜는데 고른 캘린더 없으면 `calendar-settings-read-picker`; "공유하기" → `ext-share-sheet`(제목 "일정 파일 보내기"); "파일로 저장" → `ext-document-picker`; 되돌리기 → 실행 후 토스트; ← → `settings`.
- **CalendarOpenActivity** (`calendar/CalendarExportActivity.kt:394`, 화면 없음): 캘린더 앱에서 영민이가 넣은 일정의 "앱에서 열기" → `my-schedule`(내 마감 ID가 있으면 `my-schedule-edit`).
- SwiftUI 메모: **1단계 범위 밖**(PORTING). 옮길 때 EventKit: "이 폰에만"/"구글 계정 캘린더" 구분은 iOS에서 `EKSource`(iCloud/Google/로컬) 선택으로 바뀜, 삼성 캘린더 문구는 바꿔야 함. 권한 `EKEventStore.requestFullAccessToEvents()`(iOS 17). "앱에서 열기"(CUSTOM_APP_URI)는 iOS에 없음 → 일정 URL 필드에 `youngmin://…` 딥링크로 대체. 공유 `ShareLink`, 저장 `.fileExporter`.

## calendar-settings-google-picker — 구글 캘린더 고르기

- Android: `calendar/CalendarExportActivity.kt:583`, kind dialog
- 제목 "구글 캘린더 고르기"(`headline2Bold`). 본문: 없으면 NoCalendarsHint(아래) / 행(v10): 이름(`body1Medium`) + 계정(caption). 버튼 "닫기".
- NoCalendarsHint(`:617`): "쓸 수 있는 캘린더가 없어요."(`body2Medium`) → 4dp → "폰 설정 → 계정 및 백업 → 계정 관리 → Google → 계정 동기화에서 '캘린더'를 켜 주세요. 켠 뒤 잠시 기다렸다가 다시 확인해 주세요."(`body2Regular` 회색) → 10dp → SoftButton "계정 설정 열기"(→ `ext-system-settings`) · "다시 확인" → 8dp.
- 동작: 행 → 선택(켜려던 중이면 켬) → `calendar-settings`; 닫기 → `calendar-settings`.

## calendar-settings-read-picker — 볼 캘린더 고르기

- Android: `calendar/CalendarExportActivity.kt:338`, kind dialog
- 제목 "볼 캘린더 고르기". 본문: 없으면 NoCalendarsHint / 있으면 Hint "영민이가 넣은 \"영민이\" 캘린더는 목록에 없어요(내 일정에 이미 보여요)." + 행(v8): 12dp 색 원 · 10dp · 이름(`body1Medium`) + "{계정}{ · 읽기 전용}"(caption) · 체크박스.
- 버튼 Row: "취소" · [목록 있음] "저장"(하나도 안 고르면 보기·비서 둘 다 끔).
- 동작: 저장/취소 → `calendar-settings`; 계정 설정 열기 → `ext-system-settings`.

## calendar-settings-permission — 캘린더 권한 요청 (시스템)

- Android: `calendar/CalendarExportActivity.kt:99` (READ/WRITE_CALENDAR), kind system. 허용 → 이어서 하려던 일; 다시 묻지 않음 → 토스트 + 앱 정보 화면(`ext-system-settings`); 거부 → 토스트 "캘린더 권한이 없어 켤 수 없어요".

---

## notification — 시스템 알림

- Android: `notify/NotificationPoster.kt:107` (`post`), 채널 `notify/NotifyModels.kt:61`, 탭 라우팅 `NotificationPoster.kt:26`. kind notification
- 공통: 작은 아이콘 `ic_stat_etl`, 자동 닫힘, 제목 최대 80자, 본문 최대 140자(말줄임 "…"), 펼친 본문(BigText) 있을 수 있음. 게시 후 알림 기록(`notify-history`)에 저장.
  잠금화면 숨기기 켬(기본): 잠금화면에는 제목 "영민이 알림" / 본문 "새 알림이 있어요".
- **탭 라우팅**(`NoticeRouting.tap`): ① prefill 있고 prefillAction 없음 → `chat`(입력창 채움) ② 안전한 eTL URL(`https://myetl.snu.ac.kr/…`) → `ext-etl`(LearningX, 없으면 웹) ③ agenda(내 마감·시험) → `my-schedule`(뒤 스택에 채팅; mineItemId 있으면 `my-schedule-edit`) ④ 그 외 → `chat`.
- **버튼**(순서, 최대 3): 초안 있으면 "내 일정에 등록"(→ `schedule-draft`) 또는 "시간표에 반영"(→ `timetable-change-edit`) · prefillAction 있으면 그 이름(→ `chat`, 입력창 채움) · URL 있으면 "eTL에서 열기"(→ `ext-etl`).

| 채널(이름, 중요도) | 종류 | 제목 | 본문 / 펼친 본문 | 탭 / 버튼 |
|---|---|---|---|---|
| `deadline` 마감 알림 (HIGH, 팝업, REMINDER) | 마감 임박 1건 | `마감 임박 · {과목}` | `{과제} · {MM/dd(요) HH:mm} ({N일 N시간 남음})[ (오프라인)]` / `• {날짜} · {과목} — {과제} ({남음})` | eTL 과제(내 마감이면 내 일정) · eTL에서 열기 |
| 〃 | 마감 임박 묶음 | `마감 임박 {n}건 · {3시간} 이내` | `{과목} — {과제} ({남음}) 외 {n-1}건` / 줄 최대 6 + "외 N건" | 과목 1개면 강의, 여러 개면 eTL 홈, 내 마감만이면 내 일정 |
| 〃 | 내 마감 직접 알림 | `알림 · {제목}` | `마감 {MM/dd(요) HH:mm} ({남음})[ · {메모}]` | 내 일정(그 항목) |
| 〃 | 시험 알림 | `시험 · {제목}[ · {과목}]` | `{MM/dd(요) HH:mm~HH:mm} ({남음}) · {장소} · 준비물: {…} · {응원}` / `{정보}\n{응원}` | 내 일정(그 항목) |
| 〃 | 시험 끝 | `수고했어요 · {제목}`(반말 `수고했어 · `) | `{응원}` | 내 일정 |
| 〃 | 토큰 거부 | `eTL 토큰을 확인해 주세요` | `토큰이 만료됐거나 올바르지 않아요. 설정에서 다시 저장해 주세요.` | eTL 프로필 설정 · eTL에서 열기 |
| `announcement_v2` 공지 알림 (HIGH) | 공지 1~3건 각각 | `{과목} · {새 공지|시험 공지|과제 공지|동영상 강의|보강 공지|휴강 공지}` | `{공지 제목}` / `준비물·지참: …\n\n{본문 300자}` | 공지 URL · [초안] 내 일정에 등록/시간표에 반영 · eTL에서 열기 |
| 〃 | 공지 4건 이상 | `새 공지 {n}건` | `[과목] 제목 외 {n-1}건` / `• ({배지}) [과목] 제목` 최대 8줄 | 강의/eTL 홈 · [첫 초안] · eTL에서 열기 |
| `brief` 정기 브리핑 (DEFAULT, 팝업 없음) | 브리핑 | `eTL 브리핑 · {MM/dd(요)}` | `[시험 N개(오늘·내일) · 수업 N개 · 일정 N개 · ]마감 임박 N건 · 누락 N건 · 새 공지 N건` 또는 `급한 마감, 누락, 새 공지가 없어요` / 섹션 `■ 시험 (7일 이내)`, `■ 오늘 수업`, `■ 오늘 일정`, `■ 마감 예정 (7일 이내, 미제출)`, `■ 누락 (마감 지남, 미제출)`, `■ 새 공지 (최근 24시간)`, `※ 일부 항목을 조회하지 못했어요` | 채팅 |
| `change_v2` 새 과제·마감 변경·성적 (HIGH) | 1~3건 각각 | `{새 과제|마감 변경|성적} · {과목}` | `{과제} · {마감}` / `{과제} · {옛 마감} → {새 마감}` / `{과제} · {점수}` | 과제 · eTL에서 열기 |
| 〃 | 4건 이상 | `eTL 변경 {n}건` | `마감 변경 N · 새 과제 N · 성적 N` / `• [종류] {과목} — {줄}` 최대 8 | 강의/eTL 홈 · eTL에서 열기 |
| `submit_check` 제출 확인 (HIGH) | 제출 문제 | `제출 확인 필요 · {과목}` | `{과제} · {문제}[ 외 N건]` / `{과제}\n• 문제…\n• 늦은 제출로 표시됨` | 과제 · eTL에서 열기 |
| `submit_ok` 정상 제출 확인 (LOW, 조용히) | 정상 제출 | `제출 확인됐어요` | `{과목} {과제} · 파일 N개 · {MM/dd(요) HH:mm}[ · 늦은 제출]` | 과제 · eTL에서 열기 |
| `class` 수업 알림 (HIGH) | 수업 N분 전 | `{N}분 뒤 수업 · {과목}[ [보강]]` | `{HH:mm}~{HH:mm}[ · {강의실}][ · {메모}]` | 강의 페이지(없으면 채팅) · eTL에서 열기 |
| `new_files` 새 강의자료 (DEFAULT) | 과목별 | `새 강의자료 {n}개 · {과목}` | `{파일1}, {파일2}…` / `• 파일` 줄 | 본문: 과목 파일 화면(eTL) · 버튼 "자료 받아줘"(채팅 입력창 `{과목} 새 강의자료 {…} 받아줘`) · eTL에서 열기 |
| `update_v2` 앱 업데이트 (HIGH; `update/UpdateChecker.kt:206`) | 새 버전 | `새 버전 {v}{이/가} 나왔어요` | `눌러서 받아요 · {크기}` | APK 링크(`ext-browser`) — iOS 없음 |

- SwiftUI/iOS 메모:
  - 채널 없음 → `UNNotificationContent.threadIdentifier` = 채널 id(묶음), `interruptionLevel`: HIGH → `.active`(팝업 배너 기본; `.timeSensitive`는 entitlement 필요), DEFAULT(브리핑·새 강의자료) → `.passive`에 가깝게 하려면 `.passive`(소리 없음) 또는 `.active` 무음, LOW(정상 제출) → `.passive` + sound nil. 사용자가 유형별로 끌 수 있는 시스템 UI는 없음 → 앱 설정 스위치로만.
  - 버튼: `UNNotificationCategory` 종류별(예 `ANN_DRAFT`: "내 일정에 등록"/"eTL에서 열기", `NEW_FILES`: "자료 받아줘"/"eTL에서 열기", `ETL`: "eTL에서 열기"). 버튼은 알림을 길게 눌러야 보임(안드로이드는 펼치면 바로 보임) — 다르게 됨.
  - 펼친 본문(BigText) → iOS는 `body`가 길면 길게 누르면 펼쳐짐; 제목/본문/bigText 중 bigText를 `body`로 쓰고 짧은 `text`는 `subtitle`로 쓰는 등 결정 필요(확인 필요).
  - 탭 라우팅은 `userInfo`(url, prefill, agenda, mineItemId, draft)를 넣고 `UNUserNotificationCenterDelegate.didReceive`에서 같은 우선순위로 분기.
  - 잠금화면 숨기기: 위 settings 메모 참조. 업데이트 알림 없음.

---

## widget-deadlines — 일정 위젯 2×2

- Android: provider `widget/HomeWidgets.kt:256` `DeadlineWidgetProvider`, 렌더 `:148` `deadlines(compact=false)`, 레이아웃 `res/layout/widget_deadlines.xml` + 행 `widget_deadline_row.xml`, 정보 `res/xml/deadline_widget.xml`(min 110×110dp, 2×2 셀, 가로·세로 크기 조절), 미리보기 `widget_deadlines_preview.xml`. 위젯 목록 이름 "영민이 · 일정 2×2", 설명 "가까운 과제와 내 마감을 보여줘요. 크기를 늘리면 더 많이 볼 수 있어요."
- 색(`res/values(-night)/widget_colors.xml`): 배경 `#F7F8FC`/`#202430`, 글자 `#1D2535`/`#F0F2F8`, 보조 `#5B6578`/`#B3BDCE`, 강조 `#465DC8`/`#AFBCFF`, 구분선 `#DCE1EC`/`#414859`. 배경 모서리 **20dp**.
- 레이아웃: 패딩 s16 e12 t8 b6. 머리 "일정"(14sp bold 강조색, 최소 높이 22dp) → 행 목록(사이 1dp 구분선) → 빈 문구(남은 공간 가운데, 11sp 보조색): 캐시 없음 "저장된 과제 정보가 없어요\n눌러서 내 일정 확인" / "남은 마감이 없어요"(처음 레이아웃 문구 "마감 정보를 불러오는 중이에요").
  행(v1 패딩): 본문(weight 1, e2): 제목 12sp bold 1줄 → 마감 "오늘 23:59"/"내일 18:00"/"11/26 23:59" 10sp 보조색 1줄 → 남은 시간 "3시간 12분 남음"(+ 폭 ≥240dp면 " · {과목|내 마감|시험 · 장소}") 10sp 강조색 1줄 · 오른쪽 완료 버튼 32×32 "○" 24sp 강조색(내 마감이고 시험 아닐 때만).
  글자 크기는 높이에 맞춰 계산: 행 수 = (높이 − (14+22×배율) + 1) / (48×배율 + 1), 제목 sp = clamp(행 공간 / (4.2×배율), 8, 15), 보조 = 제목×0.84.
- 내용: 미제출·마감 전 eTL 과제(숨긴 과목 제외) + 내 마감(반복은 다음 회차 하나) + 시험("[시험] {과목} {제목}", 부 "시험 · 장소"), 마감순, 개수 상한 없이 들어가는 만큼.
- 남은 시간 문구: "곧 마감"(1분 미만) / "N분 남음" / "N시간 M분 남음" / "N일 N시간 남음".
- 동작: 머리·빈 곳·빈 문구 → `my-schedule`; 내 마감/시험 행 → `my-schedule-edit`(그 항목); 과제 행 → `ext-etl`; "○" → 그 회차 완료(앱 안 열림, 다시 그림) → `widget-deadlines`.
- 갱신: 15분마다 다시 그림(네트워크 없음), 1시간마다 동기화, 마감·KST 자정 경계에 다시 그림, 데이터 저장 때마다.
- iOS 메모: WidgetKit `systemSmall`(2×2에 해당) · `systemMedium` · `systemLarge`(2026-11-27). 세 크기 모두 안드로이드처럼 실제 높이·글자 배율로 행 수를 계산해(`DeadlineWidgetLogic.capacity`) 들어가는 만큼 보인다. "○" 완료 → iOS 17 interactive widget `Button(intent: CompleteDeadlineIntent)`. 타임라인: 마감 경계·자정마다 entry, `.after(15분)`. 다크 색은 같은 hex를 Asset 색으로. 모서리 20dp는 iOS가 강제(컨테이너 배경 `containerBackground`)라 다름.

## widget-deadlines-compact — 일정 위젯 2×1

- Android: `CompactDeadlineWidgetProvider`(`widget/HomeWidgets.kt:274`), `deadlines(compact=true)`, 레이아웃 `widget_deadlines_compact.xml` + `widget_deadline_compact_row.xml`, 정보 `deadline_compact_widget.xml`(min 110×40dp, 2×1), 미리보기 `widget_deadlines_compact_preview.xml`("과제 제출" / "오늘 23:59 · 3시간 남음"). 목록 이름 "영민이 · 일정 2×1".
- 차이: 머리 "일정" 숨김(gone), 패딩 t6 b6. 행: 제목 12sp bold + "{마감} · {남은 시간}" 10sp 보조 1줄(남은 줄은 숨김), 완료 버튼 28×26 "○" 22sp. 행 높이 32×배율, 제목 sp = 행 공간/(2.7×배율).
- 동작: 2×2와 같음.
- iOS 메모: 2×1 홈 화면 위젯 패밀리가 없다 → 잠금화면 `accessoryRectangular`(가장 가까운 첫 항목 하나) 또는 `systemSmall`로 대체. 완료 버튼은 accessory에서 불가.

## widget-timetable — 시간표 위젯 4×4

- Android: `TimetableWidgetProvider`(`widget/HomeWidgets.kt:275`), 렌더 `:197` + 비트맵 `widget/TimetableWidgetRenderer.kt:25`, 레이아웃 `widget_timetable.xml`, 정보 `timetable_widget.xml`(min 250×250dp, 4×4). 목록 이름 "영민이 · 시간표 4×4", 설명 "이번 주 수업과 등록한 휴강·보강을 한눈에 볼 수 있어요."
- 레이아웃: 패딩 8dp, 배경 같은 위젯 배경(20dp). 머리 "시간표"(15sp bold 강조색, 가운데, 높이 26dp, 자간 0.04) → 격자 이미지(남은 높이, fitCenter) / 빈 문구 "등록된 수업이 없어요\n눌러서 시간표 열기"(11sp 보조색, v8).
- 격자 비트맵(이번 주 월~): 축 23dp×배율(1~1.5), 머리 18×배율; 요일 글자 11sp(오늘은 강조색 bold), 시간 "09" 9sp 오른쪽 정렬, 선 0.5 구분선색;
  블록: 모서리 3, 색 8가지 `#9ACBF0 #E5AFBC #AFA9E4 #88CDBD #ECC596 #E3CC8E #C6B1DD #B4CE9E`(휴강은 구분선색), 글자 11sp bold `rgb(29,42,62)`(휴강은 보조색), 가운데 정렬, 최대 6줄, 내용 "휴강 · {과목}" / "보강 · {과목}" / "{과목}" + (3줄 이상 들어가면) "\n{강의실}". 지금 시각: 오늘 열에 1.3 강조색 가로선 + 2.2 반지름 점.
- 동작: 어디든 → `timetable`.
- iOS 메모: `systemLarge`. 비트맵 대신 위젯 SwiftUI 뷰에서 `Canvas`/`GeometryReader`로 같은 격자를 그림. 지금 시각 선은 타임라인 entry(수업 경계·15분)로 근사.
- iOS 중간 크기(`systemMedium`, 2026-11-27, 안드로이드에 없음): 격자 대신 **오늘 수업 목록**(`TodayClassesWidgetLogic`). 머리 "오늘 수업"(14 bold 강조색) + 오른쪽 "11/27(금)"(11 보조색).
  줄: 과목색 막대 4×16 · "09:30~10:45"(12, 숫자 고정폭, 진행 중이면 강조색 bold) · 과목(13 bold, 보강은 "보강 · 과목") · 오른쪽 강의실(11 보조색). 줄 높이 24×배율, 진행 중은 강조색 12% 배경, 끝난 수업은 흐리게.
  휴강으로 빠진 수업은 없고 보강은 넣는다. 시간표에서 숨긴 과목 제외. 줄이 모자라면 끝난 수업부터 빼고 "+N개 더".
  오늘 수업이 없으면 "오늘은 수업이 없어요" + "다음 수업 · {내일|{요}요일} HH:mm" + 과목·강의실(이번 주 일요일까지), 없으면 "이번 주 남은 수업이 없어요". 다 끝났으면 흐린 목록 아래에 다음 수업 한 줄(없으면 "이번 주 남은 수업이 없어요").

## widget-timetable-tall — 시간표 위젯 4×6

- Android: `TallTimetableWidgetProvider`(`widget/HomeWidgets.kt:276`), 같은 렌더·레이아웃, 정보 `timetable_tall_widget.xml`(min 250×390dp, 최소 조절 250×250, 4×6). 목록 이름 "영민이 · 시간표 4×6". 기본 높이 390dp로 그림.
- 동작: → `timetable`.
- iOS 메모: iPhone에 4×6 패밀리 없음 → 아이폰은 `systemLarge` 하나. iPad는 `systemExtraLarge`(2026-11-27)로 같은 격자를 글자·축 1.3배, 머리 18pt, 여백 18로 그린다. 아이폰 위젯 갤러리에는 아주 크게가 나오지 않는 것이 정상이다.

## widget-next-class — 다음 수업 위젯 2×1

- Android: `NextClassWidgetProvider`(`widget/HomeWidgets.kt:277`), 렌더 `:216`, 레이아웃 `widget_next_class.xml`, 정보 `next_class_widget.xml`(min 110×40dp, 2×1). 목록 이름 "영민이 · 다음 수업 2×1", 설명 "다음 수업의 과목·시각·강의실을 보여줘요. 시작 30분 뒤 다음 수업으로 넘어가요."
- 레이아웃: 가운데 정렬, 패딩 h16 v4. 제목(과목) 14sp bold 글자색 1줄 → 2dp → "{오늘|내일|{요}요일} {HH:mm}[ · {강의실}]" 11sp 강조색 1줄. 크기는 높이에 맞춰 제목 sp = clamp((높이−10)/(2.5×배율), 8, 15).
  없을 때: "다음 수업이 없어요" / "눌러서 시간표 열기".
- 규칙: 앞으로 8일 중 가장 이른 수업(휴강 제외, 시간표에서 숨긴 과목 제외), 시작 30분 뒤(짧으면 끝) 다음 수업으로 넘어감.
- 동작: → `timetable`.
- iOS 메모: 홈 2×1 없음 → `accessoryRectangular`(잠금화면) + `systemSmall`. 타임라인 entry는 `switchesAt`마다.

---

## 외부 대상 (이 그룹에서 쓰는 것)
- `ext-browser`: ChatGPT 로그인, eTL 토큰 발급 페이지, 오픈채팅, 업데이트 APK.
- `ext-etl`: `LearningXOpenActivity`(LearningX 앱, 없으면 같은 웹 원문) — 과제/공지/강의/파일.
- `ext-calendar-app`: 캘린더 일정 열기, 일정 추가 화면, "앱에서 열기"로 되돌아옴.
- `ext-system-settings`: 앱 알림 설정, 정확한 알람, 배터리 최적화, 계정 동기화, 앱 정보(권한).
- `ext-share-sheet`: .ics 공유. `ext-document-picker`: .ics 파일로 저장.
- `ext-widget-picker`: 런처의 위젯 고정 확인(요청 결과로 위젯 추가).

## 확인하지 못한 것
- Montage v4.0.3의 `surface*`/`foregroundBrandPrimary`/`lineNeutralPrimary` 정확한 hex와 WantedButton·WantedChip·WantedTextField의 정확한 높이·모서리(라이브러리 소스 없음; 위 값은 근사).
- `ModelPickerContent` 상세(chat 그룹), `PrivacyPolicyActivity`·`OnboardingActivity`(다른 그룹).
- 런처별 위젯 실제 셀 크기·폰트(기기마다 다름).
