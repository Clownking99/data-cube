param([Parameter(Mandatory)][string]$TargetedRun,[Parameter(Mandatory)][int]$ExpectedTargeted,[Parameter(Mandatory)][int]$ExpectedFull,[Parameter(Mandatory)][string]$FullRun,[Parameter(Mandatory)][string]$BuildSrcRun,[Parameter(Mandatory)][string]$ImageRun)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$worker=Join-Path $PSScriptRoot '../sql-inflight-20261007-worker'
$target=Join-Path $PSScriptRoot 'branch-independent-tests.json'
if(Test-Path -LiteralPath $target){throw 'Audit output must be new'}
$freeze=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'reviewed-source.json') -Raw|ConvertFrom-Json
foreach($entry in $freeze.files){
 $path=Join-Path $root $entry.path
 if((Get-Item -LiteralPath $path).Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $path).Hash -ne $entry.sha256){throw ('Reviewed source changed: '+$entry.path)}
}
$results=@()
$runs=@(@{name=$TargetedRun;task=':test';folder='build-test-results-test';expected=$ExpectedTargeted;skipped=0},
 @{name=$FullRun;task=':test';folder='build-test-results-test';expected=$ExpectedFull;skipped=3},
 @{name=$BuildSrcRun;task=':buildSrc:test';folder='buildSrc-build-test-results-test';expected=8;skipped=0})
foreach($item in $runs){
 if($item.name -notmatch '^\d{3}-[a-z0-9-]+$'){throw 'Unexpected run name'}
 $dir=Join-Path $worker $item.name
 $command=Get-Content -LiteralPath (Join-Path $dir 'command.json') -Raw|ConvertFrom-Json
 $exit=Get-Content -LiteralPath (Join-Path $dir 'exit.json') -Raw|ConvertFrom-Json
 $log=Get-Content -LiteralPath (Join-Path $dir 'gradle.log') -Raw
 if($exit.exitCode -ne 0 -or $log -notmatch ('(?m)^> Task '+[regex]::Escape($item.task)+'\r?$')){throw ('Task not passed/executed '+$item.name)}
 $started=[DateTimeOffset]::Parse($command.utc).UtcDateTime
 $result=[ordered]@{name=$item.name;task=$item.task;head=$command.head;tests=0;failures=0;errors=0;skipped=0;suites=0;skips=@();freshXml=$true}
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $dir $item.folder) -Filter 'TEST-*.xml' -File){
  if($file.LastWriteTimeUtc -lt $started){throw ('Stale XML '+$file.Name)}
  [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
  $result.suites++
  foreach($key in @('tests','failures','errors','skipped')){$result[$key]+=[int]$xml.testsuite.$key}
  foreach($case in $xml.SelectNodes('/testsuite/testcase[skipped]')){$result.skips+=@{class=$case.classname;name=$case.name;reason=$case.skipped.message}}
 }
 if($result.tests -ne $item.expected -or $result.skipped -ne $item.skipped -or $result.failures -ne 0 -or $result.errors -ne 0){throw ('Count mismatch '+($result|ConvertTo-Json -Depth 6 -Compress))}
 $result.passed=$result.tests-$result.skipped
 $results+=,$result
}
if($ImageRun -notmatch '^\d{3}-[a-z0-9-]+$'){throw 'Unexpected image run'}
$imageDir=Join-Path $worker $ImageRun
$imageExit=Get-Content -LiteralPath (Join-Path $imageDir 'exit.json') -Raw|ConvertFrom-Json
$imageCommand=Get-Content -LiteralPath (Join-Path $imageDir 'command.json') -Raw|ConvertFrom-Json
$imageLog=Get-Content -LiteralPath (Join-Path $imageDir 'gradle.log') -Raw
if($imageExit.exitCode -ne 0 -or !$imageCommand.image -or $imageLog -notmatch '(?m)^> Task :jpackageImage\r?$'){throw 'Image not freshly successful'}
@{utc=[datetime]::UtcNow.ToString('o');sourceFilesVerified=$freeze.files.Count;tests=$results;imageRun=$ImageRun;imageExecuted=$true}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $target -Encoding utf8
$results|ForEach-Object{[pscustomobject]$_}|Select-Object name,tests,passed,skipped,suites|ConvertTo-Json
'Image command and reviewed source verified'
