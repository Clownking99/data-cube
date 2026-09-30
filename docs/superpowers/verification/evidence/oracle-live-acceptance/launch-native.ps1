param([string]$Repository,[string]$Scratch,[string]$Fixture,[string]$Name='native-visible')
$ErrorActionPreference='Stop'
foreach($required35 in @('DC_ORACLE_HOST','DC_ORACLE_PORT','DC_ORACLE_SERVICE','DC_ORACLE_USER','DC_ORACLE_PASSWORD')) {
 if(![Environment]::GetEnvironmentVariable($required35)){throw 'Missing Oracle native acceptance environment'}
}
$profile35=Join-Path $Scratch ('native-profile-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $profile35 -ErrorAction Stop | Out-Null
$log35=Join-Path $Scratch ($Name+'.log');$err35=Join-Path $Scratch ($Name+'-error.log')
if((Test-Path -LiteralPath $log35) -or (Test-Path -LiteralPath $err35)){throw 'Refusing to replace earlier native evidence'}
$options35=@('--add-modules','com.datacube','--enable-native-access=com.datacube',
 '--add-exports','com.datacube/com.datacube.fx=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.config=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.provider.oracle=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.provider.jdbc=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.service=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.spi=ALL-UNNAMED',
 '--add-exports','com.datacube/com.datacube.spi.model=ALL-UNNAMED',
 '--add-opens','com.datacube/com.datacube.service=ALL-UNNAMED',
 '--add-opens','com.datacube/com.datacube.fx=ALL-UNNAMED',
 '-cp',(Join-Path $Scratch 'classes'),'OracleDesktopAcceptance',$profile35,$Fixture)
$quoted35=($options35 | ForEach-Object {'"'+$_.Replace('"','\"')+'"'}) -join ' '
# A visible interactive window is necessary for the authorized native desktop acceptance.
$process35=Start-Process -FilePath (Join-Path $Repository 'build/jpackage/DataCube/runtime/bin/java.exe') -ArgumentList $quoted35 -WindowStyle Normal -RedirectStandardOutput $log35 -RedirectStandardError $err35 -PassThru
[ordered]@{name=$Name;head=(git -C $Repository rev-parse HEAD);startedAt=(Get-Date).ToString('o');pid=$process35.Id;fixture=$Fixture;profile=$profile35;log=($Name+'.log');credentialsPersisted=$false;fixtureSetup='Real packaged AppShell; fresh isolated profile; in-memory connection configurations; fixed own schema/table tree nodes; native input uses Computer Use only';runtimeModulesSha256=(Get-FileHash -LiteralPath (Join-Path $Repository 'build/jpackage/DataCube/runtime/lib/modules')).Hash} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Scratch ($Name+'.json')) -Encoding utf8
Write-Output ('pid='+$process35.Id+' isolatedNativeProfile='+$profile35)
