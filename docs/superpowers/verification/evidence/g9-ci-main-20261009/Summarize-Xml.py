"""Count fresh Gradle XML elements, including empty <skipped/> nodes."""
import json, pathlib, sys
from xml.etree import ElementTree as ET
root = pathlib.Path(sys.argv[1])
result = dict(suites=0, tests=0, failures=0, errors=0, skipped=0, skips=[], failedCases=[])
for file in sorted((root / 'xml').glob('TEST-*.xml')):
    suite = ET.parse(file).getroot()
    result['suites'] += 1
    cases = list(suite.findall('testcase'))
    assert len(cases) == int(suite.attrib['tests']), file
    result['tests'] += len(cases)
    counts = dict(failures=0, errors=0, skipped=0)
    for case in cases:
        for tag, key in [('failure', 'failures'), ('error', 'errors'), ('skipped', 'skipped')]:
            node = case.find(tag)
            if node is not None:
                counts[key] += 1
                receipt = dict(className=case.get('classname'), name=case.get('name'),
                               message=node.get('message'), text=''.join(node.itertext()))
                result['skips' if tag == 'skipped' else 'failedCases'].append(receipt)
    for key, value in counts.items():
        assert value == int(suite.get(key, '0')), (file, key)
        result[key] += value
assert result['suites'] > 0
output = root / 'actual-xml-summary.json'
assert not output.exists(), 'Preserve earlier summary'
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result, ensure_ascii=True))
