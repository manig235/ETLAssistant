//! Regression for iOS build 26: every message after the first called
//! thread/resume, which failed because the embedded server had no state DB.
//! Drives the real bridge through its C interface against a local mock model,
//! so no account or external network is needed.
use super::*;
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::Path;
use std::time::{Duration, Instant};

/// Minimal Responses endpoint: answers every POST .../responses with one
/// completed assistant message and records the request bodies. Anything else
/// gets 404, like an unmatched mock route upstream.
fn mock_model() -> (u16, Arc<Mutex<Vec<String>>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let bodies = Arc::new(Mutex::new(Vec::new()));
    let seen = bodies.clone();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(stream) = stream else { continue };
            let seen = seen.clone();
            std::thread::spawn(move || serve(stream, seen));
        }
    });
    (port, bodies)
}

fn serve(mut stream: std::net::TcpStream, seen: Arc<Mutex<Vec<String>>>) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).unwrap_or(0) == 0 {
        return;
    }
    let (mut length, mut chunked) = (0usize, false);
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap_or(0) == 0 || line == "\r\n" {
            break;
        }
        let lower = line.to_ascii_lowercase();
        if let Some(value) = lower.strip_prefix("content-length:") {
            length = value.trim().parse().unwrap_or(0);
        }
        if lower.starts_with("transfer-encoding:") && lower.contains("chunked") {
            chunked = true;
        }
    }
    let mut body = Vec::new();
    if chunked {
        loop {
            let mut size = String::new();
            if reader.read_line(&mut size).unwrap_or(0) == 0 {
                return;
            }
            let size = usize::from_str_radix(size.trim(), 16).unwrap_or(0);
            let mut chunk = vec![0; size + 2];
            if reader.read_exact(&mut chunk).is_err() {
                return;
            }
            if size == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..size]);
        }
    } else {
        body.resize(length, 0);
        if reader.read_exact(&mut body).is_err() {
            return;
        }
    }
    let path = request_line.split(' ').nth(1).unwrap_or("");
    if !request_line.starts_with("POST") || !path.ends_with("/responses") {
        let _ = stream.write_all(
            b"HTTP/1.1 404 Not Found\r\ncontent-length: 0\r\nconnection: close\r\n\r\n",
        );
        return;
    }
    let index = {
        let mut seen = seen.lock().unwrap();
        seen.push(String::from_utf8_lossy(&body).into_owned());
        seen.len()
    };
    let id = format!("resp-{index}");
    let events = [
        json!({"type":"response.created","response":{"id":id}}),
        json!({"type":"response.output_item.done","item":{
            "type":"message","role":"assistant","id":format!("msg-{index}"),
            "content":[{"type":"output_text","text":format!("reply {index}")}]
        }}),
        json!({"type":"response.completed","response":{"id":id,"usage":{
            "input_tokens":0,"input_tokens_details":null,"output_tokens":0,
            "output_tokens_details":null,"total_tokens":0
        }}}),
    ];
    let sse: String = events
        .iter()
        .map(|event| format!("event: {}\ndata: {event}\n\n", event["type"].as_str().unwrap()))
        .collect();
    let _ = stream.write_all(
        format!(
            "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{sse}",
            sse.len()
        )
        .as_bytes(),
    );
}

struct Engine {
    id: u64,
    backlog: RefCell<VecDeque<Value>>,
    next: Cell<u64>,
}

impl Engine {
    fn start(home: &Path, workspace: &Path) -> Engine {
        let home = CString::new(home.to_str().unwrap()).unwrap();
        let workspace = CString::new(workspace.to_str().unwrap()).unwrap();
        let id = unsafe { ym_codex_start(home.as_ptr(), workspace.as_ptr()) };
        assert_ne!(id, 0, "bridge did not start");
        let engine = Engine {
            id,
            backlog: RefCell::default(),
            next: Cell::new(1),
        };
        let deadline = Instant::now() + Duration::from_secs(180);
        while engine.poll(deadline)["method"] != "youngmin/ready" {}
        engine
    }

