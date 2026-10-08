"""Root independent P2 audit. Read only worker inputs; never executes Gradle."""
import argparse, hashlib, json, re, subprocess
from pathlib import Path
from datetime import datetime, timezone
from xml.etree import ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--worker', required=True)
p.add_argument('--out', required=True)
a = p.parse_args()
w, out = Path(a.worker).resolve(), Path(a.out).resolve()
out.mkdir(parents=True, exist_ok=False)
(out / '.gitattributes').write_bytes(b'* -text\n')
prefix = 'docs/superpowers/verification/evidence/g9-p2-20261008-worker'
e = w / prefix
code = 'e7950123ed052b9c370ed355496f3333b35ad11c'
head = 'b76b75c9106709121fd542108cb3bbde944cfe15'
exclude = [':(exclude).testagent', ':(exclude).testagent/**', ':(exclude)**/.testagent/**']

def safe(root, name):
    path = Path(name)
    assert not path.is_absolute() and not any(x.lower() == '.testagent' or x == '..' for x in path.parts), name
    result = (root / path).resolve()
    assert result.is_relative_to(root), name
    return result

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def verify(root, entries):
    for item in entries:
        path = safe(root, item['path'])
        assert path.stat().st_size == item.get('length', item.get('bytes')), item['path']
        assert sha(path) == item['sha256'].upper(), item['path']
    return len(entries)

def git(*args, input=None):
    return subprocess.run(['git', '-C', str(w), *args], input=input, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=True).stdout

def capture(name):
    path = safe(e, name)
    (out / ('worker-' + name.replace('/', '-'))).write_bytes(path.read_bytes())
    return read(path) if name.endswith('.json') else None

assert git('rev-parse', 'HEAD').decode().strip() == head
assert not git('status', '--short', '--', '.', *exclude).strip()
manifest = capture('raw-manifest.json')
raw_count = verify(w, manifest['files'])
code_items = capture('code-commit-audit.json')['files']
files = [i['path'] for i in manifest['files']] + manifest['selfExcluded'] + [i['path'] for i in code_items]
assert len(files) == len(set(files))
for name in files:
    safe(w, name)
    assert '\n' not in name and '\r' not in name
requests = ''.join(head + ':' + name + '\n' for name in files).encode('utf-8')
blobs = git('cat-file', '--batch-check=%(objectname) %(objecttype)', input=requests).decode().splitlines()
assert len(blobs) == len(files)
for name, blob in zip(files, blobs):
    data = safe(w, name).read_bytes()
    expected = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() + ' blob'
    assert blob == expected, name
changed = git('diff', '--name-only', '-z', code+'^', code, '--', 'src', 'test', 'README.md', *exclude).decode().split('\0')
assert set(filter(None, changed)) == {i['path'] for i in code_items}
assert not git('diff', '--name-only', code, head, '--', 'src', 'test', 'README.md', *exclude).strip()
accepted = read(w / 'docs/superpowers/verification/evidence/g9-jdbc-export-20261008-worker/p1c-final-freeze-014/manifest.json')
accepted_code = [i for i in accepted if i['path'].startswith(('src/', 'test/')) or i['path'] == 'README.md']
verify(w, accepted_code)
freeze = capture('input-freeze.json')
input_count = verify(w, freeze['files'])
verify(e / 'input-snapshot', freeze['files'])
frozen = {i['path']: (i['length'], i['sha256'].upper()) for i in freeze['files']}
assert len(frozen) == input_count
runs = []
for name, expected in [('001-targeted', (85,1181,0)), ('002-clean-full',(345,4666,3)), ('003-buildsrc-forced',(1,8,0)), ('004-image-forced',None)]:
    root = e / name
    command, exit_info = capture(name+'/command.json'), capture(name+'/exit.json')
    assert exit_info['exitCode'] == 0
    assert all(x in command['argv'] for x in ['--offline','--no-daemon','--rerun-tasks'])
    for side in ['before','after']:
        binding = read(root / ('inputs-'+side+'.json'))
        assert binding['head'] == freeze['head']
        assert {i['path']: (i['length'],i['sha256'].upper()) for i in binding['files']} == frozen
    log = (root/'stdout.log').read_text(encoding='utf-8-sig')
    tasks = [line for line in log.splitlines() if line.startswith('> Task ') or 'BUILD SUCCESSFUL' in line]
    assert 'BUILD SUCCESSFUL' in log
    assert ('> Task :buildSrc:test' if name.startswith('003') else '> Task :jpackageImage' if expected is None else '> Task :test') in tasks
    total = dict(suites=0,tests=0,failures=0,errors=0,skipped=0)
    skips, xml_bindings = [], []
    for file in sorted((root/'xml').glob('TEST-*.xml')) if expected else []:
        suite = ET.fromstring(file.read_bytes())
        cases = suite.findall('testcase')
        counts = dict(tests=len(cases),failures=sum(c.find('failure') is not None for c in cases),
                      errors=sum(c.find('error') is not None for c in cases),skipped=sum(c.find('skipped') is not None for c in cases))
        assert counts == {k:int(suite.get(k,'0')) for k in counts}, file
        total['suites'] += 1
        for k in counts: total[k] += counts[k]
        skips.extend(c.get('classname')+'.'+c.get('name') for c in cases if c.find('skipped') is not None)
        if name.startswith(('002','003')):
            current = w / ('buildSrc/build/test-results/test' if name.startswith('003') else 'build/test-results/test') / file.name
            assert sha(current) == sha(file), file
        xml_bindings.append(dict(path=file.name,sha256=sha(file)))
    if expected:
        assert (total['suites'],total['tests'],total['skipped']) == expected
        assert total['failures'] == total['errors'] == 0
        authoritative = capture(name+'/actual-xml-summary.json')
        assert all(authoritative[k] == v for k,v in total.items())
    runs.append(dict(name=name,command=command,exitCode=0,tasks=tasks,counts=total,skips=skips,xml=xml_bindings))
    for logname in ['stdout.log','stderr.log']: capture(name+'/'+logname)
