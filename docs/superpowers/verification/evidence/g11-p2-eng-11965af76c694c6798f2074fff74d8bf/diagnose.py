import ast, json, hashlib, os
from pathlib import Path
import xml.etree.ElementTree as ET
base=Path(__file__).absolute().parent
tree=ast.parse((base/'controller.py').read_bytes())
prefix=[]
for node in tree.body:
    if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='results' for t in node.targets): break
    prefix.append(node)
ns={'__file__':str(base/'controller.py')}
exec(compile(ast.Module(body=prefix,type_ignores=[]),str(base/'controller.py'),'exec'),ns)
ns['identities']()
spec=ns['load'](base/'targeted-spec.json');stage=ns['guarded'](Path(spec['stageEvidence']));out=ns['guarded'](Path(spec['out']))
inner=list(stage.glob('*/stage-result.json'))
if len(inner)!=1:raise RuntimeError('unique scope')
owned=inner[0].parent
processes=[ns['process'](p) for p in sorted(owned.glob('processes/*/process-receipt.json'))]
counts=dict(suites=0,tests=0,passed=0,skipped=0,failures=0,errors=0);native=[];other=[];skips=[]
for path in sorted(owned.glob('xml/TEST-*.xml')):
    suite=ET.fromstring(path.read_bytes());counts['suites']+=1
    for case in suite.findall('testcase'):
        counts['tests']+=1
        status='errors' if case.find('error') is not None else 'failures' if case.find('failure') is not None else 'skipped' if case.find('skipped') is not None else 'passed';counts[status]+=1
        row=dict(classname=case.get('classname'),name=case.get('name'),status=status)
        if case.get('classname')=='com.datacube.fx.RedisPaneBudgetTest':native.append(row)
        elif case.get('classname','').startswith('com.datacube.fx.RedisPane'):other.append(row)
        if status=='skipped':row['reason']=case.find('skipped').get('message','');skips.append(row)
value=ns['load'](inner[0]);outer=ns['load'](out/'result.json')
for key,val in counts.items():ns['require'](value['testResults'][key]==val,'XML summary')
ns['require'](len(native)==11 and all(row['status']=='passed' for row in native),'native actual11')
ns['require'](outer['actualExitCode']==0 and outer['settlement']['actualJobQueryObservedEmpty'] is True and outer['streamsCompleted'] is True,'outer settled')
for row in outer['streams']:ns['stream'](row)
ns['require'](ns['load'](owned/'inputs-before.json')==ns['load'](owned/'inputs-after.json')==ns['load'](base/'baseline-inputs.json'),'875 unchanged')
report=dict(schema='engineering-first-failure-diagnosis/v1',executionRepeated=False,controllerActualExit=1,targetedActualExit=0,outerJobEmpty=True,counts=counts,nativeRedisPane=native,otherRedisPane=other,skips=skips,processes=processes,cause='Controller used RedisPane classname prefix, counted 11 native RedisPaneBudgetTest plus 1 separate nonnative RedisPaneCloseSequenceTest, then required 11. Shared tools and actual tests passed.',minimalCorrection="Replace startswith('com.datacube.fx.RedisPane') with equality to 'com.datacube.fx.RedisPaneBudgetTest' for native-only row collection.")
ns['save'](base/'failure-diagnosis.json',report)
roots=[base,stage,out]
files=[]
for root in roots:
    ns['guarded'](root)
    for current,dirs,names in os.walk(root,followlinks=False):
        current=Path(current);ns['guarded'](current)
        for name in dirs:ns['guarded'](current/name)
        for name in names:
            p=ns['guarded'](current/name);files.append(dict(path=p.relative_to(base.parent).as_posix(),length=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
target=base.parent/(base.name+'-rejected')
if target.exists():raise RuntimeError('already sealed')
target.mkdir()
manifest=dict(schema='engineering-rejected-freeze/v1',accepted=False,engineeringComplete=False,roots=[r.name for r in roots],fileCount=len(files),totalBytes=sum(f['length'] for f in files),files=sorted(files,key=lambda f:f['path']))
ns['save'](target/'manifest.json',manifest)
print(json.dumps(dict(counts=counts,native=len(native),other=len(other),processes=len(processes),files=len(files),bytes=manifest['totalBytes'],manifest=str(target/'manifest.json'),sha256=hashlib.sha256((target/'manifest.json').read_bytes()).hexdigest())))
