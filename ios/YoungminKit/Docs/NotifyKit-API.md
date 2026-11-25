# NotifyKit public API

Kotlin `kr.snu.etlassistant.notify` (+ `chat/NotifyUiFormat.kt`, `calendar/CalendarRead.kt`의 `CalEvent`·표시 부분) → Swift `NotifyKit`.
ETLKit 위에 만든다. 이름은 Kotlin 1:1. `Long` → `Int64`, `Instant` → `Date`, `LocalDate` → `DayDate`, `DayOfWeek` → `Weekday`,
`LocalTime` → `DayTime`, `ZoneId` → `TimeZone`, `Pair` → 튜플, `object` → `enum`, `data class` → `struct`(`var` 필드, `copy()` 대신 `var x = …; x.f = …`).
표시 시각은 KST 고정(`NoticeText.dateLabel`), 방해 금지·브리핑·내 일정 날짜 묶음은 넘겨받은 `TimeZone`(기본 `.current`).
저장 형식(JSON 키, prefs 이름)은 안드로이드와 같다. 기본 저장소는 `UserDefaultsStore(namespace: <안드로이드 prefs 이름>)`.

## 공통 도구 (`KotlinSupport.swift`, 새로 만듦)

| Swift | Kotlin origin | 설명 |
|---|---|---|
| `struct DayTime` (`hour`, `minute`, `init?(hour:minute:)`, `of`, `parse("HH:mm")`, `minuteOfDay`) | `java.time.LocalTime` | 편집 창의 시각 |
| `enum ZoneClock`: `localDate(_:_:)`, `secondOfDay`, `hour`, `minute`, `minuteOfDay`, `instant(date,h,m,s,zone)`, `startOfDay(date, zone)` | `ZonedDateTime` | 기기 시간대 계산. 서머타임: 없는 시각은 간격만큼 뒤로, 겹치는 시각은 이른 쪽(Java와 같음) |

## 모델 (`NotifyModels.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `struct AssignmentItem` (위치 인자 init + 이름 인자 init) | `AssignmentItem` | 과제 하나(`courseId`…`check: SubmitCheck?`) |
| `struct AnnouncementItem` | same | 공지 하나 |
| `struct CourseRef(id, name)` | `CourseFilter.kt` `CourseRef` | 과목 |
| `struct Snapshot(takenAt, assignments, announcements, errors, courses)` | same | 한 번 조회한 결과 |
| `enum Channel: String` (`DEADLINE`="deadline", `ANNOUNCEMENT`="announcement_v2", `BRIEF`, `CHANGE`="change_v2", `CLASS`, `UPDATE`, `SUBMIT`="submit_check", `SUBMIT_OK`, `NEW_FILES`; `id`) | same | 알림 채널(iOS: 범주·스레드 ID) |
| `struct Notice` (`channel,title,text,bigText,url,tag,firedKeys,prefill,prefillAction,draft,agenda,mineItemId`) | same | 띄울 알림 하나. `tag` = 요청 식별자(같으면 교체) |
| `struct NotifyState` (`fired: [String: Int64]`, `announcementBaselineDone`, `lastCheckAt`, `lastResult`, `lastBriefAt`; `toJson()`, `mutating prune(_:)`, `fromJson`) | same | 중복 방지·마지막 확인 상태 |
| `struct NotifySettings` (모든 필드 + `anyEnabled`, `needsPeriodicCheck`, `needsTimedCheck`; statics `CHECK_INTERVAL_HOURS`, `DEFAULT_DEADLINE_WINDOWS`, `DEADLINE_WINDOW_PRESETS`, `MIN/MAX_WINDOW_MINUTES`, `MAX_WINDOWS`, `normalizeWindows`, `windowLabel`) | same | 알림 설정 |
| `enum Quiet.isQuiet(_ minuteOfDay, _ settings)` | same | 방해 금지(자정 넘김 포함) |
| `JSONArray.toIntList()` | same | |