assert len({r['command']['owned'] for r in runs}) == 4
audit = capture('006-image-audit/audit.json')
image = w / 'build/jpackage/DataCube'
image_items = capture('006-image-audit/image-manifest.json')
verify(image, image_items)
verify(image, audit['artifacts'])
assert audit['passed'] and not any([audit['classLeaks'],audit['fileLeaks'],audit['optionLeaks'],audit['nativeUI'],audit['realPgDump']])
test_types = {i['path'][5:-5] for i in freeze['files'] if i['path'].startswith('test/') and i['path'].endswith('.java')}
index = (e/'006-image-audit/module-index-stdout.log').read_text(encoding='utf-8-sig')
for line in index.splitlines():
    kind = re.sub(r'\$.*$|\.class$', '', line.strip())
    assert kind not in test_types
    assert not re.search(r'^(org/junit/|org/mockito/|org/testfx/|acceptance/)|(G9Runtime|DesktopProbe|RuntimeDriverProbe|ShutdownDesktopProbe)', kind)
cfg = (image/'app/DataCube.cfg').read_text(encoding='utf-8-sig')
assert not re.search('user.home|headless|java.io.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic',cfg,re.I)
probe_inputs = capture('006-image-audit/probe-inputs.json')
verify(e, probe_inputs)
for probe in ['module-index','probe-compile','driver-discovery','xml-runtime','xlsx-runtime','xlsx-reader','g9-table-export']:
    assert capture('006-image-audit/'+probe+'-exit.json')['exitCode'] == 0
    capture('006-image-audit/'+probe+'-command.json')
    if probe != 'module-index':
        capture('006-image-audit/'+probe+'-stdout.log')
        capture('006-image-audit/'+probe+'-stderr.log')
for wrapper, code_expected in [('005-image-audit-wrapper',1),('006-image-audit-wrapper',0)]:
    assert capture(wrapper+'/exit.json')['exitCode'] == code_expected
    for part in ['command.json','stdout.log','stderr.log']: capture(wrapper+'/'+part)
binding = capture('final-source-image-binding.json')
assert binding['codeCommit'] == code and binding['imageStillMatches']
verify(image,binding['imageArtifacts'])
capture('p2-results.json')
capture('process-settlement.json')
report = w/'docs/superpowers/verification/2026-10-08-g9-table-export-worker.md'
(out/'worker-report.md').write_bytes(report.read_bytes())
receipt = dict(utc=datetime.now(timezone.utc).isoformat(),head=head,codeCommit=code,
    rawManifestFiles=raw_count,rawGitBlobFiles=len(files),codeFiles=len(code_items),acceptedCodeFiles=len(accepted_code),
    inputFiles=input_count,imageFiles=len(image_items),imageArtifacts=audit['artifacts'],runs=runs,
    firstImageAuditExit=1,finalImageAuditExit=0,passed=True,
    limitations=['3 live tests skipped, not passed','No native UI, real database or pg_dump',
                 'Driver no-connect claim follows reviewed synthetic factory and discovery source, not runtime network instrumentation',
                 'Original convenience XML skip count incorrect; independent element counts authoritative'])
(out/'root-p2-verification.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in receipt.items() if k not in ['runs','imageArtifacts']},ensure_ascii=False))
