param([Parameter(Mandatory)][string]$Package)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$c=Get-Content -LiteralPath (Join-Path $Package 'config.json') -Raw|ConvertFrom-Json
Import-Module (Join-Path $Package 'tools/VerificationCore.psm1') -Force
$scope=New-OwnedScope -Repo $c.repo -EvidenceRoot $c.stageEvidence -Jdk $c.jdk -Cache $c.cache -Pwsh $c.pwsh -Python $c.python -Stage targeted -RuntimeParent $c.runtimeParent
$scope|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $scope.owned 'scope.json') -Encoding utf8NoBOM
$failures=[Collections.Generic.List[string]]::new();$results=[Collections.Generic.List[object]]::new();$xml=$null;$before=Join-Path $scope.owned 'inputs-before.json';$after=Join-Path $scope.owned 'inputs-after.json'
$hostScript=$scope.tools['OwnedProcessHost.ps1'].frozen;$pythonTool=$scope.tools['evidence_tools.py'].frozen
[IO.File]::Copy((Join-Path $Package 'inputs.json'),(Join-Path $scope.owned 'input-spec.json'),$false)
function Owned([string]$Name,[string]$Exe,[string[]]$Arguments,[string]$Role=''){
 $result=Invoke-OwnedProcess -Scope $scope -Name $Name -Exe $Exe -Argv $Arguments -Role $Role -Cwd $c.repo -HostScript $hostScript -DeadlineMs 600000 -SettleMs 5000 -StreamCap 33554432
 $results.Add(@{name=$Name;receipt=$result});if($result.status -ne 'passed'){$failures.Add($result.primaryFailure)};return $result
}
function Python([string]$Name,[string[]]$Arguments){return Owned $Name $c.python (@('-I','-S','-B')+$Arguments) 'python'}
$pre=$null
try{
 $pre=Python 'inputs-before' @($pythonTool,'snapshot','--spec',(Join-Path $scope.owned 'input-spec.json'),'--out',$before,'--owned-root',$scope.owned)
 if($pre.status -ne 'passed'){throw 'INPUT_ADMISSION_FAILED'}
 $null=Python 'baseline-verify' @($pythonTool,'verify','--before',(Join-Path $Package 'baseline-inputs.json'),'--after',$before,'--out',(Join-Path $scope.owned 'baseline-verification.json'),'--owned-root',$scope.owned)
 if($failures.Count){throw 'BASELINE_INPUTS_CHANGED'}
 $null=Owned 'java-version' (Join-Path $c.jdk 'bin/java.exe') @('--version') 'jdk-java'
 $scope.environment.TEMP=$scope.shortTemp;$scope.environment.TMP=$scope.shortTemp;$scope.environment.G11_HEADLESS='false'
 $scope.environment.JAVA_TOOL_OPTIONS='"-Duser.home='+$scope.shortHome+'" "-Djava.io.tmpdir='+$scope.shortTemp+'" -Djava.awt.headless=false'
 $bat=Join-Path $c.repo 'gradlew.bat';if((Get-FileHash -LiteralPath $bat -Algorithm SHA256).Hash -ne 'FEDAD02C18E266EC094995A5751B7FE1EB6E74F66BF75DB64FAE2E50EB22C234'){throw 'GRADLE_BATCH_MAPPING_REVIEW_REQUIRED'}
 [IO.File]::Copy($bat,(Join-Path $scope.owned 'gradlew.bat'),$false)
 $arguments=@('-Xmx64m','-Xms64m','-Dorg.gradle.appname=gradlew','-jar',(Join-Path $c.repo 'gradle/wrapper/gradle-wrapper.jar'),'cleanTest','test','--rerun-tasks','--offline','--no-daemon','--console=plain',('-Dorg.gradle.java.home='+$c.jdk),'-I',$scope.tools['isolated.gradle'].frozen,'--tests',$c.filter)
 $null=Owned 'gradle-update-targeted' (Join-Path $c.jdk 'bin/java.exe') $arguments 'jdk-java'
 $raw=Join-Path $scope.owned 'xml';$null=[IO.Directory]::CreateDirectory($raw);$generated=Assert-NoReparse (Join-Path $scope.runtime 'build/main/test-results/test')
 if([IO.Directory]::Exists($generated)){foreach($file in [IO.Directory]::EnumerateFiles($generated,'TEST-*.xml')){$null=Assert-NoReparse $file;[IO.File]::Copy($file,(Join-Path $raw ([IO.Path]::GetFileName($file))),$false)}}
 $xmlSpec=Join-Path $scope.owned 'xml-spec.json';@{directory=$raw;ownedRoot=$scope.owned}|ConvertTo-Json|Set-Content -LiteralPath $xmlSpec -Encoding utf8NoBOM
 $xmlPath=Join-Path $scope.owned 'test-results.json';$null=Python 'xml-results' @($pythonTool,'xml','--spec',$xmlSpec,'--out',$xmlPath,'--owned-root',$scope.owned)
 if([IO.File]::Exists($xmlPath)){$xml=Get-Content -LiteralPath $xmlPath -Raw|ConvertFrom-Json;if($xml.passed -eq 0 -or $xml.failures -or $xml.errors -or $xml.skipped){$failures.Add('UPDATE_TEST_FAILURE_OR_SKIP')}}else{$failures.Add('MISSING_ACTUAL_XML')}
}catch{$failures.Add('STAGE_FAILURE:'+$_.Exception.Message)}finally{
 if($null -ne $pre -and $pre.status -eq 'passed'){
  try{$post=Python 'inputs-after' @($pythonTool,'snapshot','--spec',(Join-Path $scope.owned 'input-spec.json'),'--out',$after,'--owned-root',$scope.owned);if($post.status -eq 'passed'){$null=Python 'verify-inputs' @($pythonTool,'verify','--before',$before,'--after',$after,'--out',(Join-Path $scope.owned 'input-verification.json'),'--owned-root',$scope.owned)}}catch{$failures.Add('POST_INPUT_FAILURE:'+$_.Exception.Message)}
 }
 foreach($tool in $scope.tools.Values){foreach($path in @($tool.source,$tool.frozen)){if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $tool.sha256){$failures.Add('TOOL_IDENTITY_CHANGED')}}}
 @{schema='update-targeted/v1';status=$(if($failures.Count){'failed'}else{'passed'});filter=$c.filter;repeatCount=1;failures=@($failures);processes=@($results);testResults=$xml;scope=(Join-Path $scope.owned 'scope.json')}|ConvertTo-Json -Depth 32|Set-Content -LiteralPath (Join-Path $scope.owned 'stage-result.json') -Encoding utf8NoBOM
}
exit $(if($failures.Count){1}else{0})