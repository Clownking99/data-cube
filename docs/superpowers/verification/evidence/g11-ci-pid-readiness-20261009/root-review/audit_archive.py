"""Independent P2/P3 archive, ownership and image audit; no implementation imports."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def safe(value):
    text = str(value).replace('\\', '/')
    require(re.match(r'^[A-Za-z]:/', text), 'absolute local path required')
    for part in text[3:].split('/'):
        require(part and part not in ('.', '..') and part == part.rstrip(' .') and
                ':' not in part and part.lower() not in ('.testagent', '.git', '.g10-verify-blobs.ps1'), 'forbidden path')
    path = Path(text)
    for node in reversed((path, *path.parents)):
        info = node.lstat()
        require(not getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT, 'reparse')
    return path


def load(path):
    return json.loads(safe(path).read_text(encoding='utf-8-sig'))


def identity(path):
    digest = hashlib.sha256()
    length = 0
    with safe(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            length += len(chunk)
            digest.update(chunk)
    return dict(length=length, sha256=digest.hexdigest())


def matches(path, expected):
    actual = identity(path)
    require(actual['length'] == expected['length'] and actual['sha256'] == expected['sha256'].lower(), 'identity: ' + str(path))
    return actual


def files(root):
    with os.scandir(safe(root)) as children:
        names = [child.name for child in children]
    for name in sorted(names):
        require(name.lower() not in ('.testagent', '.git'), 'forbidden entry')
        path = safe(root / name)
        if path.is_dir():
            yield from files(path)
        else:
            require(path.is_file(), 'unknown kind')
            yield path



repo = Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
evidence = repo/'docs/superpowers/verification/evidence'
name = 'g11-p2-ci-pid-4983fab79aa14a5da1626836b240525c'
base = evidence/name
manifest_path = evidence/(name+'-frozen/manifest.json')
require(identity(manifest_path)['sha256']=='04ee175ba44263ac75a6e528e19c0d6f1cafcaea1e72c46e599657cb79f72379','manifest SHA')
m=load(manifest_path)
require(set(m['roots'])=={(evidence/(name+suffix)).relative_to(repo).as_posix() for suffix in ['', '-owner', '-stage']},'roots')
actual={p.relative_to(repo).as_posix() for root in m['roots'] for p in files(repo/root)}
recorded={r['path'] for r in m['files']}
require(actual==recorded and len(recorded)==len(m['files'])==103,'manifest inventory')
for r in m['files']:matches(repo/r['path'],r)
require(sum(r['length'] for r in m['files'])==m['totalBytes']==910931,'bytes')
entries=load(base/'entry-manifest.json')
require(len(entries)==17,'entry count')
for item in entries:
 require(not Path(item['path']).is_absolute() and '..' not in Path(item['path']).parts,'entry path')
 matches(base/item['path'],item)
spec=load(base/'spec.json');owner=Path(spec['out']);stage=Path(spec['stageEvidence'])/'b7c032677cc04d2cbea16fb8a21b609d'
require(owner==evidence/(name+'-owner') and stage.parent==evidence/(name+'-stage'),'owned roots')
r=load(owner/'result.json')
require(r['actualExitCode']==0 and r['firstFailure'] is None and r['settlement']['actualJobQueryObservedEmpty'] and not r['settlement']['beforeTermination'] and not r['terminationRequested'] and r['streamsCompleted'],'outer settlement')
require(r['toolsBefore']==r['toolsAfter'] and len(r['toolsBefore'])==16 and r['toolsUnchanged'],'outer closure')
for entry in r['toolsBefore']:matches(base/entry['path'],entry)
for stream in r['streams']:
 require(Path(stream['path']).parent==owner and stream['eof'] and not stream['partial'],'stream ownership')
 matches(stream['path'],stream)
sc=load(stage/'scope.json')
for path,expected in sc['executables'].items():
 allowed={str(Path(sc['paths']['Pwsh'])).casefold(),str(Path(sc['paths']['Python'])).casefold()}
 allowed.update(str(Path(sc['paths']['Jdk'])/('bin/'+x+'.exe')).casefold() for x in ['java','javac','jimage'])
 require(str(Path(path)).casefold() in allowed,'exe role');matches(path,expected)
for item in m['workingTreeCorrections']:matches(repo/item['path'],item)
import xml.etree.ElementTree as ET
suite=ET.fromstring(safe(stage/'xml/TEST-com.datacube.export.PgDumpRunnerReliabilityTest.xml').read_bytes());cases=suite.findall('testcase');names=[c.get('name') for c in cases]
require(len(cases)==33 and all(c.get('classname')=='com.datacube.export.PgDumpRunnerReliabilityTest' and all(c.find(k) is None for k in ['failure','error','skipped']) for c in cases),'cases')
groups={
 'pidReaderRejectsEmptyPartialAndCompletePayloadUntilPublication':['[1] ','[2] 12','[3] 123456'],
 'pidPublisherPublishesCompletePositivePayloadForBothFamilyRoles':['[1] child-pid','[2] grandchild-pid'],
 'publishedPidMustBeCompleteAndPositive':['[1] ','[2] 0','[3] -1','[4] 12x','[5] 9223372036854775808'],
 'capturedFamilyHoldingPipeIsStoppedAfterParentExitAndIndependentNeighborSurvives':['[1] parent','[2] tree']}
case_proof=[]
for method,values in groups.items():
 starts=[i for i in range(len(names)-len(values)+1) if names[i:i+len(values)]==values]
 require(len(starts)==1,'case identity '+method)
 case_proof.append(dict(methodFromReviewedSource=method,ordinals=list(range(starts[0],starts[0]+len(values))),displayNames=values))
report=repo/'docs/superpowers/verification/2026-10-09-g11-ci-pid-readiness-worker.md'
result=dict(schema='root-pid-archive-review/v1',manifest=identity(manifest_path),files=len(actual),bytes=m['totalBytes'],frozenEntries=17,outerRuntimeClosure=16,outerStreams=2,outerExitedAndJobEmpty=True,passedCases=33,skipped=0,regressionCaseMapping=case_proof,workerReport=identity(report),fullProductAcceptance=False)
with Path(__file__).with_name('archive-review.json').open('x',encoding='utf-8') as output:json.dump(result,output,ensure_ascii=False,indent=2)
print(json.dumps(result,ensure_ascii=False,indent=2))
