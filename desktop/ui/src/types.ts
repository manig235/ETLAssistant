import startupState from '../electron/initial-state.json' with {type:'json'};
export type Model = {model:string;displayName:string;isDefault:boolean;defaultEffort:string|null;efforts:{effort:string;description:string}[]};
export type Artifact = {id:string;name:string;path:string;bytes:number;kind:string;messageId:string|null};
export type Session = {id:string;projectId:string;title:string;draft:string;model:string|null;effort:string|null;phase:string;status:string;error:string|null;unread:boolean;updatedAt:number;artifacts:Artifact[];draftImages:Artifact[];pdfBusy:boolean;messages:{id:string;role:string;text:string}[]};
export type Project = {id:string;name:string;kind:string;courseIds:number[];instructions:string};
export type Personal = {id?:string;title:string;due:number;kind:string;notes:string;reminders:number[];done:boolean;seriesDue?:number;occurrence?:string|null;repeatLabel?:string;repeat?:{freq:string;weekdays:number[];until?:string|null;count?:number|null}|null;end?:number|null;location?:string;course?:string;supplies?:string};
export type Academic = {id:string;title:string;due:number|null;kind:string;course:string;done:boolean;score:number|null;url:string};
export type Notice = {id:string;kind:string;title:string;body:string;at:number;chatId:string|null;url:string|null;read:boolean;delivered:boolean};
export type Announcement={courseId?:number;body?:string;category?:string;id:string;title:string;course:string;at:number;url:string;read:boolean};
export type AgendaTarget={tab?:'schedule'|'notices';add?:boolean;itemId?:string;occurrence?:string|null};
export type Preferences = {onboarded:boolean;deadlines:boolean;announcements:boolean;grades:boolean;personal:boolean;completion:boolean};
export type Agenda = {personal:Personal[];personalSeries?:Personal[];academic:Academic[];announcements?:Announcement[];notices:Notice[];settings:Preferences;lastSync?:number;syncError?:string};
export type SchoolCourse={course_id:number;name:string;professor:string|null;credits:number|null;meetings:{day:string;start:string;end:string;room:string|null}[]};
export type ScoreRow={id:string;ids?:string[];course:string;course_id:number|null;assignment_id:number|null;title:string;source:string;stats:Record<string,number>;my_score:number|null;points_possible:number|null;position:string|null};
export type ClassChange={id:number;date:string;kind:string;course:string;course_id?:number;start?:string;end?:string;room?:string;note?:string};
export type ClassRow={start:string;end:string;course:string;course_id?:number;room?:string;kind:string};
export type SchoolState={settings:{updates:boolean;theme:string;casual:boolean;study:boolean;quiet:boolean;quietStart:number;quietEnd:number;brief:boolean;briefTimes:number[];classAlerts:boolean;classLead:number;newFiles:boolean;changes:boolean;submissions:boolean;submitOk:boolean;examDone:boolean;hideContent:boolean;retentionDays:number;deadlineWindows:number[];hideTimetable:number[]};briefText:string;briefAt:number;lastCleanup:{count:number;bytes:number;at:number}|null;courseModes:Record<string,string>;stats:ScoreRow[];timetable:{courses:SchoolCourse[];errors:string[];fetchedAt:number};changes:ClassChange[];todayClasses:ClassRow[];cancelledClasses:ClassRow[]};
export type State = {approvals:{id:string;chatId:string;title:string;preview:string;expiresAt:number}[];school:SchoolState;schoolBusy?:boolean;starting:boolean;ready:boolean;status:string;error:string|null;loggedIn:boolean;loginBusy:boolean;loginUrl:string|null;loginCode:string|null;etlConnected:boolean;etlBusy:boolean;courses:{id:number;name:string;term:string}[];projects:Project[];activeProjectId:string;activeChatId:string;visibleChatIds:string[];models:Model[];sessions:Session[];agenda:Agenda};
export type Command={action:string;[key:string]:unknown};
export type UpdateInfo={version:string;url:string;releaseUrl:string;text:string;notes:string;bytes:number};
export type ShellState={update?:{available:UpdateInfo|null;checking:boolean;error:string|null;checkedAt:number;remindAt:number};openUpdate?:number;pet:boolean;petMotion?:'system'|'on'|'off';displayName?:string;error?:string|null;openAgenda?:number;openChat?:number;etlLoginBusy?:boolean;etlLoginError?:string|null};
export type PreviewData={kind:'pdf'|'image'|'text';bytes?:Uint8Array;mime?:string;text?:string};
export type Bridge={getState():Promise<{state:State;shell:ShellState}>;command(command:Command):Promise<void>;control(action:string,value?:unknown):Promise<void>;importFiles?(chatId:string,files:{name:string;bytes:Uint8Array}[]):Promise<void>;importImages(chatId:string,images:{name:string;bytes:Uint8Array}[]):Promise<void>;readArtifact(id:string):Promise<PreviewData>;getPrint(id:string):Promise<{name:string;markdown:string}>;printReady(id:string):Promise<void>;onState(callback:(state:State)=>void):()=>void;onShell(callback:(shell:ShellState)=>void):()=>void};
declare global {interface Window {youngmini:Bridge}}
export const initialState:State=startupState;
export const command=(value:Command)=>{void window.youngmini.command(value);};
export const control=(action:string,value?:unknown)=>{void window.youngmini.control(action,value);};

/** IPC can arrive before the backend's first snapshot, or from an older local process. */
export function normalizeState(value:Partial<State>):State {
  return {...initialState,...value,school:{...initialState.school,...value.school,settings:{...initialState.school.settings,...value.school?.settings},timetable:{...initialState.school.timetable,...value.school?.timetable}},approvals:value.approvals??[],
    projects:value.projects??initialState.projects,
    sessions:(value.sessions??[]).map(s=>({...s,projectId:s.projectId??'integrated',artifacts:s.artifacts??[],draftImages:s.draftImages??[],pdfBusy:s.pdfBusy??false})),
    agenda:{...initialState.agenda,...value.agenda,settings:{...initialState.agenda.settings,...value.agenda?.settings}},
  };
}
