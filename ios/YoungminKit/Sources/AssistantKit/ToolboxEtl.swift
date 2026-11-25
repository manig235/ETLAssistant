import Foundation
import ETLKit
import NotifyKit

// eTL 읽기 도구 본체(Kotlin `EtlTools`의 "도구 본체", "다운로드", "제출 확인" 부분).
extension Toolbox {

    private func kstDate(_ t: Date) -> String { DayDate(t).description }

    private func courseIds(_ courses: [JSONObject]) -> String {
        courses.map { "context_codes[]=course_\($0.optLong("id"))" }.joined(separator: "&")
    }

    /// 실패해도 목록의 나머지는 만든다(토큰 거부는 그대로 던진다).
    private func soft<T>(_ block: () async throws -> T) async throws -> T? {
        do {
            return try await block()
        } catch let e as EtlAuthException {
            throw e
        } catch is EtlException {
            return nil
        }
    }

    func statusAssignments(_ c: EtlClient, _ args: JSONObject) async throws -> JSONObject {
        let now = clock()
        let courses = try Queries.coursesFor(try await c.getAll(CourseAssignments.COURSES_PATH), try optId(args, "course_id"))
        let days: Int? = args.has("days") ? min(max(args.optInt("days"), 1), 365) : nil
        return try Queries.statusList(try await CourseAssignments.fetch(c, courses), args.optString("status", "upcoming").kt.lowercased(), days, now)
    }

    func announcements(_ c: EtlClient, _ daysArg: Int, _ course: Int64?) async throws -> JSONObject {
        let now = clock()
        let days = min(max(daysArg, 1), Queries.MAX_ANNOUNCEMENT_DAYS)
        let courses = try Queries.coursesFor(try await c.getAll(CourseAssignments.COURSES_PATH), course)
        var raw: [JSONObject] = []
        if !courses.isEmpty {
            raw = try await c.getAll("/api/v1/announcements?\(courseIds(courses))&start_date=\(kstDate(now.plusSeconds(-Int64(days) * 86400)))&end_date=\(kstDate(now.plusSeconds(86400)))&per_page=50")
        }
        var names: [Int64: String] = [:]
        for co in courses { names[co.optLong("id")] = co.str("name") ?? "" }
        let rows = Queries.announcementRows(raw, names)
        return JSONObject().put("days", days).put("count", rows.length()).put("announcements", rows)
    }

    func grades(_ c: EtlClient, _ course: Int64?) async throws -> JSONObject {
        let courses = try Queries.coursesFor(try await c.getAll(CourseAssignments.COURSES_PATH), course)
        return Queries.gradesResult(courses, try await CourseAssignments.fetch(c, courses), clock())
    }