## 문구·계획 (`NotificationPlanner.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `struct Plan(notices, silentKeys, markBaselineDone)` | same | 계획 결과 |
| `enum NoticeText`: `TITLE_MAX`(80), `LINE_MAX`(140), `ETL_PREFIX`, `clean`, `cut`(UTF-16, 대리쌍 보존), `safe`, `course`, `name`, `safeUrl`, `assignmentUrl`, `announcementUrl`, `collectionUrl`, `dateLabel`(KST `11/23(월) 15:00`), `dayLabel(_, zone)`, `dueLine`, `annLine`, `joinLimited` | `internal object NoticeText` (공개로 바꿈) | eTL 문자열 정리·자르기·링크 |
| `NotificationPlanner.plan(snapshot, state, now, settings, zone = .current)`, `nextDeadlineCheckAt`, `assignmentPrefill`, `examPrefill`, `EXAM_ACTION` | same | 마감 창·공지 알림 계획(순수) |

## 내 마감 (`MyDeadlines.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `struct MyDeadline` (`id,title,due,memo,done,reminders,repeat,doneDates,skipDates,occurrence,kind,end,location,course,supplies,sourceAnnouncementId`; `isExam`, `over`) | same | 내 마감·시험 |
| `enum MyKind` (`DEADLINE`/`EXAM`; `id`, `label`, `parse`) | same | |
| `struct MyRepeat(freq, weekdays:, until:, count:)`, `MyRepeat.Freq` (`DAILY/WEEKLY/BIWEEKLY/MONTHLY`; `id`, `label`, `parse`) | same | 반복 규칙 |
| `MyDeadlines`: 상수(`COURSE_ID`=-1, `COURSE_NAME`, `TITLE_MAX`, `MEMO_MAX`, `MAX_ITEMS`, `MAX_REMINDERS`, `MISSED_LOOKBACK_DAYS`, `COUNT_MAX`, `LOCATION_MAX`, `COURSE_MAX`, `SUPPLIES_MAX`, `EXAM_DONE_WINDOW`) | same | |
| 반복: `weekdaysOf`, `repeatEndConflict`, `matches`, `countLastDate`, `occurrenceCount`, `lastDate`, `lastOccurrence`, `endSummary`, `occurrenceAt`, `occurrences`, `nextOccurrence`, `firstOccurrence`, `lastMissed`, `active`, `agendaItems`, `itemId`, `findByItemId`, `setDone`, `skip`, `repeatLabel`, `validateRepeat`, `weekdayId`, `parseWeekday` | same | 회차 펼치기(KST) |
| 알림·브리핑: `toItems`, `merge`, `reminderKey`, `reminderNotices`, `nextReminderAt`, `examInfo`, `examReminder`, `examDoneKey`, `examDoneNotices` (→ `([Notice],[String])`), `examLines`, `defaultExamReminders` | same | |
| 입력·표시: `validate`, `validateReminders`, `validateExam`, `normalizeReminders`, `normalizeSupplies`, `parseDue`, `parseReminder`, `parseEnd`, `examTitle`, `examTimeLabel`, `reminderLabel`, `sorted`, `line` | same | |
| 도구·저장: `toolResult(list, now, window, includeDone:) -> JSONObject`, `repeatJson`, `toJson`, `fromJson` | same | 비서 도구 `my_deadlines` 결과, 저장 JSON |
| `final class MyDeadlineStore(_ prefs = "my_deadlines", onChanged:)`: `load`, `save`, `upsert`, `delete`; `var onChanged` | same | 저장할 때 `EtlEvents.dataChanged()` + `onChanged` (안드로이드의 위젯 갱신·캘린더 내보내기 대신) |
| `enum ExamCheer` (`Phase` EVE/DAY/AFTER, `phaseOf`, `pick`, `message(…, casual:)`, 문구 목록 `EVE`…`AFTER_CASUAL`) | `ExamCheer.kt` | 시험 응원 문구(결정적) |
| `enum DeadlineForm` (`EndMode`, `Input`, `firstWeekly`, `firstBiweekly(…, nextWeek:)`, `firstMonthly`, `firstDaily`, `biweeklyNextWeek`, `firstDate(…, keep:)`, `due`, `samePattern`, `switchFreq`, `switchEnd → (until:, count:)`, `firstLabel`) | `DeadlineForm.kt` | 내 마감 편집 창 계산 |

