import ETLKit
import NotifyKit
import SwiftUI
import UIKit

// 내 일정·시간표·알림 기록·과목별 알림 화면이 함께 쓰는 저장소·문구·작은 부품.
// 공용(Common) 부품에 없는 것만 여기 둔다. 이름은 다른 화면과 겹치지 않게 `Sched` 접두어.

/// 이 화면들이 쓰는 저장소(안드로이드 prefs 이름 = namespace, NotifyKit/ETLKit 기본값과 같다).
/// 통합 담당이 `AppStores`에 같은 저장소를 두면 여기를 그쪽으로 바꾼다(INTEGRATION.md).
enum SchedStores {
    static let mine = MyDeadlineStore()
    static let snapshot = SnapshotCache()
    static let courses = CourseFilterStore()
    static let timetable = TimetableStore()
    static let changes = TimetableChangeStore()
    static let history = NotifyHistory()
    static let settings = NotifySettingsStore()

    static func token() -> String? {
        guard let t = AppStores.tokens.load(), !t.isEmpty else { return nil }
        return t
    }

    static var hasToken: Bool { token() != nil }
}

/// 원본 화면들의 날짜·시각 문구.
enum SchedFormat {
    static let WEEKDAYS = ["월", "화", "수", "목", "금", "토", "일"]

    /// Kotlin `myDeadlineLabel`: 기기 시간대 기준 `11/23(월) 15:00`.
    static func myDeadlineLabel(_ t: Date, zone: TimeZone = .current) -> String {
        let d = ZoneClock.localDate(t, zone)
        return String(format: "%02d/%02d(%@) %02d:%02d", d.monthValue, d.dayOfMonth, WEEKDAYS[d.dayOfWeek.rawValue - 1],
                      ZoneClock.hour(t, zone), ZoneClock.minute(t, zone))
    }

    /// `2026/11/26(목)`.
    static func dateText(_ d: DayDate) -> String {
        String(format: "%d/%02d/%02d(%@)", d.year, d.monthValue, d.dayOfMonth, WEEKDAYS[d.dayOfWeek.rawValue - 1])
    }

    /// `09:05`.
    static func timeText(_ t: DayTime) -> String { String(format: "%02d:%02d", t.hour, t.minute) }

    static func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Kotlin `String.take(n)`(UTF-16 단위가 아니라 글자 단위로 자른다; 입력칸 제한에만 쓴다).
    static func take(_ s: String, _ n: Int) -> String { s.count > n ? String(s.prefix(n)) : s }

    static func nowMs() -> Int64 { Date().epochMilli }

    /// KST 시각 → 그날의 `DayTime`.
    static func kstTime(_ t: Date) -> DayTime {
        let p = EtlLogic.kstParts(t)
        return DayTime.of(p.hour, p.minute)
    }
}

/// eTL 링크 열기(Android `LearningXOpenActivity`): LearningX 앱 링크가 열리면 그것을, 아니면 같은 웹 주소.
@MainActor
enum SchedLinks {
    static func openEtl(_ url: String?) {
        guard let web = LearningXLink.webUrl(url), let webURL = URL(string: web) else { return }
        if let app = LearningXLink.fromWebUrl(web).flatMap(URL.init(string:)) {
            UIApplication.shared.open(app, options: [:]) { ok in
                if !ok { UIApplication.shared.open(webURL) }
            }
        } else {
            UIApplication.shared.open(webURL)
        }
    }
}

/// "✨ 비서가 공지 읽고 채우기": 채팅 입력창에 요청을 채운다(보내지 않음). 통합 담당이 채팅으로 돌아가는 동작을 넣는다.
/// 기본값(nil)이면 화면이 `AppState.chatController().input`을 채우고 한 단계 뒤로 간다.
private struct SchedOpenChatKey: EnvironmentKey {
    static let defaultValue: (@MainActor (String) -> Void)? = nil
}

extension EnvironmentValues {
    var schedOpenChat: (@MainActor (String) -> Void)? {
        get { self[SchedOpenChatKey.self] }
        set { self[SchedOpenChatKey.self] = newValue }
    }
}

extension YMFont {
    /// Montage title3Bold(편집 대화상자 제목).
    static let schedTitle3Bold = Font.system(size: 22, weight: .bold)
    static let schedCaption2Regular = Font.system(size: 11)
}

// MARK: - 작은 부품

/// 원본 상단바(56, 좌우 8): ← + 제목(weight 1) + 오른쪽 아이콘들.
struct SchedTopBar<Trailing: View>: View {
    let title: String
    let onBack: () -> Void
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 0) {
            SchedIconButton(systemName: "chevron.left", label: "뒤로", tint: YMColor.label, size: 20, weight: .semibold, action: onBack)
            Text(title)
                .font(YMFont.title2Bold)
                .foregroundStyle(YMColor.label)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.leading, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.horizontal, 8)
        .frame(height: 56)
    }
}

/// Material `IconButton`: 48 터치, 아이콘 24(또는 20).
struct SchedIconButton: View {
    let systemName: String
    let label: String
    var tint: Color = YMColor.labelSecondary
    var size: CGFloat = 22
    var weight: Font.Weight = .regular
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size, weight: weight))
                .foregroundStyle(tint)
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(YMPressStyle())
        .accessibilityLabel(label)
    }
}

/// `WantedButton(ASSISTIVE, MEDIUM, OUTLINED)`: 높이 40, 모서리 10, 1pt 선, 내용 폭.
struct SchedOutlineButton: View {
    let title: String
    var accessibility: String?
    let action: () -> Void

