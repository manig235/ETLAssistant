const test=require('node:test'),assert=require('node:assert/strict');
const {validCommand,isExternalLink,isInside}=require('../electron/validation.cjs');
test('renderer bridge accepts app commands but no arbitrary paths or shell commands',()=>{
  assert(validCommand({action:'send',chatId:'00000000-0000-4000-8000-000000000001',text:'hello'}));
  assert(!validCommand({action:'exec',command:'anything'}));assert(!validCommand({action:'send',chatId:'../secrets',text:'x'}));
  assert(validCommand({action:'newChat'}));
  assert(validCommand({action:'renameChat',chatId:'00000000-0000-4000-8000-000000000001',title:'공부 기록'}));
  assert(validCommand({action:'deleteChat',chatId:'00000000-0000-4000-8000-000000000001'}));
  assert(!validCommand({action:'deleteChat'}));assert(!validCommand({action:'renameChat',chatId:'00000000-0000-4000-8000-000000000001',title:' '}));
  for(const action of ['pdfRendered','artifactText','setLayout','download'])assert(!validCommand({action}));
  assert(!validCommand({action:'send',text:'x'.repeat(32001)}));
  assert(isInside('/workspace/etl-files','/workspace/etl-files/a.pdf'));assert(!isInside('/workspace/etl-files','/workspace/secrets/token'));
});
test('browser links allow HTTPS and OAuth opens only the fixed issuer',()=>{
  assert(isExternalLink('https://myetl.snu.ac.kr/courses/1'));assert(isExternalLink('https://auth.openai.com/oauth/authorize?state=sample',true));
  for(const bad of ['javascript:alert(1)','file:///C:/Windows/win.ini','http://example.test','https://user:pass@example.test'])assert(!isExternalLink(bad));
  assert(!isExternalLink('https://auth.openai.com.evil.test/',true));
});

test('shell startup includes the full project and agenda schema before Java starts',()=>{
  const state=require('../electron/initial-state.json');
  assert.equal(state.starting,true);assert.equal(state.ready,false);assert.equal(state.projects[0].id,'integrated');
  for(const key of ['personal','academic','notices'])assert(Array.isArray(state.agenda[key]));
  assert.equal(state.agenda.settings.onboarded,false);assert(Array.isArray(state.sessions));
});

test('custom names survive restart and invalid saved values keep the default',()=>{
  const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
  const {displayName,readAppearance,saveAppearance}=require('../electron/appearance.cjs');
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-name-')),file=path.join(dir,'appearance.json');
  try {
    fs.writeFileSync(file,JSON.stringify({pet:false}));assert.deepEqual(readAppearance(file),{pet:false,displayName:'영민이',petMotion:'system'});
    saveAppearance(file,{pet:false,displayName:'공부 친구',petMotion:'on'});assert.equal(readAppearance(file).displayName,'공부 친구');assert.equal(readAppearance(file).petMotion,'on');
    for(const bad of ['',null,{},'a'.repeat(25),'이름\n명령'])assert.equal(displayName(bad),null);
    assert.equal(displayName('  영민이  '),'영민이');
  } finally {fs.rmSync(dir,{recursive:true,force:true});}
});

test('photo importer copies normalized bytes and rejects oversized or empty batches',async()=>{
  const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
  const {importImages}=require('../electron/images.cjs');
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-images-'));
  const decode=()=>({isEmpty:()=>false,getSize:()=>({width:12,height:8}),toPNG:()=>Buffer.from('normalized')});
  try {
    const [photo]=await importImages([{name:'../../problem.jpg',bytes:new Uint8Array([1,2])}],root,decode);
    assert.equal(photo.name,'problem.png');assert.equal(photo.kind,'attachment');assert.match(photo.path,/^uploads\/[a-f0-9-]{36}\.png$/);assert.equal(fs.readFileSync(path.join(root,photo.path),'utf8'),'normalized');
    await assert.rejects(importImages([],root,decode));await assert.rejects(importImages(Array(6).fill({}),root,decode));
    await assert.rejects(importImages([{name:'x.png',bytes:new Uint8Array(20*1024*1024+1)}],root,decode));
    assert(!validCommand({action:'attachImages',chatId:'00000000-0000-4000-8000-000000000001',images:[{path:'secret'}]}));
  } finally {fs.rmSync(root,{recursive:true,force:true});}
});

