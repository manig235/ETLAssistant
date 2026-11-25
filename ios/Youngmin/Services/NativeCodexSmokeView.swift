#if DEBUG && YOUNGMIN_NATIVE_CODEX
import ChatKit
import SwiftUI

/// CI-only entry point. It calls the real embedded engine without credentials.
struct NativeCodexSmokeView: View {
    let client: CodexClient?
    @State private var status = "starting"

    var body: some View {
        Text(status)
            .accessibilityIdentifier("native-codex-status")
            .task {
                do {
                    guard let client else { throw CodexError("native runtime missing") }
                    let account = try await client.request("account/read")
                    guard account["account"]?.isNull == true else { throw CodexError("smoke test must use a fresh anonymous app") }
                    let models = ModelCatalog.parse(try await client.request("model/list"))
                    guard !models.isEmpty else { throw CodexError("native model catalog missing") }
                    // This creates only a local loopback listener and a PKCE URL.
                    // No browser or external authentication request is started.
                    let login = try await client.request("account/login/start", ["type": "chatgpt"])
                    guard let id = login["loginId"]?.string else { throw CodexError("native login attempt missing") }
                    let url = login["authUrl"]?.string.flatMap { URLComponents(string: $0) }
                    let query = Dictionary((url?.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
                    let valid = url?.scheme == "https" && url?.host == "auth.openai.com"
                        && query["client_id"] == "app_EMoamEEZ73f0CkXaXp7hrann"
                        && query["code_challenge_method"] == "S256"
                    let canceled = try await client.request("account/login/cancel", ["loginId": .string(id)])
                    guard valid, canceled["status"]?.string == "canceled" else { throw CodexError("native PKCE login did not initialize and cancel correctly") }
                    status = "ready: anonymous; models=\(models.count); PKCE=canceled"
                } catch { status = "failed: \((error as? CodexError)?.message ?? error.localizedDescription)" }
            }
    }
}
#endif
