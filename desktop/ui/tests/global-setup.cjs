// Own the fixture HTTP server in the runner process; no shell child to orphan on Windows.
module.exports=async()=>{
  const server=require('./serve.cjs');
  if(!server.listening)await new Promise((resolve,reject)=>{server.once('listening',resolve);server.once('error',reject);});
  return async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));};
};
