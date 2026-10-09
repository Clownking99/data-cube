param(
 [Parameter(Mandatory)][string]$Repo,
 [string]$EvidenceRoot,[string]$Jdk,[string]$Cache,[string]$Pwsh,[string]$Python,
 [ValidateSet('targeted','fixture')][string]$Mode='targeted',
 [string]$InputSpec,[string]$ScopePath,[string]$Fixture='normal',
 [int]$DeadlineMs=600000,[int]$SettleMs=5000,[long]$StreamCap=33554432
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'VerificationCore.psm1') -Force
$null=Assert-AdmittedPath $Repo
if(-not $ScopePath) {
 $scope=New-OwnedScope -Repo $Repo -EvidenceRoot $EvidenceRoot -Jdk $Jdk -Cache $Cache -Pwsh $Pwsh -Python $Python -Stage $Mode
 $scopePath=Join-Path $scope.owned 'scope.json'
 $scope|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $scopePath -Encoding utf8NoBOM
 & $scope.tools['run-stage.ps1'].frozen -Repo $Repo -ScopePath $scopePath -Mode $Mode -InputSpec $InputSpec -Fixture $Fixture -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap
 exit $LASTEXITCODE
}
$null=Assert-NoReparse $ScopePath
if(-not $ScopePath.StartsWith((Join-Path $Repo 'docs/superpowers/verification/evidence/g11-'),[StringComparison]::OrdinalIgnoreCase)){throw 'SCOPE_PATH_OUTSIDE_EVIDENCE'}
$scope=Get-Content -LiteralPath $ScopePath -Raw|ConvertFrom-Json -AsHashtable
if($ScopePath -ne (Join-Path $scope.owned 'scope.json')){throw 'SCOPE_FILE_IDENTITY_MISMATCH'}
if($scope.paths.Repo -ne (Assert-AdmittedPath $Repo)){throw 'REPO_SCOPE_MISMATCH'}
$hostScript=$scope.tools['OwnedProcessHost.ps1'].frozen
$failures=[Collections.Generic.List[string]]::new()
function Run-Python([string]$Name,[string[]]$Arguments) {
 $result=Invoke-OwnedProcess -Scope $scope -Name $Name -Exe $scope.paths.Python -Argv (@('-I','-S')+$Arguments) -Cwd $Repo -HostScript $hostScript -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap
 if($result.status -ne 'passed'){$failures.Add($result.primaryFailure)}
 return $result
}
if($Mode -eq 'fixture') {
 if($Fixture -in @('start-failure','assign-failure','unobserved-settlement')){$scope.testFault=$Fixture;$Fixture='normal'}
 $arguments=@($scope.tools['check-core.py'].frozen,'--fixture',$Fixture,'--','space value','中文','literal"quote','')
 $process=Run-Python 'fixture' $arguments
 $process|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $scope.owned 'stage-result.json') -Encoding utf8NoBOM
 exit $(if($process.status -eq 'passed'){0}else{1})
}
$process=$null;$testResults=$null;$before=$null;$after=$null
try {
$null=Assert-NoReparse $InputSpec
$spec=Get-Content -LiteralPath $InputSpec -Raw|ConvertFrom-Json
if($spec.repo -ne $Repo){throw 'INPUT_REPO_MISMATCH'}
foreach($required in @('build.gradle','settings.gradle','README.md','gradlew','gradlew.bat')){if($required -notin $spec.paths){throw "REQUIRED_INPUT_NOT_ADMITTED:$required"}}
if(('gradle.properties' -in $spec.paths) -eq ('gradle.properties' -in $spec.optionalAbsent)){throw 'OPTIONAL_INPUT_CONTRACT_INVALID'}
$frozenSpec=Join-Path $scope.owned 'input-spec.json'
[IO.File]::Copy($InputSpec,$frozenSpec,$false)
$pythonTool=$scope.tools['evidence_tools.py'].frozen
$before=Join-Path $scope.owned 'inputs-before.json';$after=Join-Path $scope.owned 'inputs-after.json'
$pre=Run-Python 'inputs-before' @($pythonTool,'snapshot','--spec',$frozenSpec,'--out',$before,'--owned-root',$scope.owned)
if($pre.status -ne 'passed'){throw 'INPUT_ADMISSION_FAILED'}
$types=Join-Path $scope.owned 'test-types.json'
$typeProcess=Run-Python 'test-types' @($pythonTool,'types','--inputs',$before,'--out',$types,'--owned-root',$scope.owned)
if($typeProcess.status -ne 'passed'){throw 'TEST_INVENTORY_FAILED'}
# Identity of this batch-to-java mapping is frozen as original bytes. A future
# batch change requires explicit review before this launcher may be reused.
$bat=Join-Path $Repo 'gradlew.bat';$batHash=(Get-FileHash -LiteralPath $bat -Algorithm SHA256).Hash
$expectedBat='FEDAD02C18E266EC094995A5751B7FE1EB6E74F66BF75DB64FAE2E50EB22C234'
if($batHash -ne $expectedBat){throw 'GRADLE_BATCH_MAPPING_REVIEW_REQUIRED'}
[IO.File]::Copy($bat,(Join-Path $scope.owned 'gradlew.bat'),$false)
$scope.environment.TEMP=$scope.shortTemp;$scope.environment.TMP=$scope.shortTemp;$scope.environment.G11_HEADLESS='false'
$scope.environment.JAVA_TOOL_OPTIONS='"-Duser.home='+$scope.shortHome+'" "-Djava.io.tmpdir='+$scope.shortTemp+'" -Djava.awt.headless=false'
$argv=@('-Xmx64m','-Xms64m','-Dorg.gradle.appname=gradlew','-jar',(Join-Path $Repo 'gradle/wrapper/gradle-wrapper.jar'),'cleanTest','test','--rerun-tasks','--offline','--no-daemon','--console=plain',('-Dorg.gradle.java.home='+$scope.paths.Jdk),'-I',$scope.tools['isolated.gradle'].frozen)
foreach($filter in @('com.datacube.redis.*','com.datacube.fx.RedisPane*Test','com.datacube.fx.*Close*Test','com.datacube.fx.*Shutdown*Test','com.datacube.fx.*Construction*Test','com.datacube.fx.*Lifecycle*Test','com.datacube.fx.ShutdownQuarantineTest','com.datacube.fx.task.*','com.datacube.fx.WindowShutdownControllerTest','com.datacube.service.ConnectionManager*Test')){$argv+=@('--tests',$filter)}
$process=Invoke-OwnedProcess -Scope $scope -Name 'gradle-targeted' -Exe (Join-Path $scope.paths.Jdk 'bin/java.exe') -Argv $argv -Cwd $Repo -HostScript $hostScript -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap
if($process.status -ne 'passed'){$failures.Add($process.primaryFailure)}
$rawXml=Join-Path $scope.owned 'xml';$null=[IO.Directory]::CreateDirectory($rawXml)
$generatedXml=Assert-NoReparse (Join-Path $scope.environment.G11_BUILD 'main/test-results/test')
if([IO.Directory]::Exists($generatedXml)) {
 foreach($file in [IO.Directory]::EnumerateFiles($generatedXml,'TEST-*.xml',[IO.SearchOption]::TopDirectoryOnly)) {
  $null=Assert-NoReparse $file;[IO.File]::Copy($file,(Join-Path $rawXml ([IO.Path]::GetFileName($file))),$false)
 }
}
$xmlSpec=Join-Path $scope.owned 'xml-spec.json';$xml=Join-Path $scope.owned 'test-results.json'
@{directory=$rawXml;ownedRoot=$scope.owned}|ConvertTo-Json|Set-Content -LiteralPath $xmlSpec -Encoding utf8NoBOM
$xmlProcess=Run-Python 'xml-results' @($pythonTool,'xml','--spec',$xmlSpec,'--out',$xml,'--owned-root',$scope.owned)
$post=Run-Python 'inputs-after' @($pythonTool,'snapshot','--spec',$frozenSpec,'--out',$after,'--owned-root',$scope.owned)
if($post.status -eq 'passed'){$binding=Run-Python 'verify-inputs' @($pythonTool,'verify','--before',$before,'--after',$after,'--out',(Join-Path $scope.owned 'input-verification.json'),'--owned-root',$scope.owned)}
if([IO.File]::Exists($xml)) {
 try {$testResults=Get-Content -LiteralPath $xml -Raw|ConvertFrom-Json} catch {$failures.Add('INVALID_TEST_RESULTS_RECEIPT')}
} else {$failures.Add('MISSING_TEST_RESULTS_RECEIPT')}
if($xmlProcess.status -eq 'passed') {
 if($null -eq $testResults -or $testResults.schema -ne 'test-results/v1'){$failures.Add('INVALID_TEST_RESULTS_SCHEMA')}
 else {
  if($testResults.failures -ne 0 -or $testResults.errors -ne 0){$failures.Add('TEST_FAILURES')}
  foreach($case in $testResults.cases) {
   if($case.status -eq 'skipped' -and -not ($case.class -eq 'com.datacube.redis.RedisLiveIntegrationTest' -and $case.name -eq 'standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()' -and $case.reason -match 'set DATACUBE_REDIS_HOST and DATACUBE_REDIS_PASSWORD to run live Redis smoke test')){$failures.Add('UNAPPROVED_SKIP:'+ $case.class+':'+$case.name)}
  }
 }
}
foreach($tool in $scope.tools.Values){foreach($path in @($tool.source,$tool.frozen)){if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $tool.sha256){$failures.Add('TOOL_IDENTITY_CHANGED')}}}
} catch {$failures.Add('STAGE_FAILURE:'+$_.Exception.Message)} finally {
$result=@{schema='stage-result/v1';status=$(if($failures.Count -eq 0){'passed'}else{'failed'});primaryFailure=$(if($failures.Count){$failures[0]}else{$null});secondaryFailures=@($failures|Select-Object -Skip 1);processReceipt=$process;testResults=$testResults;inputBinding=@{before=$before;after=$after};toolBinding=$scope.tools}
$result|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $scope.owned 'stage-result.json') -Encoding utf8NoBOM
}
exit $(if($failures.Count -eq 0){0}else{1})
