import Foundation
import ETLKit
import NotifyKit

// 폰 안의 데이터 쓰기(내 마감, 보강·휴강). eTL에는 아무것도 보내지 않는다.
// 모든 쓰기는 화면 승인([WriteApprovalGate])을 받은 뒤 승인 잠금 안에서 다시 확인하고 저장한다.
// 저장하면 MyDeadlineStore.onChanged·EtlEvents.dataChanged로 앱이 알림 예약을 다시 계산한다(안드로이드 rescheduleNotify 자리).
extension Toolbox {

    /// reminders(문자열 배열: "YYYY-MM-DD HH:MM" 또는 "HH:MM")와 remind_before_minutes(정수 배열)를 알림 시각으로. 둘 다 없으면 nil.
    private func reminderArgs(_ args: JSONObject, _ due: Date) throws -> [Date]? {
        if !args.has("reminders") && !args.has("remind_before_minutes") { return nil }
        var out: [Date] = []
        if let a = args.optJSONArray("reminders") {
            for i in 0..<a.length() {
                let s = a.optString(i)
                guard let r = MyDeadlines.parseReminder(s, due) else {
                    throw EtlException("알림 시각 \"\(s)\"을(를) 읽을 수 없습니다. \"YYYY-MM-DD HH:MM\" 또는 \"HH:MM\"으로 주세요")
                }
                out.append(r)
            }
        }
        if let a = args.optJSONArray("remind_before_minutes") {
            for i in 0..<a.length() {
                let m = a.optLong(i)
                if m <= 0 || m > 60 * 24 * 30 { throw EtlException("remind_before_minutes는 1~43200 사이 분이어야 합니다") }
                out.append(due.plusSeconds(-m * 60))
            }
        }
        let list = MyDeadlines.normalizeReminders(out)
        if let err = MyDeadlines.validateReminders(list, due) { throw EtlException(err) }
        return list
    }

    /// kind 인자. 없으면 [old].
    private func kindArg(_ args: JSONObject, _ old: MyKind) throws -> MyKind {
        guard let s = str(args, "kind") else { return old }
        guard let k = MyKind.parse(s) else { throw EtlException("kind는 deadline 또는 exam이어야 합니다") }
        return k
    }

    /// 시험인데 반복을 달라고 하면 거절한다.
    private func rejectExamRepeat(_ args: JSONObject) throws {
        let r = str(args, "repeat")?.kt.lowercased()
        if (r != nil && r != "" && r != "none") || args.has("weekdays") { throw EtlException("시험은 반복할 수 없습니다") }
    }

    /// 시험 끝: "YYYY-MM-DD HH:MM" 또는 시작 날짜의 "HH:MM". 빈 문자열이면 nil(지움). 인자가 없으면 [old].
    private func endArg(_ args: JSONObject, _ start: Date, _ old: Date?) throws -> Date? {
        if !args.has("end") { return old }
        let t = str(args, "end")?.kt ?? ""
        if t.isEmpty || t == "none" { return nil }
        guard let e = MyDeadlines.parseEnd(t, start) else { throw EtlException("end는 \"YYYY-MM-DD HH:MM\" 또는 \"HH:MM\"이어야 합니다") }
        return e
    }

