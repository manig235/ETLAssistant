export function Robot({busy = false, failed = false}: {busy?: boolean; failed?: boolean}) {
  return <svg className={`robot ${busy ? 'working' : ''}`} viewBox="0 0 108 108" aria-hidden="true">
    <path fill={failed ? '#B18FA7' : '#659CD2'} d="M54,14 C72,14 84,28 84,49 L84,53 C89,57 94,63 96,68 C98,73 95,77 91,77 C87,77 85,75 82,73 C81,81 77,86 73,89 L73,90 C73,94 70,96 66,96 L64,96 C60,96 58,94 58,90 L58,88 L50,88 L50,90 C50,94 48,96 44,96 L42,96 C38,96 35,94 35,90 L35,89 C31,86 27,81 26,73 C23,75 21,77 17,77 C13,77 10,73 12,68 C14,63 19,57 24,53 L24,49 C24,28 36,14 54,14 Z"/>
    <rect x="31" y="32" width="46" height="26" rx="13" fill="#F8F7F3"/>
    <g className="eyes" fill="#182C4A"><circle cx="44" cy="45" r="3.5"/><circle cx="64" cy="45" r="3.5"/></g>
    {busy && <g fill="#F8F7F3"><circle cx="47" cy="70" r="2"/><circle cx="54" cy="70" r="2"/><circle cx="61" cy="70" r="2"/></g>}
  </svg>;
}
