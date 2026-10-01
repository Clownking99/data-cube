param([ValidateSet('shell','grid')][string]$Kind='shell',[switch]$SeedQuery,[switch]$BlockWrite)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$runtime=(Resolve-Path (Join-Path $repo 'build/jpackage/DataCube/runtime')).Path
$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('datacube-sol-native-'+[guid]::NewGuid().ToString('N'))
$classes=Join-Path $temporary 'classes'; $profile=Join-Path $temporary "sol-$Kind-profile"
New-Item -ItemType Directory -Path $classes,$profile | Out-Null
$packages=@('com.datacube.fx','com.datacube.fx.task','com.datacube.config','com.datacube.service','com.datacube.spi','com.datacube.spi.model','com.datacube.provider.postgres','com.datacube.provider.jdbc')
$exports=$packages|ForEach-Object {"--add-exports=com.datacube/$_=ALL-UNNAMED"}
$sources=@(if($Kind -eq 'shell'){Join-Path $PSScriptRoot 'SolShellDesktopProbe.java'}else{@('SolGridDesktopProbe.java','SolGridSaveBoundary.java','SolRowJdbcBoundary.java')|ForEach-Object {Join-Path $PSScriptRoot $_}})
& "$jdk/bin/javac.exe" --system $runtime --add-modules com.datacube @exports -d $classes @sources *> (Join-Path $temporary 'compile.log')
if($LASTEXITCODE -ne 0){throw "Compile failed: $temporary/compile.log"}
$launcher=if($Kind -eq 'shell'){'SolShellDesktopProbe$Launcher'}else{'SolGridDesktopProbe$Launcher'}
$arguments=@('--add-modules','com.datacube')+$exports+@('--add-opens=com.datacube/com.datacube.fx=ALL-UNNAMED','--add-opens=com.datacube/com.datacube.service=ALL-UNNAMED',"-Dsol.seed.query=$($SeedQuery.IsPresent.ToString().ToLowerInvariant())","-Dsol.block.write=$($BlockWrite.IsPresent.ToString().ToLowerInvariant())",('-Duser.home="'+$profile+'"'),'--enable-native-access=com.datacube,javafx.graphics','-cp',('"'+$classes+'"'),$launcher)
# A visible GUI is necessary for native input; javaw avoids a terminal window.
$process=Start-Process -FilePath "$runtime/bin/javaw.exe" -ArgumentList $arguments -WindowStyle Normal -RedirectStandardOutput (Join-Path $temporary 'launcher.stdout.log') -RedirectStandardError (Join-Path $temporary 'launcher.stderr.log') -PassThru
[ordered]@{kind=$Kind;pid=$process.Id;root=$temporary;profile=$profile;seedQuery=$SeedQuery.IsPresent;blockWrite=$BlockWrite.IsPresent} | ConvertTo-Json | Set-Content (Join-Path $temporary 'launch.json') -Encoding utf8
Write-Output $temporary
