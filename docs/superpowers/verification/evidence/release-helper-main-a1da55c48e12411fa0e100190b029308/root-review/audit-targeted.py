import pathlib,json,hashlib,xml.etree.ElementTree as ET,base64,re,sys
package=pathlib.Path(sys.argv[1]);repo=pathlib.Path(sys.argv[2]);out=pathlib.Path(sys.argv[3]);config=json.loads((package/'config.json').read_bytes())
scopeDirs=[p for p in pathlib.Path(config['stageEvidence']).iterdir() if re.fullmatch('[0-9a-f]{32}',p.name)];assert len(scopeDirs)==1;s=scopeDirs[0]
def load(p):return json.loads(p.read_bytes())
def identity(p):
 raw=p.read_bytes();return dict(path=str(p),length=len(raw),sha256=hashlib.sha256(raw).hexdigest())
checked=[]
for name,root in [('entry-manifest.json',package),('source-identities.json',repo),('tool-manifest.json',repo/'scripts/verification')]:
 for row in load(package/name):
  p=root/row['path'];x=identity(p);assert x['length']==row['length'] and x['sha256']==row['sha256'];checked.append(x)
v=load(s/'stage-result.json');op=load(package/'operator-result.json');assert v['status']=='passed' and op['actualExit']==0 and not op['failure'] and op['settlement']['actualJobQueryObservedEmpty'];assert not op['settlement']['beforeTermination'] and not op['settlement']['afterTermination']
processes=[]
for item in v['processes']:
 p=s/'processes'/item['name'];a=load(p/'process-receipt.json');h=load(p/'host-receipt.json');e=load(p/'root-exit.json');i=load(p/'root-identity.json');proof=a['rootExitProof']
 assert a['status']=='passed' and a['ownedSettlement']=='complete' and a['hostExitCode']==0 and a['observedRootExitCode']==0
 assert h['rootExited'] and h['streamsCompleted'] and not h['primaryFailure'] and e['exitCode']==h['rootExitCode']==proof['exitCode']==0
 assert e['pid']==i['pid']==h['rootPid']==proof['pid'];assert e['startTimeUtc']==i['startTimeUtc']==h['rootStartTimeUtc']==proof['startTimeUtc'];assert 0<proof['observationTick']<=e['observationTick']==h['rootObservationTick']
 for stream in ('stdout','stderr'):
  z=identity(p/(stream+'.bin'));b=h[stream];assert b['eof'] and not b['truncated'] and b['error'] is None and z['length']==b['bytesRead']==b['bytesWritten'];checked.append(z)
 checked.extend(identity(p/n) for n in ('process-receipt.json','host-receipt.json','root-exit.json','root-identity.json','request.json'))
 processes.append(dict(name=item['name'],pid=e['pid'],exit=e['exitCode'],rootIdentityMatched=True,completeStreams=True))
cases=[];receipts=[];xmlFiles=sorted((s/'xml').glob('TEST-*.xml'))
for p in xmlFiles:
 x=ET.parse(p).getroot();xs=x.findall('testcase');assert len(xs)==int(x.get('tests')) and all(int(x.get(k,'0'))==0 for k in ('failures','errors','skipped'))
 for c in xs:
  assert c.find('failure') is None and c.find('error') is None and c.find('skipped') is None;cases.append(dict(suite=x.get('name'),name=c.get('name')))
 for line in (x.findtext('system-out') or '').splitlines():
  if line.startswith('UPDATE_HELPER_RECEIPT '):
   assert 'exited=true' in line
   for digest,encoded in re.findall(r'sha256=(\S+) base64=(\S*)',line):
    raw=base64.b64decode(encoded,validate=True)
    if digest=='NOT_COMPLETE':assert len(raw)==65536 and 'outputComplete=false' in line
    else:assert hashlib.sha256(raw).hexdigest()==digest
   receipts.append(dict(suite=x.get('name'),phase=re.search(r'phase=(\S+)',line).group(1),pid=int(re.search(r' pid=(\d+)',line).group(1)),exit=int(re.search(r' exit=(-?\d+)',line).group(1)),model='pid=99999999 ' in line))
 checked.append(identity(p))
assert len([x for x in cases if x['suite']=='com.datacube.update.PortableUpdateHelperTest'])==11
assert len([x for x in cases if x['suite']=='com.datacube.update.UpdateHelperProcessTest'])==7
assert len(cases)==92 and len(receipts)==18 and sum(x['model'] for x in receipts)==3
result=dict(passed=True,source='Independent root audit of current XML, original process events, bytes and source/tool identities',package=str(package),scope=str(s),tests=dict(passed=len(cases),skipped=0,xmlFiles=len(xmlFiles)),cases=cases,processes=processes,helperReceipts=receipts,files=checked)
out.write_bytes(json.dumps(result,ensure_ascii=False,indent=2).encode());print(json.dumps(dict(passed=True,tests=92,processes=len(processes),receipts=len(receipts),output=str(out),sha256=hashlib.sha256(out.read_bytes()).hexdigest())))
