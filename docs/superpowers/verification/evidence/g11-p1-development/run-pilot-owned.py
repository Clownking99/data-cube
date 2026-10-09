"""Single authorized targeted pilot under independent actual outer ownership."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time

directory=Path(__file__).absolute().parent
repo=directory.parents[4]
spec=importlib.util.spec_from_file_location('owned_helpers',directory/'old-comparison-final/check-core.frozen.py')
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
pwsh='C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe'
python='C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
evidence=repo/'docs/superpowers/verification/evidence/g11-p1-targeted-001'
argv=[pwsh,'-NoLogo','-NoProfile','-NonInteractive','-File',str(repo/'scripts/verification/run-stage.ps1'),
      '-Repo',str(repo),'-EvidenceRoot',str(evidence),'-Jdk','D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8',
      '-Cache','C:/Users/hetia/.gradle','-Pwsh',pwsh,'-Python',python,'-Mode','targeted',
      '-InputSpec',str(directory/'targeted-input-spec.json'),'-DeadlineMs','600000','-SettleMs','5000']
def identities():
 return [{'path':str(p.relative_to(repo)),'length':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted((repo/'scripts/verification').glob('*')) if p.is_file()]
before=identities();owner=helper.OuterOwner();process=None;failure=None;began=time.monotonic()
(directory/'pilot-command.json').write_text(json.dumps({'argv':argv,'toolBootstrapBefore':before,'outerDeadlineSeconds':660},ensure_ascii=False,indent=2),encoding='utf-8')
try:
 with (directory/'pilot-outer-stdout.log').open('xb') as out,(directory/'pilot-outer-stderr.log').open('xb') as err:
  process=subprocess.Popen(argv,cwd=repo,stdout=out,stderr=err);owner.assign(process)
  try:code=process.wait(timeout=660)
  except subprocess.TimeoutExpired:
   failure='OUTER_DEADLINE';owner.terminate();code=process.wait(timeout=5)
finally:settlement=owner.close()
after=identities()
result={'schema':'pilot-owner/v1','actualExitCode':code,'outerFailure':failure,'elapsedSeconds':time.monotonic()-began,'outerSettlement':settlement,'toolBootstrapBefore':before,'toolBootstrapAfter':after,'toolBootstrapUnchanged':before==after,'evidence':str(evidence)}
(directory/'pilot-owner-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(code if code else (1 if failure or before!=after else 0))
