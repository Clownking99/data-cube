import hashlib, json, os
from pathlib import Path

target = Path(__file__).absolute().parent
base = target.with_name(target.name.removesuffix('-frozen'))
evidence = base.parent
repo = Path(json.loads((base/'spec.json').read_bytes())['repo'])
def require(value, message):
    if not value: raise RuntimeError(message)
def load(path): return json.loads(path.read_bytes())
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def save(path, data): path.write_bytes((json.dumps(data, ensure_ascii=False, indent=2)+'\n').encode())
def guard(path):
    require(path.is_relative_to(evidence), 'outside evidence: '+str(path))
    for part in path.relative_to(evidence).parts:
        require(part.casefold() not in ('.testagent','.git','.g10-verify-blobs.ps1'), 'forbidden component')
    for ancestor in [path, *path.parents]:
        if ancestor == evidence.parent: break
        require(not ancestor.is_symlink() and not ancestor.is_junction(), 'linked evidence')

operator = load(base/'operator-result.json')
require(operator['passed'] is True and operator['engineering'] is False, 'operator failed')
require([r['name'] for r in operator['stages']]==['boundary-python','boundary-powershell','controller','collision','archive'], 'operator stages')
for row in operator['stages']:
    require(row['actualExit']==0 and row['failure'] is None and row['settlement']['actualJobQueryObservedEmpty'] is True,'operator actual exit/settlement')
for name,count in [('boundary-python-result.json',34),('boundary-powershell-result.json',36)]:
    boundary=load(base/name);require(boundary['passed'] is True and len(boundary['cases'])==count,'boundary result')
collision=load(base/'collision-result.json');require(collision['passed'] is True and collision['actualRefusal']=='RUN_COLLISION','actual collision')
result = load(base/'controller-result.json')
require(result['passed'] is True and result['count']==98 and result['engineering'] is False, 'controller failed')
require([g['count'] for g in result['groups']]==[21,21,18,31,7], 'group counts')
for group in result['groups']:
    p=Path(group['result']); guard(p)
    data=load(p)
    require(data['passed'] is True and len(data['cases'])+len(data.get('rolePolicy',[]))==group['count'], 'group raw result')
for row in load(base/'entry-manifest.json'):
    p=base/row['path']; guard(p)
    require(p.stat().st_size==row['length'] and digest(p)==row['sha256'].lower(), 'entry identity')
for row in load(base/'tool-manifest.json'):
    p=repo/'scripts'/'verification'/row['path']
    require(p.stat().st_size==row['length'] and digest(p)==row['sha256'], 'working tool changed')
roots=[Path(p) for p in load(base/'archive-roots.json')['roots']]
require(len(roots)==len(set(roots)), 'duplicate root')
raw_checks=0
stream_checks=0
for group in result['groups']:
    if group['kind'] not in ('process','root-exit'): continue
    for case in load(Path(group['result']))['cases']:
        require(case['neighborStillRunning'] is True, 'neighbor lost')
        require(case['outerSettlement']['actualJobQueryObservedEmpty'] is True, 'outer not empty')
        receipt=case['receipt']
        if 'processReceipt' in receipt: receipt=receipt['processReceipt']
        if receipt.get('schema')=='process/v1' and receipt.get('status')=='passed':
            proof=receipt['rootExitProof']; host=receipt['hostReceipt']
            require(receipt['rootExited'] is True and receipt['rootExitCode']==0 and proof['exitCode']==0, 'success proof')
            require(host['rootExited'] is True and host['rootExitCode']==0 and host['rootEventPublished'] is True, 'host proof')
        if group['kind']=='root-exit' and case['mode'] in ('exit-tail-zero','exit-tail-seven','exit-late-identity'):
            require(receipt['hostReceipt']['tailBoundaryForced'] is True, 'tail not forced')
        raw_checks+=1
        for value in receipt.values():
            if isinstance(value,dict) and {'path','sha256','length'} <= value.keys():
                p=Path(value['path']); guard(p)
                require(p.stat().st_size==value['length'] and digest(p)==value['sha256'].lower(), 'stream bytes')
                stream_checks+=1
save(base/'independent-audit.json', {'schema':'controls-audit/v1','passed':True,'count':98,'processCasesAudited':raw_checks,'streamsAudited':stream_checks,'engineering':False,'entryManifestSha256':digest(base/'entry-manifest.json'),'toolManifestSha256':digest(base/'tool-manifest.json')})
files=[]
for root in roots:
    guard(root); require(root.is_dir(), 'missing root')
    for current, dirs, names in os.walk(root, followlinks=False):
        current=Path(current); guard(current)
        for name in dirs: guard(current/name)
        for name in names:
            p=current/name; guard(p)
            files.append({'path':p.relative_to(evidence).as_posix(),'length':p.stat().st_size,'sha256':digest(p)})
require(len(files)==len({f['path'] for f in files}), 'overlapping roots')
require(not (target/'manifest.json').exists(), 'seal exists')
manifest={'schema':'controls-freeze/v1','passed':True,'count':98,'engineering':False,'baseCommit':load(base/'spec.json')['testedBaseCommit'],'boundaryCases':70,'postNormalCollisionCases':1,'auditScriptSha256':digest(Path(__file__)),'roots':[p.name for p in roots],'fileCount':len(files),'totalBytes':sum(f['length'] for f in files),'files':sorted(files,key=lambda f:f['path'])}
save(target/'manifest.json',manifest)
print(json.dumps({'roots':len(roots),'files':len(files),'bytes':manifest['totalBytes'],'manifest':str(target/'manifest.json'),'sha256':digest(target/'manifest.json')}))
