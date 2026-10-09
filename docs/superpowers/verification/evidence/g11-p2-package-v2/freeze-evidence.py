import pathlib,json,hashlib,os,stat
repo=pathlib.Path.cwd();base=repo/'docs/superpowers/verification/evidence';package=base/'g11-p2-package-v2'
def identity(p):
 b=p.read_bytes();return dict(path=p.relative_to(repo).as_posix(),length=len(b),sha256=hashlib.sha256(b).hexdigest())
checks=[]
for row in json.loads((package/'tool-manifest.json').read_text()):
 working=identity(repo/'scripts/verification'/row['path']);assert working['length']==row['length'] and working['sha256']==row['sha256'];checks.append(working)
for entry in json.loads((package/'progress.json').read_text()):
 scope=pathlib.Path(entry['inner']).parent
 before=json.loads((scope/'inputs-before.json').read_text(encoding='utf-8-sig'));after=json.loads((scope/'inputs-after.json').read_text(encoding='utf-8-sig'));assert before==after
 assert len(before['files'])==875
outer=json.loads((package/'sequence-result.json').read_text());assert outer['passed'] and len(outer['stages'])==5
(package/'closure-after.json').write_text(json.dumps(dict(passed=True,files=checks,inputCount=875),indent=2))
roots=[]
for p in base.iterdir():
 if p.name=='g11-p2-root-review' or p.name.startswith('g11-p2-frozen-'):continue
 if p.name.startswith(('g11-p2-','g11-p2-synthetic-')) and p.is_dir():roots.append(p)
files=[]
for root in sorted(roots):
 for directory,children,names in os.walk(root,followlinks=False):
  node=pathlib.Path(directory)
  assert not node.lstat().st_file_attributes & 0x400
  for name in children+names:
   assert name.casefold() not in ('.testagent','.git'),name
   assert not (node/name).lstat().st_file_attributes & 0x400
  files.extend(identity(node/name) for name in sorted(names))
manifest=dict(schema='frozen-evidence/v1',testedCommit='099dd677a653731bf2fad998cacb65ef33f2c827',phase='G11-P2',roots=[p.relative_to(repo).as_posix() for p in sorted(roots)],files=sorted(files,key=lambda r:r['path']),fileCount=len(files),totalBytes=sum(r['length'] for r in files),runtimeTreesExcluded=True,interpretation='Worker evidence only; P1 roots immutable; source controls and first v1 driver failure retained; root review excluded. v2 complete frozen matrix and five sequential engineering stages passed. Commit/checkout transport validation belongs to root P3.')
final=base/'g11-p2-frozen-20261009';final.mkdir(exist_ok=False);path=final/'manifest.json';path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(roots=len(roots),files=len(files),bytes=manifest['totalBytes'],manifest=identity(path)),indent=2))
