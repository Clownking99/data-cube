const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../..'),prefix=path.relative(root,__dirname).replaceAll('\\','/');
const tests=['test/com/datacube/export/PgDumpRunnerBaselineRedTest.java','test/com/datacube/export/PgDumpProcessHelper.java','test/com/datacube/fx/AppShellTableExportJdbcShutdownTest.java'];
const report='docs/superpowers/verification/2026-10-09-g9-ci-fix-worker.md';
const exclude=[':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**'];
const git=(args,input)=>cp.execFileSync('git',args,{cwd:root,input,encoding:'utf8',maxBuffer:32*1024*1024});
const split=s=>s.split('\0').filter(Boolean),hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase();
function walk(p){const stat=fs.lstatSync(path.join(root,p));if(stat.isSymbolicLink())throw new Error('Symlink');return stat.isDirectory()?fs.readdirSync(path.join(root,p)).filter(s=>s!=='.testagent').flatMap(s=>walk(p+'/'+s)):[p];}
function blobs(files){for(const p of files)if(p.split('/').includes('.testagent')||/[\r\n]/.test(p))throw new Error('Scope');const lines=git(['hash-object','--no-filters','--stdin-paths'],files.map(p=>JSON.stringify(p)).join('\n')+'\n').trim().split(/\r?\n/);if(lines.length!==files.length)throw new Error('Blob count');return new Map(files.map((p,i)=>[p,lines[i]]));}
function compare(files,entries){const raw=blobs(files);for(const p of files)if(raw.get(p)!==entries.get(p))throw new Error('Raw Git bytes differ: '+p);return files.map(p=>({path:p,blob:raw.get(p)}));}
function stage(files){git(['-c','core.autocrlf=false','-c','core.safecrlf=false','add','--force','--pathspec-from-file=-','--pathspec-file-nul'],Buffer.from(files.map(p=>':(literal)'+p).join('\0')+'\0'));}
function write(name,data){const file=path.join(__dirname,name);if(fs.existsSync(file))throw new Error('Receipt must be fresh');fs.writeFileSync(file,JSON.stringify({utc:new Date().toISOString(),...data},null,2)+'\n');}
const mode=process.argv[2];
if(mode==='freeze'){
 const files=[...tests,report,...walk(prefix)].filter(p=>!['raw-manifest.json','index-audit.json'].some(name=>p===prefix+'/'+name)).sort();
 write('raw-manifest.json',{parent:git(['rev-parse','HEAD']).trim(),files:files.map(p=>{const bytes=fs.readFileSync(path.join(root,p));return {path:p,bytes:bytes.length,sha256:hash(bytes)};}),selfExcluded:[prefix+'/raw-manifest.json',prefix+'/index-audit.json']});console.log(JSON.stringify({frozen:files.length}));
}else if(mode==='stage'){
 const manifest=JSON.parse(fs.readFileSync(path.join(__dirname,'raw-manifest.json'),'utf8'));
 for(const item of manifest.files){const bytes=fs.readFileSync(path.join(root,item.path));if(bytes.length!==item.bytes||hash(bytes)!==item.sha256)throw new Error('Frozen bytes changed');}
 const files=manifest.files.map(item=>item.path).concat(prefix+'/raw-manifest.json');stage(files);
 const readIndex=()=>new Map(split(git(['ls-files','--stage','-z','--',...tests,report,prefix,...exclude])).map(line=>{const m=/^\d+ ([a-f0-9]{40}) 0\t(.+)$/.exec(line);if(!m)throw new Error('Index entry');return [m[2],m[1]];}));
 write('index-audit.json',{files:compare(files,readIndex()),rawAndIndexMatch:true});stage([prefix+'/index-audit.json']);compare([prefix+'/index-audit.json'],readIndex());
 const actual=split(git(['diff','--cached','--name-only','-z','--','.',...exclude]));const expected=new Set(files.concat(prefix+'/index-audit.json'));if(actual.length!==expected.size||actual.some(p=>!expected.has(p)))throw new Error('Unexpected staged scope');console.log(JSON.stringify({staged:expected.size,rawAndIndexMatch:true}));
}else if(mode==='final'){
 const manifest=JSON.parse(fs.readFileSync(path.join(__dirname,'raw-manifest.json'),'utf8'));
 const files=manifest.files.map(item=>item.path).concat(prefix+'/raw-manifest.json',prefix+'/index-audit.json');
 for(const item of manifest.files){const bytes=fs.readFileSync(path.join(root,item.path));if(bytes.length!==item.bytes||hash(bytes)!==item.sha256)throw new Error('Frozen bytes changed after commit');}
 const lines=git(['cat-file','--batch-check=%(objectname) %(objecttype)'],files.map(p=>'HEAD:'+p).join('\n')+'\n').trim().split(/\r?\n/);if(lines.length!==files.length)throw new Error('Commit blob count');
 const entries=new Map(files.map((p,i)=>{const m=/^([a-f0-9]{40}) blob$/.exec(lines[i]);if(!m)throw new Error('Missing commit blob');return [p,m[1]];}));compare(files,entries);console.log(JSON.stringify({commit:git(['rev-parse','HEAD']).trim(),files:files.length,rawAndCommitMatch:true}));
}else throw new Error('Mode');
