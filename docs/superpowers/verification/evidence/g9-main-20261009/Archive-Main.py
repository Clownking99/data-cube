"""Freeze/stage only root-owned G9 P3 files; audit literal raw bytes against Git."""
import argparse,hashlib,json,subprocess,os
from pathlib import Path
from datetime import datetime,timezone

p=argparse.ArgumentParser();p.add_argument('mode',choices=['freeze-stage','verify']);a=p.parse_args()
e=Path(__file__).resolve().parent;repo=e.parents[4]
def git(*args,input=None):
    return subprocess.run(['git',*args],cwd=repo,input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout
def allowed(name):
    n=Path(name);assert not n.is_absolute() and '..' not in n.parts and '.testagent' not in [s.lower() for s in n.parts]
    assert (repo/n).resolve().is_relative_to(repo)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def verify_blobs(files,ref):
    for name in files:allowed(name)
    blobs=git('cat-file','--batch-check=%(objectname) %(objecttype)',input=''.join(ref+':'+f+'\n' for f in files).encode()).decode().splitlines()
    assert len(blobs)==len(files)
    for name,blob in zip(files,blobs):
        b=(repo/name).read_bytes();assert blob==hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()+' blob',name
if a.mode=='freeze-stage':
    assert not (e/'raw-manifest.json').exists()
    files=[]
    for directory,children,names in os.walk(e):
        children[:]=[c for c in children if c.lower()!='.testagent']
        for name in names:files.append((Path(directory)/name).relative_to(repo).as_posix())
    authored=['docs/superpowers/plans/2026-10-08-g9-table-export-reliability.md',
       'docs/superpowers/verification/2026-10-08-g9-table-export-coordination.md',
       'docs/handoffs/2026-09-23-product-maturity-goal-handoff.md',
       'docs/superpowers/plans/2026-09-23-product-maturity-roadmap.md',
       'docs/superpowers/verification/evidence/g9-table-export-20261008-coordination/prepare-main.py']
    files=sorted(set(files+authored))
    for name in authored:
        f=repo/name;f.write_bytes(f.read_bytes().replace(b'\r\n',b'\n'))
    entries=[]
    for name in files:
        allowed(name);f=repo/name
        entries.append(dict(path=name,length=f.stat().st_size,sha256=sha(f)))
    manifest=dict(utc=datetime.now(timezone.utc).isoformat(),files=entries,selfExcluded=(e/'raw-manifest.json').relative_to(repo).as_posix())
    (e/'raw-manifest.json').write_bytes((json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode())
    files.append(manifest['selfExcluded'])
    hashes=git('hash-object','-w','--no-filters','--stdin-paths',input=''.join(json.dumps(f,ensure_ascii=False)+'\n' for f in files).encode()).decode().splitlines()
    assert len(hashes)==len(files)
    records=b''.join(('100644 '+blob+'\t'+name+'\0').encode() for name,blob in zip(files,hashes))
    git('update-index','-z','--index-info',input=records)
    verify_blobs(files,'')
    git('diff','--cached','--check','--',*authored)
    print(json.dumps(dict(staged=len(files),rawAndIndexMatch=True)))
else:
    manifest=json.loads((e/'raw-manifest.json').read_text())
    for item in manifest['files']:
        allowed(item['path']);f=repo/item['path']
        assert f.stat().st_size==item['length'] and sha(f)==item['sha256'],item['path']
    files=[i['path'] for i in manifest['files']]+[manifest['selfExcluded']]
    verify_blobs(files,'HEAD')
    print(json.dumps(dict(commit=git('rev-parse','HEAD').decode().strip(),files=len(files),rawAndCommitMatch=True)))