    /// repeat·weekdays·until·count 인자를 반복 규칙으로. 모두 없으면 [old] 그대로. until과 count는 하나만 받는다(주면 다른 쪽은 지운다).
    private func repeatArgs(_ args: JSONObject, _ old: MyRepeat?) throws -> MyRepeat? {
        if !args.has("repeat") && !args.has("weekdays") && !args.has("until") && !args.has("count") { return old }
        let untilText = str(args, "until")?.kt ?? ""
        if let err = MyDeadlines.repeatEndConflict(hasUntil: args.has("until") && !untilText.isEmpty && untilText != "none",
                                                   hasCount: args.has("count") && !args.isNull("count") && args.optInt("count", 0) != 0) {
            throw EtlException(err)
        }
        let freqId = str(args, "repeat")?.kt.lowercased()
        let freq: MyRepeat.Freq
        if freqId == "none" || freqId == "" {
            return nil
        } else if let freqId {
            guard let f = MyRepeat.Freq.parse(freqId) else { throw EtlException("repeat는 none, daily, weekly, biweekly, monthly 중 하나여야 합니다") }
            freq = f
        } else if let old {
            freq = old.freq
        } else if args.has("weekdays") {
            freq = .WEEKLY
        } else {
            throw EtlException("until·count는 반복 마감에만 줄 수 있습니다. repeat도 주세요")
        }
        var weekdays: Set<Weekday> = old?.weekdays ?? []
        if args.has("weekdays") {
            guard let a = args.optJSONArray("weekdays") else { throw EtlException("weekdays는 [\"mon\", \"wed\"] 같은 배열이어야 합니다") }
            weekdays = []
            for i in 0..<a.length() {
                guard let w = MyDeadlines.parseWeekday(a.optString(i)) else {
                    throw EtlException("요일 \"\(a.optString(i))\"을(를) 읽을 수 없습니다. mon~sun으로 주세요")
                }
                weekdays.insert(w)
            }
        }
        let until: DayDate?
        if args.has("until") {
            let u = str(args, "until")?.kt
            if let u, !u.isEmpty, u != "none" {
                guard let d = DayDate.parse(u) else { throw EtlException("until은 YYYY-MM-DD 형식이어야 합니다") }
                until = d
            } else {
                until = nil
            }
        } else if args.has("count") {
            until = nil
        } else {
            until = old?.until
        }
        let count: Int?
        if args.has("count") {
            let v: Int?
            if args.isNull("count") {
                v = 0
            } else {
                switch args.opt("count") {
                case let n as Int64: v = Int(clamping: n)
                case let n as Int: v = n
                case let n as Double: v = n.isFinite && abs(n) < 2e9 ? Int(n) : nil
                case let s as String: v = Int(s.kt)
                default: v = nil
                }
            }
            guard let v else { throw EtlException("count는 정수(총 회차 수)여야 합니다") }
            if v == 0 {
                count = nil
            } else if (1...MyDeadlines.COUNT_MAX).contains(v) {
                count = v
            } else {
                throw EtlException("count는 1~\(MyDeadlines.COUNT_MAX) 사이여야 합니다")
            }
        } else if args.has("until") {
            count = nil
        } else {
            count = old?.count
        }
        let usesWeekdays = freq == .WEEKLY || freq == .BIWEEKLY
        return MyRepeat(freq, weekdays: usesWeekdays ? weekdays : [], until: until, count: count)
    }

    private func occurrenceArg(_ args: JSONObject) throws -> DayDate? {
        guard let s = str(args, "occurrence")?.kt, !s.isEmpty else { return nil }
        guard let d = DayDate.parse(s) else { throw EtlException("occurrence는 YYYY-MM-DD 형식이어야 합니다") }
        return d
    }

    private func deadlineJson(_ d: MyDeadline) -> JSONObject {
        let o = JSONObject().put("ok", true).put("id", d.id).put("kind", d.kind.id).put("title", d.title)
            .put("due_kst", EtlLogic.fmtKst(d.due)).put("done", d.done)
        if d.isExam {
            o.put("start_kst", EtlLogic.fmtKst(d.due))
            if let e = d.end { o.put("end_kst", EtlLogic.fmtKst(e)) }
            if !d.location.isEmpty { o.put("location", d.location) }
            if !d.course.isEmpty { o.put("course", d.course) }
            if !d.supplies.isEmpty { o.put("supplies", d.supplies) }
        }
        o.put("reminders_kst", JSONArray(d.reminders.map { EtlLogic.fmtKst($0) }))
        if d.repeat != nil { o.put("repeat", MyDeadlines.repeatJson(d).put("label", MyDeadlines.repeatLabel(d))) }
        return o
    }

    func myDeadlineAdd(_ args: JSONObject) async throws -> JSONObject {
        let store = myDeadlines
        let kind = try kindArg(args, .DEADLINE)
        let exam = kind == .EXAM
        let title = (str(args, "title") ?? "").kt.nilIfEmpty ?? (exam ? "시험" : "")
        let memo = (str(args, "memo") ?? "").kt
        if let err = MyDeadlines.validate(title, memo, store.load().count) { throw EtlException(err) }
        guard let due = MyDeadlines.parseDue(str(args, exam && args.has("start") ? "start" : "due")) else {
            throw EtlException(exam ? "시험은 start(\"YYYY-MM-DD HH:MM\", KST)가 필요합니다" : "due는 \"YYYY-MM-DD HH:MM\"(KST) 형식이어야 합니다")
        }
        if exam { try rejectExamRepeat(args) }
        let now = clock()
        let reminders = try reminderArgs(args, due) ?? (exam ? MyDeadlines.defaultExamReminders(due, now) : [])
        let d = MyDeadline(now.epochMilli, title, due, memo, reminders: reminders, repeat: exam ? nil : try repeatArgs(args, nil),
                           kind: kind, end: exam ? try endArg(args, due, nil) : nil,
                           location: exam ? (str(args, "location") ?? "").kt : "",
                           course: exam ? (str(args, "course") ?? "").kt : "",
                           supplies: exam ? MyDeadlines.normalizeSupplies(str(args, "supplies")) : "",
                           sourceAnnouncementId: try optId(args, "source_announcement_id"))
        if let err = MyDeadlines.validateRepeat(d) { throw EtlException(err) }
        if let err = MyDeadlines.validateExam(d) { throw EtlException(err) }
        try await approve("\(d.kind.label) 추가", deadlinePreview(d)) {
            if let err = MyDeadlines.validate(d.title, d.memo, store.load().count) { throw EtlException(err) }
            store.upsert(d)
        }
        EtlEvents.notice((exam ? "📝 시험 추가: " : "📌 내 마감에 추가: ") + MyDeadlines.line(d) +
            (reminders.isEmpty ? "" : " · 🔔 \(MyDeadlines.reminderLabel(d))") + " · 서랍 → 내 일정에서 고치거나 지울 수 있어요")
        return deadlineJson(d)
    }

