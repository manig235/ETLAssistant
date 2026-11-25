import AssistantKit
import ChatKit
import ETLKit
import SwiftUI

/// 받은 파일 자동 정리(Kotlin `files/DownloadCleanup`·`CleanupUi`·`WorkFileCleanup`의 앱 쪽).
/// 정리 판정·실행은 ETLKit `DownloadCleanup`/`WorkFiles`. 여기서는 AI가 만든 파일 판정(AssistantKit `GeneratedFiles`)을 붙이고,
/// 화면(채팅 첫 열기 때 한 번 묻는 창, 설정 "받은 파일", 첫 실행 안내의 알림 단계)에 쓰는 조각을 둔다.
enum FilesCleanupService {
    static let cleanup = DownloadCleanup(generated: { GeneratedFiles.shared.isGenerated($0, relativePath: $0) })

    /// 하루 한 번(앱이 활성화될 때·알림 확인 때). 네트워크 없이 뒤에서 돈다. 받은 파일 정리 + 작업 폴더 14일 정리.
    static func runIfDue() {
        DispatchQueue.global(qos: .utility).async {
            let removed = cleanup.runIfDue()
            let work = WorkFiles.runIfDue()
            if removed > 0 || work > 0 {
                DiagLog.logger.notice("cleanup: downloads=\(removed) work=\(work)")
                DispatchQueue.main.async { NotificationCenter.default.post(name: .filesCleanupChanged, object: nil) }
            }
        }
    }

    /// 기간을 저장하고 바로 한 번 정리한다(고른 결과가 보이게).
    static func setPeriod(_ p: CleanupPeriod) {
        DispatchQueue.global(qos: .utility).async {
            cleanup.setPeriod(p)
            DispatchQueue.main.async { NotificationCenter.default.post(name: .filesCleanupChanged, object: nil) }
        }
    }
}

extension Notification.Name {
    /// 기간을 바꿨거나 정리가 돌았다(설정의 "최근 정리" 줄·파일 카드 다시 그리기).
    static let filesCleanupChanged = Notification.Name("YMFilesCleanupChanged")
}

/// 기간 칩(2일 · 7일 · 30일 · 정리 안 함). [selected]가 nil이면 아무것도 고르지 않은 상태(Kotlin `CleanupChoices`).
struct CleanupChoices: View {
    let selected: CleanupPeriod?
    let onSelect: (CleanupPeriod) -> Void

    var body: some View {
        YMFlowLayout(spacing: 8, lineSpacing: 8) {
            ForEach(CleanupPeriod.allCases, id: \.self) { p in
                YMChip(p.label, isActive: selected == p) { onSelect(p) }
                    .accessibilityAddTraits(selected == p ? .isSelected : [])
            }
        }
    }
}

/// 설정 카드 안의 내용(Kotlin `DownloadCleanupSettingsContent`): 제목, 설명, 칩, 최근 정리. 바꾸면 바로 저장한다.
struct CleanupSettingsContent: View {
    @State private var selected = FilesCleanupService.cleanup.period
    @State private var status = FilesCleanupService.cleanup.statusLabel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("받은 강의자료 자동 정리")
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.label)
            Text(DownloadCleanupLogic.DESC)
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
            Text(DownloadCleanupLogic.KEEP_HINT)
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelSecondary)
                .fixedSize(horizontal: false, vertical: true)
            CleanupChoices(selected: selected) {
                selected = $0
                FilesCleanupService.setPeriod($0)
            }
            .padding(.top, 10)
            if let status {
                Text(status)
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .padding(.top, 8)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .filesCleanupChanged)) { _ in
            selected = FilesCleanupService.cleanup.period
            status = FilesCleanupService.cleanup.statusLabel
        }
    }
}

/// 이미 안내를 마친 사용자에게 채팅을 열 때 한 번 기간을 묻는다(`chat-cleanup-prompt`, Kotlin `CleanupPrompt`).
/// "나중에" 버튼은 없다. "저장"을 누르면 저장하고, 아래로 끌거나 바깥을 눌러 닫으면 고른 것으로 치지 않고 다음에 한 번 더 묻는다
/// (두 번 닫으면 더 묻지 않고 정리하지 않는다. 설정에서 언제든 고를 수 있다).
struct ChatCleanupPrompt: View {
    let onSaved: () -> Void
    @State private var selected = CleanupPeriod.DEFAULT

    /// 이번에 띄울 차례인가(다른 자동 창과 겹치지 않게 부르는 쪽이 판단).
    static var pending: Bool { FilesCleanupService.cleanup.shouldAsk }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(DownloadCleanupLogic.QUESTION)
                    .font(YMFont.headline2Bold)
                    .foregroundStyle(YMColor.label)
                    .fixedSize(horizontal: false, vertical: true)
                Text(DownloadCleanupLogic.DESC)
                    .font(YMFont.body2Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
                CleanupChoices(selected: selected) { selected = $0 }
                    .padding(.top, 12)
                Text("\(DownloadCleanupLogic.KEEP_HINT) 설정 → 받은 파일에서 언제든 바꿀 수 있어요.")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
                HStack {
                    Spacer()
                    YMTextButton("저장") {
                        FilesCleanupService.setPeriod(selected)
                        onSaved()
                    }
                }
                .padding(.top, 12)
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 16)
        }
        .background(YMColor.background)
    }
}
