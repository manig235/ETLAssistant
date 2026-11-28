const crypto=require('node:crypto');
const ORIGIN='https://myetl.snu.ac.kr',SELF=ORIGIN+'/api/v1/users/self',API=ORIGIN+'/api/v1/users/self/tokens',PROFILE=ORIGIN+'/profile/tokens';
const ERROR='eTL에 연결하지 못했어요. 다시 로그인하거나 토큰을 직접 붙여넣어 주세요.';
function allowedLoginURL(raw) {
  try {const u=new URL(raw);return u.protocol==='https:'&&!u.username&&!u.password&&(!u.port||u.port==='443')&&(u.hostname==='snu.ac.kr'||u.hostname.endsWith('.snu.ac.kr'));}catch{return false;}
}
function loginCandidate(raw) {
  try {const u=new URL(raw);return allowedLoginURL(raw)&&u.origin===ORIGIN&&!/^\/(?:login|logout|learningx\/login|learningx\/logout|xn-sso|passni)(?:\/|$)/i.test(u.pathname);}catch{return false;}
}
function json(body){try{return JSON.parse(body.replace(/^\s*while\(1\);/,''));}catch{return null;}}
function isSelf(body){const value=json(body);return !!value&&Number.isSafeInteger(Number(value.id))&&Number(value.id)>0;}
function issuedToken(body) {
  const value=json(body);const item=value?.access_token||value;
  for(const field of ['token','full_token','visible_token']) {
    const token=typeof item?.[field]==='string'?item[field].trim():'';
    if(token.length>=32&&token.length<=512&&!/\s|\.\.\.|…|\*/.test(token)&&/^[A-Za-z0-9_~.-]+$/.test(token))return token;
  }
  return null;
}
function csrfValue(cookies) {
  try {const value=decodeURIComponent(cookies.find(c=>c.name==='_csrf_token')?.value||'');return value&&value.length<=512&&/^[\x21-\x7e]+$/.test(value)?value:null;}catch{return null;}
}
async function bodyLimited(response) {
  if(Number(response.headers.get('content-length'))>256*1024)throw Error(ERROR);
  const reader=response.body?.getReader();if(!reader)return '';
  const chunks=[];let size=0;
  try {while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>256*1024)throw Error(ERROR);chunks.push(Buffer.from(value));}}
  finally {await reader.cancel().catch(()=>{});}
  return Buffer.concat(chunks).toString('utf8');
}
/** Fixed URLs only. Fallback only on 404; never retry an ambiguous token POST. */
async function issueToken({request,cookies,purpose,check=()=>{}}) {
  check();const self=await request(SELF,{method:'GET'});check();
  if(self.status!==200||!isSelf(self.body))return null;
  const csrf=csrfValue(await cookies());if(!csrf)throw Error(ERROR);
  check();
  const expires=new Date(Date.now()+90*86400000).toISOString();
  const post=(url,legacy=false)=>{
    const fields={'token[purpose]':purpose,'token[expires_at]':expires};
    if(legacy){fields['access_token[purpose]']=purpose;fields['access_token[expires_at]']=expires;}
    return request(url,{method:'POST',headers:{'X-CSRF-Token':csrf,'X-Requested-With':'XMLHttpRequest','Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'},body:new URLSearchParams(fields).toString()});
  };
  let result=await post(API);check();
  if(result.status===404){result=await post(PROFILE,true);check();}
  if(result.status<200||result.status>=300)throw Error(result.status===401||result.status===403?'eTL에서 토큰 발급이 막혀 있어요. 토큰을 직접 붙여넣어 주세요.':ERROR);
  const token=issuedToken(result.body);if(!token)throw Error(ERROR);
  // Confirm the bearer grant without the login cookies before handing it to the backend vault.
  const verified=await request(SELF,{method:'GET',credentials:'omit',headers:{Authorization:`Bearer ${token}`}});check();
  if(verified.status!==200||!isSelf(verified.body))throw Error(ERROR);
  return token;
}
async function beginEtlLogin({BrowserWindow,session,parent,name,onToken,onStatus,canConnect=()=>true}) {
  const isolated=session.fromPartition(`youngmini-etl-login-${crypto.randomUUID()}`,{cache:false});
  await Promise.all([isolated.clearStorageData(),isolated.clearCache(),isolated.clearAuthCache()]);
  isolated.setPermissionRequestHandler((_,__,reply)=>reply(false));isolated.setPermissionCheckHandler(()=>false);
  isolated.webRequest.onBeforeRequest({urls:['<all_urls>']},(details,reply)=>reply({cancel:!allowedLoginURL(details.url)}));
  isolated.on('will-download',event=>event.preventDefault());
  const window=new BrowserWindow({parent,width:920,height:790,minWidth:640,minHeight:500,title:'서울대 로그인으로 연결',show:false,autoHideMenuBar:true,webPreferences:{session:isolated,sandbox:true,contextIsolation:true,nodeIntegration:false,webSecurity:true,allowRunningInsecureContent:false,spellcheck:false,devTools:false}});
  window.setMenu(null);
  let active=true,checking=false,issued=false,apiMissing=false,profilePosted=false,poll,timer;const abort=new AbortController();
  function check(){if(!active||!canConnect())throw Error('진행 중인 작업을 마친 뒤 다시 연결해 주세요.');}
  async function cleanup(){if(!active)return;active=false;abort.abort();clearInterval(poll);clearTimeout(timer);if(!window.isDestroyed())window.destroy();await Promise.allSettled([isolated.clearStorageData(),isolated.clearCache(),isolated.clearAuthCache()]);onStatus(false,null);}
  async function fail(message){await cleanup();onStatus(false,message);}
  async function request(url,options={}) {
    if(![SELF,API,PROFILE].includes(url)||(options.method==='POST'&&![API,PROFILE].includes(url)))throw Error(ERROR);
    if(options.method==='POST') {
      if(url===API){if(issued)throw Error(ERROR);issued=true;}
      else {if(!apiMissing||profilePosted)throw Error(ERROR);profilePosted=true;}
    }
    check();const timeout=new AbortController();const stop=()=>timeout.abort();abort.signal.addEventListener('abort',stop,{once:true});const limit=setTimeout(stop,12000);
    try {const response=await isolated.fetch(url,{...options,headers:{Accept:'application/json',...options.headers},credentials:options.credentials||'include',redirect:'manual',cache:'no-store',signal:timeout.signal});if(url===API&&options.method==='POST')apiMissing=response.status===404;return {status:response.status,body:await bodyLimited(response)};}
    catch {throw Error(ERROR);}finally {clearTimeout(limit);abort.signal.removeEventListener('abort',stop);}
  }
  async function maybeConnect() {
    if(!active||checking||issued||window.isDestroyed()||!loginCandidate(window.webContents.getURL()))return;
    checking=true;
    try {
      const token=await issueToken({request,cookies:()=>isolated.cookies.get({url:ORIGIN}),purpose:`${name.slice(0,24)} (Windows)`,check:()=>{check();}});
      if(!token)return;
      check();issued=true;window.setEnabled(false);
      await onToken(token);await cleanup();
    } catch(e) {if(active)await fail([ERROR,'eTL에서 토큰 발급이 막혀 있어요. 토큰을 직접 붙여넣어 주세요.','진행 중인 작업을 마친 뒤 다시 연결해 주세요.'].includes(e.message)?e.message:ERROR);}
    finally {checking=false;}
  }
  // The single checking coroutine spans both POST attempts, so reloads cannot create another token.
  window.webContents.setWindowOpenHandler(({url})=>{if(allowedLoginURL(url))void window.loadURL(url).catch(()=>{});return {action:'deny'};});
  for(const event of ['will-navigate','will-redirect'])window.webContents.on(event,(e,url)=>{if(!allowedLoginURL(url))e.preventDefault();});
  window.webContents.on('will-attach-webview',event=>event.preventDefault());
  window.webContents.on('did-finish-load',()=>void maybeConnect());window.webContents.on('did-navigate-in-page',()=>void maybeConnect());
  window.on('closed',()=>void cleanup());onStatus(true,null);
  poll=setInterval(()=>void maybeConnect(),2000);timer=setTimeout(()=>void fail('로그인 시간이 지났어요. 다시 연결해 주세요.'),10*60*1000);
  void window.loadURL(ORIGIN+'/login').then(()=>{if(active)window.show();}).catch(()=>fail(ERROR));
  return {window,cancel:cleanup};
}
module.exports={allowedLoginURL,loginCandidate,issuedToken,csrfValue,issueToken,beginEtlLogin};
