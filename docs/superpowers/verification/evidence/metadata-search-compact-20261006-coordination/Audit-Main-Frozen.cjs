const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=path.resolve(__dirname,'../../../../..');
const worker=path.join(root,'docs/superpowers/verification/evidence/metadata-search-compact-20261006-worker');
const prefix=path.relative(root,worker).replaceAll('\\','/');
const data=JSON.parse(fs.readFileSync(path.join(worker,'raw-manifest.json'),'utf8'));
const staged=new Map(cp.execFileSync('git',['ls-files','--stage','-z','--',prefix],{cwd:root,encoding:'utf8'}).split('\0').filter(Boolean).map(line=>{const m=/^\d+ ([a-f0-9]{40}) 0\t(.+)$/.exec(line);if(!m)throw Error('Index format');return[m[2],m[1]];}));
for(const f of data.files){
 const file=path.resolve(worker,f.path); if(!file.startsWith(worker+path.sep))throw Error('Scope');
 const b=fs.readFileSync(file);const hash=crypto.createHash('sha256').update(b).digest('hex').toUpperCase();
 const oid=crypto.createHash('sha1').update(Buffer.from('blob '+b.length+'\0')).update(b).digest('hex');
 if(b.length!==f.bytes||hash!==f.sha256||staged.get(prefix+'/'+f.path)!==oid)throw Error('Bytes mismatch: '+f.path);
}
cp.execFileSync('git',['diff','--exit-code','58278f767d998cb5001407ac69bd71c823fb3ba9','HEAD','--','src','test','resources','build.gradle','buildSrc','.github/workflows'],{cwd:root,stdio:'pipe'});
const result={utc:new Date().toISOString(),head:cp.execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),files:data.files.length,rawAndIndexMatch:true,sourceMatchesReviewedCommit:true};
fs.writeFileSync(path.join(__dirname,'main-worker-raw-audit.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
