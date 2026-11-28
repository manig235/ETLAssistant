const {contextBridge, ipcRenderer} = require('electron');
contextBridge.exposeInMainWorld('youngmini', {
  getState: () => ipcRenderer.invoke('get-state'),
  readArtifact: id => ipcRenderer.invoke('read-artifact',id),
  getPrint: id => ipcRenderer.invoke('get-print',id),
  printReady: id => ipcRenderer.invoke('print-ready',id),
  command: value => ipcRenderer.invoke('command', value),
  control: (action, value) => ipcRenderer.invoke('control', action, value),
  onState: callback => { const handler = (_, state) => callback(state); ipcRenderer.on('state', handler); return () => ipcRenderer.removeListener('state', handler); },
  onShell: callback => { const handler = (_, state) => callback(state); ipcRenderer.on('shell-state', handler); return () => ipcRenderer.removeListener('shell-state', handler); },
});
