import ETLKit
import NotifyKit
import SwiftUI

/// 내 마감·시험 추가/고치기(Kotlin `DeadlineDialog` + `RepeatInputs`, chat/MyDeadlinesActivity.kt). 공지 초안이면 맨 위에 초안 머리(`schedule-draft`).
/// 마감은 반복 종류마다 필요한 입력만 보인다([DeadlineForm]): 없음 = 날짜+시각, 매일 = 시각(+시작 날짜), 매주 = 요일+시각,
/// 격주 = 요일+시각+이번 주/다음 주부터, 매월 = N일+시각. 시험은 날짜·시작·끝(선택)·장소·과목·준비물·메모이고 반복이 없다. 시간대는 KST.
struct DeadlineEditSheet: View {
    let initial: MyDeadline?
    let countWithoutThis: Int
    let courses: [String]
    let draft: ScheduleDraft?
    let onDismiss: () -> Void
    let onSave: (MyDeadline) -> Void
    let onAskAssistant: (ScheduleDraft) -> Void
    let onCancelClass: (DayDate, String, Int64) -> Void

    private let now: Date
    @State private var kind: MyKind
    @State private var title: String
    @State private var memo: String
    @State private var error: String?
    @State private var form: DeadlineForm.Input
    @State private var endMode: DeadlineForm.EndMode
    @State private var until: DayDate?
    @State private var count: Int
    @State private var examEnd: DayTime?
    @State private var location: String
    @State private var course: String
    @State private var supplies: String
    /// 알림 시각은 "마감 몇 분 전"으로 들고 있다가 저장할 때 시각으로 바꾼다(마감을 옮기면 알림도 같이 따라간다).
    @State private var offsets: [Int64]
    /// 새 시험에서 알림을 손대지 않았으면 저장할 때 시험 기본 알림(전날 20:00, 2시간 전)을 넣는다.
    @State private var remindersTouched: Bool
    @State private var cancelClass = false
    @State private var pick: SchedPick?

    init(initial: MyDeadline?, countWithoutThis: Int, courses: [String], startAsExam: Bool, draft: ScheduleDraft?,
         onDismiss: @escaping () -> Void, onSave: @escaping (MyDeadline) -> Void,
         onAskAssistant: @escaping (ScheduleDraft) -> Void, onCancelClass: @escaping (DayDate, String, Int64) -> Void) {
        self.initial = initial
        self.countWithoutThis = countWithoutThis
        self.courses = courses
        self.draft = draft
        self.onDismiss = onDismiss
        self.onSave = onSave
        self.onAskAssistant = onAskAssistant
        self.onCancelClass = onCancelClass
        let now = Date()
        self.now = now

        // 기본: 내일 23:59(시험은 10:00).
        let startDate: DayDate
        var startTime: DayTime
        if let t = initial?.due ?? draft?.start {
            startDate = DayDate(t)
            startTime = SchedFormat.kstTime(t)
        } else {
            startDate = DayDate.today(now).plusDays(1)
            startTime = draft?.kind == .EXAM ? DayTime.of(10, 0) : DayTime.of(23, 59)
        }
        let k = initial?.kind ?? draft?.kind ?? (startAsExam ? .EXAM : .DEADLINE)
        // 시험으로 시작하면 기본 시각을 오전 10시로(마감 기본값 23:59는 시험에 맞지 않는다).
        if k == .EXAM && initial?.isExam != true && startTime == DayTime.of(23, 59) { startTime = DayTime.of(10, 0) }
        let r0 = initial?.repeat
        _kind = State(initialValue: k)
        _title = State(initialValue: initial?.title ?? draft?.title ?? "")
        _memo = State(initialValue: initial?.memo ?? draft?.memo ?? "")
        _form = State(initialValue: DeadlineForm.Input(
            r0?.freq, startDate, startTime,
            initial.flatMap { $0.repeat != nil ? MyDeadlines.weekdaysOf($0) : nil } ?? [],
            monthDay: startDate.dayOfMonth,
            biweeklyNext: r0?.freq == .BIWEEKLY ? DeadlineForm.biweeklyNextWeek(startDate, now) : false,
            dailyStart: r0?.freq == .DAILY ? startDate : nil))
        _endMode = State(initialValue: r0?.count != nil ? .COUNT : (r0?.until != nil ? .UNTIL : .NONE))
        _until = State(initialValue: r0?.until)
        _count = State(initialValue: r0?.count ?? 10)
        _examEnd = State(initialValue: (initial?.end ?? draft?.end).map(SchedFormat.kstTime))
        _location = State(initialValue: initial?.location ?? "")
        _course = State(initialValue: initial?.course ?? draft?.course ?? "")
        _supplies = State(initialValue: initial?.supplies ?? draft?.supplies ?? "")
        _offsets = State(initialValue: initial.map { d in
            d.reminders.map { Int64(d.due.timeIntervalSince($0) / 60) }.filter { $0 > 0 }.sorted()
        } ?? [])
        _remindersTouched = State(initialValue: initial != nil)
    }