## 내 일정·캐시 (`Agenda.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `struct CachedSnapshot(takenAt ms, assignments)`, `SnapshotCacheLogic.toJson/fromJson` | same | 받은 eTL 과제 저장본(version 1) |
| `final class SnapshotCache(_ prefs = "etl_snapshot")`: `load`, `save(_ Snapshot)` | same | 실패한 과목은 이전 값 유지. `EtlEvents.dataChanged()` |
| `struct CalEvent`, `CalendarReadLogic.timeLabel`, `briefLines` | `calendar/CalendarRead.kt` 일부 | 캘린더 일정(1단계에선 화면이 넘길 때만) |
| `Agenda.Kind`, `Filter` (`label`), `Row`, `Group`, `Schedule` (`lastDay`, `upcomingCount`, `laterCount`) | same | |
| `Agenda.build(assignments, mine, now, zone, filter = .ALL, hideDone:, calendar:)`, `submittedLabel`, `dateLabel`, `dayLabel`, `whenLabel` | same | "내 일정" 화면 데이터 |

## 공지 초안·등록 안 한 공지 (`ScheduleDraft.swift`, `PendingNotices.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `typealias AnnCategory = Queries.AnnCategory` | | |
| `struct ScheduleDraft` (`foundDate`, `timetable`, `actionLabel`, `toJson`, `fromJson`) | same | "내 일정에 등록"/"시간표에 반영" 초안 |
| `ScheduleDrafts`: 문구 상수, `kindOf`, `fromAnnouncement`, `prefill`, `cleanTitle`, `findDays`, `findAll → [(Date, Date?)]`, `single`, `firstOf` | same | 날짜가 정확히 하나일 때만 채움 |
| `struct PendingNotice`, `PendingNoticesLogic` (`MAX`, `KEEP_MS`, `add`, `targets(registered:ignored:excludedCourse:now:)`, `shouldAutoShow`, `toJson`, `fromJson`, `ids`, `idList`) | same (`KEEP: Duration` → `KEEP_MS`) | |
| `final class PendingNoticeStore(_ prefs = "pending_notices")`: `load`, `record`, `ignored`, `seen`, `ignore`, `markSeen`, `targets(courses:registered:now:)`, `static registeredIds(mine:changes:)` | same (+ `DraftIntents.registeredIds`) | 채팅 하단 시트용 |

## 과목 필터 (`CourseFilter.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `enum CourseMode` (`SHOW/MUTE/HIDE`; `key`, `label`, `of`) | same | |
| `struct CourseFilter(modes, names, hideTimetable)`: `mode`, `muted`, `hidden`, `isEmpty`, `mutedCount`, `hiddenCount`, `timetableHiddenCount`, `with(_, _, hideFromTimetable:)`, `withTimetable`, `alertable`, `mutedPart`, `visible`, `visibleAssignments`, `classMode`, `hidesTimetable`, `hidesClass`, `timetableDay`, `timetableWeekly`, `timetableChanges`, `alertDay` | same | 과목별 알림 끄기·숨기기 |
| `CourseMute.plan(snapshot, state, now, settings, filter, zone)` | same | 끈 과목 키는 silentKeys로 |
| `CourseFilterLogic`: `toJson`, `fromJson → Stored`, `Stored`, `CourseRow`, `rows`, `keepOrder`, `fallbackCourses`, `summary` | same | |
| `final class CourseFilterStore(_ prefs = "course_filter", onChanged:)`: `loadAll`, `load`, `setMode`, `setHideTimetable`, `saveKnown` | same | 바꾸면 `EtlEvents.dataChanged()` + `onChanged` |

