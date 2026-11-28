import startupState from '../electron/initial-state.json' with {type:'json'};
export type Model = {model:string;displayName:string;isDefault:boolean;defaultEffort:string|null;efforts:{effort:string;description:string}[]};
export type Artifact = {id:string;name:string;path:string;bytes:number;kind:string;messageId:string|null};
export type Session = {id:string;projectId:string;title:string;draft:string;model:string|null;effort:string|null;phase:string;status:string;error:string|null;unread:boolean;updatedAt:number;artifacts:Artifact[];pdfBusy:boolean;messages:{id:string;role:string;text:string}[]};
export type Project = {id:string;name:string;kind:string;courseIds:number[];instructions:string};
export type Personal = {id?:string;title:string;due:number;kind:string;notes:string;reminders:number[];done:boolean};
export type Academic = {id:string;title:string;due:number|null;kind:string;course:string;done:boolean;score:number|null;url:string};
export type Notice = {id:string;kind:string;title:string;body:string;at:number;chatId:string|null;url:string|null;read:boolean;delivered:boolean};
export type Preferences = {onboarded:boolean;deadlines:boolean;announcements:boolean;grades:boolean;personal:boolean;completion:boolean};
export type Agenda = {personal:Personal[];academic:Academic[];notices:Notice[];settings:Preferences;lastSync?:number;syncError?:string};
export type State = {starting:boolean;ready:boolean;status:string;error:string|null;loggedIn:boolean;loginBusy:boolean;loginUrl:string|null;loginCode:string|null;etlConnected:boolean;etlBusy:boolean;courses:{id:number;name:string;term:string}[];projects:Project[];activeProjectId:string;activeChatId:string;visibleChatIds:string[];models:Model[];sessions:Session[];agenda:Agenda};
export type Command={action:string;[key:string]:unknown};
export type ShellState={pet:boolean;error?:string|null;openAgenda?:number};
export type PreviewData={kind:'pdf'|'image'|'text';bytes?:Uint8Array;mime?:string;text?:string};
export type Bridge={getState():Promise<{state:State;shell:ShellState}>;command(command:Command):Promise<void>;control(action:string,value?:unknown):Promise<void>;readArtifact(id:string):Promise<PreviewData>;getPrint(id:string):Promise<{name:string;markdown:string}>;printReady(id:string):Promise<void>;onState(callback:(state:State)=>void):()=>void;onShell(callback:(shell:ShellState)=>void):()=>void};
declare global {interface Window {youngmini:Bridge}}
export const initialState:State=startupState;
export const command=(value:Command)=>{void window.youngmini.command(value);};
export const control=(action:string,value?:unknown)=>{void window.youngmini.control(action,value);};

/** IPC can arrive before the backend's first snapshot, or from an older local process. */
export function normalizeState(value:Partial<State>):State {
  return {...initialState,...value,
    projects:value.projects??initialState.projects,
    sessions:(value.sessions??[]).map(s=>({...s,projectId:s.projectId??'integrated',artifacts:s.artifacts??[],pdfBusy:s.pdfBusy??false})),
    agenda:{...initialState.agenda,...value.agenda,settings:{...initialState.agenda.settings,...value.agenda?.settings}},
  };
}
