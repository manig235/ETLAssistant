import ETLKit
import NotifyKit
import SwiftUI

/// 등록 안 한 공지 불러오기(Kotlin `ChatActivity.loadPendingNotices`). 알림 확인 때 받아 둔 것만 쓴다(네트워크 없음).
enum PendingNoticesLoader {
    static var store: PendingNoticeStore { AppWiring.notifyStores.pending }

    /// 지금 보여 줄 공지(14일, 무시·등록됨·숨긴/알림 끈 과목 제외).
    static func targets(now: Date = Date()) -> [PendingNotice] {
        let registered = PendingNoticeStore.registeredIds(mine: SchedStores.mine.load(), changes: SchedStores.changes.load())
        return store.targets(courses: SchedStores.courses.load(), registered: registered, now: now)
    }

    /// 목록과 자동으로 띄울지. 띄우면 본 것으로 표시한다(새 공지가 생길 때까지 다시 자동으로 뜨지 않는다).
    /// - Parameter otherPrompt: 이번에 다른 창(iOS: 첫 알림 권한 창)이 뜨는가. 그러면 다음 기회로 미룬다.
    static func load(otherPrompt: Bool) -> (list: [PendingNotice], autoShow: Bool) {
        let list = targets()
        let auto = PendingNoticesLogic.shouldAutoShow(list, seen: store.seen(), enabled: SchedStores.settings.load().pendingSheet,
                                                      updatePromptShowing: otherPrompt)
        if auto { store.markSeen(list.map(\.id)) }
        return (list, auto)
    }
}

/// 시트에서 고른 동작(시트가 닫힌 뒤 채팅 화면이 처리한다).
enum PendingNoticeChoice: Equatable {
    /// 초안 창 열기(`AppRoute.scheduleDraft`/`.timetableDraft`).
    case open(AppRoute)
    /// "✨ 비서가 공지 읽고 채우기": 채팅 입력창 채우기(보내지 않음).
    case prefill(String)
}

/// 채팅 화면의 "등록 안 한 공지" 하단 시트(Kotlin `PendingNoticesSheet`, `chat-pending-notices`).
/// 행마다 종류 배지 · 과목 · 게시일 · 제목 · 찾은 날짜, [내 일정에 등록 | 시간표에 반영] [무시]. 하단 "나중에"는 닫기만 한다.
struct PendingNoticesSheet: View {
    @Binding var notices: [PendingNotice]
    let onChoose: (PendingNoticeChoice) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("등록 안 한 공지 \(notices.count)개")
                    .font(YMFont.headline2Bold)
                    .foregroundStyle(YMColor.label)
                Text("누르면 공지 내용을 채운 창이 열려요(그 창에서 비서에게 채워 달라고 할 수도 있어요). eTL 과제로 이미 올라온 마감이면 넣지 않아도 돼요.")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
                VStack(spacing: 10) {
                    ForEach(Array(notices.prefix(20)), id: \.id) { p in
                        PendingNoticeRow(notice: p,
                                         onRegister: { choose(.open(AppRoute.draft(p.draft))) },
                                         onAsk: { choose(.prefill(p.prefill)) },
                                         onIgnore: { ignore(p) })
                    }
                }
                .padding(.top, 12)
                HStack {
                    Spacer()
                    YMTextButton("나중에") { dismiss() }
                        .accessibilityLabel("나중에")
                }
                .padding(.top, 10)
                .padding(.bottom, 24)
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
        }
        .background(YMColor.background)
        .onChange(of: notices.isEmpty) { _, empty in if empty { dismiss() } }
    }

    private func choose(_ c: PendingNoticeChoice) {
        onChoose(c)
        dismiss()
    }

    /// "무시": 그 공지를 영구히 목록에서 뺀다(비면 닫힘).
    private func ignore(_ p: PendingNotice) {
        PendingNoticesLoader.store.ignore(p.id)
        withAnimation { notices.removeAll { $0.id == p.id } }
    }
}

private struct PendingNoticeRow: View {
    let notice: PendingNotice
    let onRegister: () -> Void
    let onAsk: () -> Void
    let onIgnore: () -> Void

    var body: some View {
        let draft = ScheduleDraft.fromJson(notice.draft)
        let exam = notice.kind == .EXAM
        let label = draft?.category.label ?? (exam ? "시험" : "과제")
        let action = draft?.actionLabel ?? ScheduleDrafts.ACTION
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Text(label)
                    .font(YMFont.caption1Bold)
                    .foregroundStyle(exam ? YMColor.cautionaryForeground : YMColor.brandPrimary)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1)
                    .background(RoundedRectangle(cornerRadius: 6).fill(exam ? YMColor.cautionarySurface : YMColor.background))
                Text(NoticeText.course(notice.courseName) + " · " + postedDay)
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .lineLimit(1)
            }
            Text(NoticeText.safe(notice.title, 80))
                .font(YMFont.body1Medium)
                .foregroundStyle(YMColor.label)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
            if let found = foundDate(draft) {
                Text("공지에서 찾은 날짜: \(found)")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.brandPrimary)
            }
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                Button(action, action: onRegister)
                    .buttonStyle(YMSolidButtonStyle(small: true))
                    .accessibilityLabel("\(notice.title) \(action)")
                if !notice.prefill.isEmpty {
                    YMTextButton(ScheduleDrafts.ASK_LINK, action: onAsk)
                        .accessibilityLabel("\(notice.title) 비서가 공지 읽고 채우기")
                }
                YMTextButton("무시", action: onIgnore)
                    .accessibilityLabel("\(notice.title) 무시")
            }
            .padding(.top, 8)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
    }

    /// 게시 날짜(공백 앞 부분, 예 11/24(월)).
    private var postedDay: String {
        let s = NoticeText.dateLabel(Date(timeIntervalSince1970: Double(notice.postedAt) / 1000))
        return s.split(separator: " ").first.map(String.init) ?? s
    }

    private func foundDate(_ d: ScheduleDraft?) -> String? {
        guard let d else { return nil }
        if let start = d.start {
            let end = d.end.map { e -> String in
                let s = NoticeText.dateLabel(e)
                return "~" + (s.split(separator: " ", maxSplits: 1).last.map(String.init) ?? s)
            } ?? ""
            return NoticeText.dateLabel(start) + end
        }
        return d.day.map { Agenda.dateLabel($0) }
    }
}
