import pathlib,json,hashlib,importlib.util,sys,traceback,re
base=pathlib.Path(__file__).absolute().parent;s=json.loads((base/'spec.json').read_text());repo=pathlib.Path(s['repo']);evidence=base.parent;tag=base.name

def require(condition,reason):
 if not condition:raise RuntimeError('CONTROLLER_REFUSAL:'+reason)

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
   p=root/row['path'];raw=p.read_bytes();require(len(raw)==row['length'] and hashlib.sha256(raw).hexdigest()==row['sha256'],'IDENTITY:'+str(p))
  rows.append(row)
 return rows

def archive_roots():
 # Read only actual, saved per-case specs. The directory name is never a reconstruction formula.
 tool=load('evidence_tools.py');roots={str(base)};cases=[];uncreated=[];seen=set()
 for kind in ('python','process','root-exit','policy','outer'):
  directory=evidence/(tag+'-'+kind)
  tool.no_links(directory)
  if not directory.exists():continue
  roots.add(str(directory))
  if kind not in ('process','root-exit','outer'):continue
  for file in sorted(directory.glob('*-spec.json')):
   tool.no_links(file);spec=json.loads(file.read_text(encoding='utf-8-sig'))
   fields={'repo','tools','out','stageEvidence','mode','inputSpec','jdk','cache','pwsh','python','runtimeParent','imageSourceScope','deadlineSeconds','fixture','processDeadlineMs','settleMs','streamCap','outerFixture'}
   require(set(spec)==fields,'ARCHIVE_SPEC_FIELDS:'+str(file))
   paths={key:tool.admitted_path(spec[key]) for key in ('repo','tools','out','stageEvidence','jdk','cache','pwsh','python','runtimeParent')}
   require(paths['repo']==repo and paths['tools']==base/'tools','ARCHIVE_SPEC_RUN_IDENTITY')
   require(all(str(paths[key]).replace('\\','/').casefold()==str(s[key]).replace('\\','/').casefold() for key in ('jdk','cache','pwsh','python')),'ARCHIVE_EXECUTABLE_IDENTITY')
   require(paths['out'].parent==evidence and paths['stageEvidence'].parent==evidence,'ARCHIVE_ROOT_PARENT')
   require(spec['mode']=='fixture' and spec['inputSpec'] is None and spec['imageSourceScope'] is None,'ARCHIVE_FIXTURE_ONLY')
   original_cases={'normal','argv','nonzero','dual','continuous','overflow','child-pipe','nonzero-child-pipe','detached-child','start-failure','assign-failure','unobserved-settlement','cap-exact','cap-plus-one','cancel','tool-change','compile-zero-xml','compile-stat-failure','nonzero-child-overflow','skip-live','skip-native'}
   exit_cases={'exit-tail-zero','exit-tail-seven','exit-late-identity','exit-no-capture-zero','exit-no-capture-seven','exit-missing-event','exit-corrupt-event','exit-wrong-pid','exit-wrong-time','exit-wrong-code','exit-missing-identity','exit-corrupt-identity','exit-summary-mismatch','exit-date-coercion','exit-root-is-host','exit-missing-seven','exit-cancel-missing','exit-budget-missing'}
   if kind in ('process','root-exit'):
    require(spec['fixture'] in (original_cases if kind=='process' else exit_cases),'ARCHIVE_CASE_ALLOWLIST')
    match=re.fullmatch(r'g11-p2-synthetic-([0-9a-f]{32})-(.+)',paths['stageEvidence'].name)
    require(match is not None and match.group(2)==spec['fixture'],'ARCHIVE_SYNTHETIC_UUID')
    require(match.group(1) not in seen,'ARCHIVE_UUID_REUSE');seen.add(match.group(1))
    require(paths['out']==pathlib.Path(str(paths['stageEvidence'])+'-owner') and spec['outerFixture'] is None,'ARCHIVE_OWNER_PAIR')
   else:
    require(spec['outerFixture'] in ('normal','start-failure','assign-failure','detached-child','nonzero-child-pipe','overflow','nonzero-child-overflow'),'ARCHIVE_OUTER_CASE')
    require(paths['out'].name==directory.name+'-'+spec['outerFixture']+'-owner' and paths['stageEvidence']==pathlib.Path(str(paths['out'])+'-stage'),'ARCHIVE_OUTER_OWNER_PAIR')
   row={'spec':str(file),'stageEvidence':str(paths['stageEvidence']),'out':str(paths['out']),'scopes':[]}
   for key in ('out','stageEvidence'):
    root=paths[key];tool.no_links(root)
    if root.exists():roots.add(str(root))
    else:uncreated.append({'spec':str(file),'kind':key,'path':str(root)})
   if spec['outerFixture'] is None and paths['stageEvidence'].exists():
    tool.no_links(paths['stageEvidence']/'run-owner.json')
    marker=json.loads((paths['stageEvidence']/'run-owner.json').read_text(encoding='utf-8-sig'))
    require(set(marker)=={'owner'} and re.fullmatch('[0-9a-f]{32}',marker['owner']) is not None,'ARCHIVE_OWNER_MARKER')
    for scope_file in sorted(paths['stageEvidence'].glob('*/scope.json')):
     tool.no_links(scope_file);scope=json.loads(scope_file.read_text(encoding='utf-8-sig'));owned=tool.admitted_path(scope['owned'])
     require(owned==scope_file.parent and owned.parent==paths['stageEvidence'] and re.fullmatch('[0-9a-f]{32}',owned.name) is not None,'ARCHIVE_SCOPE_OWNERSHIP')
     require(tool.admitted_path(scope['paths']['Repo'])==repo and tool.admitted_path(scope['paths']['EvidenceRoot'])==paths['stageEvidence'] and scope['stage']=='fixture','ARCHIVE_SCOPE_IDENTITY')
     bindings=scope['tools'];expected_tools=json.loads((base/'tool-manifest.json').read_text())
     require(set(bindings)=={entry['path'] for entry in expected_tools},'ARCHIVE_SCOPE_TOOL_SET')
     for entry in expected_tools:
      binding=bindings[entry['path']]
      require(binding['length']==entry['length'] and binding['sha256'].casefold()==entry['sha256'].casefold(),'ARCHIVE_SCOPE_TOOL_IDENTITY')
      require(tool.admitted_path(binding['source'])==repo/'scripts/verification'/entry['path'] and tool.admitted_path(binding['frozen'])==owned/'tools'/entry['path'],'ARCHIVE_SCOPE_TOOL_PATHS')
     runtime=tool.admitted_path(scope['runtime']);runtime_parent=tool.admitted_path(scope['paths']['RuntimeParent'])
     require(runtime_parent==paths['runtimeParent'],'ARCHIVE_RUNTIME_PARENT')
     require(runtime.parent==runtime_parent and re.fullmatch('datacube-g11-[0-9a-f]{32}',runtime.name) is not None,'ARCHIVE_RUNTIME_SCOPE')
     # Archive the in-repo owner/scope evidence; never enumerate or copy runtime/home/build.
     row['scopes'].append({'scope':str(scope_file),'owned':str(owned),'runtimeExcluded':str(runtime)})
   cases.append(row)
 save(base/'archive-roots.json',{'schema':'control-archive-roots/v1','roots':sorted(roots),'cases':cases,'uncreatedDeclaredRoots':uncreated,'derivedFromActualSpecsAndScopes':True})