    func files(_ c: EtlClient, _ cid: Int64) async throws -> JSONObject {
        let all = try await c.getAll(CourseAssignments.COURSES_PATH)
        guard let course = Queries.usableCourses(all).first(where: { $0.optLong("id") == cid }) else {
            throw EtlException("수강 중인 과목에서 ID \(cid) 를 찾을 수 없습니다. etl_courses로 과목 ID를 확인하세요.")
        }
        var errors: [String] = []
        let listed = try await soft { try await c.getAll("/api/v1/courses/\(cid)/files?per_page=100") }
        let folders = try await soft { try await c.getAll("/api/v1/courses/\(cid)/folders?per_page=100") } ?? []
        let modules: [JSONObject]? = try await soft {
            var out: [JSONObject] = []
            for m in try await c.getAll("/api/v1/courses/\(cid)/modules?include[]=items&per_page=100") {
                if m.optJSONArray("items") == nil && m.optInt("items_count", 1) > 0 {
                    if let items = try await soft({ try await c.getAll("/api/v1/courses/\(cid)/modules/\(m.optLong("id"))/items?per_page=100") }) {
                        m.put("items", JSONArray(items))
                    }
                }
                out.append(m)
            }
            return out
        }
        let assigns = try await soft { try await c.getAll(CourseAssignments.assignmentsPath(cid)) }
        let tomorrow = DayDate(clock().plusSeconds(86400))
        // 공지 API는 기본으로 최근 14일만 주므로 처음부터 조회한다(예전 공지의 첨부도 강의자료다).
        let anns = try await soft { try await c.getAll("/api/v1/announcements?context_codes[]=course_\(cid)&start_date=1970-01-01&end_date=\(tomorrow)&per_page=50") }
        if listed == nil && modules == nil { errors.append("과목 파일 목록과 모듈을 읽지 못했습니다.") }
        if assigns == nil { errors.append("과제 첨부·제출 파일 목록을 읽지 못했습니다.") }
        if anns == nil { errors.append("공지 첨부 파일 목록을 읽지 못했습니다.") }
        let mods = Queries.moduleFiles(modules ?? [])
        let attached = Queries.attachedFiles(assigns ?? [], anns ?? [], cid)
        let listedIds = Set((listed ?? []).map { $0.optLong("id") })
        var missing: [Int64] = []
        for f in mods + attached where !listedIds.contains(f.id) && f.meta == nil && !missing.contains(f.id) { missing.append(f.id) }
        if missing.count > Self.DETAIL_LIMIT { errors.append("파일이 많아 일부 파일의 상세 정보는 받기 요청 때 확인합니다.") }
        var details: [Int64: JSONObject] = [:]
        for fid in missing.prefix(Self.DETAIL_LIMIT) {
            if let o = try await soft({ try await c.get("/api/v1/courses/\(cid)/files/\(fid)") }) as? JSONObject { details[fid] = o }
        }
        return Queries.filesResult(cid, course.str("name"), listed, folders, mods, attached, details, savedFileIds(), errors)
    }

