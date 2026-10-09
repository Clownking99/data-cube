"""Freeze a completed, independently accepted fresh main round; no runtime copy."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re

def need(value,message):
    if not value:raise RuntimeError(message)
def load(p):return json.loads(p.read_bytes())

p=argparse.ArgumentParser();p.add_argument('--prefix',required=True);args=p.parse_args()
prefix=args.prefix;need(re.fullmatch('g11-p3-[a-f0-9]{12}',prefix),'prefix')
review=Path(__file__).absolute().parent
need(review.name==prefix+'-root-review','execute only in this round reviewer directory')
ev=review.parent;repo=ev.parents[3]
need(repo==Path('D:/Projects/朝花夕拾'),'main repo')
base=ev/(prefix+'-package')
preparation=load(base/'preparation.json')
need(preparation['prefix']==prefix and preparation['controls']==98,'round identity')
need(load(base/'shell-exit.json')['actualPythonExitCode']==0,'actual controller exit')
sequence=load(base/'sequence-result.json')
need(sequence['passed'] and sequence['controls']==98 and sequence['rootExitControls']==18,'sequence')
control=load(review/'controls-review.json')
need(control['accepted'] and control['controls']['total']==98,'control acceptance')
engineering=load(review/'engineering-review.json')
need(engineering['engineeringAccepted'] and engineering['image']['files']==183 and engineering['image']['testTypes']==408,'engineering/image acceptance')
case=load(review/'case-review.json');pid=load(review/'pid-case-review.json')
need([x['passed'] for x in case['stages']]==[291,4721,8] and pid['passed']==33 and pid['skipped']==0,'test cases')
need([x['mode'] for x in sequence['stages']]==['targeted','full','buildsrc','image','linked'],'all five stages')
roots={base,review}
for name in control['derivedRoots']:
    need('/' not in name and '\\' not in name and (name.startswith(prefix+'-') or re.fullmatch('g11-p2-synthetic-[a-f0-9]{32}-[a-z0-9-]+',name)),'control root')
    roots.add(ev/name)
for row in sequence['stages']:
    stage=Path(row['inner']).parent.parent;owner=Path(row['outer']).parent
    need(stage.parent==owner.parent==ev and stage.name.startswith(prefix+'-'+row['mode']+'-') and owner.name==stage.name+'-owner','stage ownership')
    roots.update((stage,owner))
    verdict=load(review/(row['mode']+'-review.json'))
    need(verdict['rawInputsLogsAndCountsVerified'] and verdict['testedCommit']==preparation['testedCommit'] and verdict['inputCount']==875 and verdict['typeCount']==408 and verdict['toolCount']==12,'stage acceptance')

def safe(path):
    need(path.is_relative_to(ev) and path.relative_to(ev).parts[0] in {r.name for r in roots},'outside round')
    for name in path.relative_to(ev).parts:
        need(name and name not in ('.','..') and name.casefold() not in ('.testagent','.git','.g10-verify-blobs.ps1') and name==name.rstrip(' .') and ':' not in name,'path component')
    for node in (path,*path.parents):need(not node.is_symlink() and not node.is_junction(),'link')
    return path

files=[];seen=set()
for root in sorted(roots):
    need(safe(root).is_dir(),'missing root')
    for current,dirs,names in os.walk(root,followlinks=False):
        for name in dirs:safe(Path(current)/name)
        for name in sorted(names):
            path=safe(Path(current)/name);rel=path.relative_to(repo).as_posix()
            need(rel not in seen,'duplicate evidence');seen.add(rel)
            raw=path.read_bytes();files.append(dict(path=rel,length=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
target=ev/(prefix+'-frozen');need(not target.exists(),'freeze collision');target.mkdir()
manifest=dict(schema='frozen-evidence/v2',phase='G11-P3-ROOT-EXIT-CORRECTION',accepted=True,deliveryAllowed=True,testedCommit=preparation['testedCommit'],controls=98,roots=[p.relative_to(repo).as_posix() for p in sorted(roots)],files=sorted(files,key=lambda x:x['path']),fileCount=len(files),totalBytes=sum(x['length'] for x in files),selfExcluded='manifest.json',fullProductAcceptance=False)
path=target/'manifest.json'
with path.open('x',encoding='utf-8') as f:json.dump(manifest,f,ensure_ascii=False,indent=2)
print(json.dumps(dict(path=str(path),roots=len(roots),files=len(files),bytes=manifest['totalBytes'],sha256=hashlib.sha256(path.read_bytes()).hexdigest())))