    func myDeadlineUpdate(_ args: JSONObject) async throws -> JSONObject {
        let store = myDeadlines
        let idv = try id(args, "id")
        guard let old = store.load().first(where: { $0.id == idv }) else {
            throw EtlException("내 마감에서 id \(idv) 를 찾을 수 없습니다. my_deadlines로 확인하세요.")
        }
        let title = str(args, "title")?.kt ?? old.title
        let memo = str(args, "memo")?.kt ?? old.memo
        if let err = MyDeadlines.validate(title, memo, 0) { throw EtlException(err) }
        let kind = try kindArg(args, old.kind)
        let exam = kind == .EXAM
        let dueText = (exam ? str(args, "start") : nil) ?? str(args, "due")
        let due: Date
        if let dueText {
            guard let d = MyDeadlines.parseDue(dueText) else { throw EtlException("due(시험은 start)는 \"YYYY-MM-DD HH:MM\"(KST) 형식이어야 합니다") }
            due = d
        } else {
            due = old.due
        }
        if exam { try rejectExamRepeat(args) }
        let rep = exam ? nil : try repeatArgs(args, old.repeat)
        // 알림 시각: 새로 주면 바꾸고, 안 주면 그대로(마감이 바뀌어 마감보다 늦어진 것은 뺀다).
        // 반복 마감은 "마감 몇 분 전"이 회차마다 따라가므로 마감을 옮기면 알림도 같은 만큼 옮긴다(화면과 같다).
        let shift = due.timeIntervalSince(old.due)
        let reminders = try reminderArgs(args, due)
            ?? ((rep != nil || exam) ? old.reminders.map { $0.addingTimeInterval(shift) } : old.reminders).filter { $0 < due }
        // 마감 시각이 바뀌면 새 ID로 저장한다(예전 시각 기준 알림 기록이 새 알림을 막지 않게, 화면과 같은 규칙).
        var new = old
        new.id = due != old.due ? clock().epochMilli : old.id
        new.title = title
        new.due = due
        new.memo = memo
        new.reminders = reminders
        new.repeat = rep
        new.doneDates = rep == nil ? [] : old.doneDates
        new.skipDates = rep == nil ? [] : old.skipDates
        new.done = rep == nil ? old.done : false
        new.kind = kind
        // 시작을 옮기고 끝을 주지 않으면 시험 시간 길이를 그대로 둔다.
        new.end = !exam ? nil : try endArg(args, due, old.end?.addingTimeInterval(shift))
        new.location = !exam ? "" : (str(args, "location")?.kt ?? old.location)
        new.course = !exam ? "" : (str(args, "course")?.kt ?? old.course)
        new.supplies = !exam ? "" : (str(args, "supplies").map { MyDeadlines.normalizeSupplies($0) } ?? old.supplies)
        if let err = MyDeadlines.validateRepeat(new) { throw EtlException(err) }
        if let err = MyDeadlines.validateExam(new) { throw EtlException(err) }
        var what = "수정"
        if args.has("done") {
            let done = args.optBoolean("done")
            let now = clock()
            if rep == nil {
                new.done = done
                if done != old.done { what = done ? "완료 표시" : "완료 취소" }
            } else {
                // 반복 마감: 한 회차만. 날짜가 없으면 최근 놓친 회차, 없으면 다음 회차.
                let today = DayDate(now)
                let picked = try occurrenceArg(args)
                    ?? (done ? (MyDeadlines.lastMissed(new, now) ?? MyDeadlines.nextOccurrence(new, now))?.occurrence
                        : new.doneDates.filter { !($0 > today) }.max())
                guard let date = picked else {
                    throw EtlException(done ? "완료로 표시할 회차가 없습니다. occurrence로 날짜를 주세요" : "완료를 취소할 회차가 없습니다. occurrence로 날짜를 주세요")
                }
                if !MyDeadlines.matches(new, date) { throw EtlException("\(date) 는 이 반복 마감의 회차가 아닙니다. my_deadlines의 occurrence를 쓰세요") }
                new = MyDeadlines.setDone(new, date, done, now)
                what = (done ? "완료 표시" : "완료 취소") + "(\(Agenda.dateLabel(date)) 회차)"
            }
        }
        let saved = new
        try await approve("\(saved.kind.label) \(what)", "변경 전\n\(deadlinePreview(old))\n\n변경 후\n\(deadlinePreview(saved))") {
            if store.load().first(where: { $0.id == old.id }) != old { throw EtlException("확인하는 동안 일정이 바뀌었어요. 다시 조회한 뒤 요청해 주세요.") }
            if saved.id != old.id { store.delete(old.id) }
            store.upsert(saved)
        }
        EtlEvents.notice((exam ? "📝 시험 \(what): " : "📌 내 마감 \(what): ") + MyDeadlines.line(saved) +
            (saved.reminders.isEmpty ? "" : " · 🔔 \(MyDeadlines.reminderLabel(saved))"))
        return deadlineJson(saved)
    }

