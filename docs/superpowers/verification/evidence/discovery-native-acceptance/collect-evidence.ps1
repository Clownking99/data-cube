param([string]$Repository)
$ErrorActionPreference='Stop'
$scratch=$PSScriptRoot
$dest=Join-Path $Repository 'docs/superpowers/verification/evidence/discovery-native-acceptance'
New-Item -ItemType Directory -Path $dest,($dest+'/desktop'),($dest+'/checks'),($dest+'/launches') -Force | Out-Null
[IO.File]::WriteAllText($dest+'/.gitattributes',"* -text whitespace=cr-at-eol`ndesktop/* -text whitespace=-blank-at-eol,-blank-at-eof`nchecks/* -text whitespace=-blank-at-eol,-blank-at-eof`nlaunches/** -text whitespace=-blank-at-eol,-blank-at-eof`n",[Text.UTF8Encoding]::new($false))
Copy-Item -Path ($scratch+'/desktop/*') -Destination ($dest+'/desktop') -Force
foreach($name in @('run-check.ps1','isolated-tests.gradle','audit-image.ps1','desktop.gradle','MetadataCancellationDesktopProbe.java','collect-evidence.ps1')) { Copy-Item -LiteralPath ($scratch+'/'+$name) -Destination $dest -Force }
foreach($name in @('desktop-build.log','desktop-compile.log')) { Copy-Item -LiteralPath ($scratch+'/'+$name) -Destination ($dest+'/checks') -Force }
foreach($round in @('timeout-100','cancel-150','close-inflight')) {
 $source=if($round -eq 'timeout-100') {$scratch} else {$scratch+'/'+$round}
 New-Item -ItemType Directory -Path ($dest+'/launches/'+$round) -Force | Out-Null
 foreach($name in @('desktop.args','desktop.stdout.log','desktop.stderr.log','desktop-launch.json','desktop-exit.json')) {Copy-Item -LiteralPath ($source+'/'+$name) -Destination ($dest+'/launches/'+$round) -Force}
}
$records=@()
foreach($name in @('branch-full','branch-buildSrc','branch-image','branch-runtime','main-targeted','main-full','main-buildSrc','main-image','main-runtime')) {
 if(!(Test-Path -LiteralPath ($scratch+'/'+$name+'.json'))) {continue}
 $record=Get-Content -LiteralPath ($scratch+'/'+$name+'.json') -Raw | ConvertFrom-Json
 $records+=$record
 foreach($suffix in @('.json','.log','-driver.log')) {
  if(Test-Path -LiteralPath ($scratch+'/'+$name+$suffix)) {Copy-Item -LiteralPath ($scratch+'/'+$name+$suffix) -Destination ($dest+'/checks') -Force}
 }
 if($record.xmlDirectory) {
  $xmlDir=$scratch+'/'+$record.xmlDirectory
  $files=@(Get-ChildItem -LiteralPath $xmlDir -Filter 'TEST-*.xml' | Sort-Object Name)
  $manifest=@($files | ForEach-Object {[ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
  $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath ($dest+'/checks/'+$name+'-xml-manifest.json') -Encoding utf8
  foreach($file in $files) {
   if($name -match 'buildSrc|targeted' -or $file.Name -match 'SchemaMetadataSearchDialogTest|SchemaMetadataSearchTest|SqlExecutionControlTest') {
    Copy-Item -LiteralPath $file.FullName -Destination ($dest+'/checks/'+$name+'-'+$file.Name) -Force
   }
  }
 }
}
$all=@(Get-ChildItem -LiteralPath $dest -Recurse -File | Where-Object {$_.Name -ne 'evidence-manifest.json'} | Sort-Object FullName | ForEach-Object {
 [ordered]@{path=[IO.Path]::GetRelativePath($dest,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
$all | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath ($dest+'/evidence-manifest.json') -Encoding utf8
$summary=[ordered]@{baseline='a4dc88717ee6617e8d0e00e2721729e360483a24';branch='codex/discovery-native-acceptance';productSourceChanged=$false;generatedAt=(Get-Date).ToString('o');scratch=$scratch;records=$records;desktop=[ordered]@{probeSource='MetadataCancellationDesktopProbe.java';rounds=@('timeout-100','cancel-150','close-inflight');nativeInput='node_repl @oai/sky';captureFiles=@($all | Where-Object {$_.path -like 'desktop/*'}).Count;realConnections=0;allOwnedProcessesExited=$true;limitations=@('First cancel attempt occurred after timeout','Immediate accessibility tree can lag screenshots','End did not select menu item; subsequent screenshot click did','AppShell downstream actions not covered','Closing fixture releases synthetic latches; not proof of real driver interruption','Process scale only; no OS setting change')};evidenceFiles=$all.Count;externalAcceptance='pending'}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath ($Repository+'/docs/superpowers/verification/2026-09-27-discovery-native-acceptance-results.json') -Encoding utf8
@{records=$records.Count;evidenceFiles=$all.Count;captures=$summary.desktop.captureFiles} | ConvertTo-Json
