param([Parameter(Mandatory)][string]$Root,[Parameter(Mandatory)][string]$Prefix,[Parameter(Mandatory)][string[]]$Names,[Parameter(Mandatory)][string]$Output)
$ErrorActionPreference='Stop'
$results=@(foreach($name in $Names) {
 $dir=Join-Path (Join-Path $Root $Prefix) $name
 $command=Get-Content -LiteralPath (Join-Path $dir 'command.json') -Raw|ConvertFrom-Json
 $exit=Get-Content -LiteralPath (Join-Path $dir 'exit.json') -Raw|ConvertFrom-Json
 $log=Get-Content -LiteralPath (Join-Path $dir 'gradle.log') -Raw
 $record=[ordered]@{run=$name;head=$command.head;exitCode=$exit.exitCode;image=$command.image;actualTestTask=$false;suites=0;tests=0;failures=0;errors=0;skipped=0;passed=0;skips=@();freshXml=$true}
 if($command.image) {
  if($log -notmatch '(?m)^> Task :jpackageImage\r?$' -or $exit.exitCode -ne 0){throw 'Image not actually successful'}
 } else {
  foreach($kind in @(@{task=':test';dir='build-test-results-test'},@{task=':buildSrc:test';dir='buildSrc-build-test-results-test'})) {
   $reports=Join-Path $dir $kind.dir
   if(!(Test-Path -LiteralPath $reports)){continue}
   if($log -notmatch ('(?m)^> Task '+[regex]::Escape($kind.task)+'(?: FAILED)?\r?$')){throw 'Reports without actual test execution'}
   $record.actualTestTask=$true
   foreach($file in Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -File) {
    if($file.LastWriteTimeUtc -lt [datetime]$command.utc){throw 'Stale XML'}
    [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
    $record.suites++
    foreach($key in @('tests','failures','errors','skipped')){$record[$key]+=[int]$xml.testsuite.$key}
    foreach($case in $xml.SelectNodes('/testsuite/testcase[skipped]')){$record.skips+=@{class=$case.classname;name=$case.name;reason=$case.skipped.message}}
   }
  }
  if(!$record.actualTestTask -or $record.tests -eq 0){throw 'No executed tests'}
  $record.passed=$record.tests-$record.failures-$record.errors-$record.skipped
 }
 [pscustomobject]$record
})
$results|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $Output -Encoding utf8
$results|Select-Object run,exitCode,suites,tests,passed,failures,errors,skipped,image
