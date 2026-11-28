import {useEffect,useRef,useState} from 'react';
import {Button,TextButton} from '@montage-ui/core';
import {Dialog} from './Dialogs';
import {control,type ShellState} from './types';
export function UpdateNotice({shell,enabled}:{shell:ShellState;enabled:boolean}){
  const [open,setOpen]=useState(false),[now,setNow]=useState(Date.now),[error,setError]=useState('');const pulse=useRef(0);const available=shell.update?.available;
  useEffect(()=>{if(shell.openUpdate&&shell.openUpdate!==pulse.current){pulse.current=shell.openUpdate;if(available){setError('');setOpen(true);}}},[shell.openUpdate,available]);
  useEffect(()=>{if(!available)return;const timer=setInterval(()=>setNow(Date.now()),15000);return()=>clearInterval(timer);},[available?.version]);
  if(!available)return null;
  function later(){control('deferUpdate',available!.version);setOpen(false);}
  return <>
    {enabled&&now>=(shell.update?.remindAt||0)&&<aside className="update-banner" aria-label="Windows 업데이트 알림"><span>{available.text}</span><TextButton size="small" onClick={()=>{setError('');setOpen(true);}}>업데이트 보기</TextButton><TextButton size="small" color="assistive" onClick={later}>나중에</TextButton></aside>}
    <Dialog open={open} title={`Windows ${available.version} 업데이트`} onClose={()=>setOpen(false)} footer={<><TextButton color="assistive" onClick={later}>나중에 알림</TextButton><Button onClick={async()=>{try{await window.youngmini.control('downloadUpdate',available.version);}catch{setError('다운로드 페이지를 열지 못했어요. 잠시 뒤 다시 시도해 주세요.');}}}>GitHub에서 받기 ↗</Button></>}>
      <p className="muted">Windows x64 ZIP{available.bytes>0?` · ${(available.bytes/1024/1024).toFixed(1)} MB`:''}</p>{available.notes&&<div className="update-notes">{available.notes}</div>}<p className="update-instructions">ZIP을 모두 푼 뒤, 실행 중인 영민이를 종료하고 새 YoungMini.exe를 실행해 주세요. 이 PC의 대화와 설정은 유지돼요.</p>{error&&<p role="alert">{error}</p>}
    </Dialog>
  </>;
}
