param([string]$Repository,[string]$Scratch,[switch]$Verify,[switch]$Staged)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $Repository
$relative35='docs/superpowers/verification/evidence/oracle-live-acceptance'
$destination35=Join-Path $Repository $relative35
$resultsPath35=Join-Path $Repository 'docs/superpowers/verification/2026-09-30-oracle-live-acceptance-results.json'
if(!$Verify) {
 foreach($helper35 in @('run-check.ps1','isolated-tests.gradle','audit-image.ps1','source-snapshot.json','OracleLiveAcceptance-first.java','main-merge.log','staged-diff-check-first.log')) {
  $source35=Join-Path $Scratch $helper35
  if(Test-Path -LiteralPath $source35){Copy-Item -LiteralPath $source35 -Destination $destination35}
 }
 Copy-Item -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/evidence/g7/MigrationRuntimeDriverProbe.java') -Destination $destination35
 [IO.File]::WriteAllText((Join-Path $destination35 '.gitattributes'),"* -text whitespace=cr-at-eol`n*.log -whitespace`n",[Text.UTF8Encoding]::new($false))
 $checks35=@()
 foreach($recordFile35 in (Get-ChildItem -LiteralPath $Scratch -Filter '*.json' | Sort-Object Name)) {
  $record35=Get-Content -LiteralPath $recordFile35.FullName -Raw | ConvertFrom-Json
  if(!$record35.name){continue}
  $checks35+=$record35
  $directory35=Join-Path $destination35 ('checks/'+$record35.name)
  New-Item -ItemType Directory -Path $directory35 -Force | Out-Null
  Copy-Item -LiteralPath $recordFile35.FullName -Destination $directory35
  foreach($logName35 in @($record35.log,$record35.errorLog,($record35.name+'-driver.log'))) {
   if($logName35 -and (Test-Path -LiteralPath (Join-Path $Scratch $logName35))) {Copy-Item -LiteralPath (Join-Path $Scratch $logName35) -Destination $directory35}
  }
  if($record35.actualTestTaskRan) {
   $xmlFiles35=@(Get-ChildItem -LiteralPath (Join-Path $Scratch $record35.xmlDirectory) -Filter 'TEST-*.xml' | Sort-Object Name)
   $xmlManifest35=($xmlFiles35 | ForEach-Object {$_.Name+' '+(Get-FileHash -LiteralPath $_.FullName).Hash}) -join "`n"
   [IO.File]::WriteAllText((Join-Path $directory35 'xml-manifest.txt'),$xmlManifest35,[Text.UTF8Encoding]::new($false))
   foreach($xml35 in $xmlFiles35) {
    if($xmlFiles35.Count -le 10 -or $xml35.Name -match 'DriverCancellationClassificationTest|SqlRunnerExecutionControlTest|JdbcPreparedQueryExecutorTest|SqlExecutionControlTest|JdbcEditorSessionTest|RedisLive|SchemaDiffLive') {Copy-Item -LiteralPath $xml35.FullName -Destination $directory35}
   }
  }
 }
 [ordered]@{base='4fa68014bab0ba3d4874d02bb31db874da66033e';archivedAt=(Get-Date).ToString('o');checks=$checks35;sourceSnapshot=(Get-Content -LiteralPath (Join-Path $Scratch 'source-snapshot.json') -Raw | ConvertFrom-Json);boundary='Explicit user authorization for one Oracle target and unique acceptance tables; INSERT/UPDATE only; no DELETE/TRUNCATE/DROP, existing business rows, credential persistence, .testagent or external publishing';unverified=@('Native field hit to data/DDL actions after second modal input failed','PG/Redis live and existing Schema Diff live cleanup requiring DROP USER','Complete native keyboard/window/OS multi-monitor','Formal launcher/install/upgrade/signing/remote CI/user tasks/release')} | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $resultsPath35 -Encoding utf8
 $entries35=@(Get-ChildItem -LiteralPath $destination35 -Recurse -File | Where-Object Name -ne 'manifest.json' | Sort-Object FullName | ForEach-Object {[ordered]@{path=[IO.Path]::GetRelativePath($destination35,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
 [ordered]@{createdAt=(Get-Date).ToString('o');files=$entries35} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination35 'manifest.json') -Encoding utf8
 Write-Output ('checks='+$checks35.Count+' rawFiles='+$entries35.Count)
 exit
}
$manifest35=Get-Content -LiteralPath (Join-Path $destination35 'manifest.json') -Raw | ConvertFrom-Json
foreach($entry35 in $manifest35.files) {
 $path35=Join-Path $destination35 $entry35.path
 if((Get-FileHash -LiteralPath $path35).Hash -ne $entry35.sha256 -or (Get-Item -LiteralPath $path35).Length -ne $entry35.bytes){throw ('Archive bytes mismatch: '+$entry35.path)}
 if($Staged -and (git hash-object --no-filters -- $path35) -ne (git rev-parse (':'+$relative35+'/'+$entry35.path))){throw 'Staged raw bytes mismatch'}
}
$results35=Get-Content -LiteralPath $resultsPath35 -Raw | ConvertFrom-Json
foreach($check35 in $results35.checks) {
 $directory35=Join-Path $destination35 ('checks/'+$check35.name)
 if($check35.logSha256 -and (Get-FileHash -LiteralPath (Join-Path $directory35 $check35.log)).Hash -ne $check35.logSha256){throw 'Log hash mismatch'}
 if($check35.driverLogSha256 -and (Get-FileHash -LiteralPath (Join-Path $directory35 ($check35.name+'-driver.log'))).Hash -ne $check35.driverLogSha256){throw 'Driver log mismatch'}
 if($check35.actualTestTaskRan) {
  $xmlPath35=Join-Path $directory35 'xml-manifest.txt'
  if((Get-FileHash -LiteralPath $xmlPath35).Hash -ne $check35.xmlManifestSha256){throw 'XML manifest mismatch'}
  $xmlEntries35=@(Get-Content -LiteralPath $xmlPath35)
  if($xmlEntries35.Count -ne $check35.suites){throw 'XML suite count mismatch'}
  foreach($xmlEntry35 in $xmlEntries35) {
   $parts35=$xmlEntry35.Split(' ',2);$xml35=Join-Path $directory35 $parts35[0]
   if((Test-Path -LiteralPath $xml35) -and (Get-FileHash -LiteralPath $xml35).Hash -ne $parts35[1]){throw 'Archived XML mismatch'}
  }
 }
}
foreach($source35 in $results35.sourceSnapshot.files) {
 if((git hash-object --path=$($source35.path) -- $source35.path) -ne $source35.gitBlob){throw 'Source blob mismatch'}
 if($Staged -and (git rev-parse (':'+$source35.path)) -ne $source35.gitBlob){throw 'Staged source mismatch'}
}
if($Staged) {
 $manifestPath35=$relative35+'/manifest.json'
 if((git hash-object --no-filters -- $manifestPath35) -ne (git rev-parse (':'+$manifestPath35))){throw 'Staged manifest mismatch'}
}
Write-Output ('Verified rawFiles='+$manifest35.files.Count+' checks='+$results35.checks.Count+' staged='+[bool]$Staged)
