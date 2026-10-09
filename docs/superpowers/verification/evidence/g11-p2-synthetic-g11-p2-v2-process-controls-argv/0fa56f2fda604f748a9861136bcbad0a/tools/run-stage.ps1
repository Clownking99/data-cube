param(
 [Parameter(Mandatory)][string]$Repo,
 [string]$EvidenceRoot,[string]$Jdk,[string]$Cache,[string]$Pwsh,[string]$Python,
 [ValidateSet('targeted','full','buildsrc','image','linked','fixture')][string]$Mode='targeted',
 [string]$InputSpec,[string]$ScopePath,[string]$Fixture='normal',
 [int]$DeadlineMs=0,[int]$SettleMs=5000,[long]$StreamCap=33554432,
 [string]$ImageSourceScope,[string]$RuntimeParent=''
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'VerificationCore.psm1') -Force
$null=Assert-AdmittedPath $Repo
if(-not $ScopePath) {
 $scope=New-OwnedScope -Repo $Repo -EvidenceRoot $EvidenceRoot -Jdk $Jdk -Cache $Cache -Pwsh $Pwsh -Python $Python -Stage $Mode -RuntimeParent $RuntimeParent
 $scopePath=Join-Path $scope.owned 'scope.json'
 $scope|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $scopePath -Encoding utf8NoBOM
 & $scope.tools['run-stage.ps1'].frozen -Repo $Repo -ScopePath $scopePath -Mode $Mode -InputSpec $InputSpec -Fixture $Fixture -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap -ImageSourceScope $ImageSourceScope
 exit $LASTEXITCODE
}
$null=Assert-NoReparse $ScopePath
if(-not $ScopePath.StartsWith((Join-Path $Repo 'docs/superpowers/verification/evidence/g11-'),[StringComparison]::OrdinalIgnoreCase)){throw 'SCOPE_PATH_OUTSIDE_EVIDENCE'}
$scope=Get-Content -LiteralPath $ScopePath -Raw|ConvertFrom-Json -AsHashtable
if($ScopePath -ne (Join-Path $scope.owned 'scope.json')){throw 'SCOPE_FILE_IDENTITY_MISMATCH'}
if($scope.paths.Repo -ne (Assert-AdmittedPath $Repo)){throw 'REPO_SCOPE_MISMATCH'}
$hostScript=$scope.tools['OwnedProcessHost.ps1'].frozen
$failures=[Collections.Generic.List[string]]::new()
$policy=Get-Content -LiteralPath $scope.tools['stage-policy.json'].frozen -Raw|ConvertFrom-Json -AsHashtable
if($policy.schema -ne 'verification-stages/v1'){throw 'INVALID_STAGE_POLICY'}
if(-not $DeadlineMs){$DeadlineMs=$(if($Mode -eq 'fixture'){600000}else{$policy.stages[$Mode].deadlineMs})}
function Run-Python([string]$Name,[string[]]$Arguments) {
 $tokenSource=[Threading.CancellationTokenSource]::new()
 try {
  if($Mode -eq 'fixture' -and $Fixture -eq 'cancel'){$tokenSource.CancelAfter(3000)}
  $result=Invoke-OwnedProcess -Scope $scope -Name $Name -Exe $scope.paths.Python -Argv (@('-I','-S','-B')+$Arguments) -Cwd $Repo -HostScript $hostScript -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap -CancellationToken $tokenSource.Token
 } finally {$tokenSource.Dispose()}
 if($result.status -ne 'passed'){$failures.Add($result.primaryFailure)}
 return $result
}
function Apply-TestPolicy($Results,[string]$PolicyStage=$Mode) {
 if($null -eq $Results -or $Results.schema -ne 'test-results/v1'){$failures.Add('INVALID_TEST_RESULTS_SCHEMA');return}
 if($Results.failures -ne 0 -or $Results.errors -ne 0){$failures.Add('TEST_FAILURES')}
 if($Results.passed -eq 0){$failures.Add('NO_PASSED_TEST_CASES')}
 foreach($case in $Results.cases) {
  if($PolicyStage -eq 'fixture'){$PolicyStage='targeted'}
  $allowed=@($policy.liveSkips|Where-Object {$case.class -ceq $_.class -and $case.name -ceq $_.name -and $PolicyStage -in $_.stages -and $case.reason -cmatch [Regex]::Escape($_.reason)})
  if($case.status -eq 'skipped' -and $allowed.Count -ne 1){$failures.Add('UNAPPROVED_SKIP:'+ $case.class+':'+$case.name)}
 }
}
if($Mode -eq 'fixture') {
 if($Fixture -in @('start-failure','assign-failure','unobserved-settlement')){$scope.testFault=$Fixture;$Fixture='normal'}
 $fixtureMode=$(if($Fixture -eq 'cancel'){'continuous'}elseif($Fixture -in @('compile-zero-xml','compile-stat-failure')){'nonzero'}elseif($Fixture -in @('skip-live','skip-native')){'normal'}else{$Fixture})
 $arguments=@($scope.tools['check-core.py'].frozen,'--fixture',$fixtureMode,'--','space value','中文','literal"quote','')
 try {
  if($Fixture -eq 'tool-change'){[IO.File]::AppendAllText($scope.tools['check-core.py'].frozen,"`n# synthetic mutation`n")}
  $process=Run-Python 'fixture' $arguments
  if($Fixture -in @('compile-zero-xml','compile-stat-failure','skip-live','skip-native')) {
   $compileReceipt=$process;$empty=Join-Path $scope.owned 'xml';$null=[IO.Directory]::CreateDirectory($empty)
   $xmlSpec=Join-Path $scope.owned 'xml-spec.json';$xmlResult=Join-Path $scope.owned 'test-results.json'
   @{directory=$empty;ownedRoot=$scope.owned}|ConvertTo-Json|Set-Content -LiteralPath $xmlSpec -Encoding utf8NoBOM
   if($Fixture -in @('skip-live','skip-native')) {
    $class=$(if($Fixture -eq 'skip-live'){'com.datacube.redis.RedisLiveIntegrationTest'}else{'com.datacube.fx.SyntheticNativeTest'})
    $name=$(if($Fixture -eq 'skip-live'){'standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()'}else{'toolkitSkipped()'})
    $reason=$(if($Fixture -eq 'skip-live'){'set DATACUBE_REDIS_HOST and DATACUBE_REDIS_PASSWORD to run live Redis smoke test'}else{'synthetic native FX skip'})
    [IO.File]::WriteAllText((Join-Path $empty 'TEST-policy.xml'),'<testsuite name="policy" tests="2" failures="0" errors="0" skipped="1"><testcase classname="C" name="passed"/><testcase classname="'+$class+'" name="'+$name+'"><skipped message="'+$reason+'"/></testcase></testsuite>')
   }
   if($Fixture -eq 'compile-stat-failure'){$scope.testFault='start-failure'}
   $statisticsReceipt=Run-Python 'statistics' @($scope.tools['evidence_tools.py'].frozen,'xml','--spec',$xmlSpec,'--out',$xmlResult,'--owned-root',$scope.owned)
   $statisticsResult=$null
   if([IO.File]::Exists($xmlResult)){try{$statisticsResult=Get-Content -LiteralPath $xmlResult -Raw|ConvertFrom-Json}catch{$failures.Add('INVALID_TEST_RESULTS_RECEIPT')}}else{$failures.Add('MISSING_TEST_RESULTS_RECEIPT')}
   if($statisticsReceipt.status -eq 'passed'){Apply-TestPolicy $statisticsResult}
   $process=@{schema='stage-result/v1';status=$(if($failures.Count){'failed'}else{'passed'});primaryFailure=$(if($failures.Count){$failures[0]}else{$null});secondaryFailures=@($failures|Select-Object -Skip 1);processReceipt=$compileReceipt;statisticsReceipt=$statisticsReceipt;testResults=$statisticsResult;ownedSettlement=$compileReceipt.ownedSettlement}
  }
 } catch {$process=@{schema='admission-failure/v1';status='failed';primaryFailure=$_.Exception.Message;started=$false;ownedSettlement='complete';cancelled=$false}}
 $process|ConvertTo-Json -Depth 20|Set-Content -LiteralPath (Join-Path $scope.owned 'stage-result.json') -Encoding utf8NoBOM
 exit $(if($process.status -eq 'passed'){0}else{1})
}
$process=$null;$testResults=$null;$before=$null;$after=$null;$artifact=$null;$linkedProcesses=@{};$pre=$null;$frozenSpec=$null;$pythonTool=$null;$postDone=$false
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
$pythonVersion=Run-Python 'python-version' @('--version')
$javaVersion=Invoke-OwnedProcess -Scope $scope -Name 'java-version' -Exe (Join-Path $scope.paths.Jdk 'bin/java.exe') -Argv @('--version') -Cwd $Repo -HostScript $hostScript -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap
if($javaVersion.status -ne 'passed'){$failures.Add($javaVersion.primaryFailure)}
if($pythonVersion.status -ne 'passed' -or $javaVersion.status -ne 'passed'){throw 'RUNTIME_VERSION_RECEIPT_FAILED'}
foreach($toolName in @('javac','jimage')) {
 $version=Invoke-OwnedProcess -Scope $scope -Name ($toolName+'-version') -Exe (Join-Path $scope.paths.Jdk ('bin/'+$toolName+'.exe')) -Role ('jdk-'+$toolName) -Argv @('--version') -Cwd $Repo -HostScript $hostScript -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap
 if($version.status -ne 'passed'){$failures.Add($version.primaryFailure);throw 'RUNTIME_VERSION_RECEIPT_FAILED'}
}
# Identity of this batch-to-java mapping is frozen as original bytes. A future
# batch change requires explicit review before this launcher may be reused.
$bat=Join-Path $Repo 'gradlew.bat';$batHash=(Get-FileHash -LiteralPath $bat -Algorithm SHA256).Hash
$expectedBat='FEDAD02C18E266EC094995A5751B7FE1EB6E74F66BF75DB64FAE2E50EB22C234'
if($batHash -ne $expectedBat){throw 'GRADLE_BATCH_MAPPING_REVIEW_REQUIRED'}
[IO.File]::Copy($bat,(Join-Path $scope.owned 'gradlew.bat'),$false)
$scope.environment.TEMP=$scope.shortTemp;$scope.environment.TMP=$scope.shortTemp;$scope.environment.G11_HEADLESS='false'
$scope.environment.JAVA_TOOL_OPTIONS='"-Duser.home='+$scope.shortHome+'" "-Djava.io.tmpdir='+$scope.shortTemp+'" -Djava.awt.headless=false'
if($Mode -eq 'linked') {
 $imageBinding=Bind-OwnedImage $scope $ImageSourceScope
 $scope|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $scope.owned 'scope-bound.json') -Encoding utf8NoBOM
 $sourceOwned=[IO.Path]::GetDirectoryName($ImageSourceScope)
 foreach($name in @('inputs-before.json','inputs-after.json','image-manifest.json')){
  $source=Assert-NoReparse (Join-Path $sourceOwned $name);[IO.File]::Copy($source,(Join-Path $scope.owned ('source-image-'+$name)),$false)
 }
 foreach($name in @('before','after')) {
  $cross=Run-Python ('source-image-inputs-'+$name) @($pythonTool,'verify','--before',(Join-Path $scope.owned ('source-image-inputs-'+$name+'.json')),'--after',$before,'--out',(Join-Path $scope.owned ('source-image-inputs-'+$name+'-verified.json')),'--owned-root',$scope.owned)
  if($cross.status -ne 'passed'){throw 'IMAGE_PRODUCT_TOOL_INPUT_MISMATCH'}
 }
 $image=$imageBinding.image;$imageTool=$scope.tools['image_tools.py'].frozen
 $imageSpec=Join-Path $scope.owned 'image-spec.json'
 $indexPath=Join-Path $scope.owned 'processes/module-index/stdout.bin'
 @{image=$image;runtime=$imageBinding.sourceRuntime;runtimeParent=$scope.paths.RuntimeParent;baseline=(Join-Path $scope.owned 'source-image-image-manifest.json');types=$types;index=$indexPath}|ConvertTo-Json|Set-Content -LiteralPath $imageSpec -Encoding utf8NoBOM
 $imagePre=Run-Python 'image-before' @($imageTool,'verify','--spec',$imageSpec,'--out',(Join-Path $scope.owned 'image-before.json'),'--owned-root',$scope.owned)
 if($imagePre.status -ne 'passed'){throw 'IMAGE_IDENTITY_CHANGED'}
 function Run-Linked([string]$Name,[string]$Exe,[string]$Role,[string[]]$Arguments) {
  $result=Invoke-OwnedProcess -Scope $scope -Name $Name -Exe $Exe -Role $Role -Argv $Arguments -Cwd $scope.owned -HostScript $hostScript -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap
  $linkedProcesses[$Name]=$result;if($result.status -ne 'passed'){$failures.Add($result.primaryFailure);throw "LINKED_PROCESS_FAILED:$Name"}
 }
 Run-Linked 'module-index' (Join-Path $scope.paths.Jdk 'bin/jimage.exe') 'jdk-jimage' @('list',(Join-Path $image 'runtime/lib/modules'))
 $audit=Run-Python 'image-audit' @($imageTool,'audit','--spec',$imageSpec,'--out',(Join-Path $scope.owned 'image-audit.json'),'--owned-root',$scope.owned)
 if($audit.status -ne 'passed'){throw 'IMAGE_AUDIT_FAILED'}
 $classes=Join-Path $scope.owned 'compiled-probes';$null=[IO.Directory]::CreateDirectory($classes)
 foreach($origin in $policy.probeOrigins){if($scope.tools[$origin.file].sha256 -ne $origin.sha256){throw 'PROBE_SOURCE_IDENTITY_CHANGED'}}
 $exports=@('--add-exports=com.datacube/com.datacube.redis=ALL-UNNAMED','--add-exports=com.datacube/com.datacube.migration=ALL-UNNAMED')
 Run-Linked 'probe-compile' (Join-Path $scope.paths.Jdk 'bin/javac.exe') 'jdk-javac' (@('--system',(Join-Path $image 'runtime'),'--add-modules','com.datacube','-encoding','UTF-8')+$exports+@('-d',$classes,$scope.tools['probes/G10LinkedRedisProbe.java'].frozen,$scope.tools['probes/MigrationRuntimeDriverProbe.java'].frozen))
 $runtimeArgs=@('--add-modules','com.datacube')+$exports+@('--add-opens=com.datacube/com.datacube.redis=ALL-UNNAMED','--enable-native-access=com.datacube','-cp',$classes)
 $imageJava=Join-Path $image 'runtime/bin/java.exe'
 Run-Linked 'driver-discovery' $imageJava 'image-java' ($runtimeArgs+@('MigrationRuntimeDriverProbe'))
 Run-Linked 'redis-linked' $imageJava 'image-java' ($runtimeArgs+@('-Xmx64m','-Xss256k','G10LinkedRedisProbe'))
 $driver=Get-Content -LiteralPath (Join-Path $scope.owned 'processes/driver-discovery/stdout.bin') -Raw
 $redis=Get-Content -LiteralPath (Join-Path $scope.owned 'processes/redis-linked/stdout.bin') -Raw
 if($driver -notmatch 'connectCalls=0' -or $redis -notmatch 'G10_LINKED_REDIS=true' -or $redis -notmatch 'socketsSettled=true; realServices=0'){throw 'LINKED_PROBE_ASSERTIONS_FAILED'}
 $imagePost=Run-Python 'image-after' @($imageTool,'verify','--spec',$imageSpec,'--out',(Join-Path $scope.owned 'image-after.json'),'--owned-root',$scope.owned)
 $artifact=@{kind='linked';image=$image;binding=$imageBinding;imageAudit=(Join-Path $scope.owned 'image-audit.json');driverOutput=$driver;redisOutput=$redis;realServices=0}
} else {
$argv=@('-Xmx64m','-Xms64m','-Dorg.gradle.appname=gradlew','-jar',(Join-Path $Repo 'gradle/wrapper/gradle-wrapper.jar'))+@($policy.stages[$Mode].tasks)+@('--rerun-tasks','--offline','--no-daemon','--console=plain',('-Dorg.gradle.java.home='+$scope.paths.Jdk),'-I',$scope.tools['isolated.gradle'].frozen)
if($Mode -eq 'targeted'){foreach($filter in $policy.targetedFilters){$argv+=@('--tests',$filter)}}
$process=Invoke-OwnedProcess -Scope $scope -Name ('gradle-'+$Mode) -Exe (Join-Path $scope.paths.Jdk 'bin/java.exe') -Role 'jdk-java' -Argv $argv -Cwd $Repo -HostScript $hostScript -DeadlineMs $DeadlineMs -SettleMs $SettleMs -StreamCap $StreamCap
if($process.status -ne 'passed'){$failures.Add($process.primaryFailure)}
if($Mode -eq 'image') {
 $image=Join-Path $scope.runtime 'build/main/jpackage/DataCube';$imageSpec=Join-Path $scope.owned 'image-spec.json'
 @{image=$image;runtime=$scope.runtime;runtimeParent=$scope.paths.RuntimeParent;baseline=$null;types=$null;index=$null}|ConvertTo-Json|Set-Content -LiteralPath $imageSpec -Encoding utf8NoBOM
 $imageProcess=Run-Python 'image-inventory' @($scope.tools['image_tools.py'].frozen,'inventory','--spec',$imageSpec,'--out',(Join-Path $scope.owned 'image-manifest.json'),'--owned-root',$scope.owned)
 if($imageProcess.status -eq 'passed'){[IO.File]::Copy((Join-Path $image 'app/DataCube.cfg'),(Join-Path $scope.owned 'DataCube.cfg'),$false)}
 $artifact=@{kind='image';image=$image;manifest=(Join-Path $scope.owned 'image-manifest.json')}
} else {
$rawXml=Join-Path $scope.owned 'xml';$null=[IO.Directory]::CreateDirectory($rawXml)
$generatedXml=Assert-NoReparse (Join-Path $scope.environment.G11_BUILD $policy.stages[$Mode].xml)
if([IO.Directory]::Exists($generatedXml)) {
 foreach($file in [IO.Directory]::EnumerateFiles($generatedXml,'TEST-*.xml',[IO.SearchOption]::TopDirectoryOnly)) {
  $null=Assert-NoReparse $file;[IO.File]::Copy($file,(Join-Path $rawXml ([IO.Path]::GetFileName($file))),$false)
 }
}
$xmlSpec=Join-Path $scope.owned 'xml-spec.json';$xml=Join-Path $scope.owned 'test-results.json'
@{directory=$rawXml;ownedRoot=$scope.owned}|ConvertTo-Json|Set-Content -LiteralPath $xmlSpec -Encoding utf8NoBOM
$xmlProcess=Run-Python 'xml-results' @($pythonTool,'xml','--spec',$xmlSpec,'--out',$xml,'--owned-root',$scope.owned)
if([IO.File]::Exists($xml)) {try{$testResults=Get-Content -LiteralPath $xml -Raw|ConvertFrom-Json}catch{$failures.Add('INVALID_TEST_RESULTS_RECEIPT')}}else{$failures.Add('MISSING_TEST_RESULTS_RECEIPT')}
if($xmlProcess.status -eq 'passed'){Apply-TestPolicy $testResults}
}
}
$post=Run-Python 'inputs-after' @($pythonTool,'snapshot','--spec',$frozenSpec,'--out',$after,'--owned-root',$scope.owned)
$postDone=$true
if($post.status -eq 'passed'){$binding=Run-Python 'verify-inputs' @($pythonTool,'verify','--before',$before,'--after',$after,'--out',(Join-Path $scope.owned 'input-verification.json'),'--owned-root',$scope.owned)}
foreach($tool in $scope.tools.Values){foreach($path in @($tool.source,$tool.frozen)){if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $tool.sha256){$failures.Add('TOOL_IDENTITY_CHANGED')}}}
} catch {$failures.Add('STAGE_FAILURE:'+$_.Exception.Message)} finally {
if(-not $postDone -and $null -ne $pre -and $pre.status -eq 'passed') {
 try {
  $post=Run-Python 'inputs-after' @($pythonTool,'snapshot','--spec',$frozenSpec,'--out',$after,'--owned-root',$scope.owned)
  if($post.status -eq 'passed'){$binding=Run-Python 'verify-inputs' @($pythonTool,'verify','--before',$before,'--after',$after,'--out',(Join-Path $scope.owned 'input-verification.json'),'--owned-root',$scope.owned)}
 } catch {$failures.Add('POST_INPUT_BINDING_FAILED:'+$_.Exception.Message)}
}
$result=@{schema='stage-result/v1';mode=$Mode;phaseKind=$(if($Mode -in @('image','linked')){$Mode}else{'tests'});status=$(if($failures.Count -eq 0){'passed'}else{'failed'});primaryFailure=$(if($failures.Count){$failures[0]}else{$null});secondaryFailures=@($failures|Select-Object -Skip 1);processReceipt=$process;linkedProcesses=$linkedProcesses;testResults=$testResults;artifact=$artifact;inputBinding=@{before=$before;after=$after};toolBinding=$scope.tools}
$result|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $scope.owned 'stage-result.json') -Encoding utf8NoBOM
}
exit $(if($failures.Count -eq 0){0}else{1})
