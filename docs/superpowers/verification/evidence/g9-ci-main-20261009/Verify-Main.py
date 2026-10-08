"""Independently count new main XML and bind source/runtime artifacts to accepted P2."""
import hashlib,json,re,subprocess
from pathlib import Path
from xml.etree import ElementTree as ET
from datetime import datetime,timezone

e=Path(__file__).resolve().parent
repo=e.parents[4]
worker=repo/'docs/superpowers/verification/evidence/g9-p2-20261008-worker'
def load(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def verify(root,entries):
    for i in entries:
        p=root/i['path']
        assert '.testagent' not in p.parts and p.resolve().is_relative_to(root.resolve())
        assert p.stat().st_size==i['length'] and sha(p)==i['sha256'].upper(),i['path']

freeze=load(e/'input-freeze.json')
verify(repo,freeze['files'])
frozen={i['path']:(i['length'],i['sha256']) for i in freeze['files']}
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
assert head==freeze['head']
exclusions=[':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**']
assert not subprocess.check_output(['git','diff','--name-only','6b8ceb93',head,'--','src','test','README.md',*exclusions],cwd=repo)
runs=[]
for name,expected in [('001-affected',(7,85,0)),('002-headless',(1,16,16)),('003-clean-full',(345,4667,3)),('004-buildsrc-forced',(1,8,0)),('005-image-forced',None)]:
    root=e/name
    command=load(root/'command.json')
    assert command['head']==head
    assert load(root/'exit.json')['exitCode']==0
    assert all(s in command['argv'] for s in ['--offline','--no-daemon','--rerun-tasks'])
    for side in ['before','after']:
        bound=load(root/('inputs-'+side+'.json'))
        assert bound['head']==head
        assert {i['path']:(i['length'],i['sha256']) for i in bound['files']}==frozen
    log=(root/'stdout.log').read_text(encoding='utf-8-sig')
    tasks=[s for s in log.splitlines() if s.startswith('> Task ') or 'BUILD SUCCESSFUL' in s]
    assert 'BUILD SUCCESSFUL' in log
    assert ('> Task :jpackageImage' if expected is None else '> Task :buildSrc:test' if name.startswith('004') else '> Task :test') in tasks
    totals=dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
    skips=[]
    for f in sorted((root/'xml').glob('TEST-*.xml')) if expected else []:
        s=ET.fromstring(f.read_bytes());cases=s.findall('testcase')
        counts=dict(tests=len(cases),failures=sum(c.find('failure') is not None for c in cases),errors=sum(c.find('error') is not None for c in cases),skipped=sum(c.find('skipped') is not None for c in cases))
        assert counts=={k:int(s.get(k,'0')) for k in counts}
        totals['suites']+=1
        for k in counts: totals[k]+=counts[k]
        skips.extend(c.get('classname')+'.'+c.get('name') for c in cases if c.find('skipped') is not None)
        if name.startswith(('003','004')):
            current=repo/('buildSrc/build/test-results/test' if name.startswith('004') else 'build/test-results/test')/f.name
            assert sha(current)==sha(f)
    if expected:
        assert (totals['suites'],totals['tests'],totals['skipped'])==expected
        assert totals['failures']==totals['errors']==0
        actual=load(root/'actual-xml-summary.json')
        assert all(actual[k]==v for k,v in totals.items())
        convenience=load(root/'test-summary.json')
        assert convenience['skipped']==totals['skipped']
    if name=='002-headless':
        assert '-Djava.awt.headless=true' in (root/'stderr.log').read_text(encoding='utf-8-sig')
        assert any('ui-error' in item for item in skips)
    runs.append(dict(name=name,command=command,exitCode=0,tasks=tasks,counts=totals,skips=skips))
assert len({r['command']['owned'] for r in runs})==5
audit=load(e/'006-image-audit/audit.json')
assert audit['passed'] and not any([audit['classLeaks'],audit['fileLeaks'],audit['optionLeaks'],audit['nativeUI'],audit['realPgDump']])
assert load(e/'006-image-audit-wrapper/exit.json')['exitCode']==0
image=repo/'build/jpackage/DataCube'
manifest=load(e/'006-image-audit/image-manifest.json')
verify(image,manifest)
verify(image,audit['artifacts'])
previous=load(worker/'006-image-audit/audit.json')
assert {i['path']:(i['length'],i['sha256']) for i in audit['artifacts']}=={i['path']:(i['length'],i['sha256']) for i in previous['artifacts']}
verify(e,load(e/'006-image-audit/probe-inputs.json'))
for probe in ['module-index','probe-compile','driver-discovery','xml-runtime','xlsx-runtime','xlsx-reader','g9-table-export']:
    assert load(e/('006-image-audit/'+probe+'-exit.json'))['exitCode']==0
index=(e/'006-image-audit/module-index-stdout.log').read_text(encoding='utf-8-sig')
test_types={i['path'][5:-5] for i in freeze['files'] if i['path'].startswith('test/') and i['path'].endswith('.java')}
for line in index.splitlines():
    kind=re.sub(r'\$.*$|\.class$','',line.strip())
    assert kind not in test_types
    assert not re.search(r'^(org/junit/|org/mockito/|org/testfx/|acceptance/)|(G9Runtime|DesktopProbe|RuntimeDriverProbe|ShutdownDesktopProbe)',kind)
assert not re.search('user.home|headless|java.io.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic',(image/'app/DataCube.cfg').read_text(),re.I)
receipt=dict(utc=datetime.now(timezone.utc).isoformat(),head=head,codeCommit='6b8ceb93c7413b3882137d322d88b9a1cb14ef5c',
             inputFiles=len(frozen),runs=runs,imageFiles=len(manifest),imageArtifacts=audit['artifacts'],
             mainWorkerArtifactsEqual=True,linkedAudit=audit,passed=True,
             limitations=['3 live tests and 16 separate headless cases skipped, not passed','No real DB/pg_dump/native UI','Mock driver ownership cannot prove actual driver/MVCC/network/OS filesystem behavior'])
path=e/'main-verification.json';assert not path.exists()
path.write_bytes((json.dumps(receipt,ensure_ascii=False,indent=2)+'\n').encode())
print(json.dumps(dict(head=head,runs=[dict(name=r['name'],counts=r['counts']) for r in runs],imageFiles=len(manifest),mainWorkerArtifactsEqual=True,passed=True)))
