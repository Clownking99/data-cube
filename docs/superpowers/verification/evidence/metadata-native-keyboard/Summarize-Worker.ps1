$ErrorActionPreference='Stop'
$launch=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'launch.json') -Raw | ConvertFrom-Json
$states=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'native-actions.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$raw=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'desktop-runtime.log') -Raw
$counts=[regex]::Matches($raw,'COUNTERS mockOpens=(\d+) mockCloses=(\d+) searches=(\d+) pages=(\d+) ddls=(\d+) writeAttempts=(\d+) executionAttempts=(\d+)')
$last=$counts[$counts.Count-1]
$closed=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'process-closed.json') -Raw | ConvertFrom-Json
$result=[ordered]@{
    head=$launch.head; compileExit=$launch.compileExit; gradleRuns=0; fixturePid=$launch.pid; fixtureExited=$closed.exited
    shutdownCompleted=$raw.Contains('SHUTDOWN_COMPLETED')
    counters=[ordered]@{opens=[int]$last.Groups[1].Value;closes=[int]$last.Groups[2].Value;searches=[int]$last.Groups[3].Value;pages=[int]$last.Groups[4].Value;ddls=[int]$last.Groups[5].Value;writes=[int]$last.Groups[6].Value;executions=[int]$last.Groups[7].Value}
    observedStates=$states.Count; savedImages=@(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object {$_.Extension -in @('.jpg','.jpeg','.png')}).Count
    dialogKeyEvents=[regex]::Matches($raw,'(?m)^DIALOG_KEY=').Count
    dialogTypedEvents=[regex]::Matches($raw,'(?m)^DIALOG_TYPED=').Count
    queryChanges=[regex]::Matches($raw,'(?m)^QUERY_CHANGE=').Count
    modeChanges=[regex]::Matches($raw,'(?m)^MODE_CHANGE=').Count
    resizeEvents=[regex]::Matches($raw,'(?m)^STATE event=(width|height)').Count
    typeTextAttempts=2; nativeTabAttempts=1; nativeEscAttempts=1; nativeResizeAttempts=1
    nativeInputPassed=$false; nativeKeyboardPassed=$false; invalidationWithPublishedResultPassed=$false; actualSmallerWindowPassed=$false
    productChanges=0; meaningfulFxRedGreenRequired=$false
    next='Parent independently reviews evidence and performs engineering validation; native acceptance remains incomplete'
}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'results.json') -Encoding utf8
if(!$result.fixtureExited -or !$result.shutdownCompleted -or $result.counters.opens -ne $result.counters.closes -or $result.counters.writes -ne 0 -or $result.counters.executions -ne 0){throw 'Fixture shutdown or safety evidence is incomplete'}
if($result.savedImages -ne ($states | ForEach-Object {$_.screenshots.Count} | Measure-Object -Sum).Sum){throw 'Returned screenshot count differs from saved images'}
$files=@(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object {$_.Name -ne 'raw-byte-manifest.json'})
$files | ForEach-Object {[ordered]@{name=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'raw-byte-manifest.json') -Encoding utf8
$result | ConvertTo-Json -Depth 5
