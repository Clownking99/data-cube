param([string]$Repository,[string]$Scratch,[string]$Fixture,[string]$Mode='acceptance',[string]$Name='')
$ErrorActionPreference='Stop'
foreach($required35 in @('DC_ORACLE_HOST','DC_ORACLE_PORT','DC_ORACLE_SERVICE','DC_ORACLE_USER','DC_ORACLE_PASSWORD')) {
 if(![Environment]::GetEnvironmentVariable($required35)){throw 'Missing Oracle acceptance environment'}
}
if($Mode -notin @('preflight','acceptance','cancel-diagnostic')){throw 'Invalid acceptance mode'}
if(!$Name){$Name=$Mode}
if($Name -notmatch '^[a-z0-9-]+$'){throw 'Invalid evidence name'}
$profile35=Join-Path $Scratch ($Name+'-profile-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $profile35 -ErrorAction Stop | Out-Null
$log35=Join-Path $Scratch ($Name+'.log')
if(Test-Path -LiteralPath $log35){throw 'Refusing to replace earlier acceptance evidence'}
$runtime35=Join-Path $Repository 'build/jpackage/DataCube/runtime'
$options35=@('--add-modules','com.datacube','--enable-native-access=com.datacube',
 '--add-exports','com.datacube/com.datacube.config=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.provider.oracle=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.provider.jdbc=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.service=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.spi=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.spi.model=ALL-UNNAMED',
 '--add-opens','com.datacube/com.datacube.service=ALL-UNNAMED',
 '-cp',(Join-Path $Scratch 'classes'),'OracleLiveAcceptance',$profile35,$Fixture,$Mode)
$started35=(Get-Date).ToString('o')
& (Join-Path $runtime35 'bin/java.exe') @options35 *> $log35
$exit35=$LASTEXITCODE
$raw35=Get-Content -LiteralPath $log35 -Raw
if($raw35.Contains($env:DC_ORACLE_PASSWORD)){throw 'Credential detected in acceptance log; no evidence will be displayed or archived'}
$records35=@(Get-Content -LiteralPath $log35 | Where-Object {$_ -match '^\{'} | ForEach-Object {$_ | ConvertFrom-Json})
$summary35=$records35 | Where-Object summary | Select-Object -Last 1
$result35=[ordered]@{name=$Name;mode=$Mode;head=(git -C $Repository rev-parse HEAD);startedAt=$started35;completedAt=(Get-Date).ToString('o');exitCode=$exit35;fixture=$Fixture;profileIsolated=$true;log=($Name+'.log');logSha256=(Get-FileHash -LiteralPath $log35).Hash;checks=@($records35 | Where-Object check);summary=$summary35;runtimeModulesSha256=(Get-FileHash -LiteralPath (Join-Path $runtime35 'lib/modules')).Hash;boundary='User authorized Oracle target only; new unique table; INSERT/UPDATE only; no DELETE/TRUNCATE/DROP or existing business row queries; credentials supplied through transient process environment only'}
$result35 | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $Scratch ($Name+'.json')) -Encoding utf8
$result35 | ConvertTo-Json -Depth 9
if($exit35 -ne 0){throw 'Oracle acceptance failed; redacted evidence preserved'}
