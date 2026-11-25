use super::{Cell, MAX_JSON_BYTES, Store, Tool};
use serde_json::{Value, json};
use std::cell::{Cell as ThreadCell, RefCell};
use std::collections::HashMap;
use std::ffi::{CString, c_char, c_int, c_uint, c_void};
use std::ptr;
use std::sync::{Arc, mpsc};
use std::time::{Duration, Instant};

type Context = *const c_void;
type Object = *mut c_void;
type JsValue = *const c_void;
type JsString = *mut c_void;
type Callback =
    unsafe extern "C" fn(Context, Object, Object, usize, *const JsValue, *mut JsValue) -> JsValue;

#[cfg_attr(
    target_vendor = "apple",
    link(name = "JavaScriptCore", kind = "framework")
)]
#[cfg_attr(not(target_vendor = "apple"), link(name = "javascriptcoregtk-4.1"))]
unsafe extern "C" {
    fn JSGlobalContextCreate(class: *const c_void) -> Context;
    fn JSGlobalContextRelease(context: Context);
    fn JSContextGetGlobalObject(context: Context) -> Object;
    fn JSContextGetGroup(context: Context) -> *const c_void;
    fn JSContextGroupSetExecutionTimeLimit(
        group: *const c_void,
        seconds: f64,
        callback: unsafe extern "C" fn(Context, *mut c_void) -> bool,
        data: *mut c_void,
    );
    fn JSContextGroupClearExecutionTimeLimit(group: *const c_void);
    fn JSStringCreateWithUTF8CString(value: *const c_char) -> JsString;
    fn JSStringRelease(value: JsString);
    fn JSStringGetMaximumUTF8CStringSize(value: JsString) -> usize;
    fn JSStringGetUTF8CString(value: JsString, buffer: *mut c_char, capacity: usize) -> usize;
    fn JSEvaluateScript(
        context: Context,
        source: JsString,
        this: Object,
        url: JsString,
        line: c_int,
        exception: *mut JsValue,
    ) -> JsValue;
    fn JSObjectMakeFunctionWithCallback(
        context: Context,
        name: JsString,
        callback: Callback,
    ) -> Object;
    fn JSObjectSetProperty(
        context: Context,
        object: Object,
        name: JsString,
        value: JsValue,
        attributes: c_uint,
        exception: *mut JsValue,
    );
    fn JSObjectMakeDeferredPromise(
        context: Context,
        resolve: *mut Object,
        reject: *mut Object,
        exception: *mut JsValue,
    ) -> Object;
    fn JSObjectCallAsFunction(
        context: Context,
        function: Object,
        this: Object,
        count: usize,
        args: *const JsValue,
        exception: *mut JsValue,
    ) -> JsValue;
    fn JSValueToStringCopy(context: Context, value: JsValue, exception: *mut JsValue) -> JsString;
    fn JSValueToNumber(context: Context, value: JsValue, exception: *mut JsValue) -> f64;
    fn JSValueToObject(context: Context, value: JsValue, exception: *mut JsValue) -> Object;
    fn JSValueIsNull(context: Context, value: JsValue) -> bool;
    fn JSValueMakeUndefined(context: Context) -> JsValue;
    fn JSValueMakeNull(context: Context) -> JsValue;
    fn JSValueMakeNumber(context: Context, value: f64) -> JsValue;
    fn JSValueMakeString(context: Context, value: JsString) -> JsValue;
    fn JSValueMakeFromJSONString(context: Context, value: JsString) -> JsValue;
    fn JSValueProtect(context: Context, value: JsValue);
    fn JSValueUnprotect(context: Context, value: JsValue);
}

struct Text(JsString);
impl Text {
    fn new(text: &str) -> Result<Self, String> {
        let text = CString::new(text).map_err(|_| "NUL character in JavaScript string")?;
        let value = unsafe { JSStringCreateWithUTF8CString(text.as_ptr()) };
        if value.is_null() {
            Err("JavaScript string allocation failed".into())
        } else {
            Ok(Self(value))
        }
    }
    fn read(&self) -> Result<String, String> {
        let size = unsafe { JSStringGetMaximumUTF8CStringSize(self.0) };
        if size > MAX_JSON_BYTES * 4 {
            return Err("JavaScript string limit exceeded".into());
        }
        let mut bytes = vec![0u8; size.max(1)];
        let written =
            unsafe { JSStringGetUTF8CString(self.0, bytes.as_mut_ptr().cast(), bytes.len()) };
        if written == 0 {
            return Err("JavaScript string conversion failed".into());
        }
        bytes.truncate(written - 1);
        String::from_utf8(bytes).map_err(|error| error.to_string())
    }
}
impl Drop for Text {
    fn drop(&mut self) {
        unsafe { JSStringRelease(self.0) }
    }
}

