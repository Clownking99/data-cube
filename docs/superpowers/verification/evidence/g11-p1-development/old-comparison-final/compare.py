import difflib
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time

root=Path(__file__).absolute().parent
repo=root.parents[5]
spec=importlib.util.spec_from_file_location('frozen_helpers',root/'check-core.frozen.py')
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
pwsh='C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe'
python='C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
original=root/'Run-P3.original.ps1';adapter=root/'Run-OldControlled.ps1'
(root/'controlled.diff').write_text(''.join(difflib.unified_diff(original.read_text(encoding='utf-8-sig').splitlines(True),adapter.read_text(encoding='utf-8-sig').splitlines(True),fromfile=original.name,tofile=adapter.name)),encoding='utf-8')
identities=[{'file':p.name,'length':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (original,adapter,root/'check-core.frozen.py',Path(__file__))]
records=[]
for mode in ('normal','nonzero','argv','dual','child-pipe','nonzero-child-pipe','zero-xml'):
 out=root/mode;out.mkdir(exist_ok=False);xml=out/'xml';xml.mkdir()
 if mode!='zero-xml':
  (xml/'TEST-a.xml').write_text('<testsuite name="a" tests="2" failures="0" errors="0" skipped="1"><testcase classname="C" name="same"/><testcase classname="C" name="same"><skipped/></testcase></testsuite>',encoding='utf-8')
 fixture='normal' if mode=='zero-xml' else mode
 argv=[pwsh,'-NoLogo','-NoProfile','-NonInteractive','-File',str(adapter),'-Python',python,'-FixtureScript',str(root/'check-core.frozen.py'),'-Mode',fixture,'-Out',str(out),'-Xml',str(xml)]
 owner=helper.OuterOwner();process=None;failure=None;began=time.monotonic()
 try:
  with (out/'outer-stdout.log').open('xb') as stdout,(out/'outer-stderr.log').open('xb') as stderr:
   process=subprocess.Popen(argv,stdout=stdout,stderr=stderr,cwd=root);owner.assign(process)
   try: code=process.wait(timeout=8)
   except subprocess.TimeoutExpired:
    failure='OUTER_DEADLINE';owner.terminate();code=process.wait(timeout=5)
 finally: settlement=owner.close()
 exit_path=out/'exit.json';summary_path=out/'test-summary.json'
 receipt={'mode':mode,'argv':argv,'elapsedSeconds':time.monotonic()-began,'outerExitCode':code,'firstFailure':failure,'outerSettlement':settlement,
          'rootExitObserved':json.loads((out/'root-exit-observed.json').read_text(encoding='utf-8-sig')) if (out/'root-exit-observed.json').exists() else None,
          'oldExit':json.loads(exit_path.read_text(encoding='utf-8-sig')) if exit_path.exists() else None,
          'oldSummary':json.loads(summary_path.read_text(encoding='utf-8-sig')) if summary_path.exists() else None,
          'inputIdentitiesBefore':identities,
          'inputIdentitiesAfter':[{'file':p.name,'length':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (original,adapter,root/'check-core.frozen.py',Path(__file__))]}
 if receipt['inputIdentitiesBefore']!=receipt['inputIdentitiesAfter']: raise RuntimeError('OLD_INPUT_CHANGED')
 if mode in ('child-pipe','nonzero-child-pipe'):
  if failure!='OUTER_DEADLINE':raise RuntimeError('EXPECTED_OLD_PIPE_HANG')
 elif failure or code!=(7 if mode=='nonzero' else 0): raise RuntimeError('OLD_EXIT_MISMATCH')
 if mode=='zero-xml' and receipt['oldSummary']['tests']!=0: raise RuntimeError('OLD_ZERO_XML_CONTROL')
 if mode not in ('child-pipe','nonzero-child-pipe','zero-xml') and (receipt['oldSummary']['tests']!=2 or receipt['oldSummary']['skipped']!=1): raise RuntimeError('OLD_LEGAL_XML_COUNTS')
 (out/'comparison-result.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8');records.append(receipt)
(root/'comparison-results.json').write_text(json.dumps({'schema':'controlled-comparison/v1','passed':True,'interpretation':'normal/nonzero/argv/dual and legal duplicate-name XML retain semantics; old pipe hang bounded by independent owner; old zero-XML success intentionally rejected by new core; no product regression claim','cases':records},ensure_ascii=False,indent=2),encoding='utf-8')
