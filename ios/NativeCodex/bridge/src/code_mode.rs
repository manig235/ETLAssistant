//! Code Mode sessions backed by the system JavaScriptCore inside this process.
//! All tool authority stays in Codex's supplied per-cell delegate and allowlist.
use codex_code_mode_protocol::{
    CellId, CodeModeNestedToolCall, CodeModeSession, CodeModeSessionDelegate,
    CodeModeSessionProvider, CodeModeSessionProviderFuture, CodeModeSessionResultFuture,
    EnabledToolMetadata, ExecuteRequest, FunctionCallOutputContentItem, RuntimeResponse,
    StartedCell, TerminateRequest, WaitOutcome, WaitRequest, enabled_tool_metadata,
};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio_util::sync::CancellationToken;
use youngmin_javascript::{Cell, Host, Reply, Status, Store, Tool};

pub struct JavaScriptProvider;
impl CodeModeSessionProvider for JavaScriptProvider {
    fn create_session(&self) -> CodeModeSessionProviderFuture<'_> {
        Box::pin(async { Ok(Arc::new(JavaScriptSession::default()) as Arc<dyn CodeModeSession>) })
    }
}

struct DelegateHost {
    delegate: Arc<dyn CodeModeSessionDelegate>,
    tools: Vec<EnabledToolMetadata>,
    cell_id: CellId,
    call_id: String,
    next: AtomicU64,
    runtime: tokio::runtime::Handle,
    cancellation: CancellationToken,
}
impl Host for DelegateHost {
    fn invoke(&self, index: usize, input: Value, reply: Reply) {
        let Some(tool) = self.tools.get(index).cloned() else {
            reply(Err("Tool is not enabled for this cell".into()));
            return;
        };
        let invocation = CodeModeNestedToolCall {
            cell_id: self.cell_id.clone(),
            runtime_tool_call_id: format!(
                "{}-{}",
                self.cell_id,
                self.next.fetch_add(1, Ordering::Relaxed)
            ),
            tool_name: tool.tool_name,
            tool_kind: tool.kind,
            input: Some(input),
        };
        let delegate = self.delegate.clone();
        let cancellation = self.cancellation.clone();
        self.runtime.spawn(async move {
            let result = tokio::select! {
                biased;
                _ = cancellation.cancelled() => Err("JavaScript cell was cancelled".into()),
                result = delegate.invoke_tool(invocation, cancellation.clone()) => result,
            };
            reply(result);
        });
    }
    fn notify(&self, message: String) {
        // Called only from the dedicated JavaScript worker, never a Tokio worker
        // or the UI thread. Bound the wait and dispatch before a cell can close.
        let delegate = self.delegate.clone();
        let cancellation = self.cancellation.clone();
        let call_id = self.call_id.clone();
        let cell_id = self.cell_id.clone();
        self.runtime.block_on(async move {
            let _ = tokio::time::timeout(Duration::from_secs(2), async {
                tokio::select! {
                    _ = cancellation.cancelled() => {},
                    _ = delegate.notify(call_id, cell_id, message, cancellation.clone()) => {},
                }
            })
            .await;
        });
    }
    fn cancel(&self) {
        self.cancellation.cancel();
    }
}

struct RunningCell {
    id: CellId,
    cell: Arc<Cell>,
    delegate: Arc<dyn CodeModeSessionDelegate>,
    closed: AtomicBool,
}
impl RunningCell {
    fn close(&self) {
        if !self.closed.swap(true, Ordering::AcqRel) {
            self.delegate.cell_closed(&self.id);
        }
    }
}
impl Drop for RunningCell {
    fn drop(&mut self) {
        self.cell.terminate();
        self.close();
    }
}
type Cells = Arc<Mutex<HashMap<CellId, Arc<RunningCell>>>>;
struct ObservationGuard {
    stop: Arc<AtomicBool>,
    cell: Arc<Cell>,
}
impl Drop for ObservationGuard {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        self.cell.wake();
    }
}
#[derive(Default)]
struct JavaScriptSession {
    store: Arc<Store>,
    cells: Cells,
}
impl Drop for JavaScriptSession {
    fn drop(&mut self) {
        for cell in self.cells.lock().unwrap().values() {
            cell.cell.terminate();
            cell.close();
        }
    }
}

