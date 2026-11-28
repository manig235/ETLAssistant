const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
if(!process.versions.electron) {
  const data=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-sso-fixture-'));
  try {const result=require('node:child_process').spawnSync(require('electron'),[__filename],{windowsHide:true,stdio:'inherit',env:{...process.env,YOUNGMIN_SSO_TEST_HOME:data}});process.exitCode=result.status??1;}
  finally {fs.rmSync(data,{recursive:true,force:true,maxRetries:5,retryDelay:100});}
} else {
  const {app,BrowserWindow,session}=require('electron');const {beginEtlLogin}=require('../electron/etl-login.cjs');
  app.setPath('userData',process.env.YOUNGMIN_SSO_TEST_HOME);
  app.on('window-all-closed',()=>{});
  app.whenReady().then(async()=>{
    const token='123~'+'a'.repeat(64),calls=[];let isolated,preferences,stored=false,resolveDone;
    const done=new Promise(r=>resolveDone=r);const timer=setTimeout(()=>{console.error('SSO fixture timed out');app.exit(1);},20000);
    try {
      const flow=await beginEtlLogin({parent:undefined,name:'영민이',
        BrowserWindow:class {constructor(options){preferences=options.webPreferences;return new BrowserWindow(options);}},
        session:{fromPartition:(partition,options)=>{
          assert(!partition.startsWith('persist:'));assert.equal(options.cache,false);isolated=session.fromPartition(partition,options);
          isolated.protocol.handle('https',async request=>{
            const url=new URL(request.url);calls.push({path:url.pathname,method:request.method});
            if(url.origin!=='https://myetl.snu.ac.kr')throw Error('Unexpected request');
            if(url.pathname==='/login')return new Response(`<script>document.cookie='_csrf_token=csrf%2Bvalue; Path=/; Secure';setTimeout(()=>location.assign('/courses'),80)</script>`,{headers:{'Content-Type':'text/html'}});
            if(url.pathname==='/courses')return new Response('<h1>Local SSO fixture only</h1>',{headers:{'Content-Type':'text/html'}});
            if(url.pathname==='/api/v1/users/self'){
              if(request.headers.has('authorization'))assert.equal(request.headers.get('authorization'),'Bearer '+token);
              return new Response('{"id":1}',{headers:{'Content-Type':'application/json'}});
            }
            if(url.pathname==='/api/v1/users/self/tokens'){assert.equal(request.method,'POST');return new Response('{}',{status:404});}
            if(url.pathname==='/profile/tokens'){
              assert.equal(request.headers.get('x-csrf-token'),'csrf+value');assert.equal(new URLSearchParams(await request.text()).get('access_token[purpose]'),'영민이 (Windows)');
              return new Response(JSON.stringify({visible_token:token}),{headers:{'Content-Type':'application/json'}});
            }
            return new Response('',{status:404});
          });return isolated;
        }},
        onToken:async value=>{assert.equal(value,token);stored=true;},
        onStatus:(busy,error)=>{if(!busy)resolveDone(error);},
      });
      const error=await done;assert.equal(error,null);assert(stored);assert(flow.window.isDestroyed());assert.equal((await isolated.cookies.get({})).length,0);
      assert.equal(preferences.nodeIntegration,false);assert.equal(preferences.sandbox,true);assert.equal(preferences.contextIsolation,true);assert.equal(preferences.preload,undefined);assert.equal(calls.filter(c=>c.method==='POST').length,2);
      console.log('PASS: isolated real Electron login session, cookie CSRF, one API-404 fallback, bearer verification, storage handoff and cookie cleanup. Local fixtures only; no account or external request used.');
    } finally {clearTimeout(timer);}
    app.quit();
  }).catch(error=>{console.error(error);app.exit(1);});
}
