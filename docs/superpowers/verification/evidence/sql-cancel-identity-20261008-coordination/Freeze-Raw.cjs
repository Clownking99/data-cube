const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const base = process.argv[2] ? path.resolve(process.argv[2]) : __dirname;
const excluded = new Set(['raw-manifest.json', 'staged-raw-audit.json', 'manifest.json']);
const files = [];
function walk(dir) {
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const full = path.join(dir, entry.name);
    if (entry.isSymbolicLink()) throw new Error('No evidence links allowed');
    if (entry.isDirectory() && entry.name !== 'binary') walk(full);
    else if (entry.isFile()) {
      const rel = path.relative(base, full).replaceAll('\\', '/');
      if (excluded.has(rel)) continue;
      const bytes = fs.readFileSync(full);
      files.push({path: rel, bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase()});
    }
  }
}
walk(base);
files.sort((a,b) => a.path.localeCompare(b.path));
fs.writeFileSync(path.join(base,'raw-manifest.json'), JSON.stringify({utc: new Date().toISOString(), files}, null, 2)+'\n');
console.log('Frozen files: '+files.length);

