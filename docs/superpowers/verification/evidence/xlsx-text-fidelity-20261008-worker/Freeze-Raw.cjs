const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const base = process.argv[2] ? path.resolve(process.argv[2]) : __dirname;
const excluded = new Set(['raw-manifest.json', 'staged-raw-audit.json', 'manifest.json']);
const files = [];
const originals = [];
function walk(dir) {
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const full = path.join(dir, entry.name);
    if (entry.isSymbolicLink()) throw new Error('No evidence links allowed');
    if (entry.isDirectory() && entry.name !== 'binary') walk(full);
    else if (entry.isFile()) {
      const rel = path.relative(base, full).replaceAll('\\', '/');
      if (excluded.has(rel)) continue;
      if (rel === '002-baseline-source-diagnosis/nul.xlsx') {
        const mappings=JSON.parse(fs.readFileSync(path.join(base,'002-baseline-source-diagnosis/audit-aliases.json'),'utf8').replace(/^\uFEFF/,''));
        const mapping=mappings.find(item=>item.original==='nul.xlsx' && item.auditFile==='case-nul.xlsx');
        if(!mapping)throw new Error('Missing reserved-name alias evidence');
        const alias=fs.readFileSync(path.join(base,'002-baseline-source-diagnosis',mapping.auditFile));
        if(alias.length!==mapping.length || crypto.createHash('sha256').update(alias).digest('hex').toUpperCase()!==mapping.sha256)throw new Error('Alias byte mismatch');
        originals.push({path:rel,archivedAlias:'002-baseline-source-diagnosis/'+mapping.auditFile,bytes:mapping.length,sha256:mapping.sha256,reason:'Original Windows reserved name retained in worker; byte-identical safe alias archived'});
        continue;
      }
      const bytes = fs.readFileSync(full);
      files.push({path: rel, bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase()});
    }
  }
}
walk(base);
files.sort((a,b) => a.path.localeCompare(b.path));
fs.writeFileSync(path.join(base,'raw-manifest.json'), JSON.stringify({utc: new Date().toISOString(), externalOriginals: originals, files}, null, 2)+'\n');
console.log('Frozen files: '+files.length);

