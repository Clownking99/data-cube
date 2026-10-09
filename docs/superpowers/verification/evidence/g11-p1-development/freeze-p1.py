import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil

development=Path(__file__).absolute().parent
repo=development.parents[4]
target=repo/'docs/superpowers/verification/evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529'
spec=importlib.util.spec_from_file_location('frozen_admission',target/'tools/evidence_tools.py')
admission=importlib.util.module_from_spec(spec);spec.loader.exec_module(admission)
frozen=repo/'docs/superpowers/verification/evidence/g11-p1-frozen-20261009'
frozen.mkdir(exist_ok=False)
scope=json.loads((target/'scope.json').read_text(encoding='utf-8-sig'))
tools=frozen/'tools';tools.mkdir()
for relative,identity in scope['tools'].items():
 source=repo/'scripts/verification'/relative
 if hashlib.sha256(source.read_bytes()).hexdigest().upper()!=identity['sha256']:
  raise RuntimeError('POST_PILOT_TOOL_CHANGED:'+relative)
 shutil.copyfile(source,tools/relative)
roots={development,target.parent,frozen/'tools'}
for directory in development.glob('process-matrix-*'):
 for file in directory.glob('*-check.json'):
  record=json.loads(file.read_text(encoding='utf-8-sig'))
  argv=record['argv'];path=admission.admitted_path(argv[argv.index('-EvidenceRoot')+1])
  if not path.is_relative_to(repo/'docs/superpowers/verification/evidence'):
   raise RuntimeError('UNOWNED_EVIDENCE_ROOT')
  roots.add(path)
entries=[]
def visit(directory):
 admission.no_links(directory)
 with os.scandir(directory) as children:
  for entry in children:
   # Reject forbidden components before accessing metadata or descendants.
   admission.components(entry.path)
   path=Path(entry.path);admission.no_links(path)
   if entry.is_dir(follow_symlinks=False):visit(path)
   elif entry.is_file(follow_symlinks=False):
    data=path.read_bytes();entries.append({'path':path.relative_to(repo).as_posix(),'length':len(data),'sha256':hashlib.sha256(data).hexdigest()})
   else:raise RuntimeError('INVALID_EVIDENCE_KIND')
for root in sorted(roots):visit(root)
manifest={'schema':'frozen-evidence/v1','testedCommit':'099dd677a653731bf2fad998cacb65ef33f2c827','phase':'G11-P1',
          'roots':[p.relative_to(repo).as_posix() for p in sorted(roots)],'files':sorted(entries,key=lambda item:item['path']),
          'fileCount':len(entries),'totalBytes':sum(item['length'] for item in entries),
          'runtimeTreesExcluded':True,'interpretation':'Earlier failed/debug runs retained unchanged; only explicitly accepted final controls and targeted-001 provide P1 acceptance evidence. No P2 or release acceptance.'}
file=frozen/'manifest.json'
with file.open('x',encoding='utf-8') as output:json.dump(manifest,output,ensure_ascii=False,indent=2)
print(json.dumps({'manifest':str(file),'fileCount':len(entries),'totalBytes':manifest['totalBytes'],'sha256':hashlib.sha256(file.read_bytes()).hexdigest()},indent=2))
