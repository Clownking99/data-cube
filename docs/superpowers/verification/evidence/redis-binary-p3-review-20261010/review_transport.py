"""Compare scoped commit blobs with audited worker bytes and optional main checkout."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--main',action='store_true');args=parser.parse_args()
here=Path(__file__).absolute().parent;repo=here.parents[4];worker=Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
git='D:/Git/cmd/git.exe';source='c33b9ef45c680b86976919b68b81d4d61b9230c7';commit='207782399ec41fb61f39cf30eb4416d8f28a6746'
payload='docs/superpowers/verification/evidence/redis-binary-p2-a6abe27a63754fe6aa1e55417e88432f-transport/payload.json'
prefix='docs/superpowers/verification/evidence/'
def need(ok,why):
    if not ok:raise RuntimeError(why)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def run(*argv):return subprocess.check_output([git,*argv],cwd=repo)
def paths(c):return set(run('diff-tree','--no-commit-id','--name-only','-z','-r',c,'--','.',':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**').decode('utf-8').strip('\0').split('\0'))
raw=(worker/payload).read_bytes();need(sha(raw)=='f002490e9ee79bd392d3888b269b98e446a6ae7f4fae9e8d54b30f36e3c7c2eb','payload hash')
m=json.loads(raw);rows=m['files'];need(len(rows)==m['fileCount']==3076 and len({r['path'] for r in rows})==3076,'payload coverage')
special={'docs/superpowers/verification/.gitattributes','docs/superpowers/verification/evidence/.gitattributes','docs/superpowers/verification/2026-10-10-redis-binary-key-worker.md'}
for row in rows:
    rel=row['path'];need(rel in special or re.fullmatch(re.escape(prefix)+r'redis-binary-p2-[0-9a-f]{32}(?:-[a-z][a-z0-9-]{0,63})?/.+',rel),'transport allowlist')
    need(not any(p.casefold() in ('.testagent','.git','..','.g10-verify-blobs.ps1') for p in rel.split('/')),'forbidden path')
need(paths(commit)=={r['path'] for r in rows}|{payload},'evidence commit path mismatch')
source_manifest=worker/(prefix+'redis-binary-p2-26c5f5b6b23a4107b8e9e50d228ad823-source/source-identities.json')
raw_source=source_manifest.read_bytes();need(sha(raw_source)=='c96a8f7b06277e844cfdfa75d8af68f6e59baaf434c31912fcde61c56d84a9ef','source checkpoint')
sources=json.loads(raw_source)['files'];need(len(sources)==16 and paths(source)=={r['path'] for r in sources},'source commit coverage')
checks=[*[(source,r) for r in sources],*[(commit,r) for r in rows],(commit,dict(path=payload,length=len(raw),sha256=sha(raw)))]
proc=subprocess.Popen([git,'cat-file','--batch'],cwd=repo,stdin=subprocess.PIPE,stdout=subprocess.PIPE)
endings=[]
try:
    for rev,row in checks:
        rel=row['path'];disk=(worker/rel).read_bytes();need(len(disk)==row['length'] and sha(disk)==row['sha256'].lower(),'worker changed:'+rel)
        proc.stdin.write((rev+':'+rel+'\n').encode('utf-8'));proc.stdin.flush();header=proc.stdout.readline().decode().strip().split();need(len(header)==3 and header[1]=='blob','blob header')
        blob=proc.stdout.read(int(header[2]));need(proc.stdout.read(1)==b'\n' and blob==disk,'git blob differs:'+rel)
        if args.main:
            current=(repo/rel).read_bytes()
            if current!=disk:
                need(rev==source and rel.startswith(('src/','test/')) and current.replace(b'\r\n',b'\n')==disk.replace(b'\r\n',b'\n'),'main bytes differ:'+rel)
                endings.append(rel)
finally:proc.stdin.close();need(proc.wait(timeout=20)==0,'cat-file exit')
receipt=dict(accepted=True,sourceCommit=source,evidenceCommit=commit,sourceFiles=len(sources),evidenceFiles=len(rows)+1,payloadSha256=sha(raw),workerAndGitBytesEqual=True,mainChecked=args.main,lineEndingOnlyChanges=endings)
with (here/('transport-main.json' if args.main else 'transport-git.json')).open('x',encoding='utf-8') as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
print(json.dumps(receipt,ensure_ascii=False))
