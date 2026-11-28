const LOGIN_NAME='kr.snu.youngmini.desktop';
const STARTUP_ARG='--startup';

// Read the OS setting rather than a cached preference, including Task Manager opt-out.
function createStartup({app,platform=process.platform,execPath=process.execPath}) {
  const supported=platform==='win32'&&app.isPackaged;
  const options={path:execPath,args:[STARTUP_ARG]};
  let error=null;
  function snapshot() {
    if(!supported)return {supported:false,enabled:false,error:null};
    try {
      const current=app.getLoginItemSettings(options);
      return {supported:true,enabled:!!(current.openAtLogin&&current.executableWillLaunchAtLogin),error};
    } catch {return {supported:true,enabled:false,error:'자동 실행 설정을 확인하지 못했어요.'};}
  }
  function set(enabled) {
    if(!supported||typeof enabled!=='boolean')throw Error('자동 실행 설정을 바꿀 수 없어요.');
    try {
      app.setLoginItemSettings({...options,name:LOGIN_NAME,openAtLogin:enabled,enabled});
      error=null;
      if(snapshot().enabled!==enabled)throw Error();
    } catch {error='자동 실행 설정을 저장하지 못했어요. Windows 시작 앱 설정도 확인해 주세요.';throw Error(error);}
  }
  return {snapshot,set};
}
module.exports={createStartup,LOGIN_NAME,STARTUP_ARG};
