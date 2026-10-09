import importlib.util,json,pathlib,sys,uuid,hashlib,traceback
base=pathlib.Path(__file__).absolute().parent;repo=base.parents[4];evidence=base.parent
python=r'C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
pwsh=r'C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe'
jdk=r'D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8';cache=r'C:/Users/hetia/.gradle';runtime=r'C:/Users/hetia/AppData/Local/Temp'
def require(ok, message):
 if not ok:raise RuntimeError(message)

def load(name):
 s=importlib.util.spec_from_file_location('pipeline_'+name.replace('.','_'),base/'tools'/name);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def call(module,argv):
 saved=sys.argv
 try:sys.argv=[str(module.__file__)]+argv;code=module.main()
 finally:sys.argv=saved
 if code:raise RuntimeError('HELPER_EXIT:'+str(code))
def save(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def identities():
 return [dict(path=r['path'],length=(base/'tools'/r['path']).stat().st_size,sha256=hashlib.sha256((base/'tools'/r['path']).read_bytes()).hexdigest()) for r in json.loads((base/'tool-manifest.json').read_text())]
results=[]
try:
 require(identities()==json.loads((base/'tool-manifest.json').read_text()),'TOOL_IDENTITY_CHANGED')
 core=load('check-core.py');p2=load('check-p2.py');outer=load('run-owned.py')
 tool=evidence/'g11-p3-2ba76738625d-python-controls';tool.mkdir();save(tool/'results.json',core.tool_checks(tool));print('PYTHON CONTRACTS PASS',flush=True)
 processes=evidence/'g11-p3-2ba76738625d-process-controls';save(processes/'results.json',core.process_checks(repo,processes,pwsh,python,jdk,cache));print('PROCESS CONTROLS PASS',flush=True)
 exits=evidence/'g11-p3-2ba76738625d-root-exit-controls';exit_result=core.root_exit_checks(repo,exits,pwsh,python,jdk,cache);require(exit_result['passed'] and len(exit_result['cases'])==18,'ROOT_EXIT_CONTROL_COUNT');save(exits/'results.json',exit_result);print('ROOT EXIT CONTROLS PASS 18',flush=True)
 call(p2,['--out',str(evidence/'g11-p3-2ba76738625d-policy-controls'),'--pwsh',pwsh,'--repo',str(repo)]);print('POLICY CONTROLS PASS',flush=True)
 call(p2,['--out',str(evidence/'g11-p3-2ba76738625d-outer-controls'),'--pwsh',pwsh,'--repo',str(repo),'--check-outer','--python',python,'--jdk',jdk,'--cache',cache,'--runtime-parent',runtime]);print('OUTER CONTROLS PASS',flush=True)
 image_source=None
 for mode,budget in [('targeted',660),('full',1000),('buildsrc',240),('image',660),('linked',200)]:
  name='g11-p3-2ba76738625d-'+mode+'-'+uuid.uuid4().hex;out=evidence/(name+'-owner');stage=evidence/name
  spec=dict(repo=str(repo),tools=str(base/'tools'),out=str(out),stageEvidence=str(stage),mode=mode,inputSpec=str(base/'inputs.json'),jdk=jdk,cache=cache,pwsh=pwsh,python=python,runtimeParent=runtime,imageSourceScope=image_source,deadlineSeconds=budget,fixture=None,processDeadlineMs=0,settleMs=5000,streamCap=33554432,outerFixture=None)
  file=base/(mode+'-spec.json');save(file,spec);print('START '+mode,flush=True)
  call(outer,['--spec',str(file)])
  inner=list(stage.glob('*/stage-result.json'));require(len(inner)==1,'STAGE_RESULT_COUNT')
  value=json.loads(inner[0].read_text(encoding='utf-8-sig'));require(value['status']=='passed','STAGE_FAILED:'+str(value))
  results.append(dict(mode=mode,outer=str(out/'result.json'),inner=str(inner[0])));save(base/'progress.json',results);print('PASS '+mode,flush=True)
  if mode=='image':image_source=str(inner[0].with_name('scope.json'))
 require(identities()==json.loads((base/'tool-manifest.json').read_text()),'TOOL_IDENTITY_CHANGED')
 save(base/'sequence-result.json',dict(passed=True,controls=98,rootExitControls=18,stages=results));print('ALL PASS',flush=True)
except BaseException as error:
 save(base/'sequence-result.json',dict(passed=False,stages=results,failure=repr(error)));traceback.print_exc();sys.exit(1)
