const test=require('node:test'),assert=require('node:assert/strict');
const {allowedLoginURL,loginCandidate,issuedToken,csrfValue,issueToken}=require('../electron/etl-login.cjs');
const ROOT='https://myetl.snu.ac.kr',token='123~'+'a'.repeat(64);
const cookies=async()=>[{name:'_csrf_token',value:'csrf%2Btoken%3D'}];
test('login navigation accepts SNU HTTPS only and rejects origin lookalikes',()=>{
  for(const url of [ROOT+'/login','https://sso.snu.ac.kr/','https://snu.ac.kr'])assert(allowedLoginURL(url));
  for(const url of ['http://myetl.snu.ac.kr/','https://snu.ac.kr.evil.test/','https://user:pass@myetl.snu.ac.kr','https://myetl.snu.ac.kr:444/','file:///secret','javascript:alert(1)','https://127.0.0.1/'])assert(!allowedLoginURL(url));
  assert(!loginCandidate(ROOT+'/login'));assert(loginCandidate(ROOT+'/courses'));assert(!loginCandidate('https://sso.snu.ac.kr/courses'));
});
test('CSRF and full-token parsing follow mobile rules without accepting masked tokens',()=>{
  assert.equal(csrfValue([{name:'_csrf_token',value:'a%2Bb%3D'}]),'a+b=');assert.equal(csrfValue([{name:'_csrf_token',value:'%0d%0a'}]),null);
  assert.equal(issuedToken(`while(1);${JSON.stringify({access_token:{visible_token:token}})}`),token);
  for(const bad of ['123~abcd...','123~abcd…','short','a'.repeat(31),'a'.repeat(513)])assert.equal(issuedToken(JSON.stringify({token:bad})),null);
});
test('only API 404 falls back once, then the bearer grant is checked without cookies',async()=>{
  const calls=[];const result=await issueToken({cookies,purpose:'영민이 (Windows)',request:async(url,options)=>{
    calls.push({url,...options});if(url.endsWith('/users/self'))return {status:200,body:'{"id":1}'};
    return url.endsWith('/api/v1/users/self/tokens')?{status:404,body:''}:{status:200,body:JSON.stringify({visible_token:token})};
  }});
  assert.equal(result,token);assert.equal(calls.filter(c=>c.method==='POST').length,2);assert.equal(calls[1].headers['X-CSRF-Token'],'csrf+token=');
  assert.equal(new URLSearchParams(calls[2].body).get('access_token[purpose]'),'영민이 (Windows)');assert.equal(calls[3].credentials,'omit');assert.equal(calls[3].headers.Authorization,'Bearer '+token);
  const fields=new URLSearchParams(calls[2].body);assert.equal(fields.get('token[purpose]'),'영민이 (Windows)');const expires=Date.parse(fields.get('token[expires_at]'));assert(expires>Date.now()+89*86400000&&expires<Date.now()+91*86400000);assert.equal(fields.get('access_token[expires_at]'),fields.get('token[expires_at]'));
});
test('unauthenticated session cannot create any token',async()=>{
  let count=0;assert.equal(await issueToken({cookies,purpose:'test',request:async()=>{count++;return {status:401,body:''};}}),null);assert.equal(count,1);
});
test('forbidden or ambiguous failures never retry the token POST',async()=>{
  for(const status of [403,500,302]){let posts=0;await assert.rejects(issueToken({cookies,purpose:'test',request:async(url,options)=>{if(options.method==='POST'){posts++;return {status,body:'private response'};}return {status:200,body:'{"id":1}'};}}));assert.equal(posts,1);}
  let posts=0;await assert.rejects(issueToken({cookies,purpose:'test',request:async(url,options)=>{if(options.method==='POST'){posts++;throw Error('network');}return {status:200,body:'{"id":1}'};}}));assert.equal(posts,1);
});
test('masked responses and invalid bearer grants never reach storage',async()=>{
  for(const invalid of [true,false]){await assert.rejects(issueToken({cookies,purpose:'test',request:async(url,options)=>{
    if(options.method==='POST')return {status:200,body:JSON.stringify({token:invalid?'masked...':token})};
    if(options.headers?.Authorization)return {status:401,body:'private'};
    return {status:200,body:'{"id":1}'};
  }}));}
});
test('cancellation after session validation prevents token creation',async()=>{
  let cancelled=false,posts=0;await assert.rejects(issueToken({cookies,purpose:'test',check:()=>{if(cancelled)throw Error('cancelled');},request:async(url,options)=>{if(options.method==='POST')posts++;cancelled=true;return {status:200,body:'{"id":1}'};}}));assert.equal(posts,0);
});
