import hashlib
import json
import os
import stat
from pathlib import Path

REPO = Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
OUT = Path(__file__).absolute().parent
MANIFEST = 'docs/superpowers/verification/evidence/g11-p1-frozen-20261009/manifest.json'
EXPECTED = 'ac3f1e6289e942d86dfd9d47f9e9f22bd942bae84a1d11a9ac71056740d1cc9a'

def require(condition, detail):
    if not condition:
        raise RuntimeError(detail)

def admit(relative):
    require(isinstance(relative, str) and '\\' not in relative, 'invalid relative')
    parts = relative.split('/')
    require(all(p and p not in ('.', '..') and ':' not in p and p == p.rstrip(' .') and
                p.lower() not in ('.testagent', '.git') for p in parts), 'forbidden component')
    require(relative.startswith('docs/superpowers/verification/evidence/g11-') or
            relative.startswith('scripts/verification/') or
            relative == 'docs/superpowers/verification/2026-10-09-g11-verification-worker.md', 'outside scope')
    path = REPO / relative
    for ancestor in reversed((path, *path.parents)):
        info = ancestor.lstat()
        require(not (getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT), 'reparse')
    return path

raw = admit(MANIFEST).read_bytes()
require(hashlib.sha256(raw).hexdigest() == EXPECTED, 'manifest changed')
manifest = json.loads(raw)
roots = manifest['roots']
require(len(roots) == len(set(p.lower() for p in roots)), 'duplicate roots')
entries = {}
total = 0
for item in manifest['files']:
    relative = item['path']
    require(relative.lower() not in entries, 'duplicate file')
    require(sum(relative.startswith(root + '/') for root in roots) == 1, 'file root mismatch')
    data = admit(relative).read_bytes()
    require(len(data) == item['length'] and hashlib.sha256(data).hexdigest() == item['sha256'].lower(), 'file identity mismatch: ' + relative)
    entries[relative.lower()] = item
    total += len(data)

actual = set()
def visit(relative):
    with os.scandir(admit(relative)) as children:
        for child in children:
            name = child.name
            require(name.lower() not in ('.testagent', '.git'), 'forbidden entry')
            rel = relative + '/' + name
            path = admit(rel)
            if path.is_dir():
                visit(rel)
            elif path.is_file():
                require(rel.lower() not in actual, 'overlapping root')
                actual.add(rel.lower())
            else:
                raise RuntimeError('invalid kind')
for root in roots:
    visit(root)
require(actual == set(entries), 'inventory mismatch')
require(len(entries) == manifest['fileCount'] and total == manifest['totalBytes'], 'totals mismatch')
tools = {}
for name in ('VerificationCore.psm1', 'OwnedProcessHost.ps1', 'evidence_tools.py', 'run-stage.ps1', 'isolated.gradle', 'check-core.py'):
    source = admit('scripts/verification/' + name).read_bytes()
    frozen = admit('docs/superpowers/verification/evidence/g11-p1-frozen-20261009/tools/' + name).read_bytes()
    require(source == frozen, 'frozen tool mismatch')
    tools[name] = hashlib.sha256(source).hexdigest()
report = admit('docs/superpowers/verification/2026-10-09-g11-verification-worker.md').read_bytes()
result = {'schema': 'root-p1-manifest-review/v1', 'manifestSha256': EXPECTED,
          'rootCount': len(roots), 'fileCount': len(entries), 'totalBytes': total,
          'exactInventoryAndIdentitiesVerified': True, 'tools': tools,
          'workerReportSha256': hashlib.sha256(report).hexdigest(),
          'phase': 'P1', 'fullProductAcceptance': False}
with (OUT / 'manifest-review.json').open('x', encoding='utf-8') as output:
    json.dump(result, output, indent=2)
print(json.dumps(result, indent=2))
