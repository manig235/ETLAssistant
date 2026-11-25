import EventKit
import EventKitUI
import NotifyKit
import SwiftUI

/// 내 일정 행의 "캘린더에 추가"(Kotlin `CalendarExport.insertIntent` → 캘린더 앱의 일정 추가 화면).
/// iOS는 `EKEventEditViewController`를 띄운다. iOS 17부터는 캘린더 권한 없이도 띄울 수 있고, 저장은 사용자가 한다.
struct CalendarAddDraft: Identifiable {
    let id = UUID()
    let draft: CalendarExport.InsertDraft
}

struct CalendarAddSheet: UIViewControllerRepresentable {
    let draft: CalendarExport.InsertDraft
    let onDone: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let store = EKEventStore()
        let vc = EKEventEditViewController()
        vc.eventStore = store
        vc.event = CalendarSyncEventKit.makeEvent(draft, store: store)
        vc.editViewDelegate = context.coordinator
        context.coordinator.store = store
        return vc
    }

    func updateUIViewController(_ vc: EKEventEditViewController, context: Context) {}

    final class Coordinator: NSObject, EKEventEditViewDelegate {
        let onDone: () -> Void
        var store: EKEventStore?
        init(onDone: @escaping () -> Void) { self.onDone = onDone }

        func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
            onDone()
        }
    }
}

extension MyScheduleModel {
    /// 행 하나를 캘린더 앱의 일정 추가 화면에 채울 값. 반복 내 마감은 반복 규칙을 함께 넘긴다. 넣을 수 없으면 nil.
    func calendarDraft(_ r: Agenda.Row) -> CalendarExport.InsertDraft? {
        switch r.kind {
        case .MINE, .EXAM:
            guard let occ = r.mine, let series = seriesOf(occ) else { return nil }
            return CalendarExport.insertDraft(series: series, occurrence: occ.occurrence != nil ? occ : nil)
        case .ASSIGNMENT:
            return r.assignment.flatMap { CalendarExport.insertDraft($0) }
        case .CALENDAR:
            return nil
        }
    }
}
