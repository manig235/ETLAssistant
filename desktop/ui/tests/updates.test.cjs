const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {windowsRelease,newer,downloadURL,createUpdater,fetchWindowsRelease,INTERVAL}=require('../electron/updates.cjs');
const release=(v='0.5.2')=>({tag_name:`windows-v${v}`,draft:false,prerelease:false,body:'새 버전의 변경 사항',assets:[{name:`youngmin-windows-x64-v${v}.zip`,state:'uploaded',size:400000000,browser_download_url:downloadURL(v)}]});
const response=data=>new Response(JSON.stringify(data),{status:200,headers:{'content-type':'application/json'}});
function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-update-'));return {dir,file:path.join(dir,'updates.json'),close:()=>fs.rmSync(dir,{recursive:true,force:true})};}
test('only a published Windows ZIP with the matching tag and fixed repository URL is accepted',()=>{
  const newerRelease=release('0.5.10');assert.equal(windowsRelease([release(),newerRelease],'0.5.1').version,'0.5.10');
  for(const mutate of [r=>r.draft=true,r=>r.prerelease=true,r=>r.tag_name='v0.5.2',r=>r.assets[0].state='new',r=>r.assets[0].browser_download_url+='?token=x',r=>r.assets[0].browser_download_url='https://example.com/app.zip',r=>r.assets[0].name='youngmin-v9.0.0.apk']){const r=release();mutate(r);assert.equal(windowsRelease([r],'0.5.1'),null);}
  assert.equal(windowsRelease([release()],'0.5.2'),null);assert.equal(windowsRelease([release()],'0.6.0'),null);assert(newer('1.0.0','0.99.99'));assert(!newer('0.05.2','0.5.1'));
});
test('Windows checks paginate past Android-only releases without using the Android latest endpoint',async()=>{
  const calls=[];const result=await fetchWindowsRelease(async url=>{calls.push(url);return response(calls.length===1?Array.from({length:100},()=>({tag_name:'v0.3.8',assets:[]})):[release()]);},'0.5.1');assert.equal(result.version,'0.5.2');assert.equal(calls.length,2);assert(calls[1].endsWith('page=2'));assert(calls.every(u=>u.startsWith('https://api.github.com/repos/manig235/ETLAssistant/releases?')));
});
test('concurrent checks share one request and persist the six-hour interval across restarts',async()=>{
  const f=fixture();let now=1800000000000,calls=0,resolve;const notified=[];let gate=new Promise(r=>resolve=r);
  const options={file:f.file,current:'0.5.1',clock:()=>now,fetcher:async()=>{calls++;await gate;return response([release()]);},onAvailable:r=>notified.push(r.version)};
  try{const updater=createUpdater(options);const a=updater.check(),b=updater.check(true);assert.equal(a,b);assert(updater.snapshot().checking);resolve();await a;assert.deepEqual(notified,['0.5.2']);assert.equal(calls,1);await updater.check();assert.equal(calls,1);const restored=createUpdater(options);assert.equal(restored.snapshot().available.version,'0.5.2');await restored.check();assert.equal(calls,1);now+=INTERVAL+1;await restored.check();assert.equal(calls,2);}finally{f.close();}
});
test('an offline check preserves the known update, backs off, and manual checking can retry',async()=>{
  const f=fixture();let calls=0,fail=false;const options={file:f.file,current:'0.5.1',fetcher:async()=>{calls++;if(fail)throw Error('offline');return response([release()]);}};
  try{const u=createUpdater(options);await u.check(true);fail=true;await u.check(true);assert.equal(u.snapshot().available.version,'0.5.2');assert(u.snapshot().error);await u.check();assert.equal(calls,2);fail=false;await u.check(true);assert.equal(calls,3);assert.equal(u.snapshot().error,null);}finally{f.close();}
});
test('later snoozes the current banner for one day and a newer version resets it',async()=>{
  const f=fixture();let now=1800000000000,v='0.5.2';const options={file:f.file,current:'0.5.1',clock:()=>now,fetcher:async()=>response([release(v)])};
  try{const u=createUpdater(options);await u.check();u.later('0.5.2');assert.equal(u.snapshot().remindAt,now+86400000);assert.equal(createUpdater(options).snapshot().remindAt,now+86400000);v='0.5.3';await u.check(true);assert.equal(u.snapshot().remindAt,0);assert.equal(u.snapshot().available.version,'0.5.3');assert.equal(createUpdater({...options,current:'0.5.3'}).snapshot().available,null);}finally{f.close();}
});
test('invalid caches and oversize or malformed responses never become executable download links',async()=>{
  const f=fixture();try{for(const saved of ['null','[]','{broken',JSON.stringify({available:{version:'99.0.0',url:'file:///C:/Windows/win.ini'}})]){fs.writeFileSync(f.file,saved);const u=createUpdater({file:f.file,current:'0.5.1',fetcher:async()=>response([])});assert.equal(u.snapshot().available,null);}
    await assert.rejects(fetchWindowsRelease(async()=>new Response('x'.repeat(4*1024*1024+1)),'0.5.1'));await assert.rejects(fetchWindowsRelease(async()=>response({not:'array'}),'0.5.1'));
  }finally{f.close();}
});
