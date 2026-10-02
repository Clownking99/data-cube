param([Parameter(Mandatory=$true)][string]$Run)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
Set-Location -LiteralPath $root
$destination = Join-Path $PSScriptRoot $Run
if (Test-Path -LiteralPath $destination) { throw 'Run evidence already exists' }
New-Item -ItemType Directory -Path $destination | Out-Null
$profile = Join-Path ([IO.Path]::GetTempPath()) ('datacube-schema-stability-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $profile | Out-Null
Get-ChildItem Env: | Where-Object { $_.Name.ToUpper().Contains('LIVE') -or $_.Name.ToUpper().Contains('ORACLE') -or $_.Name.ToUpper().StartsWith('PG') -or $_.Name.ToUpper().Contains('REDIS') -or $_.Name -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS') } | ForEach-Object { Remove-Item ('Env:' + $_.Name) }
$env:JAVA_HOME = 'D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$env:PATH = $env:JAVA_HOME + '/bin;' + $env:PATH
$arguments = @('--offline','--no-daemon','--max-workers=1','--rerun-tasks','--console=plain',('-Ddatacube.acceptance.root=' + $profile),'-I','docs/superpowers/verification/evidence/sol-p0-p2/isolation.init.gradle','test','--tests','com.datacube.service.SchemaDiffServiceTest','--tests','com.datacube.service.SchemaDiffConcurrencyTest')
@{ javaHome=$env:JAVA_HOME; profile=$profile; arguments=$arguments; started=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $destination 'parameters.json')
& ./gradlew.bat @arguments *> (Join-Path $destination 'raw.log')
$result = $LASTEXITCODE
Copy-Item -LiteralPath 'build/test-results/test/TEST-com.datacube.service.SchemaDiffServiceTest.xml' -Destination $destination
Copy-Item -LiteralPath 'build/test-results/test/TEST-com.datacube.service.SchemaDiffConcurrencyTest.xml' -Destination $destination
@{ exit=$result; finished=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'result.json')
Get-Content -LiteralPath (Join-Path $destination 'raw.log') | Select-Object -Last 45
exit $result
