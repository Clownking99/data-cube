"""Stage only the accepted current P3 and its explicit handoff documents."""
import hashlib
import json
from pathlib import Path
import subprocess

base=Path(__file__).absolute().parent
repo=base.parents[4]
prefix='g11-p3-2ba76738625d'
ex=['.',':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**']
def need(v,m):
    if not v:raise RuntimeError(m)
def git(*a,data=None):return subprocess.run(['D:/Git/cmd/git.exe',*a],cwd=repo,input=data,capture_output=True,check=True).stdout
def safe(rel):
    need(rel and not any(x in rel for x in (':','\\','\n','\r','\t')) and all(p and p not in ('.','..') and p.casefold() not in ('.testagent','.git','.g10-verify-blobs.ps1') for p in rel.split('/')),'unsafe relative path')
    p=repo/rel
    for node in (p,*p.parents):need(not node.is_symlink() and not node.is_junction(),'linked input')
    return rel
need(repo==Path('D:/Projects/朝花夕拾') and base.name==prefix+'-package','main package')
preparation=json.loads((base/'preparation.json').read_bytes())
need(git('branch','--show-current').strip()==b'main' and git('rev-parse','HEAD').decode().strip()==preparation['testedCommit'],'tested main changed')
mf='docs/superpowers/verification/evidence/'+prefix+'-frozen/manifest.json'
m=json.loads((repo/mf).read_bytes())
need(m['accepted'] and m['deliveryAllowed'] and m['testedCommit']==preparation['testedCommit'] and m['controls']==98,'P3 not accepted')
paths={mf}
for row in m['files']:
    p=safe(row['path'])
    need(p.startswith('docs/superpowers/verification/evidence/'),'non-evidence frozen file')
    raw=(repo/p).read_bytes()
    need(len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256'],'frozen file changed')
    paths.add(p)
paths.update([
    'docs/handoffs/CURRENT.md',
    'docs/maintenance/verification-guide.md',
    'docs/maintenance/verification-runner-design.md',
    'docs/superpowers/plans/2026-10-09-g11-verification-core.md',
    'docs/superpowers/verification/2026-10-09-g11-verification-coordination.md',
    'docs/superpowers/verification/2026-10-09-g11-main-verification.md',
    'docs/superpowers/verification/2026-10-09-g11-ci-correction-main.md',
    'docs/superpowers/verification/2026-10-09-g11-root-exit-main.md',
    'docs/superpowers/verification/evidence/g11-root-exit-20261009-review/transport-main-disk.json',
])
paths=sorted(safe(p) for p in paths)
need(not git('diff','--cached','--name-only','-z','--',*ex),'index not empty')
raw=git('status','--porcelain','-z','--untracked-files=all','--',*ex).decode().rstrip('\0')
changed={x[3:] for x in raw.split('\0') if x}
need(changed<=set(paths),'unlisted worktree changes: '+repr(sorted(changed-set(paths))))
oids=git('hash-object','-w','--no-filters','--stdin-paths',data=('\n'.join(paths)+'\n').encode()).decode().splitlines()
need(len(oids)==len(paths),'hash count')
git('update-index','--index-info',data=''.join('100644 '+o+'\t'+p+'\n' for o,p in zip(oids,paths)).encode())
staged=set(git('diff','--cached','--name-only','-z','--',*ex).decode().rstrip('\0').split('\0'))
need(staged==set(paths),'staged set mismatch')
raw=git('cat-file','--batch',data=''.join(':'+p+'\n' for p in paths).encode());pos=0
for p in paths:
    end=raw.index(b'\n',pos);header=raw[pos:end].decode().split();pos=end+1
    need(len(header)==3 and header[1]=='blob','blob kind');size=int(header[2])
    need(raw[pos:pos+size]==(repo/p).read_bytes(),'staged raw bytes: '+p);pos+=size
    need(raw[pos:pos+1]==b'\n','blob separator');pos+=1
need(pos==len(raw),'trailing bytes')
print(json.dumps(dict(stagedFiles=len(paths),frozenFiles=m['fileCount'],rawBlobMatches=True,testedCommit=preparation['testedCommit'],pushPerformed=False)))
