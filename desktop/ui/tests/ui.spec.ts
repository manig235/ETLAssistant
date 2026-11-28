import {test,expect,type Page} from '@playwright/test';
import type {State,Session} from '../src/types';
import {initialState} from '../src/types';
const a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002';
const session=(id:string,title:string,projectId='integrated'):Session=>({id,title,projectId,draft:'',model:'sol',effort:'high',phase:'idle',status:'대화를 시작해 보세요',error:null,unread:false,updatedAt:1,artifacts:[],draftImages:[],pdfBusy:false,messages:[]});
const base=():State=>({...structuredClone(initialState),starting:false,ready:true,loggedIn:true,etlConnected:true,activeChatId:a,visibleChatIds:[a],courses:[{id:10,name:'전기에너지변환',term:'2026-2'},{id:11,name:'선형대수학',term:'2026-2'}],projects:[initialState.projects[0],{id:'course-10',name:'전기에너지변환',kind:'course',courseIds:[10],instructions:''}],models:[{model:'sol',displayName:'GPT-6.1-Sol',isDefault:true,defaultEffort:'medium',efforts:[{effort:'low',description:'빠르게'},{effort:'medium',description:'보통'},{effort:'high',description:'깊게'},{effort:'xhigh',description:'매우 깊게'}]}],sessions:[session(a,'시험 대비'),session(b,'개념 질문','course-10')],agenda:{personal:[],academic:[],notices:[],settings:{onboarded:true,deadlines:true,announcements:true,grades:true,personal:true,completion:true}}});
async function mount(page:Page,state:State,pet=false,home=false) {
  await page.addInitScript(({state:s,home})=>{
    let current=s;let shell:any={pet:true,displayName:'영민이',petMotion:'system',openChat:home?0:1};const shellListeners:((v:unknown)=>void)[]=[];const listeners:((v:unknown)=>void)[]=[];const emit=()=>listeners.forEach(fn=>fn(structuredClone(current)));
    const select=(id:string)=>{const chat=current.sessions.find(c=>c.id===id)!;current.activeChatId=id;current.activeProjectId=chat.projectId;current.visibleChatIds=[id];shell.openChat++;shellListeners.forEach(fn=>fn({...shell}));};
    Object.assign(window,{__shellPush:(value:unknown)=>{Object.assign(shell,value);shellListeners.forEach(fn=>fn({...shell}));},__commands:[],__controls:[],__imports:[],__push:(next:State)=>{current=next;emit();},youngmini:{getState:async()=>({state:structuredClone(current),shell:{...shell}}),onState:(fn:(s:unknown)=>void)=>{listeners.push(fn);return()=>{};},onShell:(fn:(v:unknown)=>void)=>{shellListeners.push(fn);return()=>{};},readArtifact:async(id:string)=>{const f=current.sessions.flatMap(s=>[...s.artifacts,...s.draftImages]).find(a=>a.id===id);return f&&/\.(png|jpg)$/i.test(f.name)?{kind:'image',mime:'image/png',bytes:Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/8sAAAAASUVORK5CYII='),c=>c.charCodeAt(0))}:{kind:'text',text:`미리보기 ${id}\n자료 내용입니다.`};},importImages:async(chatId:string,images:{name:string;bytes:Uint8Array}[])=>{(window as any).__imports.push({chatId,names:images.map(i=>i.name)});current.sessions.find(s=>s.id===chatId)!.draftImages.push(...images.map(i=>({id:crypto.randomUUID(),name:i.name,path:'uploads/test.png',bytes:i.bytes.length,kind:'attachment',messageId:null})));emit();},command:async(c:any)=>{(window as any).__commands.push(c);switch(c.action){
      case 'openChat':select(c.chatId);break;
      case 'renameChat':current.sessions.find(s=>s.id===c.chatId)!.title=c.title;break;
      case 'deleteChat':{current.sessions=current.sessions.filter(s=>s.id!==c.chatId);if(current.activeChatId===c.chatId&&current.sessions.length)select(current.sessions[0].id);break;}
      case 'openProject':{let chat=current.sessions.find(s=>s.projectId===c.projectId);if(!chat){chat={...current.sessions[0],id:crypto.randomUUID(),projectId:c.projectId,title:'새 대화',messages:[],artifacts:[],draft:''};current.sessions.push(chat);}select(chat.id);break;}
      case 'newChat':{const id=crypto.randomUUID();current.sessions.push({...current.sessions[0],id,projectId:c.projectId||current.activeProjectId,title:'새 대화',draft:'',messages:[],artifacts:[],draftImages:[]});select(id);break;}
      case 'setDraft':current.sessions.find(s=>s.id===c.chatId)!.draft=c.text;break;
      case 'setChoice':Object.assign(current.sessions.find(s=>s.id===c.chatId)!,{model:c.model,effort:c.effort});break;
      case 'schoolSettings':Object.assign(current.school.settings,c.settings);break;
      case 'courseMode':current.school.courseModes[c.courseId]=c.mode;break;
      case 'approveWrite':current.approvals=current.approvals.filter(a=>a.id!==c.id);break;
      case 'saveClass':current.school.timetable.courses.push({...c.item,course_id:-123});break;
      case 'saveScore':current.school.stats.push({...c.item,id:'manual:test',source:'manual',my_score:c.item.my_score??null,points_possible:c.item.points_possible??null,position:'상위 약 10~20%'});break;
      case 'settings':Object.assign(current.agenda.settings,c.settings);break;
      case 'saveProject':{const id=c.projectId||crypto.randomUUID();current.projects.push({id,name:c.name,kind:'custom',courseIds:c.courseIds,instructions:c.instructions});break;}
      case 'savePersonal':{const item={...c.item,id:c.item.id||crypto.randomUUID()};current.agenda.personal=current.agenda.personal.filter(i=>i.id!==item.id).concat(item);break;}
      case 'completePersonal':current.agenda.personal.find(i=>i.id===c.id)!.done=c.done;break;
      case 'deletePersonal':current.agenda.personal=current.agenda.personal.filter(i=>i.id!==c.id);break;
      case 'readAnnouncement':{const n=current.agenda.announcements?.find(n=>n.id===c.id);if(n)n.read=true;break;}
      case 'removeImage':{const chat=current.sessions.find(s=>s.id===c.chatId)!;chat.draftImages=chat.draftImages.filter(i=>i.id!==c.imageId);break;}
      case 'noticeAck':{const n=current.agenda.notices.find(n=>n.id===c.id);if(n){n.read=c.read;n.delivered=true;}break;}
    }emit();},control:async(...args:unknown[])=>{(window as any).__controls.push(args);if(args[0]==='deferUpdate'&&shell.update){shell.update.remindAt=Date.now()+86400000;shellListeners.forEach(fn=>fn({...shell}));}if(args[0]==='displayName'){shell.displayName=String(args[1]);shellListeners.forEach(fn=>fn({...shell}));}if(args[0]==='petMotion'){shell.petMotion=String(args[1]);shellListeners.forEach(fn=>fn({...shell}));}}}});
  },{state,home});
  await page.goto('/'+(pet?'?pet=1':''));await expect(page.locator(pet?'.pet-shell':'.workspace')).toBeVisible();
}
const commands=(page:Page,action:string)=>page.evaluate(action=>(window as any).__commands.filter((c:any)=>c.action===action),action);

