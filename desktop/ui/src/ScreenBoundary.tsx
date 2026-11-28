import {Component,type ReactNode} from 'react';

/** Keep a usable recovery screen if rendering fails instead of leaving an empty window. */
export class ScreenBoundary extends Component<{children:ReactNode},{failed:boolean}> {
  state={failed:false};
  static getDerivedStateFromError(){return {failed:true};}
  render(){
    if(!this.state.failed)return this.props.children;
    return <main className="screen-recovery" role="alert"><h1>화면을 표시하지 못했어요</h1><p>화면을 다시 불러와 주세요. 저장된 대화와 파일은 그대로 유지돼요.</p><div><button onClick={()=>location.reload()}>다시 불러오기</button><button onClick={()=>{void window.youngmini?.control('quit');}}>영민이 종료</button></div></main>;
  }
}
