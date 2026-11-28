import {useEffect,useRef,useState} from 'react';
import type {Session} from './types';

export type PetMotion = 'idle'|'working'|'done'|'failed';
export type MotionPolicy = 'system'|'on'|'off';
export function useReducedMotion() {
  const [reduced,setReduced]=useState(()=>matchMedia('(prefers-reduced-motion:reduce)').matches);
  useEffect(()=>{const media=matchMedia('(prefers-reduced-motion:reduce)');const change=()=>setReduced(media.matches);media.addEventListener('change',change);change();return()=>media.removeEventListener('change',change);},[]);
  return reduced;
}
const succeeded=(s:Session)=>s.phase==='done'&&!s.error&&s.status!=='답변을 멈췄어요';

export function usePetMotion(sessions:Session[]) {
  const previous=useRef(new Map<string,Session>());
  const pdfStart=useRef(new Map<string,Set<string>>());
  const pending=useRef<string|null>(null);
  const timer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);
  const sequence=useRef(0);
  const [celebration,setCelebration]=useState<string|null>(null);
  const busy=sessions.some(s=>s.phase==='running'||s.pdfBusy);
  const failed=sessions.some(s=>s.phase==='failed'&&s.unread);
  const done=sessions.some(s=>s.unread&&succeeded(s));

  useEffect(()=>{
    for(const session of sessions) {
      const old=previous.current.get(session.id);
      if(session.pdfBusy&&!old?.pdfBusy)pdfStart.current.set(session.id,new Set(session.artifacts.map(a=>a.id)));
      // Celebrate new results only; restored history and repeated IPC snapshots stay quiet.
      if(old&&((old.phase==='running'&&succeeded(session))||
        (old.pdfBusy&&!session.pdfBusy&&!session.error&&session.artifacts.some(a=>a.kind==='generated'&&!pdfStart.current.get(session.id)?.has(a.id))))) {
        pending.current=`${session.id}:${++sequence.current}`;
      }
      if(!session.pdfBusy)pdfStart.current.delete(session.id);
    }
    previous.current=new Map(sessions.map(s=>[s.id,s]));
    if(busy) {
      clearTimeout(timer.current);
      setCelebration(null);
    } else if(pending.current) {
      setCelebration(pending.current);pending.current=null;
      clearTimeout(timer.current);
      timer.current=setTimeout(()=>setCelebration(null),1500);
    }
  },[sessions,busy]);
  useEffect(()=>()=>clearTimeout(timer.current),[]);
  const motion:PetMotion=busy?'working':failed?'failed':done||celebration?'done':'idle';
  return {motion,celebration:motion==='done'?celebration:null};
}
