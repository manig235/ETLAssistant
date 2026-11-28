const fs=require('node:fs');
const REPO='https://github.com/manig235/ETLAssistant';
const API='https://api.github.com/repos/manig235/ETLAssistant/releases';
const INTERVAL=6*60*60*1000,RETRY=60*60*1000;
const parse=v=>/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(v||'')&&v.split('.').every(n=>Number.isSafeInteger(Number(n)))?v.split('.').map(Number):null;
function newer(a,b){const x=parse(a),y=parse(b);if(!x||!y)return false;for(let i=0;i<3;i++)if(x[i]!==y[i])return x[i]>y[i];return false;}
function downloadURL(version,installer=false){return `${REPO}/releases/download/windows-v${version}/youngmin-windows-x64-${installer?'setup-v':'v'}${version}.${installer?'exe':'zip'}`;}
function candidate(value,current) {
  if(!value||!parse(value.version)||!newer(value.version,current)||![downloadURL(value.version),downloadURL(value.version,true)].includes(value.url))return null;
  return {version:value.version,url:value.url,releaseUrl:`${REPO}/releases/tag/windows-v${value.version}`,text:`Windows ${value.version} 업데이트가 있어요.`,notes:typeof value.notes==='string'?value.notes.slice(0,4000):'',bytes:Number.isSafeInteger(value.bytes)&&value.bytes>0?value.bytes:0};
}
function windowsRelease(releases,current){
  if(!parse(current)||!Array.isArray(releases))return null;let best=null;
  for(const release of releases){
    if(!release||release.draft||release.prerelease)continue;
    const match=/^windows-v(\d+\.\d+\.\d+)$/.exec(release.tag_name||'');if(!match||!parse(match[1]))continue;
    const version=match[1],names=[`youngmin-windows-x64-v${version}.zip`,`youngmin-windows-x64-setup-v${version}.exe`];
    for(const asset of release.assets||[]){
      if(!names.includes(asset?.name)||asset.state!=='uploaded')continue;
      const found=candidate({version,url:asset.browser_download_url,notes:release.body,bytes:asset.size},current);
      if(found&&(!best||newer(found.version,best.version)||(found.version===best.version&&found.url.endsWith('.exe'))))best=found;
    }
  }
  return best;
}
async function fetchWindowsRelease(fetcher,current){
  let best=null;
  // Android and Windows share a repo; do not use releases/latest (Android's channel).
  for(let page=1;page<=5;page++){
    const response=await fetcher(`${API}?per_page=100&page=${page}`,{headers:{Accept:'application/vnd.github+json'},signal:AbortSignal.timeout(15000)});
    if(!response.ok)throw Error('Update unavailable');
    if(Number(response.headers?.get('content-length'))>4*1024*1024)throw Error('Update response too large');
    const reader=response.body.getReader();const chunks=[];let total=0;
    try{while(true){const {done,value}=await reader.read();if(done)break;total+=value.byteLength;if(total>4*1024*1024)throw Error('Update response too large');chunks.push(Buffer.from(value));}}finally{await reader.cancel().catch(()=>{});}
    const releases=JSON.parse(Buffer.concat(chunks).toString('utf8'));if(!Array.isArray(releases))throw Error('Invalid release list');
    const found=windowsRelease(releases,current);if(found&&(!best||newer(found.version,best.version)))best=found;
    if(releases.length<100)break;
  }
  return best;
}
function createUpdater({file,current,fetcher,clock=Date.now,onChange=()=>{},onAvailable=()=>{}}){
  let cache={};try{const info=fs.statSync(file);if(info.size<=16000){const value=JSON.parse(fs.readFileSync(file,'utf8'));if(value&&typeof value==='object'&&!Array.isArray(value))cache=value;}}catch{}
  let available=candidate(cache.available,current),checking=false,error=null,pending=null;
  let checkedAt=Number.isSafeInteger(cache.checkedAt)?Math.min(cache.checkedAt,clock()):0;
  let retryAt=0,remindAt=Number.isSafeInteger(cache.remindAt)?Math.min(cache.remindAt,clock()+86400000):0;
  if(cache.dismissedVersion!==available?.version)remindAt=0;
  function snapshot(){return {available,checking,error,checkedAt,remindAt};}
  function publish(){onChange(snapshot());}
  function save(){const temp=file+'.tmp';fs.writeFileSync(temp,JSON.stringify({available,checkedAt,remindAt,dismissedVersion:available?.version||null}));fs.renameSync(temp,file);}
  function check(force=false){
    if(pending)return pending;
    if(!force&&(clock()<retryAt||(checkedAt>0&&clock()-checkedAt<INTERVAL)))return Promise.resolve(snapshot());
    checking=true;error=null;publish();
    pending=(async()=>{
      try{const found=await fetchWindowsRelease(fetcher,current);if(found?.version!==available?.version)remindAt=0;available=found;checkedAt=clock();retryAt=0;save();if(available)onAvailable(available);}
      catch{retryAt=clock()+RETRY;error='업데이트를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.';}
      finally{checking=false;pending=null;publish();}
      return snapshot();
    })();
    return pending;
  }
  function later(version){if(available?.version!==version)return;remindAt=clock()+86400000;save();publish();}
  return {snapshot,check,later};
}
module.exports={windowsRelease,newer,downloadURL,fetchWindowsRelease,createUpdater,INTERVAL};