async fn observe(
    cell: Arc<RunningCell>,
    cells: Cells,
    wait_ms: u64,
    preempt: Option<CancellationToken>,
) -> Result<RuntimeResponse, String> {
    let stop_waiting = Arc::new(AtomicBool::new(false));
    let _guard = ObservationGuard {
        stop: stop_waiting.clone(),
        cell: cell.cell.clone(),
    };
    let worker_stop = stop_waiting.clone();
    let worker_cell = cell.cell.clone();
    let mut waiting = tokio::task::spawn_blocking(move || {
        worker_cell.observe(Duration::from_millis(wait_ms.min(60_000)), &worker_stop)
    });
    let preemption = async move {
        match preempt {
            Some(token) => token.cancelled().await,
            None => std::future::pending::<()>().await,
        }
    };
    let observation = tokio::select! {
        result = &mut waiting => result,
        _ = preemption => { stop_waiting.store(true, Ordering::Release); cell.cell.wake(); waiting.await },
    }.map_err(|error| error.to_string())?;
    let content_items = observation
        .content
        .into_iter()
        .map(serde_json::from_value::<FunctionCallOutputContentItem>)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| error.to_string())?;
    let response = match observation.status {
        Status::Running => RuntimeResponse::Yielded {
            cell_id: cell.id.clone(),
            content_items,
            code_mode_host_duration: None,
        },
        Status::Completed(error_text) => RuntimeResponse::Result {
            cell_id: cell.id.clone(),
            content_items,
            error_text,
            result_buffered_for: observation.buffered_for,
            code_mode_host_duration: None,
        },
        Status::Terminated => RuntimeResponse::Terminated {
            cell_id: cell.id.clone(),
            content_items,
            code_mode_host_duration: None,
        },
    };
    if !matches!(response, RuntimeResponse::Yielded { .. }) {
        cells.lock().unwrap().remove(&cell.id);
        cell.close();
    }
    Ok(response)
}
fn missing(id: CellId) -> WaitOutcome {
    WaitOutcome::MissingCell(RuntimeResponse::Result {
        cell_id: id,
        content_items: vec![],
        error_text: Some("JavaScript cell not found".into()),
        result_buffered_for: Duration::ZERO,
        code_mode_host_duration: None,
    })
}

impl CodeModeSession for JavaScriptSession {
    fn execute<'a>(
        &'a self,
        request: ExecuteRequest,
        delegate: Arc<dyn CodeModeSessionDelegate>,
        preempt: Option<CancellationToken>,
    ) -> CodeModeSessionResultFuture<'a, StartedCell> {
        Box::pin(async move {
            let id = CellId::new(format!("ios-js-{}", uuid::Uuid::new_v4()));
            let enabled: Vec<EnabledToolMetadata> = request
                .enabled_tools
                .iter()
                .map(enabled_tool_metadata)
                .collect();
            let tools = enabled
                .iter()
                .map(|tool| Tool {
                    name: tool.global_name.clone(),
                    description: tool.description.clone(),
                })
                .collect();
            let host = Arc::new(DelegateHost {
                delegate: delegate.clone(),
                tools: enabled,
                cell_id: id.clone(),
                call_id: request.tool_call_id,
                next: AtomicU64::new(0),
                runtime: tokio::runtime::Handle::current(),
                cancellation: CancellationToken::new(),
            });
            // Reserve capacity and register without awaiting; the JS worker can
            // complete immediately but cannot consume another session's tools.
            let mut cells = self.cells.lock().unwrap();
            if cells.len() >= 4 {
                return Err(
                    "Too many running JavaScript cells; wait for a cell before starting another"
                        .into(),
                );
            }
            let cell = Cell::start(request.source, tools, self.store.clone(), host)?;
            let running = Arc::new(RunningCell {
                id: id.clone(),
                cell,
                delegate,
                closed: AtomicBool::new(false),
            });
            cells.insert(id.clone(), running.clone());
            drop(cells);
            let cells = self.cells.clone();
            Ok(StartedCell::from_future(
                id,
                observe(
                    running,
                    cells,
                    request.yield_time_ms.unwrap_or(10_000),
                    preempt,
                ),
            ))
        })
    }
    fn wait<'a>(
        &'a self,
        request: WaitRequest,
        preempt: Option<CancellationToken>,
    ) -> CodeModeSessionResultFuture<'a, WaitOutcome> {
        Box::pin(async move {
            let cell = self.cells.lock().unwrap().get(&request.cell_id).cloned();
            match cell {
                Some(cell) => observe(cell, self.cells.clone(), request.yield_time_ms, preempt)
                    .await
                    .map(WaitOutcome::LiveCell),
                None => Ok(missing(request.cell_id)),
            }
        })
    }
    fn terminate<'a>(
        &'a self,
        request: TerminateRequest,
    ) -> CodeModeSessionResultFuture<'a, WaitOutcome> {
        Box::pin(async move {
            let cell = self.cells.lock().unwrap().get(&request.cell_id).cloned();
            match cell {
                Some(cell) => {
                    cell.cell.terminate();
                    let response = observe(cell, self.cells.clone(), 5_000, None).await?;
                    if matches!(response, RuntimeResponse::Yielded { .. }) {
                        return Err("JavaScript cell did not stop in time".into());
                    }
                    Ok(WaitOutcome::LiveCell(response))
                }
                None => Ok(missing(request.cell_id)),
            }
        })
    }
    fn shutdown<'a>(&'a self) -> CodeModeSessionResultFuture<'a, ()> {
        Box::pin(async move {
            let cells: Vec<_> = self
                .cells
                .lock()
                .unwrap()
                .drain()
                .map(|(_, cell)| cell)
                .collect();
            for cell in cells {
                cell.cell.terminate();
                cell.close();
            }
            Ok(())
        })
    }
}

