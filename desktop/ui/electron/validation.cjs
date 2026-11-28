const path=require('node:path');
const actions=new Set(['start','login','cancelLogin','logout','connectEtl','disconnectEtl','loadCourses','newChat','openChat','focus','setDraft','setChoice','send','interrupt','acknowledge','dismissError','openProject','saveProject','createPdf','savePersonal','deletePersonal','completePersonal','settings','noticeAck','readAll','refreshAcademic','testNotice','removeImage','readAnnouncement','renameChat','deleteChat','askAnnouncement','exportCalendar','cleanupFiles','makeBriefing','schoolSettings','courseMode','refreshTimetable','saveClass','deleteClass','saveTimetableChange','deleteTimetableChange','saveScore','deleteScore','approveWrite','cancelApprovals']);
function validCommand(command) {
  if (!command || typeof command!=='object' || Array.isArray(command) || !actions.has(command.action)) return false;
  if (JSON.stringify(command).length>100000) return false;
  if(['renameChat','deleteChat'].includes(command.action)&&!(/^[a-f0-9-]{36}$/.test(command.chatId||'')))return false;
  if(command.action==='renameChat'&&(typeof command.title!=='string'||!command.title.trim()||command.title.length>40||/[\x00-\x1f\x7f]/.test(command.title)))return false;
  if ('chatId' in command && (typeof command.chatId!=='string' || !/^[a-f0-9-]{36}$/.test(command.chatId))) return false;
  if ('text' in command && (typeof command.text!=='string' || command.text.length>32000)) return false;
  if(command.action==='readAnnouncement'&&(typeof command.id!=='string'||!/^\d+:\d+$/.test(command.id)))return false;
  if ('imageId' in command && (typeof command.imageId!=='string' || !/^[a-f0-9-]{36}$/.test(command.imageId))) return false;
  if ('token' in command && (typeof command.token!=='string' || command.token.length>10000)) return false;
  if ('model' in command && command.model!==null && (typeof command.model!=='string' || command.model.length>200)) return false;
  if ('effort' in command && command.effort!==null && (typeof command.effort!=='string' || command.effort.length>40)) return false;
  if ('instructions' in command && (typeof command.instructions!=='string' || command.instructions.length>8000)) return false;
  if ('courseIds' in command && (!Array.isArray(command.courseIds) || command.courseIds.some(id=>!Number.isSafeInteger(id)||id<=0))) return false;
  if ('count' in command && (!Number.isSafeInteger(command.count) || command.count<1)) return false;
  for(const key of ['fileId','courseId']) if(key in command && command[key]!==null && (!Number.isSafeInteger(command[key]) || command[key]<=0)) return false;
  return true;
}
function isExternalLink(value,authOnly=false) {
  if(typeof value!=='string' || value.length>16000) return false;
  try {const url=new URL(value); return url.protocol==='https:' && !url.username && !url.password && (!authOnly || url.hostname==='auth.openai.com');} catch{return false;}
}
function isInside(root,target) {const relative=path.relative(root,target);return relative==='' || (!relative.startsWith('..'+path.sep) && relative!=='..' && !path.isAbsolute(relative));}
module.exports={validCommand,isExternalLink,isInside};
