$ErrorActionPreference='Stop'
$runNames=@('diagnostic-shell-routing-red','diagnostic-initializing-red','diagnostic-released-green','diagnostic-initializing-clean-red','diagnostic-released-clean-green','correction-focused')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$runs=foreach($name in $runNames){
 $directory=Join-Path $PSScriptRoot $name
 $p=Get-Content -Raw -LiteralPath (Join-Path $directory 'parameters.json')|ConvertFrom-Json
 $r=Get-Content -Raw -LiteralPath (Join-Path $directory 'result.json')|ConvertFrom-Json
 $task=[bool](Select-String -LiteralPath (Join-Path $directory 'raw.log') -Pattern '^> Task :test(?:\s|$)')
 $suites=@()
 foreach($xml in Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml'){
  if(!$task -or $xml.LastWriteTimeUtc -lt [DateTime]::Parse($p.started).ToUniversalTime()){throw 'Invalid XML provenance'}
  [xml]$s=Get-Content -Raw -LiteralPath $xml.FullName
  $suites+=@{name=$s.testsuite.name;tests=[int]$s.testsuite.tests;failures=[int]$s.testsuite.failures;errors=[int]$s.testsuite.errors;skipped=[int]$s.testsuite.skipped;sha256=(Get-FileHash -LiteralPath $xml.FullName -Algorithm SHA256).Hash}
 }
 @{run=$name;exit=[int]$r.exit;testTaskExecuted=$task;actualTestsExecuted=($suites.Count -gt 0);profile=$p.profile;suites=$suites}
}
$hashes=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'correction-focused/source-hashes.json')|ConvertFrom-Json
foreach($h in $hashes){if((Get-FileHash -LiteralPath (Join-Path $root $h.path) -Algorithm SHA256).Hash -ne $h.sha256){throw 'Final source hash mismatch'}}
$old=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'manifest.json')|ConvertFrom-Json
$profiles=@($runs|ForEach-Object{$_.profile})+@($old.runs|ForEach-Object{$_.profile})
$owned=@(Get-CimInstance Win32_Process -Filter "name='java.exe'"|Where-Object{$command=$_.CommandLine;@($profiles|Where-Object{$command -and $command.Contains($_)}).Count -gt 0}|Select-Object ProcessId,Name)
if($owned.Count -ne 0){throw 'Owned profile Java process still running'}
$manifestPath='docs/superpowers/verification/evidence/app-shell-grid-exit-worker/manifest.json'
Push-Location -LiteralPath $root
try{git diff --quiet e7f55b726cc7990367200c0b507eb57909d44def -- $manifestPath;if($LASTEXITCODE -ne 0){throw 'Original manifest changed'}}
finally{Pop-Location}
@{checkedUtc=[DateTime]::UtcNow.ToString('o');sourceCommitBeforeCorrection='e7f55b726cc7990367200c0b507eb57909d44def';finalRun='correction-focused';runs=$runs;sourceHashesMatch=$true;ownedProfileJavaProcesses=$owned;originalManifestUnchanged=$true;originalManifestSha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'manifest.json')).Hash;productModified=$false;historicalFullModeRecorded=$false}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'manifest-correction.json')
Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'manifest-correction.json')
