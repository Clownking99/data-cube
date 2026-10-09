param([Parameter(Mandatory)][string]$Spec,[Parameter(Mandatory)][string]$Gate)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'tools/VerificationCore.psm1') -Force
$null=Assert-NoReparse $Spec
if($Spec -ne (Join-Path $PSScriptRoot 'spec.json')){throw 'SPEC_OUTSIDE_FROZEN_ENTRY'}
$s=Get-Content -LiteralPath $Spec -Raw|ConvertFrom-Json -AsHashtable
$gatePath=Assert-AdmittedPath $Gate
$runtimeParent=Assert-AdmittedPath $s.runtimeParent
if([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($gatePath)) -ne $runtimeParent -or [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($gatePath)) -notmatch '^datacube-g11-outer-[a-f0-9]{32}$' -or [IO.Path]::GetFileName($gatePath) -ne 'outer-gate'){throw 'GATE_OUTSIDE_OWNED_RUNTIME'}
$null=Assert-NoReparse $gatePath
$gateWatch=[Diagnostics.Stopwatch]::StartNew()
while(-not [IO.File]::Exists($gatePath)){if($gateWatch.ElapsedMilliseconds -ge 15000){throw 'OUTER_ASSIGNMENT_GATE_DEADLINE'};[Threading.Thread]::Sleep(10)}
if($s.mode -ne 'targeted' -or $s.processDeadlineMs -ne 600000 -or $s.settleMs -ne 5000 -or $s.streamCap -ne 33554432 -or $null -ne $s.fixture -or $null -ne $s.outerFixture){throw 'NARROW_POLICY_MISMATCH'}
$scope=New-OwnedScope -Repo $s.repo -EvidenceRoot $s.stageEvidence -Jdk $s.jdk -Cache $s.cache -Pwsh $s.pwsh -Python $s.python -Stage 'ci-pid-readiness' -RuntimeParent $s.runtimeParent
$scopeFile=Join-Path $scope.owned 'scope.json'
$scope|ConvertTo-Json -Depth 24|Set-Content -LiteralPath $scopeFile -Encoding utf8NoBOM
$scope=Get-Content -LiteralPath $scopeFile -Raw|ConvertFrom-Json -AsHashtable
Import-Module $scope.tools['VerificationCore.psm1'].frozen -Force
$hostScript=$scope.tools['OwnedProcessHost.ps1'].frozen
$failures=[Collections.Generic.List[string]]::new();$process=$null;$testResults=$null;$prePassed=$false;$postDone=$false
$before=Join-Path $scope.owned 'inputs-before.json';$after=Join-Path $scope.owned 'inputs-after.json'
$frozenSpec=Join-Path $scope.owned 'input-spec.json';[IO.File]::Copy($s.inputSpec,$frozenSpec,$false)
[IO.File]::Copy($PSCommandPath,(Join-Path $scope.owned 'stage.ps1'),$false)
function Run-Python([string]$Name,[string[]]$Arguments){
 $p=Invoke-OwnedProcess -Scope $scope -Name $Name -Exe $scope.paths.Python -Role 'python' -Argv (@('-I','-S','-B')+$Arguments) -Cwd $scope.paths.Repo -HostScript $hostScript -DeadlineMs 600000 -SettleMs 5000 -StreamCap 33554432
 if($p.status -ne 'passed'){$failures.Add($p.primaryFailure);throw ('BOUND_COMMAND_FAILED:'+ $Name)}
 return $p
}
function Post-Inputs {
 $null=Run-Python 'inputs-after' @($scope.tools['evidence_tools.py'].frozen,'snapshot','--spec',$frozenSpec,'--out',$after,'--owned-root',$scope.owned)
 $script:postDone=$true
 $null=Run-Python 'verify-inputs' @($scope.tools['evidence_tools.py'].frozen,'verify','--before',$before,'--after',$after,'--out',(Join-Path $scope.owned 'input-verification.json'),'--owned-root',$scope.owned)
}
try {
 $null=Run-Python 'inputs-before' @($scope.tools['evidence_tools.py'].frozen,'snapshot','--spec',$frozenSpec,'--out',$before,'--owned-root',$scope.owned);$prePassed=$true
 $bat=Join-Path $scope.paths.Repo 'gradlew.bat'
 if((Get-FileHash -LiteralPath $bat -Algorithm SHA256).Hash -ne 'FEDAD02C18E266EC094995A5751B7FE1EB6E74F66BF75DB64FAE2E50EB22C234'){throw 'GRADLE_BATCH_MAPPING_REVIEW_REQUIRED'}
 [IO.File]::Copy($bat,(Join-Path $scope.owned 'gradlew.bat'),$false)
 $scope.environment.TEMP=$scope.shortTemp;$scope.environment.TMP=$scope.shortTemp;$scope.environment.G11_HEADLESS='false'
 $scope.environment.JAVA_TOOL_OPTIONS='"-Duser.home='+$scope.shortHome+'" "-Djava.io.tmpdir='+$scope.shortTemp+'" -Djava.awt.headless=false'
 $argv=@('-Xmx64m','-Xms64m','-Dorg.gradle.appname=gradlew','-jar',(Join-Path $scope.paths.Repo 'gradle/wrapper/gradle-wrapper.jar'),'cleanTest','test','--tests','com.datacube.export.PgDumpRunnerReliabilityTest','--rerun-tasks','--offline','--no-daemon','--console=plain',('-Dorg.gradle.java.home='+$scope.paths.Jdk),'-I',$scope.tools['isolated.gradle'].frozen)
 @{argv=$argv;deadlineMs=600000;settleMs=5000;streamCap=33554432;testFilter='com.datacube.export.PgDumpRunnerReliabilityTest'}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $scope.owned 'narrow-command.json') -Encoding utf8NoBOM
 $process=Invoke-OwnedProcess -Scope $scope -Name 'gradle-ci-pid' -Exe (Join-Path $scope.paths.Jdk 'bin/java.exe') -Role 'jdk-java' -Argv $argv -Cwd $scope.paths.Repo -HostScript $hostScript -DeadlineMs 600000 -SettleMs 5000 -StreamCap 33554432
 if($process.status -ne 'passed'){$failures.Add($process.primaryFailure)}
 $xmlDir=Join-Path $scope.owned 'xml';$null=[IO.Directory]::CreateDirectory($xmlDir)
 $generated=Assert-NoReparse (Join-Path $scope.environment.G11_BUILD 'main/test-results/test')
 if([IO.Directory]::Exists($generated)){foreach($file in [IO.Directory]::EnumerateFiles($generated,'TEST-*.xml',[IO.SearchOption]::TopDirectoryOnly)){$null=Assert-NoReparse $file;[IO.File]::Copy($file,(Join-Path $xmlDir ([IO.Path]::GetFileName($file))),$false)}}
 $xmlSpec=Join-Path $scope.owned 'xml-spec.json';$xmlResult=Join-Path $scope.owned 'test-results.json'
 @{directory=$xmlDir;ownedRoot=$scope.owned}|ConvertTo-Json|Set-Content -LiteralPath $xmlSpec -Encoding utf8NoBOM
 $null=Run-Python 'xml-results' @($scope.tools['evidence_tools.py'].frozen,'xml','--spec',$xmlSpec,'--out',$xmlResult,'--owned-root',$scope.owned)
 $testResults=Get-Content -LiteralPath $xmlResult -Raw|ConvertFrom-Json -AsHashtable
 if($testResults.failures -ne 0 -or $testResults.errors -ne 0 -or $testResults.skipped -ne 0 -or $testResults.passed -le 0){throw 'NARROW_TEST_RESULTS_REJECTED'}
 if(@($testResults.cases|Where-Object {$_.class -cne 'com.datacube.export.PgDumpRunnerReliabilityTest'}).Count){throw 'UNEXPECTED_TEST_CLASS'}
 Post-Inputs
 foreach($tool in $scope.tools.Values){foreach($path in @($tool.source,$tool.frozen)){if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $tool.sha256){throw 'TOOL_IDENTITY_CHANGED'}}}
} catch {$failures.Add('STAGE_FAILURE:'+ $_.Exception.Message)} finally {
 if($prePassed -and -not $postDone){try{Post-Inputs}catch{$failures.Add('POST_INPUT_BINDING_FAILED:'+ $_.Exception.Message)}}
 $exeAfter=foreach($path in $scope.executables.Keys){$actual=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash;$length=([IO.FileInfo]$path).Length;$matches=($actual -eq $scope.executables[$path].sha256 -and $length -eq $scope.executables[$path].length);if(-not $matches){$failures.Add('EXECUTABLE_IDENTITY_CHANGED')};@{path=$path;length=$length;sha256=$actual;matches=$matches}}
 $exeAfter|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $scope.owned 'executables-after.json') -Encoding utf8NoBOM
 @{schema='stage-result/v1';mode='ci-pid-readiness';phaseKind='tests';status=$(if($failures.Count){'failed'}else{'passed'});primaryFailure=$(if($failures.Count){$failures[0]}else{$null});secondaryFailures=@($failures|Select-Object -Skip 1);processReceipt=$process;testResults=$testResults;inputBinding=@{before=$before;after=$after};toolBinding=$scope.tools}|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $scope.owned 'stage-result.json') -Encoding utf8NoBOM
}
exit $(if($failures.Count){1}else{0})
