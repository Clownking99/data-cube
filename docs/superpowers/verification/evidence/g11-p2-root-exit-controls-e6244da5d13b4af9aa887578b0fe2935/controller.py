import pathlib,json,hashlib,importlib.util,sys,traceback
base=pathlib.Path(__file__).absolute().parent;s=json.loads((base/'spec.json').read_text());repo=pathlib.Path(s['repo']);evidence=base.parent;tag=base.name

def save(path,value):path.write_bytes(json.dumps(value,ensure_ascii=False,indent=2).encode())
def load(name):
 spec=importlib.util.spec_from_file_location('frozen_'+name.replace('.','_'),base/'tools'/name);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def call(m,args):
 old=sys.argv
 try:sys.argv=[str(m.__file__)]+args;code=m.main()
 finally:sys.argv=old
 if code:raise RuntimeError('HELPER_EXIT:'+str(code))

def identities():
 rows=[]
 for row in json.loads((base/'tool-manifest.json').read_text()):
  for root in (base/'tools',repo/'scripts/verification'):
   p=root/row['path'];raw=p.read_bytes();assert len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256'],str(p)
  rows.append(row)
 return rows

results=[]
try:
 identities();core=load('check-core.py');p2=load('check-p2.py')
 out=evidence/(tag+'-python');out.mkdir();result=core.tool_checks(out);save(out/'results.json',result);assert len(result['cases'])==21;results.append(dict(kind='python',count=21,result=str(out/'results.json')));save(base/'progress.json',results);print('PASS python 21',flush=True)
 for kind,checker,count in [('process',core.process_checks,21),('root-exit',core.root_exit_checks,18)]:
  out=evidence/(tag+'-'+kind);result=checker(repo,out,s['pwsh'],s['python'],s['jdk'],s['cache']);save(out/'results.json',result);assert len(result['cases'])==count;results.append(dict(kind=kind,count=count,result=str(out/'results.json')));save(base/'progress.json',results);print('PASS '+kind+' '+str(count),flush=True)
 out=evidence/(tag+'-policy');call(p2,['--out',str(out),'--pwsh',s['pwsh'],'--repo',str(repo)]);result=json.loads((out/'results.json').read_text());assert len(result['cases'])+len(result['rolePolicy'])==31;results.append(dict(kind='policy-role-image',count=31,result=str(out/'results.json')));save(base/'progress.json',results);print('PASS policy-role-image 31',flush=True)
 out=evidence/(tag+'-outer');call(p2,['--out',str(out),'--pwsh',s['pwsh'],'--repo',str(repo),'--check-outer','--python',s['python'],'--jdk',s['jdk'],'--cache',s['cache'],'--runtime-parent',s['runtimeParent']]);result=json.loads((out/'results.json').read_text());assert len(result['cases'])==7;results.append(dict(kind='outer',count=7,result=str(out/'results.json')));save(base/'progress.json',results);print('PASS outer 7',flush=True)
 identities();assert sum(row['count'] for row in results)==98;save(base/'controller-result.json',dict(passed=True,count=98,engineering=False,groups=results));print('ALL 98 PASS; no Gradle',flush=True)
except BaseException as error:
 save(base/'controller-result.json',dict(passed=False,engineering=False,groups=results,failure=repr(error)));traceback.print_exc();sys.exit(1)