"""Prepare new root-owned verification after the reviewed test-only CI correction."""
from pathlib import Path
import json,hashlib,subprocess,uuid
r=Path(__file__).resolve().parents[5]
old=r/'docs/superpowers/verification/evidence/g9-main-20261009'
worker=r/'docs/superpowers/verification/evidence/g9-ci-fix-20261009-worker'
out=r/'docs/superpowers/verification/evidence/g9-ci-main-20261009';out.mkdir(exist_ok=False)
(out/'.gitattributes').write_bytes(b'* -text\n')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(name,v):(out/name).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
provenance=[]
names=['Run-P3.ps1','Audit-Main-Image.ps1','Run-Image-Audit-Main.ps1','Summarize-Xml.py',
       'G9RuntimeJdbcMocks.java','G9RuntimeTableExportProbe.java','MigrationRuntimeDriverProbe.java','XmlRuntimeExportProbe.java',
       'XlsxRuntimeTextProbe.java','Audit-Xlsx-Text.py','Delivery.py']
for name in names:
    raw=(old/name).read_bytes();original=raw
    if name=='Run-P3.ps1':
        s=raw.decode('utf-8-sig')
        s=s.replace("'targeted','full','buildsrc','image'","'affected','headless','full','buildsrc','image'")
        s=s.replace("'targeted'{@('cleanTest','test','--rerun-tasks')}","'affected'{@('cleanTest','test','--rerun-tasks')};'headless'{@('cleanTest','test','--rerun-tasks')}")
        a=s.index("if($Mode -eq 'targeted'){");b=s.index('$psi=[Diagnostics.ProcessStartInfo]',a)
        s=s[:a]+"if($Mode -eq 'affected'){foreach($filter in @('com.datacube.export.PgDumpRunner*Test','com.datacube.fx.AppShellTableExport*Test','com.datacube.fx.AppShellSqlCancelIdentityTest')){$argv+=@('--tests',$filter)}}\nif($Mode -eq 'headless'){$argv+=@('--tests','com.datacube.fx.AppShellTableExportJdbcShutdownTest')}\n"+s[b:]
        lines=s.splitlines()
        for i,line in enumerate(lines):
            if line.startswith("$psi.Environment['JAVA_TOOL_OPTIONS']="):
                lines[i]="$psi.Environment['JAVA_TOOL_OPTIONS']='\"-Duser.home='+$owned+'/profile\" \"-Djava.io.tmpdir='+$short+'\" -Djava.awt.headless='+($Mode -eq 'headless').ToString().ToLowerInvariant()"
        s='\n'.join(lines)+'\n';s=s.replace('datacube-g9-p3-','datacube-g9-ci-main-')
        raw=s.encode()
    if name in ['Audit-Main-Image.ps1','Run-Image-Audit-Main.ps1']:
        raw=raw.replace(b'005-image-audit',b'006-image-audit')
    (out/name).write_bytes(raw)
    provenance.append(dict(source=(old/name).relative_to(r).as_posix(),sourceSha256=sha(old/name),target=name,targetSha256=sha(out/name),modified=raw!=original))
freeze=load(worker/'input-freeze.json');inputs=[]
for item in freeze['files']:
    name=item['path']
    assert '.testagent' not in Path(name).parts and '..' not in Path(name).parts
    if name.startswith('docs/'):continue
    f=r/name;inputs.append(dict(path=name,length=f.stat().st_size,sha256=sha(f),workerSha256=item['sha256']))
launcher=out/'Run-P3.ps1';inputs.append(dict(path=launcher.relative_to(r).as_posix(),length=launcher.stat().st_size,sha256=sha(launcher)))
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=r,text=True).strip()
scope=['src','test','build.gradle','buildSrc','gradle','settings.gradle','README.md','datacube-brand-assets',':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**']
assert not subprocess.check_output(['git','diff','--name-only','6b8ceb93',head,'--',*scope],cwd=r)
assert not subprocess.check_output(['git','diff','--name-only','HEAD','--',*scope],cwd=r)
save('input-freeze.json',dict(head=head,codeCommit='6b8ceb93c7413b3882137d322d88b9a1cb14ef5c',files=inputs))
save('script-provenance.json',provenance)
save('delivery-intent.json',dict(receipts='build/owned-g9-ci-final-'+uuid.uuid4().hex,verificationHead=head,scope='Final main-only push and exact SHA Verify; no fetch/tag/release',priorFailedRun=37822449579))
print(json.dumps(dict(head=head,inputFiles=len(inputs),out=str(out))))
