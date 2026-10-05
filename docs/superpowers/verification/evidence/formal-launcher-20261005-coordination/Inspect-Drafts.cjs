'use strict';
// Independent, read-only decoder for snapshots of this acceptance run's synthetic files.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const [directory, expectedFile, output] = process.argv.slice(2);
assert(directory && expectedFile && output, 'usage: snapshot-dir expected.json output.json');
const expected = JSON.parse(fs.readFileSync(expectedFile, 'utf8').replace(/^\uFEFF/, ''));
const root = fs.realpathSync(directory);
const hash = b => crypto.createHash('sha256').update(b).digest('hex');
const uuid = b => {
  const h = b.toString('hex');
  return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
};
const records = fs.readdirSync(root).filter(n => /^[0-9a-f-]{36}\.draft$/.test(n)).map(name => {
  const filename = path.join(root, name);
  assert(!fs.lstatSync(filename).isSymbolicLink());
  const b = fs.readFileSync(filename);
  assert.equal(b.readUInt32BE(0), 0x44434452);
  assert.equal(b.readInt32BE(4), 1);
  const id = uuid(b.subarray(8,24));
  assert.equal(name, `${id}.draft`);
  let offset = 32;
  const fields = [];
  for (let i = 0; i < 5; i++) {
    const length = b.readInt32BE(offset); offset += 4;
    if (length === -1 && i < 4) { fields.push(null); continue; }
    assert(length >= 0 && length <= (i === 4 ? 1048576 : 4096));
    assert(offset + length <= b.length);
    fields.push(new TextDecoder('utf-8', {fatal:true}).decode(b.subarray(offset, offset + length)));
    offset += length;
  }
  assert.equal(offset, b.length);
  assert.deepEqual(fields.slice(0,3), [null,null,null], 'offline connection identity must be empty');
  assert(fields[3] === null || fields[3] === '', 'offline schema must be blank');
  return {name,id,bytes:b.length,sha256:hash(b),modifiedAt:b.readBigInt64BE(24).toString(),connectionMetadata:fields.slice(0,4),sql:fields[4]};
});
assert.equal(records.length, expected.sql.length);
assert.deepEqual(records.map(r => r.sql).sort(), [...expected.sql].sort());
const file = path.join(root, 'workspace.bin');
assert(!fs.lstatSync(file).isSymbolicLink());
const b = fs.readFileSync(file);
assert.equal(b.readUInt32BE(0), 0x44435753);
assert.equal(b.readInt32BE(4), 1);
const count = b.readInt32BE(16), selected = b.readInt32BE(20);
assert.equal(count, expected.sql.length);
assert.equal(b.length, 24 + count * 24);
assert.equal(selected, expected.selectedIndex);
const entries = [];
for (let i = 0; i < count; i++) {
  const at = 24 + i * 24, id = uuid(b.subarray(at, at + 16));
  const record = records.find(r => r.id === id);
  assert(record, 'workspace must reference an existing actual draft');
  assert.equal(record.sql, expected.sql[i], 'tab order/content mismatch');
  const anchor = b.readInt32BE(at + 16), caret = b.readInt32BE(at + 20);
  assert(anchor >= 0 && anchor <= record.sql.length && caret >= 0 && caret <= record.sql.length);
  if (expected.positions) assert.deepEqual([anchor,caret], expected.positions[i]);
  entries.push({id,anchor,caret,sql:record.sql});
}
const result = {at:new Date().toISOString(),snapshot:root,passed:true,drafts:records,
  workspace:{bytes:b.length,sha256:hash(b),capturedAt:b.readBigInt64BE(8).toString(),selectedIndex:selected,entries}};
fs.writeFileSync(output, JSON.stringify(result,null,2) + '\n', {flag:'wx'});
console.log(JSON.stringify(result,null,2));
