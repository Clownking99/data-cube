"""Review this docs-only change and bind concrete new evidence without rerunning Gradle."""
from pathlib import Path
import datetime, hashlib, json, re, subprocess
O=Path(__file__).resolve().parent;R=O.parents[4]
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def git(*a):return subprocess.check_output(['git',*a],cwd=R)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
head=git('rev-parse','HEAD').decode().strip();assert head=='567a5293528a20c2d4eae6daafabe2242064b676'
assert git('diff','--name-only',head,'--','src','test','buildSrc','build.gradle','settings.gradle','gradle.properties','.github/workflows',':(exclude)**/.testagent/**')==b''
docs=['README.md','docs/handoffs/CURRENT.md','docs/handoffs/2026-09-23-product-maturity-goal-handoff.md','docs/superpowers/plans/2026-09-23-product-maturity-roadmap.md','docs/superpowers/plans/2026-10-09-maintenance-cost-review.md','docs/superpowers/plans/2026-10-09-g10-redis-resource-budget.md','docs/superpowers/verification/2026-10-09-g10-redis-coordination.md','docs/maintenance/2026-10-09-maintenance-cost-baseline.md','docs/maintenance/verification-guide.md','docs/maintenance/verification-runner-design.md']
trackedChanges=git('diff','--name-only','-z','--','.',':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**').decode().strip('\0').split('\0')
assert set(trackedChanges).issubset(set(docs)),trackedChanges
for name in ('baseline.json','baseline-final-collector.json'):
    b=read(O/name);assert b['head']==head and b['trackedFiles']==28985 and b['evidence']['files']==27764
b=read(O/'baseline-final-collector.json');assert b['collectorSha256']==sha(R/'docs/maintenance/collect_costs.py')
assert read(O/'collector-shipping-fixture.json')['passed']
assert read(O/'g10-delivery-result.json')['passed'] and read(O/'g10-delivery-result.json')['head']==head
links=[];out=O/'review-receipt.json';assert not out.exists()
for name in docs:
    p=R/name
    for target in re.findall(r'\]\(([^)]+)\)',p.read_text(encoding='utf-8-sig')):
        if '://' in target or target.startswith('#'):continue
        target=target.split('#')[0]
        if not target:continue
        dest=(p.parent/target).resolve()
        assert dest.is_relative_to(R) and '.testagent' not in dest.parts,str(dest)
        assert dest==out or dest.exists(),(name,target)
        links.append(dict(source=name,target=target))
bindings=[dict(path=name,bytes=(R/name).stat().st_size,sha256=sha(R/name)) for name in docs+['docs/maintenance/collect_costs.py','docs/maintenance/check_collect_costs.py']]
allNewFiles=git('ls-files','--others','--exclude-standard','-z','--','docs/maintenance','docs/handoffs/CURRENT.md',str(O.relative_to(R)).replace('\\','/'),':(exclude)**/.testagent/**').decode().strip('\0').split('\0')
assert not any(p.endswith('.pyc') for p in allNewFiles)
for phase in ('p1a','p1b','p2'):
    manifest=R/f'docs/superpowers/verification/evidence/g10-redis-20261009-{phase}-worker/raw-manifest.json'
    for row in read(manifest)['files']:
        p=R/row['path'];assert p.stat().st_size==row['length'] and sha(p)==row['sha256'].lower()
for row in read(R/'docs/superpowers/verification/evidence/g10-main-20261009/raw-manifest.json')['files']:
    p=R/row['path'];assert p.stat().st_size==row['length'] and sha(p)==row['sha256'].lower()
result=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),baselineHead=head,productTestBuildWorkflowUnchanged=True,localLinksChecked=len(links),links=links,bindings=bindings,collectorFixturePassed=True,workerReviewTurn='01a11e86-70c8-7ee2-8a94-a9973fd0aa1b',rootReadSourceIndependently=True,frozenG10OriginalsRehashed=1651,localGradleRerun=False,scope='Docs/README, read-only inventory and synthetic fixture only; no architecture or archived evidence changes',passed=True)
out.write_bytes((json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode());print(json.dumps({k:v for k,v in result.items() if k not in ('links','bindings')}))