struct Execution<'a> {
    context: Context,
    cell: &'a Cell,
    store: Arc<Store>,
    tools: Vec<Tool>,
    pending: RefCell<HashMap<u64, (Object, Object)>>,
    timers: RefCell<HashMap<u64, (Instant, Object)>>,
    next: ThreadCell<u64>,
    result: RefCell<Option<Option<String>>>,
    completions: mpsc::SyncSender<(u64, Result<Value, String>)>,
}
thread_local! { static CURRENT: ThreadCell<*const Execution<'static>> = const { ThreadCell::new(ptr::null()) }; }

impl Execution<'_> {
    fn string(&self, value: JsValue) -> Result<String, String> {
        let mut exception = ptr::null();
        let text = unsafe { JSValueToStringCopy(self.context, value, &mut exception) };
        if text.is_null() || !exception.is_null() {
            return Err("Cannot convert JavaScript argument to text".into());
        }
        Text(text).read()
    }
    fn value(&self, text: &str) -> Result<JsValue, String> {
        let text = Text::new(text)?;
        Ok(unsafe { JSValueMakeString(self.context, text.0) })
    }
    fn json(&self, value: &Value) -> Result<JsValue, String> {
        let text = value.to_string();
        if text.len() > MAX_JSON_BYTES {
            return Err("Tool result limit exceeded; request a smaller response".into());
        }
        let text = Text::new(&text)?;
        let value = unsafe { JSValueMakeFromJSONString(self.context, text.0) };
        if value.is_null() {
            Err("Invalid JSON tool result".into())
        } else {
            Ok(value)
        }
    }
    fn id(&self) -> u64 {
        let id = self.next.get();
        self.next.set(id + 1);
        id
    }
    fn number(&self, value: JsValue) -> Result<u64, String> {
        let mut exception = ptr::null();
        let value = unsafe { JSValueToNumber(self.context, value, &mut exception) };
        if !exception.is_null()
            || !value.is_finite()
            || value < 0.0
            || value.fract() != 0.0
            || value > u32::MAX as f64
        {
            return Err("Expected a nonnegative integer".into());
        }
        Ok(value as u64)
    }
    fn call(&self, function: Object, arguments: &[JsValue]) -> Result<JsValue, String> {
        let mut exception = ptr::null();
        let value = unsafe {
            JSObjectCallAsFunction(
                self.context,
                function,
                ptr::null_mut(),
                arguments.len(),
                arguments.as_ptr(),
                &mut exception,
            )
        };
        if !exception.is_null() {
            return Err(self
                .string(exception)
                .unwrap_or_else(|_| "JavaScript execution failed".into()));
        }
        Ok(value)
    }
    fn evaluate(&self, source: &str) -> Result<JsValue, String> {
        let source = Text::new(source)?;
        let mut exception = ptr::null();
        let value = unsafe {
            JSEvaluateScript(
                self.context,
                source.0,
                ptr::null_mut(),
                ptr::null_mut(),
                1,
                &mut exception,
            )
        };
        if !exception.is_null() {
            return Err(self
                .string(exception)
                .unwrap_or_else(|_| "JavaScript execution interrupted".into()));
        }
        Ok(value)
    }
    fn dispatch(&self, args: &[JsValue]) -> Result<JsValue, String> {
        if self.cell.is_cancelled() {
            return Err("JavaScript execution cancelled".into());
        }
        let undefined = unsafe { JSValueMakeUndefined(self.context) };
        let get = |index: usize| {
            args.get(index)
                .copied()
                .ok_or_else(|| "Missing native argument".to_string())
        };
        match self.string(get(0)?)?.as_str() {
            "tool" => {
                let index = self.number(get(1)?)? as usize;
                if index >= self.tools.len() {
                    return Err("Tool is not enabled for this cell".into());
                }
                if self.pending.borrow().len() >= 64 {
                    return Err("Too many concurrent tool calls".into());
                }
                let input = self.string(get(2)?)?;
                if input.len() > MAX_JSON_BYTES {
                    return Err("Tool argument limit exceeded".into());
                }
                let input: Value =
                    serde_json::from_str(&input).map_err(|error| error.to_string())?;
                let mut resolve = ptr::null_mut();
                let mut reject = ptr::null_mut();
                let mut exception = ptr::null();
                let promise = unsafe {
                    JSObjectMakeDeferredPromise(
                        self.context,
                        &mut resolve,
                        &mut reject,
                        &mut exception,
                    )
                };
                if promise.is_null() || !exception.is_null() {
                    return Err("Cannot create JavaScript promise".into());
                }
                unsafe {
                    JSValueProtect(self.context, resolve);
                    JSValueProtect(self.context, reject);
                }
                let id = self.id();
                self.pending.borrow_mut().insert(id, (resolve, reject));
                let sender = self.completions.clone();
                self.cell.host.invoke(
                    index,
                    input,
                    Box::new(move |result| {
                        let _ = sender.try_send((id, result));
                    }),
                );
                return Ok(promise);
            }
            "emit" => {
                let text = self.string(get(1)?)?;
                if text.len() > MAX_JSON_BYTES {
                    return Err("Output item limit exceeded".into());
                }
                let value: Value =
                    serde_json::from_str(&text).map_err(|error| error.to_string())?;
                match value["type"].as_str() {
                    Some("input_text") if value["text"].is_string() => {}
                    Some("input_image") if value["image_url"].is_string() => {}
                    Some("input_audio") if value["audio_url"].is_string() => {}
                    _ => return Err("Unsupported output content".into()),
                }
                self.cell.emit(value)?;
            }
            "store" => {
                let key = self.string(get(1)?)?;
                let value = self.string(get(2)?)?;
                if key.len() > 1024 || value.len() > MAX_JSON_BYTES {
                    return Err("Store value limit exceeded".into());
                }
                self.store.set(
                    key,
                    serde_json::from_str(&value).map_err(|error| error.to_string())?,
                )?;
            }
            "load" => {
                return match self.store.get(&self.string(get(1)?)?) {
                    Some(value) => self.value(&value.to_string()),
                    None => Ok(unsafe { JSValueMakeNull(self.context) }),
                };
            }
            "yield" => self.cell.yield_now(),
            "notify" => {
                let message = self.string(get(1)?)?;
                if message.len() > 64 * 1024 {
                    return Err("Notification limit exceeded".into());
                }
                self.cell.host.notify(message);
            }
            "done" => {
                let value = get(1)?;
                let error = if unsafe { JSValueIsNull(self.context, value) } {
                    None
                } else {
                    Some(self.string(value)?)
                };
                *self.result.borrow_mut() = Some(error);
            }
            "timer" => {
                if self.timers.borrow().len() >= 1024 {
                    return Err("Too many timers".into());
                }
                let mut exception = ptr::null();
                let callback = unsafe { JSValueToObject(self.context, get(1)?, &mut exception) };
                if callback.is_null() || !exception.is_null() {
                    return Err("Timer requires a callback".into());
                }
                let delay = self.number(get(2)?)?.min(120_000);
                let id = self.id();
                unsafe {
                    JSValueProtect(self.context, callback);
                }
                self.timers.borrow_mut().insert(
                    id,
                    (Instant::now() + Duration::from_millis(delay), callback),
                );
                return Ok(unsafe { JSValueMakeNumber(self.context, id as f64) });
            }
            "clearTimer" => {
                let removed = self.timers.borrow_mut().remove(&self.number(get(1)?)?);
                if let Some((_, callback)) = removed {
                    unsafe {
                        JSValueUnprotect(self.context, callback);
                    }
                }
            }
            _ => return Err("Unsupported native operation".into()),
        }
        Ok(undefined)
    }
    fn resolve(&self, id: u64, result: Result<Value, String>) -> Result<(), String> {
        let callbacks = self.pending.borrow_mut().remove(&id);
        let Some((resolve, reject)) = callbacks else {
            return Ok(());
        };
        let value = match result.and_then(|value| self.json(&value)) {
            Ok(value) => self.call(resolve, &[value]),
            Err(error) => self.call(reject, &[self.value(&error)?]),
        };
        unsafe {
            JSValueUnprotect(self.context, resolve);
            JSValueUnprotect(self.context, reject);
        }
        value.map(|_| ())
    }
    fn fire_timers(&self) -> Result<(), String> {
        let due: Vec<u64> = self
            .timers
            .borrow()
            .iter()
            .filter(|(_, (time, _))| *time <= Instant::now())
            .map(|(id, _)| *id)
            .collect();
        for id in due {
            let removed = self.timers.borrow_mut().remove(&id);
            if let Some((_, callback)) = removed {
                let result = self.call(callback, &[]);
                unsafe {
                    JSValueUnprotect(self.context, callback);
                }
                result?;
            }
        }
        Ok(())
    }
}

