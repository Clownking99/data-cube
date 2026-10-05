const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../../../../..');
const dirs = ['formal-launcher-20261005-coordination', 'formal-launcher-20261005-worker'];
const checked = dirs.map(name => {
  const dir = path.join(root, 'docs/superpowers/verification/evidence', name);
  const data = JSON.parse(fs.readFileSync(path.join(dir, 'raw-manifest.json'), 'utf8').replace(/^\uFEFF/, ''));
  const entries = Array.isArray(data) ? data : data.files;
  if (!Array.isArray(entries) || !entries.length) throw new Error('Missing entries');
  for (const entry of entries) {
    if (typeof entry.path !== 'string' || !/^[a-f0-9]{64}$/i.test(entry.sha256)) throw new Error('Invalid entry');
    const file = path.resolve(dir, entry.path);
    if (!file.toLowerCase().startsWith((dir + path.sep).toLowerCase())) throw new Error('Path escape');
    const bytes = fs.readFileSync(file);
    if (bytes.length !== entry.bytes || crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase() !== entry.sha256.toUpperCase()) throw new Error('Mismatch: ' + entry.path);
  }
  return { directory: name, checked: entries.length, match: true };
});
fs.writeFileSync(path.join(__dirname, 'previous-formal-evidence-preserved.json'), JSON.stringify({ utc: new Date().toISOString(), checked }, null, 2) + '\n');
console.log(JSON.stringify(checked));
