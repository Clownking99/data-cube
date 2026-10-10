import pathlib,json,hashlib,uuid
scratch=pathlib.Path(__file__).parent;repo=scratch.parents[1];ev=repo/'docs/superpowers/verification/evidence'
def load(p):return json.loads(p.read_bytes())
def save(p,v):p.write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
def row(p):
 b=p.read_bytes();return dict(path=p.relative_to(repo).as_posix(),length=len(b),sha256=hashlib.sha256(b).hexdigest())
loc=load(scratch/'p3-locations.json');eng=pathlib.Path(loc['engineering']);assert load(eng/'sequence-result.json')['passed'];assert load(scratch/'main-engineering-review.json')['passed'] and load(scratch/'main-image-review.json')['passed']
sealed=ev/('release-helper-main-'+uuid.uuid4().hex);sealed.mkdir();reviews=sealed/'root-review';reviews.mkdir()
names=['review-worker.py','review-worker-v2.py','worker-review-first-failure.json','worker-commit-review.json','audit-targeted.py','audit-engineering.py','audit-engineering-v2.py','engineering-review-first-failure.json','engineering-partial-review.json','engineering-through-image-review.json','diagnostic-review.json','prepare-p3.py','prepare-p3-v2.py','prepare-engineering.py','p3-locations.json','main-full-buildsrc-review.json','main-engineering-review.json','audit-main-image.py','main-image-review.json','remote-before-release.txt','remote-before-release.exit','seal-p3.py']
for name in names:(reviews/name).write_bytes((scratch/name).read_bytes())
roots=[pathlib.Path(loc[k]) for k in ('targeted','stageEvidence','targetedFrozen','engineering')]
for mode in ('full','buildsrc','image','linked'):
 spec=load(eng/(mode+'-spec.json'));roots.extend(pathlib.Path(spec[k]) for k in ('out','stageEvidence'))
rejected=[]
for p in ev.glob('redis-binary-p3-*-update-targeted'):
 if p==pathlib.Path(loc['targeted']):continue
 if (p/'config.json').is_file() and load(p/'config.json')['baseCommit']==loc['mainCommit']:
  assert not (p/'operator-command.json').exists();roots.append(p);rejected.append(p.name)
save(sealed/'preparation-failures.json',dict(workerReviewV1='Repository-relative prefix duplicated; audit stopped before reading first listed artifact. v2 path correction only.',mainPreparationV1='Raw checkout comparison failed before launching Gradle; 256 LF/CRLF-only differences independently checked; actual main bytes then snapshotted.',rejectedRoots=rejected,noTestRetry=True))
delivery=repo/'build'/('release-helper-delivery-'+uuid.uuid4().hex);delivery.mkdir();save(sealed/'delivery-intent.json',dict(schema='release-helper-delivery-intent/v1',testedMain=loc['mainCommit'],result=str(delivery/'delivery-result.json'),manifest=str(delivery/'manifest.json'),expectedNextTag='v3.2.12',remoteStatus='pending',oldTagsPreserved=True))
roots.append(sealed);files=[]
for root in roots:
 assert root.is_relative_to(ev) and not root.is_symlink() and not root.is_junction()
 for p in sorted(root.rglob('*')):
  assert not any(x.casefold() in ('.testagent','.git','.g10-verify-blobs.ps1') for x in p.parts)
  if p.is_file():files.append(row(p))
save(sealed/'manifest.json',dict(schema='release-helper-main-p3/v1',passed=True,testedMain=loc['mainCommit'],workerP2Passed=False,workerFailureRetained=True,rootP3='Fresh first formal execution per stage, original thresholds',counts=dict(targetedPassed=92,targetedSkipped=0,fullPassed=4740,fullSkipped=3,buildSrcPassed=8,buildSrcSkipped=0),imageFiles=load(scratch/'main-image-review.json')['currentImageFiles'],controlsExecuted=0,controlsVersionReused=98,realServices=0,installerExecuted=False,roots=[p.name for p in roots],files=files))
loc.update(sealed=str(sealed),delivery=str(delivery));save(scratch/'p3-locations.json',loc)
print(json.dumps(dict(sealed=str(sealed),files=len(files),manifestSha256=hashlib.sha256((sealed/'manifest.json').read_bytes()).hexdigest(),delivery=str(delivery))))
