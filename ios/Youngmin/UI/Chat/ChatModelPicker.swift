import ChatKit
import SwiftUI

/// AI 모델·생각의 깊이 시트(Kotlin `ModelPicker`). 고르는 즉시 저장, 다음 질문부터 적용.
struct ChatModelPicker: View {
    let controller: ChatController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("AI 모델")
                    .font(YMFont.title2Bold)
                    .foregroundStyle(YMColor.label)
                Spacer()
                YMTextButton("완료") { dismiss() }
            }
            .padding(.leading, 24)
            .padding(.trailing, 12)
            .padding(.top, 20)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("다음 질문부터 적용돼요. 잘 모르겠으면 '자동'을 쓰세요.")
                        .font(YMFont.body2Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .padding(.bottom, 16)
                    VStack(spacing: 10) { cards }
                }
                .padding(.horizontal, 24)
                .padding(.top, 4)
                .padding(.bottom, 24)
            }
        }
        .background(YMColor.background.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(28)
        .task {
            if controller.models.isEmpty && !controller.modelsLoading { await controller.loadModels() }
        }
    }

    @ViewBuilder
    private var cards: some View {
        let choice = controller.choice
        let models = controller.models.filter { !$0.hidden }
        let defaultModel = models.first { $0.isDefault } ?? models.first
        OptionCard(title: "자동 (권장)", detail: "Codex가 정한 기본 모델을 써요", isDefault: false,
                   selected: choice.model == nil, dimmed: false,
                   efforts: choice.model == nil ? (defaultModel?.efforts ?? []) : [],
                   effort: choice.effort,
                   effortNote: effortNote(defaultModel, auto: true, effort: choice.effort),
                   onSelect: { controller.selectModel(nil) },
                   onEffort: { controller.selectEffort($0) })
        if models.isEmpty {
            if controller.modelsLoading {
                HStack(spacing: 12) {
                    ProgressView().tint(YMColor.brandPrimary)
                    Text("모델 목록을 불러오는 중이에요…").font(YMFont.body1Medium).foregroundStyle(YMColor.label)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
            } else {
                Text("아직 모델 목록을 불러오지 못했어요. 비서가 준비되고 로그인되면 보여요.")
                    .font(YMFont.body2Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
            }
        }
        ForEach(models, id: \.id) { m in
            let selected = choice.model == m.model
            OptionCard(title: m.displayName, detail: m.description, isDefault: m.isDefault, selected: selected, dimmed: false,
                       efforts: selected ? m.efforts : [], effort: choice.effort,
                       effortNote: effortNote(m, auto: false, effort: choice.effort),
                       onSelect: { controller.selectModel(m.model) },
                       onEffort: { controller.selectEffort($0) })
        }
        if let saved = choice.model, !models.isEmpty, !models.contains(where: { $0.model == saved }) {
            OptionCard(title: saved, detail: "(지금은 쓸 수 없는 모델)", isDefault: false, selected: true, dimmed: true,
                       efforts: [], effort: nil, effortNote: "", onSelect: {}, onEffort: { _ in })
        }
    }

    /// 강도 설명: (자동이면 "기본 모델({이름}) 기준이에요. ") + 기본 문구 + 강도 설명.
    private func effortNote(_ m: ModelInfo?, auto: Bool, effort: String?) -> String {
        var s = ""
        if auto, let m { s += "기본 모델(\(m.displayName)) 기준이에요. " }
        s += "높을수록 오래 생각해서 어려운 문제에 좋고, 낮을수록 빨라요."
        if let m {
            if let e = effort {
                if let d = m.efforts.first(where: { $0.effort == e })?.description, !d.isEmpty { s += " " + d }
            } else if let def = m.defaultEffort {
                let d = m.efforts.first(where: { $0.effort == def })?.description ?? ""
                s += " 기본값은 \(ModelCatalog.effortLabel(def))이에요." + (d.isEmpty ? "" : " " + d)
            }
        }
        return s
    }
}

private struct OptionCard: View {
    let title: String
    let detail: String
    let isDefault: Bool
    let selected: Bool
    let dimmed: Bool
    let efforts: [EffortOption]
    let effort: String?
    let effortNote: String
    let onSelect: () -> Void
    let onEffort: (String?) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    Text(title).font(YMFont.body1Bold).foregroundStyle(YMColor.label)
                    if isDefault {
                        Text("기본")
                            .font(YMFont.caption1Bold)
                            .foregroundStyle(YMColor.brandPrimary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(RoundedRectangle(cornerRadius: 6).fill(YMColor.brandSubtle))
                    }
                }
                if !detail.isEmpty {
                    Text(detail)
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                }
                if selected && !efforts.isEmpty {
                    Text("생각의 깊이")
                        .font(YMFont.body2Medium)
                        .foregroundStyle(YMColor.label)
                        .padding(.top, 14)
                    YMFlowLayout(spacing: 8, lineSpacing: 8) {
                        YMChip("기본", isActive: effort == nil) { onEffort(nil) }
                        ForEach(efforts, id: \.effort) { e in
                            YMChip(ModelCatalog.effortLabel(e.effort), isActive: effort == e.effort) { onEffort(e.effort) }
                        }
                    }
                    .padding(.top, 8)
                    Text(effortNote)
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(YMColor.brandPrimary)
                    .accessibilityLabel("선택됨")
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(selected ? YMColor.brandPrimary : Color.clear, lineWidth: 2))
        .opacity(dimmed ? 0.55 : 1)
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .onTapGesture { onSelect() }
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}
