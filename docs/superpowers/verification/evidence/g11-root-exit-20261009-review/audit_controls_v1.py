"""Independent read-only review of the 98-control freeze; no tested imports."""
import hashlib
import json
import os
import re
from pathlib import Path

HERE = Path(__file__).absolute().parent
REPO = Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
EVIDENCE = REPO / 'docs/superpowers/verification/evidence'
TAG = 'g11-p2-rx2-e46234f8c9fa48e9b504156a3f0c9054'
BASE = EVIDENCE / TAG

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def safe(path):
    p = Path(path)
    require(p.is_absolute(), 'absolute path required')
    for part in p.parts[1:]:
        require(part not in ('.', '..') and part == part.rstrip(' .') and ':' not in part and
                part.casefold() not in ('.testagent', '.git', '.g10-verify-blobs.ps1'), 'forbidden path')
    for node in reversed((p, *p.parents)):
        if node.exists():
            require(not node.is_symlink() and not node.is_junction(), 'linked path')
    return p

def load(p):
    return json.loads(safe(p).read_bytes())

def identity(p):
    raw = safe(p).read_bytes()
    return dict(length=len(raw), sha256=hashlib.sha256(raw).hexdigest())

def matches(p, row):
    actual = identity(p)
    require(actual['length'] == row['length'] and actual['sha256'] == row['sha256'].lower(), 'identity mismatch: '+str(p))
    return actual['length']

def walk(root):
    for current, dirs, names in os.walk(safe(root), followlinks=False):
        for name in dirs:
            safe(Path(current)/name)
        for name in names:
            yield safe(Path(current)/name)

manifest_path = EVIDENCE/(TAG+'-frozen')/'manifest.json'
manifest_sha = identity(manifest_path)['sha256']
require(manifest_sha == 'b2de1c70097c8baa033030a469dbca6e10a2d133b4ee924d2325e08ffaedcce9', 'manifest changed')
m = load(manifest_path)
require(m['count'] == 98 and m['engineering'] is False, 'manifest scope')
roots = m['roots']
require(len(roots) == len(set(roots)) == 91, 'root inventory')
require(all(re.fullmatch(r'g11-p2-[A-Za-z0-9-]+', n) for n in roots), 'root names')
actual = {p.relative_to(EVIDENCE).as_posix() for n in roots for p in walk(EVIDENCE/n)}
recorded = set()
total = 0
for item in m['files']:
    rel = item['path']
    require(rel.split('/')[0] in roots and rel not in recorded, 'foreign or duplicate file')
    recorded.add(rel)
    total += matches(EVIDENCE/rel, item)
require(actual == recorded and len(actual) == m['fileCount'] == 1357 and total == m['totalBytes'] == 16591899, 'archive inventory')
admission = load(HERE/'path-fix-admission.json')
tm = load(BASE/'tool-manifest.json')
require(tm == admission['toolFiles'], 'tool admission')
for t in tm:
    matches(BASE/'tools'/t['path'], t)
    matches(REPO/'scripts/verification'/t['path'], t)
require(identity(BASE/'controller.py')['sha256'] == admission['controllerSha256'], 'controller admission')
entry = load(BASE/'entry-manifest.json')
require(len(entry) == 16, 'entry count')
for t in [*entry, load(BASE/'operator-manifest.json')]:
    matches(BASE/t['path'], t)
op = load(BASE/'operator-result.json')
require(op['controlActualExit'] == op['archiveActualExit'] == 0 and op['engineering'] is False, 'actual operator exit')
cmd = load(BASE/'operator-command.json')
for key, filename in [('controlArgv','controller.py'), ('archiveArgv','archive-controls.py')]:
    require(cmd[key] == ['-I','-S','-B',str(BASE/filename)], 'operator command')
require('ALL 98 PASS; no Gradle' in (BASE/'controller.log').read_text(encoding='utf-8-sig'), 'controller raw log')

