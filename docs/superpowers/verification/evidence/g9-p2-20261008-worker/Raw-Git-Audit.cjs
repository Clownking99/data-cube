const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../..'),prefix=path.relative(root,__dirname).replaceAll('\\','/');
const mode=process.argv[2];
const exclusions=[':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**'];
const git=(args,input)=>cp.execFileSync('git',args,{cwd:root,input,encoding:'utf8',maxBuffer:32*1024*1024});
const split=s=>s.split('\0').filter(Boolean);
const legal=p=>!p.split('/').includes('.testagent');
function raw(files){
 for(const p of files)if(!legal(p)||/[\r\n]/.test(p))throw new Error('Out of scope path');
 const output=git(['hash-object','--no-filters','--stdin-paths'],files.map(p=>JSON.stringify(p)).join('\n')+'\n').trim().split(/\r?\n/);
 if(output.length!==files.length)throw new Error('Raw blob count');
 return new Map(files.map((p,i)=>[p,output[i]]));
}
function indexEntries(){return new Map(split(git(['ls-files','--stage','-z','--',...scopes(),...exclusions])).map(line=>{const m=/^\d+ ([a-f0-9]{40}) 0\t(.+)$/.exec(line);if(!m)throw new Error('Index entry');return [m[2],m[1]];}));}
function scopes(){return ['src','test','README.md','docs/superpowers/verification/2026-10-08-g9-table-export-worker.md',...evidenceRoots];}
function verify(files,entries){const hashes=raw(files);for(const p of files)if(entries.get(p)!==hashes.get(p))throw new Error('Raw bytes differ: '+p);return files.map(p=>({path:p,blob:hashes.get(p)}));}
function committedEntries(files){
 for(const p of files)if(!legal(p)||/[\r\n]/.test(p))throw new Error('Illegal exact blob request');
 const lines=git(['cat-file','--batch-check=%(objectname) %(objecttype)'],files.map(p=>'HEAD:'+p).join('\n')+'\n').trim().split(/\r?\n/);
 if(lines.length!==files.length)throw new Error('Commit blob count');
 return new Map(files.map((p,i)=>{const m=/^([a-f0-9]{40}) blob$/.exec(lines[i]);if(!m)throw new Error('Commit blob missing: '+p);return [p,m[1]];}));
}
function stage(files){git(['-c','core.autocrlf=false','-c','core.safecrlf=false','add','--force','--pathspec-from-file=-','--pathspec-file-nul'],Buffer.from(files.map(p=>':(literal)'+p).join('\0')+'\0'));}
const evidenceRoots=['g9-table-export-20261008-worker','g9-pgdump-20261008-worker','g9-jdbc-export-20261008-worker','g9-p2-20261008-worker'].map(s=>'docs/superpowers/verification/evidence/'+s);
function walk(relative){
 const file=path.join(root,relative),stat=fs.lstatSync(file);if(stat.isSymbolicLink())throw new Error('Unexpected symlink');
 if(stat.isDirectory())return fs.readdirSync(file).filter(s=>s!=='.testagent').flatMap(s=>walk(relative+'/'+s));
 return [relative];
}
function receipt(name,data){fs.writeFileSync(path.join(__dirname,name),JSON.stringify({utc:new Date().toISOString(),...data},null,2)+'\n');}
if(mode==='code'){
 const files=[...new Set([...split(git(['diff','--name-only','-z','HEAD','--','src','test',...exclusions])),...split(git(['ls-files','--others','--exclude-standard','-z','--','src','test',...exclusions])),'README.md'])].sort();
 const freeze=JSON.parse(fs.readFileSync(path.join(__dirname,'input-freeze.json'),'utf8').replace(/^\uFEFF/,''));
 for(const p of files){const frozen=freeze.files.find(item=>item.path===p),bytes=fs.readFileSync(path.join(root,p));if(!frozen||bytes.length!==frozen.length||crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase()!==frozen.sha256)throw new Error('Source binding: '+p);}
 if(split(git(['diff','--cached','--name-only','-z','--',...scopes(),...exclusions])).length)throw new Error('Index must start empty within scope');
 stage(files);const records=verify(files,indexEntries());receipt('code-index-audit.json',{head:git(['rev-parse','HEAD']).trim(),files:records,rawAndIndexMatch:true});
 console.log(JSON.stringify({staged:files.length,rawAndIndexMatch:true}));
}else if(mode==='code-commit'){
 const previous=JSON.parse(fs.readFileSync(path.join(__dirname,'code-index-audit.json'),'utf8'));
 const files=previous.files.map(item=>item.path),records=verify(files,committedEntries(files));receipt('code-commit-audit.json',{commit:git(['rev-parse','HEAD']).trim(),files:records,rawAndCommitMatch:true});console.log(JSON.stringify({commit:git(['rev-parse','HEAD']).trim(),files:files.length,rawAndCommitMatch:true}));
}else if(mode==='freeze-docs'){
 const ignored=[prefix+'/raw-manifest.json',prefix+'/docs-index-audit.json'];
 const files=['docs/superpowers/verification/2026-10-08-g9-table-export-worker.md',...evidenceRoots.flatMap(walk)].filter(p=>!ignored.includes(p)).sort();
 if(fs.existsSync(path.join(__dirname,'raw-manifest.json')))throw new Error('Freeze must be fresh');
 receipt('raw-manifest.json',{codeCommit:git(['rev-parse','HEAD']).trim(),selfExcluded:ignored,files:files.map(p=>{const bytes=fs.readFileSync(path.join(root,p));return {path:p,length:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase()};})});console.log(JSON.stringify({frozen:files.length}));
}else if(mode==='docs'){
 const manifest=JSON.parse(fs.readFileSync(path.join(__dirname,'raw-manifest.json'),'utf8'));
 for(const item of manifest.files){const bytes=fs.readFileSync(path.join(root,item.path));if(bytes.length!==item.length||crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase()!==item.sha256)throw new Error('Frozen artifact changed: '+item.path);}
 const files=manifest.files.map(item=>item.path).concat(prefix+'/raw-manifest.json');stage(files);
 const records=verify(files,indexEntries());receipt('docs-index-audit.json',{codeCommit:git(['rev-parse','HEAD']).trim(),files:records,rawAndIndexMatch:true});stage([prefix+'/docs-index-audit.json']);verify([prefix+'/docs-index-audit.json'],indexEntries());console.log(JSON.stringify({staged:files.length+1,rawAndIndexMatch:true}));
}else if(mode==='final'){
 const manifest=JSON.parse(fs.readFileSync(path.join(__dirname,'raw-manifest.json'),'utf8'));
 const files=manifest.files.map(item=>item.path).concat(prefix+'/raw-manifest.json',prefix+'/docs-index-audit.json');
 const entries=committedEntries(files);
 for(const item of manifest.files){const bytes=fs.readFileSync(path.join(root,item.path));if(bytes.length!==item.length||crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase()!==item.sha256)throw new Error('Postcommit frozen artifact changed');}
 verify(files,entries);console.log(JSON.stringify({commit:git(['rev-parse','HEAD']).trim(),files:files.length,rawAndCommitMatch:true}));
}else throw new Error('Mode required');