## 변경 감시·수업·새 강의자료 (`Watch.swift`, `NewFiles.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `WatchRecord`, `WatchState` (`toJson`, `fromJson`) | same | |
| `AssignmentWatch.submitKey`, `isNewSubmission`, `diff(prev, snapshot, now, muted, changes:, submits:, submitOk:) -> (WatchState, [Notice])` | same | 새 과제·마감 변경·성적·제출 점검 |
| `ClassNotices.key`, `plan(day, now, zone, leadMin, fired)`, `nextAt` | same | 수업 N분 전 알림 |
| `final class WatchStore(_ prefs = "notify_watch")` | same | key `state` |
| `CourseFiles`, `FilesWatchState`, `FileRef(id, name)` | same | (ETLKit `Queries.FileRef`와 다른 타입) |
| `NewFiles`: `INTERVAL_MS`, `SEEN_MAX`, `SKIP_MS`, `MAX_CONSECUTIVE_NETWORK_FAILURES`, `path`, `due`, `parse`, `Outcome` (`.files/.denied/.authRejected/.failed`), `diffCourse`, `toCheck`, `apply(…, muted:)`, `prefill`, `notice` | same | |
| `final class NewFilesStore(_ prefs = "notify_files")` | same | |
| `NewFilesCheck.fetch(_ client, courses) async throws`, `fetch(_ get: (String) async throws -> Any, courses)` | same | 차례로 GET, 연결 실패 2번 이어지면 중단 |

## 브리핑·예약·설정·기록 (`BriefBuilder.swift`, `NotifySchedule.swift`, `NotifySettingsStore.swift`, `NotifyHistory.swift`, `NotifyUiFormat.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `BriefBuilder.build(snapshot, now, settings, zone, lookbackHours, todayClasses:, staleSince:, exams:, todayEvents:)` | same | 정기 브리핑 알림 |
| `NotifySchedule`: `MAX_LATE_MILLIS`, `MIN_FLEX_MILLIS`, `nextOccurrenceDelayMillis(now, zone, minuteOfDay, minLeadMillis:)`, `isStale`, `keepBriefTarget(stored, minute, nowMs, zone)`, `flexMillis`, `javaHash`, `notificationId`, `authFailureDue`, `AlarmRequest`, `nextAlarm`, `Rearm`, `rearmAction` | same (`ZonedDateTime` → `(Date, TimeZone)`) | |
| `NotifySchedule.dueBriefSlot(settings, lastBriefAtMillis:, now, zone) -> Date?` | (iOS 추가) | 놓친 브리핑 예정 시각(4시간 안) |
| `final class NotifySettingsStore(_ prefs = "notify_settings", onChanged:)`: `load`, `save` | same | 시점 저장 형식 버전 1~3 호환 |
| `final class NotifyStateStore(_ prefs = "notify_state")`: `load`, `save`, `update(_ (inout NotifyState) -> Void)` | same | `NoticeDelivery.lock` 공유 |
| `HistoryEntry`, `NotifyHistoryLogic` (`KEEP_DAYS`, `MAX_ITEMS`, `add`, `toJson`, `fromJson`, `channelLabel`) | same | |
| `final class NotifyHistory(_ prefs = "notify_history")`: `load`, `record(_ notice, nowMs:)`, `clear` | same | 게시한 뒤 앱이 부른다 |
| `NotifyUiFormat`: `formatTime`, `normalizeBriefTimes`, `windowLabel`, `windowsSummary`, `describeLastCheck`, `describeLastBrief` | `chat/NotifyUiFormat.kt` | 설정 화면 표기 |

