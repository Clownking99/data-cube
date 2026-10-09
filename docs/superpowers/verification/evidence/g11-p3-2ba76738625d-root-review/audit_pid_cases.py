"""Bind the corrected PID regressions to the fresh full-suite raw XML."""
from collections import Counter
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

base = Path(__file__).absolute().parent
repo = base.parents[4]
evidence = base.parent
review = json.loads((base / 'full-review.json').read_text())
scope = Path(review['scope'])
require(re.fullmatch(r'(g11-p2-eng-[0-9a-f]{32}-full|g11-p3-[0-9a-f]{12}-full-[0-9a-f]{32})', scope.parent.name)
        and re.fullmatch('[0-9a-f]{32}', scope.name), 'scope')
repo = scope.parents[5]
evidence = scope.parents[1]
name = 'com.datacube.export.PgDumpRunnerReliabilityTest'
rows = [r for r in review['xml'] if r['path'].endswith(name + '.xml')]
require(len(rows) == 1, 'full PID suite missing')
current = scope / 'xml' / rows[0]['path']
focused_manifest = json.loads((evidence / 'g11-p2-ci-pid-4983fab79aa14a5da1626836b240525c-frozen/manifest.json').read_text())
prior = [r for r in focused_manifest['files'] if r['path'].endswith(name + '.xml')]
require(len(prior) == 1, 'focused PID suite missing')
old = repo / prior[0]['path']
require(old.is_relative_to(evidence) and '.testagent' not in old.parts, 'focused scope')
old_raw = old.read_bytes()
require(len(old_raw) == prior[0]['length'] and hashlib.sha256(old_raw).hexdigest() == prior[0]['sha256'], 'focused XML changed')
raw = current.read_bytes()
root = ET.fromstring(raw)
cases = root.findall('testcase')
require(root.get('name') == name and len(cases) == 33, 'full PID count')
require(all(c.get('classname') == name and all(c.find(tag) is None for tag in ('failure','error','skipped')) for c in cases), 'PID case not passed')
require(Counter(c.get('name') for c in cases) == Counter(c.get('name') for c in ET.fromstring(old_raw).findall('testcase')), 'PID case identity multiset')
require(review['counts'] == dict(tests=4724, failures=0, errors=0, skipped=3) and review['passedCases'] == 4721, 'full-suite count')
receipt = dict(schema='root-full-pid-review/v1', testedCommit=review['testedCommit'],
               source='Fresh full suite; names compared as a multiset to independently reviewed 33-case focused XML. Parameter method names come from reviewed source, not fabricated XML attributes.',
               fullXml=str(current), length=len(raw), sha256=hashlib.sha256(raw).hexdigest(),
               passed=33, skipped=0, newRegressionCases=10, fullSuitePassed=4721, fullSuiteSkipped=3)
with (base / 'pid-case-review.json').open('x', encoding='utf-8') as f:
    json.dump(receipt, f, ensure_ascii=False, indent=2)
print(json.dumps(receipt, ensure_ascii=False))
