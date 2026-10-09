import pathlib,json,hashlib,xml.etree.ElementTree as ET
base=pathlib.Path(__file__).absolute().parent
rows=[]
for entry in json.loads((base/'progress.json').read_text()):
 file=pathlib.Path(entry['inner']);scope=json.loads(file.with_name('scope-bound.json' if file.with_name('scope-bound.json').exists() else 'scope.json').read_text(encoding='utf-8-sig'));result=json.loads(file.read_text(encoding='utf-8-sig'));outer=json.loads(pathlib.Path(entry['outer']).read_text())
 exe=[]
 for name,expected in scope['executables'].items():
  p=pathlib.Path(name);b=p.read_bytes();row=dict(path=name,length=len(b),sha256=hashlib.sha256(b).hexdigest());row['matches']=row['length']==expected['length'] and row['sha256'].lower()==expected['sha256'].lower();assert row['matches'];exe.append(row)
 (base/(entry['mode']+'-executables-after.json')).write_text(json.dumps(exe,indent=2))
 counts=dict(suites=0,tests=0,passed=0,skipped=0,failures=0,errors=0)
 for xml in file.parent.glob('xml/TEST*.xml'):
  suite=ET.fromstring(xml.read_bytes());counts['suites']+=1
  for case in suite.findall('testcase'):
   counts['tests']+=1;status='errors' if case.find('error') is not None else 'failures' if case.find('failure') is not None else 'skipped' if case.find('skipped') is not None else 'passed';counts[status]+=1
 if result.get('testResults'):
  for key,value in counts.items():assert result['testResults'][key]==value,(key,value,result['testResults'][key])
 rows.append(dict(mode=entry['mode'],counts=counts,status=result['status'],outerExit=outer['actualExitCode'],jobEmpty=outer['settlement']['actualJobQueryObservedEmpty'],receipt=entry['inner']))
(base/'independent-stage-audit.json').write_text(json.dumps(rows,indent=2));print(json.dumps(rows,indent=2))
