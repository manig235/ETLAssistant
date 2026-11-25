# ETLKit public API

Kotlin `kr.snu.etlassistant.etl` → Swift `ETLKit`. Names follow Kotlin 1:1. `Long` → `Int64`, `Instant` → `Date`,
`LocalDate` → `DayDate`, `DayOfWeek` → `Weekday`, `object` → `enum` (static members), `data class` → `struct`.
Blocking Kotlin network calls are `async throws` in Swift. All times are KST (UTC+9).

## Time (`Time.swift`, new)

| Swift | Kotlin origin | Description |
|---|---|---|
| `enum Weekday: Int` (`.monday`=1 … `.sunday`=7) | `java.time.DayOfWeek` | ISO weekday; `value`, `label` ("월"…"일"), `labels`, `of(_:)`, `fromLabel(_:)`, `plus(_:)`, `minus(_:)`, Comparable/Codable |
| `struct DayDate` (`year`, `month`, `day`) | `java.time.LocalDate` | KST calendar date; Comparable, Hashable, Codable as `"yyyy-MM-dd"`; `description`/`iso` = `yyyy-MM-dd` |
| `DayDate(year:month:day:)` (failable), `DayDate.of(y,m,d)` | `LocalDate.of` | create (of = precondition) |
| `DayDate(_ date: Date)`, `DayDate.today(_ now: Date = Date())` | `LocalDate.ofInstant(.., KST)` / `LocalDate.now(KST)` | KST date of an instant |
| `DayDate(iso:)`, `DayDate.parse(_:)` | `LocalDate.parse` | strict `yyyy-MM-dd`, nil on error |
| `epochDay`, `DayDate(epochDay:)` | `toEpochDay` / `ofEpochDay` | days since 1970-01-01 |
| `dayOfWeek: Weekday`, `monthValue`, `dayOfMonth`, `dayOfYear`, `lengthOfMonth`, `lengthOfYear`, `isLeapYear` | same | accessors |
| `plusDays/minusDays/plusWeeks/minusWeeks/plusMonths/minusMonths/plusYears`, `withDayOfMonth(_:)` (nil if invalid) | same | arithmetic (plusMonths clamps to month end like Java) |
| `previousOrSame(_:)`, `nextOrSame(_:)`, `next(_:)`, `previous(_:)` | `TemporalAdjusters.*` | weekday adjusters |
| `isBefore/isAfter/isEqual`, `daysUntil(_:)`, `datesUntil(_:)` | same / `ChronoUnit.DAYS.between` | comparisons |
| `startOfDay: Date`, `atTime(h, m, s = 0) -> Date` | `atStartOfDay(KST)`, `atTime(..).atZone(KST)` | KST instant |
| `Date.kstDay`, `Date.epochSecond`, `Date.epochMilli`, `Date(epochMilli:)`, `Date.plusSeconds(_:)` | `Instant` helpers | |

## JSON (`JSON.swift`, org.json replacement)

| Swift | Kotlin origin | Description |
|---|---|---|
| `final class JSONObject` | `org.json.JSONObject` | ordered, mutable, reference type. `init()`, `init(_ json: String) throws`, `init(_ dict: [String: Any?])` |
| `put(_:_:) -> JSONObject` (discardable, chainable; `nil` removes key), `remove`, `has`, `isNull`, `opt`, `optString/optBoolean/optLong/optInt/optDouble/optJSONObject/optJSONArray`, `get*` (throwing), `keys()`, `length()`, `toString()`, `copy()`, `toFoundation()` | same | org.json (Android) coercion rules: `optString` of JSON null = `"null"`, numbers stored as `Int64`/`Double` |
| `JSONObject.NULL` / `JSONNull.shared` | `JSONObject.NULL` | JSON null |
| `final class JSONArray` | `org.json.JSONArray` | `init()`, `init(_ list: [Any?])`, `init(_: [String])`, `init(_: [JSONObject])`, `init(_ json: String) throws`, `put`, `opt*`, `get*`, `isNull`, `length()`, `values`, `toString()` |
| `struct JSONException: Error` | `org.json.JSONException` | parse / get errors |
| `func ?? <T>(T?, JSONNull) -> Any` | Kotlin `x ?: JSONObject.NULL` | value or JSON null |
| `jsonNullIf(_ cond: Bool, _ value: Any?) -> Any` | `if (c) JSONObject.NULL else v` | |
| `JSONObject.str(_:)`, `obj(_:)`, `strList(_:)`; `JSONArray.objects()` | `EtlLogic.str/obj/strList/objects` extensions | null-safe readers |

## Regex helper (`KotlinRegex.swift`)

| `struct KRegex` | `kotlin.text.Regex` | ICU regex wrapper: `containsMatchIn`, `find`, `findAll`, `matches` (whole), `replace(_:_:)` literal / closure, `split`, `KRegex.escape` |

