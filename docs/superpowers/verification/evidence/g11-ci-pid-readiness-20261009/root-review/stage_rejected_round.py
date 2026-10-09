"""Precisely stage the rejected round and its checkpoint, without granting delivery."""
import hashlib
import json
from pathlib import Path
import subprocess

repo = Path('D:/Projects/朝花夕拾')
git_exe = 'D:/Git/cmd/git.exe'
excludes = ['.', ':(exclude).testagent', ':(exclude).testagent/**', ':(exclude)**/.testagent/**']

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def git(*args, data=None):
    return subprocess.run([git_exe, *args], cwd=repo, input=data, capture_output=True, check=True).stdout

def safe(path):
    require(path and not any(x in path for x in (':','\\','\n','\r','\t')) and
            all(x and x not in ('.','..') and x.casefold() not in ('.testagent','.git') for x in path.split('/')), 'unsafe path')
    return path

manifest_path = 'docs/superpowers/verification/evidence/g11-p3-581b459dad08-rejected-frozen/manifest.json'
raw = (repo/manifest_path).read_bytes()
require(hashlib.sha256(raw).hexdigest() == '40fed9cfd3b881fe62bd6e237d27eed090dd275316f75e08df9b11f060754b97', 'manifest identity')
manifest = json.loads(raw)
require(manifest['accepted'] is False and manifest['deliveryAllowed'] is False and len(manifest['files']) == 1904, 'rejection verdict')
paths = {manifest_path}
for row in manifest['files']:
    path = safe(row['path'])
    require(path.startswith('docs/superpowers/verification/evidence/'), 'non-evidence')
    name=path.split('/')[4]
    require(name.startswith(('g11-p3-581b459dad08-','g11-p2-synthetic-g11-p3-581b459dad08-')), 'foreign round')
    raw=(repo/path).read_bytes()
    require(len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256'], 'changed original')
    paths.add(path)
paths.update([
    'docs/handoffs/CURRENT.md', 'docs/maintenance/verification-guide.md',
    'docs/maintenance/verification-runner-design.md',
    'docs/superpowers/plans/2026-10-09-g11-verification-core.md',
    'docs/superpowers/verification/2026-10-09-g11-verification-coordination.md',
    'docs/superpowers/verification/2026-10-09-g11-main-verification.md',
    'docs/superpowers/verification/2026-10-09-g11-ci-correction-main.md',
])
for name in ('audit_transport.py','transport-before.json','transport-after.json','stage_rejected_round.py'):
    paths.add('docs/superpowers/verification/evidence/g11-ci-pid-readiness-20261009/root-review/'+name)
paths=sorted(safe(p) for p in paths)
require(git('branch','--show-current').strip()==b'main','branch')
require(git('rev-parse','HEAD').strip()==b'e368a1b16bdee226925b363f06b4dc4f003c1f0f','head')
require(not git('diff','--cached','--name-only','-z','--',*excludes),'index not empty')
status=git('status','--porcelain','-z','--untracked-files=all','--',*excludes).decode().rstrip('\0')
changed={row[3:] for row in status.split('\0') if row}
require(changed<=set(paths),'unexpected changes: '+repr(sorted(changed-set(paths))))
hashes=git('hash-object','-w','--no-filters','--stdin-paths',data=('\n'.join(paths)+'\n').encode()).decode().splitlines()
require(len(hashes)==len(paths),'hash count')
git('update-index','--index-info',data=''.join('100644 '+oid+'\t'+path+'\n' for oid,path in zip(hashes,paths)).encode())
staged=set(git('diff','--cached','--name-only','-z','--',*excludes).decode().rstrip('\0').split('\0'))
require(staged==set(paths),'staged set')
raw=git('cat-file','--batch',data=''.join(':'+path+'\n' for path in paths).encode())
offset=0
for path in paths:
    end=raw.index(b'\n',offset);header=raw[offset:end].decode().split()
    require(len(header)==3 and header[1]=='blob','blob kind')
    size=int(header[2]);offset=end+1
    require(raw[offset:offset+size]==(repo/path).read_bytes(),'raw blob: '+path)
    offset+=size;require(raw[offset:offset+1]==b'\n','separator');offset+=1
require(offset==len(raw),'trailing bytes')
print(json.dumps(dict(stagedFiles=len(paths),frozenEvidenceFiles=1904,rawBlobMatches=True,deliveryAllowed=False)))
