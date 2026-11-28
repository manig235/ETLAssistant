export function SessionIcon({kind}:{kind:'edit'|'delete'}) {
  return <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{kind==='edit'?<><path d="m15.5 4.5 4 4M4 20l4.5-1 12-12a2.8 2.8 0 0 0-4-4l-12 12L4 20Z"/></>:<><path d="M3.5 6.5h17m-12-3h7l1 3h-9l1-3Zm-3 3 1 14h11l1-14M10 10v7m4-7v7"/></>}</svg>;
}
