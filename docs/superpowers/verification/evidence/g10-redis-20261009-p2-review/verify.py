"""Independent P2 evidence audit; no Gradle execution or worker mutation."""
import datetime, hashlib, json, re
from pathlib import Path
import xml.etree.ElementTree as ET
W = Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
PREFIX = 'docs/superpowers/verification/evidence/g10-redis-20261009-'
P = W / (PREFIX + 'p2-worker')
OUT = Path(__file__).resolve().parent
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def digest(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''): h.update(block)
    return dict(length=p.stat().st_size, sha256=h.hexdigest())
def relative(value):
    value = value.replace('\\', '/')
    assert not Path(value).is_absolute() and '..' not in value.split('/') and '.testagent' not in value.split('/'), value
    return value
def check(p, r):
    actual = digest(p)
    assert actual['length'] == r['length'] and actual['sha256'] == r['sha256'].lower(), str(p)
phase_counts = {}
for phase in ('p1a', 'p1b', 'p2'):
    records = read(W / (PREFIX + phase + '-worker/raw-manifest.json'))['files']
    for r in records:
        value = relative(r['path'])
        assert value.startswith(PREFIX + phase + '-worker/'), value
        check(W / value, r)
    phase_counts[phase] = len(records)
baseline = read(P / '004-clean-full/inputs-before.json')
allowed = ('src/', 'test/', 'buildSrc/', 'resources/', 'datacube-brand-assets/assets/', 'drivers/', 'gradle/', '.github/workflows/')
for r in baseline:
    value = relative(r['path'])
    assert value.startswith(allowed) or value in ('build.gradle', 'settings.gradle', 'gradle.properties', 'README.md', 'gradlew', 'gradlew.bat'), value
    check(W / value, r)
runs = ['003-complete-targeted','004-clean-full','005-buildsrc-forced','006-image-forced']
stats = []
expected = [(292,0,0,1),(4714,0,0,3),(8,0,0,0)]
for index, name in enumerate(runs):
    folder = P / name
    assert read(folder / 'inputs-before.json') == read(folder / 'inputs-after.json') == baseline
    assert read(folder / 'exit.json')['exitCode'] == 0
    if index > 2: continue
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    suites, skips = [], []
    for x in sorted((folder / 'xml').glob('TEST-*.xml')):
        s = ET.parse(x).getroot(); cases = s.findall('testcase')
        assert len(cases) == int(s.get('tests'))
        assert sum(c.find('skipped') is not None for c in cases) == int(s.get('skipped'))
        for k in counts: counts[k] += int(s.get(k, 0))
        suites.append(dict(name=s.get('name'), **digest(x)))
        skips.extend(dict(name=c.get('name'), classname=c.get('classname')) for c in cases if c.find('skipped') is not None)
    assert tuple(counts.values()) == expected[index], counts
    stats.append(dict(run=name, totals=counts, passed=counts['tests']-counts['skipped'], suites=suites, skips=skips))
for name in runs[:2]:
    assert '-Djava.awt.headless=false' in (P / name / 'stderr.log').read_text(encoding='utf-8-sig')
assert '4 actionable tasks: 4 executed' in (P / runs[2] / 'stdout.log').read_text(encoding='utf-8-sig')
assert '14 actionable tasks: 14 executed' in (P / runs[3] / 'stdout.log').read_text(encoding='utf-8-sig')
A = P / '007-image-linked-audit'
audit = read(A / 'audit.json'); image = Path(audit['image'])
assert image.as_posix().replace('//','/').startswith('C:/Users/hetia/AppData/Local/Temp/datacube-g10-p2-')
images = read(A / 'image-manifest.json')
for r in images: check(image / relative(r['path']), r)
assert read(A / 'inputs-before.json') == read(A / 'inputs-after.json') == baseline
child_results = {}
for label in ('module-index','probe-compile','driver-discovery','redis-linked'):
    child = read(A / label / 'exit.json')
    assert child['exitCode'] == 0 and child['processExited'] and not child['timedOut'], child
    child_results[label] = child
types = {relative(r['path'])[5:-5] for r in baseline if relative(r['path']).startswith('test/') and relative(r['path']).endswith('.java')}
assert len(types) == 408
index = (A / 'module-index/stdout.log').read_text(encoding='utf-8-sig').splitlines()
leaks = []
for line in index:
    name = re.sub(r'\$.*$', '', re.sub(r'\.class$', '', line.strip()))
    if name in types or re.search(r'^(org/junit/|org/mockito/|org/testfx/|acceptance/)|(G10Linked|RuntimeDriverProbe|DesktopProbe|ShutdownDesktopProbe)', name): leaks.append(name)
assert not leaks, leaks
cfg = (image / 'app/DataCube.cfg').read_text(encoding='utf-8-sig')
assert not re.search(r'user.home|headless|java.io.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic', cfg, re.I)
assert not [r for r in images if re.search(r'\.datacube|(^|/)profiles?(/|$)|test-results|acceptance|Probe|fixture|isolation', r['path'], re.I)]
redis = (A / 'redis-linked/stdout.log').read_text(encoding='utf-8-sig')
assert 'G10_LINKED_REDIS=true' in redis and 'trace=[SELECT 2, SELECT 7, GET k, SELECT 7, GET k, HGET k f, PING]' in redis
assert 'socketsSettled=true; realServices=0' in redis
binding = read(P / 'final-freeze/binding.json')
assert binding['productTestGradleStopped'] and not binding['P3Executed']
receipt = dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), workerHead=binding['head'], phaseManifestsRehashed=phase_counts,
               currentInputCount=len(baseline), stages=stats, imageFilesRehashed=len(images), testTypesChecked=len(types), classLeaks=leaks,
               childResults=child_results, imageArtifacts=audit['artifacts'], rootRanGradle=False,
               limits=['No real Redis/DB or complete native desktop acceptance','P3 main validation and CI still pending'])
copies = {'worker-manifest.json': P/'raw-manifest.json', 'worker-binding.json': P/'final-freeze/binding.json',
          'worker-report-at-p2.md': W/'docs/superpowers/verification/2026-10-09-g10-redis-worker.md',
          'worker-image-audit.json': A/'audit.json', 'worker-process-settlement.json': P/'final-freeze/process-settlement.json'}
for name, source in copies.items():
    assert not (OUT/name).exists(), 'Frozen review files must not be replaced'
    (OUT/name).write_bytes(source.read_bytes())
(OUT/'.gitattributes').write_text('* -text\n',encoding='utf-8')
(OUT/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
items = [dict(path=p.name,**digest(p)) for p in sorted(OUT.iterdir()) if p.is_file() and p.name != 'raw-manifest.json']
(OUT/'raw-manifest.json').write_text(json.dumps(dict(files=items),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(manifests=phase_counts, inputs=len(baseline), stages=[s['totals'] for s in stats], imageFiles=len(images), testTypes=len(types))))
