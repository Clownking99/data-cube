import ast, hashlib, json, os, re
from pathlib import Path
base=Path(__file__).absolute().parent;evidence=base.parent
tree=ast.parse((base/'controller.py').read_bytes());prefix=[]
for node in tree.body:
    if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='results' for t in node.targets):break
    prefix.append(node)
ns={'__file__':str(base/'controller.py')};exec(compile(ast.Module(body=prefix,type_ignores=[]),str(base/'controller.py'),'exec'),ns)
load=ns['load'];save=ns['save'];require=ns['require'];guard=ns['guarded'];digest=ns['digest']
require(load(base/'operator-result.json')['actualExitCode']==0,'actual continuation exit')
sequence=load(base/'sequence-result.json');require(sequence['passed'] is True and sequence['controls']==98 and sequence['rootExitControls']==18,'full controller outcome')
require([r['mode'] for r in sequence['stages']]==['targeted','full','buildsrc','image','linked'],'all five stages')
ns['identities']()
binding=load(base/'controls-binding.json');require(digest(guard(Path(binding['manifest'])))==binding['manifestSha256'],'control freeze')
resume=load(base/'resume-targeted.json');require(digest(guard(Path(resume['manifest'])))==resume['manifestSha256'],'original targeted freeze')
roots=[base];records=[];tools=load(base/'tool-manifest.json');toolset={r['path'] for r in tools};config=load(base/'config.json');repo=base.parents[4]
for row in sequence['stages']:
    mode=row['mode']
    spec_path=Path(resume['spec']) if mode=='targeted' else base/(mode+'-spec.json')
    spec=load(guard(spec_path));stage=guard(Path(spec['stageEvidence']));out=guard(Path(spec['out']))
    require(spec['repo']==str(repo) and spec['mode']==mode and spec['fixture'] is None and spec['outerFixture'] is None,'stage spec binding')
    require(re.fullmatch('g11-p2-eng-[0-9a-f]{32}-'+mode,stage.name) is not None and stage.parent==evidence and out==evidence/(stage.name+'-owner'),'actual UUID roots')
    require(spec['tools']==str((Path(resume['originalPackage']) if mode=='targeted' else base)/'tools'),'frozen tools spec')
    for key in ('jdk','cache','pwsh','python','runtimeParent'):require(spec[key]==config[key],'actual config')
    actual=load(Path(resume['actualExit']) if mode=='targeted' else base/(mode+'-actual-exit.json'))['actualExitCode']
    audited=ns['audit_stage'](spec,actual);require(audited['scope']==row['scope'],'actual scope identity')
    scope=load(guard(Path(row['scope'])));owned=guard(Path(scope['owned']))
    require(owned.parent==stage and set(scope['tools'])==toolset,'scope ownership and 12 tools')
    for tool in tools:
        item=scope['tools'][tool['path']]
        require(item['length']==tool['length'] and item['sha256'].lower()==tool['sha256'],'tool binding')
        require(Path(item['source'])==repo/'scripts'/'verification'/tool['path'] and Path(item['frozen'])==owned/'tools'/tool['path'],'tool paths')
        ns['identity'](dict(path='tools/'+tool['path'],length=tool['length'],sha256=tool['sha256']),owned)
    require(Path(scope['paths']['RuntimeParent'])==Path(config['runtimeParent']) and Path(scope['runtime']).parent==Path(config['runtimeParent']) and re.fullmatch('datacube-g11-[0-9a-f]{32}',Path(scope['runtime']).name) is not None,'excluded runtime binding')
    executables=[];bound=load(owned/'scope-bound.json') if (owned/'scope-bound.json').exists() else scope
    et=ns['module']('evidence_tools.py')
    for filename,expected in bound['executables'].items():
        p=et.admitted_path(filename);et.no_links(p)
        actual=dict(path=str(p),length=p.stat().st_size,sha256=digest(p),role=expected['role'])
        require(actual['length']==expected['length'] and actual['sha256']==expected['sha256'].lower(),'executable changed')
        executables.append(actual)
    save(base/(mode+'-executables-after.json'),executables)
    types=load(owned/'test-types.json');require(types['sourceCount']==types['typeCount']==len(types['mappings'])==408,'408 test types')
    record=dict(mode=mode,scope=str(Path(row['scope'])),spec=str(spec_path),stageEvidence=str(stage),out=str(out),runtimeExcluded=scope['runtime'],processes=len(list(owned.glob('processes/*/process-receipt.json'))),executables=len(executables),executionOrigin='immutable-first-controller' if mode=='targeted' else 'continuation-controller',actualExit=actual)
    if mode in ('targeted','full','buildsrc'):record['xml']=load(base/(mode+'-xml-audit.json'))
    if mode=='image':
        inventory=load(owned/'image-manifest.json');record['artifactFiles']=len(inventory['files']);record['artifactCore']=inventory['core'];record['artifactManifestSha256']=digest(owned/'image-manifest.json')
    if mode=='linked':
        imageaudit=load(owned/'image-audit.json');require(imageaudit['passed'] is True and imageaudit['testTypeCount']==408 and not imageaudit['classLeaks'] and not imageaudit['missingClasses'] and not imageaudit['fileLeaks'] and imageaudit['optionLeaks'] is False,'image classes audit')
        require(load(owned/'source-image-image-manifest.json')==load(owned/'image-before.json')==load(owned/'image-after.json'),'image inventory unchanged')
        record['imageAudit']=imageaudit
    records.append(record)
    if mode!='targeted':roots.extend([stage,out])
save(base/'engineering-audit.json',dict(schema='engineering-audit/v1',passed=True,controlsManifestSha256=binding['manifestSha256'],targetedManifestSha256=resume['manifestSha256'],originalControllerActualExit=1,continuationControllerActualExit=0,targetedRepeated=False,stages=records))
save(base/'archive-roots.json',dict(schema='engineering-actual-spec-roots/v1',roots=[str(p) for p in roots],records=records,excluded='All runtime trees and prior frozen roots are excluded; prior controls and targeted are bound by exact manifest SHA.'))
files=[]
for root in roots:
    guard(root)
    for current,dirs,names in os.walk(root,followlinks=False):
        current=guard(Path(current))
        for name in dirs:guard(current/name)
        for name in names:
            p=guard(current/name);files.append(dict(path=p.relative_to(evidence).as_posix(),length=p.stat().st_size,sha256=digest(p)))
require(len(files)==len({f['path'] for f in files}),'duplicate archive files')
target=evidence/(base.name+'-frozen');require(not target.exists(),'already sealed');target.mkdir()
manifest=dict(schema='engineering-freeze/v1',passed=True,controlsManifestSha256=binding['manifestSha256'],targetedManifestSha256=resume['manifestSha256'],originalControllerActualExit=1,continuationControllerActualExit=0,targetedRepeated=False,roots=[p.name for p in roots],fileCount=len(files),totalBytes=sum(f['length'] for f in files),files=sorted(files,key=lambda f:f['path']))
save(target/'manifest.json',manifest)
print(json.dumps(dict(roots=len(roots),files=len(files),bytes=manifest['totalBytes'],manifest=str(target/'manifest.json'),sha256=digest(target/'manifest.json'),counts=[dict(mode=r['mode'],counts=r.get('xml',{}).get('counts')) for r in records])))
