"""Independent engineering ownership and image audit; controls reviewed separately; no implementation imports."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def safe(value):
    text = str(value).replace('\\', '/')
    require(re.match(r'^[A-Za-z]:/', text), 'absolute local path required')
    for part in text[3:].split('/'):
        require(part and part not in ('.', '..') and part == part.rstrip(' .') and
                ':' not in part and part.lower() not in ('.testagent', '.git', '.g10-verify-blobs.ps1'), 'forbidden path')
    path = Path(text)
    for node in reversed((path, *path.parents)):
        info = node.lstat()
        require(not getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT, 'reparse')
    return path


def load(path):
    return json.loads(safe(path).read_text(encoding='utf-8-sig'))


def identity(path):
    digest = hashlib.sha256()
    length = 0
    with safe(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            length += len(chunk)
            digest.update(chunk)
    return dict(length=length, sha256=digest.hexdigest())


def matches(path, expected):
    actual = identity(path)
    require(actual['length'] == expected['length'] and actual['sha256'] == expected['sha256'].lower(), 'identity: ' + str(path))
    return actual


def files(root):
    with os.scandir(safe(root)) as children:
        names = [child.name for child in children]
    for name in sorted(names):
        require(name.lower() not in ('.testagent', '.git'), 'forbidden entry')
        path = safe(root / name)
        if path.is_dir():
            yield from files(path)
        else:
            require(path.is_file(), 'unknown kind')
            yield path


parser = argparse.ArgumentParser()
parser.add_argument('--repo', required=True)
parser.add_argument('--package', required=True)
parser.add_argument('--prefix', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
repo = safe(args.repo)
evidence = repo / 'docs/superpowers/verification/evidence'
require(args.package.startswith(('g11-p2-', 'g11-p3-')) and '/' not in args.package, 'package')
require(args.prefix.startswith(('g11-p2-', 'g11-p3-')) and '/' not in args.prefix, 'prefix')
package = evidence / args.package
out = Path(args.out)
require(out.parent == Path(__file__).absolute().parent and not out.exists(), 'output outside review or collision')
review = dict(schema='root-completion-review/v1', repo=str(repo), package=args.package, fullProductAcceptance=False)
tool_manifest = load(package / 'tool-manifest.json')
for item in tool_manifest:
    matches(package / 'tools' / item['path'], item)
    matches(repo / 'scripts/verification' / item['path'], item)
progress = load(package / 'progress.json')
require([x['mode'] for x in progress] == ['targeted', 'full', 'buildsrc', 'image', 'linked'], 'missing stage')
stages = {}
outer_logs = 0
for row in progress:
    root = Path(row['inner']).parent
    require(root.is_relative_to(evidence) and root.parent.name.startswith(args.prefix), 'foreign stage')
    result = load(row['inner'])
    require(result['status'] == 'passed' and result['primaryFailure'] is None, 'stage failure')
    outer = load(row['outer'])
    require(outer['actualExitCode'] == 0 and outer['firstFailure'] is None and outer['settlement']['actualJobQueryObservedEmpty'] and
            not outer['settlement']['beforeTermination'] and not outer['terminationRequested'], 'outer not clean')
    require(outer['toolsBefore'] == outer['toolsAfter'] and outer['toolsUnchanged'], 'outer tool changes')
    for stream in outer['streams']:
        require(Path(stream['path']).parent == Path(row['outer']).parent and not stream['partial'] and stream['eof'], 'outer stream')
        matches(stream['path'], stream)
        outer_logs += 1
    scope = load(root / ('scope-bound.json' if row['mode'] == 'linked' else 'scope.json'))
    require(Path(scope['paths']['Repo']) == repo and Path(scope['owned']) == root, 'scope binding')
    for name, expected in scope['executables'].items():
        allowed = {str(Path(scope['paths']['Pwsh'])).casefold(), str(Path(scope['paths']['Python'])).casefold()}
        allowed.update(str(Path(scope['paths']['Jdk']) / ('bin/' + x + '.exe')).casefold() for x in ['java', 'javac', 'jimage'])
        if row['mode'] == 'linked':
            allowed.add(str(Path(scope['imageBinding']['image']) / 'runtime/bin/java.exe').casefold())
        require(str(Path(name)).casefold() in allowed, 'unexpected executable')
        matches(name, expected)
    stages[row['mode']] = (root, result, scope)
image_root, image_result, image_scope = stages['image']
linked_root, linked_result, linked_scope = stages['linked']
runtime = Path(image_scope['runtime'])
require(runtime.parent == Path('C:/Users/hetia/AppData/Local/Temp') and re.fullmatch('datacube-g11-[0-9a-f]{32}', runtime.name), 'runtime ownership')
marker = runtime / 'runtime-owner.json'
require(identity(marker)['sha256'] == image_scope['runtimeMarkerSha256'].lower(), 'marker identity')
require(Path(load(marker)['scope']) == image_root, 'marker scope')
image = runtime / 'build/main/jpackage/DataCube'
manifest = load(image_root / 'image-manifest.json')
require(Path(manifest['image']) == image, 'image path')
require(manifest == load(linked_root / 'image-before.json') == load(linked_root / 'image-after.json'), 'image changed')
expected_files = {item['path']: item for item in manifest['files']}
require(len(expected_files) == len(manifest['files']), 'duplicate image file')
actual_files = {p.relative_to(image).as_posix() for p in files(image)}
require(actual_files == set(expected_files), 'image file inventory')
for name, item in expected_files.items():
    matches(image / name, item)
binding = linked_scope['imageBinding']
require(Path(binding['sourceScope']) == image_root / 'scope.json' and Path(binding['image']) == image, 'image binding')
require(identity(image_root / 'stage-result.json')['sha256'] == binding['sourceResultSha256'].lower(), 'source result')
require(identity(image_root / 'image-manifest.json')['sha256'] == binding['sourceManifestSha256'].lower(), 'source manifest')
require(load(image_root / 'inputs-before.json') == load(image_root / 'inputs-after.json') == load(linked_root / 'inputs-before.json'), 'image input binding')
for item in binding['core']:
    matches(image / item['path'], item)
linked = linked_result['linkedProcesses']
require(set(linked) == {'module-index', 'probe-compile', 'driver-discovery', 'redis-linked'}, 'linked commands')
raw = {}
for name, result in linked.items():
    require(result['rootExitCode'] == 0 and result['rootExited'] and result['ownedSettlement'] == 'complete', 'linked not settled')
    path = linked_root / 'processes' / name / 'stdout.bin'
    matches(path, result['stdout'])
    raw[name] = safe(path).read_text(encoding='utf-8-sig')
require('connectCalls=0' in raw['driver-discovery'] and 'no credentials or user profile loaded' in raw['driver-discovery'], 'driver proof')
require(all(x in raw['redis-linked'] for x in ['G10_LINKED_REDIS=true', 'socketsSettled=true', 'realServices=0']), 'Redis proof')
classes = {line.strip() for line in raw['module-index'].splitlines() if line.strip().endswith('.class')}
types = {x['type'].replace('.', '/') for x in load(linked_root / 'test-types.json')['mappings']}
require(len(types) == 408 and classes, 'type/index count')
leaks = [x for x in classes if x[:-6].split('$')[0] in types or re.search(r'^(org/junit/|org/mockito/|org/testfx/|acceptance/)|G\d+Linked.*Probe|MigrationRuntimeDriverProbe|DesktopProbe', x)]
require(not leaks, 'test/probe classes leaked')
require(all('com/datacube/redis/' + x + '.class' in classes for x in ['RedisDisplayLimits', 'RedisDisplaySupport', 'RedisKeySnapshot', 'RedisTextRetention', 'RespClient', 'RedisSessionManager']), 'required Redis class absent')
require(not any(re.search(r'(?i)(\.datacube|(^|/)(profiles?|home|temp|test-results|acceptance)(/|$)|Probe|fixture|isolation)', x) for x in actual_files), 'image file leak')
cfg = safe(image / 'app/DataCube.cfg').read_text(encoding='utf-8-sig')
require(not re.search(r'(?i)user\.home|headless|java\.io\.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic|datacube-g11-', cfg), 'cfg pollution')
review['image'] = dict(files=len(actual_files), classes=len(classes), testTypes=len(types), core=binding['core'], rawDriver=raw['driver-discovery'], rawRedis=raw['redis-linked'])
review['outerEngineeringLogs'] = outer_logs
review['engineeringAccepted'] = True
review['controlsReviewedSeparately'] = True
with out.open('x', encoding='utf-8') as stream:
    json.dump(review, stream, ensure_ascii=False, indent=2)
print(json.dumps(review, ensure_ascii=False, indent=2))
