"""Deterministic P2 policy/image admission controls, never runs Gradle or image Java."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid


def load(name):
    path=Path(__file__).absolute().with_name(name)
    if any(p.rstrip(' .').casefold()=='.testagent' for p in path.parts):raise RuntimeError('FORBIDDEN_TOOL_PATH')
    spec=importlib.util.spec_from_file_location(name.replace('.','_'),path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


image=load('image_tools.py');evidence=image.evidence;helpers=load('check-core.py')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',required=True);parser.add_argument('--pwsh',required=True);parser.add_argument('--repo',required=True)
    parser.add_argument('--check-outer',action='store_true');parser.add_argument('--python');parser.add_argument('--jdk');parser.add_argument('--cache');parser.add_argument('--runtime-parent')
    args=parser.parse_args();out=evidence.admitted_path(args.out);pwsh=evidence.admitted_path(args.pwsh);repo=evidence.admitted_path(args.repo)
    evidence.no_links(out);evidence.no_links(pwsh);out.mkdir(exist_ok=False);records=[]
    if args.check_outer:
        outer=load('run-owned.py');rows=[]
        for mode in ('normal','start-failure','assign-failure','detached-child','nonzero-child-pipe','overflow','nonzero-child-overflow'):
            child_out=repo/'docs/superpowers/verification/evidence'/(out.name+'-'+mode+'-owner')
            spec={'repo':str(repo),'tools':str(Path(__file__).absolute().parent),'out':str(child_out),'stageEvidence':str(child_out)+'-stage','mode':'fixture','inputSpec':None,'jdk':args.jdk,'cache':args.cache,'pwsh':str(pwsh),'python':args.python,'runtimeParent':args.runtime_parent,'imageSourceScope':None,'deadlineSeconds':15,'fixture':'normal','processDeadlineMs':7000,'settleMs':1500,'streamCap':33554432,'outerFixture':mode}
            file=out/(mode+'-spec.json');file.write_text(json.dumps(spec,indent=2),encoding='utf-8')
            saved=sys.argv
            try:sys.argv=['frozen-run-owned.py','--spec',str(file)];code=outer.main()
            finally:sys.argv=saved
            result=json.loads((child_out/'result.json').read_text())
            row={'mode':mode,'wrapperExit':code,'result':result};rows.append(row);(out/(mode+'-check.json')).write_text(json.dumps(row,indent=2),encoding='utf-8')
            expected={'normal':None,'start-failure':'OUTER_FAILURE:FileNotFoundError','assign-failure':'OUTER_FAILURE:RuntimeError:SYNTHETIC_ASSIGNMENT_FAILURE_BEFORE_GATE','detached-child':'OUTER_DESCENDANT_REQUIRES_TERMINATION','nonzero-child-pipe':'NONZERO_EXIT','overflow':'OUTER_LOG_FAILURE','nonzero-child-overflow':'NONZERO_EXIT'}[mode]
            if expected is None:
                if result['firstFailure'] is not None or code:raise RuntimeError('NORMAL_FAILED')
            elif not str(result['firstFailure']).startswith(expected):raise RuntimeError('WRONG_FIRST:'+mode)
            if not result['settlement']['actualJobQueryObservedEmpty']:raise RuntimeError('JOB_NOT_SETTLED')
            if mode in ('nonzero-child-pipe','nonzero-child-overflow') and result['actualExitCode']!=7:raise RuntimeError('ROOT7_LOST')
            if mode=='overflow' and not any(x['overflow'] and x['length']==1048576 and x['partial'] for x in result['streams']):raise RuntimeError('NO_BOUNDED_OVERFLOW_PREFIX')
        (out/'results.json').write_text(json.dumps({'schema':'p2-outer-controls/v1','passed':True,'cases':rows},indent=2),encoding='utf-8');return 0
    def reject(name,call):
        try:call()
        except evidence.Refusal as error:records.append({'name':name,'actualRefusal':str(error)});return
        raise RuntimeError('MISSING_REFUSAL:'+name)
    required='\n'.join('com/datacube/redis/'+x+'.class' for x in image.REQUIRED)
    types={'schema':'test-types/v1','sourceCount':1,'typeCount':1,'mappings':[{'source':'test/com/datacube/XTest.java','type':'com.datacube.XTest'}]}
    manifest={'files':[{'path':'app/DataCube.cfg'}],'core':[]}
    for name,extra in {'nested-test':'com/datacube/XTest$Nested.class','junit':'org/junit/Test.class','mockito':'org/mockito/Mock.class','testfx':'org/testfx/FxRobot.class','probe':'G10LinkedRedisProbe.class','acceptance':'acceptance/A.class'}.items():
        index=required+'\n'+extra;(out/(name+'-index.txt')).write_text(index)
        reject(name,lambda index=index:image.audit_classes(index,types,manifest,'[Application]'))
    reject('missing-required-class',lambda:image.audit_classes(required.split('\n',1)[1],types,manifest,''))
    for name,cfg in {'cfg-user-home':'java-options=-Duser.home=synthetic','cfg-temp':'java-options=-Djava.io.tmpdir=synthetic','cfg-native-headless':'java-options=-Djava.awt.headless=false'}.items():
        (out/(name+'.cfg')).write_text(cfg);reject(name,lambda cfg=cfg:image.audit_classes(required,types,manifest,cfg))
    for leak in ('home/profile.json','temp/a','acceptance/a','app/G10LinkedRedisProbe.class'):
        reject('manifest:'+leak,lambda leak=leak:image.audit_classes(required,types,{'files':[{'path':leak}],'core':[]},''))
    original=Path.lstat
    def forbidden(*args,**kwargs):raise RuntimeError('UNEXPECTED_LSTAT')
    try:
        Path.lstat=forbidden
        reject('outside-runtime-parent-no-access',lambda:image.image_path(r'C:\foreign\datacube-g11-'+('a'*32)+r'\build\main\jpackage\DataCube',r'C:\foreign\datacube-g11-'+('a'*32),r'C:\admitted'))
    finally:Path.lstat=original
    runtime=out/('datacube-g11-'+uuid.uuid4().hex);fake_image=runtime/'build/main/jpackage/DataCube'
    for relative in ('DataCube.exe','app/DataCube.cfg','runtime/lib/modules','runtime/bin/java.exe'):
        file=fake_image/relative;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(b'owned synthetic bytes')
    baseline=image.inventory(str(fake_image),str(runtime),str(out))
    (out/'image-before.json').write_text(json.dumps(baseline,indent=2))
    (fake_image/'runtime/bin/java.exe').write_bytes(b'owned synthetic modified bytes')
    after=image.inventory(str(fake_image),str(runtime),str(out));(out/'image-after.json').write_text(json.dumps(after,indent=2))
    reject('modified-image-executable-identity',lambda:image.verify_inventory(baseline,after))
    ps=out/'role-policy-controls.ps1'
    ps.write_text(r'''param([string]$Tools,[string]$Out,[string]$Repo)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $Tools 'VerificationCore.psm1') -Force
$scope=@{stage='linked';paths=@{Pwsh='C:\owned\pwsh.exe';Python='C:\owned\python.exe';Jdk='C:\owned\jdk';RuntimeParent='C:\admitted'}}
$rows=[Collections.Generic.List[object]]::new()
foreach($pair in @(@('jdk-jimage','C:\owned\jdk\bin\javac.exe'),@('image-java','C:\foreign\java.exe'),@('unknown','C:\owned\jdk\bin\java.exe'),@('jdk-java','C:\never\.testagent\java.exe'))) {
 try{$null=Assert-OwnedExecutableRole $scope $pair[1] $pair[0];throw 'MISSING_ROLE_REFUSAL'}catch{if($_.Exception.Message -eq 'MISSING_ROLE_REFUSAL'){throw};$rows.Add(@{role=$pair[0];path=$pair[1];actualRefusal=$_.Exception.Message})}
}
$scope.imageBinding=@{schema='owned-image-binding/v1';sourceRuntime=('C:\foreign\datacube-g11-'+('a'*32));image=('C:\foreign\datacube-g11-'+('a'*32)+'\build\main\jpackage\DataCube')}
try{$null=Assert-OwnedExecutableRole $scope 'C:\foreign\java.exe' 'image-java';throw 'MISSING_PARENT_REFUSAL'}catch{if($_.Exception.Message -eq 'MISSING_PARENT_REFUSAL'){throw};$rows.Add(@{actualRefusal=$_.Exception.Message;case='foreign-runtime-parent'})}
$fakeJdk=Join-Path $Out 'owned-fake-jdk';$null=[IO.Directory]::CreateDirectory((Join-Path $fakeJdk 'bin'))
$fakeJava=Join-Path $fakeJdk 'bin/java.exe';[IO.File]::WriteAllText($fakeJava,'owned fake executable data only')
$fakeScope=@{schema='owned-scope/v1';stage='fixture';owned=$Out;paths=@{Repo=$repo;Jdk=$fakeJdk;Cache=$Out;Pwsh=(Get-Process -Id $PID).Path;Python=(Get-Process -Id $PID).Path;RuntimeParent=$Out};tools=@{'OwnedProcessHost.ps1'=@{frozen=(Join-Path $Out 'missing-owned-host.ps1')}};executables=@{}}
$fakeScope.executables[$fakeJava]=@{sha256=('0'*64);length=1}
try{$null=Invoke-OwnedProcess -Scope $fakeScope -Name 'hash-refusal' -Exe $fakeJava -Role 'jdk-java' -Argv @() -Cwd $repo -HostScript $fakeScope.tools['OwnedProcessHost.ps1'].frozen;throw 'MISSING_HASH_REFUSAL'}catch{if($_.Exception.Message -ne 'EXECUTABLE_IDENTITY_CHANGED'){throw};$rows.Add(@{case='owned-exe-hash-change';actualRefusal=$_.Exception.Message})}
$policy=Get-Content -LiteralPath (Join-Path $Tools 'stage-policy.json') -Raw|ConvertFrom-Json -AsHashtable
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $Tools 'run-stage.ps1'),[ref]$tokens,[ref]$errors)
if($errors){throw 'BAD_STAGE_SOURCE'}
$definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Apply-TestPolicy'},$true)
 . ([scriptblock]::Create($definition.Extent.Text))
foreach($rule in $policy.liveSkips){foreach($Mode in @('targeted','full','buildsrc')) {
 $failures=[Collections.Generic.List[string]]::new()
 $results=@{schema='test-results/v1';failures=0;errors=0;passed=1;cases=@(@{status='skipped';class=$rule.class;name=$rule.name;reason=$rule.reason})}
 Apply-TestPolicy $results $Mode
 $expected=$Mode -in $rule.stages
 if(($failures.Count -eq 0) -ne $expected){throw 'STAGE_SKIP_POLICY_MISMATCH'}
 $rows.Add(@{stage=$Mode;class=$rule.class;name=$rule.name;allowed=$expected;actualFailures=@($failures)})
}}
$rows|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $Out 'role-policy-results.json') -Encoding utf8NoBOM
'''.replace(chr(92)+chr(92),chr(92)),encoding='utf-8')
    owner=helpers.OuterOwner();process=None;began=time.monotonic();deadline=began+30
    environment={k:__import__('os').environ[k] for k in ('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT') if k in __import__('os').environ}
    environment.update(HOME=str(out),USERPROFILE=str(out),TEMP=str(out),TMP=str(out))
    argv=[str(pwsh),'-NoProfile','-NonInteractive','-File',str(ps),'-Tools',str(Path(__file__).absolute().parent),'-Out',str(out),'-Repo',str(repo)]
    try:
        with (out/'role-policy-stdout.log').open('xb') as stdout,(out/'role-policy-stderr.log').open('xb') as stderr:
            process=subprocess.Popen(argv,env=environment,stdout=stdout,stderr=stderr);owner.assign(process)
            try:code=process.wait(timeout=25)
            except subprocess.TimeoutExpired:owner.terminate();code=process.wait(timeout=4)
    finally:settlement=owner.close(deadline=deadline)
    (out/'role-policy-command.json').write_text(json.dumps({'argv':argv,'actualExitCode':code,'settlement':settlement},indent=2))
    if code:raise RuntimeError('ROLE_POLICY_CONTROLS_FAILED')
    (out/'results.json').write_text(json.dumps({'schema':'p2-controls/v1','passed':True,'cases':records,'rolePolicy':json.loads((out/'role-policy-results.json').read_text(encoding='utf-8-sig'))},indent=2))
    return 0


if __name__=='__main__':sys.exit(main())
