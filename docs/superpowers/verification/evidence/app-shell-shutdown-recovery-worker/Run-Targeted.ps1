param([Parameter(Mandatory=$true)][string]$Run)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
Set-Location -LiteralPath $root
$destination = Join-Path $PSScriptRoot $Run
if (Test-Path -LiteralPath $destination) { throw 'Run evidence already exists' }
New-Item -ItemType Directory -Path $destination | Out-Null
$profile = Join-Path ([IO.Path]::GetTempPath()) ('datacube-shell-recovery-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $profile | Out-Null
Get-ChildItem Env: | Where-Object { $_.Name.ToUpper().Contains('LIVE') -or $_.Name.ToUpper().Contains('ORACLE') -or $_.Name.ToUpper().StartsWith('PG') -or $_.Name.ToUpper().Contains('REDIS') -or $_.Name.ToUpper().StartsWith('DATACUBE_') -or $_.Name -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS') } | ForEach-Object { Remove-Item ('Env:' + $_.Name) }
$env:JAVA_HOME = 'D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$env:PATH = $env:JAVA_HOME + '/bin;' + $env:PATH
$suites = @('com.datacube.fx.AppShellShutdownRecoveryTest','com.datacube.fx.AppShellTest','com.datacube.fx.SqlScriptFileEntryTest')
$arguments = @('--offline','--no-daemon','--max-workers=1','--rerun-tasks','--console=plain',('-Ddatacube.acceptance.root=' + $profile),'-I','docs/superpowers/verification/evidence/sol-p0-p2/isolation.init.gradle','test')
foreach ($suite in $suites) { $arguments += @('--tests',$suite); $xmlPath = 'build/test-results/test/TEST-' + $suite + '.xml'; if (Test-Path -LiteralPath $xmlPath) { Remove-Item -LiteralPath $xmlPath } }
$hashes = @('src/com/datacube/fx/AppShell.java','test/com/datacube/fx/AppShellShutdownRecoveryTest.java','test/com/datacube/fx/AppShellTest.java') | ForEach-Object { @{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash} }
$hashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'source-hashes.json')
$started = [DateTime]::UtcNow
@{ javaHome=$env:JAVA_HOME; profile=$profile; arguments=$arguments; started=$started.ToString('o') } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $destination 'parameters.json')
& ./gradlew.bat @arguments *> (Join-Path $destination 'raw.log')
$result = $LASTEXITCODE
$testsExecuted = [bool](Select-String -LiteralPath (Join-Path $destination 'raw.log') -Pattern '^> Task :test(?:\s|$)')
foreach ($suite in $suites) { $xmlPath = 'build/test-results/test/TEST-' + $suite + '.xml'; if ($testsExecuted -and (Test-Path -LiteralPath $xmlPath) -and (Get-Item -LiteralPath $xmlPath).LastWriteTimeUtc -ge $started) { Copy-Item -LiteralPath $xmlPath -Destination $destination } }
@{ exit=$result; testsExecuted=$testsExecuted; finished=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'result.json')
Get-Content -LiteralPath (Join-Path $destination 'raw.log') | Select-Object -Last 45
exit $result
