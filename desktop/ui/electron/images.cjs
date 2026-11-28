const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {isInside}=require('./validation.cjs');
const MB=1024*1024;
async function importImages(images,workspace,decode) {
  if(!Array.isArray(images)||images.length<1||images.length>5)throw Error('사진은 1~5장 첨부해 주세요.');
  let total=0;
  for(const image of images) {
    if(!image||typeof image.name!=='string'||!(image.bytes instanceof Uint8Array)||image.bytes.length<1||image.bytes.length>20*MB)throw Error('사진 크기를 확인해 주세요.');
    total+=image.bytes.length;
  }
  if(total>50*MB)throw Error('사진 합계는 50MB까지 첨부할 수 있어요.');
  const normalized=images.map(image=>{
    const decoded=decode(Buffer.from(image.bytes));
    const size=decoded.getSize();
    if(decoded.isEmpty()||size.width<1||size.height<1||size.width*size.height>20_000_000)throw Error('사진은 2천만 화소 이하로 첨부해 주세요.');
    const png=decoded.toPNG();
    if(!png.length||png.length>20*MB)throw Error('변환한 사진이 20MB를 넘어요.');
    const id=crypto.randomUUID();
    const base=path.basename(image.name).replace(/[\x00-\x1f\x7f]/g,'').replace(/\.[^.]+$/,'').slice(0,100)||'사진';
    return {image:png,artifact:{id,name:base+'.png',path:`uploads/${id}.png`,bytes:png.length,kind:'attachment',messageId:null}};
  });
  if(normalized.reduce((n,i)=>n+i.image.length,0)>50*MB)throw Error('변환한 사진 합계가 50MB를 넘어요.');
  await fs.promises.mkdir(path.join(workspace,'uploads'),{recursive:true});
  const root=await fs.promises.realpath(workspace),uploads=await fs.promises.realpath(path.join(workspace,'uploads'));
  if(!isInside(root,uploads))throw Error('사진 폴더를 열지 못했어요.');
  const written=[];
  try {
    for(const image of normalized) {
      const file=path.join(uploads,image.artifact.id+'.png');
      await fs.promises.writeFile(file,image.image,{flag:'wx'});written.push(file);
    }
    return normalized.map(i=>i.artifact);
  } catch(error) {await Promise.all(written.map(file=>fs.promises.unlink(file).catch(()=>{})));throw error;}
}
async function importFiles(files,workspace,decode) {
  if(!Array.isArray(files)||files.length<1||files.length>5)throw Error('파일은 1~5개 첨부해 주세요.');
  if(files.some(f=>!f||typeof f.name!=='string'||!(f.bytes instanceof Uint8Array)||f.bytes.length<1||f.bytes.length>20*MB)||files.reduce((n,f)=>n+f.bytes.length,0)>50*MB)throw Error('파일당 20MB, 합계 50MB까지 첨부할 수 있어요.');
  const docs=[];const images=[];
  for(const f of files){const ext=path.extname(f.name).toLowerCase();if(['.png','.jpg','.jpeg','.webp','.gif'].includes(ext)){images.push(f);continue;}
    if(!['.pdf','.docx','.xlsx','.pptx','.hwp','.hwpx','.txt','.md','.csv','.tsv','.json','.tex','.ics'].includes(ext))throw Error('지원하는 문서나 사진을 선택해 주세요.');
    const bytes=Buffer.from(f.bytes);
    if(ext==='.pdf'&&bytes.subarray(0,5).toString()!=='%PDF-')throw Error('PDF 파일 형식을 확인해 주세요.');
    if(['.docx','.xlsx','.pptx','.hwpx'].includes(ext)&&bytes.readUInt16LE(0)!==0x4b50)throw Error('문서 파일 형식을 확인해 주세요.');
    if(['.txt','.md','.csv','.tsv','.json','.tex','.ics'].includes(ext)){try{new TextDecoder('utf-8',{fatal:true}).decode(bytes);}catch{throw Error('텍스트 파일은 UTF-8로 저장해 주세요.');}if(bytes.includes(0))throw Error('텍스트 파일 형식을 확인해 주세요.');}
    const id=crypto.randomUUID(),name=path.basename(f.name).replace(/[\x00-\x1f\x7f]/g,'').slice(0,140);
    docs.push({bytes,artifact:{id,name,path:`uploads/${id}${ext}`,bytes:bytes.length,kind:'document-attachment',messageId:null}});
  }
  const folder=path.join(workspace,'uploads');await fs.promises.mkdir(folder,{recursive:true});const root=await fs.promises.realpath(workspace),dir=await fs.promises.realpath(folder);if(!isInside(root,dir))throw Error('첨부 폴더를 열지 못했어요.');
  const written=[];let added=[];
  try{if(images.length)added=await importImages(images,workspace,decode);for(const d of docs){const target=path.join(dir,path.basename(d.artifact.path));await fs.promises.writeFile(target,d.bytes,{flag:'wx'});written.push(target);}if(added.reduce((n,a)=>n+a.bytes,0)+docs.reduce((n,d)=>n+d.bytes.length,0)>50*MB)throw Error('변환한 파일 합계가 50MB를 넘어요.');return [...added,...docs.map(d=>d.artifact)];}
  catch(e){await Promise.all([...written,...added.map(a=>path.join(dir,path.basename(a.path)))].map(p=>fs.promises.unlink(p).catch(()=>{})));throw e;}
}
module.exports={importImages,importFiles};
