"""Extract current XML synthetic ownership receipts without changing earlier evidence."""
import json, pathlib, re, sys
from xml.etree import ElementTree as ET
root = pathlib.Path(sys.argv[1])
receipts = []
prefix = re.compile(r'^(RED_HELPER |PGDUMP_PHYSICAL |PGDUMP_FAMILY |TABLE_WINDOW |TABLE_CLIENT |JDBC_WINDOW |TABLE_JDBC_PHYSICAL )')
for file in sorted((root / 'xml').glob('TEST-*.xml')):
    suite = ET.parse(file).getroot()
    for output in suite.findall('system-out'):
        for line in ''.join(output.itertext()).splitlines():
            if prefix.match(line):
                receipts.append(dict(xml=file.name, suite=suite.get('name'), receipt=line))
out = root / 'physical-receipts.json'
assert not out.exists(), 'Preserve existing receipts'
out.write_text(json.dumps(dict(count=len(receipts), receipts=receipts), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(run=root.name, physicalReceipts=len(receipts))))
