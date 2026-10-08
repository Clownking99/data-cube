$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$freezeRoot = Join-Path $PSScriptRoot 'p1a-final-freeze-009'
if (Test-Path -LiteralPath $freezeRoot) { throw 'Final freeze must be new' }
$sourceFiles = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'p1a-009-targeted/source-at-run/manifest.json') -Raw | ConvertFrom-Json).path
$runManifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'p1a-009-targeted/source-at-run/manifest.json') -Raw | ConvertFrom-Json
$binding = foreach ($entry in $runManifest) {
    $sha = (Get-FileHash -LiteralPath (Join-Path $repoRoot $entry.path) -Algorithm SHA256).Hash
    if ($sha -ne $entry.sha256) { throw ('Source changed after latest run: ' + $entry.path) }
    [pscustomobject]@{path=$entry.path; sha256=$sha; matchesRun009=$true}
}
New-Item -ItemType Directory -Path $freezeRoot | Out-Null
$files = @($sourceFiles) + @('docs/superpowers/verification/2026-10-08-g9-table-export-worker.md',
    'docs/superpowers/verification/evidence/g9-table-export-20261008-worker/Run-P1a.ps1')
$manifest = foreach ($file in $files) {
    $source = Join-Path $repoRoot $file
    $destination = Join-Path $freezeRoot $file
    New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
    $tree = & git -C $repoRoot ls-tree HEAD -- $file
    if ($LASTEXITCODE -ne 0) { throw 'HEAD blob lookup failed' }
    $blob = if ($tree -match '^\d+\s+blob\s+([0-9a-f]+)\s+') { $Matches[1] } else { $null }
    [pscustomobject]@{path=$file; bytes=(Get-Item -LiteralPath $source).Length;
        sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash; headBlob=$blob}
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $freezeRoot 'manifest.json') -Encoding utf8
$head = & git -C $repoRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'HEAD failed' }
$branch = & git -C $repoRoot branch --show-current
if ($LASTEXITCODE -ne 0) { throw 'Branch failed' }
$status = @(& git -C $repoRoot status --short -- . ':(exclude).testagent' ':(exclude).testagent/**')
$statusExit = $LASTEXITCODE
$check = @(& git -C $repoRoot diff --check -- . ':(exclude).testagent' ':(exclude).testagent/**')
$checkExit = $LASTEXITCODE
$patch = @(& git -C $repoRoot diff --binary -- $sourceFiles)
$patchExit = $LASTEXITCODE
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'p1a-final-product-test.patch'), ($patch -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
$whitespace = foreach ($file in $files) {
    $line = 0
    foreach ($text in [IO.File]::ReadAllLines((Join-Path $repoRoot $file))) {
        $line++
        if ($text -match '\s+$') { [pscustomobject]@{path=$file; line=$line} }
    }
}
if ($statusExit -ne 0 -or $checkExit -ne 0 -or $patchExit -ne 0 -or @($whitespace).Count -ne 0) { throw 'Final source checks failed' }
$suites = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'p1a-009-suites.json') -Raw | ConvertFrom-Json
[ordered]@{utc=[datetime]::UtcNow.ToString('o'); root=$repoRoot; head=$head; branch=$branch;
    latestRun='p1a-009-targeted'; tests=[int](($suites | Measure-Object -Property tests -Sum).Sum);
    failures=[int](($suites | Measure-Object -Property failures -Sum).Sum);
    errors=[int](($suites | Measure-Object -Property errors -Sum).Sum);
    skipped=[int](($suites | Measure-Object -Property skipped -Sum).Sum);
    suites=@($suites).Count; sourceBinding=$binding; statusExit=$statusExit; status=$status;
    diffCheckExit=$checkExit; diffCheckOutput=$check; patchExit=$patchExit; trailingWhitespace=@($whitespace);
    stageCommitPush='not performed'; fullBuildSrcTestImage='not run'; nextState='P1a frozen, stop for independent coordination review'
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'p1a-final-checks.json') -Encoding utf8
$artifactFiles = @(& rg --files --hidden --glob '!.testagent' --glob '!**/.testagent/**' -- $PSScriptRoot)
if ($LASTEXITCODE -ne 0) { throw 'Evidence inventory failed' }
$artifactManifest = foreach ($file in ($artifactFiles | Sort-Object)) {
    if ([IO.Path]::GetFileName($file) -eq 'p1a-artifact-manifest.json') { continue }
    $resolved = (Resolve-Path -LiteralPath $file).Path
    if (!$resolved.StartsWith($PSScriptRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Artifact escaped owned evidence root' }
    [pscustomobject]@{path=$resolved.Substring($PSScriptRoot.Length + 1).Replace('\','/');
        bytes=(Get-Item -LiteralPath $resolved).Length; sha256=(Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash}
}
$artifactManifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'p1a-artifact-manifest.json') -Encoding utf8
Write-Output ('Frozen files: ' + $manifest.Count + '; source/run bindings: ' + @($binding).Count + '; evidence artifacts: ' + @($artifactManifest).Count)
Write-Output ('Latest tests: ' + (($suites | Measure-Object -Property tests -Sum).Sum) + '; HEAD: ' + $head)
