import pathlib,json,hashlib,uuid,importlib.util,subprocess
repo=pathlib.Path('D:/Projects/朝花夕拾');ev=repo/'docs/superpowers/verification/evidence';scratch=pathlib.Path(__file__).parent
old=ev/'redis-binary-p2-2fecc37fd6c9448ebcc8dcbfc151faa5-update-targeted'
def load(p):return json.loads(p.read_bytes())
def save(p,v):p.write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
def identity(p,root):
 b=p.read_bytes();return dict(path=p.relative_to(root).as_posix(),length=len(b),sha256=hashlib.sha256(b).hexdigest())
commit=subprocess.check_output(['D:/Git/cmd/git.exe','rev-parse','HEAD'],cwd=repo).decode().strip();assert commit=='bb84b72a47f5ed21ce9833b609a301e29b4a9ebd'
base=ev/('redis-binary-p3-'+uuid.uuid4().hex+'-update-targeted');base.mkdir()
for row in load(old/'entry-manifest.json'):
 if row['path'] in ('config.json','inputs.json','baseline-inputs.json','retained-first-attempt.json'):continue
 dst=base/row['path'];dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes((old/row['path']).read_bytes())
c=load(old/'config.json');c.update(repo=str(repo),package=str(base),stageEvidence=str(ev/(base.name+'-stage')),baseCommit=commit);save(base/'config.json',c)
inputs=load(old/'inputs.json');inputs.update(repo=str(repo),testedCommit=commit);save(base/'inputs.json',inputs)
spec=importlib.util.spec_from_file_location('evidence',base/'tools/evidence_tools.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
baseline=module.snapshot(str(repo),inputs['paths'],commit,inputs['optionalAbsent']);previous=load(old/'baseline-inputs.json');assert baseline['files']==previous['files'] and baseline['optionalAbsent']==previous['optionalAbsent'];save(base/'baseline-inputs.json',baseline)
for row in load(base/'source-identities.json'):
 assert identity(repo/row['path'],repo)==row
save(base/'entry-manifest.json',[identity(p,base) for p in sorted(base.rglob('*')) if p.is_file()])
save(scratch/'p3-locations.json',dict(mainCommit=commit,targeted=str(base),stageEvidence=c['stageEvidence']))
print(json.dumps(dict(package=str(base),inputFiles=len(baseline['files']),entryFiles=len(load(base/'entry-manifest.json')),sourceAndInputsMatchWorker=True)))
