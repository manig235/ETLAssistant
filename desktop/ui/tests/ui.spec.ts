import {test,expect,type Page} from '@playwright/test';
import type {State,Session} from '../src/types';
import {initialState} from '../src/types';
const a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002';
const session=(id:string,title:string,projectId='integrated'):Session=>({id,title,projectId,draft:'',model:'sol',effort:'high',phase:'idle',status:'대화를 시작해 보세요',error:null,unread:false,updatedAt:1,artifacts:[],pdfBusy:false,messages:[]});
const base=():State=>({...structuredClone(initialState),starting:false,ready:true,loggedIn:true,etlConnected:true,activeChatId:a,visibleChatIds:[a],courses:[{id:10,name:'전기에너지변환',term:'2026-2'},{id:11,name:'선형대수학',term:'2026-2'}],projects:[initialState.projects[0],{id:'course-10',name:'전기에너지변환',kind:'course',courseIds:[10],instructions:''}],models:[{model:'sol',displayName:'GPT-6.1-Sol',isDefault:true,defaultEffort:'medium',efforts:[{effort:'low',description:'빠르게'},{effort:'medium',description:'보통'},{effort:'high',description:'깊게'},{effort:'xhigh',description:'매우 깊게'}]}],sessions:[session(a,'시험 대비'),session(b,'개념 질문','course-10')],agenda:{personal:[],academic:[],notices:[],settings:{onboarded:true,deadlines:true,announcements:true,grades:true,personal:true,completion:true}}});
async function mount(page:Page,state:State,pet=false) {
  await page.addInitScript(s=>{
    let current=s;const listeners:((v:unknown)=>void)[]=[];const emit=()=>listeners.forEach(fn=>fn(structuredClone(current)));
    const select=(id:string)=>{const chat=current.sessions.find(c=>c.id===id)!;current.activeChatId=id;current.activeProjectId=chat.projectId;current.visibleChatIds=[id];};
    Object.assign(window,{__commands:[],__controls:[],__push:(next:State)=>{current=next;emit();},youngmini:{getState:async()=>({state:structuredClone(current),shell:{pet:true}}),onState:(fn:(s:unknown)=>void)=>{listeners.push(fn);return()=>{};},onShell:()=>()=>{},readArtifact:async(id:string)=>({kind:'text',text:`미리보기 ${id}\n자료 내용입니다.`}),command:async(c:any)=>{(window as any).__commands.push(c);switch(c.action){
      case 'openChat':select(c.chatId);break;
      case 'openProject':{let chat=current.sessions.find(s=>s.projectId===c.projectId);if(!chat){chat={...current.sessions[0],id:crypto.randomUUID(),projectId:c.projectId,title:'새 대화',messages:[],artifacts:[],draft:''};current.sessions.push(chat);}select(chat.id);break;}
      case 'newChat':{const id=crypto.randomUUID();current.sessions.push({...current.sessions[0],id,projectId:c.projectId||current.activeProjectId,title:'새 대화',draft:'',messages:[],artifacts:[]});select(id);break;}
      case 'setDraft':current.sessions.find(s=>s.id===c.chatId)!.draft=c.text;break;
      case 'setChoice':Object.assign(current.sessions.find(s=>s.id===c.chatId)!,{model:c.model,effort:c.effort});break;
      case 'settings':Object.assign(current.agenda.settings,c.settings);break;
      case 'saveProject':{const id=c.projectId||crypto.randomUUID();current.projects.push({id,name:c.name,kind:'custom',courseIds:c.courseIds,instructions:c.instructions});break;}
      case 'savePersonal':{const item={...c.item,id:c.item.id||crypto.randomUUID()};current.agenda.personal=current.agenda.personal.filter(i=>i.id!==item.id).concat(item);break;}
      case 'completePersonal':current.agenda.personal.find(i=>i.id===c.id)!.done=c.done;break;
      case 'deletePersonal':current.agenda.personal=current.agenda.personal.filter(i=>i.id!==c.id);break;
      case 'noticeAck':{const n=current.agenda.notices.find(n=>n.id===c.id);if(n){n.read=c.read;n.delivered=true;}break;}
    }emit();},control:async(...args:unknown[])=>{(window as any).__controls.push(args);}}});
  },state);
  await page.goto('/'+(pet?'?pet=1':''));await expect(page.locator(pet?'.pet-shell':'.workspace')).toBeVisible();
}
const commands=(page:Page,action:string)=>page.evaluate(action=>(window as any).__commands.filter((c:any)=>c.action===action),action);

