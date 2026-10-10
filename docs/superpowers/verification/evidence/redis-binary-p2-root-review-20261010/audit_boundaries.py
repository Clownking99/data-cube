"""Read-only independent named-path result and current-source audit; no tested imports."""
import hashlib,json,re
from pathlib import Path

repo=Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')
evidence=repo/'docs/superpowers/verification/evidence'
base=evidence/'redis-binary-p2-a3fd7057b71e4152880a33c776c42984-controls'
here=Path(__file__).absolute().parent
def need(ok,why):
    if not ok:raise RuntimeError(why)
def load(path):return json.loads(path.read_bytes())
def identity(path):
    raw=path.read_bytes();return dict(length=len(raw),sha256=hashlib.sha256(raw).hexdigest())

accepted=load(here/'controls-review.json');need(accepted['accepted'],'controls not accepted')
rows={lang:load(base/('boundary-'+lang+'-result.json')) for lang in ['python','powershell']}
for lang,total in [('python',34),('powershell',36)]:
    report=rows[lang];need(report['passed'] and len(report['cases'])==total,'boundary count/status')
    cases=report['cases'];need(sum(x.get('accepted') is True for x in cases)==20,'positive count')
    need({x['case'] for x in cases if x['case'].startswith('g11-')}=={'g11-p2-legacy','g11-p3-legacy'},'legacy identity')
py=rows['python']['cases']
need(sum(x.get('accepted') is False for x in py)==6,'six lexical rejects')
expected={'foreign-out':'OUTER_EVIDENCE_OUTSIDE_G11_P2_P3','nested-stage':'OUTER_EVIDENCE_OUTSIDE_G11_P2_P3','malformed-stage':'OUTER_EVIDENCE_OUTSIDE_G11_P2_P3','tools-extra-level':'OUTER_TOOL_ROOT_OUTSIDE_RUN','tools-wrong-leaf':'OUTER_TOOL_ROOT_OUTSIDE_RUN','image-wrong-basename':'OUTER_IMAGE_SOURCE_OUTSIDE_RUN','image-bad-owned':'OUTER_IMAGE_SOURCE_OUTSIDE_RUN','image-foreign-root':'OUTER_IMAGE_SOURCE_OUTSIDE_RUN'}
actual=[x for x in py if 'actualRefusal' in x]
need(len(actual)==8 and {x['case'] for x in actual}==set(expected),'dynamic Python matrix')
for row in actual:
    need(row['actualRefusal']==expected[row['case']] and row['rejectedPhaseNoLinksVisits']==[],'Python refusal/access')
    path=Path(row['actualSpec']);need(path.parent==base and row['legalSpecReads']==[str(path)],'legal actual spec')
ps=rows['powershell']['cases']
foreign=[r for r in ps if r['case']=='new-scope-foreign-root']
need(len(foreign)==8 and all(r['actualRefusal']=='UNADMITTED_NAMED_EVIDENCE' and r['targetOrAncestorMetadataCalls']==[] for r in foreign),'PS actual foreign roots')
owned=[r for r in ps if r['case']=='invalid-owned']
need({r['leaf']:r['actualRefusal'] for r in owned}=={'not-a-uuid':'UNADMITTED_NAMED_SCOPE','A'*32:'UNADMITTED_NAMED_SCOPE','b'*32+'/nested':'UNADMITTED_NAMED_EVIDENCE'},'owned negatives')
bad=[r for r in ps if r['case']=='forbidden-synthetic-string']
need(len(bad)==4 and {r['actualRefusal'] for r in bad}=={'FORBIDDEN_PATH','INVALID_PATH_COMPONENT','ABSOLUTE_LOCAL_PATH_REQUIRED','DEVICE_PATH'},'synthetic lexical rejects')
stage=[r for r in ps if r['case']=='actual-stage-wrong-basename'];need(len(stage)==1 and stage[0]['actualRefusal']=='SCOPE_FILE_IDENTITY_MISMATCH' and stage[0]['targetNoReparseOrGetContentCommands']==0,'actual stage filename')
collision=load(base/'collision-result.json');normal=load(evidence/(base.name+'-process')/'normal-owner-spec.json')
need(collision['passed'] and collision['actualRefusal']=='RUN_COLLISION' and collision['actualRoot']==normal['stageEvidence'],'actual collision binding')
before=load(base/'inputs-before.json');after=load(base/'inputs-after.json');need(before==after,'inputs changed')
for row in before['files']:
    rel=row['path'].replace('\\','/');need(not any(p in ('.testagent','.git','.g10-verify-blobs.ps1','..') for p in rel.split('/')),'forbidden input')
    need(identity(repo/rel)=={'length':row['length'],'sha256':row['sha256'].lower()},'current input changed: '+rel)
receipt=dict(schema='root-redis-binary-boundaries/v1',accepted=True,python=34,powershell=36,realCollision=1,currentInputs=len(before['files']),rawResults={name:identity(base/name) for name in ['boundary-python-result.json','boundary-powershell-result.json','collision-result.json','operator-result.json']},limits=['Observes actual NoReparse/no_links/Get-Content calls plus reviewed source ordering; not OS-wide filesystem tracing','Two wrong-tools cases simulate only __file__; legal spec/tool reads are permitted','No Java/FX/product tests have run at this control checkpoint'])
with (here/'boundary-review.json').open('x',encoding='utf-8') as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
print(json.dumps(receipt,ensure_ascii=False))
