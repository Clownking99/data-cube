"""Seal independently reviewed main evidence without copying runtime data."""
import argparse,hashlib,json,os,re
from pathlib import Path
def need(ok,why):
    if not ok:raise RuntimeError(why)
def load(p):return json.loads(p.read_bytes())
p=argparse.ArgumentParser();p.add_argument('--prefix',required=True);args=p.parse_args()
prefix=args.prefix;need(re.fullmatch(r'redis-binary-p3-[0-9a-f]{32}',prefix),'prefix')
review=Path(__file__).absolute().parent;ev=review.parent;repo=ev.parents[3]
need(repo==Path('D:/Projects/朝花夕拾'),'repo')
base=ev/(prefix+'-package');prep=load(base/'preparation.json');seq=load(base/'sequence-result.json')
need(prep['prefix']==prefix and prep['controlsReused']==98 and prep['controlsRerun'] is False,'preparation')
shell=load(base/'shell-exit.json')
need(shell['actualPythonExitCode']==0 and shell['entryIdentityFailure'] is None,'controller actual exit and entry identity')
need(seq['passed'] and seq['reusedControls']==98 and seq['newControlsRun'] is False,'sequence')
p2=ev/'redis-binary-p2-root-review-20261010'
need(load(p2/'controls-review.json')['accepted'] and load(p2/'engineering-freeze-review.json')['accepted'],'accepted P2')
eng=load(review/'engineering-review.json');cases=load(review/'case-review.json')
need(eng['engineeringAccepted'] and eng['image']['files']>0,'engineering')
need([x['passed'] for x in cases['stages']]==[303,4733,8],'case counts')
need([len(x['liveSkips']) for x in cases['stages']]==[1,3,0],'approved skips')
need([x['mode'] for x in seq['stages']]==['targeted','full','buildsrc','image','linked'],'sequence modes')
roots={base,review,p2}
for row in seq['stages']:
    stage=Path(row['inner']).parent.parent;owner=Path(row['outer']).parent
    need(stage.parent==owner.parent==ev and stage.name.startswith(prefix+'-'+row['mode']+'-') and owner.name==stage.name+'-owner','owned stage')
    roots.update((stage,owner))
    verdict=load(review/(row['mode']+'-review.json'))
    need(verdict['rawInputsLogsAndCountsVerified'] and verdict['testedCommit']==prep['testedCommit'] and verdict['inputCount']==878 and verdict['typeCount']==eng['image']['testTypes'] and verdict['toolCount']==12,'review')
def safe(path):
    need(path.is_relative_to(ev) and path.relative_to(ev).parts[0] in {r.name for r in roots},'outside roots')
    need(all(x.casefold() not in ('.testagent','.git','..','.g10-verify-blobs.ps1') and x==x.rstrip(' .') and ':' not in x for x in path.relative_to(ev).parts),'unsafe')
    for node in (path,*path.parents):need(not node.is_symlink() and not node.is_junction(),'link')
    return path
files=[];seen=set()
for root in sorted(roots):
    for current,dirs,names in os.walk(safe(root),followlinks=False):
        for name in dirs:safe(Path(current)/name)
        for name in sorted(names):
            path=safe(Path(current)/name);rel=path.relative_to(repo).as_posix();need(rel not in seen,'duplicate');seen.add(rel)
            raw=path.read_bytes();files.append(dict(path=rel,length=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
manifest=dict(schema='frozen-evidence/v2',phase='Redis binary key fix main',accepted=True,testedCommit=prep['testedCommit'],controlsReused=98,controlsRerun=False,controlsManifestSha256=prep['controlsManifestSha256'],roots=[p.relative_to(repo).as_posix() for p in sorted(roots)],files=sorted(files,key=lambda x:x['path']),fileCount=len(files),totalBytes=sum(x['length'] for x in files),selfExcluded='manifest.json',fullProductAcceptance=False)
target=ev/(prefix+'-frozen');target.mkdir();mp=target/'manifest.json'
with mp.open('x',encoding='utf-8') as f:json.dump(manifest,f,ensure_ascii=False,indent=2)
print(json.dumps(dict(path=str(mp),roots=len(roots),files=len(files),bytes=manifest['totalBytes'],sha256=hashlib.sha256(mp.read_bytes()).hexdigest())))
