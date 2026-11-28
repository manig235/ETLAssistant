// Launch Electron directly: Playwright's default reducedMotion emulation hides OS preferences.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto'),assert=require('node:assert/strict');
if(!process.versions.electron) {
  const {spawnSync}=require('node:child_process');
  const data=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-native-motion-'));
  try {const result=spawnSync(require('electron'),[__filename],{windowsHide:true,stdio:'inherit',env:{...process.env,YOUNGMIN_MOTION_TEST_HOME:data}});process.exitCode=result.status??1;}
  finally {fs.rmSync(data,{recursive:true,force:true,maxRetries:5,retryDelay:100});}
} else {
  const {app,BrowserWindow,ipcMain}=require('electron');
  const root=path.resolve(__dirname,'..'),data=process.env.YOUNGMIN_MOTION_TEST_HOME;
  assert(data);
  app.setPath('userData',data);app.setName('영민이 모션 검사');
  let shell={pet:true,petMotion:'system'};
  const session={id:'00000000-0000-4000-8000-000000000001',projectId:'integrated',title:'모션 검사',draft:'',model:null,effort:null,phase:'idle',status:'대기 중',error:null,unread:false,updatedAt:1,artifacts:[],draftImages:[],pdfBusy:false,messages:[]};
  const state={...require('../electron/initial-state.json'),starting:false,ready:true,loggedIn:true,sessions:[session]};
  ipcMain.handle('get-state',()=>({state,shell}));ipcMain.handle('control',()=>{});ipcMain.handle('command',()=>{});
  app.whenReady().then(async()=>{
    const deadline=setTimeout(()=>{console.error('Native motion check timed out');app.exit(1);},30000);
    console.log('Native motion: opening test window');
    const window=new BrowserWindow({width:304,height:600,title:'영민이 모션 검사',frame:false,transparent:true,backgroundColor:'#00000000',alwaysOnTop:true,focusable:false,show:false,webPreferences:{preload:path.join(root,'electron/preload.cjs'),sandbox:true,contextIsolation:true,nodeIntegration:false,backgroundThrottling:false}});
    try {
      await window.loadFile(path.join(root,'dist/index.html'),{query:{pet:'1'}});window.showInactive();
      console.log('Native motion: UI loaded');
      const report={reducedMotion:await window.webContents.executeJavaScript("matchMedia('(prefers-reduced-motion:reduce)').matches"),gpu:app.getGPUFeatureStatus(),cases:[]};
      for(const policy of ['system','on','off']) {
        shell={...shell,petMotion:policy};window.webContents.send('shell-state',shell);
        for(const phase of ['idle','running','done']) {
          console.log(`Native motion: ${policy}/${phase}`);
          Object.assign(session,{phase,status:phase==='running'?'작업 중':phase==='done'?'답변이 도착했어요':'대기 중',unread:phase==='done'});window.webContents.send('state',state);
          const samples=[];
          const moving=policy==='on'||(policy==='system'&&!report.reducedMotion);
          for(let i=0;i<5;i++) {
            await new Promise(r=>setTimeout(r,150));
            const sample=await window.webContents.executeJavaScript("({policy:document.querySelector('.pet-shell').dataset.motionPolicy,motion:document.querySelector('.pet-character').dataset.motion,animations:document.querySelector('.robot').getAnimations({subtree:true}).filter(a=>a.playState==='running').length})");
            if(moving){const capture=await window.webContents.capturePage({x:70,y:448,width:164,height:150});sample.pixels=crypto.createHash('sha256').update(capture.toPNG()).digest('hex');}
            samples.push(sample);
          }
          assert(samples.every(s=>s.policy===policy));
          if(moving){assert(samples.every(s=>s.animations>0));assert(new Set(samples.map(s=>s.pixels)).size>1,`${policy}/${phase}: pixels did not change`);}
          else {assert(samples.every(s=>s.animations===0));}
          report.cases.push({policy,phase,moving,samples});
        }
      }
      const reports=path.resolve(root,'../build/reports');fs.mkdirSync(reports,{recursive:true});fs.writeFileSync(path.join(reports,'native-motion-policy.json'),JSON.stringify(report,null,2));
      console.log(`PASS: direct Electron launch, OS reducedMotion=${report.reducedMotion}; system/on/off × idle/working/done pixel changes verified without browser emulation or GPU overrides.`);
    } finally {clearTimeout(deadline);window.destroy();}
    app.quit();
  }).catch(error=>{console.error(error);app.exit(1);});
}
