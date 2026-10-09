import hashlib, importlib.util, json, pathlib, re, sys, traceback, uuid
base=pathlib.Path(__file__).absolute().parent
repo=base.parents[4]; evidence=base.parent
def require(value, reason):
    if not value: raise RuntimeError('ENGINEERING_REFUSAL:'+reason)
def load(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def save(path,value): path.write_bytes((json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode())
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def module(name):
    spec=importlib.util.spec_from_file_location('frozen_'+name.replace('.','_'),base/'tools'/name)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
def guarded(path,root=evidence):
    path=pathlib.Path(path)
    require(path.is_absolute() and path.is_relative_to(root),'outside admitted root')
    for part in path.parts: require(part.casefold() not in ('.testagent','.git','.g10-verify-blobs.ps1') and part not in ('.','..'),'forbidden path')
    for ancestor in [path,*path.parents]:
        if ancestor==root.parent: break
        require(not ancestor.is_symlink() and not ancestor.is_junction(),'linked path')
    return path
def identity(row,root):
    path=guarded(root/row['path'],root)
    require(path.stat().st_size==row['length'] and digest(path)==row['sha256'].lower(),'identity:'+str(path))
def identities():
    for row in load(base/'tool-manifest.json'):
        identity(row,base/'tools');identity(row,repo/'scripts'/'verification')
    for row in load(base/'entry-manifest.json'): identity(row,base)
def controls():
    binding=load(base/'controls-binding.json'); path=guarded(pathlib.Path(binding['manifest']))
    require(digest(path)==binding['manifestSha256'],'control manifest')
    manifest=load(path)
    require(manifest['passed'] is True and manifest['count']==98 and manifest['engineering'] is False,'controls acceptance')
    for row in manifest['files']: identity(row,evidence)
    control_base=guarded(pathlib.Path(binding['package']))
    require(load(control_base/'tool-manifest.json')==load(base/'tool-manifest.json'),'same 12 tools')
    groups=load(control_base/'controller-result.json')['groups']
    require([(g['kind'],g['count']) for g in groups]==[('python',21),('process',21),('root-exit',18),('policy-role-image',31),('outer',7)],'98 matrix including 18 root exits')
    for group in groups:
        value=load(guarded(pathlib.Path(group['result'])))
        require(value['passed'] is True and len(value['cases'])+len(value.get('rolePolicy',[]))==group['count'],'control group')
    save(base/'controls-verified.json',dict(passed=True,manifestSha256=binding['manifestSha256'],count=98,rootExitCount=18,tools=load(base/'tool-manifest.json')))
def stream(row):
    path=guarded(pathlib.Path(row['path']))
    require(path.stat().st_size==row['length'] and digest(path)==row['sha256'].lower(),'stream bytes')
    require(row.get('partial') is False,'partial stream')
def process(path):
    value=load(path); directory=path.parent
    require(value['schema']=='process/v1' and value['status']=='passed' and value['primaryFailure'] is None,'process status:'+str(path))
    require(value['rootExited'] is True and value['rootExitCode']==value['observedRootExitCode']==0 and value['rootExitEvidenceError'] is None,'root0')
    require(value['hostExited'] is True and value['hostExitCode']==0 and value['ownedSettlement']=='complete' and value['termination']['requested'] is False,'settlement')
    event=load(directory/'root-exit.json'); ident=load(directory/'root-identity.json'); host=load(directory/'host-receipt.json'); proof=value['rootExitProof']
    require(set(ident)=={'pid','startTimeUtc'} and set(event)=={'schema','pid','startTimeUtc','exitCode','observationTick','elapsedMs'},'root event shape')
    require(event['schema']=='root-exit/v1' and proof['schema']=='root-exit-proof/v1','proof schema')
    require(type(event['pid']) is int and event['pid']>0 and event['pid']!=value['hostPid'],'root PID')
    require(re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{7}Z',event['startTimeUtc']) is not None,'time identity')
    for key in ('pid','startTimeUtc'):
        require(ident[key]==event[key]==proof[key],'identity/event/proof')
    require(type(event['observationTick']) is int and event['observationTick']>0 and event['observationTick']==proof['observationTick']==host['rootObservationTick'],'observation tick')
    require(event['exitCode']==proof['exitCode']==host['rootExitCode']==0 and event['elapsedMs']>=0,'actual root code')
    require(host==value['hostReceipt'] and host['rootPid']==event['pid'] and host['rootStartTimeUtc']==event['startTimeUtc'] and host['started'] is True and host['rootExited'] is True and host['rootEventPublished'] is True,'host root proof')
    for captured in value['capturedDescendants']:
        require(captured['exitObserved'] is True,'captured settlement')
        if captured['pid']==event['pid']: require(captured['startTimeUtc']==event['startTimeUtc'] and captured['exitCode']==0,'captured actual root')
    for name in ('stdout','stderr'):
        row=host[name];require(row['eof'] is True and row['truncated'] is False and row['error'] is None and row['bytesRead']==row['bytesWritten']==value[name]['length'],'host EOF')
    for name in ('stdout','stderr','host-stdout','host-stderr'): stream(value[name])
    return dict(path=str(path),pid=event['pid'],rootExitProof=proof,streams=4)
def audit_stage(spec, actual_code):
    stage=guarded(pathlib.Path(spec['stageEvidence']));out=guarded(pathlib.Path(spec['out']))
    require(actual_code==0,'actual outer return')
    outer=load(out/'result.json')
    require(outer['actualExitCode']==0 and outer['firstFailure'] is None and outer['assigned'] is True and outer['settlement']['actualJobQueryObservedEmpty'] is True and outer['streamsCompleted'] is True and outer['toolsUnchanged'] is True,'outer result')
    for row in outer['streams']:
        stream(row); require(row['eof'] is True and not row['overflow'] and row['error'] is None,'outer EOF')
    results=list(stage.glob('*/stage-result.json')); require(len(results)==1,'unique stage')
    inner=results[0];scope=load(inner.with_name('scope.json'));owned=guarded(inner.parent)
    require(scope['owned']==str(owned) and scope['paths']['Repo']==str(repo) and scope['paths']['EvidenceRoot']==str(stage) and scope['stage']==spec['mode'],'actual scope binding')
    require(re.fullmatch('[0-9a-f]{32}',owned.name) is not None,'scope UUID')
    value=load(inner);require(value['status']=='passed' and value['primaryFailure'] is None,'stage status')
    process_rows=[process(p) for p in sorted(owned.glob('processes/*/process-receipt.json'))]
    require(len(process_rows)>0,'no process proof')
    before=load(owned/'inputs-before.json');after=load(owned/'inputs-after.json')
    baseline=load(base/'baseline-inputs.json')
    require(before==after==baseline and len(before['files'])==875,'875 input identity')
    types=load(owned/'test-types.json')
    counts=None
    if spec['mode'] in ('targeted','full','buildsrc'):
        import xml.etree.ElementTree as ET
        counts=dict(suites=0,tests=0,passed=0,skipped=0,failures=0,errors=0);skips=[];native=[]
        for path in sorted(owned.glob('xml/TEST-*.xml')):
            suite=ET.fromstring(path.read_bytes());counts['suites']+=1
            for case in suite.findall('testcase'):
                counts['tests']+=1
                status='errors' if case.find('error') is not None else 'failures' if case.find('failure') is not None else 'skipped' if case.find('skipped') is not None else 'passed';counts[status]+=1
                if status=='skipped': skips.append(dict(classname=case.get('classname'),name=case.get('name'),reason=case.find('skipped').get('message','')))
                if case.get('classname')=='com.datacube.fx.RedisPaneBudgetTest': native.append(dict(classname=case.get('classname'),name=case.get('name'),status=status))
        for key,count in counts.items():require(value['testResults'][key]==count,'raw XML count')
        require(counts['passed']>0 and counts['failures']==counts['errors']==0,'XML failure')
        if spec['mode'] in ('targeted','full'): require(len(native)==11 and all(x['status']=='passed' for x in native),'11 native RedisPane')
        save(base/(spec['mode']+'-xml-audit.json'),dict(counts=counts,skips=skips,nativeRedisPane=native))
    if spec['mode']=='linked':
        require(set(value['linkedProcesses'])=={'module-index','probe-compile','driver-discovery','redis-linked'},'four linked commands')
        require('connectCalls=0' in value['artifact']['driverOutput'] and 'realServices=0' in value['artifact']['redisOutput'] and value['artifact']['realServices']==0,'synthetic linked probes')
    save(base/(spec['mode']+'-stage-audit.json'),dict(passed=True,inner=str(inner),outer=str(out/'result.json'),processes=process_rows,counts=counts))
    return dict(mode=spec['mode'],outer=str(out/'result.json'),inner=str(inner),scope=str(inner.with_name('scope.json')))

results=[]
try:
    identities();controls()
    config=load(base/'config.json');inputs=load(base/'inputs.json');require(len(inputs['paths'])==875,'input count')
    et=module('evidence_tools.py');baseline=et.snapshot(str(repo),inputs['paths'],inputs['testedCommit'],inputs['optionalAbsent']);save(base/'baseline-inputs.json',baseline)
    outer=module('run-owned.py');image_source=None
    for mode,budget in [('targeted',660),('full',1000),('buildsrc',240),('image',660),('linked',200)]:
        identities();require(et.snapshot(str(repo),inputs['paths'],inputs['testedCommit'],inputs['optionalAbsent'])==baseline,'pre-stage inputs')
        stage=evidence/('g11-p2-eng-'+uuid.uuid4().hex+'-'+mode);out=evidence/(stage.name+'-owner')
        spec=dict(repo=str(repo),tools=str(base/'tools'),out=str(out),stageEvidence=str(stage),mode=mode,inputSpec=str(base/'inputs.json'),jdk=config['jdk'],cache=config['cache'],pwsh=config['pwsh'],python=config['python'],runtimeParent=config['runtimeParent'],imageSourceScope=image_source,deadlineSeconds=budget,fixture=None,processDeadlineMs=0,settleMs=5000,streamCap=33554432,outerFixture=None)
        path=base/(mode+'-spec.json');save(path,spec);print('START '+mode,flush=True)
        saved=sys.argv
        try:sys.argv=[str(outer.__file__),'--spec',str(path)];code=outer.main()
        finally:sys.argv=saved
        save(base/(mode+'-actual-exit.json'),dict(actualExitCode=code))
        row=audit_stage(spec,code);results.append(row);save(base/'progress.json',results)
        identities();print('PASS '+mode,flush=True)
        if mode=='image':image_source=row['scope']
    save(base/'sequence-result.json',dict(passed=True,controls=98,rootExitControls=18,stages=results));print('ALL ENGINEERING PASS',flush=True)
except BaseException as error:
    save(base/'sequence-result.json',dict(passed=False,stages=results,failure=repr(error)));traceback.print_exc();sys.exit(1)
