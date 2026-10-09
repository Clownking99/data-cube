"""Stage only this accepted round's allowlist; verify raw Git and input identities."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

base = Path(__file__).absolute().parent
repo = base.parents[4]
git_exe = 'D:/Git/cmd/git.exe'
excludes = ['.', ':(exclude).testagent', ':(exclude).testagent/**', ':(exclude)**/.testagent/**']
parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['stage', 'verify'])
args = parser.parse_args()

def git(*argv, data=None):
    return subprocess.run([git_exe, *argv], cwd=repo, input=data, capture_output=True, check=True).stdout

def safe(relative):
    require(relative and not any(x in relative for x in ('\\', ':', '\n', '\r', '\t')) and
            all(x and x not in ('.', '..') and x.casefold() not in ('.testagent', '.git') for x in relative.split('/')), 'unsafe path')
    return relative

def require_identity(path, row):
    raw = path.read_bytes()
    require(len(raw) == row['length'] and hashlib.sha256(raw).hexdigest() == row['sha256'].lower(), 'identity: ' + str(path))

prefix = json.loads((base / 'preparation.json').read_text())['prefix']
require(prefix == 'g11-p3-581b459dad08', 'round')
manifest_path = 'docs/superpowers/verification/evidence/' + prefix + '-frozen/manifest.json'
manifest = json.loads((repo / manifest_path).read_text())
require(manifest['testedCommit'] == 'e368a1b16bdee226925b363f06b4dc4f003c1f0f', 'tested commit')
paths = {safe(row['path']) for row in manifest['files']}
require(len(paths) == manifest['fileCount'], 'duplicate manifest path')
for row in manifest['files']:
    path = safe(row['path'])
    root = path.removeprefix('docs/superpowers/verification/evidence/').split('/')[0]
    require(path.startswith('docs/superpowers/verification/evidence/') and root.startswith((prefix + '-', 'g11-p2-synthetic-' + prefix + '-')), 'foreign evidence')
    require_identity(repo / path, row)
paths.add(manifest_path)
paths.update([
    'docs/handoffs/CURRENT.md',
    'docs/maintenance/verification-guide.md',
    'docs/maintenance/verification-runner-design.md',
    'docs/superpowers/plans/2026-10-09-g11-verification-core.md',
    'docs/superpowers/verification/2026-10-09-g11-verification-coordination.md',
    'docs/superpowers/verification/2026-10-09-g11-main-verification.md',
    'docs/superpowers/verification/2026-10-09-g11-ci-correction-main.md',
])
for name in ('audit_transport.py', 'transport-before.json', 'transport-after.json'):
    paths.add('docs/superpowers/verification/evidence/g11-ci-pid-readiness-20261009/root-review/' + name)
paths = sorted(safe(p) for p in paths)
require(git('branch', '--show-current').strip() == b'main', 'not main')
progress = json.loads((base / 'progress.json').read_text())
scope = Path(next(r['inner'] for r in progress if r['mode'] == 'full')).parent
require(scope.is_relative_to(repo / 'docs/superpowers/verification/evidence') and scope.parent.name.startswith(prefix + '-full-'), 'input scope')
inputs = json.loads((scope / 'inputs-after.json').read_text())
require(len(inputs['files']) == 875, 'input count')
for row in inputs['files']:
    require_identity(repo / safe(row['path'].replace('\\', '/')), row)

if args.mode == 'stage':
    require(git('rev-parse', 'HEAD').decode().strip() == manifest['testedCommit'], 'HEAD changed before staging')
    require(not git('diff', '--cached', '--name-only', '-z', '--', *excludes), 'preexisting index changes')
    status = git('status', '--porcelain', '-z', '--untracked-files=all', '--', *excludes).decode().rstrip('\0')
    changed = {row[3:] for row in status.split('\0') if row}
    require(changed <= set(paths), 'unapproved working changes: ' + repr(sorted(changed - set(paths))))
    hashes = git('hash-object', '-w', '--no-filters', '--stdin-paths', data=('\n'.join(paths) + '\n').encode()).decode().splitlines()
    require(len(hashes) == len(paths), 'hash count')
    index = ''.join('100644 ' + oid + '\t' + path + '\n' for path, oid in zip(paths, hashes))
    git('update-index', '--index-info', data=index.encode())
    actual = set(git('diff', '--cached', '--name-only', '-z', '--', *excludes).decode().rstrip('\0').split('\0'))
    require(actual == set(paths), 'staged allowlist differs')
    refs = [':' + p for p in paths]
else:
    require(not git('status', '--porcelain', '--', *excludes), 'dirty scoped checkout')
    refs = ['HEAD:' + p for p in paths]
raw = git('cat-file', '--batch', data=('\n'.join(refs) + '\n').encode())
offset = 0
for path in paths:
    end = raw.index(b'\n', offset)
    header = raw[offset:end].decode().split()
    require(len(header) == 3 and header[1] == 'blob', 'missing blob')
    length = int(header[2]); offset = end + 1
    require(raw[offset:offset+length] == (repo/path).read_bytes(), 'Git raw bytes: ' + path)
    offset += length
    require(raw[offset:offset+1] == b'\n', 'batch separator'); offset += 1
require(offset == len(raw), 'batch tail')
receipt = dict(schema='root-p3-local-readiness/v1', mode=args.mode, head=git('rev-parse','HEAD').decode().strip(),
               testedCommit=manifest['testedCommit'], evidenceFiles=manifest['fileCount'], stagedFiles=len(paths),
               unchangedInputs=875, rawBlobMatches=True, passed=True)
if args.mode == 'verify':
    intent = json.loads((base/'delivery-intent.json').read_text())
    relative = safe(intent['receipts'])
    require(relative.startswith('build/owned-g11-ci-') and len(relative.split('/')) == 2, 'receipt root')
    out = repo / relative
    out.mkdir(parents=True, exist_ok=True)
    with (out/'local-readiness.json').open('x', encoding='utf-8') as stream:
        json.dump(receipt, stream, indent=2)
print(json.dumps(receipt))
