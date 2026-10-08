"""Prepare root-owned P3 validation from the independently accepted P2 scripts."""
from pathlib import Path
import hashlib, json, subprocess

repo = Path(__file__).resolve().parents[5]
old = repo/'docs/superpowers/verification/evidence/g9-p2-20261008-worker'
out = repo/'docs/superpowers/verification/evidence/g9-main-20261009'
out.mkdir(exist_ok=False)
(out/'.gitattributes').write_bytes(b'* -text\n')

def load(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def save(name, obj): (out/name).write_bytes((json.dumps(obj,ensure_ascii=False,indent=2)+'\n').encode())

sources = ['Run-P2.ps1','Audit-P2-Image-006.ps1','Run-Image-Audit-P2-006.ps1',
           'Summarize-Xml.py','G9RuntimeJdbcMocks.java','G9RuntimeTableExportProbe.java',
           'MigrationRuntimeDriverProbe.java','XmlRuntimeExportProbe.java','XlsxRuntimeTextProbe.java','Audit-Xlsx-Text.py']
provenance = []
for name in sources:
    destination = {'Run-P2.ps1':'Run-P3.ps1','Audit-P2-Image-006.ps1':'Audit-Main-Image.ps1',
                   'Run-Image-Audit-P2-006.ps1':'Run-Image-Audit-Main.ps1'}.get(name,name)
    raw = (old/name).read_bytes()
    if name == 'Run-P2.ps1':
        raw = raw.replace(b'datacube-g9-p2-',b'datacube-g9-p3-').replace(b'G9P2ShortName',b'G9P3ShortName')
        raw = raw.replace(b'if($case.skipped)', b"if($null -ne $case.SelectSingleNode('skipped'))")
    if name in ['Audit-P2-Image-006.ps1','Run-Image-Audit-P2-006.ps1']:
        raw = raw.replace(b'006-image-audit',b'005-image-audit').replace(b'Audit-P2-Image-006.ps1',b'Audit-Main-Image.ps1')
    (out/destination).write_bytes(raw)
    provenance.append(dict(source=(old/name).relative_to(repo).as_posix(),sourceSha256=digest(old/name),
                           target=destination,targetSha256=digest(out/destination),modified=raw!=(old/name).read_bytes()))
freeze = load(old/'input-freeze.json')
inputs, binding = [], []
for item in freeze['files']:
    name = item['path']
    assert '.testagent' not in Path(name).parts and '..' not in Path(name).parts
    if name.endswith('/Run-P2.ps1'): continue
    path = repo/name
    frozen = old/'input-snapshot'/name
    current, previous = path.read_bytes(), frozen.read_bytes()
    assert current == previous or current.replace(b'\r\n',b'\n') == previous.replace(b'\r\n',b'\n'), name
    inputs.append(dict(path=name,length=len(current),sha256=digest(path)))
    binding.append(dict(path=name,workerSha256=item['sha256'],mainSha256=digest(path),rawEqual=current==previous))
launcher=out/'Run-P3.ps1'
inputs.append(dict(path=launcher.relative_to(repo).as_posix(),length=launcher.stat().st_size,sha256=digest(launcher)))
head = subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
assert not subprocess.check_output(['git','diff','--name-only','e7950123',head,'--','src','test','README.md',
    ':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**'],cwd=repo)
save('input-freeze.json',dict(head=head,files=inputs))
save('worker-main-input-binding.json',dict(head=head,files=binding,productGitTreeMatches=True))
save('script-provenance.json',provenance)
print(json.dumps(dict(head=head,inputFiles=len(inputs),rawDifferences=sum(not i['rawEqual'] for i in binding),out=str(out))))
