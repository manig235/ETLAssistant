import ETLKit
import SwiftUI

/// 설정 → 기타 → 홈 화면에 위젯 추가하기(Kotlin `SettingsScreen.kt`의 위젯 고르기 대화상자).
/// 안드로이드는 `requestPinAppWidget`으로 바로 추가하지만 iOS 앱은 위젯을 추가할 수 없어서, 직접 추가하는 순서와
/// 위젯별로 고를 수 있는 크기를 안내한다. 크기 이름은 iOS 위젯 갤러리의 작게·중간·크게(iPad는 아주 크게)로 쓴다.
struct WidgetGuideView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            YMBackTopBar(title: "홈 화면에 위젯 추가하기") { dismiss() }
            ScrollView {
                VStack(alignment: .leading, spacing: 32) {
                    VStack(alignment: .leading, spacing: 12) {
                        SettingsCaption("iPhone에서는 앱이 위젯을 대신 추가할 수 없어요. 아래 순서대로 직접 추가해 주세요.")
                        if StudyDataProfile.isDemo {
                            SettingsStatusBanner(text: "가상 eTL 테스트 중이에요. 위젯에는 가상 자료가 나오지 않고 실제 eTL 자료만 보여요.",
                                                 tone: .caution)
                        }
                    }
                    SettingsSection(title: "홈 화면") {
                        YMCard {
                            WidgetGuideSteps(steps: Self.homeSteps)
                        }
                        SettingsCaption("iOS 18부터는 편집 중에 위젯 모서리를 끌어서 그 위젯이 지원하는 크기 사이에서 바꿀 수 있어요.")
                    }
                    SettingsSection(title: "위젯 종류") {
                        ForEach(Self.kinds, id: \.name) { kind in
                            YMCard { WidgetKindRow(kind: kind) }
                        }
                        SettingsCaption("아주 크게는 iPad에만 있어요. 위젯은 앱이 저장해 둔 정보로 그려서, 새 과제는 앱을 열거나 알림 확인이 끝난 뒤에 보여요.")
                    }
                    SettingsSection(title: "잠금 화면") {
                        YMCard {
                            WidgetGuideSteps(steps: Self.lockSteps)
                        }
                        SettingsCaption("잠금 화면 위젯은 잠긴 상태에서도 내용이 보여요.")
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 40)
            }
        }
        .background(YMColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    static let homeSteps = [
        "홈 화면의 빈 곳을 길게 눌러요.",
        "왼쪽 위 \"편집\"을 누르고 \"위젯 추가\"를 골라요.",
        "목록에서 \"영민이\"를 골라요. 위쪽 검색 칸에 영민이를 입력해도 돼요.",
        "좌우로 넘겨 일정·시간표·다음 수업과 크기를 골라요.",
        "\"위젯 추가\"를 누르고 원하는 자리로 옮긴 뒤 \"완료\"를 눌러요.",
    ]

    static let lockSteps = [
        "잠금 화면을 길게 눌러요. 잠금은 Face ID나 암호로 먼저 풀어 두세요.",
        "\"사용자화\"를 누르고 \"잠금 화면\"을 골라요.",
        "시계 아래 위젯 영역을 누르고 \"영민이\"에서 일정이나 다음 수업을 골라요.",
    ]

    struct Kind {
        let name: String
        let sizes: String
        let desc: String
    }

    static let kinds = [
        Kind(name: "일정", sizes: "작게 · 중간 · 크게 · 잠금 화면",
             desc: "가까운 과제와 내 마감을 마감순으로 보여줘요. 크게 할수록 더 많이 보이고, 내 마감은 ○를 눌러 완료로 표시해요."),
        Kind(name: "시간표", sizes: "중간 · 크게 · 아주 크게(iPad)",
             desc: "중간은 오늘 수업 목록, 크게는 이번 주 시간표예요. 등록한 휴강·보강도 반영해요."),
        Kind(name: "다음 수업", sizes: "작게 · 잠금 화면",
             desc: "다음 수업의 과목·시각·강의실을 보여줘요. 시작 30분 뒤 다음 수업으로 넘어가요."),
    ]
}

/// 번호 붙은 단계(번호 원 24 + 12 + body2Regular).
private struct WidgetGuideSteps: View {
    let steps: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text("\(i + 1)")
                        .font(YMFont.label2Bold)
                        .foregroundStyle(YMColor.brandPrimary)
                        .frame(width: 24, height: 24)
                        .background(Circle().fill(YMColor.brandSubtle))
                        .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 5 }
                    Text(step)
                        .font(YMFont.body2Regular)
                        .foregroundStyle(YMColor.label)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(i + 1)단계, \(step)")
            }
        }
    }
}

/// 위젯 하나: 이름(body1Bold) · 크기(caption, 강조색) · 설명.
private struct WidgetKindRow: View {
    let kind: WidgetGuideView.Kind

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(kind.name).font(YMFont.body1Bold).foregroundStyle(YMColor.label)
            Text(kind.sizes).font(YMFont.caption1Bold).foregroundStyle(YMColor.brandPrimary)
            SettingsCaption(kind.desc).padding(.top, 2)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(kind.name) 위젯. 크기: \(kind.sizes.replacingOccurrences(of: " · ", with: ", ")). \(kind.desc)")
    }
}
