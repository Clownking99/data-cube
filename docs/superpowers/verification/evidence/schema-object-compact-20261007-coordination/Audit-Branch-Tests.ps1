param([Parameter(Mandatory)][string]$Directed,[Parameter(Mandatory)][string]$Full,[Parameter(Mandatory)][string]$BuildSrc,[Parameter(Mandatory)][string]$Image,[Parameter(Mandatory)][string]$SourceHashes)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$worker=Join-Path $PSScriptRoot '../schema-object-compact-20261007-worker'
$output=Join-Path $PSScriptRoot 'branch-independent-tests.json'
if(Test-Path -LiteralPath $output){throw 'Audit output must be new'}
$results=@()
foreach($spec in @(@{name=$Directed;task=':test';folder='build-test-results-test';skip=0},@{name=$Full;task=':test';folder='build-test-results-test';skip=3},@{name=$BuildSrc;task=':buildSrc:test';folder='buildSrc-build-test-results-test';skip=0})){
 $dir=Join-Path $worker $spec.name
 $command=Get-Content -LiteralPath (Join-Path $dir 'command.json') -Raw|ConvertFrom-Json
 $exit=Get-Content -LiteralPath (Join-Path $dir 'exit.json') -Raw|ConvertFrom-Json
 $log=Get-Content -LiteralPath (Join-Path $dir 'gradle.log') -Raw
 if($exit.exitCode -ne 0 -or $log -notmatch ('(?m)^> Task '+[regex]::Escape($spec.task)+'\r?$') -or $log -notmatch 'BUILD SUCCESSFUL'){throw ('Task not freshly successful: '+$spec.name)}
 $result=[ordered]@{run=$spec.name;task=$spec.task;baseHead=$command.head;commandUtc=$command.utc;exitUtc=$exit.utc;suites=0;tests=0;failures=0;errors=0;skipped=0;passed=0;skips=@();freshXml=$true}
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $dir $spec.folder) -Filter 'TEST-*.xml' -File){
  [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
  if($file.LastWriteTimeUtc -lt [datetime]$command.utc){throw ('Stale XML: '+$file.Name)}
  if([datetime]$xml.testsuite.timestamp -lt ([datetime]$command.utc).AddSeconds(-1)){throw ('Stale suite timestamp: '+$file.Name)}
  $result.suites++
  foreach($key in @('tests','failures','errors','skipped')){$result[$key]+=[int]$xml.testsuite.$key}
  foreach($case in $xml.SelectNodes('/testsuite/testcase[skipped]')){$result.skips+=@{class=$case.classname;name=$case.name;reason=$case.skipped.message}}
 }
 $result.passed=$result.tests-$result.failures-$result.errors-$result.skipped
 if($result.tests -eq 0 -or $result.failures -ne 0 -or $result.errors -ne 0 -or $result.skipped -ne $spec.skip){throw ('Unexpected test outcome: '+$spec.name)}
 $results+=$result
}
$bindings=Get-Content -LiteralPath (Join-Path $worker $SourceHashes) -Raw|ConvertFrom-Json
foreach($entry in $bindings){
 $file=[IO.Path]::GetFullPath($entry.Path)
 if(!$file.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Source hash path outside current worktree'}
 if((Get-FileHash -LiteralPath $file).Hash -ne $entry.Hash){throw ('Reviewed source changed: '+$file)}
}
$imageDir=Join-Path $worker $Image
$imageExit=Get-Content -LiteralPath (Join-Path $imageDir 'exit.json') -Raw|ConvertFrom-Json
$imageLog=Get-Content -LiteralPath (Join-Path $imageDir 'gradle.log') -Raw
if($imageExit.exitCode -ne 0 -or $imageLog -notmatch '(?m)^> Task :jpackageImage\r?$' -or $imageLog -notmatch 'BUILD SUCCESSFUL'){throw 'Image not freshly successful'}
@{utc=[datetime]::UtcNow.ToString('o');tests=$results;sourceHashes=$bindings;sourceStable=$true;imageRun=$Image;imageSuccess=$true}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $output -Encoding utf8
$results|ForEach-Object{[pscustomobject]$_}|Select-Object run,suites,tests,passed,failures,errors,skipped
