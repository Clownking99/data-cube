"""Prepare fresh main engineering only; bind reviewed worker input and unchanged tools."""
import argparse,ast,hashlib,json,subprocess,uuid
from pathlib import Path

def need(ok,why):
    if not ok:raise RuntimeError(why)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def load(p):return json.loads(p.read_bytes())
def ident(p):
    raw=p.read_bytes();return dict(length=len(raw),sha256=sha(raw))
p=argparse.ArgumentParser();p.add_argument('--worker-package',required=True);args=p.parse_args()
here=Path(__file__).absolute().parent;repo=here.parents[4];evidence=here.parent
worker=Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
need(repo==Path('D:/Projects/朝花夕拾'),'main checkout')
need(args.worker_package.startswith('redis-binary-p2-') and '/' not in args.worker_package and '\\' not in args.worker_package,'worker package')
source=worker/'docs/superpowers/verification/evidence'/args.worker_package
def git(*argv):return subprocess.check_output(['D:/Git/cmd/git.exe',*argv],cwd=repo,text=True).strip()
need(git('branch','--show-current')=='main','main branch')
head=git('rev-parse','HEAD');dev=git('rev-parse','codex/redis-binary-key-20261010')
need(head!='a39ffc478795801195f9ee41087f2e0af02a149f','fix not integrated')
subprocess.run(['D:/Git/cmd/git.exe','merge-base','--is-ancestor',dev,head],cwd=repo,check=True)
need(load(here.parent/'redis-binary-p2-root-review-20261010/engineering-review.json')['engineeringAccepted'],'worker engineering not accepted')
sequence=load(source/'sequence-result.json');need(sequence['passed'],'worker sequence not accepted')
tm=load(source/'tool-manifest.json');need(len(tm)==12,'tool closure')
payload={}
for row in tm:
    name=row['path'];need('..' not in name.split('/') and ':' not in name and '\\' not in name,'tool path')
    raw=(repo/'scripts/verification'/name).read_bytes()
    need(ident(repo/'scripts/verification'/name)==dict(length=row['length'],sha256=row['sha256'].lower()),'main tool mismatch')
    payload[name]=raw
before=load(source/'baseline-inputs.json');rows=[];line_endings=[]
diagnostic=here/'input-newline-diagnostic.json'
need(sha(diagnostic.read_bytes())=='cc9f38b174a5607e4b68b9c03505cdeea5db166df4430ff56966faf4309174ba','reviewed newline diagnostic')
newline_rows={r['path']:r for r in load(diagnostic)}
need(len(newline_rows)==256 and all(r['crlfOnly'] and r['sameCanonicalGit'] for r in newline_rows.values()),'newline admission')
allowed=('src/','test/','buildSrc/','resources/','datacube-brand-assets/assets/','drivers/','gradle/','.github/workflows/','scripts/verification/')
single={'build.gradle','settings.gradle','README.md','gradlew','gradlew.bat','gradle.properties'}
for row in before['files']:
    rel=row['path'].replace('\\','/');need(rel in single or rel.startswith(allowed),'input allowlist')
    need(not any(s.casefold() in ('.testagent','.git','.g10-verify-blobs.ps1','..') for s in rel.split('/')),'forbidden input')
    dev_raw=(worker/rel).read_bytes();raw=(repo/rel).read_bytes()
    need(ident(worker/rel)==dict(length=row['length'],sha256=row['sha256'].lower()),'worker input changed')
    if raw!=dev_raw:
        admitted=newline_rows.get(rel)
        need(admitted and sha(raw)==admitted['mainSha'] and sha(dev_raw)==admitted['workerSha'] and raw.replace(b'\r\n',b'\n')==dev_raw.replace(b'\r\n',b'\n'),'unreviewed main change: '+rel)
        line_endings.append(dict(path=rel,worker=ident(worker/rel),main=ident(repo/rel),normalizedSha256=sha(raw.replace(b'\r\n',b'\n'))))
    rows.append(dict(path=rel,**ident(repo/rel)))
need(len(rows)==878 and len({r['path'] for r in rows})==878,'input coverage')
control=evidence/'redis-binary-p2-a3fd7057b71e4152880a33c776c42984-controls-frozen/manifest.json'
need(sha(control.read_bytes())=='fad4963106f5ae58174a056ae697377ff3a26d103a7da01c22fe5a4edb1e6772','transported controls manifest')
old=evidence/'g11-p3-2ba76738625d-package/run-sequence.py';raw=old.read_bytes()
need(sha(raw)=='14f6ff7eb55ca20eb7988a04feec59bb55ab189cfd60b94b5b82cbd071d6404d','controller template identity')
prefix='redis-binary-p3-'+uuid.uuid4().hex;package=evidence/(prefix+'-package')
s=raw.decode('utf-8').replace('\r\n','\n').replace('g11-p3-2ba76738625d',prefix)
start=s.index(" core=load('check-core.py');p2=load('check-p2.py');outer=load('run-owned.py')")
end=s.index(' image_source=None',start)
s=s[:start]+" outer=load('run-owned.py')\n"+s[end:]
s=s.replace("dict(passed=True,controls=98,rootExitControls=18,stages=results)","dict(passed=True,reusedControls=98,newControlsRun=False,stages=results)")
guard="""
def current_inputs():
 for row in json.loads((base/'main-input-identities.json').read_text(encoding='utf-8')):
  raw=(repo/row['path']).read_bytes()
  require(len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256'],'CURRENT_MAIN_INPUT_CHANGED:'+row['path'])
 require(not (repo/'gradle.properties').exists(),'OPTIONAL_INPUT_APPEARED')
"""
s=s.replace('results=[]\n',guard+'\nresults=[]\n',1)
s=s.replace("  name='"+prefix+"-'", "  current_inputs()\n  name='"+prefix+"-'",1)
s=s.replace(" save(base/'sequence-result.json',dict(passed=True", " current_inputs()\n save(base/'sequence-result.json',dict(passed=True",1)
need('core.process_checks' not in s and s.count('current_inputs()')==3,'main sequence shape')
ast.parse(s)
inputs=load(source/'inputs.json');inputs['repo']=str(repo);inputs['testedCommit']=head
need(inputs['paths']==[r['path'] for r in rows] and inputs['optionalAbsent']==['gradle.properties'],'input spec')
package.mkdir()
def save(name,value):
    with (package/name).open('x',encoding='utf-8',newline='\n') as f:json.dump(value,f,ensure_ascii=False,indent=2)
for name,raw in payload.items():
    path=package/'tools'/name;path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('xb') as f:f.write(raw)
save('tool-manifest.json',tm);save('inputs.json',inputs);save('main-input-identities.json',rows)
with (package/'run-sequence.py').open('x',encoding='utf-8',newline='\n') as f:f.write(s)
receipt=dict(schema='redis-binary-main-preparation/v1',testedCommit=head,workerCommit=dev,workerPackage=str(source),package=str(package),prefix=prefix,inputFiles=len(rows),toolFiles=12,lineEndingOnlyChanges=line_endings,controlsReused=98,controlsManifestSha256=sha(control.read_bytes()),controlsRerun=False,controllerSha256=ident(package/'run-sequence.py')['sha256'],newStages=['targeted','full','buildsrc','image','linked'],runtimeCopied=False)
save('preparation.json',receipt)
names=['tool-manifest.json','inputs.json','main-input-identities.json','run-sequence.py','preparation.json',*['tools/'+n for n in payload]]
save('entry-manifest.json',[dict(path=n,**ident(package/n)) for n in names])
print(json.dumps(receipt,ensure_ascii=False,indent=2))
