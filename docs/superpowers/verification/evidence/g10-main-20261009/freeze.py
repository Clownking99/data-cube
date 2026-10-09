"""Seal this main verification only; historical evidence is never rewritten."""
import datetime,hashlib,json,subprocess
from pathlib import Path
O=Path(__file__).resolve().parent;R=O.parents[4]
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def digest(p):return dict(length=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest())
manifest=O/'raw-manifest.json';assert not manifest.exists()
receipt=read(O/'receipt.json');settlement=read(O/'process-settlement.json')
assert receipt['head']==read(O/'merge-receipt.json')['head'] and settlement['ownedJavaCount']==0
for output in receipt['compiledOutputs']:
    for item in read(O/(output['run']+'-class-manifest.json')):
        assert digest(Path(output['path'])/item['path'])==dict(length=item['length'],sha256=item['sha256'])
files=subprocess.check_output(['rg','--files','--hidden','--no-ignore','-g','!**/.testagent/**',str(O)],cwd=R,text=True).splitlines()
entries=[]
for name in sorted(files):
    p=Path(name);assert p.is_relative_to(O) and '.testagent' not in p.parts and not p.is_symlink()
    entries.append(dict(path=p.relative_to(R).as_posix(),**digest(p)))
manifest.write_text(json.dumps(dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),note='Fresh main runs, current XML and original logs; complete class/image hashes bind owned UUID artifacts; no duplicate product/source trees; later CI receipt remains at delivery-intent path without changing the tested commit.',files=entries),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
for item in entries:assert digest(R/item['path'])==dict(length=item['length'],sha256=item['sha256'])
print(json.dumps(dict(files=len(entries),bytes=sum(x['length'] for x in entries),manifest=digest(manifest))))
