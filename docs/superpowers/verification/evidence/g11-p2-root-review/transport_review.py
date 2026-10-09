"""Verify exact approved worker diff and current checkout bytes against Git blobs."""
import hashlib
import json
from pathlib import Path
import subprocess

repo = Path(__file__).absolute().parents[5]
git = 'D:/Git/cmd/git.exe'
base = '099dd677a653731bf2fad998cacb65ef33f2c827'
worker = '6aefd9d136058ec67cab688b28158dcc4416fb9d'
evidence = repo / 'docs/superpowers/verification/evidence'
expected = {'scripts/.gitattributes', 'docs/superpowers/verification/evidence/.gitattributes',
            'docs/superpowers/verification/2026-10-09-g11-verification-worker.md',
            'docs/superpowers/verification/2026-10-09-g11-p2-worker.md'}
for phase in ['p1', 'p2']:
    path = 'docs/superpowers/verification/evidence/g11-' + phase + '-frozen-20261009/manifest.json'
    expected.add(path)
    expected.update(x['path'] for x in json.loads((repo/path).read_text())['files'])
for row in json.loads((evidence/'g11-p2-package-v2/tool-manifest.json').read_text()):
    expected.add('scripts/verification/' + row['path'])
for path in expected:
    if any(x in ('.testagent', '.git', '..') for x in path.split('/')) or ':' in path or path.startswith('/'):
        raise RuntimeError('forbidden')
raw = subprocess.run([git, 'diff', '--raw', '--no-abbrev', '-z', base, worker, '--', '.',
                      ':(exclude).testagent', ':(exclude).testagent/**', ':(exclude)**/.testagent/**'],
                     cwd=repo, check=True, capture_output=True).stdout.decode().strip('\0').split('\0')
actual = {raw[i+1]: raw[i].split()[3] for i in range(0, len(raw), 2)}
if set(actual) != expected:
    raise RuntimeError('changed set mismatch')
paths = sorted(expected)
oids = subprocess.run([git, 'hash-object', '--no-filters', '--stdin-paths'], cwd=repo,
                      input=('\n'.join(paths)+'\n').encode(), check=True, capture_output=True).stdout.decode().splitlines()
for path, oid in zip(paths, oids):
    if actual[path] != oid:
        raise RuntimeError('transport bytes ' + path)
head = subprocess.run([git, 'rev-parse', 'HEAD'], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
result = dict(schema='root-git-transport/v1', worker=worker, integratedMain=head, exactFiles=len(paths),
              allCheckoutBytesEqualGitBlobs=True, productAndBuildChanges=0,
              p1ReportSha256=hashlib.sha256((repo/'docs/superpowers/verification/2026-10-09-g11-verification-worker.md').read_bytes()).hexdigest())
with (evidence/'g11-p2-root-review/main-transport.json').open('x', encoding='utf-8') as stream:
    json.dump(result, stream, indent=2)
print(json.dumps(result))
