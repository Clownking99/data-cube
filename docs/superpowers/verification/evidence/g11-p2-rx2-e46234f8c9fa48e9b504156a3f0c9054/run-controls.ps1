param([Parameter(Mandatory)][string]$Package)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$spec=Get-Content -LiteralPath (Join-Path $Package 'spec.json') -Raw|ConvertFrom-Json
if($Package -ne $spec.package){throw 'OPERATOR_PACKAGE_MISMATCH'}
$controlArgs=@('-I','-S','-B',(Join-Path $Package 'controller.py'))
$archiveArgs=@('-I','-S','-B',(Join-Path $Package 'archive-controls.py'))
@{executable=$spec.python;controlArgv=$controlArgs;archiveArgv=$archiveArgs;engineering=$false}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $Package 'operator-command.json') -Encoding utf8NoBOM
& $spec.python @controlArgs *> (Join-Path $Package 'controller.log');$controlExit=$LASTEXITCODE
& $spec.python @archiveArgs *> (Join-Path $Package 'archive.log');$archiveExit=$LASTEXITCODE
@{schema='controls-operator/v1';controlActualExit=$controlExit;archiveActualExit=$archiveExit;engineering=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Package 'operator-result.json') -Encoding utf8NoBOM
if($controlExit -ne 0){exit $controlExit}
exit $archiveExit