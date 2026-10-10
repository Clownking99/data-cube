import pathlib,json,hashlib,subprocess,importlib.util,os,time,sys,traceback
base=pathlib.Path(__file__).absolute().parent;c=json.loads((base/'config.json').read_bytes());repo=pathlib.Path(c['repo'])
def save(name,value):(base/name).write_bytes(json.dumps(value,indent=2).encode())
def identities():
 for row in json.loads((base/'entry-manifest.json').read_bytes()):
  raw=(base/row['path']).read_bytes();assert len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256'],row['path']
 for row in json.loads((base/'tool-manifest.json').read_bytes()):
  raw=(repo/'scripts/verification'/row['path']).read_bytes();assert len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256']
 for row in json.loads((base/'source-identities.json').read_bytes()):
  raw=(repo/row['path']).read_bytes();assert len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256']
spec=importlib.util.spec_from_file_location('owner',base/'tools/check-core.py');helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
identities();runtime=pathlib.Path(c['runtimeParent'])/(base.name+'-outer');runtime.mkdir();home=runtime/'home';temp=runtime/'temp';home.mkdir();temp.mkdir()
env={k:os.environ[k] for k in ('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT') if k in os.environ};env.update(HOME=str(home),USERPROFILE=str(home),TEMP=str(temp),TMP=str(temp))
argv=[c['pwsh'],'-NoLogo','-NoProfile','-NonInteractive','-File',str(base/'stage.ps1'),'-Package',str(base)];save('operator-command.json',dict(argv=argv,environment=env,deadlineSeconds=660,filter=c['filter'],repeatCount=1))
owner=helper.OuterOwner();p=None;code=None;failure=None;started=time.monotonic()
try:
 with (base/'operator.stdout').open('xb') as out,(base/'operator.stderr').open('xb') as err:
  p=subprocess.Popen(argv,cwd=repo,env=env,stdout=out,stderr=err);owner.assign(p)
  try:code=p.wait(timeout=650)
  except subprocess.TimeoutExpired:failure='OUTER_DEADLINE'
finally:
 settlement=owner.close()
 if p is not None:p.wait(timeout=5)
 save('operator-result.json',dict(actualExit=code,failure=failure,settlement=settlement,elapsedSeconds=time.monotonic()-started,runtimeExcluded=str(runtime)))
identities();sys.exit(0 if code==0 and failure is None else 1)
