"""Independent exact skips and current binary-key/FX case coverage; does not execute tools under review."""
import argparse,hashlib,json,re
from pathlib import Path
import xml.etree.ElementTree as ET

def need(v,m):
    if not v:raise RuntimeError(m)
p=argparse.ArgumentParser();p.add_argument('--repo',required=True);args=p.parse_args()
repo=Path(args.repo);base=Path(__file__).absolute().parent
need(repo in [Path('D:/Projects/朝花夕拾'),Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')],'unknown repo')
skips={('com.datacube.redis.RedisLiveIntegrationTest','standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()'):'set DATACUBE_REDIS_HOST and DATACUBE_REDIS_PASSWORD to run live Redis smoke test',('com.datacube.schemadiff.SchemaDiffLiveIntegrationTest','oracleSafeDeploymentConvergesInDisposableSchemas()'):'Schema Diff relational live smoke requires the explicit write gate and the complete provider environment set',('com.datacube.schemadiff.SchemaDiffLiveIntegrationTest','postgresqlSafeDeploymentConvergesInDisposableSchemas()'):'Schema Diff relational live smoke requires the explicit write gate and the complete provider environment set'}
classes=['com.datacube.redis.RedisBinaryKeyTest','com.datacube.redis.RedisBinaryKeySessionTest','com.datacube.fx.RedisPaneBudgetTest','com.datacube.fx.AppShellWorkspaceShutdownTest']
required={'binaryKeySelectionAndFiveEditorsSendOriginalKeyBytesAndInvalidateOldBinding()','binaryScanOverflowAndInstallFailureKeepOriginalTreeAndCursor()','delayedBinaryValueCannotOverwriteDifferentRawSelection()'}
results=[]
for mode in ['targeted','full','buildsrc']:
    r=json.loads((base/(mode+'-review.json')).read_bytes());raw_skips=r['skips']
    expected=set(skips) if mode=='full' else {next(iter(skips))} if mode=='targeted' else set()
    need(len(raw_skips)==len(expected) and {(x['class'],x['name']) for x in raw_skips}==expected,'skip identities')
    for row in raw_skips:need(skips[(row['class'],row['name'])] in row['reason'],'skip reason')
    rows=[]
    if mode!='buildsrc':
        scope=Path(r['scope'])
        for cls in classes:
            source=repo/'test'/(cls.replace('.','/')+'.java');raw_source=source.read_bytes()
            names={n+'()' for n in re.findall(r'@Test\s+(?:public\s+)?void\s+(\w+)\s*\(',raw_source.decode('utf-8'))}
            need(names,'no current methods')
            filename='TEST-'+cls+'.xml';need(filename in {x['path'] for x in r['xml']},'missing audited XML')
            raw=(scope/'xml'/filename).read_bytes();expected_xml=next(x for x in r['xml'] if x['path']==filename)
            need(len(raw)==expected_xml['length'] and hashlib.sha256(raw).hexdigest()==expected_xml['sha256'],'XML changed since independent review')
            cases=ET.fromstring(raw).findall('testcase')
            need(len(cases)==len(names) and {x.get('name') for x in cases}==names,'current declared case coverage')
            need(all(x.get('classname')==cls and all(x.find(s) is None for s in ['failure','error','skipped']) for x in cases),'case did not pass')
            if cls.endswith('RedisPaneBudgetTest'):need(required<=names,'required binary FX regression missing')
            if cls.endswith('AppShellWorkspaceShutdownTest'):need('fixtureSeedWaitsForActualInFlightWorkspacePublication()' in names,'deterministic fixture regression missing')
            rows.append({'class':cls,'passed':len(cases),'cases':sorted(names),'sourceSha256':hashlib.sha256(raw_source).hexdigest(),'xmlSha256':hashlib.sha256(raw).hexdigest()})
    results.append({'mode':mode,'passed':r['passedCases'],'liveSkips':raw_skips,'exactCurrentCases':rows})
receipt={'schema':'redis-binary-case-review/v1','stages':results,'fullDesktopAcceptance':False,'systemClipboardAccessed':False}
with (base/'case-review.json').open('x',encoding='utf-8') as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
print(json.dumps([{'mode':x['mode'],'passed':x['passed'],'skipped':len(x['liveSkips']),'currentClassCounts':{r['class']:r['passed'] for r in x['exactCurrentCases']}} for x in results]))