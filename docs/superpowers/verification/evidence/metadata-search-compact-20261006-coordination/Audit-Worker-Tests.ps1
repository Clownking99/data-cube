$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$worker=Join-Path (Split-Path $PSScriptRoot) 'metadata-search-compact-20261006-worker'
$results=@()
foreach($spec in @(@{name='green-reviewed-pulses';report='build-test-results-test'},@{name='full';report='build-test-results-test'},@{name='buildsrc-forced';report='buildSrc-build-test-results-test'})) {
 $dir=Join-Path $worker $spec.name
 $command=Get-Content -LiteralPath (Join-Path $dir 'command.json') -Raw | ConvertFrom-Json
 $exit=Get-Content -LiteralPath (Join-Path $dir 'exit.json') -Raw | ConvertFrom-Json
 $log=Get-Content -LiteralPath (Join-Path $dir 'gradle.log') -Raw
 if($exit.exitCode -ne 0 -or $log -notmatch '(?m)^> Task :test\r?$'){throw ('Test not executed successfully: '+$spec.name)}
 $s=[ordered]@{run=$spec.name;tests=0;failures=0;errors=0;skipped=0;passed=0;suites=@();skips=@();freshXml=$true}
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $dir $spec.report) -File -Filter 'TEST-*.xml') {
  if($file.LastWriteTimeUtc -lt [datetime]$command.utc){throw ('Stale XML: '+$file.Name)}
  [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
  $s.suites+= [string]$xml.testsuite.name
  foreach($key in @('tests','failures','errors','skipped')){$s[$key]+=[int]$xml.testsuite.$key}
  foreach($case in $xml.SelectNodes('/testsuite/testcase[skipped]')){$s.skips+=@{class=$case.classname;name=$case.name;reason=$case.skipped.InnerText}}
 }
 $s.passed=$s.tests-$s.failures-$s.errors-$s.skipped
 if($s.tests -eq 0 -or $s.failures -ne 0 -or $s.errors -ne 0){throw ('Invalid test result: '+$spec.name)}
 if($spec.name -eq 'green-reviewed-pulses') {
  foreach($suite in @('com.datacube.fx.SchemaMetadataSearchDialogTest','com.datacube.fx.SchemaObjectSearchDialogTest','com.datacube.fx.SchemaObjectSearchLifecycleTest','com.datacube.fx.MetadataSearchShellRoutingTest','com.datacube.service.SchemaMetadataSearchTest')) {
   if($s.suites -notcontains $suite){throw ('Missing requested suite: '+$suite)}
  }
  if($s.skipped -ne 0){throw 'Directed skips'}
 }
 if($spec.name -eq 'buildsrc-forced' -and ($s.tests -ne 8 -or $s.skipped -ne 0)){throw 'buildSrc incomplete'}
 $results+= $s
}
$source=Get-Content -LiteralPath (Join-Path $worker 'final-source-sha256.json') -Raw | ConvertFrom-Json
foreach($file in $source) {
 if(!$file.Path.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Source scope'}
 if((Get-FileHash -LiteralPath $file.Path).Hash -ne $file.Hash){throw 'Reviewed source drift'}
}
$record=@{utc=[datetime]::UtcNow.ToString('o');sourceStable=$true;results=$results}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'worker-test-audit.json') -Encoding utf8
$results | ForEach-Object { [pscustomobject]@{run=$_.run;suites=$_.suites.Count;tests=$_.tests;passed=$_.passed;skipped=$_.skipped} } | ConvertTo-Json