## 조회·폴백·제시간 알림 (`SnapshotFetcher.swift`, `SnapshotFallback.swift`, `TimedNotifications.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `SnapshotFetcher.fetch(client, now, announcementDays:, needsSubmissionDetail:) async throws -> Snapshot` | same | 토큰 거부는 던지고 그 밖은 `errors`에 |
| `SnapshotFetcher.sortAssignments`, `mapAssignments`, `mapAnnouncements`, `kstDateRange → (String, String)` | same | 순수 변환 |
| `SnapshotFallback`: `MAX_AGE_MS`, `Result(snapshot, cachedAt)`, `apply(fresh, cached, now, totalFailure:)`, `hasConnectionErrors`, `usable`, `failedCourses`, `ageLabel`, `staleLine` | same | |
| `TimedNotifications.Result(plan, nextAt)`, `plan(cached, mine, days, state, now, settings, courses:, zone:, casual:)`, `hasPersonalReminders`, `hasDuePersonalReminders` | same | 로컬 데이터만으로 지금 보낼 것 |
| `TimedNotifications.Inputs` (`read(stores, now, zone:, casual:)`, `plan`), `deliver(stores, now, zone:, casual:, post:) -> Result` | `readInputs` / `deliver` | 계획 + 기록(게시는 `post`) |
| `TimedNotifications.forecast(inputs, state, from:, until:, maxSteps: 64) -> [(at, notice)]` | (iOS 추가) | 앞으로 울릴 제시간 알림 미리 펼치기(예약용, 상태는 안 바꿈) |
| `NoticeDelivery.lock` (`NSRecursiveLock`), `deliver(now, load, save, post, planner) -> Plan` | `internal object NoticeDelivery` | `post`가 true인 알림만 기록, 전부 기록된 알림만 건너뜀 |

## 게시 보조·링크 (`NotificationPoster.swift`, `LearningXLink.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `enum NoticeTap` (`CHAT_PREFILL, ETL, AGENDA, CHAT`), `NoticeRouting.tap(_:)` | same | 본문 탭 경로 |
| `NotificationPoster`: `LEGACY_*`, `LOCKSCREEN_TITLE`/`TEXT`, `ETL_ACTION`, `Importance`, `channelName`, `importance`, `Action` (`.draft/.prefill/.openEtl`, `label`), `actions(_:)`, `identifier(_:)` | 순수 부분 | 채널 이름·중요도·버튼 순서 |
| `LearningXLink`: `PACKAGE`, `SCHEME`, `webUrl`, `fromWebUrl` (`canvas-student://…`), `opensBoardList` | `internal object LearningXLink` | eTL 주소만 허용 |
| `NoticeCodec.toJson/fromJson/toObject/fromObject` | (iOS 추가) | `Notice` ↔ JSON(알림 `userInfo["notice"]`, 예약 장부) |
| `NoticeOpen` (`.draft/.chat(prefill:)/.etl/.agenda`), `ACTION_DRAFT/PREFILL/ETL`, `identifier(action)`, `categoryId(actions, hideOnLockScreen:)`, `resolve(notice, action:)` | (iOS 추가) | 버튼·본문 탭이 갈 곳(안드로이드 PendingIntent들) |
| `ScheduledNotices.Entry(id, at, notice)`, `toJson/fromJson`, `resolve(list, pendingIds:, nowMs:) → (fired, keep)`, `apply(fired, to: &state)` | (iOS 추가) | 예약 알림 장부: 뜬 것의 키·브리핑 시각 기록 |
| `BriefForecast.upcomingSlots(settings, now, zone, horizonMillis:)`, `offline(stores, at:, zone:, hasToken:, todayEvents:) -> Notice?` | (iOS 추가) | 브리핑 미리 예약(받아 둔 정보로) |

## 실행기 (`NotifyRunner.swift`)

