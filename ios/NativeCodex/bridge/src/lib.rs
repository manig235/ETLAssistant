//! In-process JSON-RPC transport for the official Codex app-server.
//! No listening socket, helper executable, or token transfer through Swift.
use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, mpsc};

use codex_app_server::in_process::{self, EmbeddedNetworkPolicy, InProcessServerEvent, InProcessStartArgs};
use codex_app_server_protocol::{ClientInfo, ClientNotification, ClientRequest, InitializeCapabilities, InitializeParams, JSONRPCMessage};
use codex_core::config::ConfigBuilder;
use serde_json::{Value, json};
use tokio::sync::mpsc as async_mpsc;

struct Bridge {
    commands: async_mpsc::Sender<Value>,
    events: Mutex<mpsc::Receiver<String>>,
    failed: Arc<AtomicBool>,
}
static NEXT: AtomicU64 = AtomicU64::new(1);
static HANDLES: OnceLock<Mutex<HashMap<u64, Arc<Bridge>>>> = OnceLock::new();
fn handles() -> &'static Mutex<HashMap<u64, Arc<Bridge>>> {
    HANDLES.get_or_init(Default::default)
}
fn handle(id: u64) -> Option<Arc<Bridge>> {
    handles().lock().ok()?.get(&id).cloned()
}
fn publish(tx: &mpsc::SyncSender<String>, failed: &AtomicBool, value: Value) {
    if tx.try_send(value.to_string()).is_err() {
        // Never silently lose a tool request or a final notification.
        failed.store(true, Ordering::Release);
    }
}
fn fatal(tx: &mpsc::SyncSender<String>, failed: &AtomicBool, message: &str) {
    publish(tx, failed, json!({"method":"youngmin/fatal","params":{"message":message}}));
}

/// Paths must be UTF-8, NUL-terminated, and valid for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ym_codex_start(home: *const c_char, workspace: *const c_char) -> u64 {
    if home.is_null() || workspace.is_null() { return 0; }
    let (Ok(home), Ok(workspace)) = (unsafe { CStr::from_ptr(home) }.to_str(), unsafe { CStr::from_ptr(workspace) }.to_str()) else { return 0; };
    let home = PathBuf::from(home);
    let workspace = PathBuf::from(workspace);
    if !home.is_absolute() || !workspace.is_absolute() { return 0; }
    let (command_tx, command_rx) = async_mpsc::channel(64);
    let (event_tx, event_rx) = mpsc::sync_channel(512);
    let failed = Arc::new(AtomicBool::new(false));
    let bridge = Arc::new(Bridge { commands: command_tx, events: Mutex::new(event_rx), failed: failed.clone() });
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let Ok(mut table) = handles().lock() else { return 0; };
    table.insert(id, bridge);
    drop(table);
    let started = std::thread::Builder::new().name("youngmin-codex".into()).stack_size(16 * 1024 * 1024).spawn(move || {
        let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2)
                .thread_stack_size(16 * 1024 * 1024).enable_all().build();
            match runtime {
                Ok(runtime) => {
                    runtime.block_on(run(home, workspace, command_rx, event_tx.clone(), failed.clone()));
                    runtime.shutdown_timeout(std::time::Duration::from_secs(3));
                }
                Err(_) => fatal(&event_tx, &failed, "Codex 실행 환경을 시작하지 못했어요"),
            }
        }));
        if outcome.is_err() { fatal(&event_tx, &failed, "Codex 실행이 중단됐어요. 앱을 다시 열어 주세요"); }
    });
    if started.is_err() { handles().lock().ok().map(|mut t| t.remove(&id)); return 0; }
    id
}

