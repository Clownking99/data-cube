import hashlib, json, subprocess, datetime
from pathlib import Path
R = Path(__file__).resolve().parents[5]
O = Path(__file__).resolve().parent
W = Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(b): return hashlib.sha256(b).hexdigest()
def safe(p):
    p = p.replace('\\', '/')
    assert not Path(p).is_absolute() and not any(v in p.split('/') for v in ('..', '.testagent'))
    return p
records=[]
for phase in ('p1a', 'p1b', 'p2'):
    prefix=f'docs/superpowers/verification/evidence/g10-redis-20261009-{phase}-worker/'
    manifest=read(R / prefix / 'raw-manifest.json')
    for row in manifest['files']:
        p=safe(row['path']); assert p.startswith(prefix)
        b=(R/p).read_bytes(); assert len(b)==row['length'] and sha(b)==row['sha256'].lower(),p
        records.append((p,b))
    records.append((prefix+'raw-manifest.json',(R/prefix/'raw-manifest.json').read_bytes()))
result=subprocess.run(['git','cat-file','--batch'],input=''.join('HEAD:'+p+'\n' for p,b in records).encode(),cwd=R,capture_output=True,check=True).stdout
offset=0
for p,b in records:
    end=result.index(b'\n',offset); header=result[offset:end].split(); assert header[1]==b'blob'
    size=int(header[2]); actual=result[end+1:end+1+size]; assert actual==b,p
    offset=end+size+2
baseline=read(R/'docs/superpowers/verification/evidence/g10-redis-20261009-p2-worker/004-clean-full/inputs-before.json')
normal=[]
for row in baseline:
    p=safe(row['path']); b=(R/p).read_bytes()
    if len(b)==row['length'] and sha(b)==row['sha256'].lower(): continue
    wb=(W/p).read_bytes(); assert sha(wb)==row['sha256'].lower(),p
    assert b.replace(b'\r\n',b'\n')==wb.replace(b'\r\n',b'\n'),p
    normal.append(p)
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip()
check=subprocess.run(['git','diff','--check','HEAD^1','HEAD','--','src','test',':(exclude)**/.testagent/**'],cwd=R,capture_output=True,text=True)
assert check.returncode==0,check.stdout
receipt=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),head=head,workerHead='7ebf97d248f96f1b954406b09132e4e9d43f7a2e',rawFiles=1160,rawFilesAndManifestsGitBlobExact=len(records),inputs=len(baseline),onlyLineEndingDifferences=normal,productDiffCheck='passed',note='Raw CRLF evidence preserved by -text; whitespace lint does not rewrite frozen logs. No Gradle result reused.')
dest=O/'merge-receipt.json'; assert not dest.exists();dest.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(receipt,ensure_ascii=False))
