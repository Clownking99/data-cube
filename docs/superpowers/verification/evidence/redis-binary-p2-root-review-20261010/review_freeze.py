"""Independently bind every original engineering file to the frozen inventory."""
import hashlib,json,os,re
from pathlib import Path
here=Path(__file__).absolute().parent
evidence=Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾/docs/superpowers/verification/evidence')
package='redis-binary-p2-fd5eabee9eeb4eb3a4c20746509772bc-engineering'
expected='ba41a8c537203f70db80150a17e77cd76da637cbcd6a5eecf4514694204b6bdd'
def need(ok,why):
    if not ok:raise RuntimeError(why)
def safe(p):
    need(p.is_relative_to(evidence),'outside evidence')
    need(all(x.casefold() not in ('.testagent','.git','..','.g10-verify-blobs.ps1') for x in p.parts),'forbidden')
    for node in (p,*p.parents):
        need(not node.is_symlink() and not node.is_junction(),'linked path')
    return p
def identity(p):
    raw=safe(p).read_bytes();return dict(length=len(raw),sha256=hashlib.sha256(raw).hexdigest())
mp=evidence/(package+'-frozen')/'manifest.json'
need(identity(mp)['sha256']==expected,'manifest identity')
m=json.loads(mp.read_bytes()); roots=m['roots']
need(m['passed'] and len(roots)==len(set(roots))==11,'roots')
need(all(re.fullmatch(r'redis-binary-p2-[0-9a-f]{32}(?:-[a-z][a-z0-9-]{0,63})?',r) for r in roots),'root name')
actual=set()
for root in roots:
    for current,dirs,names in os.walk(safe(evidence/root),followlinks=False):
        for name in dirs:safe(Path(current)/name)
        for name in names:actual.add(safe(Path(current)/name).relative_to(evidence).as_posix())
recorded=set();total=0
for row in m['files']:
    rel=row['path'];need(rel.split('/')[0] in roots and rel not in recorded,'foreign or duplicate')
    need(identity(evidence/rel)==dict(length=row['length'],sha256=row['sha256'].lower()),'file identity:'+rel)
    recorded.add(rel);total+=row['length']
need(actual==recorded and len(actual)==m['fileCount'] and total==m['totalBytes'],'inventory')
base=evidence/package
need(identity(base/'entry-manifest.json')['sha256']=='4187bb8788e328a3d7f19e2f900d4543f8a4acb217e5e02135e3ce8c79810b3f','entry')
for row in json.loads((base/'entry-manifest.json').read_bytes()):
    need(identity(base/row['path'])==dict(length=row['length'],sha256=row['sha256'].lower()),'entry file')
receipt=dict(accepted=True,manifestSha256=expected,roots=len(roots),files=len(actual),bytes=total,entryManifestVerified=True)
with (here/'engineering-freeze-review.json').open('x',encoding='utf-8') as f:json.dump(receipt,f,indent=2)
print(json.dumps(receipt))
