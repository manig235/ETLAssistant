import {useEffect,useRef,useState} from 'react';
import {Button,IconButton,Loading,TextButton} from '@montage-ui/core';
import {getDocument,GlobalWorkerOptions,TextLayer,type PDFDocumentProxy} from 'pdfjs-dist';
import workerURL from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import type {Artifact,PreviewData} from './types';
import {control} from './types';
GlobalWorkerOptions.workerSrc=workerURL;
export function Preview({files,active,onSelect,onClose}:{files:Artifact[];active:string;onSelect:(id:string)=>void;onClose:(id:string)=>void}) {
  const file=files.find(f=>f.id===active)||files[0];
  return <aside className="preview-panel" aria-label="파일 미리보기"><div className="preview-top"><strong>미리보기</strong><IconButton size="small" aria-label="미리보기 모두 닫기" onClick={()=>onClose('*')}>×</IconButton></div>
    <div className="preview-tabs" role="tablist" aria-label="열린 파일">{files.map(f=><div className={`preview-tab ${f.id===file.id?'active':''}`} key={f.id}><button role="tab" aria-selected={f.id===file.id} title={f.name} onClick={()=>onSelect(f.id)}>{f.name}</button><button aria-label={`${f.name} 미리보기 닫기`} onClick={()=>onClose(f.id)}>×</button></div>)}</div>
    <FilePreview key={file.id} file={file}/><div className="preview-footer"><TextButton color="assistive" onClick={()=>control('openArtifact',file.id)}>외부 앱으로 열기 ↗</TextButton><Button size="small" variant="outlined" onClick={()=>control('saveArtifact',file.id)}>다른 이름으로 저장</Button></div></aside>;
}
function FilePreview({file}:{file:Artifact}) {
  const [data,setData]=useState<PreviewData>();const [error,setError]=useState('');const [url,setUrl]=useState('');
  useEffect(()=>{let alive=true;let objectURL='';void window.youngmini.readArtifact(file.id).then(result=>{if(!alive)return;setData(result);if(result.kind==='image'){objectURL=URL.createObjectURL(new Blob([new Uint8Array(result.bytes!)],{type:result.mime}));setUrl(objectURL);}}).catch(()=>{if(alive)setError('미리보기를 열지 못했어요. 외부 앱으로 열어 주세요.');});return()=>{alive=false;if(objectURL)URL.revokeObjectURL(objectURL);};},[file.id]);
  return <div className="preview-content">{error?<div className="empty-small" role="alert">{error}</div>:!data?<div className="loading-line"><Loading size={20}/>미리보기 준비 중</div>:data.kind==='pdf'?<Pdf bytes={data.bytes!}/>:data.kind==='image'?<img className="preview-image" src={url} alt={file.name}/>:<pre className="document-preview">{data.text||'표시할 텍스트가 없어요.'}</pre>}</div>;
}
function Pdf({bytes}:{bytes:Uint8Array}) {
  const [pdf,setPdf]=useState<PDFDocumentProxy>();const [page,setPage]=useState(1);const [zoom,setZoom]=useState(1);const [error,setError]=useState('');
  const box=useRef<HTMLDivElement>(null),canvas=useRef<HTMLCanvasElement>(null),text=useRef<HTMLDivElement>(null);const [width,setWidth]=useState(480);
  useEffect(()=>{const observer=new ResizeObserver(entries=>setWidth(entries[0].contentRect.width-18));if(box.current)observer.observe(box.current);return()=>observer.disconnect();},[]);
  useEffect(()=>{let alive=true;const task=getDocument({data:new Uint8Array(bytes),enableXfa:false});void task.promise.then(doc=>{if(alive)setPdf(doc);}).catch(()=>{if(alive)setError('PDF를 읽지 못했어요. 외부 앱으로 열어 주세요.');});return()=>{alive=false;void task.destroy();};},[bytes]);
  useEffect(()=>{if(!pdf||!canvas.current||!text.current)return;let cancelled=false;let render:ReturnType<Awaited<ReturnType<PDFDocumentProxy['getPage']>>['render']>|undefined;let layer:TextLayer|undefined;
    void pdf.getPage(page).then(async p=>{if(cancelled)return;const base=p.getViewport({scale:1});const scale=Math.min(3,Math.max(.2,(width-32)/base.width))*zoom;const viewport=p.getViewport({scale});const el=canvas.current!;const dpr=Math.min(devicePixelRatio,2);el.width=Math.ceil(viewport.width*dpr);el.height=Math.ceil(viewport.height*dpr);el.style.width=viewport.width+'px';el.style.height=viewport.height+'px';
      render=p.render({canvas:el,viewport,transform:dpr===1?undefined:[dpr,0,0,dpr,0,0]});await render.promise;if(cancelled)return;const container=text.current!;container.replaceChildren();container.style.setProperty('--scale-factor',String(scale));layer=new TextLayer({textContentSource:await p.getTextContent(),container,viewport});if(!cancelled)await layer.render();
    }).catch(e=>{if(!cancelled&&e.name!=='RenderingCancelledException')setError('이 쪽을 표시하지 못했어요.');});return()=>{cancelled=true;render?.cancel();layer?.cancel();text.current?.replaceChildren();};
  },[pdf,page,width,zoom]);
  return <div className="pdf-viewer" ref={box}><div className="pdf-controls"><IconButton size="small" aria-label="이전 쪽" disabled={page<=1} onClick={()=>setPage(p=>p-1)}>‹</IconButton><span>{page} / {pdf?.numPages||'…'}</span><IconButton size="small" aria-label="다음 쪽" disabled={!pdf||page>=pdf.numPages} onClick={()=>setPage(p=>p+1)}>›</IconButton><TextButton disabled={zoom<=.75} onClick={()=>setZoom(z=>z-.25)}>−</TextButton><span>{Math.round(zoom*100)}%</span><TextButton disabled={zoom>=2} onClick={()=>setZoom(z=>z+.25)}>＋</TextButton></div>{error&&<p role="alert">{error}</p>}<div className="pdf-scroll"><div className="pdf-page"><canvas ref={canvas} aria-label={`PDF ${page}쪽`}/><div ref={text} className="textLayer"/></div></div></div>;
}
