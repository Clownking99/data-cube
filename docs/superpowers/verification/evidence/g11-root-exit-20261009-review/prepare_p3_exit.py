"""Prepare, but do not run, fresh main P3 with all 98 controls and five stages."""
import ast
import hashlib
import json
from pathlib import Path
import subprocess
import uuid

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

here = Path(__file__).absolute().parent
repo = here.parents[4]
require(repo == Path('D:/Projects/朝花夕拾'), 'main checkout required')
evidence = here.parent
source = evidence / 'g11-p3-581b459dad08-package'
git = 'D:/Git/cmd/git.exe'
def local(*args):
    return subprocess.run([git,*args],cwd=repo,check=True,capture_output=True,text=True).stdout.strip()

require(local('branch','--show-current') == 'main', 'main branch required')
head = local('rev-parse','HEAD')
require(head not in ('e368a1b16bdee226925b363f06b4dc4f003c1f0f','854507bca10e94c68bd70424ec16290a878e31a8','0c1416ab9c33f0d9c1f90f039e0bc0ad8ae129ba'), 'runner correction not integrated')
manifest = json.loads((here/'path-fix-admission.json').read_bytes())['toolFiles']
require(len(manifest) == 12, 'tool closure')
payloads = {}
for item in manifest:
    name = item['path']
    require(name and all(p not in ('..','.','.testagent','.git') for p in name.split('/')) and ':' not in name and '\\' not in name, 'tool path')
    raw = (repo/'scripts/verification'/name).read_bytes()
    require(len(raw)==item['length'] and hashlib.sha256(raw).hexdigest()==item['sha256'], 'current tools do not match reviewed fix')
    payloads[name] = raw
raw_controller = (source/'run-sequence.py').read_bytes()
require(hashlib.sha256(raw_controller).hexdigest()=='844df3e07a93ecdb727fcf53608ef05e5b2700f2fe357a6c75597f988f39c48f', 'historical controller identity')
prefix = 'g11-p3-'+uuid.uuid4().hex[:12]
package = evidence/(prefix+'-package')
controller = raw_controller.decode('utf-8').replace('g11-p3-581b459dad08',prefix)
anchor = " call(p2,['--out',str(evidence/'"+prefix+"-policy-controls')"
require(controller.count(anchor)==1, 'root-exit insertion point')
insert = " exits=evidence/'"+prefix+"-root-exit-controls';exit_result=core.root_exit_checks(repo,exits,pwsh,python,jdk,cache);require(exit_result['passed'] and len(exit_result['cases'])==18,'ROOT_EXIT_CONTROL_COUNT');save(exits/'results.json',exit_result);print('ROOT EXIT CONTROLS PASS 18',flush=True)\n"
controller = controller.replace(anchor,insert+anchor)
controller = controller.replace("dict(passed=True,stages=results)","dict(passed=True,controls=98,rootExitControls=18,stages=results)")
# The completed controller results plus independent review still validate the
# original 21/21/31/7 matrix. Root-exit controls are fresh, not a prior receipt.
require('assert ' not in controller and controller.count('core.root_exit_checks(')==1, 'controller contract')
ast.parse(controller)
inputs = json.loads((source/'inputs.json').read_bytes())
require(len(inputs['paths'])==875, 'input allowlist count')
inputs['repo']=str(repo);inputs['testedCommit']=head
package.mkdir()
for name,raw in payloads.items():
    target=package/'tools'/name;target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('xb') as f:f.write(raw)
def save(name,value):
    with (package/name).open('x',encoding='utf-8',newline='\n') as f:json.dump(value,f,ensure_ascii=False,indent=2)
save('tool-manifest.json',manifest);save('inputs.json',inputs)
with (package/'run-sequence.py').open('x',encoding='utf-8',newline='\n') as f:f.write(controller)
receipt=dict(schema='root-p3-preparation/v2',testedCommit=head,package=str(package),prefix=prefix,toolFiles=12,inputFiles=875,controls=98,rootExitControls=18,sourceControllerSha256=hashlib.sha256(raw_controller).hexdigest(),controllerSha256=hashlib.sha256((package/'run-sequence.py').read_bytes()).hexdigest(),changes='Reviewed corrected tools; fresh main/prefix; add all 18 root-exit controls; old 21/21/31/7 and five engineering stages preserved.',runtimeCopied=False)
save('preparation.json',receipt)
print(json.dumps(receipt,ensure_ascii=False,indent=2))
