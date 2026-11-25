// Compile the same adapter that the app links, without compiling app-server.
#[path = "../../youngmin-ios-bridge/src/code_mode.rs"]
mod code_mode;

#[cfg(test)]
mod tests {
    use codex_code_mode_protocol::{
        CodeModeSessionProvider, ExecuteRequest, NoopCodeModeSessionDelegate, RuntimeResponse,
        TerminateRequest, WaitOutcome, WaitRequest,
    };
    use std::sync::Arc;

    #[tokio::test]
    async fn codex_session_round_trip_uses_the_native_javascript_runtime() {
        super::code_mode::self_test().await.unwrap();
    }

    #[tokio::test]
    async fn a_yielded_cell_can_finish_through_codex_wait() {
        let session = super::code_mode::JavaScriptProvider
            .create_session()
            .await
            .unwrap();
        let started = session.execute(ExecuteRequest {
            tool_call_id: "wait-probe".into(), enabled_tools: vec![],
            source: "text('before'); await new Promise(resolve => setTimeout(resolve,100)); text('after');".into(),
            yield_time_ms: Some(0), max_output_tokens: None,
        }, Arc::new(NoopCodeModeSessionDelegate), None).await.unwrap();
        let id = started.cell_id.clone();
        let first = started.initial_response().await.unwrap();
        assert!(matches!(first, RuntimeResponse::Yielded { .. }));
        let second = session
            .wait(
                WaitRequest {
                    cell_id: id,
                    yield_time_ms: 2_000,
                    max_output_tokens: None,
                },
                None,
            )
            .await
            .unwrap();
        assert!(matches!(
            second,
            WaitOutcome::LiveCell(RuntimeResponse::Result {
                error_text: None,
                ..
            })
        ));
        session.shutdown().await.unwrap();
    }

    #[tokio::test]
    async fn codex_can_terminate_a_running_javascript_loop() {
        let session = super::code_mode::JavaScriptProvider
            .create_session()
            .await
            .unwrap();
        let started = session
            .execute(
                ExecuteRequest {
                    tool_call_id: "interrupt-probe".into(),
                    enabled_tools: vec![],
                    source: "while(true) {}".into(),
                    yield_time_ms: Some(0),
                    max_output_tokens: None,
                },
                Arc::new(NoopCodeModeSessionDelegate),
                None,
            )
            .await
            .unwrap();
        let id = started.cell_id.clone();
        assert!(matches!(
            started.initial_response().await.unwrap(),
            RuntimeResponse::Yielded { .. }
        ));
        let stopped = session
            .terminate(TerminateRequest {
                cell_id: id,
                max_output_tokens: None,
            })
            .await
            .unwrap();
        assert!(matches!(
            stopped,
            WaitOutcome::LiveCell(RuntimeResponse::Terminated { .. })
        ));
        session.shutdown().await.unwrap();
    }
}
