import {useEffect,useRef,useState,type ClipboardEvent,type DragEvent} from 'react';
import {IconButton} from '@montage-ui/core';
import type {Artifact,Session} from './types';
import {command} from './types';

export const isImage=(file:Artifact)=>/\.(png|jpe?g|webp|gif)$/i.test(file.name);
export function ImageThumbnail({file}:{file:Artifact}) {
  const [url,setUrl]=useState('');const [failed,setFailed]=useState(false);
  useEffect(()=>{let alive=true;let objectURL='';
    void window.youngmini.readArtifact(file.id).then(data=>{
      if(!alive)return;
      if(data.kind!=='image'||!data.bytes){setFailed(true);return;}
      objectURL=URL.createObjectURL(new Blob([new Uint8Array(data.bytes)],{type:data.mime}));setUrl(objectURL);
    }).catch(()=>{if(alive)setFailed(true);});
    return()=>{alive=false;if(objectURL)URL.revokeObjectURL(objectURL);};
  },[file.id]);
  return url?<img src={url} alt={file.name} loading="lazy"/>:<span className="image-placeholder">{failed?'사진을 열 수 없어요':'사진 준비 중'}</span>;
}

export function useImageInput(session:Session) {
  const [importing,setImporting]=useState(false);const [error,setError]=useState('');
  const locked=useRef(false),alive=useRef(true);
  const input=useRef<HTMLInputElement>(null);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  async function add(files:File[]) {
    if(!files.length||locked.current||session.phase==='running')return;
    setError('');
    if(files.some(f=>!(/\.(png|jpe?g|webp|gif|pdf|docx|xlsx|pptx|hwp|hwpx|txt|md|csv|tsv|json|tex|ics)$/i.test(f.name)))){setError('사진, PDF, Office, HWP 또는 텍스트 문서를 첨부해 주세요.');return;}
    if(files.length+session.draftImages.length>5||files.some(f=>f.size>20*1024*1024)||files.reduce((n,f)=>n+f.size,0)+session.draftImages.reduce((n,f)=>n+f.bytes,0)>50*1024*1024){setError('파일은 5개, 파일당 20MB, 합계 50MB까지 첨부할 수 있어요.');return;}
    locked.current=true;setImporting(true);
    try {await (window.youngmini.importFiles||window.youngmini.importImages)(session.id,await Promise.all(files.map(async f=>({name:f.name,bytes:new Uint8Array(await f.arrayBuffer())}))));}
    catch {if(alive.current)setError('파일을 첨부하지 못했어요. 파일 형식과 크기를 확인해 주세요.');}
    finally {locked.current=false;if(alive.current)setImporting(false);}
  }
  function paste(e:ClipboardEvent) {
    const images=Array.from(e.clipboardData.items).filter(i=>i.kind==='file'&&i.type.startsWith('image/')).map(i=>i.getAsFile()).filter((f):f is File=>!!f);
    if(images.length){e.preventDefault();void add(images);}
  }
  function drop(e:DragEvent) {if(e.dataTransfer.types.includes('Files')){e.preventDefault();void add(Array.from(e.dataTransfer.files));}}
  const picker=<input ref={input} className="image-file-input" type="file" aria-label="사진 파일 선택" accept="image/png,image/jpeg,image/webp,image/gif,.pdf,.docx,.xlsx,.pptx,.hwp,.hwpx,.txt,.md,.csv,.tsv,.json,.tex,.ics" multiple disabled={importing||session.phase==='running'} onChange={e=>{void add(Array.from(e.target.files||[]));e.target.value='';}}/>;
  return {importing,error,paste,drop,picker,open:()=>input.current?.click()};
}

export function DraftImages({session,onPreview}:{session:Session;onPreview:(file:Artifact)=>void}) {
  return !!session.draftImages.length&&<div className="draft-images" aria-label="보낼 사진">{session.draftImages.map(file=><div className="draft-image" key={file.id}><button aria-label={`${file.name} 미리보기`} onClick={()=>onPreview(file)}>{isImage(file)?<ImageThumbnail file={file}/>:<span className="document-draft">{file.name}</span>}</button><IconButton size="small" aria-label={`${file.name} 첨부 취소`} disabled={session.phase==='running'} onClick={()=>command({action:'removeImage',chatId:session.id,imageId:file.id})}>×</IconButton></div>)}</div>;
}
