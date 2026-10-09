"""Authorized main-only delivery. Raw CI receipts stay in an owned build directory."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['push', 'snapshot', 'final'])
args = parser.parse_args()
base = Path(__file__).absolute().parent
repo = base.parents[4]
intent = json.loads((base/'delivery-intent.json').read_text())
relative = intent['receipts']
require(re.fullmatch('build/owned-g11-ci-[0-9a-f]{32}', relative), 'receipt path')
out = repo/relative
for node in reversed((out, *out.parents)):
    if node.exists():
        require(not getattr(node.lstat(), 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT, 'reparse')
out.mkdir(exist_ok=True, parents=True)
git = 'D:/Git/cmd/git.exe'
gh = 'C:/Program Files/GitHub CLI/gh.exe'
target = 'repos/Clownking99/data-cube'


def save(name, value):
    with (out/name).open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


def local(*argv):
    return subprocess.run([git, *argv], cwd=repo, capture_output=True, text=True, check=True).stdout.strip()


head = local('rev-parse', 'HEAD')
require(local('branch', '--show-current') == 'main', 'not main')
require(not local('status', '--porcelain', '--', '.', ':(exclude).testagent', ':(exclude).testagent/**', ':(exclude)**/.testagent/**'), 'dirty scoped checkout')


def call(label, argv, proxy=False):
    env = os.environ.copy()
    if proxy:
        env['HTTPS_PROXY'] = env['HTTP_PROXY'] = 'http://127.0.0.1:7897'
    result = subprocess.run(argv, cwd=repo, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    with (out/(label+'-stdout.raw')).open('xb') as stream:
        stream.write(result.stdout)
    with (out/(label+'-stderr.raw')).open('xb') as stream:
        stream.write(result.stderr)
    save(label+'-command.json', dict(argv=argv, proxyThisCommandOnly=proxy, exitCode=result.returncode,
                                  utc=datetime.now(timezone.utc).isoformat()))
    return result


def api(label, endpoint):
    result = call(label, [gh, 'api', endpoint])
    if result.returncode:
        result = call(label+'-proxy', [gh, 'api', endpoint], True)
    require(result.returncode == 0, 'API failed: '+label)
    return json.loads(result.stdout)


if args.mode == 'push':
    require(not (out/'push.json').exists(), 'push already recorded')
    result = call('push-direct', [git, 'push', 'origin', 'main'])
    proxy = False
    if result.returncode:
        proxy = True
        result = call('push-proxy', [git, '-c', 'http.proxy=http://127.0.0.1:7897', 'push', 'origin', 'main'])
    require(result.returncode == 0, 'Push failed; raw receipts retained')
    remote = api('remote-main', target+'/git/ref/heads/main')
    require(remote['object']['sha'] == head, 'remote SHA mismatch')
    save('push.json', dict(head=head, remoteHead=head, proxyFallback=proxy, passed=True))
    print(json.dumps(dict(head=head, pushed=True, proxyFallback=proxy)))
else:
    push = json.loads((out/'push.json').read_text())
    require(push['head'] == head, 'HEAD changed after push')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    listing = api(stamp+'-runs', target+'/actions/runs?head_sha='+head+'&event=push&per_page=30')
    runs = [r for r in listing['workflow_runs'] if r['head_sha'] == head and r['name'] == 'Verify' and r['event'] == 'push' and r['head_branch'] == 'main']
    if not runs:
        print(json.dumps(dict(head=head, verifyRunFound=False)))
        raise SystemExit(0)
    run = max(runs, key=lambda r: r['id'])
    jobs = api(stamp+'-jobs', target+'/actions/runs/'+str(run['id'])+'/jobs?filter=latest&per_page=100')
    summary = dict(head=head, runId=run['id'], url=run['html_url'], status=run['status'], conclusion=run['conclusion'],
                   attempt=run['run_attempt'], jobs=[dict(id=j['id'], name=j['name'], status=j['status'], conclusion=j['conclusion']) for j in jobs['jobs']])
    save(stamp+'-summary.json', summary)
    if args.mode == 'final':
        require(run['status'] == 'completed' and run['conclusion'] == 'success', 'CI not passed')
        by_name = {j['name']: j for j in jobs['jobs']}
        require(set(by_name) == {'wrapper-validation', 'Test (ubuntu-latest)', 'Test (windows-latest)', 'redis-integration'}, 'CI job set')
        require(all(j['conclusion'] == 'success' for j in by_name.values()), 'CI job failure')
        windows = {s['name']: s for s in by_name['Test (windows-latest)']['steps']}
        require(windows['Unit tests']['conclusion'] == 'success' and windows['Windows linked image']['conclusion'] == 'success', 'Windows test/image steps')
        result = call(stamp+'-logs', [gh, 'run', 'view', str(run['id']), '--repo', 'Clownking99/data-cube', '--log'])
        if result.returncode:
            result = call(stamp+'-logs-proxy', [gh, 'run', 'view', str(run['id']), '--repo', 'Clownking99/data-cube', '--log'], True)
        require(result.returncode == 0, 'CI logs unavailable')
        text = result.stdout.decode('utf-8', errors='replace')
        require(all(token in text for token in ['> Task :buildSrc:test', '> Task :test', '> Task :jlink']), 'required raw task evidence missing')
        remote = api(stamp+'-remote-main', target+'/git/ref/heads/main')
        require(remote['object']['sha'] == head, 'final remote SHA mismatch')
        save('delivery-result.json', dict(**summary, passed=True, remoteHead=head, fullProductAcceptance=False))
        entries = [dict(path=f.name, length=f.stat().st_size, sha256=hashlib.sha256(f.read_bytes()).hexdigest()) for f in out.iterdir() if f.is_file() and f.name != 'manifest.json']
        save('manifest.json', dict(files=entries, selfExcluded='manifest.json'))
    print(json.dumps(summary, ensure_ascii=False))
