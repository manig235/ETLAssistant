// Real relaunch with a deliberately hung renderer. Uses isolated synthetic app data.
const {_electron:electron,chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),net=require('node:net'),assert=require('node:assert/strict');
(async()=>{
  const data=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-restart-'));fs.writeFileSync(path.join(data,'agenda.json'),JSON.stringify({settings:{onboarded:true}}));
  fs.writeFileSync(path.join(data,'school.json'),JSON.stringify({settings:{updates:false}}));
  const server=net.createServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const port=server.address().port;await new Promise(resolve=>server.close(resolve));
  let app,browser;
  try {
    app=await electron.launch({executablePath:process.env.YOUNGMIN_SMOKE_EXE,args:[process.env.YOUNGMIN_SMOKE_APP||path.resolve(__dirname,'..')],env:{...process.env,YOUNGMIN_DATA_HOME:data},timeout:30000});
    console.log('Restart fixture launched');
    await app.firstWindow();let main;for(let i=0;i<200&&!main;i++){main=(await app.windows()).find(p=>p.url().includes('index.html')&&!p.url().includes('?pet'));if(!main)await new Promise(resolve=>setTimeout(resolve,50));}assert(main,'Main window missing');
    async function ready(page){const until=Date.now()+60000;while(Date.now()<until){const value=await page.evaluate(()=>window.youngmini?.getState());if(value?.shell.error)throw Error(value.shell.error);if(value?.state.ready&&value.state.activeChatId)return value;await new Promise(resolve=>setTimeout(resolve,100));}throw Error('Backend ready timed out');}
    const initial=await ready(main);const chatId=initial.state.activeChatId;
    console.log('Restart fixture ready',JSON.stringify({id:chatId,ready:initial.state.ready,sessions:initial.state.sessions.length,url:main.url()}));assert.match(chatId,/^[a-f0-9-]{36}$/);
    if(process.env.YOUNGMIN_TEST_UPDATE==='1'){const result=await main.evaluate(()=>window.youngmini.control('checkUpdate'));assert.equal(typeof result.text,'string');console.log('Public Windows update check passed');}
    await main.evaluate(async id=>{await window.youngmini.command({action:'setDraft',chatId:id,text:'재시작 후에도 보관할 초안'});await window.youngmini.control('displayName','복원 친구');},chatId);
    await app.evaluate(({Menu,app},port)=>{const relaunch=app.relaunch;app.relaunch=()=>relaunch.call(app,{args:[`--remote-debugging-port=${port}`,...process.argv.slice(1).filter(a=>!a.startsWith('--inspect')&&!a.startsWith('--remote-debugging-port'))]});const original=Menu.buildFromTemplate;Menu.buildFromTemplate=function(items,...rest){const restart=items.find(item=>item.label==='복원 친구 재시작');if(restart)globalThis.restartFromNativeTray=restart.click;return original.call(this,items,...rest);};},port);
    await main.evaluate(()=>window.youngmini.control('displayName','복원 친구'));
    const exit=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Old process did not exit')),20000);app.process().once('exit',()=>{clearTimeout(timer);resolve();});});
    // The native tray callback does not depend on a working renderer.
    await main.evaluate(()=>{setTimeout(()=>{for(;;){}},50);});
    await new Promise(resolve=>setTimeout(resolve,100));
    await app.evaluate(()=>{if(!globalThis.restartFromNativeTray)throw Error('Native restart item missing');globalThis.restartFromNativeTray();});await exit;console.log('Old process exited after native restart');
    const until=Date.now()+30000;
    while(!browser&&Date.now()<until){try{browser=await chromium.connectOverCDP(`http://127.0.0.1:${port}`,{timeout:1000});}catch{await new Promise(resolve=>setTimeout(resolve,200));}}
    assert(browser,'Relaunched process missing');let page;
    for(let i=0;i<100&&!page;i++){page=browser.contexts().flatMap(c=>c.pages()).find(p=>p.url().includes('index.html')&&!p.url().includes('?pet'));if(!page)await new Promise(resolve=>setTimeout(resolve,100));}
    assert(page,'Restored main window missing');const restored=await ready(page);assert.equal(restored.shell.displayName,'복원 친구');assert.equal(restored.state.sessions.find(s=>s.id===chatId).draft,'재시작 후에도 보관할 초안');assert.equal(restored.shell.error,null);await page.screenshot({path:path.resolve(__dirname,'../../build/reports/native-restarted.png')});
    await page.evaluate(()=>window.youngmini.control('quit'));await new Promise(resolve=>setTimeout(resolve,1500));
    console.log('PASS: native tray relaunch recovers a hung renderer, releases the backend lock and restores name/chat/draft; no account used.');
  }finally {if(browser)await browser.close().catch(()=>{});if(app){let timer;await Promise.race([app.close().catch(()=>{}),new Promise(resolve=>{timer=setTimeout(()=>{app.process().kill();resolve();},3000);})]);clearTimeout(timer);}fs.rmSync(data,{recursive:true,force:true,maxRetries:20,retryDelay:100});}
})().catch(error=>{console.error(error);process.exitCode=1;});
