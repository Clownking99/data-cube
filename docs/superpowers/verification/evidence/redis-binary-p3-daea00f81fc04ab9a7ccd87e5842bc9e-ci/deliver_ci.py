"""Explicit main-only delivery and exact-SHA CI receipts; no tag mutations."""
import argparse,datetime,hashlib,json,os,re,subprocess,uuid
from pathlib import Path
def need(ok,why):
    if not ok:raise RuntimeError(why)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def load(p):return json.loads(p.read_bytes())
def save(p,value):
    with p.open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)
parser=argparse.ArgumentParser();parser.add_argument('--package',required=True);parser.add_argument('--phase',choices=['push','status'],required=True);args=parser.parse_args()
here=Path(__file__).absolute().parent;repo=here.parents[4]
need(repo==Path('D:/Projects/朝花夕拾'),'repo')
need(re.fullmatch(r'redis-binary-p3-[0-9a-f]{32}-package',args.package),'package')
base=here.parent/args.package;intent=load(here/'delivery-intent.json')
correction=load(here/'ci-manifest.json');need(correction['accepted'],'CI correction seal')
for row in correction['files']:
    name=row['path'];need('/' not in name and '\\' not in name and name not in ('.','..'),'CI receipt path')
    data=(here/name).read_bytes();need(len(data)==row['length'] and sha(data)==row['sha256'],'CI receipt changed')
