$ErrorActionPreference='Stop'
$runNames=@('first-sixcases','reviewed-sixcases','final-focused')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$runs=foreach($name in $runNames){
    $directory=Join-Path $PSScriptRoot $name
    $p=Get-Content -Raw -LiteralPath (Join-Path $directory 'parameters.json')|ConvertFrom-Json
    $r=Get-Content -Raw -LiteralPath (Join-Path $directory 'result.json')|ConvertFrom-Json
    $executed=[bool](Select-String -LiteralPath (Join-Path $directory 'raw.log') -Pattern '^> Task :test(?:\s|$)')
    $suites=@()
    if($executed){foreach($xml in Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml'){
        if($xml.LastWriteTimeUtc -lt [DateTime]::Parse($p.started).ToUniversalTime()){throw 'Stale XML'}
        [xml]$s=Get-Content -Raw -LiteralPath $xml.FullName
        $suites+=@{name=$s.testsuite.name;tests=[int]$s.testsuite.tests;failures=[int]$s.testsuite.failures;errors=[int]$s.testsuite.errors;skipped=[int]$s.testsuite.skipped;sha256=(Get-FileHash -LiteralPath $xml.FullName -Algorithm SHA256).Hash}
    }}
    @{run=$name;exit=[int]$r.exit;testsExecuted=$executed;profile=$p.profile;suites=$suites}
}
$hashes=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'final-focused/source-hashes.json')|ConvertFrom-Json
foreach($h in $hashes){if((Get-FileHash -LiteralPath (Join-Path $root $h.path) -Algorithm SHA256).Hash -ne $h.sha256){throw 'Final source hash mismatch'}}
$profiles=@($runs|ForEach-Object{$_.profile})
$owned=@(Get-CimInstance Win32_Process -Filter "name='java.exe'"|Where-Object{$command=$_.CommandLine;@($profiles|Where-Object{$command -and $command.Contains($_)}).Count -gt 0}|Select-Object ProcessId,Name)
if($owned.Count -ne 0){throw 'Owned synthetic Java process still running'}
$manifest=@{checkedUtc=[DateTime]::UtcNow.ToString('o');finalRun='final-focused';runs=$runs;sourceHashesMatch=$true;ownedProfileJavaProcesses=$owned;productModified=$false;nativeDesktopVerified=$false;realDatabaseVerified=$false}
$manifest|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'manifest.json')
$manifest|ConvertTo-Json -Depth 8
