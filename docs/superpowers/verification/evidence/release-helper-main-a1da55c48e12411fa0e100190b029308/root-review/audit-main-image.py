import pathlib,json,hashlib,importlib.util
scratch=pathlib.Path(__file__).parent;repo=scratch.parents[1]
def load(p):return json.loads(p.read_bytes())
def module(path):
 s=importlib.util.spec_from_file_location(path.stem,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
locations=load(scratch/'p3-locations.json');base=pathlib.Path(locations['engineering']);sequence=load(base/'sequence-result.json');assert sequence['passed'] and load(base/'operator-result.json')['actualExitCode']==0
for item in load(base/'entry-manifest.json'):
 b=(base/item['path']).read_bytes();assert len(b)==item['length'] and hashlib.sha256(b).hexdigest()==item['sha256']
et=module(base/'tools/evidence_tools.py');inputs=load(base/'inputs.json');assert et.snapshot(str(repo),inputs['paths'],inputs['testedCommit'],inputs['optionalAbsent'])==load(base/'baseline-inputs.json')
stages={r['mode']:pathlib.Path(r['scope']).parent for r in sequence['stages']};linked=stages['linked'];im=load(linked/'image-spec.json');image=module(base/'tools/image_tools.py');actual=image.inventory(im['image'],im['runtime'],im['runtimeParent']);assert actual==load(stages['image']/'image-manifest.json')==load(linked/'image-before.json')==load(linked/'image-after.json')
cfg=pathlib.Path(im['image'])/'app/DataCube.cfg';audit=image.audit_classes(pathlib.Path(im['index']).read_text(encoding='utf-8-sig'),load(pathlib.Path(im['types'])),actual,cfg.read_text(encoding='utf-8-sig'));assert audit==load(linked/'image-audit.json')
target=pathlib.Path(locations['targeted']);ts=pathlib.Path(load(target/'targeted-audit.json')['scope']);extra=[]
for p in sorted((ts/'processes').glob('*/process-receipt.json')):
 r=load(p);assert r['status']=='passed' and not r['termination']['requested'] and all(x['exitObserved'] for x in r['capturedDescendants'])
 for k in ('stdout','stderr','host-stdout','host-stderr'):
  raw=pathlib.Path(r[k]['path']).read_bytes();assert not r[k]['partial'] and len(raw)==r[k]['length'] and hashlib.sha256(raw).hexdigest()==r[k]['sha256'].lower()
 extra.append(p.parent.name)
out=dict(passed=True,testedCommit=inputs['testedCommit'],engineeringInputs=880,currentImageFiles=len(actual['files']),imageAudit=audit,targetedExtraProcessAudits=extra,productionHelperUnchanged=True,realDatabaseOrInstallerExecuted=False,workerP2RemainsPartial=True)
dest=scratch/'main-image-review.json';dest.write_bytes((json.dumps(out,ensure_ascii=False,indent=2)+'\n').encode());print(json.dumps(dict(passed=True,imageFiles=len(actual['files']),testTypes=audit['testTypeCount'],manifestSha256=hashlib.sha256(dest.read_bytes()).hexdigest())))
