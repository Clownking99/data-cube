const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process');
const root=path.resolve(__dirname,'../../../../..');
const base=process.argv[2]?path.resolve(process.argv[2]):__dirname;
const prefix=path.relative(root,base).replaceAll('\\','/');
if(!/^docs\/superpowers\/verification\/evidence\/sql-inflight-20261007-(worker|coordination)$/.test(prefix))throw new Error('Scope');
const manifest=JSON.parse(fs.readFileSync(path.join(base,'raw-manifest.json'),'utf8'));
const paths=manifest.files.map(item=>{
 const full=path.resolve(base,item.path);
 if(!full.toLowerCase().startsWith((base+path.sep).toLowerCase()))throw new Error('Path outside evidence');
 return prefix+'/'+item.path;
});
paths.push(prefix+'/raw-manifest.json');
cp.execFileSync('git',['add','--force','--pathspec-from-file=-','--pathspec-file-nul'],{cwd:root,input:Buffer.from(paths.join('\0')+'\0'),stdio:['pipe','inherit','inherit']});
console.log('Staged exact frozen paths: '+paths.length);
