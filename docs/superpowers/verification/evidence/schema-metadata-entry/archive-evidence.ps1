param([string]$Repository)
$ErrorActionPreference='Stop'
$destination32=Join-Path $Repository 'docs/superpowers/verification/evidence/schema-metadata-entry'
New-Item -ItemType Directory -Path $destination32 -Force | Out-Null
foreach($name32 in @('run-check.ps1','isolated-tests.gradle','audit-image.ps1','archive-evidence.ps1','source-snapshot.json','desktop.gradle','SchemaMetadataEntryDesktopProbe.java','desktop-first-source.java','desktop-compile.log','desktop-compile-final.log','desktop-stdout.log','desktop-stderr.log','desktop2-stdout.log','desktop2-stderr.log','desktop-args.txt','desktop-args2.txt','desktop-args3.txt','desktop-args4.txt','desktop-final-runtime.log')) {
 Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name32) -Destination $destination32
}
Copy-Item -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/evidence/g7/MigrationRuntimeDriverProbe.java') -Destination $destination32
$nativeDestination32=Join-Path $destination32 'native'
New-Item -ItemType Directory -Path $nativeDestination32 -Force | Out-Null
foreach($nativeFile32 in (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'native') -File)) {
 Copy-Item -LiteralPath $nativeFile32.FullName -Destination $nativeDestination32
}
[IO.File]::WriteAllText((Join-Path $destination32 '.gitattributes'),"* -text`n",[Text.UTF8Encoding]::new($false))
$checks32=@()
foreach($file32 in (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.json' | Where-Object Name -ne 'source-snapshot.json' | Sort-Object Name)) {
 $record32=Get-Content -LiteralPath $file32.FullName -Raw | ConvertFrom-Json
 if(!$record32.name){continue}
 $checks32 += $record32
 $checkDir32=Join-Path $destination32 ('checks/'+$record32.name)
 New-Item -ItemType Directory -Path $checkDir32 -Force | Out-Null
 Copy-Item -LiteralPath $file32.FullName -Destination $checkDir32
 foreach($log32 in @($record32.log,($record32.name+'-driver.log'))) {
  if($log32 -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot $log32))) {
   Copy-Item -LiteralPath (Join-Path $PSScriptRoot $log32) -Destination $checkDir32
  }
 }
 if($record32.actualTestTaskRan) {
  $xmlDir32=Join-Path $PSScriptRoot $record32.xmlDirectory
  $xmlFiles32=@(Get-ChildItem -LiteralPath $xmlDir32 -Filter 'TEST-*.xml' | Sort-Object Name)
  $manifest32=($xmlFiles32 | ForEach-Object {$_.Name+' '+(Get-FileHash -LiteralPath $_.FullName).Hash}) -join "`n"
  $manifestPath32=Join-Path $checkDir32 'xml-manifest.txt'
  [IO.File]::WriteAllText($manifestPath32,$manifest32,[Text.UTF8Encoding]::new($false))
  if((Get-FileHash -LiteralPath $manifestPath32).Hash -ne $record32.xmlManifestSha256){throw 'XML manifest mismatch'}
  foreach($xml32 in $xmlFiles32) {
   if($xmlFiles32.Count -le 12 -or $xml32.Name -match 'MetadataSearchShellRoutingTest|SchemaObjectFindEntryTest|SchemaMetadataSearchDialogTest|SchemaObjectSearchLifecycleTest|SchemaMetadataSearchTest|RedisLive|SchemaDiffLive') {
    Copy-Item -LiteralPath $xml32.FullName -Destination $checkDir32
   }
  }
 }
}
$result32=[ordered]@{base='4f53e35d06b078f7c2563382a3907ff35c808dcd';archivedAt=(Get-Date).ToString('o');checks=$checks32;
 sourceSnapshot=(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source-snapshot.json') -Raw | ConvertFrom-Json);
 boundary='Offline JDK 25; synthetic profiles, fixed catalog SQL and strict mocks; no real connections, credentials, histories or business files; no external mutations';
 evidenceLevel='Actual AppShell programmatic FX routing for PG and Oracle; native PG direct entry, single dialog, copy, cancel and shutdown only';
 unverified=@('Complete native metadata request and result to SELECT/read-only DATA/DDL; Oracle desktop','Real PostgreSQL/Oracle/Redis permissions, transaction and cancellation','OS scaling/multi-monitor and full keyboard traversal','Formal launcher/install/upgrade/signing/remote CI/user tasks/release')}
$result32 | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/2026-09-30-schema-metadata-entry-results.json') -Encoding utf8
$manifestEntries32=@(Get-ChildItem -LiteralPath $destination32 -Recurse -File | Where-Object Name -ne 'manifest.json' | Sort-Object FullName | ForEach-Object {
 [ordered]@{path=[IO.Path]::GetRelativePath($destination32,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
[ordered]@{createdAt=(Get-Date).ToString('o');files=$manifestEntries32} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination32 'manifest.json') -Encoding utf8
Write-Output ('checks='+$checks32.Count+' manifestFiles='+$manifestEntries32.Count)
