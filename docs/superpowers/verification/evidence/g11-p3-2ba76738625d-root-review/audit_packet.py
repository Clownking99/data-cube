import hashlib,json,subprocess
from pathlib import Path
base=Path(__file__).absolute().parent.parent/'g11-p3-2ba76738625d-package'
repo=base.parents[4]
def need(v,m):
    if not v:raise RuntimeError(m)
def load(p):return json.loads(p.read_bytes())
manifest=base/'entry-manifest.json'
need(hashlib.sha256(manifest.read_bytes()).hexdigest()=='2aaa601bf03f0114ecfa2c8569cfd6102aee209cc19fededafb29f3a6ebc39fc','entry identity')
rows=load(manifest);need(len(rows)==17 and len({r['path'] for r in rows})==17,'17 unique entries')
for row in rows:
    raw=(base/row['path']).read_bytes()
    need(len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256'],'entry '+row['path'])
for row in load(base/'tool-manifest.json'):
    raw=(repo/'scripts/verification'/row['path']).read_bytes()
    need(raw==(base/'tools'/row['path']).read_bytes(),'current source tool '+row['path'])
pre=load(base/'preparation.json')
head=subprocess.run(['D:/Git/cmd/git.exe','rev-parse','HEAD'],cwd=repo,capture_output=True,check=True).stdout.decode().strip()
need(head==pre['testedCommit']=='2c8fc13577337a9b5247351e0634e76ff3841999','head')
need(pre['inputFiles']==875 and pre['toolFiles']==12 and pre['controls']==98,'scope counts')
op=load(base/'operator-command.json')
need(op['argv']==['-I','-S','-B',str(base/'run-sequence.py')] and op['soleGradleOwner']=='root','operator argv')
need(Path(op['cwd'])==repo and Path(op['executable'])==Path('C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'),'operator paths')
need(load(base/'shell-exit.json')['actualPythonExitCode']==0,'actual exit')
result=load(base/'sequence-result.json')
need(result['passed'] and result['controls']==98 and result['rootExitControls']==18,'result')
need([r['mode'] for r in result['stages']]==['targeted','full','buildsrc','image','linked'],'stages')
need((base/'sequence.log').read_text(encoding='utf-8-sig').splitlines()[-1]=='ALL PASS','actual sequence log')
receipt=dict(schema='root-entry-review/v1',accepted=True,testedCommit=head,entryFiles=17,entryManifestSha256=hashlib.sha256(manifest.read_bytes()).hexdigest(),controllerSha256=pre['controllerSha256'],sourceToolsMatched=12,actualControllerExit=0,controls=98,stages=5)
with (Path(__file__).parent/'entry-review.json').open('x',encoding='utf-8') as f:json.dump(receipt,f,indent=2)
print(json.dumps(receipt))