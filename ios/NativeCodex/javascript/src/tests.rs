use super::*;
use serde_json::json;
use std::sync::atomic::AtomicUsize;

struct TestHost {
    calls: AtomicUsize,
    cancelled: AtomicBool,
}
impl Host for TestHost {
    fn invoke(&self, index: usize, input: Value, reply: Reply) {
        self.calls.fetch_add(1, Ordering::SeqCst);
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(30));
            if index == 0 {
                reply(Ok(
                    json!({"value": input["value"].as_i64().unwrap_or(0) + 1}),
                ));
            } else {
                reply(Err("tool denied".into()));
            }
        });
    }
    fn notify(&self, _: String) {}
    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
    }
}
fn start(source: &str, store: Arc<Store>) -> (Arc<Cell>, Arc<TestHost>) {
    let host = Arc::new(TestHost {
        calls: AtomicUsize::new(0),
        cancelled: AtomicBool::new(false),
    });
    let tools = vec![
        Tool {
            name: "sum".into(),
            description: "Increment a number".into(),
        },
        Tool {
            name: "denied".into(),
            description: "Reject the request".into(),
        },
    ];
    (
        Cell::start(source.into(), tools, store, host.clone()).unwrap(),
        host,
    )
}
fn complete(cell: &Cell) -> Observation {
    let observation = cell.observe(Duration::from_secs(3), &AtomicBool::new(false));
    if observation.status == Status::Running {
        cell.terminate();
        panic!("Cell did not complete in time");
    }
    observation
}
fn output(observation: &Observation) -> String {
    observation
        .content
        .iter()
        .map(|item| item["text"].as_str().unwrap_or(""))
        .collect::<Vec<_>>()
        .join("\n")
}

#[test]
fn await_parallel_native_tools() {
    let (cell, host) = start(
        "const [a,b] = await Promise.all([tools.sum({value:19}), tools.sum({value:21})]); text(`영민이:${a.value+b.value}`);",
        Arc::default(),
    );
    let result = complete(&cell);
    assert_eq!(result.status, Status::Completed(None));
    assert_eq!(output(&result), "영민이:42");
    assert_eq!(host.calls.load(Ordering::SeqCst), 2);
}
#[test]
fn rejected_tool_is_a_rejected_promise() {
    let (cell, _) = start(
        "try { await tools.denied({}); } catch (error) { text(String(error)); }",
        Arc::default(),
    );
    let result = complete(&cell);
    assert_eq!(result.status, Status::Completed(None));
    assert_eq!(output(&result), "tool denied");
}
#[test]
fn syntax_errors_are_reported() {
    let (cell, _) = start("const value = ;", Arc::default());
    let result = complete(&cell);
    assert!(matches!(result.status, Status::Completed(Some(_))));
}
#[test]
fn timers_can_resume_awaited_code() {
    let (cell, _) = start(
        "await new Promise(resolve => setTimeout(resolve, 20)); text('finished');",
        Arc::default(),
    );
    let result = complete(&cell);
    assert_eq!(result.status, Status::Completed(None));
    assert_eq!(output(&result), "finished");
}
#[test]
fn yielding_keeps_the_cell_alive_for_wait() {
    let (cell, _) = start(
        "text('before'); await yield_control(); await new Promise(resolve => setTimeout(resolve,100)); text('after');",
        Arc::default(),
    );
    let first = cell.observe(Duration::from_secs(2), &AtomicBool::new(false));
    assert_eq!(first.status, Status::Running);
    assert_eq!(output(&first), "before");
    let second = complete(&cell);
    assert_eq!(second.status, Status::Completed(None));
    assert_eq!(output(&second), "after");
}
#[test]
fn stored_json_survives_cells_but_globals_do_not() {
    let store = Arc::new(Store::default());
    let (first, _) = start(
        "store('answer',{value:41}); globalThis.secret=99;",
        store.clone(),
    );
    assert_eq!(complete(&first).status, Status::Completed(None));
    let (second, _) = start("text(load('answer').value+1); text(typeof secret);", store);
    assert_eq!(output(&complete(&second)), "42\nundefined");
    let (third, _) = start("text(load('answer')===undefined);", Arc::default());
    assert_eq!(output(&complete(&third)), "true");
}
#[test]
fn no_ambient_process_files_or_network() {
    let (cell, _) = start(
        "text([typeof process,typeof require,typeof fetch,typeof WebAssembly,typeof SharedArrayBuffer,typeof __ymNative].join(','));",
        Arc::default(),
    );
    assert_eq!(
        output(&complete(&cell)),
        "undefined,undefined,undefined,undefined,undefined,undefined"
    );
}
#[test]
fn infinite_loop_can_be_terminated() {
    let (cell, host) = start("while(true) {}", Arc::default());
    std::thread::sleep(Duration::from_millis(30));
    let started = Instant::now();
    cell.terminate();
    assert_eq!(complete(&cell).status, Status::Terminated);
    assert!(started.elapsed() < Duration::from_secs(2));
    assert!(host.cancelled.load(Ordering::Acquire));
}
#[test]
fn explicit_exit_succeeds_and_stops_execution() {
    let (cell, _) = start("text('before'); exit(); text('after');", Arc::default());
    let result = complete(&cell);
    assert_eq!(result.status, Status::Completed(None));
    assert_eq!(output(&result), "before");
}
#[test]
fn unawaited_tool_results_cannot_touch_a_closed_context() {
    let (cell, host) = start(
        "tools.sum({value:1}).then(text); text('complete');",
        Arc::default(),
    );
    let result = complete(&cell);
    assert_eq!(result.status, Status::Completed(None));
    assert_eq!(output(&result), "complete");
    std::thread::sleep(Duration::from_millis(60));
    assert!(host.cancelled.load(Ordering::Acquire));
}

