const {app, BrowserWindow, ipcMain, shell, Tray, Menu, nativeImage, dialog, clipboard, screen, Notification} = require('electron');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {StringDecoder} = require('node:string_decoder');
const {isExternalLink, validCommand, isInside} = require('./validation.cjs');
const {displayName,petMotion,readAppearance,saveAppearance}=require('./appearance.cjs');
const {importImages,importFiles}=require('./images.cjs');
const {beginEtlLogin}=require('./etl-login.cjs');
const {createStartup,STARTUP_ARG}=require('./startup.cjs');
const startup=createStartup({app});
const startHidden=process.argv.includes(STARTUP_ARG);

const home = process.env.YOUNGMIN_DESKTOP_HOME || (app.isPackaged ? path.dirname(process.execPath) : path.resolve(__dirname, '../../build/shell-dev'));
const data = process.env.YOUNGMIN_DATA_HOME || (process.platform === 'win32' ? path.join(process.env.LOCALAPPDATA, 'YoungMini') : path.join(app.getPath('home'), '.local/share/youngmini-desktop'));
fs.mkdirSync(data, {recursive:true});
fs.mkdirSync(path.join(data,'shell'), {recursive:true});
app.setPath('userData', path.join(data, 'shell'));
app.setName('영민이');
const settingsFile = path.join(data, 'appearance.json');
let settings=readAppearance(settingsFile);
let state = structuredClone(require('./initial-state.json'));
let main, pet, tray, backend, quitting=false, shellError=null, dragOrigin, openChatPulse=0, etlLoginFlow=null, etlLoginBusy=false, etlLoginError=null,openUpdatePulse=0;
const etlConnections=new Map();
const entry = path.resolve(__dirname, '../dist/index.html');
const allowedURL = pathToFileURL(entry).href;
const appIcon = path.join(__dirname, '../dist/robot.png');
const trusted = event => [main?.webContents,pet?.webContents].includes(event.sender) && event.senderFrame === event.sender.mainFrame && event.senderFrame.url.split('?')[0] === allowedURL;
const prints=new Map(), previewRequests=new Map(), attempted=new Set();let printQueue=Promise.resolve();
const restart=require('./lifecycle.cjs').createRestarter({app,getBackend:()=>backend,beforeStop:async()=>{quitting=true;await etlLoginFlow?.cancel();for(const done of etlConnections.values())done(false);etlConnections.clear();}});
const send=command=>{if(backend?.stdin.writable)backend.stdin.write(JSON.stringify(command)+'\n');};
const sentUpdates=new Set();
const updateManager=require('./updates.cjs').createUpdater({file:path.join(data,'windows-updates.json'),current:app.getVersion(),fetcher:(...args)=>require('electron').session.fromPartition('youngmini-public-updates').fetch(...args),onChange:()=>publishShell(),onAvailable:()=>notifyUpdate()});
function notifyUpdate(){const update=updateManager.snapshot().available;if(!state.ready||state.school?.settings?.updates===false||!update||sentUpdates.has(update.version)||!backend?.stdin.writable)return;sentUpdates.add(update.version);send({action:'windowsUpdate',version:update.version,url:update.url});}
function checkUpdates(){if(state.ready&&state.school?.settings?.updates!==false){notifyUpdate();void updateManager.check();}}


