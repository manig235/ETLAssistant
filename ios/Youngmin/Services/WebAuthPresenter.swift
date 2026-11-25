import AuthenticationServices
import UIKit

/// 로그인 페이지를 앱 안 브라우저 시트(ASWebAuthenticationSession)로 연다.
/// Sign in with ChatGPT는 회신 주소가 `http://127.0.0.1:<포트>/auth/callback`라서, 회신은 앱 안 루프백 수신기가 받고
/// (ChatKit), 이 시트는 그 수신기가 코드를 받으면 `finish()`로 닫는다. 사용자가 닫으면 `onCancel`이 불린다.
@MainActor
final class WebAuthPresenter: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func start(url: URL, onCancel: @escaping @MainActor () -> Void) {
        // callbackURLScheme은 쓰지 않는다(루프백 수신기가 회신을 받는다). 임시 세션이라 쿠키를 남기지 않는다.
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: nil) { _, error in
            if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                Task { @MainActor in onCancel() }
            }
        }
        session.presentationContextProvider = self
        // Safari 쿠키를 같이 쓰면 한 번 로그인한 OpenAI·구글 계정으로 자동으로 다시 들어가 다른 계정을 고를 수 없다.
        // 로그인은 한 번만 하면 되므로 매번 깨끗한 세션으로 연다(계정 선택 화면이 항상 나온다).
        session.prefersEphemeralWebBrowserSession = true
        self.session = session
        session.start()
    }

    func finish() {
        session?.cancel()
        session = nil
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first ?? ASPresentationAnchor()
        }
    }
}
