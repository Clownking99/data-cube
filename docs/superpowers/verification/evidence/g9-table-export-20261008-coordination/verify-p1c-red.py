"""Audit the named P1c baseline failure without running tests or reading user data."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--worker', required=True)
p.add_argument('--out', required=True)
a = p.parse_args()
worker = Path(a.worker).resolve()
run = worker / 'docs/superpowers/verification/evidence/g9-jdbc-export-20261008-worker/p1c-001-baseline-red'
out = Path(a.out).resolve()
out.mkdir(parents=True, exist_ok=False)
(out / '.gitattributes').write_bytes(b'* -text\n')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def capture(path, name):
    raw = path.read_bytes()
    (out / name).write_bytes(raw)
    return json.loads(raw.decode('utf-8-sig'))

def safe(root, relative):
    rel = Path(relative)
    if rel.is_absolute() or any(p.lower() == '.testagent' or p == '..' for p in rel.parts):
        raise ValueError('Unsafe evidence path')
    value = (root / rel).resolve()
    if not value.is_relative_to(root.resolve()):
        raise ValueError('Escaped evidence path')
    return value

sources = capture(run / 'sources-before-run.json', 'worker-run-sources.json')
command = capture(run / 'command.json', 'worker-command.json')
exit_info = capture(run / 'exit.json', 'worker-exit.json')
baseline = capture(worker / 'docs/superpowers/verification/evidence/g9-pgdump-20261008-worker/p1b-final-freeze-010/manifest.json', 'accepted-p1b-manifest.json')
prior = {e['path']: e['sha256'].upper() for e in baseline}
checks = []
for entry in sources['files']:
    path = safe(run / 'source-before-run', entry['path'])
    digest = sha(path)
    checks.append(dict(path=entry['path'], sha256=digest, bytes=path.stat().st_size,
                       matches=digest == entry['sha256'].upper() and path.stat().st_size == entry['length'],
                       matchesP1b=prior.get(entry['path']) == digest if entry['path'] in prior else None))
raw_files = [run / name for name in ('command.json', 'exit.json', 'sources-before-run.json', 'stdout.log', 'stderr.log')]
suites = []
for path in sorted((run / 'xml').glob('TEST-*.xml')):
    raw_files.append(path)
    suite = ET.fromstring(path.read_bytes())
    cases = suite.findall('testcase')
    counts = {k: int(suite.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')}
    actual = dict(tests=len(cases), failures=sum(c.find('failure') is not None for c in cases),
                  errors=sum(c.find('error') is not None for c in cases), skipped=sum(c.find('skipped') is not None for c in cases))
    suites.append(dict(name=suite.get('name'), **counts, casesMatch=actual == counts,
                       failureMessages=[dict(test=c.get('name'), message=c.find('failure').get('message')) for c in cases if c.find('failure') is not None]))
raw = [dict(path=str(path.relative_to(run)).replace('\\', '/'), sha256=sha(path), bytes=path.stat().st_size) for path in raw_files]
logs = (run / 'stdout.log').read_text(encoding='utf-8-sig', errors='replace')
totals = {k: sum(s[k] for s in suites) for k in ('tests', 'failures', 'errors', 'skipped')}
receipt = dict(utc=datetime.now(timezone.utc).isoformat(), worker=str(worker), sourceChecks=checks, rawFiles=raw,
               commandHeadMatches=command['head'] == sources['head'], exitCode=exit_info['exitCode'], suites=suites, totals=totals,
               taskLines=[line for line in logs.splitlines() if line.startswith('> Task ')], rootRanGradle=False,
               limitation='First failing assertThrows prevents subsequent conversion count/old bytes assertions; no GREEN or complete output roundtrip evidence.')
(out / 'root-p1c-red-verification.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
summary = dict(sources=len(checks), rawFiles=len(raw), **totals, exitCode=exit_info['exitCode'],
               hashMismatches=[c['path'] for c in checks if not c['matches']],
               changedFromP1b=[c['path'] for c in checks if c['matchesP1b'] is False])
print(json.dumps(summary, ensure_ascii=False, indent=2))
if summary['hashMismatches'] or not receipt['commandHeadMatches'] or any(not s['casesMatch'] for s in suites):
    raise SystemExit(1)
