"""Freeze only this completed P3's explicitly derivable evidence roots."""
import hashlib
import json
import os
from pathlib import Path
import stat


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


base = Path(__file__).absolute().parent
evidence = base.parent
repo = base.parents[4]
preparation = json.loads((base/'preparation.json').read_text())
prefix = preparation['prefix']
require(prefix == 'g11-p3-581b459dad08', 'unexpected round')
require(json.loads((base/'sequence-result.json').read_text())['passed'], 'sequence failed')
require(json.loads((base/'shell-exit.json').read_text())['actualPythonExitCode'] == 0, 'shell failed')
review_root = evidence/(prefix+'-root-review')
require(json.loads((review_root/'round-verdict.json').read_text())['accepted'] is False, 'missing rejection verdict')
roots = {base, review_root}
for suffix in ['python-controls', 'process-controls', 'policy-controls', 'outer-controls']:
    roots.add(evidence/(prefix+'-'+suffix))
for row in json.loads((base/'progress.json').read_text()):
    roots.update([Path(row['inner']).parent.parent, Path(row['outer']).parent])
for row in json.loads((evidence/(prefix+'-process-controls/results.json')).read_text())['cases']:
    name = 'g11-p2-synthetic-' + prefix + '-process-controls-' + row['mode']
    roots.update([evidence/name, evidence/(name+'-owner')])
for row in json.loads((evidence/(prefix+'-outer-controls/results.json')).read_text())['cases']:
    roots.add(evidence/(prefix+'-outer-controls-'+row['mode']+'-owner'))


def safe(path):
    require(path.is_relative_to(evidence), 'outside evidence')
    relative = path.relative_to(evidence)
    require(relative.parts and (relative.parts[0].startswith(prefix+'-') or relative.parts[0].startswith('g11-p2-synthetic-'+prefix+'-')), 'outside round')
    for part in relative.parts:
        require(part not in ('.', '..') and part.casefold() not in ('.testagent', '.git') and part == part.rstrip(' .') and ':' not in part, 'forbidden path')
    for node in reversed((path, *path.parents)):
        require(not getattr(node.lstat(), 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT, 'reparse')
    return path


entries = []
seen = set()


def visit(root):
    with os.scandir(safe(root)) as children:
        names = [entry.name for entry in children]
    for name in sorted(names):
        path = safe(root/name)
        if path.is_dir():
            visit(path)
        else:
            require(path.is_file(), 'file kind')
            rel = path.relative_to(repo).as_posix()
            require(rel.casefold() not in seen, 'duplicate evidence')
            seen.add(rel.casefold())
            raw = path.read_bytes()
            entries.append(dict(path=rel, length=len(raw), sha256=hashlib.sha256(raw).hexdigest()))


for root in sorted(roots):
    visit(root)
manifest = dict(schema='frozen-evidence/v1', phase='G11-P3-REJECTED', accepted=False, deliveryAllowed=False, testedCommit=preparation['testedCommit'],
                roots=[p.relative_to(repo).as_posix() for p in sorted(roots)], files=entries,
                fileCount=len(entries), totalBytes=sum(x['length'] for x in entries),
                selfExcluded='manifest.json', fullProductAcceptance=False)
target = evidence/(prefix+'-rejected-frozen')
target.mkdir()
with (target/'manifest.json').open('x', encoding='utf-8') as stream:
    json.dump(manifest, stream, ensure_ascii=False, indent=2)
print(json.dumps(dict(path=str(target/'manifest.json'), files=manifest['fileCount'], bytes=manifest['totalBytes'],
                     sha256=hashlib.sha256((target/'manifest.json').read_bytes()).hexdigest())))
