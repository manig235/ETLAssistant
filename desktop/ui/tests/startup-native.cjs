// Real packaged Windows shell; isolated app data and mocked login APIs avoid changing startup.
const {_electron:electron}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
(async()=>{
  const data=fs.mkdtempSync(path.resolve(__dirname,'../../build/startup-native-'));
  fs.writeFileSync(path.join(data,'appearance.json'),JSON.stringify({pet:false}));
  fs.writeFileSync(path.join(data,'school.json'),JSON.stringify({settings:{updates:false}}));
  fs.writeFileSync(path.join(data,'agenda.json'),JSON.stringify({settings:{onboarded:true}}));
  let app;
  try {
    app=await electron.launch({executablePath:process.env.YOUNGMIN_SMOKE_EXE,args:['--startup'],env:{...process.env,YOUNGMIN_DATA_HOME:data},timeout:30000});
    console.log('Packaged startup fixture launched');
    const page=await app.firstWindow();await page.waitForFunction(()=>!!window.youngmini);
    await page.waitForFunction(async()=>(await window.youngmini.getState()).state.ready,{},{timeout:60000});
    assert.equal(await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isVisible()),false);
    await app.evaluate(({app})=>{
      globalThis.login={openAtLogin:false,executableWillLaunchAtLogin:false};globalThis.loginWrites=[];
      app.getLoginItemSettings=()=>globalThis.login;
      app.setLoginItemSettings=options=>{globalThis.loginWrites.push(options);globalThis.login={openAtLogin:options.openAtLogin,executableWillLaunchAtLogin:options.enabled&&options.openAtLogin};};
    });
    await page.evaluate(()=>window.youngmini.control('show'));
    assert.equal(await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isVisible()),true);
    await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();
    const toggle=page.getByRole('checkbox',{name:'Windows 로그인 시 자동 실행',exact:true});
    await toggle.check();assert.equal((await page.evaluate(()=>window.youngmini.getState())).shell.startup.enabled,true);
    const writes=await app.evaluate(()=>globalThis.loginWrites);
    assert.equal(writes.length,1);assert.equal(writes[0].path,path.resolve(process.env.YOUNGMIN_SMOKE_EXE));assert.deepEqual(writes[0].args,['--startup']);
    await toggle.uncheck();assert.equal((await page.evaluate(()=>window.youngmini.getState())).shell.startup.enabled,false);
    console.log('PASS: packaged Windows --startup stays hidden with a ready backend; show opens settings; trusted IPC enables/disables the installed executable. No startup registry was changed.');
  } finally {
    if(app)await app.close().catch(()=>{});
    await fs.promises.rm(data,{recursive:true,force:true,maxRetries:40,retryDelay:100}).catch(error=>console.warn('Fixture cleanup:',error.message));
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
