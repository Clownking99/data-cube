$ErrorActionPreference = 'Stop'
$runs = @('red-first','green-first','green-expanded','green-boundaries','green-final-boundaries','green-focused-final','green-reviewed')
$results = foreach ($run in $runs) {
    $directory = Join-Path $PSScriptRoot $run
    $parameters = Get-Content -LiteralPath (Join-Path $directory 'parameters.json') -Raw | ConvertFrom-Json
    $result = Get-Content -LiteralPath (Join-Path $directory 'result.json') -Raw | ConvertFrom-Json
    $testsExecuted = [bool](Select-String -LiteralPath (Join-Path $directory 'raw.log') -Pattern '^> Task :test(?:\s|$)')
    $suites = @()
    if ($testsExecuted) {
        foreach ($xml in Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml') {
            [xml]$parsed = Get-Content -LiteralPath $xml.FullName -Raw
            if ($xml.LastWriteTimeUtc -lt [DateTime]::Parse($parameters.started).ToUniversalTime()) { throw ('Stale XML: ' + $xml.Name) }
            $suites += @{name=$parsed.testsuite.name; tests=[int]$parsed.testsuite.tests; failures=[int]$parsed.testsuite.failures; errors=[int]$parsed.testsuite.errors; skipped=[int]$parsed.testsuite.skipped; sha256=(Get-FileHash -LiteralPath $xml.FullName -Algorithm SHA256).Hash}
        }
    }
    @{run=$run; exit=[int]$result.exit; testsExecuted=$testsExecuted; suites=$suites; profile=$parameters.profile; staleXmlExcluded=($run -eq 'green-final-boundaries')}
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$expected = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'green-reviewed/source-hashes.json') -Raw | ConvertFrom-Json
foreach ($source in $expected) { if ((Get-FileHash -LiteralPath (Join-Path $root $source.path) -Algorithm SHA256).Hash -ne $source.sha256) { throw ('Source changed: ' + $source.path) } }
$profiles = @($results | ForEach-Object { $_.profile })
$processes = @(Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object { $command = $_.CommandLine; @($profiles | Where-Object { $command -and $command.Contains($_) }).Count -gt 0 } | Select-Object ProcessId,Name)
if ($processes.Count -ne 0) { throw 'Owned synthetic-profile Java process still running' }
$manifest = @{checkedUtc=[DateTime]::UtcNow.ToString('o'); runs=$results; sourceHashesMatchFinalRun=$true; ownedProfileJavaProcesses=$processes; desktopFixtureLaunched=$false; nativeCloseVerified=$false; physical15SecondPathVerified=$false; managedBlockedResourceClose=1; managedFxFinalizer=1; failureCleanupCachedConnectionClose=1; realDatabaseAccess=$false}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'manifest.json')
$manifest | ConvertTo-Json -Depth 8
