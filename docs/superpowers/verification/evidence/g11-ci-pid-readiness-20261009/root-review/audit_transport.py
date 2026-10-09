"""Review the precise PID correction transport, without importing its implementation."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path('D:/Projects/朝花夕拾')
GIT = 'D:/Git/cmd/git.exe'
BASE = 'cfd9d4ed4ebd08d1b9f2ff138ae25690901effe9'
HEAD = 'ca055cc597fa92ce0d13b52ea892d2cd4bea72ef'
MANIFEST = 'docs/superpowers/verification/evidence/g11-p2-ci-pid-4983fab79aa14a5da1626836b240525c-frozen/manifest.json'
REPORT = 'docs/superpowers/verification/2026-10-09-g11-ci-pid-readiness-worker.md'
ATTR = 'docs/superpowers/verification/.gitattributes'
EXCLUDES = ['.', ':(exclude).testagent', ':(exclude).testagent/**', ':(exclude)**/.testagent/**']

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def git(*args):
    return subprocess.run([GIT, *args], cwd=ROOT, capture_output=True, check=True).stdout

def safe(path):
    require(isinstance(path, str) and path and not path.startswith('/') and
            not any(x in path for x in (':', '\\', '\n', '\r', '\t')) and
            not any(x.lower() == '.testagent' or x in ('.', '..', '') for x in path.split('/')), 'unsafe path')
    return path

def blob(path):
    return git('show', HEAD + ':' + safe(path))

def verify(data, entry):
    require(len(data) == entry['length'] and hashlib.sha256(data).hexdigest() == entry['sha256'], 'byte identity: ' + entry['path'])

mode = sys.argv[1]
require(mode in ('before', 'after'), 'mode')
raw = blob(MANIFEST)
require(hashlib.sha256(raw).hexdigest() == '04ee175ba44263ac75a6e528e19c0d6f1cafcaea1e72c46e599657cb79f72379', 'manifest')
manifest = json.loads(raw)
expected = {safe(x['path']) for x in manifest['files'] + manifest['workingTreeCorrections']} | {MANIFEST, REPORT, ATTR}
changed = set(git('diff', '--name-only', '-z', BASE, HEAD, '--', *EXCLUDES).decode().rstrip('\0').split('\0'))
require(changed == expected and len(expected) == 109, 'unexpected worker paths')
for entry in manifest['files'] + manifest['workingTreeCorrections']:
    verify(blob(entry['path']), entry)
require(hashlib.sha256(blob(REPORT)).hexdigest() == 'a313f3b091d59e43e24d019be69d12bce0ae02659dfb305ef6a6a075c84c6c1d', 'report identity')
prior = git('show', BASE + ':' + ATTR).decode().splitlines()
require(blob(ATTR).decode().splitlines() == prior + ['2026-10-09-g11-ci-pid-readiness-worker.md -text'], 'attributes scope')
source = []
if mode == 'after':
    for entry in manifest['files']:
        verify((ROOT / safe(entry['path'])).read_bytes(), entry)
    for path in (MANIFEST, REPORT):
        require((ROOT / path).read_bytes() == blob(path), 'checkout bytes: ' + path)
    for entry in manifest['workingTreeCorrections']:
        data = (ROOT / safe(entry['path'])).read_bytes()
        require(data.replace(b'\r\n', b'\n') == blob(entry['path']).replace(b'\r\n', b'\n'), 'source text changed')
        source.append(dict(path=entry['path'], length=len(data), sha256=hashlib.sha256(data).hexdigest(), rawIdentical=data == blob(entry['path'])))
result = dict(schema='root-pid-transport/v1', mode=mode, reviewedWorkerHead=HEAD,
              currentHead=git('rev-parse', 'HEAD').decode().strip(), files=109,
              evidenceFiles=103, sourceCheckout=source, passed=True)
out = Path(__file__).parent / ('transport-' + mode + '.json')
with out.open('x', encoding='utf-8') as stream:
    json.dump(result, stream, ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False))