| Swift | Kotlin | 설명 |
|---|---|---|
| `struct NotifyStores` (settings, state, mine, courses, watch, files, snapshot, pending, history, timetable, timetableChanges; `inMemory()`) | — | 저장소 모음(기본 UserDefaults namespace = 안드로이드 prefs 이름) |
| `NotifyRunner.Outcome` (`SUCCESS, RETRY, SKIPPED, FAILED`) | same | |
| `NotifyRunner.Environment(stores:token:zone:casual:canPost:clock:makeClient:log:todayEvents:)` | (Context·EtlService·AppSettings 대신) | 실행 환경 주입 |
| `NotifyRunner.CheckResult` (`outcome`, `notices`, `nextAt`, `lastResult`) | — | 게시할 알림(이미 기록됨) + 다음 제시간 시각 |
| `NotifyRunner.runCheck(_ env, now:, briefDueAt:) async -> CheckResult` | `runCheck` | 순서: 내가 정한 알림(오프라인) → eTL 조회(폴백) → 마감·공지 계획 → 새 과제·변경·제출 점검 → 수업 → 제시간 재계획 → 새 강의자료(4시간) → 브리핑(`briefDueAt`이 있으면) |
| `NotifyRunner.runBrief(_ env, now:, scheduledAtMillis:, allowFallback:) async -> CheckResult` | `runBrief` | 브리핑만 |

토큰 거부면 하루 한 번 "eTL 토큰을 확인해 주세요"(`tag` `auth`, 키 `authfail`)를 돌려주고 `FAILED`. 다른 예외는 `RETRY` + `lastResult = "실패 · …"`.

## 캘린더 연동 "영민이 → 캘린더" (`CalendarSyncLogic.swift`, `CalendarSyncExport.swift`, `CalendarSyncEventKit.swift`, 3단계)

Kotlin `calendar/CalendarLogic.kt`(내보내기 부분)·`CalendarExport.kt`·`CalendarCleanupPolicy.kt`. AssistantKit에 비서 도구용 `CalendarLogic`이 이미 있어
이 묶음은 `CalendarSyncLogic`이라 부른다. 일정·캘린더 ID는 EventKit 식별자(문자열, 묘비 `TOMBSTONE` = "").

| Swift | Kotlin | 설명 |
|---|---|---|
| `enum CalendarTarget` (`LOCAL`="local", `GOOGLE`="google"), `struct CalendarExportSettings` (`toJson`/`fromJson`, `googleCalendarId: String?`) | same | GOOGLE = 사용자가 고른 계정 캘린더(iCloud·구글 등) |
| `struct CalItem` (`hash`, `rowHash`), `struct CalRow` | same | |
| `CalendarSyncLogic`: `KEEP_PAST`(14일), `REPEAT_AHEAD`(60일), `FORGET_AFTER`(180일), `MAX_REVERTS`, 키(`etlKey`, `mineKey`, `prefixOf`), `appUri`/`keyFromAppUri`/`mineIdFromUri`, `hash`(SHA-256 12바이트), 제목·설명(`etlTitle`…`titleFromCalendar`, `FOOTER`, `cleanMultiline`), `etlItem`, `mineItem`, `Desired`, `desired`, `Entry`, `Pull`, `Edit`(`PullMine/Revert/Accept/Baseline`), `edits`, `applyPull`, `Op`, `Plan`, `plan`, `Mapping`, `mappingToJson/FromJson`, `Recurrence`, `recurrence`, `rrule`, `dtstartFor`, `utc`, `local`, `escapeText`, `fold`, `ics`, `icsFileName`, `resultLabel`, `editsLabel` | `CalendarLogic` | 순수 계획 |
| `CalendarCleanupPolicy.cleanup/switchTarget`, `CalendarPermissionError`, `CalendarSyncError` | same (+ `SecurityException`) | 지우기에 성공할 때까지 매핑을 놓지 않는다 |
| `CalendarAppCalendarCleanup` (`isAppEvent`, `eventsToDelete`, `userEventCount`, `canRemoveCalendar`, `SCAN_YEARS_BACK/AHEAD`=30), `CalendarScan`, `CalendarScannedEvent` | — (iOS 추가) | 끄기·대상 바꾸기: 앱 캘린더에서 앱이 넣은 일정(매핑 + `youngmin://item/`·설명 끝 문구)만 지우고, 지운 뒤 앞뒤 30년을 다시 읽어 사용자 일정이 없을 때만 캘린더를 지운다. 남긴 캘린더는 `localCalendar`로 기억해 다음에 켤 때 다시 쓰고(재설치 뒤엔 `reusableLocalCalendar`), 쓰지 않는 동안 `ownIdentifiers`에서 빠진다 |
| `protocol CalendarSyncBackend`, `CalendarExportInfo`, `CalendarLocalPlacement` (`device/iCloud/other/unavailable`) | CalendarContract 부분 | 테스트는 메모리 가짜 |
| `final class CalendarExportStore(_ prefs = "calendar_export")`: `settings/saveSettings`, `lastResult`, `note`, `setResult`, `loadMapping/saveMapping/clearMapping`, `localCalendar`(iOS 추가), `ownIdentifiers()` | prefs 부분 | 키 `settings`, `lastResult`, `note`, `mapping`, `localCalendar` |
| `final class CalendarExport(store:backend:mine:snapshot:courses:)`: `summary(showEnabled:assistantEnabled:)`, `localCalendarId`, `localPlacement`, `accountCalendars`, `syncIfEnabled(now)`, `enable`, `disable`, `switchTarget`, `lastCleanupKeptCalendar`, `offMessage/switchedMessage(keptCalendar:)`, `icsContent`, `InsertDraft`, `insertDraft(_ assignment)`, `insertDraft(series:occurrence:)`, `onChanged`, `log` | `object CalendarExport` | 잠금으로 한 번에 하나 |
| `final class CalendarSyncEventKit: CalendarSyncBackend` (`eventStore`, `requestAccess`, `makeEvent(draft, store)`, `rule(Recurrence)`) | — | 앱 캘린더는 로컬 소스(없으면 iCloud)에 만든다. 일정 주소 `youngmin://item/{key}`(키의 `:`→`-`, 위젯 링크와 같은 모양) |

