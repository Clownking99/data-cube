import pathlib,json,hashlib,importlib.util,subprocess,threading,time,sys,os
base=pathlib.Path(__file__).resolve().parent
prep=pathlib.Path('C:\\Users\\hetia\\.codex\\worktrees\\aed5\\朝花夕拾\\docs\\superpowers\\verification\\evidence\\redis-binary-p2-1d9c1c970f1145ecb9f1c5614929b583-linked-diagnostic-prep-v2');tools=pathlib.Path('C:\\Users\\hetia\\.codex\\worktrees\\aed5\\朝花夕拾\\docs\\superpowers\\verification\\evidence\\redis-binary-p2-5570968fcd464e57819c408447327b34-update-engineering\\tools');repo=pathlib.Path('C:\\Users\\hetia\\.codex\\worktrees\\aed5\\朝花夕拾')
moduleSpec=importlib.util.spec_from_file_location('frozen_outer',tools/'run-owned.py');outer=importlib.util.module_from_spec(moduleSpec);moduleSpec.loader.exec_module(outer)
helper=outer.helper;Pump=outer.Pump
manifest=json.loads((prep/'input-manifest.json').read_text());binding=json.loads((base/'entry-manifest.json').read_text())
def identities():
 rows=[]
 for item in manifest['files']+binding:
  p=pathlib.Path(item['path']);raw=p.read_bytes();row=dict(path=str(p),length=len(raw),sha256=hashlib.sha256(raw).hexdigest())
  if row['length']!=item['length'] or row['sha256']!=item['sha256']:raise RuntimeError('DIAGNOSTIC_BINDING_CHANGED:'+str(p))
  rows.append(row)
 return rows
scope=json.loads(pathlib.Path(json.loads((prep/'config.json').read_text())['originalScope']).read_text())
argv=[scope['paths']['Pwsh'],'-NoLogo','-NoProfile','-NonInteractive','-File',str(prep/'diagnose.ps1')]
environment={k:os.environ[k] for k in ('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT') if k in os.environ}
paths={'out':base,'repo':repo};spec={'deadlineSeconds':150,'outerFixture':None}
before=identities()
(base/'command.json').write_text(json.dumps(dict(diagnosticOnly=True,argv=argv,environment=environment,deadlineSeconds=150,singleActualProbe=True),indent=2),encoding='utf-8')
owner=helper.OuterOwner();process=None;failure=None;failure_tick=None;secondary=[];code=None;assigned=False;termination=False;began=time.monotonic();deadline=began+spec['deadlineSeconds']
def register(kind,tick):
    nonlocal failure,failure_tick
    if failure_tick is None or tick<failure_tick:
        if failure and failure!=kind:secondary.append(failure)
        failure,failure_tick=kind,tick
    elif failure!=kind:secondary.append(kind)
pumps=[Pump(),Pump()];threads=[];settlement=None
try:
    process=subprocess.Popen(argv,cwd=paths['repo'],env=environment,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if spec['outerFixture']=='assign-failure':raise RuntimeError('SYNTHETIC_ASSIGNMENT_FAILURE_BEFORE_GATE')
    owner.assign(process);assigned=True
    if spec['outerFixture']:gate.write_text('assigned',encoding='utf-8')
    for pump,stream,name in zip(pumps,(process.stdout,process.stderr),('stdout.bin','stderr.bin')):
        thread=threading.Thread(target=pump.drain,args=(stream,paths['out']/name),daemon=True);threads.append(thread);thread.start()
    while time.monotonic()<deadline-5:
        observed=process.poll()
        if observed is not None:
            code=observed
            if code!=0 and failure is None:register('NONZERO_EXIT',time.monotonic_ns())
        if any(p.overflow or p.error for p in pumps):
            register('OUTER_LOG_FAILURE',min(p.failureTick for p in pumps if p.failureTick is not None))
            break
        if process.poll() is not None and all(p.eof for p in pumps):break
        time.sleep(.01)
    if process.poll() is None or not all(p.eof for p in pumps):
        if time.monotonic()>=deadline-5:
            if failure is None:failure='OUTER_DEADLINE'
            else:secondary.append('OUTER_DEADLINE')
        termination=True;owner.terminate()
    remaining=max(.001,deadline-1-time.monotonic())
    try:code=process.wait(timeout=remaining)
    except subprocess.TimeoutExpired:failure=failure or 'OUTER_EXIT_NOT_OBSERVED'
    for thread in threads:thread.join(timeout=max(0,deadline-1-time.monotonic()))
    if any(t.is_alive() for t in threads):failure=failure or 'OUTER_STREAMS_INCOMPLETE'
except Exception as error:
    failure=failure or 'OUTER_FAILURE:'+type(error).__name__+':'+str(error)
    if process is not None:
        try:
            if not assigned and process.poll() is None:termination=True;process.kill()
            if process.poll() is not None:code=process.returncode
            elif not assigned:code=process.wait(timeout=max(.001,deadline-1-time.monotonic()))
        except Exception as cleanup_error:secondary.append('DIRECT_ROOT_SETTLEMENT_FAILED:'+str(cleanup_error))
finally:
    try:
        settlement=owner.close(deadline=deadline)
        if settlement['beforeTermination']:
            termination=True
            if failure is None:failure='OUTER_DESCENDANT_REQUIRES_TERMINATION'
            else:secondary.append('OUTER_DESCENDANT_REQUIRES_TERMINATION')
    except Exception as error:
        failure=failure or 'OUTER_SETTLEMENT_FAILED:'+str(error)
        settlement={'actualJobQueryObservedEmpty':False,'status':'unknown','error':str(error)}
for thread in threads:thread.join(timeout=max(0,deadline-time.monotonic()))
if any(t.is_alive() for t in threads):failure=failure or 'OUTER_STREAMS_INCOMPLETE'
after=identities();streams=[]
for pump,name in zip(pumps,('stdout.bin','stderr.bin')):
    path=paths['out']/name;raw=path.read_bytes() if path.exists() else b''
    streams.append({'path':str(path),'length':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'bytesRead':pump.read,'bytesWritten':pump.written,'eof':pump.eof,'overflow':pump.overflow,'error':pump.error,'failureTick':pump.failureTick,'partial':not pump.eof})
if code is not None and code!=0 and failure is None:failure='NONZERO_EXIT'
result={'schema':'outer-result/v1','started':process is not None,'assigned':assigned,'ownershipCoverage':('job-members' if assigned else 'direct-root-handle-only'),'actualExitCode':code,'firstFailure':failure,'failureTick':failure_tick,'secondaryFailures':secondary,'terminationRequested':termination,'settlement':settlement,'streams':streams,'streamsCompleted':all(p.eof and not p.error and not p.overflow for p in pumps),'toolsBefore':before,'toolsAfter':after,'toolsUnchanged':before==after,'elapsedSeconds':time.monotonic()-began}
(paths['out']/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
sys.exit(code if code is not None and code!=0 else (1 if failure or code is None or before!=after else 0))