out=repo/intent['receipts'];need(out.parent==repo/'build' and re.fullmatch(r'owned-redis-binary-ci-[0-9a-f]{32}',out.name),'receipts')
out.mkdir(exist_ok=True);run=out/(args.phase+'-'+uuid.uuid4().hex);run.mkdir()
git='D:/Git/cmd/git.exe';gh='C:/Program Files/GitHub CLI/gh.exe'
def command(name,argv,proxy=False,timeout=90):
    env=os.environ.copy()
    if proxy:env.update(HTTPS_PROXY='http://127.0.0.1:7897',HTTP_PROXY='http://127.0.0.1:7897')
    started=datetime.datetime.now(datetime.timezone.utc).isoformat()
    try:
        p=subprocess.run(argv,cwd=repo,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        code=p.returncode;stdout=p.stdout;stderr=p.stderr;failure=None
    except subprocess.TimeoutExpired as e:
        code=None;stdout=e.stdout or b'';stderr=e.stderr or b'';failure='timeout'
    (run/(name+'.stdout')).write_bytes(stdout);(run/(name+'.stderr')).write_bytes(stderr)
    save(run/(name+'.json'),dict(argv=argv,started=started,ended=datetime.datetime.now(datetime.timezone.utc).isoformat(),actualExitCode=code,failure=failure,commandLocalProxy=proxy,stdoutSha256=sha(stdout),stderrSha256=sha(stderr)))
    return code,stdout
def local(*argv):
    code,raw=command('local-'+uuid.uuid4().hex,[git,*argv]);need(code==0,'local git');return raw.decode('utf-8').strip()
need(local('branch','--show-current')=='main','main only');head=local('rev-parse','HEAD')
prep=load(base/'preparation.json')
migration=load(here/'input-delta.json')
need(load(here/'review.json')['accepted'],'CI correction not reviewed')
need(set(migration)=={'.github/workflows/verify.yml','.github/workflows/release.yml'},'workflow-only migration')
for row in load(base/'main-input-identities.json'):
    rel=row['path'];need(not any(x.casefold() in ('.testagent','.git','..') for x in rel.split('/')),'input path')
    expected=row
    if rel in migration:
        need(migration[rel]['before']==dict(length=row['length'],sha256=row['sha256']),'old input mismatch')
        expected=migration[rel]['after']
    raw=(repo/rel).read_bytes();need(len(raw)==expected['length'] and sha(raw)==expected['sha256'],'tested input changed')
manifest=repo/intent['p3Manifest'];need(load(manifest)['accepted'],'P3 acceptance')
need(local('merge-base','--is-ancestor',prep['testedCommit'],head)=='','tested main ancestor')
if args.phase=='push':
    need(not (out/'push-result.json').exists(),'push already attempted')
    code,raw=command('push-direct',[git,'push','--no-follow-tags','origin','HEAD:refs/heads/main'])
    proxy=False
    if code!=0:
        proxy=True;code,raw=command('push-proxy',[git,'-c','http.proxy=http://127.0.0.1:7897','push','--no-follow-tags','origin','HEAD:refs/heads/main'])
    need(code==0,'push failed; original receipts retained')
    save(out/'push-result.json',dict(head=head,passed=True,commandLocalProxy=proxy,receiptRun=str(run)))
push=load(out/'push-result.json');need(push['passed'] and push['head']==head,'main changed since push');proxy=push['commandLocalProxy']
network_git=[git,*(['-c','http.proxy=http://127.0.0.1:7897'] if proxy else [])]
code,raw=command('remote-main',network_git+['ls-remote','origin','refs/heads/main']);need(code==0 and raw.decode().strip()==head+'\trefs/heads/main','remote main mismatch')
code,raw=command('runs',[gh,'run','list','--repo','Clownking99/data-cube','--workflow','verify.yml','--commit',head,'--limit','10','--json','databaseId,headSha,event,status,conclusion,url'],proxy)
need(code==0,'CI list unavailable');runs=[x for x in json.loads(raw) if x['headSha']==head and x['event']=='push']
if not runs:print(json.dumps(dict(head=head,ci='not yet visible',receipts=str(run))));raise SystemExit(0)
latest=max(runs,key=lambda x:x['databaseId'])
code,raw=command('ci',[gh,'run','view',str(latest['databaseId']),'--repo','Clownking99/data-cube','--json','databaseId,headSha,event,status,conclusion,url,jobs'],proxy)
need(code==0,'CI detail unavailable');ci=json.loads(raw);need(ci['headSha']==head and ci['event']=='push','CI identity')
print(json.dumps(dict(head=head,run=ci['databaseId'],status=ci['status'],conclusion=ci['conclusion'],jobs=[dict(name=x['name'],status=x['status'],conclusion=x['conclusion']) for x in ci['jobs']],url=ci['url'])))
if ci['status']!='completed':raise SystemExit(0)
code,raw=command('ci-log',[gh,'run','view',str(ci['databaseId']),'--repo','Clownking99/data-cube','--log'],proxy,180);need(code==0 and raw,'CI original logs')
need(ci['conclusion']=='success' and {x['name'] for x in ci['jobs']}=={'wrapper-validation','Test (ubuntu-latest)','Test (windows-latest)','redis-integration'} and all(x['status']=='completed' and x['conclusion']=='success' for x in ci['jobs']),'CI failed or incomplete')
receipt=dict(passed=True,head=head,remoteMain=head,testedCommit=prep['testedCommit'],p3ManifestSha256=sha(manifest.read_bytes()),ciRun=ci['databaseId'],ciUrl=ci['url'],jobs=[x['name'] for x in ci['jobs']],rawCiLogsSha256=sha(raw),fullProductAcceptance=False,tagChanged=False,workflowOnlyInputMigrationSha256=sha((here/'input-delta.json').read_bytes()),ciCorrectionManifestSha256=sha((here/'ci-manifest.json').read_bytes()),receiptRun=str(run))
save(out/'delivery-result.json',receipt)
rows=[]
for path in sorted(out.rglob('*')):
    need(not path.is_symlink() and not path.is_junction(),'linked receipt')
    if path.is_file():data=path.read_bytes();rows.append(dict(path=path.relative_to(out).as_posix(),length=len(data),sha256=sha(data)))
save(out/'manifest.json',dict(files=rows,fileCount=len(rows),totalBytes=sum(x['length'] for x in rows)))
print(json.dumps(receipt))
