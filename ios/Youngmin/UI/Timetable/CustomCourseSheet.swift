import ETLKit
import NotifyKit
import SwiftUI

/// 수업 직접 추가/수정(Kotlin `CustomCourseDialog`). 직접 추가한 수업은 음수 ID.
struct CustomCourseSheet: View {
    let course: TimetableLogic.CourseMeetings?
    let onDismiss: () -> Void
    let onSave: (TimetableLogic.CourseMeetings) throws -> Void
    let onDelete: (Int64) -> Void

    private let id: Int64
    @State private var name: String
    @State private var professor: String
    @State private var room: String
    @State private var days: String
    @State private var start: String
    @State private var end: String
    @State private var error: String?
    @State private var confirmingDelete = false
    @State private var pick: SchedPick?

    init(course: TimetableLogic.CourseMeetings?, onDismiss: @escaping () -> Void,
         onSave: @escaping (TimetableLogic.CourseMeetings) throws -> Void, onDelete: @escaping (Int64) -> Void) {
        self.course = course
        self.onDismiss = onDismiss
        self.onSave = onSave
        self.onDelete = onDelete
        // Kotlin `UUID.randomUUID().mostSignificantBits or Long.MIN_VALUE`: 늘 음수.
        id = course?.courseId ?? (Int64(bitPattern: UInt64.random(in: 0...UInt64.max)) | Int64.min)
        _name = State(initialValue: course?.name ?? "")
        _professor = State(initialValue: course?.professor ?? "")
        _room = State(initialValue: course?.meetings.first?.room ?? "")
        var seen = Set<String>()
        let d = course?.meetings.map(\.day).filter { seen.insert($0).inserted }.joined() ?? "월"
        _days = State(initialValue: d)
        _start = State(initialValue: course?.meetings.first?.start ?? "09:00")
        _end = State(initialValue: course?.meetings.first?.end ?? "10:15")
    }

    var body: some View {
        SchedFormSheet(title: course == nil ? "수업 직접 추가" : "직접 추가한 수업 수정") {
            VStack(alignment: .leading, spacing: 12) {
                SchedCaption("매주 반복되는 수업이나 일정을 추가해요.")
                label("과목명")
                SchedTextField(placeholder: "과목명 또는 일정 이름", text: $name, limit: 80)
                    .onChange(of: name) { error = nil }
                label("요일 · 여러 개 선택 가능")
                YMFlowLayout(spacing: 6, lineSpacing: 6) {
                    ForEach(TimetableLogic.weekdayLabels, id: \.self) { day in
                        YMChip(day, isActive: days.contains(day)) {
                            days = days.contains(day) ? days.replacingOccurrences(of: day, with: "") : days + day
                            error = nil
                        }
                    }
                }
                YMFlowLayout(spacing: 8, lineSpacing: 8) {
                    SchedOutlineButton("시작 \(start)") {
                        pick = .time(DayTime.parse(start) ?? DayTime.of(9, 0)) { start = $0.description; error = nil }
                    }
                    SchedOutlineButton("종료 \(end)") {
                        pick = .time(DayTime.parse(end) ?? DayTime.of(10, 15)) { end = $0.description; error = nil }
                    }
                }
                SchedTextField(placeholder: "강의실 (선택)", text: $room, limit: 80)
                SchedTextField(placeholder: "교수님 (선택)", text: $professor, limit: 80)
                if let error { SchedCaption(error, color: YMColor.negativeForeground) }
                if course != nil {
                    YMTextButton("이 수업 삭제") { confirmingDelete = true }
                }
            }
        } buttons: {
            YMTextButton("취소", action: onDismiss)
            YMTextButton("저장", action: save)
        }
        .schedPicker($pick)
        .alert("수업을 삭제할까요?", isPresented: $confirmingDelete) {
            Button("취소", role: .cancel) {}
            Button("삭제", role: .destructive) { onDelete(id) }
        } message: {
            Text(name)
        }
    }

    private func label(_ text: String) -> some View {
        Text(text).font(YMFont.body2Medium).foregroundStyle(YMColor.label)
    }

    private func save() {
        let meetings = TimetableLogic.weekdayLabels.filter { days.contains($0) }.map {
            TimetableLogic.Meeting($0, start, end, room)
        }
        let result = TimetableLogic.CourseMeetings(id, name, meetings, professor)
        if let err = CustomTimetableLogic.validationError(result) {
            error = err
            return
        }
        guard let normalized = CustomTimetableLogic.normalize(result) else { return }
        do {
            try onSave(normalized)
        } catch let e as EtlException {
            error = e.message
        } catch {
            self.error = "\(error)"
        }
    }
}
