# 내 일정 · 시간표 · 알림 기록 · 과목별 알림 — 연결 방법

## AppRoute (`App/AppRoute.swift`)

| case | view |
|---|---|
| `.mySchedule` | `MyScheduleView()` (replaces `MySchedulePlaceholder`) |
| `.timetable` | `TimetableView()` (replaces `TimetablePlaceholder`) |
| `.notifyHistory` | `NotifyHistoryView()` (replaces `NotifyHistoryPlaceholder`) |

New cases to add (settings, notification taps, widgets):

| new case | view |
|---|---|
| `.courseAlerts` | `CourseAlertsView()` (설정 → 과목별 알림) |
| `.myScheduleItem(Int64)` | `MyScheduleView(openDeadlineId: id)` (opens the edit sheet; `NoticeTap.AGENDA` + `mineItemId`, so a repeat occurrence's item ID works too) |
| `.scheduleDraft(String)` | `MyScheduleView(draft: ScheduleDraft.fromJson(json))` (notification button "내 일정에 등록"; timetable drafts are passed on to `TimetableView` by the view itself) |
| `.timetableDraft(String)` | `TimetableView(draft: ScheduleDraft.fromJson(json))` (notification button "시간표에 반영") |

Inside these screens I push other screens with `navigationDestination(isPresented:)` / `(item:)`. These are: 내 일정 → 시간표 and 과목별 알림; 시간표 → 과목별 알림; 알림 기록 → 내 일정 (edit or draft) and 시간표. They need no route. Every view hides the system nav bar and draws its own 56pt top bar, so they must be pushed inside the existing `NavigationStack`.

## Environment and state

- `AppState` must be in the environment. It already is under `RootView`. The views use it only for the fallback of "✨ 비서가 공지 읽고 채우기": they set `app.chatController().input = prefill`, then `dismiss()`.
- Recommended: set `.environment(\.schedOpenChat) { prefill in controller.input = prefill; path.removeAll() }` on the `NavigationStack` in `ChatView`, so the button returns to the chat root.
- `ToastCenter.shared` is used for toasts (`RootView` already has `.toastOverlay()`).

## Stores

`Schedule/ScheduleShared.swift` → `SchedStores` makes the default NotifyKit/ETLKit stores. They use the Android prefs namespaces: `my_deadlines`, `etl_snapshot`, `course_filter`, `timetable`, `timetable_changes`, `notify_history`, `notify_settings`. Token: `AppStores.tokens`.

When `AppStores` gets a `NotifyStores` instance (e.g. `static let notify = NotifyStores()` with `mine.onChanged` / `courses.onChanged` wired to re-planning), point `SchedStores.*` at those instances. Then saving from these screens triggers notification re-planning. Until then, every save still posts `EtlEvents.dataChanged()`, so subscribe to that to call `TimedNotifications.deliver` and `forecast`.

Android extras I did not port, which the app layer should do on `MyDeadlineStore` change:
- `NotifyScheduler.apply`
- `DeadlineAlarm.scheduleEarlier`
- the immediate `checkNow` when deadline alerts are on

## Demo

`DemoScheduleSeed.seed(_:)` runs from each screen's `onAppear` in DEBUG when `debug.demo` is true. It runs once per data part, controlled by the flags `debug.demo.seeded.snapshot|mine|timetable|change|history`. To reseed, delete those keys. During the demo no eTL requests are made.

## Left out (phase 2)

These were left out:
- the "캘린더에 추가" row icon and the "캘린더" filter chip (EventKit)
- calendar rows
