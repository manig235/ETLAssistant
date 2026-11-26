# 설정 화면 통합 안내 (`Youngmin/UI/Settings`)

파일: `SettingsView.swift`(화면·섹션) · `SettingsModel.swift`(상태·동작, `SettingsHooks`) · `SettingsLogic.swift`(Kotlin `SettingsLogic` 이식) ·
`SettingsComponents.swift`(설정 전용 행·배너·버튼·대화상자 카드) · `SettingsWindowAddDialog.swift`(알림 시점 추가).

## 1. AppRoute 연결 (App/AppRoute.swift — 통합 담당이 고친다)

```swift
case .settings: SettingsView(courseAlerts: { AnyView(CourseAlertsView()) })   // SettingsPlaceholder 대신
```
- `SettingsPlaceholder`는 지워도 된다.
- 과목별 알림: `AppRoute`에 경로가 없어 `courseAlerts` 클로저로 받는다(설정 안에서 `navigationDestination(isPresented:)`로 push).
  나중에 `case courseAlerts`를 만들면 `SettingsView.swift`의 "과목별 알림" 행을 `NavigationLink(value: AppRoute.courseAlerts)`로 바꾸면 된다.
- 이미 있는 경로를 그대로 쓴다(`NavigationLink(value:)`, 뿌리 `navigationDestination(for: AppRoute.self)` 필요):
  `.notifyHistory`(알림 기록), `.timetable`(시간표 · 보강/휴강), `.mySchedule`(내 일정), `.privacyPolicy`(개인정보 처리방침 → `PrivacyPolicyView`).
- "처음 안내 다시 보기" → `AppState.showOnboarding()`.

## 2. AppState / 다른 모듈에서 필요한 것

- 쓰는 것: `app.auth`(AssistantAuth: account, loginInProgress, loginMessage, startChatGPTLogin, reopenBrowser, cancelLogin, signOut, apiKey, settings),
  `app.chat`(있으면 모델 라벨·`ChatModelPicker`·`refreshEnvironment()`·`loadModels()`), `app.refreshTokenState()`, `app.showOnboarding()`. 새 API는 필요 없다.
- `SettingsHooks`(앱 시작 때 채우기, 비어 있으면 아무 일도 안 함):
  - `notifySettingsChanged` — 알림 설정 저장 직후(Android `NotifyScheduler.apply`: TimedNotifications/브리핑 예약 다시 맞추기).
  - `etlTokenChanged(connected)` — 토큰 저장·해제 직후(조회·예약 다시, 해제면 eTL 알림 예약 지우기 — `EtlDisconnect`는 캐시만 지운다).
  - `checkNow` / `briefNow` — 채우면 알림 카드에 "지금 확인"·"브리핑 미리 보내기" 버튼이 보인다(NotifyKit `runCheck`/`runBrief`).
- 로그인 성공 토스트는 `ChatController`가 `auth.onLoginDone`으로 띄운다(설정은 실패 토스트만; 채팅 상태가 없을 때만 성공 토스트).
- "잠금화면에서 알림 내용 숨기기"(`hideContentOnLockScreen`)는 저장만 한다. 알림 게시 쪽(LocalNotifications/NotificationPoster)이 읽어
  `UNNotificationCategory(hiddenPreviewsBodyPlaceholder:)` 또는 내용 바꾸기로 반영해야 한다.

## 3. 저장(Android와 같은 키)

- 알림: `NotifySettingsStore(AppStores.notifySettings)` → prefs `notify_settings`(deadlineAlerts, deadlineWindowsMinutes+Version, announcementAlerts,
  changeAlerts, submitAlerts, submitOkAlerts, newFileAlerts, pendingSheet, examDoneAlerts, classAlerts, classLeadMinutes, briefEnabled,
  briefMinutesOfDay, quietEnabled, quietStart/EndMinutes, hideContentOnLockScreen).
- 과목 요약: `CourseFilterStore()`(`course_filter`), 마지막 확인/브리핑: `NotifyStateStore()`(`notify_state`).
- 말투·인증 방식·모델: `ChatSettings`(`app_settings`: casualTone, authMode, model, effort).
- 비밀값: eTL 토큰 `TokenStore`(`token_blob`), API 키 `APIKeyAuthProvider.SECRET_KEY` — Keychain.
- 화면 모드: 공용 `AppAppearanceStore.shared`(현재 `UserDefaults.standard` 키 `app.themeMode`, 값 system/light/dark).
  Android는 `app_settings`에 저장하므로 키를 맞추려면 Theme.swift(공용) 쪽을 고쳐야 한다.

## 4. 원본과 다른 점

- 섹션 순서는 원본 `SettingsRoot` 그대로(ChatGPT 계정 · eTL 토큰 · 알림 · 대화 · AI 모델 · OpenAI API 키 (고급) · 기타 · 앱 정보).
  화면 모드와 알림 기록·시간표·내 일정 링크는 원본처럼 "기타" 안에 있다.
- AI 모델: 원본은 `ModelPickerContent`를 섹션에 그대로 넣는다. `ChatModelPicker`의 본문이 private이라 iOS는 현재 선택 행 → `ChatModelPicker` 시트.
  똑같이 하려면 Chat 쪽에서 본문(`cards`)을 `ChatModelPickerContent`로 꺼내 주면 섹션에 바로 넣을 수 있다.
- 로그인 패널: 앱 안 브라우저 시트라 기기 코드 방식("코드 복사", 남은 시간, "브라우저가 안 되나요? 코드로 로그인")은 뺐다.
  안내 문구는 "…끝나면 이 화면으로 돌아와요."로 바꿨다(원본 "끝나면 알림이 오고…").
- "Keystore" 문구 → "Keychain". API 키 설명의 "실행할 때만 환경변수로 넘겨요" → "OpenAI에 요청할 때만 써요".
- eTL 토큰 "저장"은 첫 실행 안내와 같은 확인 흐름(`OnboardingModel.checkAndSaveToken`, eTL `users/self` 확인 후 저장)을 쓴다. 원본은 형식만 보고 저장.
- "홈 화면에 위젯 추가하기"(2026-11-27): 원본은 위젯 고르기 대화상자 → `requestPinAppWidget`. iOS 앱은 위젯을 추가할 수 없어서
  같은 자리(기타, "내 일정" 아래)의 행이 안내 화면 `WidgetGuideView`를 연다: 홈 화면 추가 순서, 위젯별 크기(작게·중간·크게, iPad 아주 크게),
  iOS 18 모서리 끌기 크기 전환, 잠금 화면 추가 순서. 가상 eTL에서는 행 설명과 안내 배너로 "위젯에는 실제 eTL 자료만 보여요"를 알린다.

## 5. 뺀 행(iOS에 의미 없음 또는 2단계)

- 정확한 알림 상태·"정확한 알림 허용"(iOS 개념 없음)
- 배터리 최적화 상태·"배터리 제한 풀기"·삼성 안내·절전 모드 안내
- 캘린더 섹션("캘린더 연동") — 2단계
- 받은 파일 섹션("받은 강의자료 자동 정리") — 2단계
- 업데이트 알림 스위치, "업데이트 확인"/"새 버전 받기" 링크(App Store/TestFlight)
- "지금 확인"·"브리핑 미리 보내기"는 `SettingsHooks`를 채울 때만 보인다.
