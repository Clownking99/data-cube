param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$package=$PSScriptRoot
$repo='D:/Projects/朝花夕拾'
$python='C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$runArgs=@('-I','-S','-B',(Join-Path $package 'run-sequence.py'))
foreach($name in @('operator-command.json','sequence.log','shell-exit.json')){if(Test-Path -LiteralPath (Join-Path $package $name)){throw 'operator output collision'}}
@{schema='root-p3-command/v1';executable=$python;argv=$runArgs;cwd=$repo;soleGradleOwner='root'}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $package 'operator-command.json') -Encoding utf8NoBOM
& $python @runArgs *> (Join-Path $package 'sequence.log')
$runExit=$LASTEXITCODE
@{schema='root-p3-shell-exit/v1';actualPythonExitCode=$runExit;soleGradleOwner='root'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $package 'shell-exit.json') -Encoding utf8NoBOM
exit $runExit
