"""Main-only acceptance receipt: current XML, immutable inputs and actual image bytes."""
import datetime, hashlib, json, re, subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
O=Path(__file__).resolve().parent
R=O.parents[4]
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def digest(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
    return dict(length=p.stat().st_size,sha256=h.hexdigest())
def safe(p):
    p=p.replace('\\','/')
    assert not Path(p).is_absolute() and not any(v in p.split('/') for v in ('..','.testagent')),p
    return p
def check(p,r):
    d=digest(p);assert d['length']==r['length'] and d['sha256']==r['sha256'].lower(),str(p)
def save(name,data):
    p=O/name;assert not p.exists(),str(p)
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip()
assert head==read(O/'merge-receipt.json')['head']
runs=['001-complete-targeted','002-clean-full','003-buildsrc-forced','004-image-forced']
baseline=read(O/runs[0]/'inputs-before.json');assert len(baseline)==863
for row in baseline:check(R/safe(row['path']),row)
stats=[];compiled=[]
for i,name in enumerate(runs):
    P=O/name;cmd=read(P/'command.json')
    assert cmd['head']==head and cmd['branch']=='main'
    assert read(P/'inputs-before.json')==read(P/'inputs-after.json')==baseline
    assert read(P/'exit.json')['exitCode']==0
    stdout=(P/'stdout.log').read_text(encoding='utf-8-sig')
    stderr=(P/'stderr.log').read_text(encoding='utf-8-sig')
    assert 'BUILD SUCCESSFUL' in stdout
    assert '-Djava.awt.headless=false' in stderr
    assert ('4 actionable tasks: 4 executed' if i==2 else '14 actionable tasks: 14 executed' if i==3 else '9 actionable tasks: 8 executed, 1 up-to-date') in stdout
    if i<3:
        counts=dict(tests=0,failures=0,errors=0,skipped=0);skips=[];suites=0
        for x in sorted((P/'xml').glob('TEST-*.xml')):
            s=ET.parse(x).getroot();cases=s.findall('testcase');suites+=1
            assert len(cases)==int(s.get('tests'))
            assert sum(c.find('skipped') is not None for c in cases)==int(s.get('skipped'))
            assert not any(c.find('failure') is not None or c.find('error') is not None for c in cases)
            for k in counts:counts[k]+=int(s.get(k,0))
            skips.extend(dict(name=c.get('name'),classname=c.get('classname')) for c in cases if c.find('skipped') is not None)
        assert tuple(counts.values())==[(292,0,0,1),(4714,0,0,3),(8,0,0,0)][i],counts
        if i<2:
            fx=ET.parse(P/'xml/TEST-com.datacube.fx.RedisPaneBudgetTest.xml').getroot()
            assert int(fx.get('tests'))==11 and int(fx.get('skipped'))==0
        stats.append(dict(run=name,suites=suites,**counts,passed=counts['tests']-counts['skipped'],skips=skips))
    if i:
        owned=Path(cmd['owned']);assert owned.as_posix().startswith('C:/Users/hetia/AppData/Local/Temp/datacube-g10-main-')
        classes=owned/('build/buildSrc/classes/java' if i==2 else 'build/main/classes/java')
        entries=[dict(path=p.relative_to(classes).as_posix(),**digest(p)) for p in sorted(classes.rglob('*')) if p.is_file()]
        assert entries
        save(name+'-class-manifest.json',entries)
        compiled.append(dict(run=name,path=str(classes),count=len(entries)))
A=O/'005-image-linked-audit';audit=read(A/'audit.json')
assert audit['passed'] and audit['allChildProcessesExited'] and audit['head']==head
image=Path(audit['image']);assert image.as_posix().startswith('C:/Users/hetia/AppData/Local/Temp/datacube-g10-main-')
images=read(A/'image-manifest.json');assert len(images)==183
for row in images:check(image/safe(row['path']),row)
assert read(A/'inputs-before.json')==read(A/'inputs-after.json')==baseline
types={safe(r['path'])[5:-5] for r in baseline if safe(r['path']).startswith('test/') and safe(r['path']).endswith('.java')};assert len(types)==408
for line in (A/'module-index/stdout.log').read_text(encoding='utf-8-sig').splitlines():
    name=re.sub(r'\$.*$','',re.sub(r'\.class$','',line.strip()))
    assert name not in types and not re.search(r'^(org/junit/|org/mockito/|org/testfx/|acceptance/)|(G10Linked|RuntimeDriverProbe|DesktopProbe|ShutdownDesktopProbe)',name),name
assert not re.search(r'user.home|headless|java.io.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic',(image/'app/DataCube.cfg').read_text(encoding='utf-8-sig'),re.I)
assert not [r for r in images if re.search(r'\.datacube|(^|/)profiles?(/|$)|test-results|acceptance|Probe|fixture|isolation',r['path'],re.I)]
for name in ('module-index','probe-compile','driver-discovery','redis-linked'):
    e=read(A/name/'exit.json');assert e['exitCode']==0 and e['processExited'] and not e['timedOut']
redis=(A/'redis-linked/stdout.log').read_text(encoding='utf-8-sig')
assert 'trace=[SELECT 2, SELECT 7, GET k, SELECT 7, GET k, HGET k f, PING]' in redis and 'G10_LINKED_REDIS=true' in redis and 'socketsSettled=true; realServices=0' in redis
assert 'connectCalls=0' in (A/'driver-discovery/stdout.log').read_text(encoding='utf-8-sig')
receipt=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),head=head,inputCount=len(baseline),stages=stats,compiledOutputs=compiled,imageFiles=len(images),imageArtifacts=audit['artifacts'],testTypes=len(types),actualFxTestsPerProductRun=11,limitations=['No real Redis/database or full native desktop acceptance','DNS/blocking writes/native close/GC and aggregate multi-session RSS not hard-bounded','Live service skips are not passes; CI pending'])
save('receipt.json',receipt)
print(json.dumps(receipt,ensure_ascii=False))
