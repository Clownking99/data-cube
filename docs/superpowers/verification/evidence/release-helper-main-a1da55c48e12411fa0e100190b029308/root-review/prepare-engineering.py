import pathlib,json,hashlib,uuid,xml.etree.ElementTree as ET
scratch=pathlib.Path(__file__).parent;repo=scratch.parents[1];ev=repo/'docs/superpowers/verification/evidence'
def load(p):return json.loads(p.read_bytes())
def save(p,v):p.write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
def row(p,root):
 b=p.read_bytes();return dict(path=p.relative_to(root).as_posix(),length=len(b),sha256=hashlib.sha256(b).hexdigest())
locations=load(scratch/'p3-locations.json');target=pathlib.Path(locations['targeted']);stage=pathlib.Path(locations['stageEvidence']);audit=load(target/'targeted-audit.json');assert audit['passed']
frozen=ev/(target.name+'-frozen');frozen.mkdir();files=[]
for root in (target,stage):
 for p in sorted(root.rglob('*')):
  if p.is_file():files.append(row(p,ev))
save(frozen/'manifest.json',dict(schema='release-helper-main-targeted/v1',passed=True,testedCommit=locations['mainCommit'],actualGradleExecutions=1,counts=audit['tests'],files=files))
old=ev/'redis-binary-p2-5570968fcd464e57819c408447327b34-update-engineering';base=ev/('redis-binary-p3-'+uuid.uuid4().hex+'-update-engineering');base.mkdir()
for name in ('source-identities.json','tool-manifest.json','expected-update-cases.json'):(base/name).write_bytes((old/name).read_bytes())
for item in load(base/'tool-manifest.json'):
 src=repo/'scripts/verification'/item['path'];assert row(src,repo/'scripts/verification')==item;dst=base/'tools'/item['path'];dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(src.read_bytes())
c=load(target/'config.json');c['package']=str(base);save(base/'config.json',c)
(base/'inputs.json').write_bytes((target/'inputs.json').read_bytes());(base/'accepted-targeted-baseline.json').write_bytes((target/'baseline-inputs.json').read_bytes())
worker=pathlib.Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
def historical(p):
 p=pathlib.Path(p);return repo/p.relative_to(worker)
binding=load(old/'controls-binding.json')
for k in ('package','manifest'):binding[k]=str(historical(binding[k]))
assert hashlib.sha256(pathlib.Path(binding['manifest']).read_bytes()).hexdigest()==binding['manifestSha256']
for item in load(pathlib.Path(binding['manifest']))['files']:assert row(ev/item['path'],ev)==item
for group in load(pathlib.Path(binding['package'])/'controller-result.json')['groups']:
 value=load(historical(group['result']));assert value['passed'] and len(value['cases'])+len(value.get('rolePolicy',[]))==group['count']
save(base/'controls-binding.json',binding)
save(base/'targeted-binding.json',dict(package=str(target),manifest=str(frozen/'manifest.json'),manifestSha256=hashlib.sha256((frozen/'manifest.json').read_bytes()).hexdigest(),executedAgain=False,freshMainP3=True))
controller=(old/'controller.py').read_text();before="value=load(guarded(pathlib.Path(group['result'])))";after="historical=pathlib.Path(group['result']); historical=repo/historical.relative_to(pathlib.Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')); value=load(guarded(historical))";assert controller.count(before)==1
controller=controller.replace(before,after).replace("'redis-binary-p2-'","'redis-binary-p3-'");(base/'controller.py').write_bytes(controller.encode())
runner=(old/'run-engineering.ps1').read_text();assert runner.count("'worker-only'")==1;(base/'run-engineering.ps1').write_bytes(runner.replace("'worker-only'","'root-only'").encode())
save(base/'preflight.json',dict(controlsReused=98,controlsExecuted=0,source='Existing version-bound control results; absolute historical result paths mapped to exact-byte local copies',baseline='Fresh main P3 targeted',sourceCommit=locations['mainCommit'],sharedToolsChanged=False,thresholdsChanged=False))
save(base/'entry-manifest.json',[row(p,base) for p in sorted(base.rglob('*')) if p.is_file()])
locations.update(engineering=str(base),targetedFrozen=str(frozen));save(scratch/'p3-locations.json',locations);print(json.dumps(dict(package=str(base),controlsVerified=98,freshTargetedManifestFiles=len(files))))