expected = {'normal':None,'argv':None,'nonzero':'NONZERO_EXIT','dual':None,'continuous':'DEADLINE','overflow':'LOG_LIMIT','child-pipe':'DEADLINE','nonzero-child-pipe':'NONZERO_EXIT','detached-child':'OWNED_DESCENDANT_REQUIRES_TERMINATION','start-failure':'PARENT_FAILURE:','assign-failure':'PARENT_FAILURE:','unobserved-settlement':'OWNED_SETTLEMENT_INCOMPLETE','cap-exact':None,'cap-plus-one':'LOG_LIMIT','cancel':'CANCELLED','tool-change':'TOOL_IDENTITY_CHANGED','compile-zero-xml':'NONZERO_EXIT','compile-stat-failure':'NONZERO_EXIT','nonzero-child-overflow':'NONZERO_EXIT','skip-live':None,'skip-native':'UNAPPROVED_SKIP:'}
new_expected = {'exit-tail-zero':None,'exit-tail-seven':'NONZERO_EXIT','exit-late-identity':None,'exit-no-capture-zero':None,'exit-no-capture-seven':'NONZERO_EXIT'}
new_expected.update({n:'ROOT_EXIT_EVIDENCE:' for n in ['exit-missing-event','exit-corrupt-event','exit-wrong-pid','exit-wrong-time','exit-wrong-code','exit-missing-identity','exit-corrupt-identity','exit-summary-mismatch','exit-date-coercion','exit-root-is-host']})
new_expected.update({'exit-missing-seven':'NONZERO_EXIT','exit-cancel-missing':'CANCELLED','exit-budget-missing':'DEADLINE'})
streams = 0
proofs = 0
cases = []
derived_roots = {TAG, *(TAG+'-'+x for x in ('python','process','root-exit','policy','outer'))}
uncreated = set()
used_uuid = set()

def audit_stream(item, parent=None, cap=None):
    global streams
    p = safe(item['path'])
    require(p.is_relative_to(EVIDENCE) and p.relative_to(EVIDENCE).parts[0] in roots, 'foreign stream')
    if parent is not None:
        require(p.parent == parent, 'stream parent')
    if p.exists():
        matches(p, item)
        if cap is not None:
            require(item['length'] <= cap, 'log cap')
        streams += 1
    else:
        require(item['partial'], 'missing complete stream')

