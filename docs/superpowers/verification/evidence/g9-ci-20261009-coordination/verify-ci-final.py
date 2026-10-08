"""Independent final worker CI correction audit, exact known paths only."""
import argparse,hashlib,json,subprocess
from pathlib import Path
from datetime import datetime,timezone
from xml.etree import ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--worker',required=True);p.add_argument('--out',required=True);a=p.parse_args()
w=Path(a.worker).resolve();e=w/'docs/superpowers/verification/evidence/g9-ci-fix-20261009-worker'
out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=False);(out/'.gitattributes').write_bytes(b'* -text\n')
head='6b8ceb93c7413b3882137d322d88b9a1cb14ef5c'
exclude=[':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**']
def git(*args,input=None):return subprocess.run(['git','-C',str(w),*args],input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def safe(root,name):
    p=Path(name);assert not p.is_absolute() and '..' not in p.parts and '.testagent' not in [x.lower() for x in p.parts]
    result=(root/p).resolve();assert result.is_relative_to(root);return result
def verify(root,entries):
    for i in entries:
        f=safe(root,i['path']);assert f.stat().st_size==i.get('length',i.get('bytes')) and sha(f)==i['sha256'].upper(),i['path']
def capture(name):
    f=safe(e,name);(out/name.replace('/','-')).write_bytes(f.read_bytes());return load(f) if name.endswith('.json') else None
assert git('rev-parse','HEAD').decode().strip()==head
assert not git('status','--short','--','.',*exclude).strip()
m=capture('raw-manifest.json');verify(w,m['files'])
files=[i['path'] for i in m['files']]+m['selfExcluded'];assert len(files)==len(set(files))
for name in files:safe(w,name)
blobs=git('cat-file','--batch-check=%(objectname) %(objecttype)',input=''.join(head+':'+p+'\n' for p in files).encode()).decode().splitlines()
assert len(blobs)==len(files)
for name,blob in zip(files,blobs):
    b=safe(w,name).read_bytes();assert blob==hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()+' blob',name
changed=set(filter(None,git('diff','--name-only','-z','b76b75c9',head,'--','.',*exclude).decode().split('\0')))
assert changed==set(files)
assert not git('diff','--name-only','e7950123',head,'--','src','build.gradle','settings.gradle','buildSrc','gradle','gradlew','gradlew.bat','README.md','datacube-brand-assets',*exclude).strip()
regular=capture('input-freeze.json');identity=capture('input-freeze-identity.json')
verify(w,regular['files']);verify(w,identity['files'])
prior=load(w/'docs/superpowers/verification/evidence/g9-p2-20261008-worker/raw-manifest.json');verify(w,prior['files'])
old_code=load(w/'docs/superpowers/verification/evidence/g9-p2-20261008-worker/input-freeze.json')
changed_tests=[i['path'] for i in old_code['files'] if i['path'].startswith(('src/','test/')) and sha(safe(w,i['path']))!=i['sha256'].upper()]
assert set(changed_tests)=={'test/com/datacube/export/PgDumpProcessHelper.java','test/com/datacube/export/PgDumpRunnerBaselineRedTest.java','test/com/datacube/fx/AppShellTableExportJdbcShutdownTest.java'}
runs=[]
for name,expected in [('001-affected',(6,81,0)),('002-forced-headless',(1,16,16)),('003-g9-targeted',(85,1182,0)),('004-cancel-identity-once',(1,4,0)),('005-clean-full',(345,4667,3))]:
    root=e/name;command=capture(name+'/command.json');assert capture(name+'/exit.json')['exitCode']==0
    before=load(root/'inputs-before.json');after=load(root/'inputs-after.json');assert before==after
    frozen=identity if name.startswith('004') else regular
    assert {i['path']:(i['length'],i['sha256']) for i in before['files']}=={i['path']:(i['length'],i['sha256']) for i in frozen['files']}
    assert all(v in command['argv'] for v in ['--offline','--no-daemon','--rerun-tasks'])
    log=(root/'stdout.log').read_text(encoding='utf-8-sig');assert '> Task :test\n' in log.replace('\r\n','\n') and 'BUILD SUCCESSFUL' in log
    if name.startswith('005'):assert 'clean' in command['argv']
    counts=dict(suites=0,tests=0,failures=0,errors=0,skipped=0);skips=[];xml=[];lines={};cancel=[]
    for f in sorted((root/'xml').glob('TEST-*.xml')):
        s=ET.fromstring(f.read_bytes());cases=s.findall('testcase');actual=dict(tests=len(cases),failures=sum(c.find('failure') is not None for c in cases),errors=sum(c.find('error') is not None for c in cases),skipped=sum(c.find('skipped') is not None for c in cases))
        assert actual=={k:int(s.get(k,'0')) for k in actual};counts['suites']+=1
        for k in actual:counts[k]+=actual[k]
        skips += [dict(name=c.get('classname')+'.'+c.get('name'),reason=c.find('skipped').get('message')) for c in cases if c.find('skipped') is not None]
        xml.append(dict(path=f.name,sha256=sha(f),length=f.stat().st_size))
        lines[f.name]=[line for n in s.findall('system-out') for line in (n.text or '').splitlines()]
        if s.get('name')=='com.datacube.fx.AppShellSqlCancelIdentityTest':cancel=actual
        if name.startswith('005'):assert sha(w/'build/test-results/test'/f.name)==sha(f)
    assert (counts['suites'],counts['tests'],counts['skipped'])==expected and counts['failures']==counts['errors']==0
    actual_summary=capture(name+'/actual-xml-summary.json');assert all(actual_summary[k]==v for k,v in counts.items())
    physical=capture(name+'/all-physical-receipts.json');assert physical['count']==len(physical['receipts'])
    for receipt in physical['receipts']:assert receipt['receipt'] in lines[receipt['xml']]
    if name in ['004-cancel-identity-once','005-clean-full']:assert cancel==dict(tests=4,failures=0,errors=0,skipped=0)
    for filename in ['stdout.log','stderr.log']:capture(name+'/'+filename)
    if name.startswith('002'):
        assert '-Djava.awt.headless=true' in (root/'stderr.log').read_text(encoding='utf-8-sig')
        assert any('ui-error' in s['name'] for s in skips) and all('available display' in s['reason'] for s in skips)
    runs.append(dict(run=name,counts=counts,skips=skips,xml=xml,physicalReceipts=physical['count'],owned=command['owned'],cancelIdentity=cancel))
assert len({r['owned'] for r in runs})==5
for name in ['final-input-binding.json','test-only-binding.json','process-settlement.json','results.json','first-ci-failures.json','windows-timeout-single-followup.json','windows-timeout-full-followup.json']:capture(name)
(out/'worker-report.md').write_bytes((w/'docs/superpowers/verification/2026-10-09-g9-ci-fix-worker.md').read_bytes())
receipt=dict(utc=datetime.now(timezone.utc).isoformat(),head=head,rawCommitFiles=len(files),priorFrozenFiles=len(prior['files']),regularInputFiles=len(regular['files']),identityInputFiles=len(identity['files']),changedTests=changed_tests,runs=runs,passed=True,
             limitations=['Linux actual helper remains pending new SHA CI','Headless skips are not passes','First Windows FX initialization timeout not reproduced; cause unknown','Product/build unchanged: no new buildSrc/image execution claimed'])
(out/'root-ci-final-verification.json').write_bytes((json.dumps(receipt,ensure_ascii=False,indent=2)+'\n').encode())
print(json.dumps({k:v for k,v in receipt.items() if k!='runs'},ensure_ascii=False))
