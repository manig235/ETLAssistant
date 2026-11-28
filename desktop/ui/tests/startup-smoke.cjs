// Hold the backend before its first byte: the real shell must render and keep settings usable.
// Linux uses a test-only Java wrapper; no delay switches are added to the distributed app.
const {_electron:electron}=require('playwright');
const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs'),os=require('node:os');
(async()=>{
  assert.equal(process.platform,'linux','This controlled Java wrapper is for Linux test hosts.');
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-startup-'));
  const home=path.join(root,'runtime'),data=path.join(root,'data'),release=path.join(root,'release');
  const reports=path.resolve(__dirname,'../../build/reports');fs.mkdirSync(reports,{recursive:true});
  fs.mkdirSync(path.join(home,'jre/bin'),{recursive:true});
  fs.symlinkSync(process.env.YOUNGMIN_SMOKE_LIB||path.resolve(__dirname,'../../build/windows-stage/lib'),path.join(home,'lib'));
  const realJava=process.env.YOUNGMIN_SMOKE_JAVA||(process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java'):'java');
  fs.writeFileSync(path.join(home,'jre/bin/java'),`#!${process.execPath}\nconst fs=require('fs'),{spawn}=require('child_process');\nlet child;process.on('SIGTERM',()=>{if(child)child.kill();else process.exit(0);});const timer=setInterval(()=>{if(!fs.existsSync(${JSON.stringify(release)}))return;clearInterval(timer);child=spawn(${JSON.stringify(realJava)},process.argv.slice(2),{stdio:'inherit'});child.on('exit',code=>process.exit(code??1));},50);\n`,{mode:0o755});
  fs.mkdirSync(path.join(data,'chats'),{recursive:true});
  fs.writeFileSync(path.join(data,'chats/00000000-0000-4000-8000-000000000001.json'),JSON.stringify({id:'00000000-0000-4000-8000-000000000001',title:'이전 대화',thread:null,messages:[{id:'m',role:'assistant',text:'이전 버전의 대화도 보존돼요.'}],files:[],draft:'이전에 쓰던 초안'}));
  const app=await electron.launch({args:[process.env.YOUNGMIN_SMOKE_APP||path.resolve(__dirname,'..'),'--no-sandbox','--disable-gpu'],env:{...process.env,YOUNGMIN_DESKTOP_HOME:home,YOUNGMIN_DATA_HOME:data}});
  let page;
  try {
    await app.firstWindow();for(let i=0;i<100&&!page;i++){page=(await app.windows()).find(p=>p.url().includes('index.html')&&!p.url().includes('?'));if(!page)await new Promise(resolve=>setTimeout(resolve,40));}assert(page,'Main window missing');
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.waitForFunction(()=>!!window.youngmini);
    await page.evaluate(()=>window.youngmini.getState()); // Complete IPC while Java still cannot emit a state.
    await page.waitForTimeout(300);
    assert(await page.locator('.workspace').isVisible(),`UI disappeared before backend startup. ${errors.join('; ')}`);
    assert.equal((await page.evaluate(()=>window.youngmini.getState())).state.starting,true);
    await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'완료',exact:true}).click();
    await page.screenshot({path:path.join(reports,'startup-before-backend.png')});
    fs.writeFileSync(release,'continue');
    let ready=false;for(let i=0;i<300&&!ready;i++){const snapshot=await page.evaluate(()=>window.youngmini.getState());if(snapshot.shell.error)throw Error(snapshot.shell.error);ready=snapshot.state.ready;if(!ready)await page.waitForTimeout(100);}assert(ready,'Real backend did not start');
    await page.getByRole('dialog').getByRole('button',{name:'나중에 설정',exact:true}).click();
    await page.getByRole('button',{name:'통합 프로젝트',exact:true}).click();await page.getByText('이전 버전의 대화도 보존돼요.',{exact:true}).waitFor();assert.equal(await page.getByRole('textbox').inputValue(),'이전에 쓰던 초안');assert.deepEqual(errors,[]);
    await page.screenshot({path:path.join(reports,'startup-after-backend.png')});
    console.log('PASS: real shell renders before any backend output, settings stay usable, then real Kotlin/Codex starts and restores legacy chat/draft.');
  } catch(error){console.error(error);if(page)await page.screenshot({path:path.join(reports,'startup-failure.png')}).catch(()=>{});throw error;}
  finally {fs.writeFileSync(release,'continue');await app.close();fs.rmSync(root,{recursive:true,force:true,maxRetries:20,retryDelay:100});}
})().catch(error=>{console.error(error);process.exitCode=1;});