    private struct QuickOffset { let minutes: Int64; let label: String }
    private static let quickOffsets = [QuickOffset(minutes: 10, label: "+10분 전"), QuickOffset(minutes: 60, label: "+1시간 전"),
                                       QuickOffset(minutes: 1440, label: "+하루 전")]

    // MARK: 계산

    private var exam: Bool { kind == .EXAM }

    private var keep: DayDate? {
        guard let i = initial, !exam, i.repeat != nil, DeadlineForm.samePattern(i, form, now) else { return nil }
        return DayDate(i.due)
    }

    private var dueOrNull: Date? {
        exam ? form.date.atTime(form.time.hour, form.time.minute) : DeadlineForm.due(form, now, keep: keep)
    }

    private func repeatOf() -> MyRepeat? {
        guard let f = form.freq else { return nil }
        let days: Set<Weekday> = (f == .WEEKLY || f == .BIWEEKLY) ? form.weekdays : []
        return MyRepeat(f, weekdays: days, until: endMode == .UNTIL ? until : nil, count: endMode == .COUNT ? count : nil)
    }

    private func seriesPreview() -> MyDeadline? {
        dueOrNull.map { MyDeadline(0, "x", $0, repeat: repeatOf()) }
    }

    private func offsetLabel(_ m: Int64) -> String {
        guard let due = dueOrNull else { return "\(m)분 전" }
        let at = EtlLogic.kstParts(due.addingTimeInterval(-Double(m) * 60))
        let hm = String(format: "%02d:%02d", at.hour, at.minute)
        let rel: String
        if m % 1440 == 0 { rel = "\(m / 1440)일 전" }
        else if m % 60 == 0 { rel = "\(m / 60)시간 전" }
        else if m > 60 { rel = "\(m / 60)시간 \(m % 60)분 전" }
        else { rel = "\(m)분 전" }
        return at.day == DayDate(due) ? "\(hm) · \(rel)" : "\(at.day.monthValue)/\(at.day.dayOfMonth) \(hm) · \(rel)"
    }

    private var canSave: Bool { exam || form.freq == nil || dueOrNull != nil }

    private var sheetTitle: String {
        if initial == nil { return exam ? "시험 추가" : "내 마감 추가" }
        return exam ? "시험 고치기" : "내 마감 고치기"
    }

    // MARK: 화면

