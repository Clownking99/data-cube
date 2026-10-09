"""Independent exact inventory/hash review for a named G11 evidence manifest."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re

def require(ok,message):
    if not ok:raise RuntimeError(message)

def components(text):
    require(text and '\\' not in text and ':' not in text and all(
        p and p not in ('.','..') and p==p.rstrip(' .') and
        p.casefold() not in ('.testagent','.git','.g10-verify-blobs.ps1')
        for p in text.split('/')), 'unadmitted relative path')
    return text

def safe(p):
    for node in reversed((p,*p.parents)):
        require(not node.is_symlink() and not node.is_junction(), 'linked path')
    return p

def identity(p):
    digest=hashlib.sha256();size=0
    with safe(p).open('rb') as f:
        for block in iter(lambda:f.read(65536),b''):
            size+=len(block);digest.update(block)
    return dict(length=size,sha256=digest.hexdigest())

parser=argparse.ArgumentParser()
parser.add_argument('--repo',required=True)
parser.add_argument('--manifest',required=True)
parser.add_argument('--sha',required=True)
parser.add_argument('--out',required=True)
args=parser.parse_args()
repo=Path(args.repo)
require(repo in (Path('D:/Projects/朝花夕拾'),Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')), 'checkout')
ev=repo/'docs/superpowers/verification/evidence'
rel=components(args.manifest)
require(rel.startswith('docs/superpowers/verification/evidence/g11-') and rel.endswith('/manifest.json'), 'manifest scope')
path=repo/rel
require(identity(path)['sha256']==args.sha, 'manifest hash')
m=json.loads(safe(path).read_bytes())
def from_relative(value):
    components(value)
    p=repo/value if value.startswith('docs/superpowers/verification/evidence/') else ev/value
    require(p.is_relative_to(ev) and p!=ev and p.relative_to(ev).parts[0].startswith('g11-'), 'evidence scope')
    return safe(p)
roots=[from_relative(x) for x in m['roots']]
require(len(roots)==len(set(roots)) and all(p.parent==ev for p in roots), 'roots overlap/foreign')
actual=set()
for root in roots:
    require(root.is_dir(), 'missing root')
    for current,dirs,names in os.walk(root,followlinks=False):
        for n in dirs:from_relative((Path(current)/n).relative_to(ev).as_posix())
        for n in names:
            p=from_relative((Path(current)/n).relative_to(ev).as_posix())
            require(p not in actual,'duplicate actual file');actual.add(p)
recorded=set();total=0
for item in m['files']:
    p=from_relative(item['path'])
    require(p not in recorded and sum(p.is_relative_to(root) for root in roots)==1,'duplicate/foreign record')
    recorded.add(p)
    data=identity(p)
    require(data['length']==item['length'] and data['sha256']==item['sha256'].lower(),'file identity: '+str(p))
    total+=data['length']
require(actual==recorded and len(actual)==m['fileCount'] and total==m['totalBytes'],'inventory mismatch')
out=Path(args.out)
require(out.parent==Path(__file__).absolute().parent and not out.exists(),'review output')
receipt=dict(schema='root-archive-review/v1',archiveVerified=True,manifest=str(path),manifestSha256=args.sha,roots=len(roots),files=len(actual),bytes=total,manifestAccepted=m.get('accepted',m.get('passed')),engineeringAcceptanceDeterminedSeparately=True)
with out.open('x',encoding='utf-8') as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
print(json.dumps(receipt,ensure_ascii=False))