## 옮기지 않은 것(안드로이드 전용)

`NotifyScheduler`(WorkManager 주기·브리핑 예약), `NotifyCheckWorker`, `BriefWorker`, `DeadlineAlarm`(정확한 알람), `SystemEventReceiver`(재부팅·시간 변경),
`BatteryOpt`, `LearningXOpenActivity`, `NotificationPoster.ensureChannels/post/canPost`(NotificationManager), 홈 위젯 갱신.

## What the iOS app layer must do

1. **게시**: `runCheck`/`runBrief`/`TimedNotifications.deliver`가 돌려준 `Notice`를 `UNUserNotificationCenter`로 게시한다.
   - `identifier` = `notice.tag`(같은 tag는 교체), `threadIdentifier`/`categoryIdentifier` = `channel.id`, `title`/`body` = `title`/`bigText ?? text`.
   - `interruptionLevel`: `NotificationPoster.importance` — `.high` → `.timeSensitive`(권한 없으면 `.active`), `.normal` → `.active`, `.low`(`SUBMIT_OK`) → `.passive`(소리 없음).
   - 버튼은 `NotificationPoster.actions(notice)` 순서로 `UNNotificationCategory`에 넣는다(최대 3개). `userInfo`에 `url`, `prefill`, `draft`, `agenda`, `mineItemId`를 담는다.
   - 잠금화면 숨김(`settings.hideContentOnLockScreen`)은 iOS 설정의 "미리 보기"로 대신하거나 `hiddenPreviewsBodyPlaceholder`에 `LOCKSCREEN_TEXT`를 쓴다.
   - 게시한 뒤 `stores.history.record(notice)`(알림 기록 화면).
   - 중복 방지 키는 NotifyKit이 이미 기록했다. 권한이 없으면 `Environment.canPost = false`로 부르면 아무것도 기록하지 않는다(권한을 주면 다시 보낸다).
2. **탭 처리**: `NoticeRouting.tap`으로 화면을 고른다. `.ETL` → `LearningXLink.fromWebUrl(url)`을 `UIApplication.open`(실패하면 `webUrl`을 Safari),
   `.AGENDA` → 내 일정(있으면 `MyDeadlines.findByItemId(store.load(), mineItemId)` 항목 창), `.CHAT_PREFILL` → 채팅 입력창에 `prefill`(보내지 않음), `.CHAT` → 채팅.
   버튼: `.draft` → `ScheduleDraft.fromJson(json)`로 등록 창, `.prefill` → 채팅 입력창, `.openEtl` → 위와 같다. `Info.plist`의 `LSApplicationQueriesSchemes`에 `canvas-student`.