    fn poll(&self, deadline: Instant) -> Value {
        loop {
            let raw = ym_codex_poll(self.id);
            if !raw.is_null() {
                let text = unsafe { CStr::from_ptr(raw) }.to_str().unwrap().to_owned();
                unsafe { ym_codex_free_string(raw) };
                let value: Value = serde_json::from_str(&text).unwrap();
                assert_ne!(value["method"], "youngmin/fatal", "bridge failed: {value}");
                if value.get("method").is_some() && value.get("id").is_some() {
                    // Server requests are rejected just like the Swift engine does.
                    let reply = json!({"id":value["id"],"error":{"code":-32601,"message":"unsupported in test"}});
                    self.send(&reply);
                    continue;
                }
                return value;
            }
            assert!(Instant::now() < deadline, "timed out waiting for the bridge");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn send(&self, message: &Value) {
        let message = CString::new(message.to_string()).unwrap();
        assert_eq!(unsafe { ym_codex_send(self.id, message.as_ptr()) }, 0);
    }

    /// The same JSON-RPC shape the Swift client sends (no jsonrpc field).
    fn request(&self, method: &str, params: Value) -> Result<Value, Value> {
        let id = format!("test-{}", self.next.get());
        self.next.set(self.next.get() + 1);
        self.send(&json!({"id":id,"method":method,"params":params}));
        let deadline = Instant::now() + Duration::from_secs(120);
        loop {
            let value = self.poll(deadline);
            if value.get("method").is_none() && value["id"] == id.as_str() {
                return match value.get("error") {
                    Some(error) => Err(error.clone()),
                    None => Ok(value["result"].clone()),
                };
            }
            self.backlog.borrow_mut().push_back(value);
        }
    }

    /// Starts a turn like `CodexEngine` and returns its completion status.
    fn turn(&self, thread: &str, text: &str) -> String {
        let started = self
            .request(
                "turn/start",
                json!({"threadId":thread,"input":[{"type":"text","text":text,"text_elements":[]}]}),
            )
            .unwrap_or_else(|error| panic!("turn/start failed: {error}"));
        let turn = started["turn"]["id"].as_str().unwrap().to_owned();
        let deadline = Instant::now() + Duration::from_secs(120);
        loop {
            let queued = self.backlog.borrow_mut().pop_front();
            let value = queued.unwrap_or_else(|| self.poll(deadline));
            let params = &value["params"];
            if value["method"] == "turn/completed"
                && params["threadId"] == thread
                && params["turn"]["id"] == turn.as_str()
            {
                if params["turn"]["status"] != "completed" {
                    eprintln!("turn ended: {value}");
                }
                return params["turn"]["status"].as_str().unwrap_or("").to_owned();
            }
        }
    }

    /// The exact parameters `CodexEngine` sends from the second message on.
    fn resume(&self, thread: &str) -> Result<Value, Value> {
        self.request(
            "thread/resume",
            json!({"threadId":thread,"model":"mock-model","developerInstructions":"test","excludeTurns":true}),
        )
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        ym_codex_stop(self.id);
    }
}

#[test]
fn later_messages_resume_the_same_thread_with_its_history() {
    let (port, bodies) = mock_model();
    let root = std::env::temp_dir().join(format!("youngmin-resume-{}", uuid::Uuid::new_v4()));
    let (home, workspace) = (root.join("codex-home"), root.join("workspace"));
    std::fs::create_dir_all(&home).unwrap();
    std::fs::write(
        home.join("config.toml"),
        format!(
            r#"model = "mock-model"
model_provider = "mock_provider"

[model_providers.mock_provider]
name = "Mock provider"
base_url = "http://127.0.0.1:{port}/v1"
wire_api = "responses"
request_max_retries = 0
stream_max_retries = 0
"#
        ),
    )
    .unwrap();

    let engine = Engine::start(&home, &workspace);
    let started = engine
        .request(
            "thread/start",
            json!({"model":"mock-model","developerInstructions":"test","dynamicTools":[],"approvalPolicy":"never","sandbox":"read-only"}),
        )
        .unwrap_or_else(|error| panic!("thread/start failed: {error}"));
    let thread = started["thread"]["id"].as_str().unwrap().to_owned();
    assert_eq!(engine.turn(&thread, "first alpha-context"), "completed");

    // Second message in the same app session: the build-26 failure.
    engine
        .resume(&thread)
        .unwrap_or_else(|error| panic!("thread/resume after the first turn failed: {error}"));
    assert_eq!(engine.turn(&thread, "second beta-context"), "completed");
    assert!(
        bodies.lock().unwrap().last().unwrap().contains("alpha-context"),
        "the second request lost the first message"
    );
    drop(engine);

    // Reopened chat: a new bridge on the same CODEX_HOME resumes from disk.
    // The previous engine shuts down asynchronously; give it time to finish.
    std::thread::sleep(Duration::from_secs(2));
    let engine = Engine::start(&home, &workspace);
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        match engine.resume(&thread) {
            Ok(_) => break,
            Err(error) if Instant::now() < deadline => {
                eprintln!("retrying thread/resume after restart: {error}");
                std::thread::sleep(Duration::from_secs(1));
            }
            Err(error) => panic!("thread/resume after restart failed: {error}"),
        }
    }
    assert_eq!(engine.turn(&thread, "third gamma-context"), "completed");
    let last = bodies.lock().unwrap().last().unwrap().clone();
    assert!(
        last.contains("alpha-context") && last.contains("beta-context"),
        "the resumed request lost earlier messages"
    );

    // Conversation deletion (ChatController.deleteConversation) with the DB.
    engine
        .request("thread/delete", json!({"threadId":thread}))
        .unwrap_or_else(|error| panic!("thread/delete failed: {error}"));
    assert!(engine.resume(&thread).is_err(), "a deleted thread still resumes");
    drop(engine);
    std::thread::sleep(Duration::from_secs(2));
    let _ = std::fs::remove_dir_all(&root);
}
