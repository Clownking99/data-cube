const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const base=__dirname,root=path.resolve(base,'../../../../..');
const prefix=path.relative(root,base).replaceAll('\\','/');
if(prefix!=='docs/superpowers/verification/evidence/favorites-compact-ci-20261007')throw Error('Unexpected evidence scope');
const manifestPath=path.join(base,'manifest.json');
const digest=b=>crypto.createHash('sha256').update(b).digest('hex');
if(process.argv[2]==='freeze'){
 if(fs.existsSync(manifestPath))throw Error('Manifest already exists');
 const files=[];
 const walk=d=>{for(const e of fs.readdirSync(d,{withFileTypes:true})){
  const p=path.join(d,e.name);if(e.isSymbolicLink())throw Error('No links');
  if(e.isDirectory())walk(p);else if(e.isFile()&&e.name!=='manifest.json'){
   const b=fs.readFileSync(p);files.push({path:path.relative(base,p).replaceAll('\\','/'),bytes:b.length,sha256:digest(b)});
  }
 }};walk(base);files.sort((a,b)=>a.path.localeCompare(b.path));
 fs.writeFileSync(manifestPath,JSON.stringify({utc:new Date().toISOString(),sourceCommit:'bdf274cac590f27105f02e20835da1fe8bf49848',files},null,2)+'\n');console.log('Frozen '+files.length);return;
}
const manifest=JSON.parse(fs.readFileSync(manifestPath,'utf8'));
for(const item of manifest.files){
 const p=path.resolve(base,item.path);if(!p.startsWith(base+path.sep)||fs.lstatSync(p).isSymbolicLink())throw Error('Unsafe path');
 const b=fs.readFileSync(p);if(b.length!==item.bytes||digest(b)!==item.sha256)throw Error('Raw mismatch '+item.path);
 const stored=cp.execFileSync('git',['show',':'+prefix+'/'+item.path],{cwd:root,maxBuffer:20*1024*1024});
 if(!stored.equals(b))throw Error('Git bytes mismatch '+item.path);
}
console.log(JSON.stringify({checked:manifest.files.length,rawAndGitBytesMatch:true}));
