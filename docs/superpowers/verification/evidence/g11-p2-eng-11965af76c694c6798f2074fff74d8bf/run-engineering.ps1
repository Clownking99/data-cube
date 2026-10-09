param([Parameter(Mandatory)][string]$Package)
$ErrorActionPreference='Stop'
$config=Get-Content -LiteralPath (Join-Path $Package 'config.json') -Raw|ConvertFrom-Json
$argv=@('-I','-S','-B',(Join-Path $Package 'controller.py'))
@{schema='engineering-operator-command/v1';executable=$config.python;argv=$argv;cwd=(Get-Location).Path}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $Package 'operator-command.json') -Encoding utf8NoBOM
& $config.python @argv *> (Join-Path $Package 'controller.log')
$actualExit=$LASTEXITCODE
@{schema='engineering-operator-result/v1';actualExitCode=$actualExit;gradleExecutor='worker-only'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Package 'operator-result.json') -Encoding utf8NoBOM
exit $actualExit
