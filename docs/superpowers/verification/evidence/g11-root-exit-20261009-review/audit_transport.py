"""Independent committed-byte review; long working paths are checked with .NET."""
import hashlib
import json
from pathlib import Path
import subprocess

repo=Path('D:/Projects/朝花夕拾');here=Path(__file__).absolute().parent
source='a59b3f15ae21c1514acfc5060b15ab41fe90c94b'
revision='258a03db12648b821e85273adcc16b25e1efc099'
baseline='e368a1b16bdee226925b363f06b4dc4f003c1f0f'
ev='docs/superpowers/verification/evidence/'
delivery=ev+'g11-p2-delivery-4830a1fcc6894ab9ab51e10622c99cf4/'
ex=['.',':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**']
def need(v,m):
    if not v:raise RuntimeError(m)
def git(*a,data=None):return subprocess.run(['D:/Git/cmd/git.exe',*a],cwd=repo,input=data,capture_output=True,check=True).stdout
def blob(path):return git('show',revision+':'+path)
def ident(raw):return dict(length=len(raw),sha256=hashlib.sha256(raw).hexdigest())
def allowed(path):
    need(path and ':' not in path and '\\' not in path and all(p and p not in ('.','..') and p.casefold() not in ('.testagent','.git','.g10-verify-blobs.ps1') and p==p.rstrip(' .') for p in path.split('/')),'forbidden path')
    return path
need(git('rev-parse',revision+'^').decode().strip()==source and git('rev-parse',source+'^').decode().strip()==baseline,'two commits')
payload_raw=blob(delivery+'delivery-payload.json');paths_raw=blob(delivery+'delivery-paths.nul')
need(ident(payload_raw)['sha256']=='684ff1f08697749db777c8b340877d83f39c332aaf573e3f859d307105dc5483','payload identity')
need(ident(paths_raw)['sha256']=='a3c2be4e8540ffef29e072eeed449bcca93e64d7fd6ed1b1d4f3834e4c2b773e','path list identity')
payload=json.loads(payload_raw)
paths=[allowed(p) for p in paths_raw.decode().split('\0') if p]
need(len(paths)==len(set(paths))==3320 and payload['fileCount']==len(payload['files'])==3318,'delivery count')
expected={allowed(x['path']):x for x in payload['files']}
need(len(expected)==3318,'duplicate payload')
expected[delivery+'delivery-payload.json']=dict(path=delivery+'delivery-payload.json',**ident(payload_raw))
expected[delivery+'delivery-paths.nul']=dict(path=delivery+'delivery-paths.nul',**ident(paths_raw))
need(set(paths)==set(expected),'exact path list')
manifests=[('g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935-rejected','95a136cd033d36fdcee971106b3e84bc6d01cf056cbaa352099e52ff7fac3624',736,True),('g11-p2-rx2-e46234f8c9fa48e9b504156a3f0c9054-frozen','b2de1c70097c8baa033030a469dbca6e10a2d133b4ee924d2325e08ffaedcce9',1357,False),('g11-p2-eng-11965af76c694c6798f2074fff74d8bf-rejected','9cc7a935cbfe5ac1d7c4826c9cdb5e4ceff99be3bb88438ace25002b5bb3552f',213,False),('g11-p2-eng2-861d631887ec4c9790f3deb3f062ecae-frozen','02cb7a0b73bd7647b3ddaead8fdf2395d5bc51cc4c1f5ea8368f82c1da77e479',995,False)]
frozen_paths=set()
for name,sha,count,repo_relative in manifests:
    path=ev+name+'/manifest.json';raw=blob(path);need(ident(raw)['sha256']==sha,'frozen manifest');m=json.loads(raw)
    need(len(m['files'])==m['fileCount']==count,'frozen count');frozen_paths.add(path)
    for row in m['files']:
        rel=allowed(row['path'] if repo_relative else ev+row['path'])
        need(rel not in frozen_paths and rel in expected,'missing/duplicate frozen file')
        need(expected[rel]['length']==row['length'] and expected[rel]['sha256'].lower()==row['sha256'].lower(),'frozen identity mismatch')
        frozen_paths.add(rel)
extra_roots=['g11-p2-root-exit-source-abddf454863f4846921eea16c475b20d','g11-p2-root-exit-final-source-0c7e63f31b1043aab36e321208837958','g11-p2-root-exit-controller-review-b1c95f19622f4eabb00d399aa91c6e33','g11-p2-eng-native-review-3c86ac32bf824e729d81c7ef043c885a']
report='docs/superpowers/verification/2026-10-09-g11-root-exit-observation-worker.md'
attr='docs/superpowers/verification/.gitattributes'
extra_exact={report,attr,*(delivery+x for x in ['prepare-delivery.ps1','audit-blobs.ps1','delivery-payload.json','delivery-paths.nul'])}
extras=set(expected)-frozen_paths
need(all(p in extra_exact or any(p.startswith(ev+n+'/') and '/' not in p[len(ev+n+'/'):] for n in extra_roots) for p in extras),'foreign delivery extra')
need(ident(blob(report))['sha256']=='bf22248eb2e02b8ce1e3b246f1057d11d37112ce5ab855085af39a6052b15b6b','report identity')
need(blob(attr).decode().splitlines()==git('show',baseline+':'+attr).decode().splitlines()+['2026-10-09-g11-root-exit-observation-worker.md -text'],'attribute change')
tools=json.loads((here/'path-fix-admission.json').read_bytes())['toolFiles']
four={'OwnedProcessHost.ps1','VerificationCore.psm1','check-core.py','run-stage.ps1'}
for row in tools:
    if row['path'] in four:
        path='scripts/verification/'+row['path'];expected[path]=dict(path=path,length=row['length'],sha256=row['sha256'])
changed=set(git('diff','--name-only','-z',baseline,revision,'--',*ex).decode().rstrip('\0').split('\0'))
need(changed==set(expected),'whole branch changed paths')
raw=git('cat-file','--batch',data=''.join(revision+':'+p+'\n' for p in sorted(expected)).encode());pos=0;rows=[]
for path in sorted(expected):
    end=raw.index(b'\n',pos);header=raw[pos:end].decode().split();pos=end+1
    need(len(header)==3 and header[1]=='blob','blob kind');size=int(header[2]);data=raw[pos:pos+size];pos+=size
    need(raw[pos:pos+1]==b'\n','blob separator');pos+=1
    row=expected[path];actual=ident(data)
    need(actual['length']==row['length'] and actual['sha256']==row['sha256'].lower(),'blob identity: '+path)
    rows.append(dict(path=path,oid=header[0],**actual))
need(pos==len(raw),'batch trailing bytes')
receipt=dict(schema='root-transport-blobs/v1',revision=revision,sourceCommit=source,baseline=baseline,committedBlobsAccepted=True,files=len(rows),frozenFiles=3301,deliveryExtras=sorted(extras),reportSha256=ident(blob(report))['sha256'],diskTransportPending=True,entries=rows)
with (here/'transport-blobs-review.json').open('x',encoding='utf-8') as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
print(json.dumps({k:v for k,v in receipt.items() if k not in ('entries','deliveryExtras')},ensure_ascii=False))
