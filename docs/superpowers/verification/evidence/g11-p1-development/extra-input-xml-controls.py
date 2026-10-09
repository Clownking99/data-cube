import importlib.util
import json
from pathlib import Path
import subprocess
import sys

directory=Path(__file__).absolute().parent
repo=directory.parents[4]
tool=repo/'docs/superpowers/verification/evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/tools/evidence_tools.py'
spec=importlib.util.spec_from_file_location('frozen_evidence',tool)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
out=directory/'additional-controls';out.mkdir(exist_ok=False)
records=[]
def reject(name,call):
 try:call()
 except module.Refusal as error:
  records.append({'name':name,'actualRefusal':str(error)});return
 raise RuntimeError('MISSING_REFUSAL:'+name)
fake=out/'fixture-repo';(fake/'src/legitimate/build').mkdir(parents=True);(fake/'test').mkdir()
(fake/'src/legitimate/build/A.java').write_text('class A {}',encoding='utf-8')
(fake/'test/A.java').write_text('class A {}',encoding='utf-8')
paths=['src/legitimate/build/A.java','test/A.java']
before=module.snapshot(str(fake),paths,'a'*40,['gradle.properties'])
(out/'inputs-before.json').write_text(json.dumps(before,indent=2),encoding='utf-8')
unknown=fake/'src/legitimate/build/Ignored.java';unknown.write_text('class Ignored {}',encoding='utf-8')
(fake/'.gitignore').write_text('src/legitimate/build/Ignored.java\n',encoding='utf-8')
argv=['git','-C',str(repo),'check-ignore','--no-index','-v','--',str(unknown.relative_to(repo))]
result=subprocess.run(argv,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
(out/'git-ignore-stdout.log').write_bytes(result.stdout);(out/'git-ignore-stderr.log').write_bytes(result.stderr)
(out/'git-ignore-command.json').write_text(json.dumps({'argv':argv,'actualExitCode':result.returncode}),encoding='utf-8')
if result.returncode!=0:raise RuntimeError('GIT_IGNORE_CONTROL_NOT_PROVEN')
reject('actual-git-ignored-unknown-java-in-build-package',lambda:module.snapshot(str(fake),paths,'a'*40,['gradle.properties']))
optional=out/'optional-repo';(optional/'test').mkdir(parents=True);(optional/'test/A.java').write_text('class A {}')
baseline=module.snapshot(str(optional),['test/A.java'],'a'*40,['gradle.properties'])
(out/'optional-before.json').write_text(json.dumps(baseline,indent=2),encoding='utf-8')
(optional/'gradle.properties').write_text('# synthetic optional appearance\n')
reject('optional-input-appearance',lambda:module.snapshot(str(optional),['test/A.java'],'a'*40,['gradle.properties']))
xml=out/'duplicate-suite';xml.mkdir()
body='<testsuite name="same" tests="1" failures="0" errors="0" skipped="0"><testcase classname="C" name="x"/></testsuite>'
for name in ('TEST-a.xml','TEST-b.xml'):(xml/name).write_text(body)
reject('duplicate-suite-files',lambda:module.xml_results(xml))
binding=json.loads(json.dumps(before));binding['unknownField']=True
(out/'unknown-field-binding.json').write_text(json.dumps(binding,indent=2))
reject('unknown-binding-field',lambda:module.verify(before,binding))
(out/'results.json').write_text(json.dumps({'schema':'additional-controls/v1','passed':True,'cases':records},indent=2),encoding='utf-8')
