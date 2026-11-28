export function SidebarIcon({kind}:{kind:'calendar'|'settings'|'home'}) {
  return <svg className="sidebar-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind==='home'?<><path d="m3 10 9-7 9 7M5.5 9v11h5v-6h3v6h5V9"/></>:kind==='calendar'?<><rect x="3.5" y="5.5" width="17" height="15" rx="3"/><path d="M7.5 3.5v4m9-4v4m-13 3h17M8 14.5h3m-3 3h6"/></>:<><path d="m9.5 3-.6 2.3-1.4.8-2.3-.6-2.5 4.3 1.7 1.7v1.6l-1.7 1.7 2.5 4.3 2.3-.6 1.4.8.6 2.3h5l.6-2.3 1.4-.8 2.3.6 2.5-4.3-1.7-1.7v-1.6l1.7-1.7-2.5-4.3-2.3.6-1.4-.8L14.5 3z"/><circle cx="12" cy="12.3" r="3"/></>}
  </svg>;
}
