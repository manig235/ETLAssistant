import './pet-motion.css';
import type {PetMotion} from './PetMotion';

export function Robot({busy = false, failed = false,motion,celebration}: {busy?: boolean; failed?: boolean;motion?:PetMotion;celebration?:string|null}) {
  if(motion)return <svg className={`robot robot-animated motion-${motion}`} viewBox="0 0 108 108" aria-hidden="true">
    <g key={celebration||'rest'} className={`robot-celebration ${celebration?'is-celebrating':''}`}>
      <g className="robot-pose" fill={motion==='failed'?'#B18FA7':'#659CD2'}>
        <path className="robot-leg robot-leg-left" d="M35 82h15v8c0 4-2 6-6 6h-2c-4 0-7-2-7-6Z"/>
        <path className="robot-leg robot-leg-right" d="M58 82h15v8c0 4-3 6-7 6h-2c-4 0-6-2-6-6Z"/>
        <path className="robot-arm robot-arm-left" d="M25 53C20 57 14 63 12 68c-2 5 1 9 5 9s6-2 9-4l6-12Z"/>
        <path className="robot-arm robot-arm-right" d="M83 53c5 4 11 10 13 15 2 5-1 9-5 9s-6-2-9-4l-6-12Z"/>
        <path d="M24 47h60v19c0 14-9 23-20 23H44c-11 0-20-9-20-23Z"/>
        <g className="robot-head">
          <path d="M24 54v-5c0-21 12-35 30-35s30 14 30 35v5Z"/>
          <rect x="31" y="32" width="46" height="26" rx="13" fill="#F8F7F3"/>
          <g className="robot-blink">
            {motion==='done'?<g fill="none" stroke="#182C4A" strokeWidth="3.5" strokeLinecap="round"><path d="M40.5 46q3.5-6 7 0m13 0q3.5-6 7 0"/></g>:<g fill="#182C4A"><circle cx="44" cy="45" r="3.5"/><circle cx="64" cy="45" r="3.5"/></g>}
          </g>
        </g>
        {motion==='working'&&<g className="robot-thinking" fill="#F8F7F3"><circle cx="47" cy="70" r="2"/><circle cx="54" cy="70" r="2"/><circle cx="61" cy="70" r="2"/></g>}
      </g>
    </g>
  </svg>;
  return <svg className={`robot ${busy ? 'working' : ''}`} viewBox="0 0 108 108" aria-hidden="true">
    <path fill={failed ? '#B18FA7' : '#659CD2'} d="M54,14 C72,14 84,28 84,49 L84,53 C89,57 94,63 96,68 C98,73 95,77 91,77 C87,77 85,75 82,73 C81,81 77,86 73,89 L73,90 C73,94 70,96 66,96 L64,96 C60,96 58,94 58,90 L58,88 L50,88 L50,90 C50,94 48,96 44,96 L42,96 C38,96 35,94 35,90 L35,89 C31,86 27,81 26,73 C23,75 21,77 17,77 C13,77 10,73 12,68 C14,63 19,57 24,53 L24,49 C24,28 36,14 54,14 Z"/>
    <rect x="31" y="32" width="46" height="26" rx="13" fill="#F8F7F3"/>
    <g className="eyes" fill="#182C4A"><circle cx="44" cy="45" r="3.5"/><circle cx="64" cy="45" r="3.5"/></g>
    {busy && <g fill="#F8F7F3"><circle cx="47" cy="70" r="2"/><circle cx="54" cy="70" r="2"/><circle cx="61" cy="70" r="2"/></g>}
  </svg>;
}
