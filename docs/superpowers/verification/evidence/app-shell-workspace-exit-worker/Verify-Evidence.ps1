$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
Set-Location -LiteralPath $root
$runs=@('first-workspace','corrected-fourcases','final-related-focused')
$profiles=@()
$summary=foreach($run in $runs){
 $folder=Join-Path $PSScriptRoot $run
 $parameters=Get-Content -Raw -LiteralPath (Join-Path $folder 'parameters.json')|ConvertFrom-Json
 $result=Get-Content -Raw -LiteralPath (Join-Path $folder 'result.json')|ConvertFrom-Json
 $profiles+= $parameters.profile
 $actualTask=[bool](Select-String -LiteralPath (Join-Path $folder 'raw.log') -Pattern '^> Task :test(?:\s|$)')
 $suites=@(Get-ChildItem -LiteralPath $folder -Filter 'TEST-*.xml'|ForEach-Object{
  if(-not $actualTask -or $_.LastWriteTimeUtc -lt [DateTime]$parameters.started){throw 'XML is not from this execution'}
  [xml]$xml=Get-Content -Raw -LiteralPath $_.FullName
  @{name=$xml.testsuite.name;tests=[int]$xml.testsuite.tests;failures=[int]$xml.testsuite.failures;errors=[int]$xml.testsuite.errors;skipped=[int]$xml.testsuite.skipped;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
 })
 @{run=$run;exit=$result.exit;actualTestTask=$actualTask;profile=$parameters.profile;suites=$suites}
}
$finalHashes=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'final-related-focused/source-hashes.json')|ConvertFrom-Json
foreach($entry in $finalHashes){if((Get-FileHash -LiteralPath $entry.path -Algorithm SHA256).Hash -ne $entry.sha256){throw ('Source changed after run: '+$entry.path)}}
$owned=@(foreach($profile in $profiles){
 $profileName=Split-Path -Leaf $profile
 if($profileName -notmatch '^datacube-shell-workspace-worker-[0-9a-f-]+$'){throw 'Unexpected owned process namespace'}
 Get-CimInstance Win32_Process -Filter ("(Name='java.exe' OR Name='javaw.exe') AND CommandLine LIKE '%"+$profileName+"%'") | Select-Object ProcessId
})
if($owned.Count -ne 0){throw 'Owned Java process remains'}
$artifacts=@(foreach($run in $runs){Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot $run) -File|ForEach-Object{@{path=$run+'/'+$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}}})
$manifest=@{checkedUtc=[DateTime]::UtcNow.ToString('o');baseline='242946facc6c4c648cf1550d995aa9237b6544d4';finalRun='final-related-focused';runs=$summary;sourceHashesMatch=$true;ownedProfileJavaProcesses=$owned;artifacts=$artifacts;productModified=$false;evidenceLevel='synthetic FX production Alert; no native/launcher/database evidence';firstFailure='fixture .bin counting; actual draft suffix .draft';correctedProductionAlerts=5}
if(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'manifest.json')){throw 'Manifest already exists'}
$manifest|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'manifest.json')
$manifest|ConvertTo-Json -Depth 10