    /// 작업 폴더(etl-files/)에 이미 받아 둔 파일 ID(`{ID}-{이름}`).
    func savedFileIds() -> Set<Int64> {
        let items = (try? FileManager.default.contentsOfDirectory(at: workspace, includingPropertiesForKeys: [.isRegularFileKey])) ?? []
        var out = Set<Int64>()
        for f in items where !f.lastPathComponent.hasSuffix(".part") {
            guard (try? f.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true else { continue }
            let name = f.lastPathComponent
            let head = name.firstIndex(of: "-").map { String(name[..<$0]) } ?? name
            if let id = Int64(head) { out.insert(id) }
        }
        return out
    }

    func assignments(_ c: EtlClient, _ range: AssignmentQuery.Range, _ days: Int, _ includeSubmitted: Bool, _ course: Int64?) async throws -> JSONObject {
        let now = clock()
        let window = AssignmentQuery.window(range, now, days)
        let courses = course == nil ? try await CourseAssignments.currentCourses(c)
            : try Queries.coursesFor(try await c.getAll(CourseAssignments.COURSES_PATH), course)
        let results = try await CourseAssignments.fetch(c, courses)
        let rows = AssignmentQuery.select(results, window, includeSubmitted: includeSubmitted, now: now)
        return JSONObject()
            .put("now", EtlLogic.fmtKst(now))
            .put("range", range.id)
            .put("start", EtlLogic.fmtKst(window.start))
            .put("end", EtlLogic.fmtKst(window.endExclusive.plusSeconds(-60)))
            .put("include_submitted", includeSubmitted)
            .put("courses", JSONArray(results.map { $0.courseName }))
            .put("count", rows.count)
            .put("submitted_count", rows.filter { $0.optBoolean("submitted") }.count)
            .put("assignments", JSONArray(rows))
            .put("errors", JSONArray(results.compactMap { r in r.error.map { "\(r.courseName): \($0)" } }))
    }

    func digest(_ c: EtlClient, _ days: Int, _ annDays: Int) async throws -> JSONObject {
        let now = clock()
        let courses = try await CourseAssignments.currentCourses(c)
        let errors = JSONArray()
        let rows = try await CourseAssignments.fetch(c, courses)

        let horizon = now.plusSeconds(Int64(days) * 86400)
        var upcoming: [(Date?, JSONObject)] = []
        var missing: [(Date?, JSONObject)] = []
        for r in rows {
            let course = r.course
            if let err = r.error { errors.put("\(course.str("name") ?? "null"): \(err)") }
            for a in r.assignments {
                if a.has("published") && !a.optBoolean("published", true) { continue }
                let due = EtlLogic.parseInstant(a.str("due_at"))
                let row = JSONObject()
                    .put("course", course.str("name"))
                    .put("course_id", course.optLong("id"))
                    .put("assignment_id", a.optLong("id"))
                    .put("name", a.str("name") ?? "(제목 없음)")
                    .put("due", EtlLogic.fmtKst(due))
                    .put("remaining", EtlLogic.remaining(due, now))
                    .put("online", EtlLogic.isOnline(a))
                    .put("points", a.isNull("points_possible") ? JSONObject.NULL : a.opt("points_possible"))
                    .put("url", a.str("html_url"))
                if EtlLogic.isMissing(a, now) {
                    missing.append((due, row))
                } else if !EtlLogic.isSubmitted(a), let due, !(due < now), !(due > horizon) {
                    upcoming.append((due, row))
                }
            }
        }
        let byDue: ((Date?, JSONObject), (Date?, JSONObject)) -> Bool = { ($0.0 ?? .distantFuture) < ($1.0 ?? .distantFuture) }
        upcoming = stableSorted(upcoming, byDue)
        missing = stableSorted(missing, byDue)

        let announcements = JSONArray()
        do {
            let start = kstDate(now.plusSeconds(-Int64(annDays) * 86400))
            let end = kstDate(now.plusSeconds(86400))
            if !courses.isEmpty {
                var names: [Int64: String] = [:]
                for co in courses { if let n = co.str("name") { names[co.optLong("id")] = n } }
                let cutoff = now.plusSeconds(-Int64(annDays) * 86400)
                let fetched = try await c.getAll("/api/v1/announcements?\(courseIds(courses))&start_date=\(start)&end_date=\(end)&per_page=50")
                let items = stableSorted(fetched.map { ($0, EtlLogic.parseInstant($0.str("posted_at")) ?? now) }.filter { !($0.1 < cutoff) }) { $0.1 > $1.1 }
                for (it, posted) in items {
                    let code = it.str("context_code") ?? "course_0"
                    let cid = Int64(code.split(separator: "_", omittingEmptySubsequences: false).last.map(String.init) ?? "") ?? 0
                    announcements.put(JSONObject()
                        .put("course", names[cid] ?? String(cid))
                        .put("title", it.str("title"))
                        .put("posted", EtlLogic.fmtKst(posted))
                        .put("url", it.str("html_url") ?? it.str("url"))
                        .put("text", EtlLogic.htmlToText(it.str("message"))))
                }
            }
        } catch let e as EtlAuthException {
            throw e
        } catch let e as EtlException {
            errors.put("공지: \(e.message)")
        }

        return JSONObject()
            .put("now", EtlLogic.fmtKst(now))
            .put("upcoming_days", days)
            .put("courses", JSONArray(courses.map { $0.str("name") as Any? }))
            .put("upcoming", JSONArray(upcoming.map(\.1)))
            .put("missing", JSONArray(missing.map(\.1)))
            .put("announcements", announcements)
            .put("errors", errors)
    }

    func assignment(_ c: EtlClient, _ cid: Int64, _ aid: Int64) async throws -> JSONObject {
        let a = try await c.getObject("/api/v1/courses/\(cid)/assignments/\(aid)?include[]=submission")
        let due = EtlLogic.parseInstant(a.str("due_at"))
        let sub = a.obj("submission")
        return JSONObject()
            .put("id", aid).put("course_id", cid)
            .put("name", a.str("name"))
            .put("due", EtlLogic.fmtKst(due))
            .put("remaining", EtlLogic.remaining(due, clock()))
            .put("lock_at", EtlLogic.parseInstant(a.str("lock_at")).map { EtlLogic.fmtKst($0) })
            .put("points_possible", a.isNull("points_possible") ? JSONObject.NULL : a.opt("points_possible"))
            .put("submission_types", JSONArray(a.strList("submission_types")))
            .put("allowed_extensions", JSONArray(a.strList("allowed_extensions")))
            .put("published", a.optBoolean("published", true))
            .put("url", a.str("html_url"))
            .put("submitted", EtlLogic.isSubmitted(a))
            .put("submission_state", sub?.str("workflow_state"))
            .put("submitted_at", EtlLogic.parseInstant(sub?.str("submitted_at")).map { EtlLogic.fmtKst($0) })
            .put("score", sub?.opt("score"))
            .put("late", sub?.optBoolean("late", false) ?? false)
            .put("missing", sub?.optBoolean("missing", false) ?? false)
            .put("unlock_at", EtlLogic.parseInstant(a.str("unlock_at")).map { EtlLogic.fmtKst($0) })
            .put("allowed_attempts", (a.isNull("allowed_attempts") || !a.has("allowed_attempts")) ? JSONObject.NULL : a.opt("allowed_attempts"))
            .put("description", EtlLogic.htmlToText(a.str("description"), 4000))
    }

    func searchFiles(_ c: EtlClient, _ cid: Int64, _ term: String) async throws -> JSONArray {
        if term.kt.utf16.count < 2 { throw EtlException("검색어는 2글자 이상이어야 합니다") }
        guard let files = try await c.get("/api/v1/courses/\(cid)/files?search_term=\(term.kt.formEncoded)&per_page=50") as? JSONArray else {
            throw EtlException("목록이 아닌 응답")
        }
        let out = JSONArray()
        for i in 0..<files.length() {
            guard let f = files.optJSONObject(i) else { continue }
            out.put(JSONObject()
                .put("id", f.optLong("id"))
                .put("name", f.str("display_name"))
                .put("size", f.opt("size"))
                .put("content_type", f.str("content-type"))
                .put("updated_at", EtlLogic.parseInstant(f.str("updated_at")).map { EtlLogic.fmtKst($0) })
                .put("locked", f.optBoolean("locked_for_user", false)))
        }
        return out
    }

    /// 범용 GET(etl_get). 허용 경로만, 쪽지는 읽음 표시하지 않게, 서명 링크는 가리고, 너무 크면 줄인다.
    func getRaw(_ c: EtlClient, _ path: String, _ all: Bool) async throws -> Any {
        if !EtlLogic.isAllowedGetPath(path) {
            throw EtlException("허용되지 않은 경로입니다(/api/v1/ 아래의 안전한 문자만, 토큰·로그인·도구 실행 경로 제외)")
        }
        let p = Queries.readOnlyPath(path)
        let data: Any = all ? JSONArray(try await c.getAll(p)) : try await c.get(p)
        return try Queries.capAnswer(p, EtlLogic.unsign(data) ?? JSONObject.NULL, Self.MAX_GET_CHARS)
    }

    // MARK: 강의계획서

    /// 강의계획서 = eTL 강의계획서 본문 + 본문이 가리키는 수강편람 강의계획서(cc103ajax, 공개, eTL 토큰 없이).
    func syllabus(_ c: EtlClient, _ cid: Int64, _ attachment: Int?) async throws -> JSONObject {
        let course = try await c.getObject("/api/v1/courses/\(cid)?include[]=syllabus_body")
        let body = course.str("syllabus_body")
        let out = JSONObject().put("course_id", cid).put("course", course.str("name"))
        let text = EtlLogic.unsignText(EtlLogic.htmlToText(body, 12_000))
        if !text.isBlankK { out.put("etl_syllabus_text", text) }
        let linked = Queries.linkedFiles(body, cid)
        if !linked.isEmpty { out.put("linked_files", JSONArray(linked)) }
        let ref = TimetableLogic.catalogRef(body)
        var detail: JSONObject?
        if let ref {
            do {
                let d = try await CatalogClient.syllabus(ref)
                detail = d
                out.put("catalog", TimetableLogic.syllabusJson(d))
            } catch let e as CatalogError {
                out.put("catalog_error", e.message)
            }
        }
        if text.isBlankK && ref == nil && linked.isEmpty {
            out.put("note", "eTL 강의계획서가 비어 있고 수강편람 연결도 없습니다. 강의자료(etl_files)·공지·게시판에 계획서가 있을 수 있습니다.")
        }
        if let attachment {
            let atts = TimetableLogic.syllabusAttachments(detail)
            guard attachment >= 0, attachment < atts.count, let ref else {
                throw EtlException(detail == nil ? "수강편람 강의계획서를 읽지 못해 첨부를 받을 수 없습니다" : "attachment는 0~\(atts.count - 1) 중 하나여야 합니다(첨부 \(atts.count)개)")
            }
            out.put("downloaded", try await fetchSyllabusAttachment(atts[attachment], ref))
        }
        return out
    }

    /// 수강편람 강의계획서 첨부를 비서가 읽을 작업 폴더(`etl-files/syllabus/`)에만 받는다. 사용자에게 파일 카드를 띄우지 않는다.
    private func fetchSyllabusAttachment(_ a: TimetableLogic.SyllabusAttachment, _ ref: [String: String]) async throws -> JSONObject {
        guard let url = TimetableLogic.attachmentUrl(a) else { throw EtlException("첨부 번호가 올바르지 않습니다") }
        func r(_ k: String) -> String { ref[k] ?? "null" }
        let name = EtlLogic.safeName(a.name, "syllabus-\(r("sbjtCd"))")
        let fm = FileManager.default
        try? fm.createDirectory(at: workspace, withIntermediateDirectories: true)
        EtlDownloads.prune(workspace, nowMs: clock().epochMilli)
        let dir = workspace.appendingPathComponent(EtlDownloads.SYLLABUS_DIR, isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let stored = dir.appendingPathComponent(EtlDownloads.fitName("syllabus-\(r("openSchyy"))-\(r("sbjtCd"))-\(r("ltNo"))-\(a.lang)-\(name)"))
        let tmp = dir.appendingPathComponent(stored.lastPathComponent + ".part")
        let bytes: Int64
        do {
            bytes = try await CatalogClient.downloadAttachment(url, to: tmp, maxBytes: 50 << 20)
        } catch {
            try? fm.removeItem(at: tmp)
            throw EtlException(CatalogClient.message(error))
        }
        try? fm.removeItem(at: stored)
        try fm.moveItem(at: tmp, to: stored)
        let ext = stored.lastPathComponent.contains(".") ? (stored.lastPathComponent.split(separator: ".").last.map { String($0).lowercased() } ?? "") : ""
        let hint: String
        if ext == "pdf" {
            hint = "etl_render_pages로 쪽 이미지를 만들어 읽으세요(채팅에 미리보기는 뜨지 않습니다). 이 파일은 비서가 읽기 위한 것이며 사용자에게는 읽은 내용으로만 답합니다"
        } else if ["docx", "pptx", "hwp", "hwpx"].contains(ext) || Self.TEXT_EXT.contains(ext) {
            hint = "etl_read_file로 읽으세요. 이 파일은 비서가 읽기 위한 것이며 사용자에게는 읽은 내용으로만 답합니다"
        } else {
            hint = "이 형식(\(ext.isEmpty ? "?" : ext))은 내용을 읽을 수 없습니다. 원본은 수강편람의 강의계획서 화면에서 직접 받을 수 있다고 안내하세요"
        }
        return JSONObject().put("name", a.name).put("workspace_path", relPath(stored)).put("bytes", bytes).put("hint", hint)
    }

    /// Kotlin `EtlFiles.TEXT_EXT`.
    static let TEXT_EXT: Set<String> = [
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "tex", "yaml", "yml", "ini",
        "c", "h", "cpp", "hpp", "cc", "java", "kt", "py", "m", "js", "ts", "sh", "sql", "r", "v", "sv", "asm",
    ]

    // MARK: 시간표

    func timetable(_ c: EtlClient, _ args: JSONObject) async throws -> JSONObject {
        let w = try await Timetable.weekly(c, timetableStore, nowMs: clock().epochMilli, force: args.optBoolean("refresh", false))
        let out = JSONObject().put("source", "서울대 수강편람(정규 수업) + eTL 캘린더 일정")
        if !w.errors.isEmpty { out.put("errors", JSONArray(w.errors)) }
        let today = DayDate(clock())
        if args.optBoolean("week", false) {
            let upcoming = timetableChanges.load().filter { !($0.date < today) }
            return out.put("week", TimetableLogic.weekJson(w)).put("changes", JSONArray(upcoming.map { ch in
                JSONObject().put("id", ch.id).put("date", ch.date.description).put("kind", ch.kind).put("course", ch.course)
                    .put("start", ch.start ?? JSONObject.NULL).put("end", ch.end ?? JSONObject.NULL)
                    .put("room", ch.room ?? JSONObject.NULL).put("note", ch.note ?? JSONObject.NULL)
            }))
        }
        let s = args.optString("date", "").kt
        let date: DayDate
        if s.isEmpty {
            date = today
        } else {
            guard let d = DayDate.parse(s) else { throw EtlException("date는 YYYY-MM-DD 형식이어야 합니다") }
            date = d
        }
        var events: [TimetableLogic.ClassRow] = []
        do {
            events = try await Timetable.calendarClasses(c, w.courses, date)
        } catch let e as EtlAuthException {
            throw e
        } catch let e as EtlException {
            out.put("calendar_error", e.message)
        }
        let day = TimetableLogic.day(w, date, timetableChanges.load(), events)
        return out.put("date", date.description).put("weekday", day.weekday)
            .put("classes", TimetableLogic.classesJson(day.classes))
            .put("cancelled", TimetableLogic.classesJson(day.cancelled))
    }

    // MARK: 다운로드

    /// 동영상과 숨김 파일은 받지 않는다(교수가 일부러 숨긴 것일 수 있다). 안드로이드는 게시판 첨부(숨김 폴더)를
    /// LearningX로 확인해 받지만 iOS 1단계에는 게시판이 없어 숨김 파일은 모두 거절한다.
    func downloadFile(_ c: EtlClient, _ ref: String, _ saveToPhone: Bool, _ args: JSONObject) async throws -> JSONObject {
        let fid = try EtlLogic.fileId(ref)
        let meta = try await c.getObject("/api/v1/files/\(fid)")
        let type = (meta.str("content-type") ?? "").lowercased()
        if type.hasPrefix("video/") || meta.optString("mime_class") == "video" {
            throw EtlException("동영상은 받지 않습니다. eTL에서 보세요: \(EtlClient.BASE_URL)/files/\(fid)")
        }
        // 숨김 파일은 게시판 첨부일 때만 받는다(LearningX가 그 글의 첨부임을 확인해 준 경우).
        var post: String?
        if meta.optBoolean("hidden_for_user", false) { post = try await boardAttachmentPost(args, fid) }
        let r = try await saveAttachment(c, meta, saveToPhone)
        if let post { r.put("post", post) }
        return r
    }

    /// 파일을 받아 작업 폴더(`etl-files/`)에 먼저 저장하고, [saveToPhone]이면 `Documents/etl/`에도 사본을 둔다. 채팅에는 파일 카드로 알린다.
    func saveAttachment(_ c: EtlClient, _ meta: JSONObject, _ saveToPhone: Bool) async throws -> JSONObject {
        let saved = try await EtlDownloads.saveAttachment(c, meta: meta, saveToUser: saveToPhone, workspace: workspace,
                                                          userDir: userDirectory, now: clock())
        let savedTo = saved.userFile.map { "\(userFolderLabel)/\($0.lastPathComponent)" }
        // 채팅 화면에 파일 카드로 보여 준다(앱 안에서만 전달하고, 모델에게 주는 결과에는 파일 URL을 넣지 않는다).
        EtlEvents.fileSaved(EtlEvents.SavedFile(name: meta.str("display_name") ?? saved.name, mime: saved.mime, size: saved.bytes, file: saved.file,
                                                phoneUri: saved.userFile?.absoluteString, shownPath: savedTo, referenceOnly: !saveToPhone))
        let out = JSONObject()
            .put("id", saved.id)
            .put("name", meta.str("display_name"))
            .put("workspace_path", relPath(saved.file))
            .put("saved_to", savedTo ?? JSONObject.NULL)
            .put("bytes", saved.bytes)
            .put("reused", saved.reused)
            .put("expected_bytes", saved.expectedBytes ?? JSONObject.NULL)
            .put("size_ok", saved.sizeOk)
            .put("sha256", saved.sha256)
        if let w = saved.userError { out.put("warning", w) }
        return out
    }

    // MARK: 제출 확인

    func verify(_ c: EtlClient, _ cid: Int64, _ aid: Int64) async throws -> JSONObject {
        let a = try await c.getObject("/api/v1/courses/\(cid)/assignments/\(aid)")
        let s = try await c.getObject("/api/v1/courses/\(cid)/assignments/\(aid)/submissions/self?include[]=submission_comments&include[]=submission_history")
        let now = clock()
        var ev = EtlLogic.evaluateSubmission(a, s, now)
        let allowedTypes = a.strList("submission_types")
        let allowedExt = a.strList("allowed_extensions").map { e -> String in
            var x = Substring(e.lowercased())
            while x.first == "." { x = x.dropFirst() }
            return String(x)
        }
        let st = s.str("submission_type")

        let files = JSONArray()
        if let attachments = s.optJSONArray("attachments") {
            for i in 0..<attachments.length() {
                guard let att = attachments.optJSONObject(i) else { continue }
                let name = att.str("display_name") ?? att.str("filename") ?? ""
                ev.problems += EtlLogic.checkAttachment(att, allowedExt)
                let row = JSONObject().put("id", att.opt("id")).put("name", name).put("expected_bytes", att.opt("size"))
                if let url = att.str("url"), !att.optBoolean("locked_for_user", false) {
                    // 내려받은 바이트는 저장하지 않고 크기·해시만 본다(임시 파일은 바로 지운다).
                    let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("verify-\(UUID().uuidString).part")
                    defer { try? FileManager.default.removeItem(at: tmp) }
                    do {
                        let d = try await c.download(url, to: tmp)
                        row.put("bytes", d.bytes).put("sha256", d.sha256)
                        let expected: Int64? = (att.has("size") && !att.isNull("size")) ? att.optLong("size") : nil
                        let ok = expected == nil || expected == d.bytes
                        row.put("size_ok", ok)
                        if !ok { ev.problems.append("내려받은 크기가 eTL 기록과 다름: \(name)") }
                    } catch let e as EtlAuthException {
                        throw e
                    } catch let e as EtlException {
                        ev.warnings.append("다운로드 실패 \(name): \(e.message)")
                    }
                }
                files.put(row)
            }
        }
        if ev.submitted && st == "online_upload" && files.length() == 0 { ev.problems.append("파일 업로드 과제인데 첨부 파일이 없음") }

        let comments = JSONArray()
        if let all = s.optJSONArray("submission_comments") {
            for i in max(0, all.length() - 5)..<all.length() {
                guard let cm = all.optJSONObject(i) else { continue }
                comments.put(JSONObject()
                    .put("author", cm.str("author_name"))
                    .put("at", EtlLogic.fmtKst(EtlLogic.parseInstant(cm.str("created_at"))))
                    .put("text", EtlLogic.htmlToText(cm.str("comment"), 400)))
            }
        }
        let history = s.optJSONArray("submission_history")
        return JSONObject()
            .put("verdict", ev.submitted && ev.problems.isEmpty ? "ok" : "problem")
            .put("problems", JSONArray(ev.problems))
            .put("warnings", JSONArray(ev.warnings))
            .put("course_id", cid).put("assignment", a.str("name")).put("url", a.str("html_url"))
            .put("due", EtlLogic.fmtKst(ev.due))
            .put("allowed_types", JSONArray(allowedTypes))
            .put("allowed_extensions", allowedExt.isEmpty ? JSONObject.NULL : JSONArray(allowedExt))
            .put("submitted", ev.submitted)
            .put("state", s.str("workflow_state"))
            .put("submitted_at", ev.submittedAt.map { EtlLogic.fmtKst($0) })
            .put("attempt", s.opt("attempt"))
            .put("attempts_total", history?.length())
            .put("submission_type", st)
            .put("late", s.optBoolean("late", false))
            .put("missing", s.optBoolean("missing", false))
            .put("files", files)
            .put("score", s.opt("score")).put("grade", s.str("grade")).put("points_possible", a.opt("points_possible"))
            .put("comments", comments)
    }
}
