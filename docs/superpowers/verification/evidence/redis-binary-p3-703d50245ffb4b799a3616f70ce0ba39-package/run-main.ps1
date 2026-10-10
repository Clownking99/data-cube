$ErrorActionPreference='Stop'
$package=$PSScriptRoot
$python='C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$manifest=Join-Path $package 'entry-manifest.json'
$manifestHash=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash
function Assert-Entry {
  if((Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash -ne $manifestHash){throw 'ENTRY_MANIFEST_CHANGED'}
  foreach($row in (Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json)) {
    $file=Join-Path $package $row.path
    if((Get-Item -LiteralPath $file).Length -ne $row.length -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $row.sha256){throw 'ENTRY_FILE_CHANGED'}
  }
}
Assert-Entry
$argv=@('-I','-S','-B',(Join-Path $package 'run-sequence.py'))
@{executable=$python;argv=$argv;cwd=(Get-Location).Path;entryManifestSha256=$manifestHash}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $package 'shell-command.json') -Encoding utf8NoBOM
& $python @argv 1> (Join-Path $package 'controller.stdout') 2> (Join-Path $package 'controller.stderr')
$actual=$LASTEXITCODE
$entryFailure=$null
try { Assert-Entry } catch { $entryFailure=$_.Exception.Message }
@{actualPythonExitCode=$actual;entryIdentityFailure=$entryFailure;gradleExecutor='root-only'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $package 'shell-exit.json') -Encoding utf8NoBOM
if($entryFailure){throw $entryFailure}
exit $actual
