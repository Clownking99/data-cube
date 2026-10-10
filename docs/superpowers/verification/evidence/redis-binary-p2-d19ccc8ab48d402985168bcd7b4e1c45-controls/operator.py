import pathlib,json,hashlib,importlib.util,sys,subprocess,time,traceback
base=pathlib.Path(__file__).absolute().parent
config=json.loads((base/'spec.json').read_bytes())
def save(name,value):(base/name).write_bytes(json.dumps(value,indent=2).encode())
def load(name):
 s=importlib.util.spec_from_file_location('operator_'+name.replace('.','_'),base/'tools'/name);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
core=load('check-core.py');ev=load('evidence_tools.py');rows=[]
def identities():
 for row in json.loads((base/'entry-manifest.json').read_bytes()):
  raw=(base/row['path']).read_bytes()
  if len(raw)!=row['length'] or hashlib.sha256(raw).hexdigest()!=row['sha256']:raise RuntimeError('ENTRY_IDENTITY:'+row['path'])
def run(name,argv,seconds):
 owner=core.OuterOwner();p=None;started=time.monotonic();code=None;failure=None;settlement=None
 save(name+'-command.json',dict(argv=argv,deadlineSeconds=seconds,engineering=False))
 try:
  with (base/(name+'.stdout')).open('xb') as out,(base/(name+'.stderr')).open('xb') as err:
   p=subprocess.Popen(argv,stdout=out,stderr=err);owner.assign(p)
   try:code=p.wait(timeout=seconds)
   except subprocess.TimeoutExpired:failure='OPERATOR_DEADLINE'
 finally:
  settlement=owner.close()
  if p is not None:p.wait(timeout=5)
  row=dict(name=name,actualExit=code,failure=failure,settlement=settlement,elapsedSeconds=time.monotonic()-started);rows.append(row);save('operator-progress.json',rows)
 if failure or code!=0:raise RuntimeError('OPERATOR_STAGE_FAILED:'+name+':'+str(code)+':'+str(failure))
py=config['python'];ps=config['pwsh']
def python(script):return [py,'-I','-S','-B',str(base/script)]
def powershell(script):return [ps,'-NoLogo','-NoProfile','-NonInteractive','-File',str(base/script),'-Package',str(base)]
try:
 identities();before=ev.snapshot(config['repo'],sorted(ev.disk_inventory(pathlib.Path(config['repo']))),config['testedBaseCommit']);ev.verify(json.loads((base/'inputs-before.json').read_bytes()),before)
 run('boundary-python',python('boundary.py'),45)
 run('boundary-powershell',powershell('boundary.ps1'),45)
 run('controller',python('controller.py'),1000)
 run('collision',powershell('collision.ps1'),45)
 run('archive',python('archive-controls.py'),45)
 identities();after=ev.snapshot(config['repo'],sorted(ev.disk_inventory(pathlib.Path(config['repo']))),config['testedBaseCommit']);save('inputs-after.json',after);ev.verify(before,after)
 save('operator-result.json',dict(passed=True,engineering=False,stages=rows));print('Boundaries, 98 controls, collision and archive passed',flush=True)
except BaseException as error:
 save('operator-result.json',dict(passed=False,engineering=False,stages=rows,failure=repr(error)));traceback.print_exc();sys.exit(1)