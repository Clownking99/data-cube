const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), cp = require('node:child_process');
const root = path.resolve(__dirname, '../../../../..');
const base = process.argv[2] ? path.resolve(process.argv[2]) : __dirname;
const prefix = path.relative(root,base).replaceAll('\\','/');
const data = JSON.parse(fs.readFileSync(path.join(base,'raw-manifest.json'),'utf8'));
const output = cp.execFileSync('git',['ls-files','--stage','-z','--',prefix],{cwd:root,encoding:'utf8'});
const staged = new Map(output.split('\0').filter(Boolean).map(line=>{
  const match=/^\d+ ([a-f0-9]{40}) 0\t(.+)$/.exec(line);
  if(!match) throw new Error('Unexpected index entry');
  return [match[2],match[1]];
}));
for(const entry of data.files){
  if(typeof entry.path!=='string'||!/^[A-F0-9]{64}$/.test(entry.sha256))throw new Error('Schema');
  const file=path.resolve(base,entry.path);
  if(!file.toLowerCase().startsWith((base+path.sep).toLowerCase()))throw new Error('Scope');
  const bytes=fs.readFileSync(file);
  if(bytes.length!==entry.bytes||crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase()!==entry.sha256)throw new Error('Frozen bytes changed: '+entry.path);
  const oid=crypto.createHash('sha1').update(Buffer.from('blob '+bytes.length+'\0')).update(bytes).digest('hex');
  if(staged.get(prefix+'/'+entry.path)!==oid)throw new Error('Index bytes differ: '+entry.path);
}
const result={utc:new Date().toISOString(),checked:data.files.length,rawAndIndexMatch:true};
fs.writeFileSync(path.join(base,'staged-raw-audit.json'),JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result));