/// Validate the actual Codex session/delegate contract before accepting app
/// requests. This needs no account, API request, server or helper executable.
pub async fn self_test() -> Result<(), String> {
    use codex_code_mode_protocol::{
        CodeModeToolKind, NotificationFuture, ToolDefinition, ToolInvocationFuture,
    };
    use codex_protocol::ToolName;
    use serde_json::json;
    struct Probe;
    impl CodeModeSessionDelegate for Probe {
        fn invoke_tool<'a>(
            &'a self,
            call: CodeModeNestedToolCall,
            _: CancellationToken,
        ) -> ToolInvocationFuture<'a> {
            Box::pin(async move {
                if call.tool_name != ToolName::new(None, "youngmin_probe") {
                    return Err("Unexpected JavaScript probe tool".into());
                }
                Ok(
                    json!({"value":call.input.and_then(|v| v["value"].as_i64()).ok_or("Missing probe argument")? + 1}),
                )
            })
        }
        fn notify<'a>(
            &'a self,
            _: String,
            _: CellId,
            _: String,
            _: CancellationToken,
        ) -> NotificationFuture<'a> {
            Box::pin(async { Ok(()) })
        }
        fn cell_closed(&self, _: &CellId) {}
    }
    let provider = JavaScriptProvider;
    let session = provider.create_session().await?;
    let started = session.execute(ExecuteRequest {
        tool_call_id: "youngmin-javascript-probe".into(),
        enabled_tools: vec![ToolDefinition { name: "youngmin_probe".into(), tool_name: ToolName::new(None, "youngmin_probe"),
            description: "Increment the probe value".into(), kind: CodeModeToolKind::Function,
            input_schema: Some(json!({"type":"object","properties":{"value":{"type":"integer"}},"required":["value"],"additionalProperties":false})),
            input_schema_max_bytes: None, output_schema: None }],
        source: "const answer = await tools.youngmin_probe({value:41}); text(answer.value);".into(),
        yield_time_ms: Some(2_000), max_output_tokens: Some(100),
    }, Arc::new(Probe), None).await?;
    let result = started.initial_response().await;
    session.shutdown().await?;
    match result? {
        RuntimeResponse::Result {
            error_text: None,
            content_items,
            ..
        } if matches!(content_items.as_slice(), [FunctionCallOutputContentItem::InputText {text}] if text == "42") => {
            Ok(())
        }
        other => Err(format!("JavaScript Codex delegate probe failed: {other:?}")),
    }
}
