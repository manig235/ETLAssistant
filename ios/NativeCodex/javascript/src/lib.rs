//! An isolated JavaScriptCore cell, with no process, file or network bindings.
//! The embedder supplies only the tools enabled for this particular execution.
mod jsc;

use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

pub const MAX_JSON_BYTES: usize = 2 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 8 * 1024 * 1024;
const MAX_STORE_BYTES: usize = 8 * 1024 * 1024;
pub type Reply = Box<dyn FnOnce(Result<Value, String>) + Send>;

pub trait Host: Send + Sync + 'static {
    /// Indexes refer to the immutable allowlist supplied to Cell::start.
    fn invoke(&self, index: usize, input: Value, reply: Reply);
    fn notify(&self, message: String);
    fn cancel(&self);
}

#[derive(Clone)]
pub struct Tool {
    pub name: String,
    pub description: String,
}

#[derive(Default)]
pub struct Store(Mutex<HashMap<String, Value>>);
impl Store {
    fn get(&self, key: &str) -> Option<Value> {
        self.0.lock().unwrap().get(key).cloned()
    }
    fn set(&self, key: String, value: Value) -> Result<(), String> {
        let mut entries = self.0.lock().unwrap();
        let size: usize = entries
            .iter()
            .filter(|(k, _)| **k != key)
            .map(|(k, v)| k.len() + v.to_string().len())
            .sum();
        if entries.len() >= 256 && !entries.contains_key(&key)
            || size + key.len() + value.to_string().len() > MAX_STORE_BYTES
        {
            return Err("Session store limit exceeded".into());
        }
        entries.insert(key, value);
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum Status {
    Running,
    Completed(Option<String>),
    Terminated,
}
pub struct Observation {
    pub status: Status,
    pub content: Vec<Value>,
    pub buffered_for: Duration,
}
struct State {
    status: Status,
    completed_at: Option<Instant>,
    output: Vec<Value>,
    bytes: usize,
    yielded: bool,
}
pub struct Cell {
    cancelled: AtomicBool,
    state: Mutex<State>,
    changed: Condvar,
    observer: Mutex<()>,
    host: Arc<dyn Host>,
}
impl Cell {
    pub fn start(
        source: String,
        tools: Vec<Tool>,
        store: Arc<Store>,
        host: Arc<dyn Host>,
    ) -> Result<Arc<Self>, String> {
        if source.len() > 512 * 1024 {
            return Err("JavaScript source limit exceeded".into());
        }
        if tools.len() > 512 {
            return Err("Too many enabled tools".into());
        }
        let cell = Arc::new(Self {
            cancelled: AtomicBool::new(false),
            state: Mutex::new(State {
                status: Status::Running,
                completed_at: None,
                output: vec![],
                bytes: 0,
                yielded: false,
            }),
            changed: Condvar::new(),
            observer: Mutex::new(()),
            host,
        });
        let running = cell.clone();
        std::thread::Builder::new()
            .name("youngmin-javascript".into())
            .stack_size(4 * 1024 * 1024)
            .spawn(move || {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    jsc::run(&running, source, tools, store)
                }));
                let error = match result {
                    Ok(result) => result.err(),
                    Err(_) => Some("JavaScript runtime stopped unexpectedly".into()),
                };
                running.finish(error);
            })
            .map_err(|error| error.to_string())?;
        Ok(cell)
    }

    pub fn terminate(&self) {
        self.cancelled.store(true, Ordering::Release);
        self.host.cancel();
        self.changed.notify_all();
    }
    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Acquire)
    }
    pub fn wake(&self) {
        self.changed.notify_all();
    }

    /// Only this observation is preempted; the JavaScript cell keeps running.
    pub fn observe(&self, timeout: Duration, preempt: &AtomicBool) -> Observation {
        let _observer = self.observer.lock().unwrap();
        let deadline = Instant::now() + timeout;
        let mut state = self.state.lock().unwrap();
        while state.status == Status::Running && !state.yielded && !preempt.load(Ordering::Acquire)
        {
            let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
                break;
            };
            let result = self
                .changed
                .wait_timeout(state, remaining.min(Duration::from_millis(25)))
                .unwrap();
            state = result.0;
        }
        state.yielded = false;
        state.bytes = 0;
        Observation {
            status: state.status.clone(),
            content: std::mem::take(&mut state.output),
            buffered_for: state
                .completed_at
                .map(|time| time.elapsed())
                .unwrap_or_default(),
        }
    }
    fn emit(&self, value: Value) -> Result<(), String> {
        let mut state = self.state.lock().unwrap();
        let bytes = value.to_string().len();
        if state.bytes + bytes > MAX_OUTPUT_BYTES || state.output.len() >= 2048 {
            return Err("JavaScript output limit exceeded; output a smaller summary".into());
        }
        state.bytes += bytes;
        state.output.push(value);
        Ok(())
    }
    fn yield_now(&self) {
        self.state.lock().unwrap().yielded = true;
        self.changed.notify_all();
    }
    fn finish(&self, error: Option<String>) {
        self.host.cancel();
        let mut state = self.state.lock().unwrap();
        state.status = if self.is_cancelled() {
            Status::Terminated
        } else {
            Status::Completed(error)
        };
        state.completed_at = Some(Instant::now());
        self.changed.notify_all();
    }
}

#[cfg(test)]
mod tests;
