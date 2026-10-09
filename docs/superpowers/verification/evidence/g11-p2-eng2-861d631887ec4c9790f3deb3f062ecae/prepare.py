import ast, hashlib, json
from pathlib import Path
base=Path(__file__).absolute().parent
ast.parse((base/'controller.py').read_bytes())
rows=[]
for name in ('controller.py','run-engineering.ps1','prepare.py','inputs.json','config.json','controls-binding.json','resume-targeted.json','tool-manifest.json'):
    p=base/name;raw=p.read_bytes();rows.append(dict(path=name,length=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
for row in json.loads((base/'tool-manifest.json').read_bytes()):
    p=base/'tools'/row['path'];raw=p.read_bytes()
    if len(raw)!=row['length'] or hashlib.sha256(raw).hexdigest()!=row['sha256']:raise RuntimeError('frozen tool changed')
    rows.append(dict(path='tools/'+row['path'],length=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
(base/'entry-manifest.json').write_bytes((json.dumps(rows,indent=2)+'\n').encode())
print(hashlib.sha256((base/'entry-manifest.json').read_bytes()).hexdigest())
