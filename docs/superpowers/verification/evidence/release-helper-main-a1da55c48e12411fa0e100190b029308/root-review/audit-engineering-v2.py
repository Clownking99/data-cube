import pathlib,json,hashlib,re,sys,xml.etree.ElementTree as ET
package=pathlib.Path(sys.argv[1]);out=pathlib.Path(sys.argv[2]);modes=sys.argv[3:];evidence=package.parent;repo=package.parents[4]
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def require(v,msg):
 if not v:raise RuntimeError(msg)
def file(p):
 p=pathlib.Path(p);require(p.is_relative_to(evidence),'outside evidence');require(not any(x.casefold() in ('.testagent','.git','.g10-verify-blobs.ps1') for x in p.parts),'forbidden');b=p.read_bytes();return dict(path=str(p),length=len(b),sha256=hashlib.sha256(b).hexdigest())
checked=[];stages=[];baseline=load(package/'baseline-inputs.json');require(len(baseline['files'])==880,'inputs')
for mode in modes:
 spec=load(package/(mode+'-spec.json'));outerPath=pathlib.Path(spec['out'])/'result.json';outer=load(outerPath);actual=load(package/(mode+'-actual-exit.json'))
 require(actual['actualExitCode']==outer['actualExitCode']==0 and outer['firstFailure'] is None and outer['settlement']['actualJobQueryObservedEmpty'] and outer['streamsCompleted'] and outer['toolsUnchanged'],'outer failure')
 for stream in outer['streams']:
  z=file(stream['path']);require(z['length']==stream['length'] and z['sha256']==stream['sha256'].lower() and stream['eof'] and not stream['overflow'] and stream['error'] is None and not stream['partial'],'outer streams');checked.append(z)
 innerFiles=list(pathlib.Path(spec['stageEvidence']).glob('*/stage-result.json'));require(len(innerFiles)==1,'unique scope');s=innerFiles[0].parent;v=load(innerFiles[0]);scope=load(s/'scope.json');require(v['status']=='passed' and v['primaryFailure'] is None,'stage failed');require(scope['paths']['Repo']==str(repo) and scope['stage']==mode,'wrong scope')
 require(load(s/'inputs-before.json')==load(s/'inputs-after.json')==baseline,'input change')
 ps=[]
 for receipt in sorted((s/'processes').glob('*/process-receipt.json')):
  d=receipt.parent;a=load(receipt);h=load(d/'host-receipt.json');e=load(d/'root-exit.json');i=load(d/'root-identity.json');p=a['rootExitProof']
  require(a['status']=='passed' and a['ownedSettlement']=='complete' and a['hostExitCode']==a['observedRootExitCode']==a['rootExitCode']==e['exitCode']==h['rootExitCode']==p['exitCode']==0,'process failure')
  require(e['pid']==i['pid']==h['rootPid']==p['pid'] and e['startTimeUtc']==i['startTimeUtc']==h['rootStartTimeUtc']==p['startTimeUtc'],'process identity')
  require(0<p['observationTick']==e['observationTick']==h['rootObservationTick'] and h['rootExited'] and h['streamsCompleted'] and a['rootExitEvidenceError'] is None,'exit proof')
  require(not a['termination']['requested'] and all(x['exitObserved'] for x in a['capturedDescendants']),'settlement')
  for stream in ('stdout','stderr','host-stdout','host-stderr'):
   z=file(d/(stream+'.bin'));streamReceipt=a[stream];require(z['length']==streamReceipt['length'] and z['sha256']==streamReceipt['sha256'].lower() and not streamReceipt['partial'],'original stream hash')
   if stream in ('stdout','stderr'):
    hs=h[stream];require(hs['eof'] and not hs['truncated'] and hs['error'] is None and hs['bytesRead']==hs['bytesWritten']==z['length'],'original stream complete')
   checked.append(z)
  checked.extend(file(d/n) for n in ('process-receipt.json','root-exit.json','root-identity.json','host-receipt.json','request.json'));ps.append(dict(name=d.name,pid=e['pid'],exit=0))
 counts=None;skips=[];cases=[]
 if mode in ('full','buildsrc'):
  counts=dict(tests=0,passed=0,skipped=0,failures=0,errors=0,suites=0)
  for f in sorted((s/'xml').glob('TEST-*.xml')):
   x=ET.parse(f).getroot();xc=x.findall('testcase');require(len(xc)==int(x.get('tests')),'suite mismatch');local=dict(tests=len(xc),passed=0,skipped=0,failures=0,errors=0);counts['suites']+=1
   for c in xc:
    status='failures' if c.find('failure') is not None else 'errors' if c.find('error') is not None else 'skipped' if c.find('skipped') is not None else 'passed';local[status]+=1;cases.append(dict(classname=c.get('classname'),name=c.get('name'),status=status))
    if status=='skipped':skips.append(dict(classname=c.get('classname'),name=c.get('name'),reason=c.find('skipped').get('message','')))
   for k in ('failures','errors','skipped'):require(local[k]==int(x.get(k,'0')),'case/suite mismatch')
   for k,n in local.items():counts[k]+=n
   checked.append(file(f))
  require(all(v['testResults'][k]==n for k,n in counts.items()),'summary/current XML mismatch');require(counts['failures']==counts['errors']==0,'XML failure')
  if mode=='full':
   require(counts['passed']==4740 and counts['skipped']==3,'full counts');allow={(x['class'],x['name'],'Assumption failed: '+x['reason']) for x in load(repo/'scripts/verification/stage-policy.json')['liveSkips']};require({(x['classname'],x['name'],x['reason']) for x in skips}==allow,'skip changed')
   for cls,names in load(package/'expected-update-cases.json').items():require(sorted(x['name'] for x in cases if x['classname']==cls and x['status']=='passed')==sorted(names),'missing critical case')
   require(len([x for x in cases if x['classname']=='com.datacube.fx.RedisPaneBudgetTest' and x['status']=='passed'])==14,'native cases')
  else:require(counts['passed']==8 and not counts['skipped'],'buildsrc counts')
 if mode=='linked':require(set(v['linkedProcesses'])=={'module-index','probe-compile','driver-discovery','redis-linked'} and 'connectCalls=0' in v['artifact']['driverOutput'] and 'realServices=0' in v['artifact']['redisOutput'] and v['artifact']['realServices']==0,'linked probes')
 checked.extend(file(q) for q in [outerPath,innerFiles[0],s/'scope.json',s/'inputs-before.json',s/'inputs-after.json',package/(mode+'-spec.json')]);stages.append(dict(mode=mode,scope=str(s),counts=counts,skips=skips,processes=ps,artifact=v.get('artifact')))
result=dict(passed=True,review='Independent original events, four streams, inputs, actual XML and critical cases',package=str(package),stages=stages,files=checked)
out.write_bytes(json.dumps(result,ensure_ascii=False,indent=2).encode());print(json.dumps(dict(passed=True,stages=[dict(mode=x['mode'],counts=x['counts'],processes=len(x['processes'])) for x in stages],files=len(checked),sha256=hashlib.sha256(out.read_bytes()).hexdigest())))