    func myDeadlineDelete(_ args: JSONObject) async throws -> JSONObject {
        let store = myDeadlines
        let idv = try id(args, "id")
        guard let old = store.load().first(where: { $0.id == idv }) else {
            throw EtlException("내 마감에서 id \(idv) 를 찾을 수 없습니다. my_deadlines로 확인하세요.")
        }
        if let date = try occurrenceArg(args), old.repeat != nil {
            if !MyDeadlines.matches(old, date) { throw EtlException("\(date) 는 이 반복 마감의 회차가 아닙니다. my_deadlines의 occurrence를 쓰세요") }
            let now = clock()
            try await approve("반복 일정 한 회차 삭제", "\(deadlinePreview(old))\n\n삭제할 회차: \(date) (다른 회차는 유지)", destructive: true) {
                if store.load().first(where: { $0.id == old.id }) != old { throw EtlException("확인하는 동안 일정이 바뀌었어요. 다시 조회해 주세요.") }
                store.upsert(MyDeadlines.skip(old, date, now))
            }
            EtlEvents.notice("🗑 내 마감에서 한 회차 지움: \(old.title) · \(Agenda.dateLabel(date))")
            return JSONObject().put("ok", true).put("deleted", old.title).put("occurrence", date.description)
        }
        try await approve("\(old.kind.label) 삭제", deadlinePreview(old) + (old.repeat != nil ? "\n\n반복 일정 전체를 삭제해요." : ""), destructive: true) {
            if store.load().first(where: { $0.id == old.id }) != old { throw EtlException("확인하는 동안 일정이 바뀌었어요. 다시 조회해 주세요.") }
            store.delete(idv)
        }
        EtlEvents.notice("🗑 내 마감에서 지움: \(MyDeadlines.line(old))")
        return JSONObject().put("ok", true).put("deleted", old.title)
    }

    func timetableChangeAdd(_ args: JSONObject) async throws -> JSONObject {
        let kind = str(args, "kind")?.kt ?? ""
        if kind != "보강" && kind != "휴강" { throw EtlException("kind는 \"보강\" 또는 \"휴강\"이어야 합니다") }
        guard let date = str(args, "date").flatMap({ DayDate.parse($0.kt) }) else { throw EtlException("date는 YYYY-MM-DD 형식이어야 합니다") }
        let courseText = (str(args, "course") ?? "").kt.take16(60)
        let start = TimetableLogic.normTime(str(args, "start"))
        let end = TimetableLogic.normTime(str(args, "end"))
        if let err = TimetableLogic.validateChange(kind, courseText, start, end) { throw EtlException(err) }
        // 과목명이 시간표의 과목과 맞으면 그 과목에 묶는다(이름이 조금 달라도 휴강이 정확히 걸리게).
        let course = timetableStore.load()?.courses.first { TimetableLogic.sameCourse(courseText, $0.name) }
        let ch = TimetableLogic.Change(
            clock().epochMilli, date, kind, course.map { TimetableLogic.shortName($0.name) } ?? courseText,
            courseId: course?.courseId, start: start, end: kind == "보강" ? end : nil,
            room: str(args, "room")?.kt.take16(40).nilIfEmpty, note: str(args, "note")?.kt.take16(100).nilIfEmpty,
            sourceAnnouncementId: try optId(args, "source_announcement_id"))
        let changes = timetableChanges
        try await approve("시간표에 \(kind) 추가", changePreview(ch)) { changes.add(ch) }
        let wd = TimetableLogic.weekdayOf(date)
        EtlEvents.notice("🗓 시간표에 \(kind) 추가: \(date.monthValue)/\(date.dayOfMonth)(\(wd)) \(ch.course)" +
            (ch.start.map { " \($0)" + (ch.end.map { e in "~\(e)" } ?? "") } ?? "") + " · 서랍 → 시간표에서 지울 수 있어요")
        return JSONObject().put("ok", true).put("id", ch.id).put("course", ch.course).put("matched_course", course != nil)
    }