test('session icons rename and confirm deletion while protecting working sessions',async({page})=>{
  const s=base();s.sessions.push(session('00000000-0000-4000-8000-000000000003','작업 중'));s.sessions[2].pdfBusy=true;
  await mount(page,s);await expect(page.getByRole('button',{name:'작업 중 삭제',exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'시험 대비 이름 바꾸기',exact:true}).click();await page.getByLabel('세션 이름',{exact:true}).fill('기말 정리');await page.getByRole('dialog').getByRole('button',{name:'저장',exact:true}).click();await expect(page.getByRole('button',{name:'기말 정리',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'기말 정리 삭제',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'취소',exact:true}).click();expect(await commands(page,'deleteChat')).toEqual([]);
  await page.getByRole('button',{name:'기말 정리 삭제',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'삭제',exact:true}).click();await expect.poll(()=>commands(page,'deleteChat')).toContainEqual({action:'deleteChat',chatId:a});await expect(page.getByRole('button',{name:'기말 정리',exact:true})).toHaveCount(0);
});

test('startup IPC state renders before backend output and fills missing older fields',async({page})=>{
  const cold=structuredClone(initialState) as Partial<State>;delete cold.projects;delete cold.agenda;
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
  await mount(page,cold as State);
  await expect(page.getByRole('button',{name:'통합 프로젝트',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();await expect(page.getByRole('dialog').getByRole('heading',{name:'영민이 설정',exact:true})).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'완료',exact:true}).click();
  await page.evaluate(s=>(window as any).__push(s),base());await expect(page.getByTestId('chat-'+a)).toBeVisible();expect(errors).toEqual([]);
});

test('unexpected rendering error leaves a visible recovery screen',async({page})=>{
  await mount(page,base());await page.evaluate(()=>(window as any).__push({projects:[null]}));
  await expect(page.getByRole('alert')).toContainText('화면을 표시하지 못했어요');await expect(page.getByRole('button',{name:'다시 불러오기',exact:true})).toBeVisible();
});

test('settings header and fixed footer stay in viewport at desktop, narrow and short sizes',async({page})=>{
  await mount(page,base());await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();
  for(const [width,height,label] of [[1560,980,'desktop'],[720,720,'narrow'],[1000,500,'short']] as const){
    await page.setViewportSize({width,height});const dialog=page.getByRole('dialog');const title=dialog.getByText('영민이 설정',{exact:true});const done=dialog.getByRole('button',{name:'완료',exact:true});
    await expect(title).toBeVisible();await expect(done).toBeInViewport();const box=await title.boundingBox();const frame=await dialog.boundingBox();expect(box!.x-frame!.x).toBeGreaterThanOrEqual(20);expect(box!.y-frame!.y).toBeGreaterThanOrEqual(12);
    await dialog.locator('.dialog-body').evaluate(el=>el.scrollTop=el.scrollHeight);await expect(done).toBeInViewport();await expect(dialog.getByRole('button',{name:'영민이 설정 닫기'})).toBeInViewport();
    await page.screenshot({path:`../build/reports/settings-${label}.png`});
  }
  await page.getByRole('dialog').getByRole('button',{name:'완료',exact:true}).click();await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('projects switch a single visible chat while other sessions continue and drafts survive',async({page})=>{
  const state=base();state.sessions[0].phase='running';await mount(page,state);await expect(page.locator('.chat-pane')).toHaveCount(1);
  await page.getByRole('button',{name:'전기에너지변환',exact:true}).click();await expect(page.getByTestId('chat-'+b)).toBeVisible();await expect(page.getByText('1개 작업 중')).toBeVisible();
  const input=page.getByRole('textbox',{name:'개념 질문 메시지'});await input.fill('작성하던 질문');
  await page.getByRole('button',{name:'통합 프로젝트',exact:false}).first().click();await expect(page.getByTestId('chat-'+a)).toBeVisible();await expect(page.getByRole('button',{name:'중지',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'전기에너지변환',exact:true}).click();await expect(page.getByRole('textbox')).toHaveValue('작성하던 질문');
  await page.getByRole('button',{name:'＋ 새 대화',exact:true}).click();await expect(page.locator('.chat-pane')).toHaveCount(1);expect(await commands(page,'interrupt')).toHaveLength(0);
});

test('custom project accepts multiple courses and shared instructions',async({page})=>{
  await mount(page,base());await page.getByRole('button',{name:'프로젝트 추가',exact:true}).click();const dialog=page.getByRole('dialog');await dialog.getByLabel('프로젝트 이름',{exact:true}).fill('기말 공부방');
  await dialog.getByRole('checkbox',{name:'전기에너지변환',exact:true}).check();await dialog.getByRole('checkbox',{name:'선형대수학',exact:true}).check();await dialog.getByLabel('프로젝트 공통 지침').fill('풀이 과정을 자세하게');await dialog.getByRole('button',{name:'저장',exact:true}).click();
  await expect.poll(()=>commands(page,'saveProject')).toEqual([{action:'saveProject',projectId:null,name:'기말 공부방',instructions:'풀이 과정을 자세하게',courseIds:[10,11]}]);await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('effort remains independent across sessions',async({page})=>{
  await mount(page,base());await page.getByLabel('추론 강도',{exact:true}).click();await page.getByRole('option',{name:'매우 높음',exact:true}).click();await expect.poll(()=>commands(page,'setChoice')).toEqual([{action:'setChoice',chatId:a,model:'sol',effort:'xhigh'}]);
  await page.getByRole('button',{name:'전기에너지변환',exact:true}).click();await expect(page.getByLabel('추론 강도',{exact:true})).toContainText('높음');await page.getByRole('button',{name:'통합 프로젝트',exact:true}).click();await expect(page.getByLabel('추론 강도',{exact:true})).toContainText('매우 높음');
});

test('Enter sends, Ctrl+Enter inserts a newline, Korean composition does not send',async({page})=>{
  await mount(page,base());const input=page.getByRole('textbox');await input.fill('첫 줄');await input.press('Control+Enter');await input.press('x');await expect(input).toHaveValue('첫 줄\nx');expect(await commands(page,'send')).toHaveLength(0);
  await input.press('Enter');await expect.poll(()=>commands(page,'send')).toEqual([{action:'send',chatId:a,text:'첫 줄\nx'}]);await input.fill('한글 입력');await input.dispatchEvent('compositionstart');await input.press('Enter');expect(await commands(page,'send')).toHaveLength(1);await input.dispatchEvent('compositionend');await input.press('Enter');await expect.poll(async()=>(await commands(page,'send')).length).toBe(2);
});

test('only delivered files appear and open in closeable preview tabs',async({page})=>{
  const s=base();s.sessions[0].artifacts=[{id:'f1',name:'기말 대비.md',path:'generated/f1.md',bytes:1200,kind:'generated',messageId:null},{id:'f2',name:'요청한 자료.txt',path:'etl-files/f2.txt',bytes:300,kind:'requested',messageId:null}];await mount(page,s);
  await expect(page.getByRole('button',{name:'강의자료 찾기'})).toHaveCount(0);await expect(page.locator('.preview-panel')).toHaveCount(0);const before=(await page.locator('.chat-pane').boundingBox())!.width;
  await page.getByRole('button',{name:/기말 대비.md/}).click();await expect(page.getByRole('tab',{name:'기말 대비.md'})).toBeVisible();await expect(page.locator('.document-preview')).toContainText('미리보기 f1');
  await page.getByRole('button',{name:/요청한 자료.txt/}).click();await expect(page.getByRole('tab')).toHaveCount(2);await page.getByRole('tab',{name:'기말 대비.md'}).click();await expect(page.locator('.document-preview')).toContainText('미리보기 f1');
  await page.screenshot({path:'../build/reports/preview-tabs.png'});await page.getByRole('button',{name:'미리보기 모두 닫기'}).click();await expect(page.locator('.preview-panel')).toHaveCount(0);expect((await page.locator('.chat-pane').boundingBox())!.width).toBe(before);
});

test('Markdown math tables and PDF action render without loading remote content',async({page})=>{
  const s=base();s.sessions[0].messages=[{id:'answer',role:'assistant',text:String.raw`## Ch4 시험 대비

**정답·핵심 풀이**

균일 공극에서 \(L_{sr}=M\cos\theta\)입니다.

\[
W'_m=\frac12 L_s i_s^2+M i_s i_r\cos\theta+\frac12 L_r i_r^2
\]

| 문항 | 난이도 |
| --- | --- |
| 1 | 기본 |

<img src="https://example.test/track" onerror="window.hacked=true" />` }];const external:string[]=[];page.on('request',r=>{if(!r.url().startsWith('http://127.0.0.1:4179'))external.push(r.url());});await mount(page,s);
  await expect(page.getByRole('heading',{name:'Ch4 시험 대비'})).toBeVisible();await expect(page.locator('.katex')).toHaveCount(2);await expect(page.locator('.katex-error')).toHaveCount(0);await expect(page.locator('.markdown table')).toBeVisible();expect(external).toEqual([]);await page.getByRole('button',{name:'PDF로 받기'}).click();await expect.poll(()=>commands(page,'createPdf')).toEqual([{action:'createPdf',chatId:a,messageId:'answer'}]);await page.screenshot({path:'../build/reports/montage-markdown.png'});
});

test('first run is a simple mobile style greeting followed by account and alert choices',async({page})=>{
  const s=base();s.agenda.settings.onboarded=false;await mount(page,s,true===false,true);
  let dialog=page.getByRole('dialog');await expect(dialog.getByRole('heading')).toContainText('안녕하세요');await expect(dialog.getByRole('heading')).toContainText('영민이예요');await expect(dialog).not.toContainText('공부는 대화로');await expect(dialog).not.toContainText('통합 프로젝트에서는');
  await page.screenshot({path:'../build/reports/welcome-simple.png'});await dialog.getByRole('button',{name:'시작하기',exact:true}).click();await expect(dialog.getByRole('heading',{name:'계정 연결하기'})).toBeVisible();await expect(dialog.getByRole('button',{name:'서울대 로그인으로 연결'})).toBeVisible();await expect(dialog).toContainText('과목을 가져와요.');await expect(dialog).not.toContainText('개인 액세스 토큰을 연결하면');
  await dialog.getByRole('button',{name:'다음',exact:true}).click();await expect(dialog.getByRole('heading',{name:'알림 설정'})).toBeVisible();await expect(dialog).not.toContainText('첫 조회는 기준만 저장');await dialog.getByRole('button',{name:'완료',exact:true}).click();await expect(page.getByTestId('home-dashboard')).toBeVisible();
});

test('custom schedule CRUD and grade result inbox work',async({page})=>{
  const s=base();s.agenda.notices=[{id:'grade',kind:'grades',title:'성적 · 전기에너지변환',body:'HW1 · 95',at:Date.now(),chatId:null,url:'https://myetl.snu.ac.kr/courses/10/assignments/20',read:false,delivered:false}];await mount(page,s);await page.getByRole('button',{name:'일정 · 알림'}).click();let dialog=page.getByRole('dialog');await dialog.getByRole('button',{name:'＋ 일정 추가'}).click();await dialog.getByLabel('일정 제목',{exact:true}).fill('기말 시험');await dialog.getByLabel('일정 날짜와 시간').fill('2026-12-10T14:00');await dialog.getByRole('checkbox',{name:'시험 일정',exact:true}).check();await dialog.getByRole('button',{name:'일정 저장',exact:true}).click();await expect(dialog.getByRole('button',{name:/기말 시험 시험/})).toBeVisible();await dialog.getByRole('button',{name:'수정',exact:true}).click();await dialog.getByLabel('일정 제목',{exact:true}).fill('기말 시험 변경');await dialog.getByRole('button',{name:'일정 저장',exact:true}).click();await expect(dialog.getByText('기말 시험 변경',{exact:true})).toBeVisible();await page.screenshot({path:'../build/reports/custom-schedule.png'});
  await dialog.getByRole('checkbox',{name:'기말 시험 변경 완료'}).click();await expect(dialog.getByText('기말 시험 변경',{exact:true})).toHaveCount(0);await dialog.getByRole('checkbox',{name:'완료한 일정 보기'}).check();await dialog.getByRole('button',{name:'기말 시험 변경 삭제'}).click();await dialog.getByRole('button',{name:'삭제',exact:true}).click();await expect.poll(async()=>(await commands(page,'deletePersonal')).length).toBe(1);
  await dialog.getByRole('button',{name:'알림 1',exact:true}).click();await expect(dialog.getByText('HW1 · 95')).toBeVisible();await dialog.getByRole('button',{name:/성적 · 전기에너지변환/}).click();await expect.poll(()=>commands(page,'noticeAck')).toContainEqual({action:'noticeAck',id:'grade',read:true});
});

test('pet labels every session with its project and opens the correct session',async({page})=>{
  await page.setViewportSize({width:304,height:600});const s=base();s.sessions[0].phase='running';s.sessions[1].phase='done';s.sessions[1].unread=true;for(let n=3;n<=6;n++)s.sessions.push({...session(`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`,`대화 ${n}`),phase:'running'});await mount(page,s,true);await expect(page.locator('.pet-bubble')).toHaveCount(6);const petBox=await page.locator('.pet-character').boundingBox();for(const bubble of await page.locator('.pet-bubble').all()){const box=await bubble.boundingBox();expect(Math.abs(box!.x+box!.width/2-petBox!.x-petBox!.width/2)).toBeLessThan(1);}await expect(page.getByTestId('pet-'+b)).toContainText('전기에너지변환');await page.getByTestId('pet-'+b).getByRole('button').first().click();await expect.poll(()=>commands(page,'openChat')).toEqual([{action:'openChat',chatId:b}]);await page.screenshot({path:'../build/reports/montage-pet.png',omitBackground:true});
});

test('pet animates by session state while bubbles and hit target stay centered',async({page})=>{
  await page.setViewportSize({width:304,height:600});await page.emulateMedia({reducedMotion:'no-preference'});const s=base();await mount(page,s,true);
  const pet=page.locator('.pet-character');const target=await pet.boundingBox();
  async function centered(selector:string){const bubble=await page.locator(selector).boundingBox();const box=await pet.boundingBox();expect(box).toEqual(target);expect(Math.abs(bubble!.x+bubble!.width/2-box!.x-box!.width/2)).toBeLessThan(1);expect(bubble!.y+bubble!.height).toBeLessThan(box!.y+10);}
  async function moves(selector:string){const locator=page.locator(selector);await expect.poll(()=>locator.evaluate(el=>el.getAnimations().filter(a=>a.playState==='running').length)).toBeGreaterThan(0);const samples=await locator.evaluate(async el=>{const first=getComputedStyle(el).transform;await new Promise(r=>setTimeout(r,375));return [first,getComputedStyle(el).transform];});expect(samples[0]).not.toBe(samples[1]);}
  await expect(pet).toHaveAttribute('data-motion','idle');await centered('.pet-greeting');await moves('.robot-head');
  await page.mouse.move(target!.x+target!.width/2,target!.y+target!.height/2);await page.mouse.down();await page.mouse.move(target!.x+target!.width/2+14,target!.y+target!.height/2+3);await expect(pet).toHaveAttribute('data-dragging','true');expect(await pet.locator('.robot').evaluate(el=>el.getAnimations({subtree:true}).every(a=>a.playState==='paused'))).toBe(true);await page.mouse.up();await expect(pet).toHaveAttribute('data-dragging','false');
  s.sessions[0]={...s.sessions[0],phase:'running',status:'자료를 읽고 있어요'};await page.evaluate(s=>(window as any).__push(s),s);await expect(pet).toHaveAttribute('data-motion','working');await centered('.pet-bubble');await moves('.robot-arm-left');
  s.sessions[0]={...s.sessions[0],phase:'done',status:'답변이 도착했어요',unread:true,updatedAt:2};await page.evaluate(s=>(window as any).__push(s),s);await expect(pet).toHaveAttribute('data-motion','done');await expect(page.locator('.is-celebrating')).toHaveCount(1);await centered('.pet-bubble');
  await page.evaluate(s=>(window as any).__push(s),s);await page.waitForTimeout(1600);await expect(page.locator('.is-celebrating')).toHaveCount(0);await page.evaluate(s=>(window as any).__push(s),s);await expect(page.locator('.is-celebrating')).toHaveCount(0);
  await page.screenshot({path:'../build/reports/pet-motion-done.png',omitBackground:true});
  s.sessions[1].phase='running';await page.evaluate(s=>(window as any).__push(s),s);await expect(pet).toHaveAttribute('data-motion','working');
  s.sessions[1]={...s.sessions[1],phase:'failed',unread:true,error:'연결 오류'};await page.evaluate(s=>(window as any).__push(s),s);await expect(pet).toHaveAttribute('data-motion','failed');await expect(page.locator('.is-celebrating')).toHaveCount(0);
});

test('PDF work animates, successful completion waves once and reduced motion stays quiet',async({page})=>{
  await page.setViewportSize({width:304,height:600});const s=base();await mount(page,s,true);s.sessions[0].pdfBusy=true;await page.evaluate(s=>(window as any).__push(s),s);await expect(page.locator('.pet-character')).toHaveAttribute('data-motion','working');await expect(page.getByText('PDF를 만들고 있어요')).toBeVisible();
  s.sessions[0].artifacts.push({id:'pdf',name:'결과.pdf',kind:'generated',bytes:100,path:'generated/test.pdf',messageId:null});await page.evaluate(s=>(window as any).__push(s),s);s.sessions[0].pdfBusy=false;await page.evaluate(s=>(window as any).__push(s),s);await expect(page.locator('.pet-character')).toHaveAttribute('data-motion','done');await expect(page.locator('.is-celebrating')).toHaveCount(1);
  await page.emulateMedia({reducedMotion:'reduce'});expect(await page.locator('.robot').evaluate(el=>el.getAnimations({subtree:true}).filter(a=>a.playState==='running').length)).toBe(0);
});

test('sidebar has matching outline icons and custom assistant name updates the UI',async({page})=>{
  const s=base();s.sessions[0].messages=[{id:'m',role:'assistant',text:'같이 공부해요'}];await mount(page,s);
  for(const button of await page.locator('.sidebar-bottom>button').all()){await expect(button.locator('svg')).toHaveAttribute('fill','none');expect(await button.evaluate(el=>parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(15);}
  await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();await page.getByRole('textbox',{name:'비서 이름',exact:true}).fill('공부 친구');await page.getByRole('button',{name:'이름 저장',exact:true}).click();await expect(page.getByRole('button',{name:'공부 친구 종료',exact:true})).toBeVisible();await expect(page.getByRole('heading',{name:'공부 친구 설정',exact:true})).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'완료',exact:true}).click();await expect(page.locator('.brand')).toContainText('공부 친구');await expect(page.locator('.assistant-name')).toHaveText('공부 친구');await expect(page).toHaveTitle('공부 친구');await page.screenshot({path:'../build/reports/custom-name-outline.png'});
});

test('one random example is stable per session and uses the projects course scope',async({page})=>{
  await mount(page,base());const example=page.getByRole('button',{name:'예시 질문 사용'});await expect(example).toHaveCount(1);const text=(await example.innerText()).replace('↗','').trim();await example.click();await expect(page.getByRole('textbox')).toHaveValue(text);
  await page.getByRole('button',{name:'전기에너지변환',exact:true}).click();await expect(example).toHaveCount(1);const scoped=(await example.innerText()).replace('↗','').trim();expect(scoped.startsWith('전기에너지변환')||scoped==='이번 주에 할 공부를 같이 계획해줘').toBe(true);
  await page.getByRole('button',{name:'통합 프로젝트',exact:true}).click();await expect(example).toContainText(text);await expect(page.getByRole('textbox')).toHaveValue(text);
  const choices=new Set<string>([text]);for(let n=0;n<20;n++){await page.getByRole('button',{name:'＋ 새 대화',exact:true}).click();await expect(example).toHaveCount(1);choices.add(await example.innerText());}expect(choices.size).toBeGreaterThan(1);
});

test('photos attach, survive session switches, send without text and preview image results',async({page})=>{
  const s=base();await mount(page,s);const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/8sAAAAASUVORK5CYII=','base64');
  await page.getByLabel('사진 파일 선택').setInputFiles({name:'문제.png',mimeType:'image/png',buffer:png});await expect(page.locator('.draft-images img')).toHaveCount(1);await expect(page.getByRole('button',{name:'보내기 ↑',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'전기에너지변환',exact:true}).click();await expect(page.locator('.draft-images img')).toHaveCount(0);await page.getByRole('button',{name:'통합 프로젝트',exact:true}).click();await expect(page.locator('.draft-images img')).toHaveCount(1);
  await page.getByRole('button',{name:'보내기 ↑',exact:true}).click();await expect.poll(()=>commands(page,'send')).toEqual([{action:'send',chatId:a,text:''}]);
  s.sessions[0].messages=[{id:'question',role:'user',text:''},{id:'answer',role:'assistant',text:'사진에서 읽은 내용이에요.'}];s.sessions[0].artifacts=[{id:'input',name:'문제.png',path:'uploads/input.png',bytes:png.length,kind:'attachment',messageId:'question'},{id:'output',name:'풀이.png',path:'etl-files/output.png',bytes:png.length,kind:'requested',messageId:'answer'}];await page.evaluate(s=>(window as any).__push(s),s);
  await expect(page.locator('.message.user img')).toHaveCount(1);await expect(page.locator('.message.assistant img')).toHaveCount(1);await expect.poll(()=>page.locator('.image-card img').evaluateAll(els=>els.every(el=>(el as HTMLImageElement).naturalWidth>0))).toBe(true);
  await page.getByRole('button',{name:/풀이.png/}).click();await expect(page.locator('.preview-image')).toBeVisible();expect(await page.locator('.preview-image').evaluate(el=>(el as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);await page.screenshot({path:'../build/reports/photo-input-output.png'});
});

test('clipboard images and dropped photos attach while excessive selections show an error',async({page})=>{
  await mount(page,base());
  await page.getByRole('textbox').evaluate(el=>{const data=new DataTransfer();data.items.add(new File([new Uint8Array([1,2,3])],'붙여넣기.png',{type:'image/png'}));el.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));});await expect(page.locator('.draft-images img')).toHaveCount(1);
  await page.locator('.composer').evaluate(el=>{const data=new DataTransfer();data.items.add(new File([new Uint8Array([1,2,3])],'드래그.jpg',{type:'image/jpeg'}));el.dispatchEvent(new DragEvent('drop',{dataTransfer:data,bubbles:true,cancelable:true}));});await expect(page.locator('.draft-images img')).toHaveCount(2);
  await page.getByRole('button',{name:'붙여넣기.png 첨부 취소'}).click();await expect(page.locator('.draft-images img')).toHaveCount(1);
  await page.getByLabel('사진 파일 선택').setInputFiles(Array.from({length:5},(_,i)=>({name:`too-many-${i}.png`,mimeType:'image/png',buffer:Buffer.from([1])})));await expect(page.getByRole('alert')).toContainText('파일은 5개');await expect(page.locator('.draft-images img')).toHaveCount(1);
});

test('pet motion override works when Windows requests reduced motion and persists across states',async({page})=>{
  await page.emulateMedia({reducedMotion:'reduce'});const s=base();await mount(page,s,true);
  const animations=()=>page.locator('.robot').evaluate(el=>el.getAnimations({subtree:true}).filter(a=>a.playState==='running').length);
  expect(await animations()).toBe(0);
  await page.evaluate(()=>window.youngmini.control('petMotion','on'));await expect(page.locator('.pet-shell')).toHaveAttribute('data-motion-policy','on');await expect.poll(animations).toBeGreaterThan(0);
  s.sessions[0].phase='running';await page.evaluate(s=>(window as any).__push(s),s);await expect(page.locator('.pet-character')).toHaveAttribute('data-motion','working');await expect.poll(animations).toBeGreaterThan(0);
  await page.evaluate(()=>window.youngmini.control('petMotion','off'));await expect.poll(animations).toBe(0);
});

test('settings explains reduced motion and lets the user enable only the pet',async({page})=>{
  await page.emulateMedia({reducedMotion:'reduce'});await mount(page,base());await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();await expect(page.getByText('Windows에서 움직임을 줄이도록 설정되어 있어 펫이 멈춰 있어요. 항상 켜기를 선택하면 펫만 움직여요.')).toBeVisible();
  await page.getByLabel('펫 모션',{exact:true}).click();await page.getByRole('option',{name:'항상 켜기',exact:true}).click();await expect(page.getByLabel('펫 모션',{exact:true})).toContainText('항상 켜기');await expect(page.getByText('Windows에서 움직임을 줄이도록 설정되어 있어 펫이 멈춰 있어요. 항상 켜기를 선택하면 펫만 움직여요.')).toHaveCount(0);
});

test('pet right click opens its menu without starting a drag or opening chat',async({page})=>{
  await mount(page,base(),true);await page.locator('.pet-character').click({button:'right'});
  await expect.poll(()=>page.evaluate(()=>(window as any).__controls.filter((c:any)=>c[0]==='petMenu').length)).toBe(1);
  expect(await page.evaluate(()=>(window as any).__controls.filter((c:any)=>c[0]==='show'||c[0]==='dragStart'))).toEqual([]);
  await expect(page.locator('.pet-character')).toHaveAttribute('data-dragging','false');
});

test('home dashboard separates exams and lets personal tasks update without changing chats',async({page})=>{
  const s=base(),now=Date.now();const today=new Date(now+9*3600000).toISOString().slice(0,10),due=Date.parse(today+'T23:58:00+09:00');
  s.sessions[0].draft='작성하던 질문';s.sessions[1].phase='running';
  s.agenda.personal=[{id:'todo',title:'보고서 개요 쓰기',due,kind:'task',notes:'',reminders:[10],done:false},{id:'exam',title:'선형대수학 기말고사',due:now+3*86400000,kind:'exam',notes:'',reminders:[10],done:false}];
  s.agenda.academic=[{id:'10:1',title:'과제 5 제출',due:now+2*86400000,kind:'task',course:'전기에너지변환',done:false,score:null,url:'https://myetl.snu.ac.kr/courses/10/assignments/1'}];
  s.agenda.announcements=[{id:'10:20',title:'기말 범위 안내',course:'전기에너지변환',at:now-3600000,url:'https://myetl.snu.ac.kr/courses/10/discussion_topics/20',read:false}];
  await mount(page,s,false,true);await expect(page.getByTestId('home-dashboard')).toBeVisible();await expect(page.locator('#home-deadlines')).toContainText('과제 5 제출');await expect(page.locator('#home-deadlines')).not.toContainText('기말고사');await expect(page.locator('.home-exams')).toContainText('선형대수학 기말고사');await expect(page.locator('#home-today')).toContainText('보고서 개요 쓰기');
  await page.locator('#home-today').getByRole('checkbox',{name:'보고서 개요 쓰기 완료'}).check();await expect.poll(()=>commands(page,'completePersonal')).toContainEqual({action:'completePersonal',id:'todo',done:true});await expect(page.locator('#home-deadlines')).not.toContainText('보고서 개요 쓰기');
  await page.getByRole('button',{name:/기말 범위 안내/}).click();await expect.poll(()=>commands(page,'readAnnouncement')).toContainEqual({action:'readAnnouncement',id:'10:20'});await expect(page.locator('#home-announcements')).not.toContainText('기말 범위 안내');await page.getByRole('dialog').getByRole('button',{name:'완료',exact:true}).click();
  await page.getByRole('button',{name:'통합 프로젝트',exact:true}).click();await expect(page.getByRole('textbox')).toHaveValue('작성하던 질문');await page.getByRole('textbox').fill('계속 작성 중');await page.getByRole('button',{name:'홈',exact:true}).click();await page.getByRole('button',{name:'통합 프로젝트',exact:true}).click();await expect(page.getByRole('textbox')).toHaveValue('계속 작성 중');expect(await commands(page,'interrupt')).toEqual([]);
});

test('dashboard remains readable at desktop and narrow widths and opens add and notice views',async({page})=>{
  await mount(page,base(),false,true);
  for(const [width,height] of [[1560,980],[1000,720],[720,600]]){await page.setViewportSize({width,height});const sizes=await page.getByTestId('home-dashboard').evaluate(el=>({available:el.clientWidth,content:el.scrollWidth}));expect(sizes.content).toBeLessThanOrEqual(sizes.available+1);await expect(page.getByRole('button',{name:'홈',exact:true})).toBeVisible();await page.screenshot({path:`../build/reports/dashboard-${width}.png`});}
  await page.getByRole('button',{name:'＋ 할 일 추가',exact:true}).click();await expect(page.getByLabel('일정 제목',{exact:true})).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'완료',exact:true}).click();await page.getByRole('button',{name:'알림 기록',exact:true}).click();await expect(page.getByRole('button',{name:'모두 읽음'})).toBeVisible();
});

test('background session selection leaves home even for the same active chat',async({page})=>{
  const s=base();s.sessions[0].phase='running';await mount(page,s,false,true);await page.locator('.home-running').first().click();await expect(page.getByTestId('chat-'+a)).toBeVisible();expect(await commands(page,'interrupt')).toEqual([]);
});

test('eTL login and manual paste are explicit and removed technical copy stays absent',async({page})=>{
  await mount(page,base());await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();const dialog=page.getByRole('dialog');await expect(dialog).not.toContainText('로그인 정보는 Windows 계정으로 암호화');await expect(dialog).not.toContainText('첫 조회는 기준만 저장');await expect(dialog.getByLabel('eTL 액세스 토큰')).toHaveCount(0);await dialog.getByRole('button',{name:'서울대 로그인으로 연결'}).click();await expect.poll(()=>page.evaluate(()=>(window as any).__controls.some((c:any)=>c[0]==='etlLogin'))).toBe(true);await dialog.getByRole('button',{name:'토큰 직접 붙여넣기'}).click();await expect(dialog.getByLabel('eTL 액세스 토큰')).toBeVisible();
});

test('academic dialogs create manual classes and score distributions without moving the chat',async({page})=>{
  await mount(page,base());await page.getByRole('button',{name:'시간표',exact:true}).click();let d=page.getByRole('dialog');await d.getByRole('button',{name:'수업 추가',exact:true}).click();await d.getByLabel('수업 과목명').fill('직접 수업');await d.getByLabel('월요일 수업').check();await d.getByRole('button',{name:'수업 저장',exact:true}).click();await expect(d.locator('.week-class')).toContainText('직접 수업');await page.screenshot({path:'../build/reports/school-timetable.png'});await d.getByRole('button',{name:'완료',exact:true}).click();await expect(page.getByTestId('chat-'+a)).toBeVisible();
  await page.getByRole('button',{name:'성적 통계',exact:true}).click();d=page.getByRole('dialog');await d.getByRole('button',{name:'통계 추가',exact:true}).click();await d.getByLabel('통계 제목').fill('중간고사');await d.getByLabel('평균',{exact:true}).fill('60');await d.getByRole('button',{name:'통계 저장',exact:true}).click();await expect(d.locator('.score-row')).toContainText('중간고사');await page.screenshot({path:'../build/reports/school-scores.png'});
});
test('write confirmation stays with its session and cancels when leaving the chat',async({page})=>{
  const s=base();s.sessions[0].phase='running';s.approvals=[{id:'request1',chatId:a,title:'일정 추가',preview:'미적분 시험\n12월 10일 14:00',expiresAt:Date.now()+90000},{id:'request2',chatId:b,title:'다른 대화 변경',preview:'다른 내용',expiresAt:Date.now()+90000}];await mount(page,s);await expect(page.getByLabel('비서의 변경 확인')).toContainText('미적분 시험');await expect(page.getByText('다른 대화 변경',{exact:true})).toHaveCount(0);await page.screenshot({path:'../build/reports/write-approval.png'});await page.getByRole('button',{name:'이 내용으로 변경',exact:true}).click();await expect.poll(()=>commands(page,'approveWrite')).toContainEqual({action:'approveWrite',id:'request1',approved:true});
  await page.getByRole('button',{name:'홈',exact:true}).click();await expect.poll(async()=>(await commands(page,'cancelApprovals')).length).toBeGreaterThan(0);
});
test('advanced preferences choose dark mode and keep modal controls visible',async({page})=>{
  await mount(page,base());await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();const d=page.getByRole('dialog');await d.getByLabel('친근한 반말로 대화').check();await d.getByLabel('방해 금지 시간',{exact:true}).check();await expect(d.getByLabel('방해 금지 시작')).toBeVisible();await d.getByRole('combobox',{name:'화면 테마'}).click();await page.getByRole('option',{name:'다크',exact:true}).click();await expect(page.locator('html')).toHaveAttribute('data-youngmini-theme','dark');await page.screenshot({path:'../build/reports/settings-dark.png'});await d.locator('.dialog-body').evaluate(el=>el.scrollTop=el.scrollHeight);await expect(d.getByRole('button',{name:'완료',exact:true})).toBeInViewport();await d.getByRole('button',{name:'버그 신고 ↗'}).click();await expect.poll(()=>page.evaluate(()=>(window as any).__controls)).toContainEqual(['openLink','https://open.kakao.com/o/sR3ebBYi']);
});
test('notice details keep their text after marking read and offer reviewed schedule actions',async({page})=>{
  const s=base();s.agenda.announcements=[{id:'10:20',courseId:10,title:'기말 시험 안내',body:'12월 시험의 범위는 10~17강입니다.',category:'시험',course:'전기에너지변환',at:Date.now()-1000,url:'https://myetl.snu.ac.kr/courses/10/discussion_topics/20',read:false}];await mount(page,s,false,true);await page.getByRole('button',{name:/기말 시험 안내/}).click();const d=page.getByRole('dialog');await expect(d).toContainText('10~17강');await d.getByRole('button',{name:'일정에 기록',exact:true}).click();await expect(d.getByLabel('일정 제목',{exact:true})).toHaveValue('기말 시험 안내');await expect(d.getByRole('checkbox',{name:'시험 일정',exact:true})).toBeChecked();expect((await commands(page,'savePersonal')).length).toBe(0);await page.screenshot({path:'../build/reports/notice-schedule-review.png'});
});

test('custom name restart is available beside exit in settings',async({page})=>{
  await mount(page,base());await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();await page.getByLabel('비서 이름',{exact:true}).fill('공부 친구');await page.getByRole('button',{name:'이름 저장',exact:true}).click();const dialog=page.getByRole('dialog');await expect(dialog.getByRole('button',{name:'공부 친구 종료',exact:true})).toBeVisible();await dialog.getByRole('button',{name:'공부 친구 재시작',exact:true}).click();await expect.poll(()=>page.evaluate(()=>(window as any).__controls)).toContainEqual(['restart',undefined]);
});

test('Windows update banner shows reviewed release notes and a version-bound download action',async({page})=>{
  await mount(page,base());await page.evaluate(()=>(window as any).__shellPush({update:{available:{version:'0.5.2',url:'https://github.com/manig235/ETLAssistant/releases/download/windows-v0.5.2/youngmin-windows-x64-v0.5.2.zip',releaseUrl:'https://github.com/manig235/ETLAssistant/releases/tag/windows-v0.5.2',text:'Windows 0.5.2 업데이트가 있어요.',notes:'재시작 개선\n<script>window.hacked=true</script>',bytes:400000000},checking:false,error:null,checkedAt:Date.now(),remindAt:0}}));
  await page.getByRole('button',{name:'업데이트 보기',exact:true}).click();const d=page.getByRole('dialog');await expect(d.getByRole('heading',{name:'Windows 0.5.2 업데이트',exact:true})).toBeVisible();await expect(d).toContainText('재시작 개선');expect(await page.evaluate(()=>(window as any).hacked)).toBeUndefined();await d.getByRole('button',{name:'GitHub에서 받기 ↗',exact:true}).click();await expect.poll(()=>page.evaluate(()=>(window as any).__controls)).toContainEqual(['downloadUpdate','0.5.2']);await page.screenshot({path:'../build/reports/windows-update-prompt.png'});await d.getByRole('button',{name:'나중에 알림',exact:true}).click();await expect(page.getByLabel('Windows 업데이트 알림')).toHaveCount(0);await expect(page.getByTestId('chat-'+a)).toBeVisible();
});
test('Windows update notifications can be disabled in settings',async({page})=>{
  await mount(page,base());await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();const checkbox=page.getByRole('checkbox',{name:'새 Windows 버전 알림',exact:true});await checkbox.uncheck();await expect.poll(()=>commands(page,'schoolSettings')).toContainEqual({action:'schoolSettings',settings:{updates:false}});await expect(checkbox).not.toBeChecked();
});