3. **주기 확인(안드로이드 1시간 WorkManager 대신)**: `BGAppRefreshTask`(earliestBeginDate ≈ 1시간, `settings.needsPeriodicCheck`일 때만)에서
   `NotifyRunner.runCheck(env, briefDueAt: NotifySchedule.dueBriefSlot(settings, lastBriefAtMillis: state.lastBriefAt, now, .current))`.
   작업 만료 핸들러에서 Task를 취소하고, `RETRY`면 더 이른 다음 작업을 잡는다. 토큰은 ETLKit `TokenStore().load()`, `casual`은 ChatKit 설정의 반말 여부.
4. **제시간 알림(안드로이드 `DeadlineAlarm` 대신)**: iOS는 정확한 시각에 앱을 깨울 수 없으므로 `TimedNotifications.forecast(Inputs.read(stores, now), stores.state.load(), from: now, until: now+7일)`
   결과를 `UNCalendarNotificationTrigger`(또는 `UNTimeIntervalNotificationTrigger`)로 미리 예약한다(앱 알림은 64개 한도 — 가까운 것부터).
   식별자는 `notice.tag`. 예약을 다시 잡기 전에 이전 예약(예: 접두어 `timed:`를 붙인 식별자)을 지운다. 예약된 알림이 실제로 뜬 뒤 앱이 열리면
   `TimedNotifications.deliver`가 같은 키를 기록해 중복을 막는다(같은 tag라 교체). 지금 보낼 것은 `deliver(stores, post:)`로 바로 게시한다.
5. **다시 계획할 때**: 앱 시작·포그라운드 진입, 알림 권한을 새로 받았을 때, 시간대·시계 변경(`NSSystemTimeZoneDidChange`, `NSSystemClockDidChange`),
   `MyDeadlineStore.onChanged`·`CourseFilterStore.onChanged`·`NotifySettingsStore.onChanged`, `EtlEvents.dataChanged`(시간표·보강/휴강), `runCheck` 끝(`CheckResult.nextAt`).
   이때 `TimedNotifications.deliver` + `forecast` 예약을 다시 한다. 권한을 새로 받았으면 `hasDuePersonalReminders`가 true일 때 바로 `deliver`.
6. **브리핑(안드로이드 `BriefWorker` 대신)**: `settings.briefMinutesOfDay`마다 `NotifySchedule.nextOccurrenceDelayMillis`로 `BGAppRefreshTask`를 그 시각 근처에 요청하고
   거기서 `runBrief(env, scheduledAtMillis:)`(또는 `runCheck(briefDueAt:)`). 늦어진 브리핑은 `dueBriefSlot`/`isStale`(4시간)로 거른다.
   백그라운드가 안 돌면 브리핑은 다음 앱 실행 때 `dueBriefSlot`로 보낸다(정각 보장 불가 — 안드로이드와 다름).
7. **설정 화면**: `NotifyUiFormat.describeLastCheck(state.lastCheckAt, state.lastResult)`, "지금 확인" = `runCheck`. 과목 목록은 `CourseFilterLogic.rows(stored.known, fallbackCourses(...), filter)`;
   eTL 조회 뒤 `CourseFilterStore.saveKnown(snapshot.courses, now)`를 앱이 부른다(안드로이드 설정 화면과 같다).
8. **채팅 하단 시트**: `PendingNoticeStore.targets(courses:registered: PendingNoticeStore.registeredIds(mine:changes:))`, `shouldAutoShow(…, seen:, enabled: settings.pendingSheet, …)`.
9. **eTL 연결 해제**: ETLKit `EtlDisconnect.clearCaches`가 `etl_snapshot`·`notify_watch`·`notify_files`를 지운다(NotifyKit 기본 namespace와 같다). 예약해 둔 로컬 알림도 지운다.
