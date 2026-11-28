import {UpdateNotice} from './UpdateNotice';
import {useEffect,useRef,useState,type KeyboardEvent} from 'react';
import {useThemeControl,Button,TextButton,IconButton,Select,Option,TextArea,Loading} from '@montage-ui/core';
import type {State,Session,ShellState,Artifact,Project,AgendaTarget} from './types';
import {initialState,normalizeState,command,control} from './types';
import {Robot} from './Robot';
import {Dashboard} from './Dashboard';
import {SchoolDialog} from './SchoolDialogs';
import {usePetMotion,type MotionPolicy} from './PetMotion';
import {NameContext,useAssistantName} from './Name';
import {SidebarIcon} from './SidebarIcon';
import {SessionIcon} from './SessionIcon';
import {DraftImages,ImageThumbnail,isImage,useImageInput} from './Images';
import {sessionSuggestion} from './Suggestion';
import {Markdown} from './Markdown';
import {Preview} from './Preview';
import {Settings,Onboarding,ProjectEditor,AgendaDialog,SessionDialog} from './Dialogs';
const effortNames:Record<string,string>={none:'끔',minimal:'최소',low:'낮음',medium:'보통',high:'높음',xhigh:'매우 높음',max:'최대',ultra:'울트라'};
export function App() {
  const [state,setState]=useState<State>(initialState);const [shell,setShell]=useState<ShellState>({pet:true});
  useEffect(()=>{if(!window.youngmini){setShell({pet:false,error:'데스크톱 앱에서 실행해 주세요.'});return;}const stop=window.youngmini.onState(s=>setState(normalizeState(s)));const stopShell=window.youngmini.onShell(s=>setShell(prev=>({...prev,...s})));void window.youngmini.getState().then(({state,shell})=>{setState(normalizeState(state));setShell(shell);}).catch(()=>setShell({pet:false,error:'앱 연결을 불러오지 못했어요. 창을 다시 열어 주세요.'}));return()=>{stop();stopShell();};},[]);
  const pet=new URLSearchParams(location.search).has('pet');
  useEffect(()=>{document.body.classList.toggle('pet-mode',pet);},[pet]);
  useEffect(()=>{if(!pet)return;const move=(e:MouseEvent)=>control('hitTest',!!document.elementFromPoint(e.clientX,e.clientY)?.closest('.pet-bubble,.pet-character,.pet-greeting'));window.addEventListener('mousemove',move);return()=>window.removeEventListener('mousemove',move);},[pet]);
  const name=shell.displayName||'영민이';
  useEffect(()=>{document.title=pet?`${name} 펫`:name;},[name,pet]);
  const {setTheme,theme:resolvedTheme}=useThemeControl();
  useEffect(()=>{setTheme(state.school.settings.theme);},[setTheme,state.school.settings.theme]);
  useEffect(()=>{document.documentElement.dataset.youngminiTheme=resolvedTheme||'light';},[resolvedTheme]);
  return <NameContext.Provider value={name}>{pet?<Pet state={state} paused={!shell.pet} motionPolicy={shell.petMotion||'system'}/>:<Workspace state={state} shell={shell}/>}</NameContext.Provider>;
}
function Workspace({state,shell}:{state:State;shell:ShellState}) {
  const name=useAssistantName();
  const [home,setHome]=useState(true),[settings,setSettings]=useState(false),[agenda,setAgenda]=useState<AgendaTarget|null>(null);
  const [guide,setGuide]=useState(false),[dismissGuide,setDismissGuide]=useState(false),[editing,setEditing]=useState<Project|null|undefined>();
  const [tabs,setTabs]=useState<Artifact[]>([]),[activeFile,setActiveFile]=useState('');
  const [sessionAction,setSessionAction]=useState<{id:string;mode:'rename'|'delete'}|null>(null);
  const managed=state.sessions.find(s=>s.id===sessionAction?.id);
  const [schoolView,setSchoolView]=useState<'timetable'|'scores'|null>(null);
  const selected=state.sessions.find(s=>s.id===state.activeChatId),project=state.projects.find(p=>p.id===state.activeProjectId)||state.projects[0];
  const running=state.sessions.filter(s=>s.phase==='running').length;
  useEffect(()=>{if(shell.openAgenda)setAgenda({tab:'schedule'});},[shell.openAgenda]);
  useEffect(()=>{if(shell.openChat)setHome(false);},[shell.openChat]);
  useEffect(()=>{
    const valid=new Set(state.sessions.flatMap(s=>[...s.artifacts,...s.draftImages]).map(a=>a.id));
    if(tabs.some(t=>!valid.has(t.id))){const next=tabs.filter(t=>valid.has(t.id));setTabs(next);if(!valid.has(activeFile))setActiveFile(next.at(-1)?.id||'');}
  },[state.sessions,tabs,activeFile]);
  function navigate(value:{action:string;[key:string]:unknown}){setHome(false);command(value);}
  function preview(file:Artifact){setTabs(old=>old.some(f=>f.id===file.id)?old:[...old,file]);setActiveFile(file.id);}
  function closePreview(id:string){setTabs(old=>{const next=id==='*'?[]:old.filter(f=>f.id!==id);if(id===activeFile||id==='*')setActiveFile(next.at(-1)?.id||'');return next;});}
  return <div className="workspace"><aside className="sidebar">
    <div className="brand"><Robot/><span title={name}>{name}</span></div>
    <button className={`home-nav ${home?'selected':''}`} aria-label="홈" aria-current={home?'page':undefined} onClick={()=>{command({action:'cancelApprovals',chatId:state.activeChatId});setHome(true);}}><SidebarIcon kind="home"/>홈</button>
    <div className="school-nav"><button onClick={()=>setSchoolView('timetable')}><SidebarIcon kind="calendar"/>시간표</button><button onClick={()=>setSchoolView('scores')}><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"><path d="M4 20h16M7 16v-5m5 5V5m5 11V8"/></svg>성적 통계</button></div>
    <Button fullWidth onClick={()=>navigate({action:'newChat'})}>＋ 새 대화</Button>
    <div className="sidebar-section-heading"><span>프로젝트</span><IconButton size="small" aria-label="프로젝트 추가" onClick={()=>setEditing(null)}>＋</IconButton></div>
    <nav className="project-list" aria-label="프로젝트와 세션">{state.projects.map(p=>{
      const sessions=state.sessions.filter(s=>s.projectId===p.id).sort((a,b)=>b.updatedAt-a.updatedAt),expanded=p.id===state.activeProjectId,working=sessions.filter(s=>s.phase==='running').length;
      return <div className="project-group" key={p.id}><button className={`project-button ${expanded&&!home?'selected':''}`} aria-label={p.name} aria-expanded={expanded} title={p.name} onClick={()=>navigate({action:'openProject',projectId:p.id})}><span className="project-icon">{p.kind==='integrated'?'◈':p.kind==='course'?'▤':'◇'}</span><span>{p.name}</span>{working>0?<span className="project-count">{working}<i className="status-dot running"/></span>:sessions.some(s=>s.unread)?<span className="unread-dot"/>:null}</button>{expanded&&<div className="project-sessions">{sessions.map(s=><div className={`session-row ${s.id===selected?.id&&!home?'selected':''}`} key={s.id}>
        <button className={`session-button ${s.id===selected?.id&&!home?'selected':''}`} aria-label={s.title} title={s.title} onClick={()=>navigate({action:'openChat',chatId:s.id})}><span className={`status-dot ${s.phase}`}/><span>{s.title}</span>{s.unread&&s.id!==selected?.id&&<span className="unread-dot"/>}</button>
        <IconButton className="session-action" size="small" aria-label={`${s.title} 이름 바꾸기`} title={s.phase==='running'||s.pdfBusy?'작업이 끝난 뒤 이름을 바꿀 수 있어요':'이름 바꾸기'} disabled={!state.ready||s.phase==='running'||s.pdfBusy} onClick={()=>setSessionAction({id:s.id,mode:'rename'})}><SessionIcon kind="edit"/></IconButton>
        <IconButton className="session-action session-delete" size="small" aria-label={`${s.title} 삭제`} title={s.phase==='running'||s.pdfBusy?'작업이 끝난 뒤 삭제할 수 있어요':'세션 삭제'} disabled={!state.ready||s.phase==='running'||s.pdfBusy} onClick={()=>setSessionAction({id:s.id,mode:'delete'})}><SessionIcon kind="delete"/></IconButton>
      </div>)}<button className="new-session" onClick={()=>navigate({action:'newChat',projectId:p.id})}>＋ 세션 추가</button></div>}</div>;
    })}</nav>
    <div className="sidebar-bottom"><button aria-label="일정 · 알림" onClick={()=>setAgenda({tab:'schedule'})}><SidebarIcon kind="calendar"/>일정 · 알림{state.agenda.notices.some(n=>!n.read)&&<span className="unread-dot"/>}</button><button aria-label="연결 · 설정" onClick={()=>setSettings(true)}><SidebarIcon kind="settings"/>연결 · 설정</button></div>
  </aside><main className={`workspace-main ${home?'home-workspace':''}`}>
    {!home&&<header className="workspace-toolbar"><div className="toolbar-title"><strong>{project?.name}</strong><span>{project?.kind==='integrated'?'2026년 2학기 전체 과목':project?.kind==='course'?'2026년 2학기':project?.courseIds.length?`${project.courseIds.length}개 과목 연결`:'나만의 프로젝트'}</span></div><div className="toolbar-actions">{running>0&&<span className="running-count"><i className="status-dot running"/>{running}개 작업 중</span>}<IconButton aria-label="프로젝트 설정" onClick={()=>setEditing(project)}>⋯</IconButton></div></header>}
    <UpdateNotice shell={shell} enabled={state.school.settings.updates}/>
    {(state.error||shell.error)&&<div className="error-banner" role="alert"><span>{state.error||shell.error}</span>{!state.ready&&!state.starting&&<TextButton onClick={()=>command({action:'start'})}>다시 연결</TextButton>}<IconButton aria-label="오류 닫기" size="small" onClick={()=>command({action:'dismissError'})}>×</IconButton></div>}
    {home?<Dashboard state={state} onAsk={id=>navigate({action:'askAnnouncement',id})} onSettings={()=>setSettings(true)} onAgenda={setAgenda} onChat={id=>navigate({action:'openChat',chatId:id})}/>:<div className={`work-content ${tabs.length?'has-preview':''}`}>{selected?<ChatPane key={selected.id} session={selected} state={state} onSettings={()=>setSettings(true)} onPreview={preview}/>:<div className="loading-line"><Loading size={20}/>{state.status}</div>}{!!tabs.length&&<Preview files={tabs} active={activeFile} onSelect={setActiveFile} onClose={closePreview}/>}</div>}
  </main>{schoolView&&<SchoolDialog state={state} view={schoolView} onClose={()=>setSchoolView(null)}/ >}{sessionAction&&managed&&<SessionDialog key={`${managed.id}:${sessionAction.mode}`} session={managed} mode={sessionAction.mode} onClose={()=>setSessionAction(null)}/>}<Settings state={state} shell={shell} open={settings} onClose={()=>setSettings(false)} onGuide={()=>{setSettings(false);setGuide(true);}}/><Onboarding state={state} shell={shell} open={guide||(!state.starting&&!state.agenda.settings.onboarded&&!dismissGuide)} onClose={()=>{setGuide(false);setDismissGuide(true);}}/>{editing!==undefined&&<ProjectEditor key={editing?.id||'new'} project={editing} state={state} onClose={()=>setEditing(undefined)}/ >}{agenda&&<AgendaDialog state={state} target={agenda} onClose={()=>setAgenda(null)}/>}</div>;
}
function ChatPane({session,state,onSettings,onPreview}:{session:Session;state:State;onSettings:()=>void;onPreview:(file:Artifact)=>void}) {
  const name=useAssistantName();
  const [draft,setDraft]=useState(session.draft);const scroll=useRef<HTMLDivElement>(null);const stick=useRef(true);const composing=useRef(false);const latest=useRef(draft);latest.current=draft;
  const photos=useImageInput(session);
  const suggestion=sessionSuggestion(session.id,state.projects.find(p=>p.id===session.projectId),state.courses);
  const busy=session.phase==='running';const model=state.models.find(m=>m.model===session.model)||state.models.find(m=>m.isDefault)||state.models[0];const canSend=state.ready&&state.loggedIn&&!state.loginBusy&&!busy&&!photos.importing&&(!!draft.trim()||session.draftImages.length>0);
  useEffect(()=>{if(stick.current)scroll.current?.scrollTo({top:scroll.current.scrollHeight});},[session.messages,session.artifacts]);
  useEffect(()=>{const timeout=setTimeout(()=>{if(draft!==session.draft)command({action:'setDraft',chatId:session.id,text:draft});},250);return()=>clearTimeout(timeout);},[draft,session.id,session.draft]);
  useEffect(()=>()=>{command({action:'setDraft',chatId:session.id,text:latest.current});},[session.id]);
  function send(){if(!canSend)return;command({action:'send',chatId:session.id,text:draft});setDraft('');latest.current='';}
  function keydown(e:KeyboardEvent<HTMLTextAreaElement>){if(e.key!=='Enter'||e.nativeEvent.isComposing||composing.current||e.nativeEvent.keyCode===229)return;if(e.ctrlKey||e.shiftKey||e.metaKey){e.preventDefault();const target=e.currentTarget,start=target.selectionStart,end=target.selectionEnd;setDraft(draft.slice(0,start)+'\n'+draft.slice(end));requestAnimationFrame(()=>target.setSelectionRange(start+1,start+1));return;}e.preventDefault();send();}
  const cards=(files:Artifact[])=>!!files.length&&<div className="artifact-cards">{files.map(f=><button key={f.id} className={`artifact-card ${isImage(f)?'image-card':''}`} onClick={()=>onPreview(f)}>{isImage(f)&&<ImageThumbnail file={f}/>}<span className="file-icon">{f.name.split('.').pop()?.toUpperCase().slice(0,5)}</span><span><strong>{f.name}</strong><small>{f.kind==='generated'?'완성된 파일':f.kind==='attachment'?'보낸 사진':f.kind==='document-attachment'?'보낸 문서':'요청한 자료'} · {f.bytes>1024*1024?`${(f.bytes/1024/1024).toFixed(1)} MB`:`${Math.max(1,Math.round(f.bytes/1024))} KB`}</small></span><span>↗</span></button>)}</div>;
  return <section className="chat-pane" data-testid={`chat-${session.id}`}><div className="chat-heading"><span className={`status-dot ${session.phase}`}/><h2>{session.title}</h2></div>{session.error&&<div className="session-error" role="alert">{session.error}<TextButton onClick={()=>command({action:'dismissError',chatId:session.id})}>닫기</TextButton></div>}<div className="message-scroll" ref={scroll} onScroll={()=>{const el=scroll.current!;stick.current=el.scrollHeight-el.scrollTop-el.clientHeight<100;}}>{!session.messages.length&&<div className="empty-chat"><Robot/><h3>무엇부터 같이 볼까요?</h3><button className="example-question" aria-label="예시 질문 사용" onClick={()=>{setDraft(suggestion);latest.current=suggestion;command({action:'setDraft',chatId:session.id,text:suggestion});}}>{suggestion}<span aria-hidden="true">↗</span></button>{!state.loggedIn&&<Button variant="outlined" onClick={onSettings}>로그인하고 시작하기</Button>}</div>}{session.messages.map(m=><article className={`message ${m.role}`} key={m.id}>{m.role==='assistant'?<><div className="assistant-name"><Robot/>{name}</div><Markdown text={m.text}/>{cards(session.artifacts.filter(f=>f.messageId===m.id))}{!busy&&<><TextButton size="small" color="assistive" disabled={session.pdfBusy} onClick={()=>command({action:'createPdf',chatId:session.id,messageId:m.id})}>{session.pdfBusy?'PDF 만드는 중…':'PDF로 받기'}</TextButton><TextButton size="small" color="assistive" onClick={()=>control('copy',m.text)}>복사</TextButton></>}</>:<div className="user-content">{m.text&&<div className="user-message">{m.text}</div>}{cards(session.artifacts.filter(f=>f.messageId===m.id))}</div>}</article>)}{cards(session.artifacts.filter(f=>!f.messageId||!session.messages.some(m=>m.id===f.messageId)))}</div>{(busy||session.pdfBusy)&&<div className="session-progress" role="status"><Loading size={16}/>{session.pdfBusy?'PDF를 만들고 있어요':session.status}</div>}{state.approvals.filter(a=>a.chatId===session.id).map(a=><aside className="write-approval" key={a.id} aria-label="비서의 변경 확인"><strong>{a.title}</strong><pre>{a.preview}</pre><div className="button-row"><TextButton color="assistive" onClick={()=>command({action:'approveWrite',id:a.id,approved:false})}>취소</TextButton><Button size="small" onClick={()=>command({action:'approveWrite',id:a.id,approved:true})}>이 내용으로 변경</Button></div></aside>)}<div className="composer" onPaste={photos.paste} onDragOver={e=>{if(e.dataTransfer.types.includes('Files'))e.preventDefault();}} onDrop={photos.drop}>{photos.picker}<DraftImages session={session} onPreview={onPreview}/>{photos.error&&<p className="image-input-error" role="alert">{photos.error}</p>}{photos.importing&&<p className="muted" role="status">사진을 준비하고 있어요</p>}<TextArea aria-label={`${session.title} 메시지`} width="100%" placeholder={state.loggedIn?'자료 찾기, 요약, 문제 만들기…':'로그인하고 대화를 시작해 보세요'} value={draft} minRows={2} maxRows={6} maxLength={32000} onChange={e=>setDraft(e.target.value)} onBlur={()=>command({action:'setDraft',chatId:session.id,text:draft})} onKeyDown={keydown} onCompositionStart={()=>{composing.current=true;}} onCompositionEnd={()=>{composing.current=false;}}/><div className="composer-actions"><IconButton aria-label="사진 첨부" title="사진 · 문서 첨부" disabled={busy||photos.importing||session.draftImages.length>=5||!state.ready} onClick={photos.open}><svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3.5" y="3.5" width="17" height="17" rx="3"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m4 17 5-5 3 3 3.5-4 4.5 5"/></svg></IconButton><div className="model-controls"><Select aria-label="모델" value={session.model||'auto'} width="100%" disabled={busy||!state.loggedIn} onChange={value=>command({action:'setChoice',chatId:session.id,model:value==='auto'?null:value,effort:null})}><Option value="auto">모델 자동</Option>{state.models.map(m=><Option value={m.model} key={m.model}>{m.displayName}</Option>)}</Select><Select aria-label="추론 강도" value={session.effort||'auto'} width="100%" disabled={busy||!state.loggedIn||!model?.efforts.length} onChange={value=>command({action:'setChoice',chatId:session.id,model:session.model,effort:value==='auto'?null:value})}><Option value="auto">강도 자동</Option>{model?.efforts.map(e=><Option key={e.effort} value={e.effort}>{effortNames[e.effort]||e.effort}</Option>)}</Select></div><Button size="small" disabled={!busy&&!canSend} onClick={()=>busy?command({action:'interrupt',chatId:session.id}):send()}>{busy?'중지':'보내기 ↑'}</Button></div></div></section>;
}
function Pet({state,paused,motionPolicy}:{state:State;paused:boolean;motionPolicy:MotionPolicy}) {
  const name=useAssistantName();
  const {motion,celebration}=usePetMotion(state.sessions);
  const [isDragging,setIsDragging]=useState(false);
  const sessions=state.sessions.filter(s=>s.phase==='running'||s.pdfBusy||s.unread).sort((a,b)=>Number(b.phase==='failed')-Number(a.phase==='failed')||Number(b.unread)-Number(a.unread)||b.updatedAt-a.updatedAt);
  const dragging=useRef(false);const start=useRef<{x:number;y:number}|null>(null);
  return <div className="pet-shell" data-motion-paused={paused} data-motion-policy={motionPolicy}>
    <div className="pet-bubbles-wrap"><div className="pet-bubbles" aria-label="세션별 작업 상태">
      {sessions.map(s=><div key={s.id} className={`pet-bubble ${s.phase}`} data-testid={`pet-${s.id}`}>
        <button onClick={()=>{command({action:'openChat',chatId:s.id});control('show');}}>
          <small className="pet-project">{state.projects.find(p=>p.id===s.projectId)?.name}</small>
          <span className="pet-bubble-title"><i className={`status-dot ${s.phase}`}/><strong>{s.title}</strong></span>
          <span className="pet-detail">{s.pdfBusy?'PDF를 만들고 있어요':s.status}</span>
        </button>
        {s.phase!=='running'&&!s.pdfBusy&&<IconButton aria-label={`${s.title} 알림 닫기`} size="small" onClick={()=>command({action:'acknowledge',chatId:s.id})}>×</IconButton>}
      </div>)}
      {!sessions.length&&<button className="pet-greeting" onClick={()=>control('show')}>{state.starting?`${name} · 준비하고 있어요`:state.loggedIn?'함께 공부할까요?':'눌러서 시작하기'}</button>}
    </div></div>
    <div className="pet-character" data-motion={motion} data-dragging={isDragging} role="button" tabIndex={0} aria-label={`${name} 채팅 열기`} onKeyDown={e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();control('show');}}}
      onContextMenu={e=>{e.preventDefault();start.current=null;setIsDragging(false);control('petMenu');}}
      onPointerDown={e=>{if(e.button!==0)return;start.current={x:e.screenX,y:e.screenY};dragging.current=false;e.currentTarget.setPointerCapture(e.pointerId);control('dragStart');}}
      onPointerMove={e=>{if(!start.current)return;const dx=e.screenX-start.current.x,dy=e.screenY-start.current.y;if(Math.abs(dx)+Math.abs(dy)>5){dragging.current=true;setIsDragging(true);control('drag',{dx,dy});}}}
      onPointerUp={()=>{if(start.current&&!dragging.current)control('show');start.current=null;setIsDragging(false);}} onPointerCancel={()=>{start.current=null;setIsDragging(false);}} onLostPointerCapture={()=>{start.current=null;setIsDragging(false);}}>
      <Robot motion={motion} celebration={celebration}/>
    </div>
  </div>;
}
