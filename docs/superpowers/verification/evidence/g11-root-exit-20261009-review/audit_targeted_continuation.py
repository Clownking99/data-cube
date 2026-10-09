"""Review the rejected controller and unchanged, actually passed targeted run."""
import hashlib
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

here=Path(__file__).absolute().parent
repo=Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
ev=repo/'docs/superpowers/verification/evidence'
tag='g11-p2-eng-11965af76c694c6798f2074fff74d8bf'
base=ev/tag
def need(v,m):
    if not v:raise RuntimeError(m)
def safe(p):
    p=Path(p)
    need(p.is_absolute() and all(x.casefold() not in ('.testagent','.git','.g10-verify-blobs.ps1') and x not in ('.','..') for x in p.parts),'path')
    for node in (p,*p.parents):need(not node.is_symlink() and not node.is_junction(),'link')
    return p
def load(p):return json.loads(safe(p).read_bytes())
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
mf=ev/(tag+'-rejected')/'manifest.json'
need(sha(mf)=='9cc7a935cbfe5ac1d7c4826c9cdb5e4ceff99be3bb88438ace25002b5bb3552f','manifest')
m=load(mf);need(m['accepted'] is False and m['engineeringComplete'] is False,'rejected status')
need(set(m['roots'])=={tag,'g11-p2-eng-ce52e07a5f234db388d43d97c9fcea4e-targeted','g11-p2-eng-ce52e07a5f234db388d43d97c9fcea4e-targeted-owner'},'roots')
actual=set()
for root in m['roots']:
    for current,dirs,names in os.walk(safe(ev/root),followlinks=False):
        for n in dirs:safe(Path(current)/n)
        for n in names:actual.add(safe(Path(current)/n).relative_to(ev).as_posix())
need(actual=={x['path'] for x in m['files']} and len(actual)==213,'inventory')
total=0
for row in m['files']:
    p=safe(ev/row['path']);need(p.stat().st_size==row['length'] and sha(p)==row['sha256'],'raw identity');total+=row['length']
need(total==m['totalBytes']==1767823,'bytes')
old=(base/'controller.py').read_bytes()
proposal=ev/'g11-p2-eng-native-review-3c86ac32bf824e729d81c7ef043c885a/controller-next.py'
need(sha(proposal)=='cfc783054449d410042f9181fe5ae307ca46adad0e918e958130aff9686afbe8','proposal identity')
before=b"case.get('classname','').startswith('com.datacube.fx.RedisPane')"
after=b"case.get('classname')=='com.datacube.fx.RedisPaneBudgetTest'"
need(old.count(before)==1 and old.replace(before,after)==proposal.read_bytes(),'only exact native class correction')
need(load(base/'operator-result.json')['actualExitCode']==1 and not load(base/'sequence-result.json')['passed'],'first failure preserved')
need(load(base/'targeted-actual-exit.json')['actualExitCode']==0,'actual targeted exit')
r=load(here/'targeted-review.json')
need(r['counts']==dict(tests=292,failures=0,errors=0,skipped=1) and r['passedCases']==291,'raw counts')
need(len(r['skips'])==1 and r['skips'][0]['class']=='com.datacube.redis.RedisLiveIntegrationTest' and r['skips'][0]['name']=='standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()','live skip identity')
need('set DATACUBE_REDIS_HOST and DATACUBE_REDIS_PASSWORD to run live Redis smoke test' in r['skips'][0]['reason'],'live skip reason')
native=[];ordinary=[]
for row in r['xml']:
    if not row['path'].endswith(('.RedisPaneBudgetTest.xml','.RedisPaneCloseSequenceTest.xml')):continue
    p=safe(Path(r['scope'])/'xml'/row['path']);need(sha(p)==row['sha256'],'XML identity')
    for case in ET.fromstring(p.read_bytes()).findall('testcase'):
        need(all(case.find(k) is None for k in ('failure','error','skipped')),'case not passed')
        (native if case.get('classname')=='com.datacube.fx.RedisPaneBudgetTest' else ordinary).append(case.get('name'))
need(len(native)==11 and ordinary==['queueFailureStillAttemptsSessionClose()'],'native classification')
receipt=dict(schema='root-targeted-continuation/v1',targetedAccepted=True,engineeringComplete=False,originalControllerExit=1,targetedActualExit=0,manifestSha256=sha(mf),files=213,bytes=total,proposalSha256=sha(proposal),nativePassed=native,ordinaryUnitNotNative=ordinary,continuation='New controller may strictly re-audit and bind this immutable targeted run, then execute only full/buildsrc/image/linked with unchanged inputs/tools; no test rerun needed.')
with (here/'targeted-continuation-review.json').open('x',encoding='utf-8') as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
print(json.dumps(receipt,ensure_ascii=False))
