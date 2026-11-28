import {useState} from 'react';
import {TextButton,Button,Checkbox} from '@montage-ui/core';
import {Dialog} from './Dialogs';
import {command,type State} from './types';
import {version} from '../package.json' with {type:'json'};
export function About({state}:{state:State}){
  const [privacy,setPrivacy]=useState(false),[checking,setChecking]=useState(false),[result,setResult]=useState<{text:string;url?:string}|null>(null);
  return <section><h3>앱 정보</h3><p className="muted">Young Mini · Windows {version}</p><label className="check-label"><Checkbox aria-label="새 Windows 버전 알림" checked={state.school.settings.updates} onCheckedChange={updates=>command({action:'schoolSettings',settings:{updates}})}/>새 Windows 버전 알림</label><p className="muted">실행할 때와 6시간마다 GitHub를 확인해요.</p><div className="button-row"><TextButton size="small" onClick={()=>setPrivacy(true)}>개인정보처리방침</TextButton><TextButton size="small" disabled={checking} onClick={async()=>{setChecking(true);try{setResult(await window.youngmini.control('checkUpdate') as unknown as {text:string;url?:string});}catch{setResult({text:'업데이트를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.'});}finally{setChecking(false);}}}>{checking?'확인 중…':'Windows 업데이트 확인'}</TextButton></div>{result&&<p className="muted" role="status">{result.text}{result.url&&<TextButton size="small" onClick={()=>void window.youngmini.control('openLink',result.url)}>새 버전 받기 ↗</TextButton>}</p>}
    <Dialog open={privacy} title="개인정보처리방침" onClose={()=>setPrivacy(false)} footer={<Button onClick={()=>setPrivacy(false)}>완료</Button>}><div className="form-stack"><p>로그인 정보는 이 Windows 계정의 DPAPI로 암호화해 이 PC에 저장해요. 대화·첨부·받은 파일·일정도 이 PC에 보관해요.</p><p>질문, 직접 첨부한 사진과 비서가 읽은 자료는 본인 ChatGPT 계정을 통해 OpenAI에 전송돼요. 앱 운영 서버는 없고 PC와 휴대폰은 자동 동기화되지 않아요.</p><p>공개 학습자료 검색은 MIT OCW에 검색어와 자료 주소를 보내요. eTL 토큰은 eTL에만 보내며 공개 학습자료·수강편람·GitHub에는 보내지 않아요. 업데이트 확인은 GitHub의 공개 Windows 배포 목록만 확인해요.</p><p>문서 렌더링은 설치된 Microsoft Office로 이 PC에서 처리해요. 원본을 보존하고 외부 연결·매크로를 제거한 임시 사본을 사용해요. 앱에서 저장한 파일 사본과 캘린더 파일은 사용자가 선택한 위치에 남아요.</p><p>계정 연결은 설정에서 해제하고 대화는 세션의 휴지통으로 삭제할 수 있어요. 파일 자동 정리는 기본적으로 꺼져 있으며 선택한 기간이 지나고 대화 참조도 없는 앱 내부 파일에만 적용돼요.</p></div></Dialog>
  </section>;
}
