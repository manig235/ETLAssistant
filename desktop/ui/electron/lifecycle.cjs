/** A native-menu restart must also work when the renderer cannot answer IPC. */
function createRestarter({app,getBackend,beforeStop,timeoutMillis=5000}) {
  let pending;
  return function restart() {
    if(pending)return pending;
    pending=(async()=>{
      try{await beforeStop();}catch{}
      const child=getBackend();
      if(child&&child.exitCode===null&&child.signalCode===null)await new Promise(resolve=>{
        let finished=false,timer,forced;
        const finish=()=>{if(finished)return;finished=true;clearTimeout(timer);clearTimeout(forced);child.removeListener('exit',finish);child.removeListener('error',finish);resolve();};
        child.once('exit',finish);child.once('error',finish);
        timer=setTimeout(()=>{try{child.kill();}catch{}if(!finished)forced=setTimeout(finish,1000);},timeoutMillis);
        // EOF lets the JVM save every session and close its own Codex transport.
        try{if(child.stdin.writable&&!child.stdin.writableEnded)child.stdin.end();}catch{}
        if(child.exitCode!==null||child.signalCode!==null)finish();
      });
      app.relaunch();
      // app.quit can wait indefinitely for an unresponsive renderer's unload event.
      app.exit(0);
    })();
    return pending;
  };
}
module.exports={createRestarter};