    var body: some View {
        SchedFormSheet(title: sheetTitle) {
            if let draft { draftHeader(draft) }
            HStack(spacing: 8) {
                ForEach(MyKind.allCases, id: \.self) { k in
                    YMChip(k.label, isActive: kind == k) {
                        kind = k
                        error = nil
                        if k == .EXAM && initial?.isExam != true && form.time == DayTime.of(23, 59) { form.time = DayTime.of(10, 0) }
                    }
                    .accessibilityLabel("종류 \(k.label)" + (kind == k ? ", 선택됨" : ""))
                }
            }
            Spacer().frame(height: 10)
            SchedTextField(placeholder: exam ? "예: 중간고사" : "예: 장학금 서류 제출", text: $title, limit: MyDeadlines.TITLE_MAX)
                .onChange(of: title) { error = nil }
            Spacer().frame(height: 12)
            if exam { examInputs } else { repeatInputs }
            Spacer().frame(height: 12)
            label("알림 (선택)")
            SchedCaption(reminderHint)
            Spacer().frame(height: 6)
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(offsets, id: \.self) { m in
                    YMChip(offsetLabel(m) + "  ✕") {
                        offsets.removeAll { $0 == m }
                        remindersTouched = true
                        error = nil
                    }
                    .accessibilityLabel("알림 \(offsetLabel(m)) 지우기")
                }
                if offsets.count < MyDeadlines.MAX_REMINDERS {
                    ForEach(Self.quickOffsets, id: \.minutes) { q in
                        if !offsets.contains(q.minutes) {
                            YMChip(q.label) {
                                offsets = (offsets + [q.minutes]).sorted()
                                remindersTouched = true
                                error = nil
                            }
                        }
                    }
                    YMChip("+시각 고르기") { pickReminderTime() }
                }
            }
            Spacer().frame(height: 12)
            SchedTextField(placeholder: "메모 (선택)", text: $memo, limit: MyDeadlines.MEMO_MAX)
            if let draft, draft.replacesClass, let day = draft.day {
                // 동영상 강의 공지에 "대면 수업 없음/대체" 단서가 있고 그날을 알 때만.
                Spacer().frame(height: 10)
                Button { cancelClass.toggle() } label: {
                    HStack(spacing: 8) {
                        Image(systemName: cancelClass ? "checkmark.square.fill" : "square")
                            .font(.system(size: 20))
                            .foregroundStyle(cancelClass ? YMColor.brandPrimary : YMColor.labelSecondary)
                        Text("그날(\(Agenda.dateLabel(day))) 대면 수업은 휴강으로 표시")
                            .font(YMFont.body2Regular)
                            .foregroundStyle(YMColor.label)
                            .multilineTextAlignment(.leading)
                    }
                    .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(YMPressStyle())
                .accessibilityLabel("그날 대면 수업은 휴강으로 표시" + (cancelClass ? ", 선택됨" : ""))
            }
            if let error {
                Spacer().frame(height: 8)
                SchedCaption(error, color: YMColor.negativeForeground)
            }
        } buttons: {
            YMTextButton("취소", action: onDismiss)
            YMTextButton("저장", action: save)
        }
        .schedPicker($pick)
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(YMFont.body2Medium)
            .foregroundStyle(YMColor.label)
    }

