use super::{Cell, MAX_JSON_BYTES, Store, Tool};
use serde_json::{Value, json};
use std::cell::{Cell as ThreadCell, RefCell};
use std::collections::HashMap;
use std::ffi::{CString, c_char, c_int, c_void};
use std::ptr;
use std::sync::{Arc, mpsc};
use std::time::{Duration, Instant};
type Context = *mut c_void;
type Raw = *mut c_void;
unsafe extern "C" {
    fn ym_runtime() -> Raw;
    fn ym_runtime_free(rt: Raw);
    fn ym_context(rt: Raw) -> Context;
    fn ym_context_free(ctx: Context);
    fn ym_limits(rt: Raw, interrupt: unsafe extern "C" fn(Raw, Raw)->c_int, data: Raw);
    fn ym_free(value: Raw);
    fn ym_dup(value: Raw)->Raw;
    fn ym_string(ctx: Context,text:*const c_char,len:usize)->Raw;
    fn ym_text(value: Raw,len:*mut usize)->*mut c_char;
    fn ym_text_free(ctx: Context,text:*mut c_char);
    fn ym_number(value: Raw,out:*mut f64)->c_int;
    fn ym_null(value: Raw)->c_int;
    fn ym_function(value: Raw)->c_int;
    fn ym_undefined(ctx: Context)->Raw;
    fn ym_null_value(ctx: Context)->Raw;
    fn ym_number_value(ctx: Context,value:f64)->Raw;
    fn ym_json(ctx:Context,text:*const c_char,len:usize)->Raw;
    fn ym_eval(ctx:Context,text:*const c_char,len:usize)->Raw;
    fn ym_exception(ctx:Context)->Raw;
    fn ym_error(ctx:Context,text:*const c_char)->Raw;
    fn ym_call(function:Raw,count:c_int,args:*const Raw)->Raw;
    fn ym_promise(ctx:Context,resolve:*mut Raw,reject:*mut Raw)->Raw;
    fn ym_install(ctx:Context,callback:unsafe extern "C" fn(Context,c_int,*const Raw)->Raw)->c_int;
    fn ym_job(rt:Raw)->c_int;
}
struct Owned(Raw);
impl Owned {
    fn take(ctx: Context, raw:Raw)->Result<Self,String> {
        if raw.is_null() { Err(exception(ctx)) } else { Ok(Self(raw)) }
    }
    fn into_raw(self)->Raw { let raw=self.0; std::mem::forget(self); raw }
}
impl Drop for Owned { fn drop(&mut self) { unsafe { ym_free(self.0) } } }
fn string(ctx:Context,raw:Raw)->Result<String,String> {
    let mut len=0;
    let bytes=unsafe { ym_text(raw,&mut len) };
    if bytes.is_null() { return Err("Cannot convert JavaScript value to text".into()) }
    let result=if len>MAX_JSON_BYTES*4 { Err("JavaScript string limit exceeded".into()) }
        else { Ok(String::from_utf8_lossy(unsafe {std::slice::from_raw_parts(bytes.cast(),len)}).into_owned()) };
    unsafe { ym_text_free(ctx,bytes) }; result
}
fn exception(ctx:Context)->String {
    let raw=unsafe { ym_exception(ctx) };
    if raw.is_null() { return "JavaScript execution interrupted or out of memory".into() }
    let value=Owned(raw);
    string(ctx,value.0).unwrap_or_else(|_|"JavaScript execution failed".into())
}
struct Execution<'a> {
    context: Context,
    runtime: Raw,
    cell: &'a Cell,
    store: Arc<Store>,
    tools: Vec<Tool>,
    pending: RefCell<HashMap<u64,(Owned,Owned)>>,
    timers: RefCell<HashMap<u64,(Instant,Owned)>>,
    next: ThreadCell<u64>,
    result: RefCell<Option<Option<String>>>,
    completions: mpsc::SyncSender<(u64,Result<Value,String>)>,
}
thread_local! { static CURRENT: ThreadCell<*const Execution<'static>> = const { ThreadCell::new(ptr::null()) }; }
impl Execution<'_> {
    fn string(&self,value:Raw)->Result<String,String> { string(self.context,value) }
    fn value(&self,text:&str)->Result<Owned,String> {
        Owned::take(self.context,unsafe{ym_string(self.context,text.as_ptr().cast(),text.len())})
    }
    fn json(&self,value:&Value)->Result<Owned,String> {
        let text=value.to_string();
        if text.len()>MAX_JSON_BYTES {return Err("Tool result limit exceeded; request a smaller response".into())}
        Owned::take(self.context,unsafe{ym_json(self.context,text.as_ptr().cast(),text.len())})
    }
    fn id(&self)->u64 {let id=self.next.get();self.next.set(id+1);id}
    fn number(&self,value:Raw)->Result<u64,String> {
        let mut number=0.;
        if unsafe{ym_number(value,&mut number)}<0 || !number.is_finite() || number<0. || number.fract()!=0. || number>u32::MAX as f64 {
            return Err("Expected a nonnegative integer".into())
        }
        Ok(number as u64)
    }
    fn call(&self,function:&Owned,args:&[Raw])->Result<Owned,String> {
        Owned::take(self.context,unsafe{ym_call(function.0,args.len() as c_int,args.as_ptr())})
    }
    fn evaluate(&self,source:&str)->Result<Owned,String> {
        Owned::take(self.context,unsafe{ym_eval(self.context,source.as_ptr().cast(),source.len())})
    }
    fn dispatch(&self,args:&[Raw])->Result<Owned,String> {
        if self.cell.is_cancelled() || self.cell.exited() {return Err("JavaScript execution cancelled".into())}
        let get=|index:usize|args.get(index).copied().ok_or_else(||"Missing native argument".to_string());
        match self.string(get(0)?)?.as_str() {
            "tool"=>{
                let index=self.number(get(1)?)? as usize;
                if index>=self.tools.len(){return Err("Tool is not enabled for this cell".into())}
                if self.pending.borrow().len()>=64{return Err("Too many concurrent tool calls".into())}
                let input=self.string(get(2)?)?;
                if input.len()>MAX_JSON_BYTES{return Err("Tool argument limit exceeded".into())}
                let input:Value=serde_json::from_str(&input).map_err(|e|e.to_string())?;
                let mut resolve=ptr::null_mut();let mut reject=ptr::null_mut();
                let promise=Owned::take(self.context,unsafe{ym_promise(self.context,&mut resolve,&mut reject)})?;
                let id=self.id();
                self.pending.borrow_mut().insert(id,(Owned(resolve),Owned(reject)));
                let sender=self.completions.clone();
                self.cell.host.invoke(index,input,Box::new(move|result|{let _=sender.try_send((id,result));}));
                return Ok(promise)
            }
            "emit"=>{
                let text=self.string(get(1)?)?;
                if text.len()>MAX_JSON_BYTES{return Err("Output item limit exceeded".into())}
                let value:Value=serde_json::from_str(&text).map_err(|e|e.to_string())?;
                match value["type"].as_str(){
                    Some("input_text") if value["text"].is_string()=>{},
                    Some("input_image") if value["image_url"].is_string()=>{},
                    Some("input_audio") if value["audio_url"].is_string()=>{},
                    _=>return Err("Unsupported output content".into())
                }
                self.cell.emit(value)?;
            }
            "store"=>{
                let key=self.string(get(1)?)?;let value=self.string(get(2)?)?;
                if key.len()>1024 || value.len()>MAX_JSON_BYTES{return Err("Store value limit exceeded".into())}
                self.store.set(key,serde_json::from_str(&value).map_err(|e|e.to_string())?)?;
            }
            "load"=>return match self.store.get(&self.string(get(1)?)?){
                Some(value)=>self.value(&value.to_string()),
                None=>Owned::take(self.context,unsafe{ym_null_value(self.context)})
            },
            "yield"=>self.cell.yield_now(),
            "exit"=>self.cell.request_exit(),
            "notify"=>{
                let message=self.string(get(1)?)?;
                if message.len()>64*1024{return Err("Notification limit exceeded".into())}
                self.cell.host.notify(message);
            }
            "done"=>{
                let value=get(1)?;
                let error=if unsafe{ym_null(value)}!=0{None}else{Some(self.string(value)?)};
                *self.result.borrow_mut()=Some(error);
            }
            "timer"=>{
                if self.timers.borrow().len()>=1024{return Err("Too many JavaScript timers".into())}
                let raw=get(1)?;
                if unsafe{ym_function(raw)}==0{return Err("Timer callback must be a function".into())}
                let callback=Owned::take(self.context,unsafe{ym_dup(raw)})?;
                let delay=self.number(get(2)?)?.min(120_000);let id=self.id();
                self.timers.borrow_mut().insert(id,(Instant::now()+Duration::from_millis(delay),callback));
                return Owned::take(self.context,unsafe{ym_number_value(self.context,id as f64)})
            }
            "clearTimer"=>{self.timers.borrow_mut().remove(&self.number(get(1)?)?);}
            _=>return Err("Unsupported native operation".into())
        }
        Owned::take(self.context,unsafe{ym_undefined(self.context)})
    }
    fn resolve(&self,id:u64,result:Result<Value,String>)->Result<(),String>{
        let Some((resolve,reject))=self.pending.borrow_mut().remove(&id) else{return Ok(())};
        match result.and_then(|value|self.json(&value)){
            Ok(value)=>{self.call(&resolve,&[value.0])?;},
            Err(error)=>{let value=self.value(&error)?;self.call(&reject,&[value.0])?;}
        }
        Ok(())
    }
    fn fire_timers(&self)->Result<(),String>{
        let due:Vec<u64>=self.timers.borrow().iter().filter(|(_, (t,_))|*t<=Instant::now()).map(|(id,_)|*id).collect();
        for id in due {
            let removed=self.timers.borrow_mut().remove(&id);
            if let Some((_,callback))=removed{self.call(&callback,&[])?;}
        }
        Ok(())
    }
}
unsafe extern "C" fn native(context:Context,count:c_int,args:*const Raw)->Raw {
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{
        if count<0 || count>8 || args.is_null(){return Err("Invalid native call".into())}
        let execution=CURRENT.with(|current|current.get());
        if execution.is_null(){return Err("JavaScript cell is closed".into())}
        unsafe{(&*execution).dispatch(std::slice::from_raw_parts(args,count as usize))}
    }));
    match result.unwrap_or_else(|_|Err("JavaScript native callback failed".into())){
        Ok(value)=>value.into_raw(),
        Err(error)=>{
            let text=CString::new(error.replace('\0',"�")).unwrap();
            unsafe{ym_error(context,text.as_ptr())}
        }
    }
}
struct Watchdog<'a>{cell:&'a Cell,started:Instant}
unsafe extern "C" fn should_terminate(_:Raw,data:Raw)->c_int {
    let watchdog=unsafe{&*(data as *const Watchdog<'_>)};
    (watchdog.cell.is_cancelled() || watchdog.cell.exited() || watchdog.started.elapsed()>=Duration::from_secs(120)) as c_int
}
impl Drop for Execution<'_>{
    fn drop(&mut self){
        self.pending.get_mut().clear();self.timers.get_mut().clear();
        CURRENT.with(|current|current.set(ptr::null()));
        unsafe{ym_context_free(self.context);ym_runtime_free(self.runtime);}
    }
}
pub(super) fn run(cell:&Cell,source:String,tools:Vec<Tool>,store:Arc<Store>)->Result<(),String>{
    let runtime=unsafe{ym_runtime()};
    if runtime.is_null(){return Err("Cannot create QuickJS runtime".into())}
    let mut watchdog=Watchdog{cell,started:Instant::now()};
    unsafe{ym_limits(runtime,should_terminate,(&mut watchdog as *mut Watchdog<'_>).cast())};
    let context=unsafe{ym_context(runtime)};
    if context.is_null(){unsafe{ym_runtime_free(runtime)};return Err("Cannot create QuickJS context".into())}
    let(sender,receiver)=mpsc::sync_channel(64);
    let execution=Execution{context,runtime,cell,store,tools,pending:RefCell::new(HashMap::new()),timers:RefCell::new(HashMap::new()),next:ThreadCell::new(1),result:RefCell::new(None),completions:sender};
    CURRENT.with(|current|current.set((&execution as *const Execution<'_>).cast()));
    if unsafe{ym_install(context,native)}<0{return Err(exception(context))}
    let metadata:Vec<Value>=execution.tools.iter().map(|tool|json!({"name":tool.name,"description":tool.description})).collect();
    let bootstrap=format!(
        "const __ymRun = ({}) (__ymNative, {}); delete globalThis.__ymNative; delete globalThis.console; delete globalThis.Atomics; delete globalThis.SharedArrayBuffer; delete globalThis.WebAssembly; __ymRun({});",
        include_str!("bootstrap.js"),serde_json::to_string(&metadata).unwrap(),serde_json::to_string(&source).unwrap());
    execution.evaluate(&bootstrap)?;
    loop{
        if cell.is_cancelled() || cell.exited(){return Ok(())}
        if watchdog.started.elapsed()>Duration::from_secs(120){return Err("JavaScript cell exceeded its execution time limit".into())}
        // One job per iteration: an endless chain of microtasks must not starve
        // cancellation, timers or tool completions. The engine interrupt also
        // stops synchronous loops inside each individual job.
        if unsafe{ym_job(runtime)}<0{return Err(exception(context))}
        if let Some(error)=execution.result.borrow_mut().take(){return error.map_or(Ok(()),Err)}
        execution.fire_timers()?;
        match receiver.try_recv(){
            Ok((id,result))=>execution.resolve(id,result)?,
            Err(mpsc::TryRecvError::Empty)=>{std::thread::sleep(Duration::from_millis(1));},
            Err(mpsc::TryRecvError::Disconnected)=>return Err("JavaScript tool channel closed".into())
        }
    }
}
