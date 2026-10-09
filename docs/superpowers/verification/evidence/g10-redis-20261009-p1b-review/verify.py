"""Independent read-only worker evidence verification; writes only this review folder."""
import datetime
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

WORKER = Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
BASE = 'docs/superpowers/verification/evidence/'
P1A = WORKER / (BASE + 'g10-redis-20261009-p1a-worker')
P1B = WORKER / (BASE + 'g10-redis-20261009-p1b-worker')
OUT = Path(__file__).resolve().parent

def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def digest(path):
    raw = path.read_bytes()
    return {'length': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}

def allowed_path(relative, evidence=False):
    relative = relative.replace('\\', '/')
    parts = relative.split('/')
    assert not any(p in ('.testagent', '..') for p in parts), relative
    assert not Path(relative).is_absolute(), relative
    if evidence:
        assert relative.startswith(BASE + 'g10-redis-20261009-p1'), relative
    else:
        assert relative.startswith(('src/', 'test/', 'buildSrc/')) or relative in (
            'build.gradle', 'settings.gradle', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties'
        ), relative
    return WORKER / relative

def check(record, evidence=False):
    actual = digest(allowed_path(record['path'], evidence))
    assert actual['length'] == record['length'], record['path']
    assert actual['sha256'] == record['sha256'].lower(), record['path']

manifest_counts = {}
for stage, directory in [('P1a', P1A), ('P1b', P1B)]:
    records = read_json(directory / 'raw-manifest.json')['files']
    for record in records:
        check(record, evidence=True)
    manifest_counts[stage] = len(records)

run = P1B / '007-p1b-final-targeted'
before = read_json(run / 'inputs-before.json')
after = read_json(run / 'inputs-after.json')
assert before == after
for record in after:
    check(record)

p1a_inputs = read_json(P1A / '009-p1a-frozen-targeted/inputs-after.json')
p1a_names = ['RedisResourceLimits', 'RedisException', 'RespCodec', 'RespClient', 'RedisSession', 'RedisSessionManager']
for name in p1a_names:
    relative = f'src/com/datacube/redis/{name}.java'
    record = next(r for r in p1a_inputs if r['path'].replace('\\', '/') == relative)
    check(record)

totals = dict(tests=0, failures=0, errors=0, skipped=0)
suites = []
skips = []
for path in sorted((run / 'xml').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    counts = {key: int(suite.get(key, 0)) for key in totals}
    for key in totals:
        totals[key] += counts[key]
    suites.append({'name': suite.get('name'), **counts, **digest(path)})
    for case in suite.findall('testcase'):
        if case.find('skipped') is not None:
            skips.append({'class': case.get('classname'), 'name': case.get('name')})
assert totals == dict(tests=88, failures=0, errors=0, skipped=1), totals
assert len(suites) == 15
assert skips == [{'class': 'com.datacube.redis.RedisLiveIntegrationTest', 'name': 'standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()'}], skips
assert read_json(run / 'exit.json')['exitCode'] == 0
assert '-Djava.awt.headless=false' in (run / 'stderr.log').read_text(encoding='utf-8-sig')
assert '8 actionable tasks: 8 executed' in (run / 'stdout.log').read_text(encoding='utf-8-sig')
binding = read_json(P1B / 'final-freeze/binding.json')
assert binding['verifiedInputCount'] == len(after)
assert binding['productWritesStopped'] and binding['fxActual'] == 11 and binding['fxSkipped'] == 0

receipt = {
    'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'reviewScope': 'P1b only; independent source review plus new raw evidence; root did not run Gradle',
    'workerHead': binding['head'], 'workerBranch': binding['branch'],
    'manifestFilesRehashed': manifest_counts,
    'inputBeforeAfterAndCurrentMatch': len(after), 'p1aProductsUnchanged': p1a_names,
    'run': run.name, 'actualXmlTotals': totals, 'passed': totals['tests'] - totals['skipped'],
    'suites': suites, 'skippedCases': skips,
    'fxActual': 11, 'fxSkipped': 0,
    'limitations': ['No native desktop end-to-end or real Redis acceptance', 'P2 and P3 not executed'],
}
copies = {
    'worker-manifest.json': P1B / 'raw-manifest.json',
    'worker-binding.json': P1B / 'final-freeze/binding.json',
    'worker-report-at-p1b.md': WORKER / 'docs/superpowers/verification/2026-10-09-g10-redis-worker.md',
}
for name, source in copies.items():
    target = OUT / name
    assert not target.exists(), 'Do not replace frozen review evidence'
    target.write_bytes(source.read_bytes())
(OUT / '.gitattributes').write_text('* -text\n', encoding='utf-8')
(OUT / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
files = [{'path': p.name, **digest(p)} for p in sorted(OUT.iterdir()) if p.is_file() and p.name != 'raw-manifest.json']
(OUT / 'raw-manifest.json').write_text(json.dumps({'files': files}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'manifestFiles': manifest_counts, 'currentInputs': len(after), 'totals': totals, 'fxActual': 11}))
