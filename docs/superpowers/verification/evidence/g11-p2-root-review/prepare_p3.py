"""Prepare a new G11 main verification package without executing any stage."""
import hashlib
import json
from pathlib import Path
import subprocess
import uuid


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


repo = Path(__file__).absolute().parents[5]
require(str(repo) == str(Path('D:/Projects/朝花夕拾')), 'unexpected checkout')
evidence = repo / 'docs/superpowers/verification/evidence'
source = evidence / 'g11-p2-package-v2'
head = subprocess.run(['D:/Git/cmd/git.exe', 'rev-parse', 'HEAD'], cwd=repo,
                      check=True, capture_output=True, text=True).stdout.strip()
suffix = uuid.uuid4().hex[:12]
prefix = 'g11-p3-' + suffix
package = evidence / (prefix + '-package')
package.mkdir()
manifest = json.loads((source / 'tool-manifest.json').read_text())
for item in manifest:
    relative = item['path']
    require('..' not in relative.split('/') and '.testagent' not in relative.lower() and ':' not in relative, 'tool path')
    content = (repo / 'scripts/verification' / relative).read_bytes()
    require(len(content) == item['length'] and hashlib.sha256(content).hexdigest() == item['sha256'], 'tool bytes changed')
    target = package / 'tools' / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('xb') as stream:
        stream.write(content)
with (package / 'tool-manifest.json').open('x', encoding='utf-8') as stream:
    json.dump(manifest, stream, indent=2)
inputs = json.loads((source / 'inputs.json').read_text())
inputs['repo'] = str(repo)
inputs['testedCommit'] = head
with (package / 'inputs.json').open('x', encoding='utf-8') as stream:
    json.dump(inputs, stream, ensure_ascii=False, indent=2)
controller = (source / 'run-sequence.py').read_text(encoding='utf-8')
original = hashlib.sha256((source / 'run-sequence.py').read_bytes()).hexdigest()
require(original == '853c05437eed258e351d2aec74a30fba41edd5feb14f916fd4856dd4f005ee61', 'source controller changed')
controller = controller.replace('g11-p2-v2-', prefix + '-')
controller = controller.replace('def load(name):', "def require(ok, message):\n if not ok:raise RuntimeError(message)\n\ndef load(name):")
controller = controller.replace("assert identities()==json.loads((base/'tool-manifest.json').read_text())", "require(identities()==json.loads((base/'tool-manifest.json').read_text()),'TOOL_IDENTITY_CHANGED')")
controller = controller.replace('assert len(inner)==1', "require(len(inner)==1,'STAGE_RESULT_COUNT')")
controller = controller.replace("assert value['status']=='passed',value", "require(value['status']=='passed','STAGE_FAILED:'+str(value))")
require('assert ' not in controller, 'remaining optimizable assertion')
with (package / 'run-sequence.py').open('x', encoding='utf-8', newline='\n') as stream:
    stream.write(controller)
receipt = dict(schema='root-p3-preparation/v1', testedCommit=head, package=str(package), prefix=prefix,
               sourceControllerSha256=original, controllerSha256=hashlib.sha256((package/'run-sequence.py').read_bytes()).hexdigest(),
               toolFiles=len(manifest), inputFiles=len(inputs['paths']),
               changes='Fresh prefix, current main input binding, explicit require instead of assertions; 12 shared tools unchanged.',
               syntheticNaming='Frozen check-core retains g11-p2-synthetic prefix containing this unique g11-p3 control ID.')
with (package / 'preparation.json').open('x', encoding='utf-8') as stream:
    json.dump(receipt, stream, ensure_ascii=False, indent=2)
print(json.dumps(receipt, ensure_ascii=False, indent=2))