    @ViewBuilder
    private func draftHeader(_ draft: ScheduleDraft) -> some View {
        // 공지에서 온 초안: 날짜를 하나만 찾았을 때만 넣었고, 저장은 사용자가 누를 때만.
        if !draft.foundDate {
            Text(ScheduleDrafts.ASK_HINT)
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.cautionaryForeground)
                .fixedSize(horizontal: false, vertical: true)
            Spacer().frame(height: 4)
        }
        if !draft.prefill.isEmpty {
            YMTextButton(ScheduleDrafts.ASK_LINK) { onAskAssistant(draft) }
            Text("공지 내용은 이걸 누를 때만 비서(내 ChatGPT 계정)로 가요.")
                .font(YMFont.schedCaption2Regular)
                .foregroundStyle(YMColor.labelSecondary)
        }
        let hint = [draft.foundDate ? ScheduleDrafts.FOUND_HINT : nil, draft.kind == .DEADLINE ? ScheduleDrafts.ASSIGNMENT_HINT : nil]
            .compactMap { $0 }.joined(separator: " ")
        if !hint.isEmpty { SchedCaption(hint, color: YMColor.brandPrimary) }
        Spacer().frame(height: 8)
    }

    private var reminderHint: String {
        if exam {
            return (!remindersTouched && offsets.isEmpty ? "그대로 두면 전날 20:00과 시작 2시간 전에 알려요(응원 문구와 함께). " : "") +
                "정한 시각에 꼭 울려요(방해 금지 시간에도)."
        }
        return "설정의 기본 마감 알림과 별개로, 정한 시각에 꼭 울려요(방해 금지 시간에도)." + (form.freq != nil ? " 반복 마감은 회차마다 울려요." : "")
    }

    // MARK: 시험 입력

    @ViewBuilder
    private var examInputs: some View {
        label("날짜와 시간")
        Spacer().frame(height: 6)
        YMFlowLayout(spacing: 8, lineSpacing: 8) {
            SchedOutlineButton(SchedFormat.dateText(form.date), accessibility: "시험 날짜 \(SchedFormat.dateText(form.date))") {
                pick = .date(form.date) { form.date = $0; error = nil }
            }
            SchedOutlineButton(SchedFormat.timeText(form.time) + " 시작", accessibility: "시작 시각 \(SchedFormat.timeText(form.time))") {
                pick = .time(form.time) { form.time = $0; error = nil }
            }
            SchedOutlineButton(examEnd.map { SchedFormat.timeText($0) + " 끝" } ?? "끝 시각 (선택)",
                               accessibility: "끝 시각 " + (examEnd.map(SchedFormat.timeText) ?? "없음")) {
                pick = .time(examEnd ?? DayTime.of((form.time.hour + 2) % 24, form.time.minute)) { examEnd = $0; error = nil }
            }
            if examEnd != nil {
                YMTextButton("끝 시각 지우기") { examEnd = nil }
            }
        }
        Spacer().frame(height: 10)
        SchedTextField(placeholder: "장소 (선택, 예: 301동 118호)", text: $location, limit: MyDeadlines.LOCATION_MAX)
        Spacer().frame(height: 8)
        SchedTextField(placeholder: "과목 (선택)", text: $course, limit: MyDeadlines.COURSE_MAX)
        if !courses.isEmpty {
            Spacer().frame(height: 6)
            YMFlowLayout(spacing: 6, lineSpacing: 6) {
                ForEach(Array(courses.prefix(12)), id: \.self) { name in
                    YMChip(name, isActive: course == name) { course = course == name ? "" : name }
                }
            }
        }
        Spacer().frame(height: 8)
        SchedTextField(placeholder: "준비물 (선택, 예: 공학용 계산기, 신분증)", text: $supplies, limit: MyDeadlines.SUPPLIES_MAX)
    }

    // MARK: 반복 입력 (Kotlin RepeatInputs)

    private var timeButton: some View {
        SchedOutlineButton(SchedFormat.timeText(form.time), accessibility: "마감 시각 \(SchedFormat.timeText(form.time))") {
            pick = .time(form.time) { form.time = $0; error = nil }
        }
    }

    @ViewBuilder
    private var repeatInputs: some View {
        label("반복")
        Spacer().frame(height: 6)
        YMFlowLayout(spacing: 8, lineSpacing: 8) {
            YMChip("없음", isActive: form.freq == nil) { setForm(DeadlineForm.switchFreq(form, nil, now)) }
            ForEach(MyRepeat.Freq.allCases, id: \.self) { f in
                YMChip(f.label, isActive: form.freq == f) { setForm(DeadlineForm.switchFreq(form, f, now)) }
            }
        }
        Spacer().frame(height: 10)
        switch form.freq {
        case nil:
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                SchedOutlineButton(SchedFormat.dateText(form.date), accessibility: "마감 날짜 \(SchedFormat.dateText(form.date))") {
                    pick = .date(form.date) { form.date = $0; error = nil }
                }
                timeButton
            }
        case .DAILY:
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                timeButton
                YMTextButton(form.dailyStart.map { "시작 \(SchedFormat.dateText($0))" } ?? "시작 날짜 바꾸기") {
                    pick = .date(form.dailyStart ?? DayDate.today()) { form.dailyStart = $0; error = nil }
                }
            }
        case .WEEKLY, .BIWEEKLY:
            YMFlowLayout(spacing: 6, lineSpacing: 6) {
                ForEach(1...7, id: \.self) { i in
                    let wd = Weekday.of(i)
                    let name = SchedFormat.WEEKDAYS[i - 1]
                    let on = form.weekdays.contains(wd)
                    YMChip(name, isActive: on) {
                        if on { form.weekdays.remove(wd) } else { form.weekdays.insert(wd) }
                        error = nil
                    }
                    .accessibilityLabel("\(name)요일" + (on ? ", 선택됨" : ""))
                }
            }
            Spacer().frame(height: 8)
            if form.freq == .BIWEEKLY {
                HStack(spacing: 8) {
                    YMChip("이번 주부터", isActive: !form.biweeklyNext) { form.biweeklyNext = false }
                    YMChip("다음 주부터", isActive: form.biweeklyNext) { form.biweeklyNext = true }
                }
                Spacer().frame(height: 8)
            }
            timeButton
            if form.weekdays.isEmpty {
                SchedCaption("요일을 하나 이상 골라 주세요.", color: YMColor.negativeForeground)
                    .padding(.top, 4)
            }
        case .MONTHLY:
            HStack(spacing: 4) {
                Text("매월").font(YMFont.body2Regular).foregroundStyle(YMColor.label)
                YMTextButton("−") { form.monthDay = form.monthDay <= 1 ? 31 : form.monthDay - 1 }
                Text("\(form.monthDay)일")
                    .font(YMFont.body1Medium)
                    .foregroundStyle(YMColor.label)
                    .accessibilityLabel("매월 \(form.monthDay)일")
                YMTextButton("+") { form.monthDay = form.monthDay >= 31 ? 1 : form.monthDay + 1 }
                Spacer().frame(width: 8)
                timeButton
            }
            if form.monthDay > 28 {
                SchedCaption("\(form.monthDay)일이 없는 달은 그달 마지막 날이 마감이에요.").padding(.top, 4)
            }
        }
        if form.freq != nil { repeatEnd }
    }

    @ViewBuilder
    private var repeatEnd: some View {
        if let first = DeadlineForm.firstLabel(form, now, keep: keep) {
            SchedCaption(first, color: YMColor.brandPrimary).padding(.top, 6)
        }
        Spacer().frame(height: 10)
        label("끝")
        Spacer().frame(height: 6)
        YMFlowLayout(spacing: 8, lineSpacing: 8) {
            YMChip("끝 없음", isActive: endMode == .NONE) { setEnd(.NONE) }
            YMChip("날짜까지", isActive: endMode == .UNTIL) { setEnd(.UNTIL) }
            YMChip("횟수", isActive: endMode == .COUNT) { setEnd(.COUNT) }
        }
        switch endMode {
        case .UNTIL:
            Spacer().frame(height: 8)
            SchedOutlineButton(until.map { SchedFormat.dateText($0) + "까지" } ?? "끝 날짜 고르기",
                               accessibility: "끝 날짜 " + (until.map(SchedFormat.dateText) ?? "없음")) {
                pick = .date(until ?? form.date.plusMonths(1)) { until = $0; error = nil }
            }
        case .COUNT:
            Spacer().frame(height: 8)
            HStack(spacing: 4) {
                YMTextButton("−") { setCount(count - 1) }
                Text("\(count)번")
                    .font(YMFont.body1Medium)
                    .foregroundStyle(YMColor.label)
                    .accessibilityLabel("총 \(count)번")
                YMTextButton("+") { setCount(count + 1) }
                YMTextButton("+10") { setCount(count + 10) }
            }
        case .NONE:
            EmptyView()
        }
        if let s = seriesPreview() {
            SchedCaption(MyDeadlines.endSummary(s)).padding(.top, 6)
        }
        if keep != nil {
            SchedCaption("반복 모양이 그대로면 시작 날짜는 바뀌지 않아요. 고치면 반복 전체에 적용돼요.").padding(.top, 4)
        }
    }

    // MARK: 동작

    private func setForm(_ f: DeadlineForm.Input) {
        form = f
        error = nil
    }

    private func setEnd(_ mode: DeadlineForm.EndMode) {
        if let s = seriesPreview() {
            let r = DeadlineForm.switchEnd(s, endMode, mode, until, count)
            if mode == .UNTIL { until = r.until ?? until ?? form.date.plusMonths(1) }
            if mode == .COUNT { count = r.count ?? count }
        }
        endMode = mode
        error = nil
    }

    private func setCount(_ n: Int) {
        count = min(max(n, 1), MyDeadlines.COUNT_MAX)
        error = nil
    }

    private func pickReminderTime() {
        guard let due = dueOrNull else { return }
        let initialTime = DayTime.of((form.time.hour + 23) % 24, form.time.minute)
        pick = .time(initialTime) { picked in
            var at = DayDate(due).atTime(picked.hour, picked.minute)
            if !(at < due) { at = at.addingTimeInterval(-86_400) } // 마감보다 늦으면 전날 그 시각
            let m = Int64(due.timeIntervalSince(at) / 60)
            if m > 0 && !offsets.contains(m) { offsets = (offsets + [m]).sorted() }
            remindersTouched = true
            error = nil
        }
    }

    private func save() {
        if !canSave { error = "요일을 하나 이상 골라 주세요"; return }
        let trimmed = SchedFormat.trim(title)
        let titleText = trimmed.isEmpty ? (exam ? "시험" : "") : trimmed
        if let err = MyDeadlines.validate(titleText, memo, countWithoutThis) { error = err; return }
        guard let due = dueOrNull else { error = "첫 마감을 정할 수 없어요"; return }
        let reminders = exam && !remindersTouched && offsets.isEmpty
            ? MyDeadlines.defaultExamReminders(due, Date())
            : MyDeadlines.normalizeReminders(offsets.map { due.addingTimeInterval(-Double($0) * 60) })
        if let err = MyDeadlines.validateReminders(reminders, due) { error = err; return }
        let rep = exam ? nil : repeatOf()
        let repeating = rep != nil
        let end: Date? = exam ? examEnd.map { e in
            let t = form.date.atTime(e.hour, e.minute)
            return t > due ? t : t.addingTimeInterval(86_400)
        } : nil
        let new = MyDeadline(
            id: (initial != nil && initial!.due == due) ? initial!.id : SchedFormat.nowMs(),
            title: titleText,
            due: due,
            memo: SchedFormat.trim(memo),
            done: (repeating || exam) ? false : (initial?.done ?? false),
            reminders: reminders,
            repeat: rep,
            // 회차별 완료·건너뛰기는 반복을 고쳐도 날짜 기준으로 그대로 둔다.
            doneDates: repeating ? (initial?.doneDates ?? []) : [],
            skipDates: repeating ? (initial?.skipDates ?? []) : [],
            kind: kind,
            end: end,
            location: exam ? SchedFormat.trim(location) : "",
            course: exam ? SchedFormat.trim(course) : "",
            supplies: exam ? MyDeadlines.normalizeSupplies(supplies) : "",
            sourceAnnouncementId: draft?.announcementId ?? initial?.sourceAnnouncementId
        )
        if let err = MyDeadlines.validateRepeat(new) { error = err; return }
        if let err = MyDeadlines.validateExam(new) { error = err; return }
        if cancelClass, let d = draft, let day = d.day { onCancelClass(day, d.course, d.announcementId) }
        onSave(new)
    }
}
