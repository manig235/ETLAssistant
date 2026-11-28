const fs=require('node:fs');
const DEFAULT_NAME='영민이';
function displayName(value) {
  if(typeof value!=='string'||/[\u0000-\u001f\u007f]/u.test(value))return null;
  const name=value.trim();
  return name.length>0&&name.length<=24?name:null;
}
function readAppearance(file) {
  try {const value=JSON.parse(fs.readFileSync(file,'utf8'));return {pet:value.pet!==false,displayName:displayName(value.displayName)||DEFAULT_NAME,petMotion:petMotion(value.petMotion)||'system'};}
  catch {return {pet:true,displayName:DEFAULT_NAME,petMotion:'system'};}
}
function petMotion(value) {return ['system','on','off'].includes(value)?value:null;}
function saveAppearance(file,value) {
  const temp=file+'.tmp';fs.writeFileSync(temp,JSON.stringify(value));fs.renameSync(temp,file);
}
module.exports={DEFAULT_NAME,displayName,petMotion,readAppearance,saveAppearance};
