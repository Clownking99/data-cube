import hashlib,json,subprocess
from pathlib import Path
here=Path(__file__).absolute().parent;repo=here.parents[4];worker=Path('C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾');ev=worker/'docs/superpowers/verification/evidence';name='redis-binary-p2-a71189e287fa465ca6e32c5182314976-ci-longpaths';base=ev/name
def need(ok,why):
    if not ok:raise RuntimeError(why)
def sha(b):return hashlib.sha256(b).hexdigest()
def load(p):return json.loads(p.read_bytes())
mp=ev/(name+'-frozen')/'manifest.json';need(sha(mp.read_bytes())=='9b999249226d964e8c3bec8f5130d1cb08d911a5d68e3138034052b51dc5621b','manifest')
m=load(mp);need(m['passed'] and m['fileCount']==41,'freeze')
seen=set();total=0
for row in m['files']:
    rel=row['path'];need(rel.startswith(name+'/') and rel.count('/')==1 and '..' not in rel,'file scope')
    p=ev/rel;need(not p.is_symlink() and not p.is_junction(),'link');b=p.read_bytes();need(len(b)==row['length'] and sha(b)==row['sha256'],'original hash');seen.add(p.name);total+=len(b)
need(seen=={p.name for p in base.iterdir()} and total==m['totalBytes'],'complete inventory')
entry=load(base/'entry-manifest.json')
for row in entry:
    b=(base/row['path']).read_bytes();need(len(b)==row['length'] and sha(b)==row['sha256'],'entry')
r=load(base/'result.json');need(r['passed'] and len(r['records'])==9 and load(base/'operator-result.json')['actualExit']==0,'actual helper result')
for row in r['records']:
    need(row['failure'] is None and row['settlement']['actualJobQueryObservedEmpty'] and not row['settlement']['beforeTermination'],'settlement')
    need(row['actualExit']==(128 if row['name']=='checkout-false' else 0),'actual exit')
    for stream in ['stdout','stderr']:
        b=(base/(row['name']+'.'+stream)).read_bytes();need(len(b)==row[stream]['length'] and sha(b)==row[stream]['sha256'],'stream')
a=load(base/'checkout-false-command.json');b=load(base/'checkout-true-command.json');need(a['argv']==b['argv'],'same checkout argv')
env=dict(a['environment']);need(env['GIT_CONFIG_COUNT']=='1' and env['GIT_CONFIG_KEY_0']=='core.longpaths' and env['GIT_CONFIG_VALUE_0']=='false','negative config')
env['GIT_CONFIG_VALUE_0']='true';need(env==b['environment'],'only longpaths value changes')
need(b'Filename too long' in (base/'checkout-false.stderr').read_bytes(),'specific failure')
expected=(base/'expected-file.bin').read_bytes();need(expected==(base/'cat-file.stdout').read_bytes() and sha(expected)==r['actualFileSha256'],'original blob')
runtime=Path(r['sameRepository']);need(runtime==Path('C:/Users/hetia/AppData/Local/Temp/redis-binary-ci-a71189e287fa465ca6e32c5182314976/repo'),'owned runtime')
target=runtime/r['path'];need(len(str(target))==r['absolutePathLength']==387,'long target');need(Path('\\\\?\\'+str(target)).read_bytes()==expected,'actual checkout bytes')
checks=[]
for rel,count in [('.github/workflows/verify.yml',"${{ runner.os == 'Windows' && '1' || '0' }}"),('.github/workflows/release.yml',"'1'")]:
    new=(worker/rel).read_bytes().replace(b'\r\n',b'\n');old=subprocess.check_output(['D:/Git/cmd/git.exe','show','3af30f12ea225c872b1d65b831ab0a136ef05e18:'+rel],cwd=repo).replace(b'\r\n',b'\n')
    block=('        env:\n          GIT_CONFIG_COUNT: '+count+"\n          GIT_CONFIG_KEY_0: core.longpaths\n          GIT_CONFIG_VALUE_0: 'true'\n").encode()
    need(new.count(block)==1 and new.replace(block,b'')==old,'exact workflow-only patch')
    checks.append(dict(path=rel,workerSha256=sha((worker/rel).read_bytes()),canonicalSha256=sha(new)))
need(load(base/'static-audit.json')['windowsCheckouts']==2,'Windows coverage')
receipt=dict(accepted=True,baseCommit='3af30f12ea225c872b1d65b831ab0a136ef05e18',workerManifestSha256=sha(mp.read_bytes()),files=41,bytes=total,actualGitCommands=9,negativeExit=128,positiveExit=0,pathLength=387,originalBytes=68,workflowChecks=checks,engineeringRerun=False,reason='Only checkout environments changed; exact-SHA CI required after merge',releaseWorkflowExecuted=False)
with (here/'review.json').open('x',encoding='utf-8') as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
print(json.dumps(receipt))