## Errors & client (`EtlClient.swift`)

| Swift | Kotlin origin | Description |
|---|---|---|
| `open class EtlException: Error` (`message`, `kind`) | `EtlException` | kinds: `timeout`, `network`, `http-<code>`, `forbidden`, `auth`, `parse`, `error`; catch with `catch let e as EtlException` |
| `final class EtlAuthException: EtlException` | `EtlAuthException` | token rejected (401, kind `auth`) |
| `actor AsyncSemaphore` | `java.util.concurrent.Semaphore` | FIFO async semaphore |
| `final class EtlClient` `init(_ token: String, origin: String = BASE_URL, session: URLSession? = nil) throws` | `EtlClient(token, origin)` | read-only Canvas client; throws if token is not printable ASCII |
| `get(_ path) async throws -> Any` (JSONObject or JSONArray), `getObject(_:)`, `getAll(_:) -> [JSONObject]` | same | `/api/v1/` only; Link `rel="next"` pagination (≤20 pages, stops if next leaves origin); app-wide ≤3 concurrent |
| `download(_ rawUrl: String, to: URL, maxBytes: Int64 = MAX_DOWNLOAD) async throws -> Download` | `download(rawUrl, OutputStream)` | streams to file; SHA-256; ≤5 redirects, https only; Authorization only on eTL origin (stripped on cross-origin redirects) |
| `struct EtlClient.Download` (`bytes`, `sha256`) | `Download` | |
| `EtlClient.gated { … } async rethrows` | `EtlClient.gated` | run other eTL-host requests (board) under the same limit |
| statics: `BASE_URL`, `MAX_PAGES`, `MAX_REDIRECTS`, `CONNECT_TIMEOUT_MS`, `READ_TIMEOUT_MS`, `DOWNLOAD_READ_TIMEOUT_MS`, `MAX_CONCURRENT`, `MAX_DOWNLOAD` | companion | |
| `isPermissionDenied(_ lowerBody)`, `errorHint(_ lowerBody)`, `sameOrigin(_ url, _ origin = BASE_URL)`, `parseBody(_ body, _ path) throws`, `pathOnly(_:)`, `nextLink(_:)`, `ioKind(_ error)` | companion | pure helpers (tested) |

## Downloads (`EtlDownloads.swift`; from `EtlTools.saveAttachment` + `EtlFiles` name rules)

| Swift | Description |
|---|---|
| `EtlDownloads.DIR` ("etl-files"), `SYLLABUS_DIR` ("syllabus"), `KEEP_DAYS` (14) | constants (`EtlFiles.*`) |
| `workspaceDirectory` (AppPaths `etl-files`), `syllabusDirectory`, `userDirectory` (`Documents/etl`) | locations: assistant-only vs user-visible |
| `storedName(_ id, _ safeName, maxBytes: 200)`, `fitName(_:maxBytes:)` | `EtlFiles.storedName/fitName` (UTF-8 byte cap, keeps extension) |
| `prune(_ dir, nowMs:, maxAgeMs:)` | `EtlFiles.prune` |
| `mimeType(forName:)`, `uniqueFile(in:name:)`, `sha256(_ file)` | helpers |
| `saveAttachment(_ client, meta:, saveToUser: Bool = true, workspace:, userDir:, now:) async throws -> Saved` | file meta → workspace copy `{id}-{name}` (reused if same size and not updated since) + optional `Documents/etl` copy; rejects `locked_for_user`, missing `url` |
| `struct EtlDownloads.Saved` (`id`, `displayName`, `name`, `mime`, `file`, `userFile`, `userError`, `bytes`, `sha256`, `reused`, `expectedBytes`, `sizeOk`) | result (caller builds tool JSON and posts `EtlEvents.fileSaved`) |

## Received-file cleanup (`FilesCleanup.swift`; from `files/DownloadCleanup.kt`, `files/WorkFileCleanup.kt`)

| Swift | Description |
|---|---|
| `enum CleanupPeriod` (`TWO/SEVEN/THIRTY/NEVER`, `key`, `days`, `label`, `of(_:)`, `DEFAULT`) | 2일·7일·30일·정리 안 함 |
| `enum DownloadCleanupLogic` (`DAY_MS`, `MIN_INTERVAL_MS`, `MAX_ASKS`, `USER_DIR`, `GENERATED_SUBDIR`, `isEtlDownload`, `isGeneratedPath`, `shouldDelete`, `due`, `shouldAsk`, `lastRunLabel`, `documentsRelative`, 문구 `QUESTION/DESC/KEEP_HINT/NOT_CHOSEN`) | pure rules; paths are relative to `Documents` (`etl/…`) |
| `final class DownloadCleanup` (`init(prefs:documents:generated:)`, `period`, `setPeriod`, `asks`, `markAsked`, `shouldAsk`, `lastRunLabel`, `statusLabel`, `wasCleaned(_ uri)`, `runIfDue`, `run(force:)`) | prefs `download_cleanup` (keys as Android). Deletes only top-level files in `Documents/etl` by `addedToDirectoryDate`; remembers deleted relative paths (500) |
| `enum WorkFiles` (`prune(_ root, nowMs:, keepDays:)`, `runIfDue(root:prefs:now:)`) | 14-day `etl-files` cleanup, never follows symlinks, once a day |