    init(_ title: String, accessibility: String? = nil, action: @escaping () -> Void) {
        self.title = title
        self.accessibility = accessibility
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(YMFont.label1Bold)
                .foregroundStyle(YMColor.label)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(height: 40)
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(YMColor.line, lineWidth: 1))
                .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(YMPressStyle())
        .accessibilityLabel(accessibility ?? title)
    }
}

/// 제목 없는 `WantedTextField(text, placeholder)`: 높이 48, 모서리 12, 1pt 선.
struct SchedTextField: View {
    let placeholder: String
    @Binding var text: String
    var limit: Int = 200
    var keyboard: UIKeyboardType = .default

    var body: some View {
        TextField(placeholder, text: $text, axis: .vertical)
            .font(YMFont.body1Regular)
            .foregroundStyle(YMColor.label)
            .keyboardType(keyboard)
            .lineLimit(1...4)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(minHeight: 48)
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(YMColor.line, lineWidth: 1))
            .onChange(of: text) { _, v in
                let cut = SchedFormat.take(v.replacingOccurrences(of: "\n", with: " "), limit)
                if cut != v { text = cut }
            }
    }
}

/// 원본 `Caption`·보조 글.
struct SchedCaption: View {
    let text: String
    var color: Color = YMColor.labelSecondary

    init(_ text: String, color: Color = YMColor.labelSecondary) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(YMFont.caption1Regular)
            .foregroundStyle(color)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 원본 AlertDialog 모양의 편집 시트: 제목(title3Bold) · 스크롤 본문 · 오른쪽 아래 글자 버튼 줄.
struct SchedFormSheet<Content: View, Buttons: View>: View {
    let title: String
    @ViewBuilder var content: Content
    @ViewBuilder var buttons: Buttons

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(YMFont.schedTitle3Bold)
                .foregroundStyle(YMColor.label)
                .padding(.horizontal, 24)
                .padding(.top, 28)
                .padding(.bottom, 16)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) { content }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                buttons
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .background(YMColor.background.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationCornerRadius(28)
        .presentationDragIndicator(.hidden)
    }
}

/// 원본 AlertDialog(본문이 글 이상일 때): 어두운 배경 위 가운데 카드(모서리 28, 안쪽 24).
struct SchedDialogCard<Content: View, Buttons: View>: View {
    let title: String
    let onDismiss: () -> Void
    @ViewBuilder var content: Content
    @ViewBuilder var buttons: Buttons

    var body: some View {
        ZStack {
            Color.black.opacity(0.32).ignoresSafeArea()
                .onTapGesture(perform: onDismiss)
            VStack(alignment: .leading, spacing: 16) {
                Text(title)
                    .font(YMFont.headline2Bold)
                    .foregroundStyle(YMColor.label)
                ScrollView {
                    VStack(alignment: .leading, spacing: 6) { content }
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(maxHeight: 360)
                .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    buttons
                }
            }
            .padding(24)
            .frame(maxWidth: 560)
            .background(RoundedRectangle(cornerRadius: 28).fill(YMColor.background))
            .padding(.horizontal, 24)
        }
        .transition(.opacity)
    }
}

// MARK: - 날짜·시각 고르기 (Android DatePickerDialog / TimePickerDialog). 시간대는 KST 고정.

struct SchedPick: Identifiable {
    enum Kind {
        case date(DayDate, (DayDate) -> Void)
        case time(DayTime, (DayTime) -> Void)
    }

    let id = UUID()
    let kind: Kind

    static func date(_ initial: DayDate, _ done: @escaping (DayDate) -> Void) -> SchedPick { SchedPick(kind: .date(initial, done)) }
    static func time(_ initial: DayTime, _ done: @escaping (DayTime) -> Void) -> SchedPick { SchedPick(kind: .time(initial, done)) }
}

struct SchedPickerSheet: View {
    let pick: SchedPick
    @Environment(\.dismiss) private var dismiss
    @State private var value: Date

    init(_ pick: SchedPick) {
        self.pick = pick
        switch pick.kind {
        case let .date(d, _): _value = State(initialValue: d.atTime(12, 0))
        case let .time(t, _): _value = State(initialValue: DayDate.today().atTime(t.hour, t.minute))
        }
    }

    private var isDate: Bool { if case .date = pick.kind { return true } else { return false } }

    var body: some View {
        VStack(spacing: 0) {
            Group {
                if isDate {
                    DatePicker("", selection: $value, displayedComponents: .date)
                        .datePickerStyle(.graphical)
                } else {
                    DatePicker("", selection: $value, displayedComponents: .hourAndMinute)
                        .datePickerStyle(.wheel)
                }
            }
            .labelsHidden()
            .environment(\.timeZone, EtlLogic.KST)
            .environment(\.calendar, EtlLogic.calendar)
            .environment(\.locale, Locale(identifier: "ko_KR"))
            .tint(YMColor.brandPrimary)
            .padding(.horizontal, 16)
            HStack(spacing: 8) {
                Spacer()
                YMTextButton("취소") { dismiss() }
                YMTextButton("확인") {
                    switch pick.kind {
                    case let .date(_, done): done(DayDate(value))
                    case let .time(_, done): done(SchedFormat.kstTime(value))
                    }
                    dismiss()
                }
            }
            .padding(.horizontal, 16)
        }
        .padding(.vertical, 12)
        .background(YMColor.background.ignoresSafeArea())
        .presentationDetents([.height(isDate ? 470 : 300)])
        .presentationCornerRadius(28)
    }
}

extension View {
    /// `$pick`에 값을 넣으면 날짜/시각 고르기 시트를 띄운다.
    func schedPicker(_ pick: Binding<SchedPick?>) -> some View {
        sheet(item: pick) { SchedPickerSheet($0) }
    }
}
