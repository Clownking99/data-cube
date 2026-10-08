"""Independent, read-only audit of named G9c worker evidence; never runs Gradle."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--worker', required=True)
p.add_argument('--out', required=True)
p.add_argument('--revision', choices=('011', '014'), default='011')
a = p.parse_args()
worker = Path(a.worker).resolve()
evidence = worker / 'docs/superpowers/verification/evidence/g9-jdbc-export-20261008-worker'
out = Path(a.out).resolve()
out.mkdir(parents=True, exist_ok=False)
(out / '.gitattributes').write_bytes(b'* -text\n')

def within(root, relative):
    rel = Path(relative)
    if rel.is_absolute() or any(x.lower() == '.testagent' or x == '..' for x in rel.parts):
        raise ValueError('Unsafe evidence path')
    result = (root / rel).resolve()
    if not result.is_relative_to(root.resolve()):
        raise ValueError('Escaped evidence root')
    return result

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def capture(path, name):
    raw = path.read_bytes()
    (out / name).write_bytes(raw)
    return json.loads(raw.decode('utf-8-sig'))

def verify(root, entries):
    results = []
    for entry in entries:
        path = within(root, entry['path'])
        size, digest = path.stat().st_size, sha(path)
        results.append(dict(path=entry['path'], bytes=size, sha256=digest,
                            matches=size == entry.get('bytes', entry.get('length'))
                            and digest == entry['sha256'].upper()))
    return results

def xml_receipt(run):
    suites, physical = [], []
    for path in sorted((run / 'xml').glob('TEST-*.xml')):
        suite = ET.fromstring(path.read_bytes())
        cases = suite.findall('testcase')
        counts = {k: int(suite.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')}
        actual = dict(tests=len(cases), failures=sum(c.find('failure') is not None for c in cases),
                      errors=sum(c.find('error') is not None for c in cases),
                      skipped=sum(c.find('skipped') is not None for c in cases))
        suites.append(dict(name=suite.get('name'), **counts, casesMatch=counts == actual, sha256=sha(path)))
        for node in suite.findall('system-out'):
            for line in (node.text or '').splitlines():
                if line.startswith(('PGDUMP_PHYSICAL ', 'PGDUMP_FAMILY ', 'TABLE_BASELINE ', 'TABLE_WINDOW ', 'TABLE_CLIENT ', 'JDBC_PHYSICAL ', 'JDBC_DEADLINE ', 'JDBC_CANCEL_WORKER ', 'JDBC_LATE_VALUE ', 'JDBC_LOB_ERROR ', 'JDBC_WINDOW ', 'JDBC_LATE_DRIVER ', 'JDBC_DDL_METADATA ')):
                    physical.append(dict(suite=suite.get('name'), line=line))
    exit_info = json.loads((run / 'exit.json').read_text(encoding='utf-8-sig'))
    logs = (run / 'stdout.log').read_text(encoding='utf-8-sig', errors='replace')
    return dict(run=run.name, exitCode=exit_info['exitCode'], suites=suites,
                totals={k: sum(s[k] for s in suites) for k in ('tests', 'failures', 'errors', 'skipped')},
                tasks=[line for line in logs.splitlines() if line.startswith('> Task ') or 'BUILD SUCCESSFUL' in line or 'BUILD FAILED' in line],
                physical=physical)

freeze = evidence / ('p1c-final-freeze-' + a.revision)
run = evidence / ('p1c-011-affected-targeted' if a.revision == '011' else 'p1c-014-ddl-affected-targeted')
frozen = capture(freeze / 'manifest.json', 'worker-freeze.json')
artifacts = capture(evidence / ('p1c-artifact-manifest-' + a.revision + '.json'), 'worker-artifacts.json')
sources = capture(run / 'sources-before-run.json', 'worker-run-sources.json')
command = capture(run / 'command.json', 'worker-command.json')
capture(run / 'exit.json', 'worker-exit.json')
physical_claim = capture(evidence / ('p1c-physical-receipts-' + a.revision + '.json'), 'worker-physical.json')
old_root = worker / 'docs/superpowers/verification/evidence/g9-table-export-20261008-worker'
old_manifest = capture(old_root / 'p1a-artifact-manifest-012.json', 'worker-old-p1a-artifacts.json')
verified = dict(frozen=verify(freeze, frozen), artifacts=verify(evidence, artifacts),
                runSources=verify(run / 'source-before-run', sources['files']), oldP1a=verify(old_root, old_manifest))
b_root = worker / 'docs/superpowers/verification/evidence/g9-pgdump-20261008-worker'
b_manifest = capture(b_root / 'p1b-artifact-manifest-010.json', 'worker-old-p1b-artifacts.json')
b_freeze = capture(b_root / 'p1b-final-freeze-010/manifest.json', 'worker-old-p1b-freeze.json')
verified['oldP1b'] = verify(b_root, b_manifest)
verified['oldP1bFrozen'] = verify(b_root / 'p1b-final-freeze-010', b_freeze)
if a.revision != '011':
    c_manifest = capture(evidence / 'p1c-artifact-manifest-011.json', 'worker-old-p1c-011-artifacts.json')
    verified['oldP1c011'] = verify(evidence, c_manifest)
capture(evidence / 'p1c-driver-static-inspection.json', 'worker-driver-static.json')
(out / 'worker-frozen-report.md').write_bytes(within(freeze, 'docs/superpowers/verification/2026-10-08-g9-table-export-worker.md').read_bytes())
current_xml = [dict(path=p.name, archiveSha=sha(p), currentSha=sha(worker / 'build/test-results/test' / p.name))
               for p in sorted((run / 'xml').glob('TEST-*.xml'))]
(out / 'root-current-xml-binding.json').write_text(json.dumps(current_xml, indent=2) + '\n', encoding='utf-8')
frozen_map = {e['path']: e['sha256'].upper() for e in frozen}
bindings = [dict(path=e['path'], matchesFrozen=frozen_map.get(e['path']) == e['sha256'].upper()) for e in sources['files']]
current = [dict(path=e['path'], matchesFrozen=sha(within(worker, e['path'])) == e['sha256'].upper()) for e in frozen]
history = [xml_receipt(evidence / name) for name in (
    'p1c-001-baseline-red', 'p1c-002-jdbc', 'p1c-003-ownership', 'p1c-004-integrity',
    'p1c-005-targeted', 'p1c-006-cancellation', 'p1c-007-window', 'p1c-008-window-values',
    'p1c-009-affected-targeted', 'p1c-010-late-driver-red', 'p1c-011-affected-targeted')]
if a.revision == '014':
    history.extend(xml_receipt(evidence / name) for name in (
        'p1c-012-ddl-metadata-red', 'p1c-013-ddl-metadata-targeted', 'p1c-014-ddl-affected-targeted'))
head = subprocess.run(['git', '-C', str(worker), 'rev-parse', 'HEAD'], check=True, capture_output=True, text=True).stdout.strip()
report_path = 'docs/superpowers/verification/2026-10-08-g9-table-export-worker.md'
unexpected_binding = [e['path'] for e in bindings if not e['matchesFrozen'] and e['path'] != report_path]
receipt = dict(utc=datetime.now(timezone.utc).isoformat(), worker=str(worker), head=head,
               commandHeadMatches=head == command['head'] == sources['head'], verified=verified,
               currentXmlMatches=all(e['archiveSha'] == e['currentSha'] for e in current_xml),
               runToFrozen=bindings, currentToFrozen=current, history=history,
               physicalReceiptMatchesXml=history[-1]['physical'] == physical_claim,
               expectedReportChange='Report records results after execution; not runtime source.', rootRanGradle=False,
               scope='P1c independent audit only; no full/buildSrc/image/main/live acceptance')
(out / 'root-p1c-verification.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
summary = dict(counts={k: len(v) for k, v in verified.items()},
               hashMismatches=[e['path'] for entries in verified.values() for e in entries if not e['matches']],
               unexpectedRunBinding=unexpected_binding,
               currentMismatches=[e['path'] for e in current if not e['matchesFrozen']],
               commandHeadMatches=receipt['commandHeadMatches'], physicalMatches=receipt['physicalReceiptMatchesXml'],
               currentXmlMatches=receipt['currentXmlMatches'],
               history=[dict(run=r['run'], exitCode=r['exitCode'], suites=len(r['suites']), **r['totals']) for r in history])
print(json.dumps(summary, ensure_ascii=False, indent=2))
if (summary['hashMismatches'] or unexpected_binding or summary['currentMismatches']
    or not summary['commandHeadMatches'] or not summary['physicalMatches'] or not summary['currentXmlMatches']
    or any(not s['casesMatch'] for r in history for s in r['suites'])):
    raise SystemExit(1)
