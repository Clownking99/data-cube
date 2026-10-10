import ast,hashlib,json,os,re
from pathlib import Path
target=Path(__file__).absolute().parent;base=target.with_name(target.name.removesuffix('-frozen'));evidence=base.parent
body=[]
for node in ast.parse((base/'controller.py').read_bytes()).body:
    if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='results' for t in node.targets):break
    body.append(node)
ns={'__file__':str(base/'controller.py')};exec(compile(ast.Module(body=body,type_ignores=[]),str(base/'controller.py'),'exec'),ns)
load=ns['load'];save=ns['save'];require=ns['require'];guard=ns['guarded'];digest=ns['digest'];repo=ns['repo']
require(load(base/'operator-result.json')['actualExitCode']==0,'operator actual exit')
sequence=load(base/'sequence-result.json');require(sequence['passed'] is True,'all engineering passed')
require([r['mode'] for r in sequence['stages']]==['targeted','full','buildsrc','image','linked'],'five stage sequence')
ns['identities']();binding=load(base/'controls-binding.json');require(digest(guard(Path(binding['manifest'])))==binding['manifestSha256'],'accepted control SHA')
roots=[base];records=[];tools=load(base/'tool-manifest.json');config=load(base/'config.json');firsttypes=None
for row in sequence['stages']:
    mode=row['mode'];spec_path=base/(mode+'-spec.json');spec=load(spec_path);stage=guard(Path(spec['stageEvidence']));out=guard(Path(spec['out']))
    require(spec['repo']==str(repo) and spec['mode']==mode and spec['fixture'] is None and spec['outerFixture'] is None,'spec identity')
    require(re.fullmatch('redis-binary-p2-[0-9a-f]{32}-'+mode,stage.name) is not None and stage.parent==evidence and out==evidence/(stage.name+'-owner'),'actual named UUID root')
    require(spec['tools']==str(base/'tools') and spec['inputSpec']==str(base/'inputs.json'),'frozen inputs/tools spec')
    for key in ('jdk','cache','pwsh','python','runtimeParent'):require(spec[key]==config[key],'config identity')
    actual=load(base/(mode+'-actual-exit.json'))['actualExitCode'];audited=ns['audit_stage'](spec,actual);require(audited['scope']==row['scope'],'actual scope')
    scope=load(Path(row['scope']));owned=guard(Path(scope['owned']));require(owned.parent==stage and set(scope['tools'])=={r['path'] for r in tools},'ownership/tool set')
    for tool in tools:
        item=scope['tools'][tool['path']];require(item['length']==tool['length'] and item['sha256'].lower()==tool['sha256'],'tool binding')
        require(Path(item['source'])==repo/'scripts/verification'/tool['path'] and Path(item['frozen'])==owned/'tools'/tool['path'],'tool paths')
        ns['identity'](dict(path='tools/'+tool['path'],length=tool['length'],sha256=tool['sha256']),owned)
    require(Path(scope['runtime']).parent==Path(config['runtimeParent']) and re.fullmatch('datacube-g11-[0-9a-f]{32}',Path(scope['runtime']).name) is not None,'excluded runtime')
    bound=load(owned/'scope-bound.json') if (owned/'scope-bound.json').exists() else scope;executables=[];et=ns['module']('evidence_tools.py')
    for filename,expected in bound['executables'].items():
        p=et.admitted_path(filename);et.no_links(p);got=dict(path=str(p),length=p.stat().st_size,sha256=digest(p),role=expected['role'])
        require(got['length']==expected['length'] and got['sha256']==expected['sha256'].lower(),'actual executable identity');executables.append(got)
    save(base/(mode+'-executables-after.json'),executables)
    types=load(owned/'test-types.json');require(types['sourceCount']==types['typeCount']==len(types['mappings']),'actual test type mapping')
    if firsttypes is None:firsttypes=types
    require(types==firsttypes,'same actual test mappings in every stage')
    record=dict(mode=mode,spec=str(spec_path),scope=row['scope'],stageEvidence=str(stage),out=str(out),runtimeExcluded=scope['runtime'],actualExit=actual,processes=len(list(owned.glob('processes/*/process-receipt.json'))),testTypeCount=types['typeCount'])
    if mode in ('targeted','full','buildsrc'):record['xml']=load(base/(mode+'-xml-audit.json'))
    if mode=='image':
        inventory=load(owned/'image-manifest.json');record['artifactFiles']=len(inventory['files']);record['artifactManifestSha256']=digest(owned/'image-manifest.json')
    if mode=='linked':
        audit=load(owned/'image-audit.json');require(audit['passed'] is True and audit['testTypeCount']==types['typeCount'] and not audit['classLeaks'] and not audit['missingClasses'] and not audit['fileLeaks'] and audit['optionLeaks'] is False,'image test class/options audit')
        require(load(owned/'source-image-image-manifest.json')==load(owned/'image-before.json')==load(owned/'image-after.json'),'image identity unchanged')
        index=owned/'processes/module-index/stdout.bin';require('com/datacube/redis/RedisKey.class' in index.read_text(encoding='utf-8'),'RedisKey.class missing from actual module index')
        record['redisKeyClassPresent']=True;record['moduleIndexSha256']=digest(index);record['imageAudit']=audit
    records.append(record);roots.extend([stage,out])
ns['identities']();inputs=load(base/'inputs.json');et=ns['module']('evidence_tools.py');require(et.snapshot(str(repo),inputs['paths'],inputs['testedCommit'],inputs['optionalAbsent'])==load(base/'baseline-inputs.json'),'final inputs')
save(base/'engineering-audit.json',dict(passed=True,controlsManifestSha256=binding['manifestSha256'],testTypeCount=firsttypes['typeCount'],stages=records))
save(base/'archive-roots.json',dict(roots=[str(p) for p in roots],records=records,derivedFromActualSpecs=True,excluded='Independent runtime/home/temp/build and prior control roots excluded; image and source identities bound by original receipts.'))
files=[]
for root in roots:
    guard(root)
    for current,dirs,names in os.walk(root,followlinks=False):
        current=guard(Path(current))
        for name in dirs:guard(current/name)
        for name in names:
            p=guard(current/name);files.append(dict(path=p.relative_to(evidence).as_posix(),length=p.stat().st_size,sha256=digest(p)))
require(len(files)==len({f['path'] for f in files}),'duplicate archive file');require(not (target/'manifest.json').exists(),'already sealed')
manifest=dict(schema='redis-binary-engineering-freeze/v1',passed=True,controlsManifestSha256=binding['manifestSha256'],auditScriptSha256=digest(Path(__file__)),roots=[p.name for p in roots],fileCount=len(files),totalBytes=sum(f['length'] for f in files),files=sorted(files,key=lambda f:f['path']))
save(target/'manifest.json',manifest)
print(json.dumps(dict(roots=len(roots),files=len(files),bytes=manifest['totalBytes'],manifest=str(target/'manifest.json'),sha256=digest(target/'manifest.json'),testTypes=firsttypes['typeCount'],counts=[dict(mode=r['mode'],counts=r.get('xml',{}).get('counts')) for r in records])))