def audit_proof(r, directory):
    global proofs
    proof = r['rootExitProof']
    event = load(directory/'root-exit.json')
    ident = load(directory/'root-identity.json')
    host = load(directory/'host-receipt.json')
    require(host == r['hostReceipt'], 'host receipt mismatch')
    require(set(ident) == {'pid','startTimeUtc'} and set(event) == {'schema','pid','startTimeUtc','exitCode','observationTick','elapsedMs'}, 'raw event schema')
    require(event['schema'] == 'root-exit/v1' and type(ident['pid']) is int and ident['pid']>0, 'identity value')
    require(isinstance(ident['startTimeUtc'],str) and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{7}Z',ident['startTimeUtc']), 'UTC type')
    require(ident['pid'] != r['hostPid'], 'root is host')
    for k in ('pid','startTimeUtc'):
        require(ident[k] == event[k] == proof[k], 'event identity')
    require(event['exitCode'] == proof['exitCode'] == host['rootExitCode'] == r['rootExitCode'] == r['observedRootExitCode'], 'actual exit disagreement')
    require(proof['pid'] == host['rootPid'] and proof['startTimeUtc'] == host['rootStartTimeUtc'], 'host root identity')
    require(type(event['observationTick']) is int and event['observationTick']>0 and event['observationTick'] == proof['observationTick'] == host['rootObservationTick'], 'first observation tick')
    require(host['rootExited'] and host['rootEventPublished'] and r['rootExited'], 'exit not observed')
    captured = [x for x in r['capturedDescendants'] if x['pid'] == proof['pid']]
    require(proof['parentCaptured'] == bool(captured), 'capture source')
    for c in captured:
        require(c['startTimeUtc'] == ident['startTimeUtc'] and c['exitObserved'] and c['exitCode'] == proof['exitCode'], 'captured handle disagreement')
    require(proof['source'] == 'host-held-root-handle-event'+('-and-parent-captured-handle' if captured else ''), 'proof source')
    if r['status'] == 'passed':
        require(proof['exitCode']==0 and r['ownedSettlement']=='complete' and r['hostExited'] and r['hostExitCode']==0, 'success settlement')
        require(all(x['exitObserved'] for x in r['capturedDescendants']), 'live captured handle')
        require(host['streamsCompleted'] and all(host[k]['eof'] and not host[k]['error'] and not host[k]['truncated'] for k in ('stdout','stderr')), 'success EOF')
    proofs += 1

for group, wants in [('process',expected), ('root-exit',new_expected)]:
    gd = EVIDENCE/(TAG+'-'+group)
    rows = load(gd/'results.json')
    require(rows['passed'] and {r['mode'] for r in rows['cases']} == set(wants) and len(rows['cases']) == len(wants), 'case matrix')
    neighbor = load(gd/'neighbor-exit.json')
    require(type(neighbor['pid']) is int and neighbor['pid']>0 and type(neighbor['actualWaitExitCode']) is int, 'neighbor actual wait')
    for row in rows['cases']:
        name = row['mode']
        require(load(gd/(name+'-check.json')) == row, 'case summary changed')
        spec = load(gd/(name+'-owner-spec.json'))
        stage = safe(spec['stageEvidence']); owner = safe(spec['out'])
        match = re.fullmatch(r'g11-p2-synthetic-([0-9a-f]{32})-'+re.escape(name), stage.name)
        require(match and match[1] not in used_uuid and stage.parent == EVIDENCE and owner == EVIDENCE/(stage.name+'-owner'), 'case UUID ownership')
        used_uuid.add(match[1]); derived_roots.update((stage.name,owner.name))
        require(spec['mode']=='fixture' and spec['fixture']==name and Path(spec['repo'])==REPO and Path(spec['tools'])==BASE/'tools', 'spec binding')
        require(spec['deadlineSeconds']==22 and spec['processDeadlineMs']==7000 and spec['settleMs']==1500, 'budgets changed')
        require(spec['streamCap'] == (32768 if name in ('overflow','cap-exact','cap-plus-one','nonzero-child-overflow') else 33554432), 'stream budget changed')
        outer = load(owner/'result.json')
        require(outer['settlement']==row['outerSettlement'] and outer['settlement']['actualJobQueryObservedEmpty'] and row['neighborStillRunning'], 'ownership settlement')
        require(load(owner/'command.json')['argv']==row['argv'], 'actual argv')
        wanted = wants[name]; r = row['receipt']; failure = r['primaryFailure']
        require((failure is None and r['status']=='passed' and row['outerExitCode']==outer['actualExitCode']==0) if wanted is None else (str(failure).startswith(wanted) and r['status']=='failed' and row['outerExitCode']!=0 and outer['actualExitCode']!=0), 'unexpected case: '+name)
        for item in outer['streams']:
            audit_stream(item,owner,1048576)
        run_owner = load(stage/'run-owner.json')
        scope_paths = list(stage.glob('*/scope.json'))
        require(len(scope_paths)==1, 'scope count')
        scope = load(scope_paths[0]); owned = scope_paths[0].parent
        require(Path(scope['owned'])==owned and Path(scope['paths']['Repo'])==REPO and Path(scope['paths']['EvidenceRoot'])==stage, 'scope binding')
        require(load(owned/'stage-result.json')==r, 'raw stage mismatch')
        process = r.get('processReceipt',r)
        if process.get('schema')=='process/v1':
            directory = safe(process['directory'])
            require(directory.is_relative_to(owned/'processes') and load(directory/'process-receipt.json')==process, 'raw process mismatch')
            for k in ('stdout','stderr','host-stdout','host-stderr'):
                if k in process:
                    audit_stream(process[k],directory,33554432 if not k.startswith('host-') else 1048576)
            if process.get('rootExitProof'):
                audit_proof(process,directory)
            if wanted is None:
                require(process['rootExitProof'] is not None and process['status']=='passed', 'success without proof')
            if name in ('exit-tail-zero','exit-tail-seven','exit-late-identity'):
                require(process['hostReceipt']['tailBoundaryForced'], 'tail branch not forced')
            if name in ('exit-late-identity','exit-no-capture-zero','exit-no-capture-seven'):
                require(not process['capturedDescendants'] and not process['rootExitProof']['parentCaptured'], 'late/capture branch not forced')
            if group=='root-exit' and name not in list(new_expected)[:5]:
                require(process['rootExitProof'] is None and process['rootExitEvidenceError'], 'invalid evidence accepted')
                before = list(directory.glob('*.before-fixture-fault'))
                if name != 'exit-date-coercion':
                    require(before, 'original before injected fault absent')
                if name in ('exit-missing-seven','exit-cancel-missing','exit-budget-missing'):
                    require(any(x.startswith('ROOT_EXIT_EVIDENCE:') for x in process['secondaryFailures']), 'first failure lost')
                if name == 'exit-missing-seven':
                    require(process['rootExitCode']==7 and process['primaryFailureTick']==process['hostReceipt']['primaryFailureTick'], 'nonzero tick lost')
                if name == 'exit-cancel-missing':
                    require(process['cancelled'], 'cancel not applied')
        cases.append(dict(group=group,mode=name,failure=failure,outerExit=row['outerExitCode'],rootProof=process.get('rootExitProof'),settlement=r['ownedSettlement']))

py = load(EVIDENCE/(TAG+'-python')/'results.json')
require(py['passed'] and len(py['cases'])==21 and all(c['passed'] for c in py['cases']), 'Python contracts')
policy = load(EVIDENCE/(TAG+'-policy')/'results.json')
require(policy['passed'] and len(policy['cases'])==16 and len(policy['rolePolicy'])==15 and all(c['actualRefusal'] for c in policy['cases']), 'policy contracts')
for row in policy['rolePolicy']:
    require(bool(row['actualFailures']) != row['allowed'] if 'allowed' in row else bool(row['actualRefusal']), 'role contract')
outer_rows = load(EVIDENCE/(TAG+'-outer')/'results.json')
require(outer_rows['passed'] and len(outer_rows['cases'])==7, 'outer count')
for row in outer_rows['cases']:
    name = row['mode']; r = row['result']
    owner = EVIDENCE/(TAG+'-outer-'+name+'-owner')
    derived_roots.add(owner.name)
    require(load(owner/'result.json')==r and r['settlement']['actualJobQueryObservedEmpty'], 'outer raw/settlement')
    require(row['wrapperExit']==0 if name=='normal' else row['wrapperExit']!=0, 'outer false pass')
    if name.startswith('nonzero'):
        require(r['actualExitCode']==7, 'outer first exit lost')
    if name=='detached-child':
        require(r['actualExitCode']==0 and r['settlement']['beforeTermination'], 'outer residual not exercised')
    for item in r['streams']:
        audit_stream(item,owner,1048576)
require(derived_roots == set(roots), 'archive roots not exactly derived from case specs')
result = dict(schema='root-controls-review/v1',accepted=True,engineeringAuthorized=True,engineeringRun=False,manifestSha256=manifest_sha,roots=91,files=len(actual),bytes=total,controls=dict(python=21,process=21,rootExit=18,policy=31,outer=7,total=98),rawLogIdentities=streams,strongExitProofs=proofs,cases=cases,reviewerNotes=['Earlier read guessed exit-tail-zero-spec.json; absent filename query only, correct actual file is exit-tail-zero-owner-spec.json. No tested process rerun.'])
with (HERE/'controls-review.json').open('x',encoding='utf-8') as f:
    json.dump(result,f,ensure_ascii=False,indent=2)
print(json.dumps({k:v for k,v in result.items() if k!='cases'},ensure_ascii=False))
