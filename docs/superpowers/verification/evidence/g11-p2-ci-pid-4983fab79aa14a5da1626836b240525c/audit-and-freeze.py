import pathlib,json,hashlib,xml.etree.ElementTree as ET,os,stat
repo=pathlib.Path.cwd();base=pathlib.Path(__file__).absolute().parent;s=json.loads((base/'spec.json').read_text());stage=pathlib.Path(s['stageEvidence']);owner=pathlib.Path(s['out']);receipt=next(stage.glob('*/stage-result.json'));scope=receipt.parent
r=json.loads(receipt.read_text(encoding='utf-8-sig'));outer=json.loads((owner/'result.json').read_text());assert r['status']=='passed' and r['primaryFailure'] is None;assert outer['actualExitCode']==0 and outer['firstFailure'] is None and outer['settlement']['actualJobQueryObservedEmpty'] and outer['toolsUnchanged'] and outer['streamsCompleted'];assert r['processReceipt']['observedRootExitCode']==0 and r['processReceipt']['ownedSettlement']=='complete'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
for row in json.loads((base/'entry-manifest.json').read_text()):p=base/row['path'];assert p.stat().st_size==row['length'] and sha(p)==row['sha256']
for row in json.loads((base/'tool-manifest.json').read_text()):
 for p in (base/'tools'/row['path'],repo/'scripts/verification'/row['path']):assert p.stat().st_size==row['length'] and sha(p)==row['sha256']
before=json.loads((scope/'inputs-before.json').read_text());after=json.loads((scope/'inputs-after.json').read_text());assert before==after and len(before['files'])==875
xmls=list((scope/'xml').glob('TEST-*.xml'));assert len(xmls)==1;suite=ET.fromstring(xmls[0].read_bytes());cases=suite.findall('testcase');assert len(cases)==33
assert all(c.attrib['classname']=='com.datacube.export.PgDumpRunnerReliabilityTest' and c.find('skipped') is None and c.find('failure') is None and c.find('error') is None for c in cases)
t=r['testResults'];assert [t[k] for k in ('suites','tests','passed','skipped','failures','errors')]==[1,33,33,0,0,0]
groups={'pidReaderRejectsEmptyPartialAndCompletePayloadUntilPublication':['[1] ','[2] 12','[3] 123456'],'pidPublisherPublishesCompletePositivePayloadForBothFamilyRoles':['[1] child-pid','[2] grandchild-pid'],'publishedPidMustBeCompleteAndPositive':['[1] ','[2] 0','[3] -1','[4] 12x','[5] 9223372036854775808'],'capturedFamilyHoldingPipeIsStoppedAfterParentExitAndIndependentNeighborSurvives':['[1] parent','[2] tree'],'throwingNormalHandleControlStillJoinsItsWorkerAndForcesOnlyOwnedFamily':['throwingNormalHandleControlStillJoinsItsWorkerAndForcesOnlyOwnedFamily()'],'blockedHandleControlCannotBlockObservationAndItsLateReturnIsStillOwned':['blockedHandleControlCannotBlockObservationAndItsLateReturnIsStillOwned()']}
actual=[c.attrib['name'] for c in cases];rows=[]
for method,names in groups.items():
 starts=[i for i in range(len(actual)-len(names)+1) if actual[i:i+len(names)]==names];assert len(starts)==1,(method,starts)
 start=starts[0];rows.append(dict(sourceMethod=method,rawXml=xmls[0].name,ordinalBase='zero',ordinals=list(range(start,start+len(names))),displayNames=names,passed=len(names)))
exe=json.loads((scope/'executables-after.json').read_text());assert all(x['matches'] for x in exe)
sourceFiles=['test/com/datacube/export/'+name for name in ('PgDumpProcessHelper.java','PgDumpTestJobs.java','PgDumpRunnerReliabilityTest.java')]
report=dict(schema='g11-ci-pid-audit/v1',passed=True,testedBaseCommit='cfd9d4ed4ebd08d1b9f2ff138ae25690901effe9',workingTreeCorrections=[dict(path=p,length=(repo/p).stat().st_size,sha256=sha(repo/p)) for p in sourceFiles],testCounts={k:t[k] for k in ('suites','tests','passed','skipped','failures','errors')},cases=rows,sourceMappingNote='Gradle parameterized XML uses display names, not Java method names. Unique contiguous display groups are mapped to unchanged source declarations; duplicate empty display names retain separate XML ordinals.',inputCount=875,inputBeforeAfterIdentical=True,entryToolsUnchanged=True,outerExit=0,outerJobEmpty=True,innerExit=0,innerSettlement='complete',stageReceipt=str(receipt),outerReceipt=str(owner/'result.json'),firstInvocation='outer-invocation.log: relative spec rejected before any process or Gradle start; retained unchanged',realServices=0)
(base/'audit-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
files=[]
for root in (base,owner,stage):
 for directory,children,names in os.walk(root,followlinks=False):
  node=pathlib.Path(directory)
  for name in children+names:
   assert name.rstrip(' .').casefold() not in ('.testagent','.git')
   assert not getattr((node/name).lstat(),'st_file_attributes',0)&0x400
  for name in sorted(names):
   p=node/name;files.append(dict(path=p.relative_to(repo).as_posix(),length=p.stat().st_size,sha256=sha(p)))
final=pathlib.Path(str(base)+'-frozen');final.mkdir(exist_ok=False);manifest=dict(schema='frozen-evidence/v1',phase='G11 CI PID readiness focused correction',testedCommit=report['testedBaseCommit'],workingTreeCorrections=report['workingTreeCorrections'],roots=[p.relative_to(repo).as_posix() for p in (base,owner,stage)],files=sorted(files,key=lambda x:x['path']),fileCount=len(files),totalBytes=sum(x['length'] for x in files),runtimeTreesExcluded=True)
p=final/'manifest.json';p.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(dict(files=len(files),bytes=manifest['totalBytes'],manifest=str(p),manifestSha256=sha(p),testCounts=report['testCounts']),indent=2))
