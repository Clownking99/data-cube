"""Bounded independent outer owner for one explicit frozen stage entry."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import uuid


def sibling(name):
    file=Path(__file__).absolute().parent/'tools'/name
    if any(p.rstrip(' .').casefold() in ('.testagent','.git','.g10-verify-blobs.ps1') for p in file.parts):raise RuntimeError('FORBIDDEN_TOOL_PATH')
    spec=importlib.util.spec_from_file_location('outer_'+name.replace('.','_'),file)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


evidence=sibling('evidence_tools.py');helper=sibling('check-core.py')


class Pump:
    def __init__(self):self.eof=False;self.overflow=False;self.error=None;self.read=0;self.written=0;self.failureTick=None
    def drain(self,stream,path):
        try:
            with path.open('xb',buffering=0) as output:
                while True:
                    data=stream.read1(16384)
                    if not data:self.eof=True;break
                    self.read+=len(data);keep=data[:max(0,1048576-self.written)]
                    if keep:
                        count=output.write(keep);self.written+=count
                        if count!=len(keep):raise OSError('OUTER_SHORT_FILE_WRITE')
                    if len(keep)!=len(data):self.failureTick=time.monotonic_ns();self.overflow=True;break
        except Exception as error:self.failureTick=time.monotonic_ns();self.error=type(error).__name__+':'+str(error)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--spec',required=True);args=parser.parse_args()
    spec=evidence.load(args.spec)
    fields={'repo','tools','out','stageEvidence','mode','inputSpec','jdk','cache','pwsh','python','runtimeParent','imageSourceScope','deadlineSeconds','fixture','processDeadlineMs','settleMs','streamCap','outerFixture'}
    if set(spec)!=fields:raise RuntimeError('UNKNOWN_OUTER_SPEC_FIELD')
    paths={key:evidence.admitted_path(spec[key]) for key in ('repo','tools','out','stageEvidence','inputSpec','jdk','cache','pwsh','python','runtimeParent') if key!='inputSpec' or spec[key]}
    source_image=evidence.admitted_path(spec['imageSourceScope']) if spec['imageSourceScope'] else None
    prefix=paths['repo']/'docs/superpowers/verification/evidence'
    for key in ('out','stageEvidence'):
        if paths[key].parent!=prefix or not paths[key].name.startswith(('g11-p2-','g11-p3-')):raise RuntimeError('OUTER_EVIDENCE_OUTSIDE_G11_P2_P3')
    if spec['mode'] not in ('targeted','full','buildsrc','image','linked','fixture'):raise RuntimeError('INVALID_OUTER_STAGE')
    if spec['mode']!='fixture' and ('inputSpec' not in paths or spec['fixture'] is not None):raise RuntimeError('INVALID_OUTER_STAGE_SPEC')
    outer_cases={'normal':'normal','nonzero-child-pipe':'nonzero-child-pipe','detached-child':'detached-child','overflow':'overflow','nonzero-child-overflow':'nonzero-child-overflow','start-failure':'sleep','assign-failure':'sleep'}
    if spec['outerFixture'] is not None and (spec['mode']!='fixture' or spec['outerFixture'] not in outer_cases):raise RuntimeError('FAULT_OUTSIDE_OUTER_FIXTURE')
    for key in ('processDeadlineMs','settleMs','streamCap'):
        if type(spec[key]) is not int or spec[key]<0:raise RuntimeError('INVALID_OUTER_PROCESS_POLICY')
    if type(spec['deadlineSeconds']) is not int or not 10<=spec['deadlineSeconds']<=1100:raise RuntimeError('INVALID_OUTER_DEADLINE')
    if paths['tools']!=Path(__file__).absolute().parent/'tools':raise RuntimeError('OUTER_FROZEN_TOOL_ROOT_MISMATCH')
    permitted_work=paths['repo']/'scripts/verification'
    permitted_frozen=paths['tools'].is_relative_to(prefix) and any(p.startswith(('g11-p2-','g11-p3-')) for p in paths['tools'].relative_to(prefix).parts[:1])
    if paths['tools']!=permitted_work and not permitted_frozen:raise RuntimeError('OUTER_TOOL_ROOT_OUTSIDE_RUN')
    if source_image and (source_image.name!='scope.json' or source_image.parents[1].parent!=prefix or not source_image.parents[1].name.startswith(('g11-p2-','g11-p3-'))):raise RuntimeError('OUTER_IMAGE_SOURCE_OUTSIDE_RUN')
    for path in (*paths.values(),*((source_image,) if source_image else ())):evidence.no_links(path)
    paths['out'].mkdir(exist_ok=False)
    environment={k:os.environ[k] for k in ('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT') if k in os.environ}
    runtime=paths['runtimeParent']/('datacube-g11-outer-'+uuid.uuid4().hex)
    (runtime/'home').mkdir(parents=True,exist_ok=False);(runtime/'temp').mkdir()
    environment.update(HOME=str(runtime/'home'),USERPROFILE=str(runtime/'home'),TEMP=str(runtime/'temp'),TMP=str(runtime/'temp'))
    argv=[str(paths['pwsh']),'-NoLogo','-NoProfile','-NonInteractive','-File',str(paths['tools']/'run-stage.ps1'),
          '-Repo',str(paths['repo']),'-EvidenceRoot',str(paths['stageEvidence']),'-Jdk',str(paths['jdk']),'-Cache',str(paths['cache']),
          '-Pwsh',str(paths['pwsh']),'-Python',str(paths['python']),'-Mode',spec['mode'],'-RuntimeParent',str(paths['runtimeParent']),
          '-DeadlineMs',str(spec['processDeadlineMs']),'-SettleMs',str(spec['settleMs']),'-StreamCap',str(spec['streamCap'])]
    if 'inputSpec' in paths:argv+=['-InputSpec',str(paths['inputSpec'])]
    if spec['mode']=='fixture':argv+=['-Fixture',spec['fixture']]
    if source_image:argv+=['-ImageSourceScope',str(source_image)]
    gate=runtime/'outer-gate'
    argv=[str(paths['pwsh']),'-NoLogo','-NoProfile','-NonInteractive','-File',str(Path(__file__).with_name('stage.ps1')),'-Spec',args.spec,'-Gate',str(gate)]
    if spec['outerFixture']:
        argv=[str(paths['python']),'-I','-S','-B',str(paths['tools']/'check-core.py'),'--fixture',outer_cases[spec['outerFixture']],'--wait-gate',str(gate)]
        if spec['outerFixture']=='start-failure':argv[0]=str(runtime/'missing-fixture.exe')
    relative_files=('VerificationCore.psm1','OwnedProcessHost.ps1','evidence_tools.py','run-stage.ps1','isolated.gradle','check-core.py','check-p2.py','stage-policy.json','image_tools.py','run-owned.py','probes/G10LinkedRedisProbe.java','probes/MigrationRuntimeDriverProbe.java')
    files=[paths['tools']/relative for relative in relative_files]+[Path(__file__).absolute(),Path(__file__).with_name('stage.ps1'),paths['inputSpec'],Path(args.spec)]
    for file in files:evidence.no_links(file)
    def identities():return [{'path':str(p.relative_to(paths['tools'].parent)),'length':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]
    before=identities();command={'schema':'outer-command/v1','argv':argv,'environment':environment,'deadlineSeconds':spec['deadlineSeconds'],'toolsBefore':before,'wrapper':str(Path(__file__).absolute())}
    (paths['out']/'command.json').write_text(json.dumps(command,ensure_ascii=False,indent=2),encoding='utf-8')
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
        if assigned:gate.write_text('assigned',encoding='utf-8')
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
    return code if code is not None and code!=0 else (1 if failure or code is None or before!=after else 0)


if __name__=='__main__':sys.exit(main())
