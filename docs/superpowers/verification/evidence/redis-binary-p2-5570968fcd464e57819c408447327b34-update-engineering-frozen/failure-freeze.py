import json,pathlib,hashlib,os
base=pathlib.Path('docs/superpowers/verification/evidence/redis-binary-p2-5570968fcd464e57819c408447327b34-update-engineering').resolve();ev=base.parent
load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
roots=[base];specs=[]
for mode in ['full','buildsrc','image','linked']:
 s=load(base/(mode+'-spec.json'));roots.extend([pathlib.Path(s['stageEvidence']),pathlib.Path(s['out'])]);specs.append(mode+'-spec.json')
report='''# Release helper engineering handoff — not accepted

Full: 354 suites, 4743 tests, 4740 passed, 3 expected live skips, zero failures/errors. Exact accepted update cases (11 original and 7 new) passed; all 14 native RedisPane cases passed. Forced buildSrc: 8/8 passed. Image: passed. Linked: failed, outer actual exit 1. Operator actual exit 1; no complete-engineering success is claimed.

Linked module-index, probe-compile and driver-discovery passed. redis-linked Java root actually exited 0, host exited 0, streams reached EOF; supervisor recorded OWNED_DESCENDANT_REQUIRES_TERMINATION, termination requested and ownedSettlement complete. This failure must remain a failure. Its precise scheduling/ownership cause has not been established. No tool edits, no automatic retry, no accepted-stage rerun.

Actual entries retained: controller.py and run-engineering.ps1, entry-manifest.json (SHA-256 2859193d63b2d24f46c3d5fd477aa936514f3af0e9b9b97b28d4549a055f840f). Actual stage specs: full-spec.json, buildsrc-spec.json, image-spec.json, linked-spec.json. Each binds fresh evidence/owner/runtime roots and the unchanged 12 tools. Accepted controls reused, zero controls executed. The prepared success-only finish.py was not run because its prerequisites failed. failure-freeze.py seals the raw attempt without changing its outcome.

Accepted targeted evidence: ../redis-binary-p2-2fecc37fd6c9448ebcc8dcbfc151faa5-update-targeted; 92/92 passed, zero skips, original manifest SHA-256 ac1742589bebb51099c4ae1b9ccb655dd5046b03b9296b5f9233a3fedc00d50d. Its source.diff is complete, SHA-256 22101f26c307f21ff7d2becd66eb43c33cf55c606d652db12795d391c7eec54e. Its actual controller.py, stage.ps1 and frozen tools/isolated.gradle remain available. No separate preparation generator was executed; preparation used inline tool code. Do not rerun old scopes: mint a fresh UUID and rebind verified current inputs if further work is authorized.

Retained first preparation refusal: ../redis-binary-p2-663bf4c554aa4097b20e32407fb257d9-update-targeted and its -stage sibling, frozen manifest SHA-256 976ad62b8ec47794e4971099d897969d09da3a2a582e981be63ecc0f87c16254. RECEIPT_INPUT_OUTSIDE_OWNED_SCOPE occurred before Gradle; accepted targeted used a fresh owned baseline copy.

Source SHA-256, unchanged after engineering:
- PortableUpdateHelperTest.java: 53b84274b761e54284b97b6600e6bb9e843b85b493829a22391ebdddad86c86c
- UpdateHelperProcess.java: e412c6d35cb8d103ab5926fbe98ded4e03907b2ed66f25374074e6ee16b0e92d
- UpdateHelperProcessTest.java: 132fe04ca3b5d9cb8e55031bbd255a69c21a6e67ed46a0914db62a41c1eb5c48

The runner is test-only: startup 90s, execution 20s, cleanup 10s; ready acknowledgement, bounded stream capture, actual retained Process exit and restored interruption. Three new tests are deterministic models and four use actual synthetic PowerShell. Existing product/image/recovery assertions remain. Original release timeout cannot be attributed conclusively to startup or execution. Synchronous OS process launch has no absolute hard bound. This single accepted targeted run is not repeat-stability or remote release acceptance.

No commit, merge, push, tag, real service or installation. Stop for independent review of the retained linked failure.
'''
(base/'report.md').write_text(report,encoding='utf-8')
files=[]
for root in roots:
 for current,dirs,names in os.walk(root,followlinks=False):
  for name in dirs+names:
   p=pathlib.Path(current)/name
   if p.is_symlink():raise RuntimeError('link refused')
  for name in names:
   p=pathlib.Path(current)/name;b=p.read_bytes();files.append(dict(path=p.relative_to(ev).as_posix(),length=len(b),sha256=hashlib.sha256(b).hexdigest()))
manifest=dict(schema='release-helper-engineering-failed-freeze/v1',passed=False,reason='OWNED_DESCENDANT_REQUIRES_TERMINATION',actualOperatorExit=1,roots=[r.name for r in roots],fileCount=len(files),files=sorted(files,key=lambda r:r['path']))
target=base.with_name(base.name+'-frozen')/'failed-manifest.json'
assert not target.exists();target.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8');print(len(files),hashlib.sha256(target.read_bytes()).hexdigest())
