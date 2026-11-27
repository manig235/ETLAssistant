import ETLKit
import NotifyKit
import SwiftUI

/// 알림 기록(Kotlin `NotifyHistoryActivity`): 최근 90일 동안 띄운 알림. 공지 초안은 여기서 내 일정·시간표에 넣을 수 있다.
struct NotifyHistoryView: View {
    @State private var items: [HistoryEntry] = []
    /// 같은 공지로 이미 넣은 항목(공지 ID → 내 마감 ID(양수) 또는 -1(시간표 보강·휴강으로 반영됨)).
    @State private var registered: [Int64: Int64] = [:]
    @State private var expanded: Int64?
    @State private var confirmClear = false
    @State private var target: Target?

    @Environment(\.dismiss) private var dismiss

    /// "내 일정에 등록"·"시간표에 반영"·"등록됨 · 열기"·"반영됨 · 열기"가 여는 화면.
    enum Target: Hashable {
        case edit(Int64)
        case draft(String)
        case timetable(String?)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SchedTopBar(title: "알림 기록", onBack: { dismiss() }) {
                if !items.isEmpty {
                    SchedIconButton(systemName: "trash.slash", label: "기록 지우기") { confirmClear = true }
                }
            }
            Text("최근 \(NotifyHistoryLogic.KEEP_DAYS)일 동안 띄운 알림이에요. 이 폰에만 남아요.")
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .padding(.horizontal, 24)
            Spacer().frame(height: 12)
            if items.isEmpty {
                Text("아직 알림 기록이 없어요")
                    .font(YMFont.body1Medium)
                    .foregroundStyle(YMColor.labelSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(Array(items.enumerated()), id: \.offset) { _, e in card(e) }
                        Spacer().frame(height: 24)
                    }
                    .ymReadableWidth(YMLayout.formWidth - 32)
                    .padding(.horizontal, 16)
                }
            }
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            #if DEBUG
            DemoScheduleSeed.seed(.history)
            #endif
            reload()
        }
        .alert("알림 기록을 지울까요?", isPresented: $confirmClear) {
            Button("취소", role: .cancel) {}
            Button("지우기", role: .destructive) {
                SchedStores.history.clear()
                reload()
            }
        } message: {
            Text("지운 기록은 되돌릴 수 없어요. 알림 설정은 그대로예요.")
        }
        .navigationDestination(item: $target) { t in
            switch t {
            case let .edit(id): MyScheduleView(openDeadlineId: id)
            case let .draft(json): MyScheduleView(draft: ScheduleDraft.fromJson(json))
            case let .timetable(json): TimetableView(draft: json.flatMap(ScheduleDraft.fromJson))
            }
        }
    }

    private func card(_ e: HistoryEntry) -> some View {
        let open = expanded == e.at
        return VStack(alignment: .leading, spacing: 0) {
            Text(SchedFormat.myDeadlineLabel(Date(epochMilli: e.at)) + " · " + NotifyHistoryLogic.channelLabel(e.channel))
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelSecondary)
            Spacer().frame(height: 2)
            Text(e.title)
                .font(YMFont.body1Medium)
                .foregroundStyle(YMColor.label)
                .fixedSize(horizontal: false, vertical: true)
            Text(open && !(e.bigText.map(SchedFormat.trim)?.isEmpty ?? true) ? e.bigText! : e.text)
                .font(YMFont.body2Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                if let draft = e.draft {
                    let parsed = ScheduleDraft.fromJson(draft)
                    let existing = parsed.flatMap { registered[$0.announcementId] }
                    let label = existing != nil
                        ? (parsed?.timetable == true ? "반영됨 · 열기" : "등록됨 · 열기")
                        : (parsed?.actionLabel ?? ScheduleDrafts.ACTION)
                    YMTextButton(label) { register(draft, parsed, existing) }
                }
                if open, let url = e.url, url.hasPrefix("https://myetl.snu.ac.kr/") {
                    YMTextButton("eTL에서 열기") { SchedLinks.openEtl(url) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .onTapGesture {
            withAnimation(.easeOut(duration: 0.2)) { expanded = open ? nil : e.at }
        }
        .accessibilityAddTraits(.isButton)
    }

    /// 시험·과제 공지의 "내 일정에 등록": 미리 채운 추가 창. 이미 넣었으면 그 항목을 고치기로 연다.
    private func register(_ draft: String, _ parsed: ScheduleDraft?, _ existing: Int64?) {
        if let existing, existing > 0 {
            target = .edit(existing)
        } else if existing != nil {
            target = .timetable(nil) // 시간표에 반영된 보강·휴강
        } else if parsed?.timetable == true {
            target = .timetable(draft)
        } else {
            target = .draft(draft)
        }
    }

    private func reload() {
        items = SchedStores.history.load().sorted { $0.at > $1.at }
        var map: [Int64: Int64] = [:]
        for c in SchedStores.changes.load() { if let a = c.sourceAnnouncementId { map[a] = -1 } }
        for d in SchedStores.mine.load() { if let a = d.sourceAnnouncementId { map[a] = d.id } }
        registered = map
    }
}
