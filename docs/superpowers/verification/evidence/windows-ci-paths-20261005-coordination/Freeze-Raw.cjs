const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const excluded = new Set(['raw-manifest.json', 'staged-raw-audit.json']);
const files = [];
function walk(dir) {
  for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
    const full = path.join(dir, entry.name);
    if (entry.isSymbolicLink()) throw new Error('No evidence links allowed');
    if (entry.isDirectory()) walk(full);
    else if (entry.isFile()) {
      const rel = path.relative(__dirname, full).replaceAll('\\', '/');
      if (excluded.has(rel)) continue;
      const bytes = fs.readFileSync(full);
      files.push({path: rel, bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase()});
    }
  }
}
walk(__dirname);
files.sort((a,b) => a.path.localeCompare(b.path));
fs.writeFileSync(path.join(__dirname,'raw-manifest.json'), JSON.stringify({utc: new Date().toISOString(), files}, null, 2)+'\n');
console.log('Frozen files: '+files.length);
