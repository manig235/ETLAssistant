const test=require('node:test'),assert=require('node:assert/strict');
const {validCommand,isExternalLink,isInside}=require('../electron/validation.cjs');
test('renderer bridge accepts app commands but no arbitrary paths or shell commands',()=>{
  assert(validCommand({action:'send',chatId:'00000000-0000-4000-8000-000000000001',text:'hello'}));
  assert(!validCommand({action:'exec',command:'anything'}));assert(!validCommand({action:'send',chatId:'../secrets',text:'x'}));
  assert(validCommand({action:'newChat'}));
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