unsafe extern "C" fn native(
    context: Context,
    _: Object,
    _: Object,
    count: usize,
    arguments: *const JsValue,
    exception: *mut JsValue,
) -> JsValue {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if count > 8 || arguments.is_null() {
            return Err("Invalid native call".into());
        }
        let execution = CURRENT.with(|current| current.get());
        if execution.is_null() {
            return Err("JavaScript cell is closed".into());
        }
        unsafe { (&*execution).dispatch(std::slice::from_raw_parts(arguments, count)) }
    }));
    match result.unwrap_or_else(|_| Err("JavaScript native callback failed".into())) {
        Ok(value) => value,
        Err(error) => {
            if !exception.is_null() {
                if let Ok(text) = Text::new(&error) {
                    unsafe {
                        *exception = JSValueMakeString(context, text.0);
                    }
                }
            }
            unsafe { JSValueMakeUndefined(context) }
        }
    }
}
struct Watchdog<'a> {
    cell: &'a Cell,
    started: Instant,
}
unsafe extern "C" fn should_terminate(_: Context, data: *mut c_void) -> bool {
    let watchdog = unsafe { &*(data as *const Watchdog<'_>) };
    watchdog.cell.is_cancelled() || watchdog.started.elapsed() >= Duration::from_secs(120)
}

impl Drop for Execution<'_> {
    fn drop(&mut self) {
        // Every protected value belongs to this thread and is released before
        // its context. Late tool completions can only reach the closed channel.
        for (_, (resolve, reject)) in self.pending.get_mut().drain() {
            unsafe {
                JSValueUnprotect(self.context, resolve);
                JSValueUnprotect(self.context, reject);
            }
        }
        for (_, (_, callback)) in self.timers.get_mut().drain() {
            unsafe {
                JSValueUnprotect(self.context, callback);
            }
        }
        CURRENT.with(|current| current.set(ptr::null()));
        unsafe {
            JSContextGroupClearExecutionTimeLimit(JSContextGetGroup(self.context));
            JSGlobalContextRelease(self.context);
        }
    }
}

pub(super) fn run(
    cell: &Cell,
    source: String,
    tools: Vec<Tool>,
    store: Arc<Store>,
) -> Result<(), String> {
    let context = unsafe { JSGlobalContextCreate(ptr::null()) };
    if context.is_null() {
        return Err("Cannot create JavaScriptCore context".into());
    }
    let (sender, receiver) = mpsc::sync_channel(64);
    let execution = Execution {
        context,
        cell,
        store,
        tools,
        pending: RefCell::new(HashMap::new()),
        timers: RefCell::new(HashMap::new()),
        next: ThreadCell::new(1),
        result: RefCell::new(None),
        completions: sender,
    };
    CURRENT.with(|current| current.set((&execution as *const Execution<'_>).cast()));
    let mut watchdog = Watchdog {
        cell,
        started: Instant::now(),
    };
    unsafe {
        JSContextGroupSetExecutionTimeLimit(
            JSContextGetGroup(context),
            0.1,
            should_terminate,
            (&mut watchdog as *mut Watchdog<'_>).cast(),
        );
    }
    let name = Text::new("__ymNative")?;
    let callback = unsafe { JSObjectMakeFunctionWithCallback(context, name.0, native) };
    let mut exception = ptr::null();
    unsafe {
        JSObjectSetProperty(
            context,
            JSContextGetGlobalObject(context),
            name.0,
            callback,
            0,
            &mut exception,
        );
    }
    if !exception.is_null() {
        return Err("Cannot initialize JavaScript native callbacks".into());
    }
    let metadata: Vec<Value> = execution
        .tools
        .iter()
        .map(|tool| json!({"name":tool.name,"description":tool.description}))
        .collect();
    let bootstrap = format!(
        "const __ymRun = ({}) (__ymNative, {}); delete globalThis.__ymNative; delete globalThis.console; delete globalThis.Atomics; delete globalThis.SharedArrayBuffer; delete globalThis.WebAssembly; __ymRun({});",
        include_str!("bootstrap.js"),
        serde_json::to_string(&metadata).unwrap(),
        serde_json::to_string(&source).unwrap()
    );
    execution.evaluate(&bootstrap)?;
    loop {
        if cell.is_cancelled() {
            return Ok(());
        }
        if watchdog.started.elapsed() > Duration::from_secs(120) {
            return Err("JavaScript cell exceeded its execution time limit".into());
        }
        if let Some(error) = execution.result.borrow_mut().take() {
            return error.map_or(Ok(()), Err);
        }
        execution.fire_timers()?;
        match receiver.recv_timeout(Duration::from_millis(5)) {
            Ok((id, result)) => execution.resolve(id, result)?,
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                return Err("JavaScript tool channel closed".into());
            }
        }
    }
}