test('document importer copies bounded managed files and rejects disguised executables and invalid text',async()=>{
  const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{importFiles}=require('../electron/images.cjs');const root=fs.mkdtempSync(path.join(os.tmpdir(),'youngmini-docs-'));
  try{const files=await importFiles([{name:'메모.txt',bytes:new Uint8Array(Buffer.from('한글 문서'))},{name:'원본.pdf',bytes:new Uint8Array(Buffer.from('%PDF-1.7\nfixture'))}],root,()=>{throw Error('Not an image');});assert.equal(files.length,2);assert(files.every(f=>f.kind==='document-attachment'&&f.path.startsWith('uploads/')));assert.equal(fs.readFileSync(path.join(root,files[0].path),'utf8'),'한글 문서');await assert.rejects(importFiles([{name:'run.exe',bytes:new Uint8Array([1,2])}],root,()=>{}));await assert.rejects(importFiles([{name:'fake.pdf',bytes:new Uint8Array([77,90])}],root,()=>{}));await assert.rejects(importFiles([{name:'fake.txt',bytes:new Uint8Array([255,0])}],root,()=>{}));}finally{fs.rmSync(root,{recursive:true,force:true});}
});
test('update checks select only newer verified Windows assets and never Android APKs',()=>{
  const {windowsRelease}=require('../electron/updates.cjs');const apk={name:'youngmin-v9.0.0.apk',browser_download_url:'https://github.com/manig235/ETLAssistant/releases/download/v9.0.0/youngmin.apk'};const asset={state:'uploaded',name:'youngmin-windows-x64-v0.5.1.zip',browser_download_url:'https://github.com/manig235/ETLAssistant/releases/download/windows-v0.5.1/youngmin-windows-x64-v0.5.1.zip'};assert.equal(windowsRelease([{tag_name:'windows-v0.5.1',assets:[apk]}],'0.5.0'),null);assert.equal(windowsRelease([{tag_name:'windows-v0.5.1',assets:[apk,asset]}],'0.5.0').version,'0.5.1');assert.equal(windowsRelease([{tag_name:'windows-v0.5.1',prerelease:true,assets:[asset]}],'0.5.0'),null);assert.equal(windowsRelease([{tag_name:'windows-v0.5.1',assets:[asset]}],'0.5.1'),null);assert.equal(windowsRelease([{tag_name:'windows-v0.5.1',assets:[{...asset,browser_download_url:'https://example.com/app.zip'}]}],'0.5.0'),null);
});

test('restart saves through backend EOF once before relaunching and handles a stalled backend',async()=>{
  const {EventEmitter}=require('node:events'),{createRestarter}=require('../electron/lifecycle.cjs');const calls=[];
  const child=new EventEmitter();Object.assign(child,{exitCode:null,signalCode:null,stdin:{writable:true,writableEnded:false,end:()=>{calls.push('save');setImmediate(()=>{child.exitCode=0;child.emit('exit');});}},kill:()=>{calls.push('kill');child.signalCode='SIGTERM';child.emit('exit');}});
  const restart=createRestarter({app:{relaunch:()=>calls.push('relaunch'),exit:()=>calls.push('exit')},getBackend:()=>child,beforeStop:async()=>{calls.push('cancel-login');}});const first=restart();assert.equal(restart(),first);await first;assert.deepEqual(calls,['cancel-login','save','relaunch','exit']);
  calls.length=0;child.exitCode=null;child.signalCode=null;child.stdin.end=()=>calls.push('save');await createRestarter({app:{relaunch:()=>calls.push('relaunch'),exit:()=>calls.push('exit')},getBackend:()=>child,beforeStop:async()=>{},timeoutMillis:15})();assert.deepEqual(calls,['save','kill','relaunch','exit']);
});