test('startup IPC state renders before backend output and fills missing older fields',async({page})=>{
  const cold=structuredClone(initialState) as Partial<State>;delete cold.projects;delete cold.agenda;
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
  await mount(page,cold as State);
  await expect(page.getByRole('button',{name:'통합 프로젝트',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();await expect(page.getByRole('dialog').getByRole('heading',{name:'연결 · 설정',exact:true})).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'완료',exact:true}).click();
  await page.evaluate(s=>(window as any).__push(s),base());await expect(page.getByTestId('chat-'+a)).toBeVisible();expect(errors).toEqual([]);
});

test('unexpected rendering error leaves a visible recovery screen',async({page})=>{
  await mount(page,base());await page.evaluate(()=>(window as any).__push({projects:[null]}));
  await expect(page.getByRole('alert')).toContainText('화면을 표시하지 못했어요');await expect(page.getByRole('button',{name:'다시 불러오기',exact:true})).toBeVisible();
});

test('settings header and fixed footer stay in viewport at desktop, narrow and short sizes',async({page})=>{
  await mount(page,base());await page.getByRole('button',{name:'연결 · 설정',exact:true}).click();
  for(const [width,height,label] of [[1560,980,'desktop'],[720,720,'narrow'],[1000,500,'short']] as const){
    await page.setViewportSize({width,height});const dialog=page.getByRole('dialog');const title=dialog.getByText('연결 · 설정',{exact:true});const done=dialog.getByRole('button',{name:'완료',exact:true});
    await expect(title).toBeVisible();await expect(done).toBeInViewport();const box=await title.boundingBox();const frame=await dialog.boundingBox();expect(box!.x-frame!.x).toBeGreaterThanOrEqual(20);expect(box!.y-frame!.y).toBeGreaterThanOrEqual(12);
    await dialog.locator('.dialog-body').evaluate(el=>el.scrollTop=el.scrollHeight);await expect(done).toBeInViewport();await expect(dialog.getByRole('button',{name:'연결 · 설정 닫기'})).toBeInViewport();
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

<img src="https://example.test/track" onerror="window.hacked=true" />` }];const external:string[]=[];page.on('request',r=>{if(!r.url().startsWith('http://127.0.0.1:4178'))external.push(r.url());});await mount(page,s);
  await expect(page.getByRole('heading',{name:'Ch4 시험 대비'})).toBeVisible();await expect(page.locator('.katex')).toHaveCount(2);await expect(page.locator('.katex-error')).toHaveCount(0);await expect(page.locator('.markdown table')).toBeVisible();expect(external).toEqual([]);await page.getByRole('button',{name:'PDF로 받기'}).click();await expect.poll(()=>commands(page,'createPdf')).toEqual([{action:'createPdf',chatId:a,messageId:'answer'}]);await page.screenshot({path:'../build/reports/montage-markdown.png'});
});

test('first run explains projects, optional connections and tray notification lifecycle',async({page})=>{
  const s=base();s.agenda.settings.onboarded=false;await mount(page,s);const dialog=page.getByRole('dialog');await expect(dialog).toContainText('여러 세션이 동시에 작업');await dialog.getByRole('button',{name:'다음',exact:true}).click();await expect(dialog.getByRole('heading',{name:'계정 연결하기'})).toBeVisible();await dialog.getByRole('button',{name:'다음',exact:true}).click();await expect(dialog).toContainText('창을 닫아도 트레이');await expect(dialog).toContainText('PC가 꺼져 있거나 절전 중');await page.screenshot({path:'../build/reports/onboarding-notifications.png'});await dialog.getByRole('button',{name:'시작하기',exact:true}).click();await expect.poll(()=>commands(page,'settings')).toContainEqual({action:'settings',settings:{onboarded:true}});await expect(dialog).toHaveCount(0);
});

test('custom schedule CRUD and grade result inbox work',async({page})=>{
  const s=base();s.agenda.notices=[{id:'grade',kind:'grades',title:'성적 · 전기에너지변환',body:'HW1 · 95',at:Date.now(),chatId:null,url:'https://myetl.snu.ac.kr/courses/10/assignments/20',read:false,delivered:false}];await mount(page,s);await page.getByRole('button',{name:'일정 · 알림'}).click();let dialog=page.getByRole('dialog');await dialog.getByRole('button',{name:'＋ 일정 추가'}).click();await dialog.getByLabel('일정 제목',{exact:true}).fill('기말 시험');await dialog.getByLabel('일정 날짜와 시간').fill('2026-12-10T14:00');await dialog.getByRole('checkbox',{name:'시험 일정',exact:true}).check();await dialog.getByRole('button',{name:'일정 저장',exact:true}).click();await expect(dialog.getByRole('button',{name:/기말 시험 시험/})).toBeVisible();await dialog.getByRole('button',{name:'수정',exact:true}).click();await dialog.getByLabel('일정 제목',{exact:true}).fill('기말 시험 변경');await dialog.getByRole('button',{name:'일정 저장',exact:true}).click();await expect(dialog.getByText('기말 시험 변경',{exact:true})).toBeVisible();await page.screenshot({path:'../build/reports/custom-schedule.png'});
  await dialog.getByRole('checkbox',{name:'기말 시험 변경 완료'}).click();await expect(dialog.getByText('기말 시험 변경',{exact:true})).toHaveCount(0);await dialog.getByRole('checkbox',{name:'완료한 일정 보기'}).check();await dialog.getByRole('button',{name:'기말 시험 변경 삭제'}).click();await expect.poll(async()=>(await commands(page,'deletePersonal')).length).toBe(1);
  await dialog.getByRole('button',{name:'알림 1',exact:true}).click();await expect(dialog.getByText('HW1 · 95')).toBeVisible();await dialog.getByRole('button',{name:/성적 · 전기에너지변환/}).click();await expect.poll(()=>commands(page,'noticeAck')).toContainEqual({action:'noticeAck',id:'grade',read:true});
});

test('pet labels every session with its project and opens the correct session',async({page})=>{
  await page.setViewportSize({width:304,height:600});const s=base();s.sessions[0].phase='running';s.sessions[1].phase='done';s.sessions[1].unread=true;for(let n=3;n<=6;n++)s.sessions.push({...session(`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`,`대화 ${n}`),phase:'running'});await mount(page,s,true);await expect(page.locator('.pet-bubble')).toHaveCount(6);const petBox=await page.locator('.pet-character').boundingBox();for(const bubble of await page.locator('.pet-bubble').all()){const box=await bubble.boundingBox();expect(Math.abs(box!.x+box!.width/2-petBox!.x-petBox!.width/2)).toBeLessThan(1);}await expect(page.getByTestId('pet-'+b)).toContainText('전기에너지변환');await page.getByTestId('pet-'+b).getByRole('button').first().click();await expect.poll(()=>commands(page,'openChat')).toEqual([{action:'openChat',chatId:b}]);await page.screenshot({path:'../build/reports/montage-pet.png',omitBackground:true});
});

test('pet remains still with greeting and session bubbles centered above it',async({page})=>{
  await page.setViewportSize({width:304,height:600});await page.emulateMedia({reducedMotion:'no-preference'});const s=base();await mount(page,s,true);
  async function centered(selector:string){const bubble=await page.locator(selector).boundingBox();const pet=await page.locator('.pet-character').boundingBox();expect(Math.abs(bubble!.x+bubble!.width/2-pet!.x-pet!.width/2)).toBeLessThan(1);expect(bubble!.y+bubble!.height).toBeLessThan(pet!.y+10);}
  async function still(){const pose=await page.locator('.pet-character .robot').evaluate(el=>({transform:getComputedStyle(el).transform,animations:el.getAnimations({subtree:true}).filter(a=>a.playState==='running').length}));expect(pose.animations).toBe(0);await page.waitForTimeout(250);expect(await page.locator('.pet-character .robot').evaluate(el=>getComputedStyle(el).transform)).toBe(pose.transform);}
  await centered('.pet-greeting');await still();await page.locator('.pet-character').hover();await still();await page.screenshot({path:'../build/reports/pet-centered-static.png',omitBackground:true});
  s.sessions[0]={...s.sessions[0],phase:'running',status:'자료를 읽고 있어요'};await page.evaluate(s=>(window as any).__push(s),s);await expect(page.locator('.pet-character')).toHaveAttribute('data-motion','static');await centered('.pet-bubble');await still();await page.screenshot({path:'../build/reports/pet-centered-working.png',omitBackground:true});
});
