const test=require('node:test'),assert=require('node:assert/strict');
const {createStartup,LOGIN_NAME}=require('../electron/startup.cjs');

test('startup toggle uses the installed executable and reflects Windows disabling it',()=>{
  const calls=[];let open=false,approved=true;
  const app={isPackaged:true,getLoginItemSettings:options=>{assert.deepEqual(options,{path:'C:\\Users\\Student Name\\YoungMini.exe',args:['--startup']});return {openAtLogin:open,executableWillLaunchAtLogin:open&&approved};},setLoginItemSettings:options=>{calls.push(options);open=options.openAtLogin;approved=options.enabled;}};
  const startup=createStartup({app,platform:'win32',execPath:'C:\\Users\\Student Name\\YoungMini.exe'});
  assert.equal(startup.snapshot().enabled,false);assert.equal(calls.length,0);
  startup.set(true);assert.equal(startup.snapshot().enabled,true);assert.equal(calls[0].name,LOGIN_NAME);
  approved=false;assert.equal(startup.snapshot().enabled,false);
  startup.set(true);assert.equal(startup.snapshot().enabled,true);
  startup.set(false);assert.equal(startup.snapshot().enabled,false);
  assert.throws(()=>startup.set('true'));
});
test('development and other operating systems never register startup',()=>{
  for(const [platform,isPackaged] of [['win32',false],['linux',true],['darwin',true]]){
    const startup=createStartup({app:{isPackaged},platform});
    assert.equal(startup.snapshot().supported,false);assert.throws(()=>startup.set(true));
  }
});
test('registry failure is reported and never becomes a saved enabled state',()=>{
  const startup=createStartup({platform:'win32',app:{isPackaged:true,getLoginItemSettings:()=>({openAtLogin:false,executableWillLaunchAtLogin:false}),setLoginItemSettings:()=>{throw Error('Denied');}}});
  assert.throws(()=>startup.set(true));assert.equal(startup.snapshot().enabled,false);assert.match(startup.snapshot().error,/저장하지 못/);
});
test('update download prefers the installer and still accepts older portable releases',()=>{
  const {windowsRelease,downloadURL}=require('../electron/updates.cjs');
  const zip={name:'youngmin-windows-x64-v0.5.3.zip',state:'uploaded',browser_download_url:downloadURL('0.5.3')};
  const exe={name:'youngmin-windows-x64-setup-v0.5.3.exe',state:'uploaded',browser_download_url:downloadURL('0.5.3',true)};
  const release={tag_name:'windows-v0.5.3',assets:[zip,exe]};
  for(const assets of [[zip,exe],[exe,zip]])assert.equal(windowsRelease([{...release,assets}],'0.5.2').url,exe.browser_download_url);
  assert.equal(windowsRelease([{...release,assets:[zip]}],'0.5.2').url,zip.browser_download_url);
  assert.equal(windowsRelease([{...release,assets:[{...exe,browser_download_url:'https://example.com/setup.exe'}]}],'0.5.2'),null);
});
