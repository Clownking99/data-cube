"""Record why a zero-exit engineering sequence is rejected, preserving raw evidence."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

base = Path(__file__).absolute().parent
evidence = base.parent
package = evidence/'g11-p3-581b459dad08-package'
progress = json.loads((package/'progress.json').read_text())
require([r['mode'] for r in progress] == ['targeted','full','buildsrc','image','linked'], 'stage order')
require(json.loads((package/'shell-exit.json').read_text())['actualPythonExitCode'] == 0, 'actual sequence exit')
rows = []
contradictions = []
for item in progress:
    root = Path(item['inner']).parent
    require(root.is_relative_to(evidence) and root.parent.name.startswith('g11-p3-581b459dad08-'), 'scope')
    stage = json.loads(Path(item['inner']).read_text())
    processes = []
    for p in sorted((root/'processes').iterdir()):
        require(p.is_dir() and p.name not in ('.testagent','.git'), 'process directory')
        receipt = json.loads((p/'process-receipt.json').read_text())
        contradictory = receipt['status'] == 'passed' and (not receipt['rootExited'] or receipt['rootExitCode'] != 0)
        if contradictory:
            contradictions.append(dict(mode=item['mode'], process=p.name, receipt=str(p/'process-receipt.json'),
                                       reportedStatus=receipt['status'], rootExited=receipt['rootExited'], rootExitCode=receipt['rootExitCode'],
                                       rootExitEventExists=(p/'root-exit.json').exists(), hostRootExited=receipt['hostReceipt']['rootExited'],
                                       hostRootExitCode=receipt['hostReceipt']['rootExitCode'], capturedDescendants=receipt['capturedDescendants']))
        for key in ('stdout','stderr','host-stdout','host-stderr'):
            row = receipt[key]
            stream = p/(key+'.bin')
            raw = stream.read_bytes()
            require(len(raw) == row['length'] and hashlib.sha256(raw).hexdigest() == row['sha256'].lower(), 'log identity')
        processes.append(dict(name=p.name, reportedStatus=receipt['status'], rootExited=receipt['rootExited'], rootExitCode=receipt['rootExitCode']))
    counts = dict(tests=0,failures=0,errors=0,skipped=0)
    skipped=[]; suites=0; pid_cases=None
    if item['mode'] in ('targeted','full','buildsrc'):
        for path in sorted((root/'xml').glob('*.xml')):
            xml=ET.fromstring(path.read_bytes()); cases=xml.findall('testcase'); suites+=1
            actual=dict(tests=len(cases),failures=sum(c.find('failure') is not None for c in cases),errors=sum(c.find('error') is not None for c in cases),skipped=sum(c.find('skipped') is not None for c in cases))
            require(actual == {k:int(xml.attrib[k]) for k in counts}, 'raw suite counts')
            for key in counts:counts[key]+=actual[key]
            for c in cases:
                if c.find('skipped') is not None:skipped.append(dict(className=c.get('classname'),name=c.get('name')))
            if xml.get('name') == 'com.datacube.export.PgDumpRunnerReliabilityTest':
                require(actual == dict(tests=33,failures=0,errors=0,skipped=0), 'PID counts')
                pid_cases=33
        require(suites>0 and counts['tests']>0, 'missing XML')
    rows.append(dict(mode=item['mode'], toolReportedStatus=stage['status'], rawXmlSuites=suites,
                     rawCounts=counts, skipped=skipped, pidPassedCases=pid_cases, processes=processes))
require([(r['mode'],r['process']) for r in contradictions] == [('full','java-version')], 'changed contradiction set')
require(rows[1]['rawCounts'] == dict(tests=4724,failures=0,errors=0,skipped=3), 'full counts')
verdict=dict(schema='root-rejected-p3/v1', testedCommit='e368a1b16bdee226925b363f06b4dc4f003c1f0f', accepted=False,
             deliveryAllowed=False, sequenceActualExitCode=0, reason='Contradictory process success without top-level root exit observation; independent audit refused.',
             contradictions=contradictions, stages=rows,
             next='Fix source and deterministic controls, freeze new tools, repeat complete worker and main validation. Do not relax reviewer or overwrite this round.')
with (base/'round-verdict.json').open('x',encoding='utf-8') as stream:json.dump(verdict,stream,ensure_ascii=False,indent=2)
print(json.dumps(dict(accepted=False,contradictions=contradictions,stages=[dict(mode=r['mode'],counts=r['rawCounts'],pidPassed=r['pidPassedCases']) for r in rows]),ensure_ascii=False))
