import {useEffect,useState} from 'react';
import {Markdown} from './Markdown';
export function Print({id}:{id:string}) {
  const [doc,setDoc]=useState<{name:string;markdown:string}>();
  useEffect(()=>{void window.youngmini.getPrint(id).then(setDoc);},[id]);
  useEffect(()=>{if(!doc)return;document.title=doc.name;document.body.classList.add('print-mode');void (async()=>{await document.fonts.load('400 14px "Pretendard Variable"');await document.fonts.ready;document.querySelector('.print-document')?.getBoundingClientRect();await window.youngmini.printReady(id);})();},[doc,id]);
  return <main className="print-document">{doc&&<Markdown text={doc.markdown}/>}</main>;
}