    func timetableChangeDelete(_ args: JSONObject) async throws -> JSONObject {
        let store = timetableChanges
        let idv = try id(args, "id")
        guard let old = store.load().first(where: { $0.id == idv }) else {
            throw EtlException("보강·휴강에서 id \(idv) 를 찾을 수 없습니다. etl_timetable(week=true)로 확인하세요.")
        }
        try await approve("시간표에서 \(old.kind) 삭제", changePreview(old), destructive: true) {
            if store.load().first(where: { $0.id == old.id }) != old { throw EtlException("확인하는 동안 시간표가 바뀌었어요. 다시 조회해 주세요.") }
            store.delete(idv)
        }
        EtlEvents.notice("🗑 시간표에서 \(old.kind) 지움: \(old.date.monthValue)/\(old.date.dayOfMonth) \(old.course)")
        return JSONObject().put("ok", true)
    }

    /// 승인 창 본문(바뀔 내용). 시각은 KST `yyyy-MM-dd HH:mm`.
    func deadlinePreview(_ d: MyDeadline) -> String {
        func fmt(_ t: Date) -> String {
            let p = EtlLogic.kstParts(t)
            return "\(p.day) " + String(format: "%02d:%02d", p.hour, p.minute)
        }
        var lines: [String] = [MyDeadlines.line(d)]
        lines.append("\(d.isExam ? "시작" : "마감"): \(fmt(d.due)) (한국 시간)")
        if let e = d.end { lines.append("끝: \(fmt(e)) (한국 시간)") }
        if let u = d.repeat?.until { lines.append("반복 끝 날짜: \(u)") }
        let export = CalendarExportStore().settings()
        if export.enabled && export.includeMine {
            let destination = export.target == .LOCAL ? "이 기기의 영민이 캘린더" : "캘린더 ‘\(export.googleCalendarName)’(계정 서버에 동기화)"
            lines.append("자동 연동: \(destination) 에도 반영돼요.")
        }
        if !d.course.isBlankK { lines.append("과목: \(d.course)") }
        if !d.location.isBlankK { lines.append("장소: \(d.location)") }
        if !d.supplies.isBlankK { lines.append("준비물: \(d.supplies)") }
        if !d.memo.isBlankK { lines.append("메모: \(d.memo)") }
        let label = MyDeadlines.reminderLabel(d)
        lines.append("알림: " + (label.isEmpty ? "직접 지정한 알림 없음" : label))
        lines.append("완료: " + (d.done ? "완료함" : "미완료"))
        if !d.doneDates.isEmpty { lines.append("완료 회차: \(d.doneDates.sorted().map(\.description).joined(separator: ", "))") }
        if !d.skipDates.isEmpty { lines.append("건너뛴 회차: \(d.skipDates.sorted().map(\.description).joined(separator: ", "))") }
        return lines.joined(separator: "\n").kt
    }

    func changePreview(_ ch: TimetableLogic.Change) -> String {
        var lines = ["\(ch.course) · \(ch.kind) · \(ch.date)"]
        lines.append("시간: \(ch.start ?? "그날 전체")\(ch.end.map { "~\($0)" } ?? "") (한국 시간)")
        if let r = ch.room { lines.append("장소: \(r)") }
        if let n = ch.note { lines.append("메모: \(n)") }
        return lines.joined(separator: "\n").kt
    }

    /// 폰 안의 '내 마감'. eTL에 요청하지 않는다(토큰이 없어도 된다).
    func myDeadlinesList(_ args: JSONObject) -> JSONObject {
        let now = clock()
        let rangeId = args.optString("range", "all").kt.lowercased()
        let window = (rangeId == "all" || rangeId.isEmpty) ? nil
            : AssignmentQuery.window(AssignmentQuery.Range.parse(rangeId), now, args.optInt("days", 7))
        return MyDeadlines.toolResult(myDeadlines.load(), now, window, includeDone: args.optBoolean("include_done", false))
    }
}