if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', (_,argv) => {if(!argv.includes(STARTUP_ARG))show();});
  app.whenReady().then(() => {
    app.setAppUserModelId('kr.snu.youngmini.desktop');
    // The app UI loads bundled assets only. eTL login uses a separate ephemeral session.
    const session = require('electron').session.defaultSession;
    session.webRequest.onBeforeRequest({urls:['http://*/*','https://*/*','ws://*/*','wss://*/*']}, (_, callback) => callback({cancel:true}));
    session.setPermissionRequestHandler((_, __, callback) => callback(false));
    session.setPermissionCheckHandler(() => false);
    main = createWindow({width:1560, height:980, minWidth:720, minHeight:500, show:!startHidden, title:settings.displayName, backgroundColor:'#f7f8fa'});
    main.loadFile(entry);
    main.on('close', event => {if (!quitting) {event.preventDefault(); main.hide();}});
    main.on('show', () => publishShell());
    main.on('focus', () => publishShell());
    if (settings.pet) createPet();
    tray = new Tray(nativeImage.createFromPath(appIcon).resize({width:24,height:24}));
    tray.setToolTip(settings.displayName); tray.on('click', show); updateTray();
    startBackend();
    const updateTimer=setInterval(checkUpdates,60*60*1000);updateTimer.unref();
  }).catch(() => {dialog.showErrorBox('영민이', '앱을 실행하지 못했어요. 압축을 모두 푼 뒤 다시 실행해 주세요.'); app.quit();});
}
function createWindow(options) {
  const {backgroundThrottling=true,...windowOptions}=options;
  const window = new BrowserWindow({...windowOptions, icon:appIcon, webPreferences:{preload:path.join(__dirname,'preload.cjs'), contextIsolation:true, sandbox:true, nodeIntegration:false, webSecurity:true, devTools:!app.isPackaged, spellcheck:false,backgroundThrottling}});
  window.setMenu(null);
  window.webContents.setWindowOpenHandler(() => ({action:'deny'}));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  return window;
}
function show() {if (!main || main.isDestroyed()) return; if (main.isMinimized()) main.restore(); main.show(); main.focus();}
function createPet() {
  if (pet && !pet.isDestroyed()) {pet.showInactive(); return;}
  const area = screen.getPrimaryDisplay().workArea;
  pet = createWindow({width:304,height:600,x:area.x+area.width-326,y:area.y+area.height-612,frame:false,transparent:true,backgroundColor:'#00000000',resizable:false,alwaysOnTop:true,skipTaskbar:true,show:false,focusable:false,hasShadow:false,backgroundThrottling:false,title:settings.displayName+' 펫'});
  pet.setIgnoreMouseEvents(true,{forward:true});
  pet.loadFile(entry, {query:{pet:'1'}}); pet.showInactive();
  pet.on('close', event => {if (!quitting) {event.preventDefault(); setPet(false);}});
}
function setPet(value) {
  settings.pet = !!value;
  saveAppearance(settingsFile,settings);
  if (settings.pet) createPet(); else pet?.hide();
  updateTray(); publishShell();
}
function updateTray() {tray?.setContextMenu(Menu.buildFromTemplate([{label:'채팅 열기',click:()=>{revealChat();show();}},{label:'바탕화면 펫',type:'checkbox',checked:settings.pet,click:item=>setPet(item.checked)},{type:'separator'},{label:settings.displayName+' 재시작',click:()=>void restart()},{label:settings.displayName+' 종료',click:()=>app.quit()}]));}
function publish(channel, value) {for (const window of [main,pet]) if (window && !window.isDestroyed()) window.webContents.send(channel,value);}
function shellState() {return {...settings,startup:startup.snapshot(),error:shellError,openChat:openChatPulse,openUpdate:openUpdatePulse,update:updateManager.snapshot(),etlLoginBusy,etlLoginError};}
function revealChat(){++openChatPulse;publishShell();}
function publishShell() {publish('shell-state',shellState());}
function setDisplayName(value) {
  const name=displayName(value);if(!name)throw Error('이름은 1~24자로 입력해 주세요.');
  const next={...settings,displayName:name};
  try {saveAppearance(settingsFile,next);}catch {throw Error('이름을 저장하지 못했어요. 다시 시도해 주세요.');}
  settings=next;send({action:'assistantName',name});main?.setTitle(name);pet?.setTitle(name+' 펫');tray?.setToolTip(name);updateTray();publishShell();
}
function setPetMotion(value) {
  const motion=petMotion(value);if(!motion)throw Error('모션 설정을 확인해 주세요.');
  const next={...settings,petMotion:motion};saveAppearance(settingsFile,next);settings=next;publishShell();
}
async function startEtlLogin() {
  if(etlLoginBusy){if(etlLoginFlow&&!etlLoginFlow.window.isDestroyed())etlLoginFlow.window.focus();return;}
  if(!backend?.stdin.writable||state.etlBusy||state.sessions.some(s=>s.phase==='running'))return;
  etlLoginBusy=true;etlLoginError=null,openUpdatePulse=0;publishShell();
  try {
    etlLoginFlow=await beginEtlLogin({BrowserWindow,session:require('electron').session,parent:main,name:settings.displayName,
      canConnect:()=>!!backend?.stdin.writable&&!state.etlBusy&&!state.sessions.some(s=>s.phase==='running'),
      onStatus:(busy,error)=>{etlLoginBusy=busy;etlLoginError=error;if(!busy)etlLoginFlow=null;publishShell();},
      onToken:token=>new Promise((resolve,reject)=>{
        const requestId=require('node:crypto').randomUUID();
        const timer=setTimeout(()=>{etlConnections.delete(requestId);reject(Error('저장 실패'));},25000);
        etlConnections.set(requestId,success=>{clearTimeout(timer);success?resolve():reject(Error('저장 실패'));});
        send({action:'connectEtlIssued',requestId,token});
      })});
  } catch {etlLoginBusy=false;etlLoginError='로그인 창을 열지 못했어요. 토큰을 직접 붙여넣어 주세요.';publishShell();}
}
function startBackend() {
  const java = path.join(home, 'jre/bin', process.platform==='win32' ? 'java.exe' : 'java');
  const env = {...process.env};
  delete env.JAVA_TOOL_OPTIONS; delete env._JAVA_OPTIONS; delete env.JDK_JAVA_OPTIONS;
  const classpath=[path.join(home,'lib/youngmini-desktop-0.4.4.jar'),path.join(home,'lib/*')].join(path.delimiter);
  backend=spawn(java,['-Djava.awt.headless=true','-Dfile.encoding=UTF-8',`-Dyoungmini.home=${home}`,`-Dyoungmini.data=${data}`,`-Dyoungmini.assets=${__dirname}`,'-cp',classpath,'kr.snu.youngmini.desktop.BridgeMainKt'],{cwd:home,stdio:['pipe','pipe','pipe'],windowsHide:true,env});
  send({action:'assistantName',name:settings.displayName});
  backend.stderr.resume(); // Never persist credential-bearing or source-provided stderr.
  let pending=''; const decoder=new StringDecoder('utf8');
  backend.stdout.on('data', bytes => {
    pending += decoder.write(bytes);
    if (pending.length>32*1024*1024) {backend.kill(); return;}
    let end;
    while ((end=pending.indexOf('\n'))>=0) {
      const line=pending.slice(0,end); pending=pending.slice(end+1);
      let message; try {message=JSON.parse(line);} catch {continue;}
      if (message.type==='state') {state=message.state; publish('state',state); deliverNotices(); checkUpdates();}
      else if(message.type==='etlConnectionResult'){const done=etlConnections.get(message.requestId);if(done){etlConnections.delete(message.requestId);done(message.success===true);}}
      else if(message.type==='renderPdf') {printQueue=printQueue.then(()=>renderPdf(message)).catch(()=>{});}
      else if(message.type==='artifactPreview'){const request=previewRequests.get(message.requestId);if(request){previewRequests.delete(message.requestId);try{const file=fs.realpathSync(message.path),root=fs.realpathSync(path.join(data,'workspace/generated'));if(!isInside(root,file)||path.extname(file).toLowerCase()!=='.pdf'||fs.statSync(file).size>50*1024*1024)throw Error();request.resolve({kind:'pdf',bytes:new Uint8Array(fs.readFileSync(file)),mime:'application/pdf'});}catch{request.resolve({kind:'text',text:'원본 미리보기를 열지 못했어요. 외부 앱으로 열어 주세요.'});}}}
      else if(message.type==='artifactText') {const request=previewRequests.get(message.requestId);if(request){previewRequests.delete(message.requestId);request.resolve({kind:'text',text:message.text});}}
      else if (message.type==='browse' && isExternalLink(message.url,true)) void shell.openExternal(message.url);
      else if (message.type==='openPath') {
        // The UI cannot supply a filesystem path. Only canonical backend results are opened.
        try {const target=fs.realpathSync(message.path); const root=fs.realpathSync(path.join(data,'workspace/etl-files')); if (isInside(root,target)) void shell.openPath(target);} catch {}
      } else if (message.type==='fatal' || message.type==='commandError') {shellError=message.message; publishShell();}
    }
  });
  backend.on('error', () => {shellError='동봉된 Java 실행 파일을 찾지 못했어요. 압축을 모두 풀어 주세요.'; publishShell();});
  backend.on('exit', () => {if (!quitting) {state={...state,ready:false,starting:false}; shellError='비서 프로세스가 종료됐어요. 앱을 다시 실행해 주세요.'; publish('state',state); publishShell();}});
}