results=[]
try:
 identities();core=load('check-core.py');p2=load('check-p2.py')
 out=evidence/(tag+'-python');out.mkdir();result=core.tool_checks(out);save(out/'results.json',result);require(len(result['cases'])==21,'PYTHON_COUNT');results.append(dict(kind='python',count=21,result=str(out/'results.json')));save(base/'progress.json',results);print('PASS python 21',flush=True)
 for kind,checker,count in [('process',core.process_checks,21),('root-exit',core.root_exit_checks,18)]:
  out=evidence/(tag+'-'+kind);result=checker(repo,out,s['pwsh'],s['python'],s['jdk'],s['cache']);save(out/'results.json',result);require(len(result['cases'])==count,kind+'_COUNT');results.append(dict(kind=kind,count=count,result=str(out/'results.json')));save(base/'progress.json',results);print('PASS '+kind+' '+str(count),flush=True)
 out=evidence/(tag+'-policy');call(p2,['--out',str(out),'--pwsh',s['pwsh'],'--repo',str(repo)]);result=json.loads((out/'results.json').read_text());require(len(result['cases'])+len(result['rolePolicy'])==31,'POLICY_ROLE_IMAGE_COUNT');results.append(dict(kind='policy-role-image',count=31,result=str(out/'results.json')));save(base/'progress.json',results);print('PASS policy-role-image 31',flush=True)
 out=evidence/(tag+'-outer');call(p2,['--out',str(out),'--pwsh',s['pwsh'],'--repo',str(repo),'--check-outer','--python',s['python'],'--jdk',s['jdk'],'--cache',s['cache'],'--runtime-parent',s['runtimeParent']]);result=json.loads((out/'results.json').read_text());require(len(result['cases'])==7,'OUTER_COUNT');results.append(dict(kind='outer',count=7,result=str(out/'results.json')));save(base/'progress.json',results);print('PASS outer 7',flush=True)
 identities();require(sum(row['count'] for row in results)==98,'TOTAL_COUNT');save(base/'controller-result.json',dict(passed=True,count=98,engineering=False,groups=results));archive_roots();print('ALL 98 PASS; no Gradle',flush=True)
except BaseException as error:
 save(base/'controller-result.json',dict(passed=False,engineering=False,groups=results,failure=repr(error)));traceback.print_exc();archive_roots();sys.exit(1)