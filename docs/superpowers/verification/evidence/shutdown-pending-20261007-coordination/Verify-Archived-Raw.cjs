const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=path.resolve(__dirname,'../../../../..');
const results=[];
for(const prefix of process.argv.slice(2)){
 if(!/^docs\/superpowers\/verification\/evidence\/shutdown-pending-20261007-(worker|coordination)$/.test(prefix))throw new Error('Unexpected evidence prefix');
 const base=path.join(root,prefix),manifest=JSON.parse(fs.readFileSync(path.join(base,'raw-manifest.json'),'utf8'));
 const entries=cp.execFileSync('git',['ls-files','--stage','-z','--',prefix],{cwd:root,encoding:'utf8'}).split('\0').filter(Boolean);
 const index=new Map(entries.map(line=>{const m=/^\d+ ([a-f0-9]{40}) 0\t(.+)$/.exec(line);if(!m)throw new Error('Unexpected index');return[m[2],m[1]];}));
 let totalBytes=0;
 for(const item of manifest.files){
  const file=path.resolve(base,item.path);
  if(!file.toLowerCase().startsWith((base+path.sep).toLowerCase())||fs.lstatSync(file).isSymbolicLink())throw new Error('Unsafe path');
  const data=fs.readFileSync(file);totalBytes+=data.length;
  if(data.length!==item.bytes||crypto.createHash('sha256').update(data).digest('hex').toUpperCase()!==item.sha256)throw new Error('Raw changed: '+item.path);
  const oid=crypto.createHash('sha1').update(Buffer.from('blob '+data.length+'\0')).update(data).digest('hex');
  if(index.get(prefix+'/'+item.path)!==oid)throw new Error('Git differs: '+item.path);
 }
 results.push({prefix,checked:manifest.files.length,totalBytes,rawAndGitMatch:true});
}
const output={utc:new Date().toISOString(),head:cp.execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),results};
console.log(JSON.stringify(output,null,2));
