$ErrorActionPreference='Stop'
$config=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'config.json')|ConvertFrom-Json -AsHashtable
$manifest=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'input-manifest.json')|ConvertFrom-Json -AsHashtable
foreach($item in $manifest.files){if(([IO.FileInfo]$item.path).Length -ne $item.length -or (Get-FileHash -LiteralPath $item.path -Algorithm SHA256).Hash -ne $item.sha256){throw 'DIAGNOSTIC_INPUT_CHANGED'}}
$module=Import-Module (Join-Path $PSScriptRoot 'VerificationCore.diagnostic.psm1') -PassThru -Force
& $module {Initialize-OwnedJob}
$scope=Get-Content -Raw -LiteralPath $config.originalScope|ConvertFrom-Json -AsHashtable -DateKind String
$request=Get-Content -Raw -LiteralPath $config.originalRequest|ConvertFrom-Json -AsHashtable -DateKind String
if(Test-Path -LiteralPath $config.output){throw 'DIAGNOSTIC_COLLISION'}
$null=[IO.Directory]::CreateDirectory($config.owned)
$null=[IO.Directory]::CreateDirectory((Join-Path $config.owned 'tools'))
foreach($relative in $scope.tools.Keys){$tool=$scope.tools[$relative];$destination=Join-Path $config.owned ('tools/'+$relative);$null=[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination));[IO.File]::Copy($tool.frozen,$destination,$false);$tool.frozen=$destination}
$scope.owned=$config.owned
$scope|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $config.owned 'diagnostic-scope.json') -Encoding utf8NoBOM
# Original argv and environment, frozen image and compiled probe; fresh evidence cwd only.
$result=Invoke-OwnedProcess -Scope $scope -Name 'redis-linked' -Exe $request.exe -Role $request.role -Argv $request.argv -Cwd $scope.owned -HostScript $scope.tools['OwnedProcessHost.ps1'].frozen -DeadlineMs $request.deadlineMs -SettleMs $request.settleMs -StreamCap $request.streamCap
@{diagnosticOnly=$true;formalAcceptance=$false;result=$result}|ConvertTo-Json -Depth 24|Set-Content -LiteralPath (Join-Path $config.owned 'diagnostic-result.json') -Encoding utf8NoBOM
if($result.status -ne 'passed'){exit 1}
