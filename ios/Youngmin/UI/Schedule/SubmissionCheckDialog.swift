import ETLKit
import SwiftUI

/// 제출 확인 결과(Kotlin `SubmissionCheckDialog`). eTL 제출 기록(과제 + 내 제출, GET 두 번)만 보고 파일은 받지 않는다.
struct SubmissionCheckDialog: View {
    let state: SubmissionCheckUi
    let onDismiss: () -> Void
    let onOpen: (String) -> Void

    private var title: String {
        switch state {
        case .loading: return "제출 확인 중"
        case .failed: return "제출을 확인하지 못했어요"
        case let .done(_, r):
            if r.ok && r.warnings.isEmpty { return "제출에 문제 없어요" }
            if r.ok { return "제출됐어요 · 참고할 점 있음" }
            if !r.submitted { return "제출 기록이 없어요" }
            return "확인이 필요해요"
        }
    }

    var body: some View {
        SchedDialogCard(title: title, onDismiss: onDismiss) {
            Text(state.title)
                .font(YMFont.body2Medium)
                .foregroundStyle(YMColor.label)
            switch state {
            case .loading:
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("eTL 제출 기록을 받는 중…")
                        .font(YMFont.body2Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                }
            case let .failed(_, message):
                Text(message)
                    .font(YMFont.body2Regular)
                    .foregroundStyle(YMColor.labelSecondary)
            case let .done(_, r):
                SchedCaption("제출 \(r.submittedAt.map { EtlLogic.fmtKst($0) } ?? "없음") · 마감 \(EtlLogic.fmtKst(r.due))")
                if !r.files.isEmpty {
                    SchedCaption("제출한 파일: " + r.files.joined(separator: ", "))
                }
                ForEach(Array(r.problems.enumerated()), id: \.offset) { _, p in
                    Text("• \(p)").font(YMFont.body2Regular).foregroundStyle(YMColor.negativeForeground)
                }
                ForEach(Array(r.warnings.enumerated()), id: \.offset) { _, w in
                    Text("• \(w)").font(YMFont.body2Regular).foregroundStyle(YMColor.labelSecondary)
                }
                SchedCaption("eTL 제출 기록만 봤어요(파일 내용은 받지 않았어요).", color: YMColor.labelTertiary)
            }
        } buttons: {
            if case let .done(_, r) = state, let url = r.url {
                YMTextButton("eTL에서 열기") { onOpen(url) }
            }
            YMTextButton("닫기", action: onDismiss)
        }
    }
}
