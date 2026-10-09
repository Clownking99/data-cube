$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$run = Join-Path $PSScriptRoot '009-p1a-frozen-targeted'
$snapshot = Join-Path $PSScriptRoot 'final-freeze'
if (Test-Path -LiteralPath $snapshot) { throw 'Fresh freeze required' }
$inputs = @(Get-Content -LiteralPath (Join-Path $run 'inputs-after.json') -Raw | ConvertFrom-Json)
$before = @(Get-Content -LiteralPath (Join-Path $run 'inputs-before.json') -Raw | ConvertFrom-Json)
if (($inputs | ConvertTo-Json -Depth 5 -Compress) -ne ($before | ConvertTo-Json -Depth 5 -Compress)) { throw 'Run inputs changed' }
foreach ($item in $inputs) {
    $file = Join-Path $repo $item.path
    if ((Get-FileHash -LiteralPath $file).Hash -ne $item.sha256 -or (Get-Item -LiteralPath $file).Length -ne $item.length) { throw "Not the verified final input: $($item.path)" }
}
$summary = Get-Content -LiteralPath (Join-Path $run 'test-summary.json') -Raw | ConvertFrom-Json
$exit = Get-Content -LiteralPath (Join-Path $run 'exit.json') -Raw | ConvertFrom-Json
if ($exit.exitCode -ne 0 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.tests -ne 75 -or $summary.skipped -ne 1) { throw 'Unexpected final test result' }
New-Item -ItemType Directory -Path (Join-Path $snapshot 'sources') | Out-Null
$paths = @(& rg --files -g '!**/.testagent/**' src/com/datacube/redis test/com/datacube/redis)
foreach ($path in $paths) {
    $target = Join-Path (Join-Path $snapshot 'sources') $path
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo $path) -Destination $target
}
$command = Get-Content -LiteralPath (Join-Path $run 'command.json') -Raw | ConvertFrom-Json
Copy-Item -LiteralPath (Join-Path $command.owned 'build/main/classes/java/main/com/datacube/redis') -Destination (Join-Path $snapshot 'classes-main') -Recurse
Copy-Item -LiteralPath (Join-Path $command.owned 'build/main/classes/java/test/com/datacube/redis') -Destination (Join-Path $snapshot 'classes-test') -Recurse
Copy-Item -LiteralPath (Join-Path $repo 'docs/superpowers/verification/2026-10-09-g10-redis-worker.md') -Destination (Join-Path $snapshot 'worker-report.md')
@{ utc=[datetime]::UtcNow.ToString('o'); head=(& git -C $repo rev-parse HEAD); branch=(& git -C $repo branch --show-current); verifiedInputCount=$inputs.Count; stage='P1a only'; run='009-p1a-frozen-targeted'; total=$summary.tests; passed=($summary.tests-$summary.skipped); skipped=$summary.skipped; productWritesStopped=$true; committed=$false } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $snapshot 'binding.json') -Encoding utf8
$files = @(& rg --files --hidden --no-ignore -g '!**/.testagent/**' -g '!raw-manifest.json' $PSScriptRoot | Sort-Object)
$entries = @(foreach ($path in $files) {
    $absolute = (Resolve-Path -LiteralPath $path).Path
    @{ path=[IO.Path]::GetRelativePath($repo,$absolute).Replace('\','/'); length=(Get-Item -LiteralPath $absolute).Length; sha256=(Get-FileHash -LiteralPath $absolute).Hash }
})
@{ root=$repo; createdUtc=[datetime]::UtcNow.ToString('o'); files=$entries; note='Immutable P1a evidence plus frozen sources/classes/report. Manifest excludes itself.' } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'raw-manifest.json') -Encoding utf8
$verified = 0
foreach ($entry in $entries) {
    $file=Join-Path $repo $entry.path
    if ((Get-FileHash -LiteralPath $file).Hash -ne $entry.sha256 -or (Get-Item -LiteralPath $file).Length -ne $entry.length) { throw 'Frozen evidence mismatch' }
    $verified++
}
@{ files=$verified; rawManifestSha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'raw-manifest.json')).Hash; inputs=$inputs.Count; tests=$summary.tests; passed=($summary.tests-$summary.skipped); skipped=$summary.skipped } | ConvertTo-Json