/// Returns 0 on acceptance, 1 on backpressure, and -1 for invalid/closed handles.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ym_codex_send(id: u64, message: *const c_char) -> i32 {
    if message.is_null() { return -1; }
    let Some(bridge) = handle(id) else { return -1; };
    let Ok(bytes) = unsafe { CStr::from_ptr(message) }.to_str() else { return -1; };
    if bytes.len() > 16 * 1024 * 1024 { return -1; }
    let Ok(value) = serde_json::from_str(bytes) else { return -1; };
    match bridge.commands.try_send(value) {
        Ok(()) => 0,
        Err(async_mpsc::error::TrySendError::Full(_)) => 1,
        Err(_) => -1,
    }
}

/// Non-blocking. Caller releases a returned message with ym_codex_free_string.
#[unsafe(no_mangle)]
pub extern "C" fn ym_codex_poll(id: u64) -> *mut c_char {
    let Some(bridge) = handle(id) else { return std::ptr::null_mut(); };
    let value = if bridge.failed.swap(false, Ordering::AcqRel) {
        json!({"method":"youngmin/fatal","params":{"message":"Codex 이벤트 수신이 지연됐어요. 앱을 다시 열어 주세요"}}).to_string()
    } else {
        let Ok(events) = bridge.events.lock() else { return std::ptr::null_mut(); };
        let Ok(value) = events.try_recv() else { return std::ptr::null_mut(); };
        value
    };
    CString::new(value).map(CString::into_raw).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn ym_codex_free_string(value: *mut c_char) {
    if !value.is_null() { drop(unsafe { CString::from_raw(value) }); }
}

/// Non-blocking. Dropping channels initiates bounded shutdown off the UI thread.
#[unsafe(no_mangle)]
pub extern "C" fn ym_codex_stop(id: u64) {
    if let Ok(mut table) = handles().lock() { table.remove(&id); }
}

async fn run(home: PathBuf, workspace: PathBuf, mut commands: async_mpsc::Receiver<Value>, events: mpsc::SyncSender<String>, failed: Arc<AtomicBool>) {
    if std::fs::create_dir_all(&home).and_then(|_| std::fs::create_dir_all(&workspace)).is_err() {
        fatal(&events, &failed, "Codex 저장 공간을 만들지 못했어요"); return;
    }
    let mut overrides = vec![
        ("model".into(), toml::Value::String("gpt-5.5".into())),
        ("cli_auth_credentials_store".into(), toml::Value::String("keyring".into())),
        ("web_search".into(), toml::Value::String("disabled".into())),
    ];
    for key in ["shell_tool", "unified_exec", "code_mode", "code_mode_only", "code_mode_host", "multi_agent", "apps", "view_image", "apply_patch_freeform"] {
        overrides.push((format!("features.{key}"), toml::Value::Boolean(false)));
    }
    let loaders = codex_config::LoaderOverrides::default();
    let policy = EmbeddedNetworkPolicy::load(&loaders).await;
    let config = ConfigBuilder::default().codex_home(home.clone()).fallback_cwd(Some(workspace))
        .cli_overrides(overrides.clone()).loader_overrides(loaders.clone()).build().await;
    let Ok(mut config) = config else { fatal(&events, &failed, "Codex 설정을 불러오지 못했어요"); return; };
    policy.activate(&mut config);
    let paths = std::env::current_exe().ok().and_then(|p| codex_exec_server::ExecServerRuntimeOptions::new(p, None).ok());
    let environment = codex_exec_server::EnvironmentManager::from_codex_home(&home, paths, config.http_client_factory()).await;
    let Ok(environment) = environment else { fatal(&events, &failed, "Codex 앱 실행 환경을 구성하지 못했어요"); return; };
    let args = InProcessStartArgs {
        arg0_paths: codex_arg0::Arg0DispatchPaths::default(), config: Arc::new(config), cli_overrides: overrides,
        loader_overrides: loaders, strict_config: false, cloud_config_bundle: Default::default(),
        embedded_network_policy: policy, thread_config_loader: Arc::new(codex_config::NoopThreadConfigLoader),
        feedback: codex_feedback::CodexFeedback::new(), log_db: None, state_db: None,
        environment_manager: Arc::new(environment), config_warnings: vec![],
        session_source: codex_protocol::protocol::SessionSource::Exec, enable_codex_api_key_env: false,
        initialize: InitializeParams { client_info: ClientInfo { name: "youngmin_ios".into(), title: Some("영민이".into()), version: env!("CARGO_PKG_VERSION").into() },
            capabilities: Some(InitializeCapabilities { experimental_api: true, ..Default::default() }) }, channel_capacity: 256,
    };
    let client = in_process::start(args).await;
    let Ok(mut client) = client else { fatal(&events, &failed, "Codex 초기화에 실패했어요"); return; };
    publish(&events, &failed, json!({"method":"youngmin/ready","params":{}}));
    loop {
        if failed.load(Ordering::Acquire) { break; }
        tokio::select! {
            value = commands.recv() => {
                let Some(value) = value else { break; };
                let decoded = serde_json::from_value::<JSONRPCMessage>(value.clone());
                match decoded {
                    Ok(JSONRPCMessage::Request(request)) => {
                        let sender = client.sender(); let tx = events.clone(); let health = failed.clone();
                        tokio::spawn(async move {
                            let listing_models = request.method == "model/list";
                            let id = request.id;
                            let result = match serde_json::from_value::<ClientRequest>(value) {
                                Ok(request) => match sender.request(request).await {
                                    Ok(Ok(mut result)) => {
                                        if listing_models { retain_direct_models(&mut result); }
                                        json!({"id":id,"result":result})
                                    },
                                    Ok(Err(error)) => json!({"id":id,"error":error}),
                                    Err(_) => json!({"id":id,"error":{"code":-32000,"message":"Codex 연결이 종료됐어요"}}),
                                },
                                Err(_) => json!({"id":id,"error":{"code":-32602,"message":"지원하지 않는 Codex 요청이에요"}}),
                            };
                            publish(&tx, &health, result);
                        });
                    }
                    Ok(JSONRPCMessage::Notification(_)) => {
                        if let Ok(notification) = serde_json::from_value::<ClientNotification>(value) { let _ = client.notify(notification); }
                    }
                    Ok(JSONRPCMessage::Response(response)) => { let _ = client.respond_to_server_request(response.id, response.result); }
                    Ok(JSONRPCMessage::Error(error)) => { let _ = client.fail_server_request(error.id, error.error); }
                    Err(_) => fatal(&events, &failed, "Codex 요청 형식이 올바르지 않아요"),
                }
            }
            event = client.next_event() => {
                match event {
                    Some(InProcessServerEvent::ServerRequest(request)) => {
                        if let Ok(value) = serde_json::to_value(request) { publish(&events, &failed, value); }
                    }
                    Some(InProcessServerEvent::ServerNotification(notification)) => {
                        if let Ok(value) = serde_json::to_value(notification) { publish(&events, &failed, value); }
                    }
                    Some(InProcessServerEvent::Lagged { .. }) => { fatal(&events, &failed, "Codex 이벤트가 누락됐어요. 다시 시도해 주세요"); break; }
                    None => { fatal(&events, &failed, "Codex 연결이 종료됐어요"); break; }
                }
            }
        }
    }
    let _ = client.shutdown().await;
}

fn retain_direct_models(result: &mut Value) {
    // Use capabilities from the pinned engine's own catalog. Models requiring
    // a separate JavaScript host are unavailable in this native mobile build.
    let catalog: Value = serde_json::from_str(include_str!("../../models-manager/models.json")).expect("pinned model catalog");
    let compatible: Vec<&str> = catalog["models"].as_array().into_iter().flatten()
        .filter(|m| m["tool_mode"].as_str() != Some("code_mode_only"))
        .filter_map(|m| m["slug"].as_str()).collect();
    if let Some(models) = result.get_mut("data").and_then(Value::as_array_mut) {
        models.retain(|m| m["model"].as_str().is_some_and(|slug| compatible.contains(&slug)));
        for (index, model) in models.iter_mut().enumerate() { model["isDefault"] = json!(index == 0); }
    }
}