## Logic (`EtlLogic.swift`)

| Swift (`EtlLogic.`) | Description |
|---|---|
| `kst`, `KST` (TimeZone Asia/Seoul), `calendar` (gregorian, KST) | time zone |
| `termRank(_:)`, `currentCourses(_:)` | latest-term course filter |
| `isSubmitted`, `isOnline`, `isMissing(_:_ now:)` | assignment rules |
| `parseInstant(_:) -> Date?` | ISO-8601 (Z or ±HH:MM, fractional seconds) |
| `fmtKst(_:)` ("11-19(목) 23:59" / "마감일 없음"), `remaining(_:_:)` ("1일 2시간 남음"), `kstParts(_:)` (day, hour, minute, second) | formatting |
| `htmlToText(_:_ limit = 600)` | HTML → text |
| `safeName(_:_ fallback)`, `fileId(_:) throws` | file names / refs |
| `isAllowedGetPath(_:)` | etl_get path filter |
| `unsignText(_:)`, `unsign(_:)`, `modelText(_ value, _ secrets = [])`, `kotlinToString(_:)` | secret redaction before model |
| `struct Evaluation` (`submitted`, `due`, `submittedAt`, `problems`, `warnings`), `evaluateSubmission(_ a, _ s, _ now)`, `checkAttachment(_:_:)`, `LATE_FLAGGED`, `LATE_AFTER_DUE` | submission check |

## Queries (`Queries.swift`, enum `Queries`)

`STATUSES`, `LIST_LIMIT`, `GRADED_LIMIT`, `ANNOUNCEMENT_LIMIT`, `DEFAULT_ANNOUNCEMENT_DAYS`, `MAX_ANNOUNCEMENT_DAYS`;
`usableCourses`, `courseRow`, `coursesResult`, `coursesFor(_:_:) throws`; `isGraded`, `isPublished`, `matchesStatus(_:_:_:) throws`,
`summary(_ a, _ course, _ now)`, `statusList(_ results, _ status, _ days, _ now, limit:) throws`, `gradesResult`;
`announcementRows(_ raw, _ names: [Int64: String]) -> JSONArray`;
`enum AnnCategory` (`EXAM, ASSIGNMENT, VIDEO, MAKEUP, CANCEL`; `badge`, `label`, `timetable`), `announcementCategory`, `announcementBadge`,
`isVideoLecture`, `replacesClass`, `enum ExamMention` (`NONE, TITLE, BODY`), `examMention`, `isExamAnnouncement`, `isAssignmentAnnouncement`,
`suppliesLines(_:maxLines:maxChars:)`; `readOnlyPath`, `capAnswer(_:_:_:) throws`;
`linkedFiles(_ html, _ courseId)`, `struct FileRef` (`id`, `meta`, `source`, `labels`, `title`), `attachedFiles`, `moduleFiles`, `folderNames`, `skipReason`,
`filesResult(...)` — all as in Kotlin `Queries`.

## Courses / assignments / submissions (`CourseAssignments.swift`)

| Swift | Kotlin | Description |
|---|---|---|
| `CourseAssignments.COURSES_PATH`, `assignmentsPath(_:)` | same | |
| `CourseAssignments.CourseResult` (`course`, `assignments`, `error`, `retryable`, `courseId`, `courseName`; `init(_ course, _ assignments, _ error, retryable:)`) | same | |
| `CourseAssignments.currentCourses(_ client) async throws`, `fetch(_ client, _ courses) async throws` | same | parallel; rethrows `EtlAuthException` |
| `AssignmentQuery.Range` (`THIS_WEEK, NEXT_WEEK, DAYS`; `id`, `parse`), `Window` (`start`, `endExclusive`, `contains`), `window(_:_:_ days = 7)`, `select(_:_:includeSubmitted:now:)` | same | KST Monday-based weeks |
| `struct SubmitCheck` (`late`, `problems`, `attachmentsKnown`, `files`) | `SubmitCheck` | |
| `SubmissionCheck.evaluate`, `NO_ATTACHMENT`, `allowedExtensions`, `submissionPath`, `struct Live` (`ok`), `live(_ client, _ courseId, _ assignmentId, _ now) async throws`, `liveFrom` | same | |

## Timetable (`Timetable.swift`)

