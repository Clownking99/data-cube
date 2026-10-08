param([Parameter(Mandatory)][string]$Name,[switch]$BuildSrc)
$ErrorActionPreference='Stop'
$dir=Join-Path $PSScriptRoot $Name
$command=Get-Content -LiteralPath (Join-Path $dir 'command.json') -Raw|ConvertFrom-Json
$exit=Get-Content -LiteralPath (Join-Path $dir 'exit.json') -Raw|ConvertFrom-Json
$log=Get-Content -LiteralPath (Join-Path $dir 'gradle.log') -Raw
$task=if($BuildSrc){':buildSrc:test'}else{':test'}
if($log -notmatch ('(?m)^> Task '+[regex]::Escape($task)+'\r?$')){throw 'Required test task was not executed'}
$reports=if($BuildSrc){'buildSrc-build-test-results-test'}else{'build-test-results-test'}
$result=[ordered]@{run=$Name;source=$command.head;task=$task;exitCode=$exit.exitCode;suites=0;tests=0;failures=0;errors=0;skipped=0;passed=0;skips=@();freshXml=$true}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $dir $reports) -Filter 'TEST-*.xml'){
 if($file.LastWriteTimeUtc -lt [datetime]$command.utc){throw 'Stale report'}
 [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
 $result.suites++
 foreach($key in @('tests','failures','errors','skipped')){$result[$key]+=[int]$xml.testsuite.$key}
 foreach($case in $xml.SelectNodes('/testsuite/testcase[skipped]')){$result.skips+=@{class=$case.classname;name=$case.name;reason=$case.skipped.message}}
}
$result.passed=$result.tests-$result.failures-$result.errors-$result.skipped
$result|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $dir 'summary.json') -Encoding utf8
if($result.tests -eq 0 -or $result.exitCode -ne 0 -or $result.failures -ne 0 -or $result.errors -ne 0){throw 'Test validation failed'}
$result|ConvertTo-Json -Depth 5
