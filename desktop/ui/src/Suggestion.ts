import type {Project,State} from './types';

export function suggestionPool(project:Project|undefined,courses:State['courses']):string[] {
  if(project?.kind==='custom'&&!project.courseIds.length)return [
    '어려운 개념을 예시를 들어 설명해줘',
    '이해한 내용을 확인할 퀴즈를 만들어줘',
    '이번 주에 할 공부를 같이 계획해줘',
    '붙여 넣은 글의 핵심만 요약해줘',
    '시험 공부할 내용을 항목별로 정리해줘',
    '공부한 내용을 복습할 문제를 만들어줘',
    '시험 대비 개념 정리를 PDF로 만들어줘',
  ];
  const scoped=project?.kind==='integrated'?courses:courses.filter(c=>project?.courseIds.includes(c.id));
  const name=scoped.length?scoped[Math.floor(Math.random()*scoped.length)].name:'';
  const course=name?`${name} `:'';
  return [
    `${course}이번 주 마감 과제 알려줘`,
    `${course}새 공지 중 중요한 내용만 정리해줘`,
    `${course}최근 강의자료 핵심 내용 요약해줘`,
    `${course}최근 강의자료 파일 받아줘`,
    `${course}최근 과제가 제대로 제출됐는지 확인해줘`,
    `${course}기말고사 대비 문제를 PDF로 만들어줘`,
    '이번 주에 할 공부를 같이 계획해줘',
  ];
}

// Keep one choice for each session, including when returning from another project.
const choices=new Map<string,{index:number;courseId?:number}>();
export function sessionSuggestion(id:string,project:Project|undefined,courses:State['courses']) {
  const scoped=project?.kind==='integrated'?courses:courses.filter(c=>project?.courseIds.includes(c.id));
  let choice=choices.get(id);
  if(!choice){choice={index:Math.floor(Math.random()*7),courseId:scoped.length?scoped[Math.floor(Math.random()*scoped.length)].id:undefined};choices.set(id,choice);}
  if(!scoped.some(c=>c.id===choice.courseId)&&scoped.length)choice.courseId=scoped[Math.floor(Math.random()*scoped.length)].id;
  const selected=scoped.find(c=>c.id===choice.courseId);
  return suggestionPool(project,selected?[selected]:[])[choice.index];
}
