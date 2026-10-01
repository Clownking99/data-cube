param([Parameter(Mandatory=$true)][ValidateSet('directed','full','buildsrc','image')][string]$Step)
$ErrorActionPreference='Stop'
$main='D:/Projects/朝花夕拾'
$baseline=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'baseline.json') -Raw | ConvertFrom-Json
$runRoot=[IO.Path]::GetFullPath($baseline.runRoot)
if((Split-Path -Leaf $runRoot) -notmatch '^datacube-sol-p3-[0-9a-f]{32}$' -or -not $runRoot.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected P3 run root'}
Set-Location -LiteralPath $main
if((git branch --show-current) -ne 'main' -or (git rev-parse HEAD) -ne $baseline.main){throw 'Main source baseline changed'}
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$env:PATH=$env:JAVA_HOME+'/bin;'+$env:PATH
foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS')){Remove-Item -LiteralPath ('Env:'+ $name) -ErrorAction SilentlyContinue}
$init=Join-Path $main 'docs/superpowers/verification/evidence/sol-p0-p2/isolation.init.gradle'
$arguments=@('--offline','--no-daemon','--console=plain','--rerun-tasks')
$report='build/test-results/test'
switch($Step){
 'directed' {
  $arguments+=@('-I',$init,('-Ddatacube.acceptance.root='+$runRoot+'/directed'),'test')
  foreach($suite in @('com.datacube.fx.MetadataSearchShellRoutingTest','com.datacube.fx.WriteSafetyIntegrationTest','com.datacube.fx.DataGridSaveFlowTest','com.datacube.fx.DataGridPaneLifecycleTest','com.datacube.fx.ObjectEditorPaneLifecycleTest','com.datacube.service.RelationalWriteSafetyTest')){$arguments+=@('--tests',$suite)}
 }
 'full' {$arguments+=@('-I',$init,('-Ddatacube.acceptance.root='+$runRoot+'/full'),'test')}
 'buildsrc' {$arguments+=@('-p','buildSrc','-I',$init,('-Ddatacube.acceptance.root='+$runRoot+'/buildsrc'),'clean','test');$report='buildSrc/build/test-results/test'}
 'image' {$arguments+=@('jpackageImage')}
}
$started=[DateTime]::UtcNow.ToString('o')
& (Join-Path $main 'gradlew.bat') @arguments *> (Join-Path $PSScriptRoot ($Step+'.log'))
$code=$LASTEXITCODE
[ordered]@{step=$Step;main=$baseline.main;startedUtc=$started;endedUtc=[DateTime]::UtcNow.ToString('o');exitCode=$code;arguments=$arguments;profileRoot=if($Step -eq 'image'){$null}else{$runRoot+'/'+$Step}} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot ($Step+'-execution.json')) -Encoding utf8
if($Step -ne 'image'){
 $destination=Join-Path $PSScriptRoot $Step
 New-Item -ItemType Directory -Path $destination -ErrorAction Stop | Out-Null
 $suites=@();$totals=@{tests=0;failures=0;errors=0;skipped=0}
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $main $report) -Filter 'TEST-*.xml' -File){
  [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
  $suite=$xml.testsuite;$row=[ordered]@{suite=$suite.name}
  foreach($metric in @('tests','failures','errors','skipped')){$row[$metric]=[int]$suite.GetAttribute($metric);$totals[$metric]+=$row[$metric]}
  $suites+=$row
  Copy-Item -LiteralPath $file.FullName -Destination $destination
 }
 [ordered]@{suites=$suites.Count;tests=$totals.tests;passed=$totals.tests-$totals.failures-$totals.errors-$totals.skipped;failures=$totals.failures;errors=$totals.errors;skipped=$totals.skipped;details=$suites} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'summary.json') -Encoding utf8
 Write-Output "$Step suites=$($suites.Count) tests=$($totals.tests) failures=$($totals.failures) errors=$($totals.errors) skipped=$($totals.skipped)"
}
Write-Output "$Step exit=$code"
exit $code
