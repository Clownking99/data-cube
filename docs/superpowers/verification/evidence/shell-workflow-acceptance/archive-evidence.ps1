param([string]$Repository)
$ErrorActionPreference='Stop'
$taskEvidence = Join-Path $Repository 'docs/superpowers/verification/evidence/shell-workflow-acceptance'
$taskScratch = $PSScriptRoot
New-Item -ItemType Directory -Path $taskEvidence,(Join-Path $taskEvidence 'desktop'),(Join-Path $taskEvidence 'checks'),(Join-Path $taskEvidence 'launches/small'),(Join-Path $taskEvidence 'launches/before-fix'),(Join-Path $taskEvidence 'launches/after-fix') -Force | Out-Null
foreach($taskFile in @('run-check.ps1','isolated-tests.gradle','desktop.gradle','audit-image.ps1','archive-evidence.ps1','ShellDiscoveryDesktopProbe.java','SqlSmallWindowDesktopProbe.java','source-snapshot.json')) {
 Copy-Item -LiteralPath (Join-Path $taskScratch $taskFile) -Destination (Join-Path $taskEvidence $taskFile) -Force
}
Copy-Item -Path (Join-Path $taskScratch 'desktop/*') -Destination (Join-Path $taskEvidence 'desktop') -Force
foreach($taskFile in @('small.args','small-launch.json','small.stdout.log','small.stderr.log','small-compile.log')) {
 Copy-Item -LiteralPath (Join-Path $taskScratch $taskFile) -Destination (Join-Path $taskEvidence ('launches/small/'+$taskFile)) -Force
}
foreach($taskFile in @('shell.args','shell-launch.json','shell.stdout.log','shell.stderr.log')) {
 Copy-Item -LiteralPath (Join-Path $taskScratch ('before-fix/'+$taskFile)) -Destination (Join-Path $taskEvidence ('launches/before-fix/'+$taskFile)) -Force
 Copy-Item -LiteralPath (Join-Path $taskScratch $taskFile) -Destination (Join-Path $taskEvidence ('launches/after-fix/'+$taskFile)) -Force
}
foreach($taskFile in @('shell-first-failed.args','shell-first-failed.stderr.log','shell-compile.log','shell-loading-threads.log','desktop-build.log','desktop-classpath.txt')) {
 Copy-Item -LiteralPath (Join-Path $taskScratch $taskFile) -Destination (Join-Path $taskEvidence ('checks/'+$taskFile)) -Force
}
if(Test-Path -LiteralPath (Join-Path $taskScratch 'integration-binding.json')) {
 Copy-Item -LiteralPath (Join-Path $taskScratch 'integration-binding.json') -Destination (Join-Path $taskEvidence 'checks/integration-binding.json') -Force
}
$taskRecords = @()
foreach($taskName in @('branch-regression-red','branch-regression-red-runtime','branch-targeted','branch-layout','branch-full','branch-buildSrc','branch-image','branch-runtime','main-targeted','main-full','main-buildSrc','main-image','main-runtime')) {
 $taskJson=Join-Path $taskScratch ($taskName+'.json')
 if(!(Test-Path -LiteralPath $taskJson)){continue}
 $taskRecords += Get-Content -LiteralPath $taskJson -Raw | ConvertFrom-Json
 foreach($taskSuffix in @('.json','.log','-driver.log')) {
  $taskFile=Join-Path $taskScratch ($taskName+$taskSuffix)
  if(Test-Path -LiteralPath $taskFile){Copy-Item -LiteralPath $taskFile -Destination (Join-Path $taskEvidence ('checks/'+$taskName+$taskSuffix)) -Force}
 }
 $taskXml=Join-Path $taskScratch ($taskName+'-xml')
 if(Test-Path -LiteralPath $taskXml){
  $taskXmlFiles=@(Get-ChildItem -LiteralPath $taskXml -Filter 'TEST-*.xml' | Sort-Object Name)
  $taskManifest=($taskXmlFiles | ForEach-Object { $_.Name+' '+(Get-FileHash -LiteralPath $_.FullName).Hash }) -join "`n"
  [IO.File]::WriteAllText((Join-Path $taskEvidence ('checks/'+$taskName+'-xml-manifest.txt')),$taskManifest,[Text.UTF8Encoding]::new($false))
  $taskSelection=@($taskXmlFiles | Where-Object { $taskName -notin @('branch-full','main-full') -or $_.Name -match 'ConnectionTreeLazyLoadTest|ConnectionTreePaneLifecycleTest|SchemaMetadataSearchDialogTest|TableSelectSqlTabsTest|SqlPanelLayoutIntegrationTest|SqlResultToolbarLayoutTest' })
  New-Item -ItemType Directory -Path (Join-Path $taskEvidence ('checks/'+$taskName+'-xml')) -Force | Out-Null
  foreach($taskFile in $taskSelection){Copy-Item -LiteralPath $taskFile.FullName -Destination (Join-Path $taskEvidence ('checks/'+$taskName+'-xml/'+$taskFile.Name)) -Force}
 }
}
@{baseline='12e927af6fdc67bf039b3d4121d13fb5b21fc8c6';scratch=$taskScratch;records=$taskRecords} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/2026-09-30-shell-workflow-acceptance-results.json') -Encoding utf8
[IO.File]::WriteAllText((Join-Path $taskEvidence '.gitattributes'),"* -text whitespace=cr-at-eol`nShellDiscoveryDesktopProbe.java -text whitespace=cr-at-eol,-blank-at-eof`ndesktop/* -text whitespace=-blank-at-eol,-blank-at-eof`nchecks/** -text whitespace=-blank-at-eol,-blank-at-eof`nlaunches/** -text whitespace=-blank-at-eol,-blank-at-eof`n",[Text.UTF8Encoding]::new($false))
$taskFiles=@(Get-ChildItem -LiteralPath $taskEvidence -Recurse -File | Where-Object Name -ne 'manifest.json' | Sort-Object FullName)
$taskManifest=@($taskFiles | ForEach-Object { @{path=[IO.Path]::GetRelativePath($taskEvidence,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash} })
$taskManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskEvidence 'manifest.json') -Encoding utf8
@{files=$taskFiles.Count;png=@($taskFiles | Where-Object Extension -eq '.png').Count;desktopStates=@($taskFiles | Where-Object { $_.Directory.Name -eq 'desktop' -and $_.Extension -eq '.json' }).Count;records=$taskRecords.Count} | ConvertTo-Json
