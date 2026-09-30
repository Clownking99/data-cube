param([string]$Repository)
$ErrorActionPreference='Stop'
$destination33=Join-Path $Repository 'docs/superpowers/verification/evidence/schema-object-admission'
New-Item -ItemType Directory -Path $destination33 -Force | Out-Null
foreach($name33 in @('run-check.ps1','isolated-tests.gradle','audit-image.ps1','archive-evidence.ps1','verify-evidence.ps1','source-snapshot.json','initial-source-snapshot.json','name-red-test.java','main-merge.log')) {
 $source33=Join-Path $PSScriptRoot $name33
 if(Test-Path -LiteralPath $source33){Copy-Item -LiteralPath $source33 -Destination $destination33}
}
Copy-Item -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/evidence/g7/MigrationRuntimeDriverProbe.java') -Destination $destination33
[IO.File]::WriteAllText((Join-Path $destination33 '.gitattributes'),"* -text`n",[Text.UTF8Encoding]::new($false))
$checks33=@()
foreach($file33 in (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.json' | Where-Object Name -ne 'source-snapshot.json' | Sort-Object Name)) {
 $record33=Get-Content -LiteralPath $file33.FullName -Raw | ConvertFrom-Json
 if(!$record33.name){continue}
 $checks33 += $record33
 $checkDir33=Join-Path $destination33 ('checks/'+$record33.name)
 New-Item -ItemType Directory -Path $checkDir33 -Force | Out-Null
 Copy-Item -LiteralPath $file33.FullName -Destination $checkDir33
 foreach($log33 in @($record33.log,($record33.name+'-driver.log'))) {
  if($log33 -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot $log33))) {
   Copy-Item -LiteralPath (Join-Path $PSScriptRoot $log33) -Destination $checkDir33
  }
 }
 if($record33.actualTestTaskRan) {
  $xmlDir33=Join-Path $PSScriptRoot $record33.xmlDirectory
  $xmlFiles33=@(Get-ChildItem -LiteralPath $xmlDir33 -Filter 'TEST-*.xml' | Sort-Object Name)
  $manifest33=($xmlFiles33 | ForEach-Object {$_.Name+' '+(Get-FileHash -LiteralPath $_.FullName).Hash}) -join "`n"
  $manifestPath33=Join-Path $checkDir33 'xml-manifest.txt'
  [IO.File]::WriteAllText($manifestPath33,$manifest33,[Text.UTF8Encoding]::new($false))
  if((Get-FileHash -LiteralPath $manifestPath33).Hash -ne $record33.xmlManifestSha256){throw 'XML manifest mismatch'}
  foreach($xml33 in $xmlFiles33) {
   if($xmlFiles33.Count -le 12 -or $xml33.Name -match 'MetadataSearchShellRoutingTest|SchemaObject.*Test|SchemaMetadataSearchDialogTest|SchemaObjectSearchLifecycleTest|SchemaMetadataSearchTest|SchemaObjectCatalogTest|RedisLive|SchemaDiffLive') {
    Copy-Item -LiteralPath $xml33.FullName -Destination $checkDir33
   }
  }
 }
}
$result33=[ordered]@{base='918d7198cb7ec07d509bbce024dd442972889f6b';archivedAt=(Get-Date).ToString('o');checks=$checks33;
 sourceSnapshot=(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source-snapshot.json') -Raw | ConvertFrom-Json);
 boundary='Offline JDK 25; new synthetic profiles, fixed catalog SQL and strict mocks; no real connections, credentials, histories, business files or external mutations';
 evidenceLevel='Actual AppShell programmatic FX and mock JDBC for PostgreSQL/Oracle; this increment has no new native desktop evidence';
 unverified=@('Native close/reopen waiting hint and full metadata request to SELECT/read-only DATA/DDL; Oracle desktop','Real PostgreSQL/Oracle/Redis permissions, transaction and cancellation','OS scaling/multi-monitor and full keyboard traversal','Formal launcher/install/upgrade/signing/remote CI/user tasks/release')}
$result33 | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/2026-09-30-schema-object-admission-results.json') -Encoding utf8
$manifestEntries33=@(Get-ChildItem -LiteralPath $destination33 -Recurse -File | Where-Object Name -ne 'manifest.json' | Sort-Object FullName | ForEach-Object {
 [ordered]@{path=[IO.Path]::GetRelativePath($destination33,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
[ordered]@{createdAt=(Get-Date).ToString('o');files=$manifestEntries33} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination33 'manifest.json') -Encoding utf8
Write-Output ('checks='+$checks33.Count+' manifestFiles='+$manifestEntries33.Count)
