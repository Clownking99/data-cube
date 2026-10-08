"""Independent audit of completed, explicitly named CI-fix runs; no Gradle."""
from pathlib import Path
import argparse,json,hashlib
from xml.etree import ElementTree as ET
from datetime import datetime,timezone

p=argparse.ArgumentParser();p.add_argument('--worker',required=True);p.add_argument('--out',required=True)
p.add_argument('--runs',nargs='+',required=True);a=p.parse_args()
w=Path(a.worker).resolve();e=w/'docs/superpowers/verification/evidence/g9-ci-fix-20261009-worker'
out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=False);(out/'.gitattributes').write_bytes(b'* -text\n')
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def safe(root,name):
    p=Path(name);assert not p.is_absolute() and '..' not in p.parts and '.testagent' not in [s.lower() for s in p.parts]
    result=(root/p).resolve();assert result.is_relative_to(root);return result
def capture(name):
    src=safe(e,name);(out/name.replace('/','-')).write_bytes(src.read_bytes());return load(src) if name.endswith('.json') else None
freeze=capture('input-freeze.json');frozen={i['path']:(i['length'],i['sha256'].upper()) for i in freeze['files']}
for item in freeze['files']:
    f=safe(w,item['path']);assert f.stat().st_size==item['length'] and sha(f)==item['sha256'].upper(),item['path']
old=load(w/'docs/superpowers/verification/evidence/g9-p2-20261008-worker/input-freeze.json')
changed=[]
for item in old['files']:
    if item['path'].startswith(('src/','test/')) and sha(safe(w,item['path']))!=item['sha256'].upper():changed.append(item['path'])
assert set(changed)=={'test/com/datacube/export/PgDumpProcessHelper.java','test/com/datacube/export/PgDumpRunnerBaselineRedTest.java','test/com/datacube/fx/AppShellTableExportJdbcShutdownTest.java'}
for name in changed:(out/Path(name).name).write_bytes(safe(w,name).read_bytes())
for script in ['Run-CI-Fix.ps1','Run-CI-Identity.ps1']:capture(script)
expected={'001-affected':(6,81,0),'002-forced-headless':(1,16,16),'003-g9-targeted':(85,1182,0)}
runs=[]
for name in a.runs:
    root=safe(e,name);command=capture(name+'/command.json');exit_info=capture(name+'/exit.json')
    assert exit_info['exitCode']==0
    before=capture(name+'/inputs-before.json');after=capture(name+'/inputs-after.json')
    assert before==after
    assert {i['path']:(i['length'],i['sha256'].upper()) for i in before['files']}==frozen
    assert all(s in command['argv'] for s in ['--offline','--no-daemon','--rerun-tasks'])
    capture(name+'/stdout.log');capture(name+'/stderr.log');summary=capture(name+'/actual-xml-summary.json')
    log=(root/'stdout.log').read_text(encoding='utf-8-sig')
    assert '> Task :test\n' in log.replace('\r\n','\n') and 'BUILD SUCCESSFUL' in log
    counts=dict(suites=0,tests=0,failures=0,errors=0,skipped=0);skips=[];xml=[]
    for f in sorted((root/'xml').glob('TEST-*.xml')):
        suite=ET.fromstring(f.read_bytes());cases=suite.findall('testcase')
        actual=dict(tests=len(cases),failures=sum(c.find('failure') is not None for c in cases),errors=sum(c.find('error') is not None for c in cases),skipped=sum(c.find('skipped') is not None for c in cases))
        assert actual=={k:int(suite.get(k,'0')) for k in actual}
        counts['suites']+=1
        for k in actual:counts[k]+=actual[k]
        skips += [dict(className=c.get('classname'),name=c.get('name'),reason=c.find('skipped').get('message')) for c in cases if c.find('skipped') is not None]
        xml.append(dict(path=f.name,length=f.stat().st_size,sha256=sha(f)))
    assert all(summary[k]==v for k,v in counts.items())
    assert counts['failures']==counts['errors']==0
    if name in expected:assert (counts['suites'],counts['tests'],counts['skipped'])==expected[name]
    if name=='002-forced-headless':
        assert '-Djava.awt.headless=true' in (root/'stderr.log').read_text(encoding='utf-8-sig')
        assert any('ui-error' in i['name'] for i in skips)
        assert all('available display' in (i['reason'] or '') for i in skips)
    runs.append(dict(run=name,counts=counts,skips=skips,xml=xml,exitCode=0,owned=command['owned']))
assert len({r['owned'] for r in runs})==len(runs)
receipt=dict(utc=datetime.now(timezone.utc).isoformat(),phase='pre-freeze progress audit',currentInputFiles=len(frozen),changedTests=changed,runs=runs,
             fullAcceptance=False,passed=True,notes=['No Linux execution claim','Headless 16 skips are not passes','Worker still owns Gradle; final freeze and full run pending'])
(out/'root-progress-verification.json').write_bytes((json.dumps(receipt,ensure_ascii=False,indent=2)+'\n').encode())
print(json.dumps(dict(inputs=len(frozen),changedTests=changed,runs=[dict(run=r['run'],counts=r['counts']) for r in runs],passed=True)))
