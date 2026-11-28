// Synthetic release responses in an isolated real Electron/JVM app. No public download or account.
const {_electron:electron}=require('playwright'),fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const current=require('../package.json').version.split('.').map(Number);const next=[current[0],current[1],current[2]+1].join('.'),after=[current[0],current[1],current[2]+2].join('.');
(async()=>{
  const data=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-updates-native-'));
  fs.writeFileSync(path.join(data,'agenda.json'),JSON.stringify({settings:{onboarded:true}}));fs.writeFileSync(path.join(data,'school.json'),JSON.stringify({settings:{updates:false}}));
  let app,page;
  async function launch(){
    app=await electron.launch({executablePath:process.env.YOUNGMIN_SMOKE_EXE,args:[process.env.YOUNGMIN_SMOKE_APP||path.resolve(__dirname,'..')],env:{...process.env,YOUNGMIN_DATA_HOME:data},timeout:30000});
    await app.firstWindow();for(let i=0;i<200&&!page;i++){page=(await app.windows()).find(p=>p.url().includes('index.html')&&!p.url().includes('?pet'));if(!page)await new Promise(r=>setTimeout(r,50));}
    assert(page);await wait(s=>s.ready&&s.activeChatId);
    await app.evaluate(({Notification,session,shell},next)=>{
      globalThis.updateFixture={calls:0,toasts:[],opened:null,version:next};
      Notification.isSupported=()=>true;Notification.prototype.show=function(){globalThis.updateFixture.toasts.push({title:this.title,body:this.body});globalThis.updateFixture.toast=this;this.emit('show');};
      shell.openExternal=async url=>{globalThis.updateFixture.opened=url;};
      session.fromPartition('youngmini-public-updates').fetch=async url=>{if(!url.startsWith('https://api.github.com/repos/manig235/ETLAssistant/releases?'))throw Error('Unexpected fixture request');const f=globalThis.updateFixture;f.calls++;const v=f.version;return new Response(JSON.stringify([{tag_name:'v9.0.0',assets:[{name:'youngmin.apk'}]},{tag_name:`windows-v${v}`,draft:false,prerelease:false,body:'Windows 업데이트 알림 개선',assets:[{name:`youngmin-windows-x64-v${v}.zip`,state:'uploaded',size:400000000,browser_download_url:`https://github.com/manig235/ETLAssistant/releases/download/windows-v${v}/youngmin-windows-x64-v${v}.zip`}]}]),{headers:{'content-type':'application/json'}});};
    },next);
  }
  async function wait(test){const until=Date.now()+45000;while(Date.now()<until){const result=await page.evaluate(()=>window.youngmini.getState());if(result.shell.error)throw Error(result.shell.error);if(test(result.state,result.shell))return result;await page.waitForTimeout(80);}throw Error('State timed out');}
  async function close(){await app.close();app=null;page=null;}
  try {
    await launch();await page.evaluate(()=>window.youngmini.command({action:'schoolSettings',settings:{updates:true}}));
    await page.getByLabel('Windows 업데이트 알림').waitFor();await wait(s=>s.agenda.notices.some(n=>n.kind==='updates'&&n.delivered));assert.equal(await app.evaluate(()=>globalThis.updateFixture.calls),1);assert.equal(await app.evaluate(()=>globalThis.updateFixture.toasts.length),1);
    await app.evaluate(()=>globalThis.updateFixture.toast.emit('click'));const dialog=page.getByRole('dialog');await dialog.getByRole('heading',{name:`Windows ${next} 업데이트`,exact:true}).waitFor();await dialog.getByRole('button',{name:'GitHub에서 받기 ↗',exact:true}).click();assert.equal(await app.evaluate(()=>globalThis.updateFixture.opened),`https://github.com/manig235/ETLAssistant/releases/download/windows-v${next}/youngmin-windows-x64-v${next}.zip`);
    await page.screenshot({path:path.resolve(__dirname,'../../build/reports/native-windows-update.png')});await dialog.getByRole('button',{name:'나중에 알림',exact:true}).click();await wait((s,shell)=>shell.update.remindAt>Date.now());await page.getByLabel('Windows 업데이트 알림').waitFor({state:'detached'});
    await page.evaluate(()=>window.youngmini.command({action:'schoolSettings',settings:{casual:true}}));await wait(s=>s.school.settings.casual);assert.equal(await app.evaluate(()=>globalThis.updateFixture.calls),1);await close();
    await launch();await wait(s=>s.school.settings.updates);assert.equal(await app.evaluate(()=>globalThis.updateFixture.calls),0);assert.equal((await page.evaluate(()=>window.youngmini.getState())).state.agenda.notices.filter(n=>n.kind==='updates').length,1);assert.equal(await page.getByLabel('Windows 업데이트 알림').count(),0);
    await page.evaluate(()=>window.youngmini.command({action:'schoolSettings',settings:{updates:false}}));await wait(s=>!s.school.settings.updates);await app.evaluate((_,version)=>{globalThis.updateFixture.version=version;},after);await page.evaluate(()=>window.youngmini.control('checkUpdate'));await page.getByRole('dialog').getByRole('heading',{name:`Windows ${after} 업데이트`,exact:true}).waitFor();assert.equal(await app.evaluate(()=>globalThis.updateFixture.toasts.length),0);assert.equal((await page.evaluate(()=>window.youngmini.getState())).state.agenda.notices.filter(n=>n.kind==='updates').length,1);
    console.log('PASS: actual app auto-check → durable update inbox → native toast dispatch/click → reviewed download, six-hour cache, one-day snooze/restart and opt-out; synthetic releases only.');
  }finally {if(app){let timer;await Promise.race([app.close().catch(()=>{}),new Promise(r=>{timer=setTimeout(()=>{app.process().kill();r();},3000);})]);clearTimeout(timer);}fs.rmSync(data,{recursive:true,force:true,maxRetries:20,retryDelay:100});}
})().catch(e=>{console.error(e);process.exitCode=1;});
