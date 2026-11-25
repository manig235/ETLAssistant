import NotifyKit
import SwiftUI

/// 알림 시점 추가(Kotlin `CustomWindowDialog`, `settings-window-add-dialog`): 자주 쓰는 시점 빠른 버튼 + 숫자·단위 직접 입력.
struct WindowAddDialog: View {
    let current: [Int]
    let onDismiss: () -> Void
    let onAdd: (Int) -> Void

    @State private var amount = ""
    @State private var unit: SettingsLogic.WindowUnit = .hour
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        SettingsDialogCard(title: "알림 시점 추가", onDismiss: onDismiss) {
            Text("자주 쓰는 시점").font(YMFont.body2Medium).foregroundStyle(YMColor.label)
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(SettingsLogic.quickWindows, id: \.self) { m in
                    let has = current.contains(m)
                    YMChip(NotifyUiFormat.windowLabel(m) + (has ? " 전 ✓" : " 전")) {
                        guard !has else { return }
                        switch SettingsLogic.checkWindow(Int64(m), current) {
                        case .ok(let v): onAdd(v)
                        case .error(let msg): error = msg
                        }
                    }
                    .disabled(has)
                    .opacity(has ? 0.4 : 1)
                }
            }
            .padding(.top, 8)
            Text("직접 입력").font(YMFont.body2Medium).foregroundStyle(YMColor.label).padding(.top, 16)
            TextField("예: 30", text: $amount)
                .keyboardType(.numberPad)
                .focused($focused)
                .font(YMFont.body1Regular)
                .foregroundStyle(YMColor.label)
                .padding(.horizontal, 16)
                .frame(height: 48)
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(YMColor.line, lineWidth: 1))
                .padding(.top, 8)
                .onChange(of: amount) { _, v in
                    let digits = String(v.filter(\.isASCII).filter(\.isNumber).prefix(4))
                    if digits != v { amount = digits }
                    error = nil
                }
            YMFlowLayout(spacing: 8, lineSpacing: 8) {
                ForEach(SettingsLogic.WindowUnit.allCases, id: \.self) { u in
                    YMChip(u.label + " 전", isActive: unit == u) {
                        unit = u
                        error = nil
                    }
                }
            }
            .padding(.top, 10)
            if let error {
                Text(error)
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.negativeForeground)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
            }
        } buttons: {
            YMTextButton("취소") { onDismiss() }
            YMTextButton("추가") {
                switch SettingsLogic.parseCustomWindow(amount, unit, current) {
                case .ok(let v): onAdd(v)
                case .error(let msg): error = msg
                }
            }
        }
    }
}
