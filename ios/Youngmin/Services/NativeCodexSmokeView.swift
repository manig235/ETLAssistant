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
                    status = "ready: anonymous; models=\(models.count)"
                } catch { status = "failed: \((error as? CodexError)?.message ?? error.localizedDescription)" }
            }
    }
}
#endif
