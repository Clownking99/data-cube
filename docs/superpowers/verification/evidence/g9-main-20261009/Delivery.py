"""Authorized main-only push and exact SHA Verify receipts. No fetch/tag/release."""
import argparse,hashlib,json,os,subprocess
from pathlib import Path
from datetime import datetime,timezone

p=argparse.ArgumentParser();p.add_argument('mode',choices=['push','snapshot','logs','final']);a=p.parse_args()
e=Path(__file__).resolve().parent;repo=e.parents[4]
intent=json.loads((e/'delivery-intent.json').read_text())
out=repo/intent['receipts'];assert out.resolve().is_relative_to((repo/'build').resolve())
out.mkdir(exist_ok=True,parents=True)
gh='C:/Program Files/GitHub CLI/gh.exe'
target='repos/Clownking99/data-cube'
def save(name,obj):
    (out/name).write_bytes((json.dumps(obj,ensure_ascii=False,indent=2)+'\n').encode())
def git(*args):return subprocess.check_output(['git',*args],cwd=repo,text=True).strip()
head=git('rev-parse','HEAD')
assert git('branch','--show-current')=='main'
def call(label,args,proxy=False):
    env=os.environ.copy()
    if proxy:
        env['HTTPS_PROXY']=env['HTTP_PROXY']='http://127.0.0.1:7897'
    r=subprocess.run(args,cwd=repo,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    (out/(label+'-stdout.raw')).write_bytes(r.stdout)
    (out/(label+'-stderr.raw')).write_bytes(r.stderr)
    save(label+'-command.json',dict(argv=args,proxyThisCommandOnly=proxy,exitCode=r.returncode,utc=datetime.now(timezone.utc).isoformat()))
    return r
def api(label,endpoint):
    r=call(label,[gh,'api',endpoint])
    if r.returncode:r=call(label+'-proxy',[gh,'api',endpoint],True)
    assert r.returncode==0,(label,r.returncode)
    return json.loads(r.stdout)
if a.mode=='push':
    assert not (out/'push.json').exists()
    r=call('push-direct',['git','push','origin','main'])
    proxy=False
    if r.returncode:
        proxy=True;r=call('push-proxy',['git','-c','http.proxy=http://127.0.0.1:7897','push','origin','main'])
    assert r.returncode==0,'Push failed; raw receipts retained'
    remote=api('remote-main',target+'/git/ref/heads/main')
    assert remote['object']['sha']==head
    save('push.json',dict(head=head,remoteHead=remote['object']['sha'],proxyFallback=proxy,passed=True))
    print(json.dumps(dict(head=head,pushed=True,proxyFallback=proxy)))
else:
    push=json.loads((out/'push.json').read_text());assert push['head']==head
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    listing=api(stamp+'-runs',target+'/actions/runs?head_sha='+head+'&event=push&per_page=30')
    candidates=[r for r in listing['workflow_runs'] if r['head_sha']==head and r['name']=='Verify' and r['event']=='push' and r['head_branch']=='main']
    if not candidates:
        print(json.dumps(dict(head=head,verifyRunFound=False)));raise SystemExit(0)
    run=max(candidates,key=lambda r:r['id'])
    jobs=api(stamp+'-jobs',target+'/actions/runs/'+str(run['id'])+'/jobs?filter=latest&per_page=100')
    summary=dict(head=head,runId=run['id'],url=run['html_url'],status=run['status'],conclusion=run['conclusion'],attempt=run['run_attempt'],
                 jobs=[dict(id=j['id'],name=j['name'],status=j['status'],conclusion=j['conclusion']) for j in jobs['jobs']])
    save('latest.json',summary)
    if a.mode in ['logs','final']:
        assert run['status']=='completed'
        r=call('run-'+str(run['id'])+'-attempt-'+str(run['run_attempt'])+'-logs',[gh,'run','view',str(run['id']),'--repo','Clownking99/data-cube','--log'])
        if r.returncode:r=call('run-'+str(run['id'])+'-logs-proxy',[gh,'run','view',str(run['id']),'--repo','Clownking99/data-cube','--log'],True)
        assert r.returncode==0
        text=r.stdout.decode('utf-8',errors='replace')
        if a.mode=='final':
            assert run['conclusion']=='success'
            by_name={j['name']:j for j in jobs['jobs']}
            assert set(by_name)=={'wrapper-validation','Test (ubuntu-latest)','Test (windows-latest)','redis-integration'}
            assert all(j['conclusion']=='success' for j in by_name.values())
            windows={s['name']:s for s in by_name['Test (windows-latest)']['steps']}
            assert windows['Unit tests']['conclusion']=='success' and windows['Windows linked image']['conclusion']=='success'
            assert '> Task :buildSrc:test' in text and '> Task :test' in text and '> Task :jlink' in text
            remote=api(stamp+'-remote-main',target+'/git/ref/heads/main');assert remote['object']['sha']==head
            save('delivery-result.json',dict(**summary,passed=True,remoteHead=head,nativeOrRealDatabaseAcceptance=False))
            entries=[dict(path=f.name,length=f.stat().st_size,sha256=hashlib.sha256(f.read_bytes()).hexdigest().upper()) for f in out.iterdir() if f.is_file() and f.name!='manifest.json']
            save('manifest.json',dict(files=entries,selfExcluded='manifest.json'))
    print(json.dumps(summary,ensure_ascii=False))