#[test]
fn preempted_observers_leave_output_for_the_next_wait() {
    let (cell, _) = start("text('preserved');", Arc::default());
    std::thread::sleep(Duration::from_millis(60));
    let preempted = cell.observe(Duration::ZERO, &AtomicBool::new(true));
    assert_eq!(preempted.status, Status::Running);
    assert!(preempted.content.is_empty());
    assert_eq!(output(&complete(&cell)), "preserved");
}

#[test]
fn caught_exit_cannot_continue_output_or_run_forever() {
    let (cell, _) = start(
        "text('before'); try { exit(); } catch (_) {} try { text('after'); } catch (_) {} while(true) {}",
        Arc::default(),
    );
    let result = complete(&cell);
    assert_eq!(result.status, Status::Completed(None));
    assert_eq!(output(&result), "before");
}

#[test]
fn tools_not_in_the_allowlist_cannot_be_invoked() {
    let (cell, host) = start(
        "try { await tools.unknown({}); } catch (_) { text('blocked'); }",
        Arc::default(),
    );
    assert_eq!(output(&complete(&cell)), "blocked");
    assert_eq!(host.calls.load(Ordering::SeqCst), 0);
}

#[test]
fn microtask_loop_can_be_terminated() {
    let (cell, _) = start("while (true) await Promise.resolve();", Arc::default());
    std::thread::sleep(Duration::from_millis(30));
    cell.terminate();
    assert_eq!(complete(&cell).status, Status::Terminated);
}
#[test]
fn embedded_nul_and_unicode_roundtrip() {
    let (cell, _) = start("store('한글', '앞\\u0000뒤😀'); text(load('한글'));", Arc::default());
    let result = complete(&cell);
    assert_eq!(result.status, Status::Completed(None));
    assert_eq!(output(&result), "앞\0뒤😀");
}
#[test]
fn memory_limit_reports_an_error_without_killing_the_host() {
    let (cell, _) = start("const data=[]; while (true) data.push(new Array(100000).fill(42));", Arc::default());
    let result = complete(&cell);
    assert!(matches!(result.status, Status::Completed(Some(_))));
    let (next, _) = start("text(42);", Arc::default());
    assert_eq!(output(&complete(&next)), "42");
}
