import {useEffect,useState} from 'react';
import {NoticeDialog} from './NoticeDialog';
import {Button,TextButton,Checkbox} from '@montage-ui/core';
import type {State,AgendaTarget,Announcement} from './types';
import {command,control} from './types';
import './dashboard.css';

const DAY=86400000;
export const kstDay=(time:number)=>new Date(time+9*3600000).toISOString().slice(0,10);
const dateText=(time:number)=>new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',month:'numeric',day:'numeric',weekday:'short',hour:'2-digit',minute:'2-digit',hour12:false}).format(time);
const timeText=(time:number)=>new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',hour:'2-digit',minute:'2-digit',hour12:false}).format(time);
type Task={id:string;title:string;due:number;kind:string;course:string;done:boolean;personal:boolean;url?:string;occurrence?:string|null;repeatLabel?:string};
function remaining(due:number,now:number) {
  const days=Math.round((Date.parse(kstDay(due))-Date.parse(kstDay(now)))/DAY);
  return due<now?'기한 지남':days===0?'오늘':days===1?'내일':`D-${days}`;
}
export function Dashboard({state,onAgenda,onSettings,onChat,onAsk}:{state:State;onAgenda:(target:AgendaTarget)=>void;onSettings:()=>void;onChat:(id:string)=>void;onAsk:(id:string)=>void}) {
  const [now,setNow]=useState(Date.now),[showAll,setShowAll]=useState(false),[brief,setBrief]=useState(false),[notice,setNotice]=useState<Announcement|null>(null);
  useEffect(()=>{const timer=setInterval(()=>setNow(Date.now()),15000);return()=>clearInterval(timer);},[]);
  const today=kstDay(now);
  const personal:Task[]=state.agenda.personal.filter(i=>i.id&&Number.isFinite(i.due)).map(i=>({...i,id:i.id!,course:'내 일정',personal:true}));
  const academic:Task[]=state.agenda.academic.filter(i=>i.due!=null&&Number.isFinite(i.due)).map(i=>({...i,due:i.due!,personal:false}));
  const tasks=[...personal,...academic];
  const upcoming=tasks.filter(i=>i.kind!=='exam'&&!i.done&&i.due>=now&&i.due<=now+7*DAY).sort((a,b)=>a.due-b.due);
  const exams=tasks.filter(i=>i.kind==='exam'&&!i.done&&i.due>=now).sort((a,b)=>a.due-b.due);
  const todays=tasks.filter(i=>i.kind!=='exam'&&kstDay(i.due)===today&&(i.personal||!i.done)).sort((a,b)=>Number(a.done)-Number(b.done)||a.due-b.due);
  const overdue=tasks.filter(i=>i.kind!=='exam'&&!i.done&&i.due<now&&kstDay(i.due)!==today).sort((a,b)=>b.due-a.due);
  const notices=new Map<string,Announcement>();
  for(const n of state.agenda.notices.filter(n=>n.kind==='announcements'&&!n.read))notices.set(n.url||n.id,{id:`notice:${n.id}`,title:n.body,course:n.title.replace(/^공지 · /,''),at:n.at,url:n.url||'',read:false});
  for(const n of state.agenda.announcements||[])notices.set(n.url||n.id,n);
  const announcements=[...notices.values()].filter(n=>!n.read&&n.at>=now-7*DAY&&n.at<=now).sort((a,b)=>b.at-a.at);
  const running=state.sessions.filter(s=>s.phase==='running'||s.pdfBusy);
  function openTask(item:Task) {if(item.personal)onAgenda({tab:'schedule',itemId:item.id,occurrence:item.occurrence});else if(item.url)control('openLink',item.url);}
  function openNotice(item:Announcement) {
    if(item.id.startsWith('notice:'))command({action:'noticeAck',id:item.id.slice(7),read:true});
    else command({action:'readAnnouncement',id:item.id});
    setNotice(item);
  }
  function taskRow(item:Task,check=false) {
    return <div className={`home-task ${item.done?'is-done':''}`} key={`${item.personal?'personal':'academic'}:${item.id}:${item.occurrence||''}`}>
      {check&&item.personal?<Checkbox aria-label={`${item.title} 완료`} checked={item.done} onCheckedChange={done=>command({action:'completePersonal',id:item.id,done,occurrence:item.occurrence})}/>:<span className={`due-pill ${item.due-now<DAY?'urgent':''}`}>{remaining(item.due,now)}</span>}
      <button className="home-row-link" onClick={()=>openTask(item)}><strong>{item.title}</strong><span>{item.course}{item.repeatLabel?` · ${item.repeatLabel}`:''}{item.personal?'':' · eTL'} · {check?timeText(item.due):dateText(item.due)}</span></button>
      <span className="home-row-arrow" aria-hidden="true">›</span>
    </div>;
  }
  return <section className="home-dashboard" data-testid="home-dashboard" aria-label="홈 대시보드">
    <header className="home-heading"><div><p className="home-eyebrow">오늘의 학습</p><h1>{new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',month:'long',day:'numeric',weekday:'short'}).format(now)}</h1>{(state.agenda.lastSync||state.etlConnected)&&<p className="home-sync">{state.agenda.lastSync?`eTL 최근 확인 · ${dateText(state.agenda.lastSync)}`:'eTL 일정을 확인하고 있어요'}</p>}</div><Button size="small" variant="outlined" disabled={!state.etlConnected} onClick={()=>command({action:'refreshAcademic'})}>eTL 새로고침</Button></header>
    {state.agenda.syncError&&<p className="home-sync-error" role="status">{state.agenda.syncError}</p>}
    {!state.etlConnected&&<div className="home-connect"><span>eTL을 연결하면 과목의 마감과 공지를 볼 수 있어요.</span><TextButton size="small" onClick={onSettings}>계정 연결</TextButton></div>}
    <div className="home-brief-action"><TextButton size="small" color="assistive" onClick={()=>{command({action:'makeBriefing'});setBrief(v=>!v);}}>{brief?'브리핑 접기':'오늘 브리핑'}</TextButton></div>{brief&&<pre className="home-brief-text">{state.school.briefText||'브리핑을 준비하고 있어요.'}</pre>}
    {notice&&<NoticeDialog item={notice} onClose={()=>setNotice(null)} onAsk={()=>{onAsk(notice.id);setNotice(null);}}/>}
    <div className="home-grid">
      <section className="home-panel" id="home-deadlines"><div className="home-panel-heading"><h2>다가오는 마감 <span className="home-count">{upcoming.length}</span></h2><TextButton size="small" color="assistive" onClick={()=>onAgenda({tab:'schedule'})}>전체 일정</TextButton></div>{upcoming.length?<div className="home-list">{upcoming.slice(0,8).map(i=>taskRow(i))}</div>:<p className="home-empty">앞으로 7일 안에 남은 마감이 없어요.</p>}{overdue.length>0&&<details className="home-overdue"><summary>기한이 지난 미완료 항목 {overdue.length}개</summary>{overdue.slice(0,10).map(i=>taskRow(i))}</details>}</section>
      <section className="home-panel home-exams"><div className="home-panel-heading"><h2>시험 일정</h2><span className="home-count">{exams.length}</span></div>{exams.length?<div className="home-list">{exams.slice(0,8).map(i=>taskRow(i))}</div>:<p className="home-empty">다가오는 시험 일정이 없어요.</p>}<div className="home-panel-footer"><TextButton size="small" color="assistive" onClick={()=>onAgenda({tab:'schedule',add:true})}>일정 추가</TextButton></div></section>
      <section className="home-panel" id="home-today"><div className="home-panel-heading"><h2>오늘 할 일 <span className="home-count">{todays.filter(i=>!i.done).length}</span></h2><TextButton size="small" onClick={()=>onAgenda({tab:'schedule',add:true})}>＋ 할 일 추가</TextButton></div>{todays.length?<div className="home-list">{todays.map(i=>taskRow(i,true))}</div>:<p className="home-empty">오늘 할 일을 추가해 보세요.</p>}</section>
      <section className="home-panel" id="home-announcements"><div className="home-panel-heading"><h2>새 공지 <span className="home-count">{announcements.length}</span></h2><TextButton size="small" color="assistive" onClick={()=>onAgenda({tab:'notices'})}>알림 기록</TextButton></div>{announcements.length?<div className="home-list">{announcements.slice(0,showAll?50:5).map(n=><button className="home-notice" key={n.id} onClick={()=>openNotice(n)}><span className="unread-dot"/><span><strong>{n.title}</strong><small>{n.course} · {dateText(n.at)}</small></span><span className="home-row-arrow" aria-hidden="true">↗</span></button>)}</div>:<p className="home-empty">최근 7일 동안 읽지 않은 새 공지가 없어요.</p>}{announcements.length>5&&<TextButton size="small" color="assistive" onClick={()=>setShowAll(v=>!v)}>{showAll?'접기':'더 보기'}</TextButton>}</section>
    </div>
      {running.length>0&&<section className="home-running-section"><div className="home-panel-heading"><h2>진행 중인 대화</h2><span className="home-count">{running.length}</span></div>{running.map(s=><button className="home-running" key={s.id} onClick={()=>onChat(s.id)}><i className="status-dot running"/><span><strong>{s.title}</strong><small>{state.projects.find(p=>p.id===s.projectId)?.name} · {s.pdfBusy?'PDF 만드는 중':s.status}</small></span><span aria-hidden="true">›</span></button>)}</section>}
  </section>;
}
