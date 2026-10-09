"""Verify exact live skip identities and executed Redis FX cases in fresh P3 XML."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


base = Path(__file__).absolute().parent
expected = {
    ('com.datacube.redis.RedisLiveIntegrationTest', 'standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()'):
        'set DATACUBE_REDIS_HOST and DATACUBE_REDIS_PASSWORD to run live Redis smoke test',
    ('com.datacube.schemadiff.SchemaDiffLiveIntegrationTest', 'oracleSafeDeploymentConvergesInDisposableSchemas()'):
        'Schema Diff relational live smoke requires the explicit write gate and the complete provider environment set',
    ('com.datacube.schemadiff.SchemaDiffLiveIntegrationTest', 'postgresqlSafeDeploymentConvergesInDisposableSchemas()'):
        'Schema Diff relational live smoke requires the explicit write gate and the complete provider environment set',
}
results = []
for mode in ['targeted', 'full', 'buildsrc']:
    review = json.loads((base/(mode+'-review.json')).read_text())
    skips = review['skips']
    require(len(skips) == {'targeted': 1, 'full': 3, 'buildsrc': 0}[mode], 'skip count')
    ids = {(row['class'], row['name']) for row in skips}
    required_ids = set(expected) if mode == 'full' else {next(iter(expected))} if mode == 'targeted' else set()
    require(ids == required_ids, 'unexpected skip identity')
    for row in skips:
        require(expected[(row['class'], row['name'])] in row['reason'], 'unexpected skip reason')
    cases = []
    if mode != 'buildsrc':
        root = Path(review['scope'])/'xml'
        files = [item['path'] for item in review['xml'] if item['path'].endswith('.RedisPaneBudgetTest.xml')]
        require(len(files) == 1, 'Redis FX XML missing')
        for case in ET.fromstring((root/files[0]).read_bytes()).findall('testcase'):
            require(all(case.find(tag) is None for tag in ['failure', 'error', 'skipped']), 'Redis FX case not passed')
            cases.append(case.get('name'))
        require(len(cases) == 11, 'Redis FX case count')
    results.append(dict(mode=mode, passed=review['passedCases'], liveSkips=skips, redisNativeFxPassed=cases,
                        closeSequenceUnitNotCountedAsNative=True))
with (base/'case-review.json').open('x', encoding='utf-8') as stream:
    json.dump(dict(schema='root-case-review/v1', stages=results, fullDesktopAcceptance=False), stream, ensure_ascii=False, indent=2)
print(json.dumps([dict(mode=r['mode'], passed=r['passed'], skipped=len(r['liveSkips']), nativeFx=len(r['redisNativeFxPassed'])) for r in results]))
