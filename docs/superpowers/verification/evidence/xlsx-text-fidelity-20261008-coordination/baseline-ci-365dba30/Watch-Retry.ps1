$ErrorActionPreference='Stop'
$dir=$PSScriptRoot
$expected='365dba304a5e198e72ae7633d88a1b5258d058fa'
$command=Get-Content -LiteralPath (Join-Path $dir 'local-helper-regression/command.json') -Raw|ConvertFrom-Json
$exitResult=Get-Content -LiteralPath (Join-Path $dir 'local-helper-regression/exit.json') -Raw|ConvertFrom-Json
$xmlFiles=@(Get-ChildItem -LiteralPath (Join-Path $dir 'local-helper-regression/build-test-results-test') -File -Filter '*.xml')
if($command.head -ne $expected -or $exitResult.exitCode -ne 0 -or $xmlFiles.Count -ne 1){throw 'Local diagnostic identity/exit/count mismatch'}
[xml]$xml=Get-Content -LiteralPath $xmlFiles[0].FullName -Raw
$suite=$xml.testsuite
$log=Get-Content -LiteralPath (Join-Path $dir 'local-helper-regression/gradle.log') -Raw
if($suite.tests -ne '11' -or $suite.failures -ne '0' -or $suite.errors -ne '0' -or $suite.skipped -ne '0' -or $log -notmatch '(?m)^> Task :test\s*$'){throw 'Local diagnostic did not actually pass'}
if([datetime]$suite.timestamp -lt [datetime]$command.utc){throw 'Stale diagnostic XML'}
@{utc=[datetime]::UtcNow.ToString('o');headSha=$expected;tests=11;passed=11;failed=0;errors=0;skipped=0;actualTestTask=$true;noLiveEnvironment=$command.noLiveEnvironment;rootCause='unknown; local success does not explain the initial CI timeout';xmlSha256=(Get-FileHash -LiteralPath $xmlFiles[0].FullName).Hash;cases=@($suite.testcase|Select-Object name,time)}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $dir 'local-helper-regression-audit.json') -Encoding utf8
$oldHttp=$env:HTTP_PROXY;$oldHttps=$env:HTTPS_PROXY
$lastState='';$queryFailures=0
try{
 $env:HTTP_PROXY='http://127.0.0.1:7897';$env:HTTPS_PROXY=$env:HTTP_PROXY
 for($i=0;$i -lt 100;$i++){
  $raw=& 'C:/Program Files/GitHub CLI/gh.exe' run view 37714054981 --repo Clownking99/data-cube --json headSha,status,conclusion,jobs,url,attempt 2>&1
  $queryExit=$LASTEXITCODE
  if($queryExit -ne 0){
   $queryFailures++
   @{utc=[datetime]::UtcNow.ToString('o');queryExit=$queryExit;output=($raw -join "`n")}|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $dir 'retry-query-errors.jsonl') -Encoding utf8
   if($queryFailures -ge 3){throw 'Three consecutive CI read failures'}
   Start-Sleep -Seconds 15
   continue
  }
  $queryFailures=0
  $run=($raw -join "`n")|ConvertFrom-Json
  if($run.headSha -ne $expected -or $run.attempt -gt 2){throw 'Unexpected CI SHA or extra attempt'}
  @{utc=[datetime]::UtcNow.ToString('o');snapshot=$run}|ConvertTo-Json -Depth 12 -Compress|Add-Content -LiteralPath (Join-Path $dir 'retry-queries.jsonl') -Encoding utf8
  $windows=@($run.jobs|Where-Object name -eq 'Test (windows-latest)')
  $state=(@($run.attempt,$run.status,$run.conclusion)+@($windows.steps|Where-Object name -in @('Unit tests','Windows linked image')|ForEach-Object { $_.name+':'+$_.status+':'+$_.conclusion })) -join ' | '
  if($state -ne $lastState){Write-Output $state;$lastState=$state}
  if($run.attempt -eq 2 -and $run.status -eq 'completed'){
   ($raw -join "`n")|Set-Content -LiteralPath (Join-Path $dir 'workflow-retry-complete.json') -Encoding utf8
   if($run.conclusion -ne 'success'){throw 'Single failed-job rerun did not pass; no further reruns'}
   if($run.jobs.Count -ne 4 -or @($run.jobs|Where-Object conclusion -ne 'success').Count -ne 0){throw 'Required job successes missing'}
   Copy-Item -LiteralPath (Join-Path $dir 'workflow-retry-complete.json') -Destination (Join-Path $dir 'workflow-complete.json')
   Write-Output 'Exact-SHA retry complete: all four required jobs successful; first failure preserved.'
   exit 0
  }
  Start-Sleep -Seconds 15
 }
 throw 'CI observation window elapsed without completion'
}finally{$env:HTTP_PROXY=$oldHttp;$env:HTTPS_PROXY=$oldHttps}
