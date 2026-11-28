// Apply and verify the existing YoungMini logo in the copied Windows launcher.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const R=require('../ui/node_modules/resedit');
const root=path.resolve(__dirname,'..');
const icon=R.Data.IconFile.from(fs.readFileSync(path.join(root,'ui/public/robot.ico')));
const bytes=item=>Buffer.from(item.isRaw()?item.bin:item.generate());
function verify(binary){
  const exe=R.NtExecutable.from(binary,{ignoreCert:true});
  const resources=R.NtExecutableResource.from(exe);
  const groups=R.Resource.IconGroupEntry.fromEntries(resources.entries);
  assert(groups.length>0,'Executable icon group missing');
  for(const group of groups){
    const images=group.getIconItemsFromEntries(resources.entries);
    assert.equal(images.length,icon.icons.length,'Incomplete Windows icon sizes');
    images.forEach((item,index)=>assert(bytes(item).equals(bytes(icon.icons[index].data)),'Executable still contains a different icon'));
  }
  console.log('PASS: Windows executable embeds YoungMini logo at '+icon.icons.map(i=>i.data.width).join(', ')+' px');
}
if(process.argv[2]==='--verify'){
  verify(fs.readFileSync(process.argv[3]==='-'?0:process.argv[3]));
}else{
  const target=process.argv[2];
  const exe=R.NtExecutable.from(fs.readFileSync(target),{ignoreCert:true});
  const resources=R.NtExecutableResource.from(exe);
  const groups=R.Resource.IconGroupEntry.fromEntries(resources.entries);
  assert(groups.length>0,'Upstream Electron icon group missing');
  for(const group of groups)R.Resource.IconGroupEntry.replaceIconsForResource(resources.entries,group.id,group.lang,icon.icons.map(i=>i.data));
  const version=require('../ui/package.json').version.split('.').map(Number);
  for(const info of R.Resource.VersionInfo.fromEntries(resources.entries)){
    info.setFileVersion(...version,0,1033);info.setProductVersion(...version,0,1033);
    info.setStringValues({lang:1033,codepage:1200},{FileDescription:'Young Mini',ProductName:'Young Mini',InternalName:'YoungMini',OriginalFilename:'YoungMini.exe'});
    info.outputToResourceEntries(resources.entries);
  }
  resources.outputResource(exe);
  const output=Buffer.from(exe.generate());verify(output);fs.writeFileSync(target,output);
}
