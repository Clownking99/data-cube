param([string]$Repository)
$ErrorActionPreference='Stop'
$destination31=Join-Path $Repository 'docs/superpowers/verification/evidence/metadata-shell-routing'
New-Item -ItemType Directory -Path $destination31 -Force | Out-Null
foreach($name31 in @('run-check.ps1','isolated-tests.gradle','audit-image.ps1','archive-evidence.ps1','source-snapshot.json','routing-red-confirmed-test.java')) {
 Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name31) -Destination $destination31
}
Copy-Item -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/evidence/g7/MigrationRuntimeDriverProbe.java') -Destination $destination31
$checks31=@()
foreach($file31 in (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.json' | Where-Object Name -ne 'source-snapshot.json' | Sort-Object Name)) {
 $record31=Get-Content -LiteralPath $file31.FullName -Raw | ConvertFrom-Json
 if(!$record31.name){continue}
 $checks31 += $record31
 $checkDir31=Join-Path $destination31 ('checks/'+$record31.name)
 New-Item -ItemType Directory -Path $checkDir31 -Force | Out-Null
 Copy-Item -LiteralPath $file31.FullName -Destination $checkDir31
 foreach($log31 in @($record31.log,($record31.name+'-driver.log'))) {
  if($log31 -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot $log31))) {
   Copy-Item -LiteralPath (Join-Path $PSScriptRoot $log31) -Destination $checkDir31
  }
 }
 if($record31.actualTestTaskRan) {
  $xmlDir31=Join-Path $PSScriptRoot $record31.xmlDirectory
  $xmlFiles31=@(Get-ChildItem -LiteralPath $xmlDir31 -Filter 'TEST-*.xml' | Sort-Object Name)
  $manifest31=($xmlFiles31 | ForEach-Object {$_.Name+' '+(Get-FileHash -LiteralPath $_.FullName).Hash}) -join "`n"
  $manifestPath31=Join-Path $checkDir31 'xml-manifest.txt'
  [IO.File]::WriteAllText($manifestPath31,$manifest31,[Text.UTF8Encoding]::new($false))
  if((Get-FileHash -LiteralPath $manifestPath31).Hash -ne $record31.xmlManifestSha256){throw 'XML manifest mismatch'}
  foreach($xml31 in $xmlFiles31) {
   if($xmlFiles31.Count -le 12 -or $xml31.Name -match 'MetadataSearchShellRoutingTest|SchemaMetadataSearchDialogTest|DataGridExplicitSaveTest|AppShellTest|RedisLive|SchemaDiffLive') {
    Copy-Item -LiteralPath $xml31.FullName -Destination $checkDir31
   }
  }
 }
}
$result31=[ordered]@{base='a28eb79d8dbdf29530ea564b24c2294a7c9224fc';archivedAt=(Get-Date).ToString('o');checks=$checks31;
 boundary='Offline JDK 25; synthetic profiles, fixed catalog SQL and strict mocks; no real profiles, credentials, histories or business files; no external mutations';
 evidenceLevel='Programmatic FX integration through actual AppShell handlers; no new native desktop acceptance';
 unverified=@('Complete native metadata result to SELECT/read-only DATA/DDL workflow','Oracle route and real PostgreSQL/Oracle/Redis permissions, transaction and cancellation','OS scaling/multi-monitor and full keyboard traversal','Formal launcher/install/upgrade/signing/remote CI/user tasks/release')}
$result31 | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/2026-09-30-metadata-shell-routing-results.json') -Encoding utf8
$manifestEntries31=@(Get-ChildItem -LiteralPath $destination31 -Recurse -File | Where-Object Name -ne 'manifest.json' | Sort-Object FullName | ForEach-Object {
 [ordered]@{path=[IO.Path]::GetRelativePath($destination31,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
[ordered]@{createdAt=(Get-Date).ToString('o');files=$manifestEntries31} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination31 'manifest.json') -Encoding utf8
Write-Output ('checks='+$checks31.Count+' manifestFiles='+$manifestEntries31.Count)