| Swift | Description |
|---|---|
| `TimetableLogic.WEEKDAYS` ("월화수목금토일"), `weekdayLabels`, `REF_KEYS` | constants |
| structs `Meeting(day,start,end,room)`, `CourseMeetings(courseId,name,meetings,professor,credits:)`, `Weekly(ids,fetchedAt(ms),courses,errors,metadataVersion:)`, `CatalogCourse`, `ClassRow(start,end,course,courseId,room,kind,note)`, `Change(id,date: DayDate,kind,course,courseId:,start:,end:,room:,note:,sourceAnnouncementId:)`, `Day(date,weekday,classes,cancelled)`, `SyllabusAttachment(name,lang,no,seq)` — all `var` fields, Hashable/Codable | data classes (use `var x = …; x.f = …` instead of `copy`) |
| `sameCourse`, `day(_ weekly, _ date, _ changes = [], _ events = [])`, `dayClasses(_ weekly, _ date, _ events = [], _ changes = [])`, `changesToJson/changesFromJson`, `validateChange`, `normTime`, `shortName`, `catalogRef` (`[String: String]?`), `cleanRoom`, `cleanProfessor`, `professor`, `credits`, `refreshedCourse`, `parseMeetings`, `weekdayOf(_ DayDate) -> String`, `attachmentUrl`, `attachmentName`, `syllabusAttachments`, `syllabusJson`, `classesJson`, `weekJson`, `briefLines`, `toJson`, `fromJson` | `TimetableLogic` |
| `CatalogClient.fetch(_ refs) async -> [Result<CatalogCourse, Error>]`, `syllabus(_ ref) async throws -> JSONObject`, `downloadAttachment(_ url, to:, maxBytes:) async throws -> Int64`, `message(_ error) -> String` | sugang.snu.ac.kr (no eTL token, manual session cookie, no redirects, 15 s) |
| `struct CatalogError: Error` (`message`) | user-facing catalogue error |
| `TimetableChangeStore(_ prefs: KeyValueStore = UserDefaultsStore(namespace: "timetable_changes"))`: `load`, `save(_:today:)`, `add`, `delete` | key `items`, drops >30 days |
| `TimetableStore(_ prefs = UserDefaultsStore(namespace: "timetable"))`: `loadCatalog`, `customCourses`, `withCustom`, `load`, `save`, `saveCustom(_:) throws`, `deleteCustom`, `clearCatalog` | keys `weekly`, `custom` |
| `Timetable.CACHE_MS`, `nowMs()`, `weekly(_ etl, _ store, nowMs:, force:) async throws`, `cachedOrFetch(_:_:nowMs:) async throws`, `calendarClasses(_ etl, _ courses, _ date: DayDate) async throws` | |
| `CustomTimetableLogic.validationError`, `normalize`, `merge` | `CustomTimetable.kt` |

## Events / disconnect / secrets

| Swift | Kotlin | Description |
|---|---|---|
| `EtlEvents.SavedFile(name:mime:size:file: URL:phoneUri:shownPath:referenceOnly:)` | `EtlEvents.SavedFile` | `phoneUri` = user copy URL string |
| `EtlEvents.Event` (`.filesSaved([SavedFile], pagePreview:)`, `.notice(String)`, `.dataChanged`) | — | |
| `EtlEvents.subscribe(_ handler) -> Subscription` (keep it; `cancel()`), `EtlEvents.listener` (weak `Listener`: `onFilesSaved`, `onNotice`) | `EtlEvents.listener` | thread-safe hub; handlers run on posting thread |
| `EtlEvents.fileSaved`, `notice`, `pagesRendered`, `dataChanged`, `post` | same (+ `dataChanged` replaces `HomeWidgets.dataChanged`) | |
| `EtlDisconnect.Stores(snapshot:notifyWatch:notifyFiles:timetable:etlFilesDirectory:extraCleanup:)` | — | defaults: UserDefaults namespaces `etl_snapshot`, `notify_watch`, `notify_files`, `timetable` |
| `EtlDisconnect.clearCaches(_ stores)`, `run(tokens:stores:) throws` | `EtlDisconnect` | |
| `open class SecretSlot(store: SecretStore, blobKey:)`: `has`, `save(_:) throws`, `load`, `clear() throws` | Kotlin class `SecretStore` (renamed: Swift `SecretStore` is the storage protocol) | |
| `TokenStore(store: = KeychainSecretStore())` + `hasToken()` (key `token_blob`), `ApiKeyStore` (`apikey_blob`), `ChatGptSecretStore` (`chatgpt_oauth_blob`) | same | |

Pre-existing (`Storage.swift`): `KeyValueStore`, `UserDefaultsStore`, `InMemoryStore`, `SecretStore`, `KeychainSecretStore`, `InMemorySecretStore`, `AppPaths`.
