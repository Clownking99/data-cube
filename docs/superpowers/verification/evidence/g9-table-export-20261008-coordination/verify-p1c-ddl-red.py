"""Read-only audit of the fixed G9c 012 regression, without executing product/tests."""
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
evidence = worker / 'docs/superpowers/verification/evidence/g9-jdbc-export-20261008-worker'
run = evidence / 'p1c-012-ddl-metadata-red'
out = Path(a.out).resolve()
out.mkdir(parents=True, exist_ok=False)
(out / '.gitattributes').write_bytes(b'* -text\n')

def within(root, relative):
    path = Path(relative)
    if path.is_absolute() or any(x.lower() == '.testagent' or x == '..' for x in path.parts):
        raise ValueError('Unsafe path')
    result = (root / path).resolve()
    if not result.is_relative_to(root.resolve()):
        raise ValueError('Escaped root')
    return result

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def capture(relative, name):
    raw = within(run, relative).read_bytes()
    (out / name).write_bytes(raw)
    return raw

sources = json.loads(capture('sources-before-run.json', 'worker-run-sources.json').decode('utf-8-sig'))
command = json.loads(capture('command.json', 'worker-command.json').decode('utf-8-sig'))
exit_info = json.loads(capture('exit.json', 'worker-exit.json').decode('utf-8-sig'))
stdout = capture('stdout.log', 'worker-stdout.log').decode('utf-8-sig', errors='replace')
frozen = json.loads((evidence / 'p1c-final-freeze-011/manifest.json').read_text(encoding='utf-8-sig'))
frozen_map = {e['path']: e['sha256'].upper() for e in frozen}
bindings = []
for entry in sources['files']:
    path = within(run / 'source-before-run', entry['path'])
    bindings.append(dict(path=entry['path'], bytes=path.stat().st_size, sha256=sha(path),
        snapshotMatches=path.stat().st_size == entry.get('bytes', entry.get('length')) and sha(path) == entry['sha256'].upper(),
        productMatches011=sha(path) == frozen_map.get(entry['path']) if entry['path'].startswith('src/') else None))
suites = []
for xml_path in sorted((run / 'xml').glob('TEST-*.xml')):
    raw = capture('xml/' + xml_path.name, xml_path.name)
    suite = ET.fromstring(raw)
    cases = suite.findall('testcase')
    counts = {k: int(suite.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')}
    actual = dict(tests=len(cases), failures=sum(c.find('failure') is not None for c in cases),
                  errors=sum(c.find('error') is not None for c in cases), skipped=sum(c.find('skipped') is not None for c in cases))
    suites.append(dict(name=suite.get('name'), sha256=sha(xml_path), counts=counts, actual=actual,
        failures=[dict(test=c.get('name'), message=c.find('failure').get('message')) for c in cases if c.find('failure') is not None]))
totals = {k: sum(s['counts'][k] for s in suites) for k in ('tests', 'failures', 'errors', 'skipped')}
receipt = dict(utc=datetime.now(timezone.utc).isoformat(), command=command, exit=exit_info, bindings=bindings,
    suites=suites, totals=totals, actualTest='> Task :test FAILED' in stdout,
    tasks=[line for line in stdout.splitlines() if line.startswith('> Task ') or 'BUILD FAILED' in line],
    assertionBoundary='assertThrows fails because export returns normally; subsequent old-byte, move-count and cleanup assertions were not reached.',
    rootRanGradle=False, scope='012 new regression evidence only, no GREEN or phase acceptance')
(out / 'root-p1c-ddl-red-verification.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
ok = (all(e['snapshotMatches'] and e['productMatches011'] is not False for e in bindings)
      and all(s['counts'] == s['actual'] for s in suites)
      and totals == dict(tests=12, failures=12, errors=0, skipped=0)
      and receipt['actualTest'] and exit_info['exitCode'] == 1
      and command['head'] == sources['head'] == 'b81923f29e0a6b6603710a589504aab490995491')
print(json.dumps(dict(ok=ok, snapshots=len(bindings), productBindings=sum(e['productMatches011'] is not None for e in bindings), totals=totals), indent=2))
if not ok:
    raise SystemExit(1)
