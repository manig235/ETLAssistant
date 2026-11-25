import SwiftUI

/// 1. 환영(Kotlin `WelcomeStep`).
struct OnboardingWelcomeStep: View {
    let model: OnboardingModel
    @Binding var showPrivacy: Bool
    @Environment(AppState.self) private var app

    var body: some View {
        OnboardingStepFrame(
            title: "안녕하세요,\n영민이예요",
            subtitle: "마감과 공지는 챙기고, 궁금한 건 물어보고, 강의 파일은 바로 받아 보세요."
        ) {
            Image("RobotLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 120, height: 120)
                .background(YMColor.brandNavy)
                .clipShape(RoundedRectangle(cornerRadius: 24))
                .frame(maxWidth: .infinity)
                .accessibilityLabel("영민이 로고")
                .padding(.bottom, 28)
        } content: {
            VStack(alignment: .leading, spacing: 20) {
                feature("bell", "마감·공지 알림", "마감이 다가오면 미리 알려드려요")
                feature("bubble.left", "채팅으로 물어보기", "이번 주 할 일, 제출 확인, 파일 내용까지")
                feature("arrow.down.to.line", "강의 파일 받기", "필요한 자료를 폰에 바로 저장해요")
            }
            Spacer().frame(height: 28)
            YMCard {
                HStack(spacing: 8) {
                    Image(systemName: "lock")
                        .font(.system(size: 17))
                        .foregroundStyle(YMColor.labelSecondary)
                        .frame(width: 20, height: 20)
                    Text("개발자는 개인정보를 수집하지 않아요")
                        .font(YMFont.body2Bold)
                        .foregroundStyle(YMColor.label)
                }
                Text("개발자 서버는 없어요. eTL 토큰은 eTL로, ChatGPT 로그인 정보나 API 키는 OpenAI로 보내 인증해요. 비서에게 보낸 질문과 첨부, 비서가 불러온 강의 정보는 OpenAI로 전송돼요. 캘린더 연동을 켜고 계정 캘린더를 고르면 일정이 그 계정에 동기화될 수 있어요.")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
                YMTextButton("개인정보 처리방침 읽기", alignment: .leading) { showPrivacy = true }
                    .padding(.top, 10)
            }
            Text("서울대학교 공식 앱이 아니에요.")
                .font(YMFont.caption1Regular)
                .foregroundStyle(YMColor.labelTertiary)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.top, 16)
            #if DEBUG
            Button("샘플 데이터로 둘러보기") { DemoSeed.enterDemo(app) }
                .font(YMFont.caption1Medium)
                .foregroundStyle(YMColor.labelTertiary)
                .frame(maxWidth: .infinity, minHeight: 36)
                .padding(.top, 8)
            #endif
        } buttons: {
            YMPrimaryButton("시작하기") { model.goNext() }
        }
    }

    private func feature(_ icon: String, _ title: String, _ detail: String) -> some View {
        HStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 22))
                .foregroundStyle(YMColor.brandPrimary)
                .frame(width: 48, height: 48)
                .background(RoundedRectangle(cornerRadius: 14).fill(YMColor.brandSubtle))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(YMFont.headline1Bold).foregroundStyle(YMColor.label)
                Text(detail).font(YMFont.body2Regular).foregroundStyle(YMColor.labelSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
