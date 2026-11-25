import ETLKit
import NotifyKit
import SwiftUI

/// 보강·휴강 공지 초안 → 추가 창의 처음 값(Kotlin `TimetableDraftLogic`).
enum TimetableDraftLogic {
    struct Prefill {
        var kind: String
        var course: TimetableLogic.CourseMeetings?
        var courseText: String
        var date: DayDate?
        var start: DayTime?
        var end: DayTime?
        var note: String
    }

    /// 과목은 시간표의 과목과 이름이 맞으면 그 과목으로, 아니면 글로. 보강의 시각은 공지에서 하나만 찾았을 때만.
    static func prefill(_ d: ScheduleDraft, _ courses: [TimetableLogic.CourseMeetings]) -> Prefill {
        let kind = d.category == .MAKEUP ? "보강" : "휴강"
        let course = courses.first { TimetableLogic.sameCourse(d.course, $0.name) }
        let start = kind == "보강" ? d.start.map(SchedFormat.kstTime) : nil
        let end = kind == "보강" ? d.end.map(SchedFormat.kstTime) : nil
        return Prefill(kind: kind, course: course, courseText: d.course, date: d.day ?? d.start.map { DayDate($0) },
                       start: start, end: end, note: SchedFormat.take(d.memo, 100))
    }
}

/// 보강·휴강 추가(Kotlin `ChangeDialog`). 고치기는 없다(지우고 다시 추가).
struct TimetableChangeSheet: View {
    let courses: [TimetableLogic.CourseMeetings]
    let draft: ScheduleDraft?
    let onDismiss: () -> Void
    let onSave: (TimetableLogic.Change) -> Void
    let onAskAssistant: (ScheduleDraft) -> Void

    @State private var kind: String
    @State private var course: TimetableLogic.CourseMeetings?
    @State private var courseText: String
    @State private var date: DayDate
    @State private var start: DayTime?
    @State private var end: DayTime?
    @State private var room = ""
    @State private var note: String
    @State private var error: String?
    @State private var pick: SchedPick?
    /// 과목 칩을 누를 때 입력칸을 비우는 동안 `onChange`가 고른 과목을 지우지 않게.
    @State private var suppressTextChange = false

    init(courses: [TimetableLogic.CourseMeetings], draft: ScheduleDraft?, onDismiss: @escaping () -> Void,
         onSave: @escaping (TimetableLogic.Change) -> Void, onAskAssistant: @escaping (ScheduleDraft) -> Void) {
        self.courses = courses
        self.draft = draft
        self.onDismiss = onDismiss
        self.onSave = onSave
        self.onAskAssistant = onAskAssistant
        let pre = draft.map { TimetableDraftLogic.prefill($0, courses) }
        _kind = State(initialValue: pre?.kind ?? "휴강")
        _course = State(initialValue: pre?.course)
        _courseText = State(initialValue: pre?.course == nil ? (pre?.courseText ?? "") : "")
        _date = State(initialValue: pre?.date ?? DayDate.today())
        _start = State(initialValue: pre?.start)
        _end = State(initialValue: pre?.end)
        _note = State(initialValue: pre?.note ?? "")
    }

    private func hm(_ t: DayTime?) -> String? { t.map(SchedFormat.timeText) }

    var body: some View {
        SchedFormSheet(title: "보강·휴강 추가") {
            if let draft {
                if !draft.foundDate {
                    Text(ScheduleDrafts.ASK_HINT)
                        .font(YMFont.body2Medium)
                        .foregroundStyle(YMColor.cautionaryForeground)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer().frame(height: 4)
                } else {
                    SchedCaption(ScheduleDrafts.FOUND_HINT, color: YMColor.brandPrimary)
                }
                if !draft.prefill.isEmpty {
                    YMTextButton(ScheduleDrafts.ASK_LINK) { onAskAssistant(draft) }
                    Text("공지 내용은 이걸 누를 때만 비서(내 ChatGPT 계정)로 가요.")
                        .font(YMFont.schedCaption2Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                }
                Spacer().frame(height: 8)
            }
            HStack(spacing: 8) {
                ForEach(["휴강", "보강"], id: \.self) { k in
                    YMChip(k, isActive: kind == k) { kind = k; error = nil }
                }
            }
            Spacer().frame(height: 12)
            Text("과목").font(YMFont.body2Medium).foregroundStyle(YMColor.label)
            Spacer().frame(height: 6)
            if !courses.isEmpty {
                YMFlowLayout(spacing: 8, lineSpacing: 8) {
                    ForEach(courses, id: \.courseId) { co in
                        YMChip(TimetableLogic.shortName(co.name), isActive: course?.courseId == co.courseId) { selectCourse(co) }
                    }
                }
                Spacer().frame(height: 6)
            }
            SchedTextField(placeholder: courses.isEmpty ? "과목명" : "목록에 없으면 직접 입력", text: $courseText, limit: 60)
                .onChange(of: courseText) { _, v in
                    if suppressTextChange { suppressTextChange = false; return }
                    if !SchedFormat.trim(v).isEmpty { course = nil }
                    error = nil
                }
            Spacer().frame(height: 12)
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                SchedOutlineButton("\(date.monthValue)/\(date.dayOfMonth)(\(TimetableLogic.weekdayOf(date)))") {
                    pick = .date(date) { date = $0; error = nil }
                }
                if kind == "보강" {
                    SchedOutlineButton(hm(start) ?? "시작") {
                        pick = .time(start ?? DayTime.of(9, 0)) { start = $0; error = nil }
                    }
                    Text("~").foregroundStyle(YMColor.labelSecondary).frame(height: 40)
                    SchedOutlineButton(hm(end) ?? "끝") {
                        let base = start.map { DayTime.of(($0.minuteOfDay + 75) / 60 % 24, ($0.minuteOfDay + 75) % 60) } ?? DayTime.of(10, 15)
                        pick = .time(end ?? base) { end = $0; error = nil }
                    }
                }
            }
            if kind == "휴강" {
                SchedCaption("그날 그 과목의 수업이 모두 빠져요.")
            } else {
                Spacer().frame(height: 12)
                SchedTextField(placeholder: "강의실 (선택)", text: $room, limit: 40)
            }
            Spacer().frame(height: 12)
            SchedTextField(placeholder: "메모 (선택)", text: $note, limit: 100)
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

    private func selectCourse(_ co: TimetableLogic.CourseMeetings) {
        course = co
        if !courseText.isEmpty {
            suppressTextChange = true
            courseText = ""
        }
        error = nil
        if start == nil, kind == "보강", let m = co.meetings.first(where: { $0.day == TimetableLogic.weekdayOf(date) }) {
            start = DayTime.parse(m.start)
            end = DayTime.parse(m.end)
        }
    }

    private func save() {
        let name = course.map { TimetableLogic.shortName($0.name) } ?? SchedFormat.trim(courseText)
        let makeup = kind == "보강"
        if let err = TimetableLogic.validateChange(kind, name, makeup ? hm(start) : nil, makeup ? hm(end) : nil) {
            error = err
            return
        }
        let r = SchedFormat.trim(room)
        let n = SchedFormat.trim(note)
        onSave(TimetableLogic.Change(
            SchedFormat.nowMs(), date, kind, name, courseId: course?.courseId,
            start: makeup ? hm(start) : nil, end: makeup ? hm(end) : nil,
            room: r.isEmpty ? nil : r, note: n.isEmpty ? nil : n,
            sourceAnnouncementId: draft?.announcementId))
    }
}
