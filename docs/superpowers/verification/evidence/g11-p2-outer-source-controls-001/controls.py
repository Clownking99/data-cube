import importlib.util,json,pathlib,sys,tempfile
repo=pathlib.Path.cwd();directory=repo/'docs/superpowers/verification/evidence/g11-p2-outer-source-controls-001';tools=repo/'scripts/verification'
s=importlib.util.spec_from_file_location('checked_outer',tools/'run-owned.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
rows=[]
for mode in ('normal','start-failure','assign-failure','detached-child','nonzero-child-pipe','overflow','nonzero-child-overflow'):
 out=repo/'docs/superpowers/verification/evidence'/('g11-p2-outer-control-001-'+mode)
 spec={'repo':str(repo),'tools':str(tools),'out':str(out),'stageEvidence':str(out)+'-stage','mode':'fixture','inputSpec':None,'jdk':'D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8','cache':'C:/Users/hetia/.gradle','pwsh':'C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe','python':sys.executable,'runtimeParent':tempfile.gettempdir(),'imageSourceScope':None,'deadlineSeconds':15,'fixture':'normal','processDeadlineMs':7000,'settleMs':1500,'streamCap':33554432,'outerFixture':mode}
 file=directory/(mode+'-spec.json');file.write_text(json.dumps(spec,indent=2),encoding='utf-8');sys.argv=['run-owned.py','--spec',str(file)];code=m.main();result=json.loads((out/'result.json').read_text())
 row={'mode':mode,'wrapperExit':code,'result':result};rows.append(row);(directory/(mode+'-check.json')).write_text(json.dumps(row,indent=2),encoding='utf-8')
 expected={'normal':None,'start-failure':'OUTER_FAILURE:FileNotFoundError','assign-failure':'OUTER_FAILURE:RuntimeError:SYNTHETIC_ASSIGNMENT_FAILURE_BEFORE_GATE','detached-child':'OUTER_DESCENDANT_REQUIRES_TERMINATION','nonzero-child-pipe':'NONZERO_EXIT','overflow':'OUTER_LOG_FAILURE','nonzero-child-overflow':'NONZERO_EXIT'}[mode]
 if expected is None:
  if result['firstFailure'] is not None or code:raise RuntimeError('NORMAL_FAILED')
 elif not str(result['firstFailure']).startswith(expected):raise RuntimeError('WRONG_FIRST:'+mode)
 if not result['settlement']['actualJobQueryObservedEmpty']:raise RuntimeError('JOB_NOT_SETTLED')
 if mode in ('nonzero-child-pipe','nonzero-child-overflow') and result['actualExitCode']!=7:raise RuntimeError('ROOT7_LOST')
 if mode=='overflow' and not any(x['overflow'] and x['length']==1048576 and x['partial'] for x in result['streams']):raise RuntimeError('NO_BOUNDED_OVERFLOW_PREFIX')
(directory/'results.json').write_text(json.dumps({'passed':True,'cases':rows},indent=2),encoding='utf-8')