ipcMain.handle('get-state', event => {if (!trusted(event)) throw Error('Denied'); return {state,shell:shellState()};});
ipcMain.handle('command', (event, command) => {
  if (!trusted(event) || !validCommand(command)) throw Error('Denied');
  if(command.action==='send'&&importingImages.has(command.chatId))throw Error('사진 첨부가 끝난 뒤 보내 주세요.');
  if (backend?.stdin.writable) {send(command);if(['openChat','openProject','newChat','focus','saveProject','askAnnouncement'].includes(command.action))revealChat();}
});
ipcMain.handle('control', async(event, action, value) => {
  if (!trusted(event)) throw Error('Denied');
  switch(action) {
    case 'show': if(event.sender===pet?.webContents)revealChat();show(); break;
    case 'etlLogin': if(event.sender===main?.webContents)void startEtlLogin(); break;
    case 'cancelEtlLogin': if(event.sender===main?.webContents)void etlLoginFlow?.cancel(); break;
    case 'openArtifact': {const file=artifactFile(value);if(file)return shell.openPath(file);break;}
    case 'saveArtifact': {const file=artifactFile(value);if(!file)break;return dialog.showSaveDialog(main,{defaultPath:state.sessions.flatMap(s=>[...(s.artifacts||[]),...(s.draftImages||[])]).find(a=>a.id===value)?.name||path.basename(file)}).then(result=>{if(!result.canceled&&result.filePath)fs.copyFileSync(file,result.filePath);});}
    case 'checkUpdate': {
      if(event.sender!==main?.webContents)throw Error('Denied');const result=await updateManager.check(true);
      if(result.available){++openUpdatePulse;publishShell();}
      return {text:result.error||result.available?.text||'공개된 새 Windows 버전이 없어요.'};
    }
    case 'openUpdate': if(event.sender===main?.webContents){++openUpdatePulse;publishShell();}break;
    case 'deferUpdate': if(event.sender===main?.webContents)updateManager.later(value);break;
    case 'downloadUpdate': {if(event.sender!==main?.webContents)throw Error('Denied');const update=updateManager.snapshot().available;if(!update||update.version!==value)throw Error('업데이트를 다시 확인해 주세요.');return shell.openExternal(update.url);}
    case 'restart': void restart(); break;
    case 'quit': app.quit(); break;
    case 'displayName': setDisplayName(value); break;
    case 'petMotion': setPetMotion(value); break;
    case 'startup': {
      if(event.sender!==main?.webContents||typeof value!=='boolean')throw Error('Denied');
      try {startup.set(value);}finally {publishShell();}
      break;
    }
    case 'petMenu': if(event.sender===pet?.webContents) Menu.buildFromTemplate([
      {label:'채팅 열기',click:()=>{revealChat();show();}},
      {type:'separator'},
      {label:settings.displayName+' 재시작',click:()=>void restart()},
      {label:settings.displayName+' 종료',click:()=>app.quit()},
    ]).popup({window:pet}); break;
    case 'pet': if(typeof value==='boolean') setPet(value); break;
    case 'openLink': if(isExternalLink(value)) return shell.openExternal(value); break;
    case 'loginBrowser': if(isExternalLink(state.loginUrl,true)) return shell.openExternal(state.loginUrl); break;
    case 'copy': if(typeof value==='string' && value.length<=1000000) clipboard.writeText(value); break;
    case 'dragStart': if(event.sender===pet?.webContents) dragOrigin=pet.getPosition(); break;
    case 'hitTest': if(event.sender===pet?.webContents && typeof value==='boolean') pet.setIgnoreMouseEvents(!value,{forward:true}); break;
    case 'drag': if(event.sender===pet?.webContents && dragOrigin && Number.isFinite(value?.dx) && Number.isFinite(value?.dy) && Math.abs(value.dx)<10000 && Math.abs(value.dy)<10000) {
      const x=Math.round(dragOrigin[0]+value.dx), y=Math.round(dragOrigin[1]+value.dy);
      const area=screen.getDisplayNearestPoint({x,y}).workArea;
      pet.setPosition(Math.max(area.x-190,Math.min(x,area.x+area.width-100)),Math.max(area.y-430,Math.min(y,area.y+area.height-130)));
    } break;
  }
});
function artifactFile(id) {
  if(typeof id!=='string')return null;
  const artifact=state.sessions.flatMap(s=>[...(s.artifacts||[]),...(s.draftImages||[])]).find(a=>a.id===id);if(!artifact)return null;
  try {
    const root=fs.realpathSync(path.join(data,'workspace'));
    const target=fs.realpathSync(path.resolve(root,artifact.path));
    if(!['etl-files','generated','uploads'].some(dir=>isInside(path.join(root,dir),target))||!fs.statSync(target).isFile())return null;
    return target;
  } catch{return null;}
}
const importingImages=new Set();
async function attachFiles(event,chatId,images,importer) {
  if(!trusted(event)||event.sender!==main?.webContents||typeof chatId!=='string'||!/^[a-f0-9-]{36}$/.test(chatId)||importingImages.has(chatId))throw Error('Denied');
  const chat=state.sessions.find(s=>s.id===chatId);
  if(!chat||chat.phase==='running'||!backend?.stdin.writable)throw Error('사진을 첨부할 대화가 준비되지 않았어요.');
  if(!Array.isArray(images)||images.length+(chat.draftImages||[]).length>5)throw Error('사진은 5장까지 첨부할 수 있어요.');
  importingImages.add(chatId);
  try {
    const added=await importer(images,path.join(data,'workspace'),bytes=>nativeImage.createFromBuffer(bytes));
    if(added.reduce((n,i)=>n+i.bytes,0)+(chat.draftImages||[]).reduce((n,i)=>n+i.bytes,0)>50*1024*1024){
      await Promise.all(added.map(i=>fs.promises.unlink(path.join(data,'workspace',i.path)).catch(()=>{})));throw Error('사진 합계는 50MB까지 첨부할 수 있어요.');
    }
    send({action:'attachImages',chatId,images:added});
  } finally {importingImages.delete(chatId);}
}
ipcMain.handle('import-images',(event,chatId,images)=>attachFiles(event,chatId,images,importImages));
ipcMain.handle('import-files',(event,chatId,files)=>attachFiles(event,chatId,files,importFiles));
ipcMain.handle('read-artifact', async(event,id)=> {
  if(!trusted(event))throw Error('Denied');const file=artifactFile(id);if(!file)throw Error('파일을 찾지 못했어요.');
  if(fs.statSync(file).size>50*1024*1024)throw Error('50MB를 넘는 파일은 외부 앱으로 열어 주세요.');
  const ext=path.extname(file).toLowerCase();
  if(['.pdf','.png','.jpg','.jpeg','.gif','.webp'].includes(ext))return {kind:ext==='.pdf'?'pdf':'image',bytes:new Uint8Array(await fs.promises.readFile(file)),mime:ext==='.pdf'?'application/pdf':ext==='.jpg'||ext==='.jpeg'?'image/jpeg':`image/${ext.slice(1)}`};
  if(['.md','.txt','.csv','.json','.log','.tex'].includes(ext))return {kind:'text',text:(await fs.promises.readFile(file)).subarray(0,2*1024*1024).toString('utf8')};
  const requestId=require('node:crypto').randomUUID();
  return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{previewRequests.delete(requestId);reject(Error('미리보기를 준비하지 못했어요. 외부 앱으로 열어 주세요.'));},['.docx','.xlsx','.pptx'].includes(ext)?95000:20000);previewRequests.set(requestId,{resolve:result=>{clearTimeout(timer);resolve(result);}});send({action:['.docx','.xlsx','.pptx'].includes(ext)?'artifactPreview':'artifactText',id,requestId});});
});
ipcMain.handle('get-print', (event,id)=> {
  const job=prints.get(id);if(!job||event.sender!==job.window.webContents||event.senderFrame!==event.sender.mainFrame)throw Error('Denied');
  return {name:job.name,markdown:job.markdown};
});
ipcMain.handle('print-ready', (event,id)=> {
  const job=prints.get(id);if(!job||event.sender!==job.window.webContents||job.printing)return;
  job.printing=true;job.ready();
});
async function renderPdf(message) {
  let window;
  try {
    if(!/^[a-f0-9-]{36}$/.test(message.id)||typeof message.markdown!=='string'||message.markdown.length>300000)throw Error();
    const dir=path.join(data,'workspace/generated');fs.mkdirSync(dir,{recursive:true});
    const root=fs.realpathSync(dir);const target=path.join(root,path.basename(message.path));
    if(path.resolve(message.path)!==target||fs.existsSync(target))throw Error();
    window=createWindow({show:false,width:794,height:1123,backgroundThrottling:false,title:'PDF 만들기'});
    let ready;const waiting=new Promise((resolve,reject)=>{ready=resolve;const timer=setTimeout(()=>reject(Error('PDF timeout')),60000);window.once('closed',()=>clearTimeout(timer));});
    prints.set(message.id,{window,name:message.name,markdown:message.markdown,ready,printing:false});
    await window.loadFile(entry,{query:{print:message.id}});await waiting;
    const buffer=await window.webContents.printToPDF({pageSize:'A4',printBackground:true,preferCSSPageSize:true,generateTaggedPDF:true,margins:{top:0.55,bottom:0.55,left:0.55,right:0.55}});
    if(buffer.length>20*1024*1024)throw Error();
    await fs.promises.writeFile(target,buffer,{flag:'wx'});send({action:'pdfRendered',id:message.id,success:true});
  } catch {send({action:'pdfRendered',id:message.id,success:false});}
  finally {prints.delete(message.id);if(window&&!window.isDestroyed())window.destroy();}
}
function deliverNotices() {
  const prefs=state.agenda?.settings||{},extra=state.school?.settings||{};
  const now=new Date(),minute=Number(new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Seoul',hour:'numeric',minute:'2-digit',hour12:false}).format(now).split(':')[0])*60+Number(new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Seoul',minute:'numeric'}).format(now));
  const quiet=extra.quiet&&(extra.quietStart===extra.quietEnd|| (extra.quietStart<extra.quietEnd?minute>=extra.quietStart&&minute<extra.quietEnd:minute>=extra.quietStart||minute<extra.quietEnd));
  for(const notice of state.agenda?.notices||[]) {
    if(notice.delivered||attempted.has(notice.id))continue;
    if(notice.kind==='updates'&&!require('./updates.cjs').newer(notice.updateVersion,app.getVersion())){send({action:'noticeAck',id:notice.id});continue;}
    if(prefs[notice.kind]===false || extra[notice.kind]===false || (notice.hiddenOnly&&main?.isVisible()&&!main.isMinimized())) {send({action:'noticeAck',id:notice.id});continue;}
    if(quiet||!Notification.isSupported())continue;
    attempted.add(notice.id);
    try {
      const notification=new Notification({title:notice.title,body:extra.hideContent?'앱을 열어 새 알림을 확인해 주세요.':notice.body,icon:appIcon,silent:false});
      notification.once('show',()=>send({action:'noticeAck',id:notice.id}));
      notification.once('click',()=>{send({action:'noticeAck',id:notice.id,read:true});if(notice.chatId){send({action:'openChat',chatId:notice.chatId});revealChat();}else if(notice.kind==='updates'){++openUpdatePulse;publishShell();}else publish('shell-state',{...shellState(),openAgenda:Date.now()});show();});
      notification.once('failed',()=>{setTimeout(()=>attempted.delete(notice.id),60000).unref();});notification.show();
    } catch {setTimeout(()=>attempted.delete(notice.id),60000).unref();}
  }
}
app.on('window-all-closed', () => {});
app.on('before-quit', () => {
  quitting=true;void etlLoginFlow?.cancel();for(const done of etlConnections.values())done(false);etlConnections.clear();if(backend?.stdin.writable&&!backend.stdin.writableEnded)backend.stdin.end();
  const child=backend;
  const timeout=setTimeout(()=>child?.kill(),2500); timeout.unref();
});
