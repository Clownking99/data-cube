"""Independent raw-receipt review; never imports the implementation under review."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import xml.etree.ElementTree as ET


def require(ok, detail):
    if not ok:
        raise RuntimeError(detail)


def lexical(value):
    text = str(value).replace('\\', '/')
    require(re.match(r'^[A-Za-z]:/', text) is not None, 'absolute local path required')
    for part in text[3:].split('/'):
        require(part and part not in ('.', '..') and part == part.rstrip(' .') and
                ':' not in part and part.lower() not in ('.testagent', '.git', '.g10-verify-blobs.ps1'), 'forbidden path')
    return Path(text)


def safe(path):
    path = lexical(path)
    for node in reversed((path, *path.parents)):
        info = node.lstat()
        require(not getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT, 'reparse')
    return path


def load(path):
    return json.loads(safe(path).read_text(encoding='utf-8-sig'))


def identity(path):
    raw = safe(path).read_bytes()
    return {'length': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}


def matches(path, item):
    actual = identity(path)
    require(actual['length'] == item['length'] and actual['sha256'] == item['sha256'].lower(), 'identity changed: ' + str(path))
    return actual


parser = argparse.ArgumentParser()
parser.add_argument('--repo', required=True)
parser.add_argument('--scope', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
repo, root, out = map(lexical, (args.repo, args.scope, args.out))
require(root.is_relative_to(repo / 'docs/superpowers/verification/evidence') and
        root.parent.name.startswith(('redis-binary-p2-', 'redis-binary-p3-')) and re.fullmatch('[0-9a-f]{32}', root.name), 'scope outside Redis binary fix')
output_parent = Path(__file__).absolute().parent
require(out.parent == output_parent and out.name.endswith('.json'), 'output outside reviewer directory')
safe(output_parent)
require(not out.exists(), 'output collision')
scope = load(root / 'scope.json')
result = load(root / 'stage-result.json')
require(result['status'] == 'passed', 'stage did not pass')
before, after = load(root / 'inputs-before.json'), load(root / 'inputs-after.json')
require(before == after, 'inputs before/after differ')
allowed_dirs = ('src/', 'test/', 'buildSrc/', 'resources/', 'datacube-brand-assets/assets/',
                'drivers/', 'gradle/', '.github/workflows/', 'scripts/verification/')
allowed_files = {'build.gradle', 'settings.gradle', 'README.md', 'gradlew', 'gradlew.bat', 'gradle.properties'}
seen = set()
for item in before['files']:
    relative = item['path'].replace('\\', '/')
    require(relative in allowed_files or relative.startswith(allowed_dirs), 'unadmitted source')
    path = lexical(repo / relative)
    require(path.is_relative_to(repo) and relative.casefold() not in seen, 'duplicate source')
    seen.add(relative.casefold())
    matches(path, item)
for relative in before['optionalAbsent']:
    require(relative == 'gradle.properties' and not (repo / relative).exists(), 'optional input appeared')
types = load(root / 'test-types.json')
test_sources = {p for p in seen if p.startswith('test/') and p.endswith('.java')}
mapped_sources = {m['source'].replace('\\', '/').casefold() for m in types['mappings']}
require(test_sources and mapped_sources == test_sources and len(types['mappings']) == len(test_sources), 'type coverage')
tool_count = 0
for name, tool in scope['tools'].items():
    source, frozen = lexical(repo / 'scripts/verification' / name), lexical(root / 'tools' / name)
    require(lexical(tool['source']) == source and lexical(tool['frozen']) == frozen, 'tool binding path')
    matches(source, tool)
    matches(frozen, tool)
    tool_count += 1
processes, logs = [], 0
with os.scandir(safe(root / 'processes')) as entries:
    names = [entry.name for entry in entries]
for name in sorted(names):
    require(re.fullmatch('[a-z0-9-]+', name) is not None, 'invalid process entry')
    directory = root / 'processes' / name
    receipt = load(directory / 'process-receipt.json')
    require(receipt['status'] == 'passed' and receipt['rootExited'] and receipt['rootExitCode'] == 0 and
            receipt['ownedSettlement'] == 'complete', 'process failed: ' + name)
    # Independent strong-exit gate added after the rejected java-version receipt.
    # A summary and PID alone are not enough: bind raw event, identity, host handle
    # observation and any parent-captured handle before accepting success.
    ident = load(directory / 'root-identity.json')
    event = load(directory / 'root-exit.json')
    host = load(directory / 'host-receipt.json')
    proof = receipt.get('rootExitProof')
    require(isinstance(proof, dict) and host == receipt['hostReceipt'], 'proof/host absent or contradictory')
    require(set(ident) == {'pid', 'startTimeUtc'} and set(event) ==
            {'schema', 'pid', 'startTimeUtc', 'exitCode', 'observationTick', 'elapsedMs'}, 'exit schema')
    require(event['schema'] == 'root-exit/v1' and proof['schema'] == 'root-exit-proof/v1', 'proof schema')
    require(type(ident['pid']) is int and ident['pid'] > 0 and ident['pid'] != receipt['hostPid'], 'root PID')
    require(isinstance(ident['startTimeUtc'], str) and re.fullmatch(
            r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{7}Z', ident['startTimeUtc']), 'root UTC string')
    for field in ('pid', 'startTimeUtc'):
        require(ident[field] == event[field] == proof[field], 'exit identity mismatch')
    require(proof['pid'] == host['rootPid'] and proof['startTimeUtc'] == host['rootStartTimeUtc'], 'host identity mismatch')
    require(type(event['exitCode']) is int and event['exitCode'] == proof['exitCode'] == host['rootExitCode'] ==
            receipt['rootExitCode'] == receipt['observedRootExitCode'] == 0, 'exit code mismatch')
    require(type(event['observationTick']) is int and event['observationTick'] > 0 and
            event['observationTick'] == proof['observationTick'] == host['rootObservationTick'], 'exit tick mismatch')
    require(type(event['elapsedMs']) is int and event['elapsedMs'] >= 0 and host['started'] and
            host['rootExited'] and host['rootEventPublished'] and host['streamsCompleted'], 'host not observed')
    require(receipt['hostExited'] and receipt['hostExitCode'] == 0 and not receipt['rootExitEvidenceError'], 'host/evidence failure')
    captured = [c for c in receipt['capturedDescendants'] if c['pid'] == proof['pid']]
    require(proof['parentCaptured'] == bool(captured) and len(captured) <= 1, 'capture proof mismatch')
    for c in captured:
        require(c['startTimeUtc'] == proof['startTimeUtc'] and c['exitObserved'] and c['exitCode'] == 0, 'captured root mismatch')
    require(all(c['exitObserved'] for c in receipt['capturedDescendants']), 'live captured handle')
    require(proof['source'] == 'host-held-root-handle-event' +
            ('-and-parent-captured-handle' if captured else ''), 'proof source mismatch')
    require(all(host[k]['eof'] and not host[k]['truncated'] and not host[k]['error'] for k in ('stdout','stderr')), 'host stream incomplete')
    for stream in ('stdout', 'stderr', 'host-stdout', 'host-stderr'):
        item = receipt[stream]
        path = lexical(item['path'])
        require(path.parent == directory and not item['partial'], 'incomplete or foreign stream')
        matches(path, item)
        logs += 1
    processes.append({'name': name, 'receipt': identity(directory / 'process-receipt.json'),
                      'rootIdentity': identity(directory / 'root-identity.json'),
                      'rootEvent': identity(directory / 'root-exit.json'), 'proof': proof})
mode = result['mode']
counts = None
skips = []
xml_ids = []
if mode in ('targeted', 'full', 'buildsrc'):
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = set()
    with os.scandir(safe(root / 'xml')) as entries:
        xml_names = [entry.name for entry in entries]
    for name in sorted(xml_names):
        require(name.startswith('TEST-') and name.endswith('.xml') and '/' not in name and '\\' not in name, 'invalid XML filename')
        file = root / 'xml' / name
        data = safe(file).read_bytes()
        require(len(data) <= 16 * 1024 * 1024 and b'<!DOCTYPE' not in data and b'<!ENTITY' not in data, 'XML outside contract')
        suite = ET.fromstring(data)
        require(suite.tag == 'testsuite' and suite.get('name') not in suites, 'duplicate or invalid suite')
        suites.add(suite.get('name'))
        actual = dict(tests=0, failures=0, errors=0, skipped=0)
        for ordinal, case in enumerate(suite.findall('testcase')):
            actual['tests'] += 1
            states = [case.find(tag) is not None for tag in ('failure', 'error', 'skipped')]
            require(sum(states) <= 1, 'conflicting testcase statuses')
            for key, state in zip(('failures', 'errors', 'skipped'), states):
                actual[key] += state
            if states[2]:
                skips.append({'file': name, 'ordinal': ordinal, 'class': case.get('classname'),
                              'name': case.get('name'), 'reason': ET.tostring(case.find('skipped'), encoding='unicode')})
        require(all(int(suite.get(key)) == value for key, value in actual.items()), 'suite and cases differ')
        for key, value in actual.items():
            counts[key] += value
        xml_ids.append({'path': name, **identity(file)})
    require(counts['tests'] > 0 and counts['failures'] == 0 and counts['errors'] == 0, 'test result not accepted')
    require(all(result['testResults'][key] == value for key, value in counts.items()), 'summary differs from raw XML')
review = {'schema': 'root-stage-review/v1', 'scope': str(root), 'mode': mode, 'testedCommit': before['testedCommit'],
          'counts': counts, 'passedCases': counts['tests'] - counts['skipped'] if counts else None, 'skips': skips,
          'inputCount': len(seen), 'typeCount': len(test_sources), 'toolCount': tool_count,
          'processes': processes, 'logCount': logs, 'xml': xml_ids,
          'rawInputsLogsAndCountsVerified': True, 'imageBinariesReviewedByThisScript': False,
          'fullAcceptance': False}
with out.open('x', encoding='utf-8') as output:
    json.dump(review, output, ensure_ascii=False, indent=2)
print(json.dumps({key: review[key] for key in ('mode', 'counts', 'inputCount', 'typeCount', 'toolCount', 'logCount')}, ensure_ascii=False))
