param([string]$Repository, [string]$Name, [ValidateSet('100','150')][string]$Scale='150')
$ErrorActionPreference='Stop'
if($Name -notmatch '^[a-z0-9-]+$'){throw 'Invalid evidence name'}
$run=Join-Path $PSScriptRoot $Name
if(Test-Path -LiteralPath $run){throw 'Evidence name already used'}
$profile=Join-Path $run 'g8-shell-desktop-profile'
New-Item -ItemType Directory -Path $profile | Out-Null
$runtime=Join-Path $Repository 'build/jpackage/DataCube/runtime/bin/javaw.exe'
$classes=Join-Path $PSScriptRoot 'runtime-probe-classes'
if(!(Test-Path -LiteralPath (Join-Path $classes 'G8RuntimeShellProbe$Launcher.class'))){throw 'Compile the shell probe first'}
$lines=@('--add-modules','com.datacube','--add-exports','com.datacube/com.datacube.fx=ALL-UNNAMED',
 '--enable-native-access=ALL-UNNAMED,com.datacube',('-Duser.home="'+$profile.Replace('\','/')+'"'),
 ('-Dglass.win.uiScale='+$Scale+'%'),'-cp',('"'+$classes.Replace('\','/')+'"'),'G8RuntimeShellProbe$Launcher')
$argsFile=Join-Path $run 'launch.args'
[IO.File]::WriteAllLines($argsFile,$lines)
# Visible acceptance windows were explicitly authorized for this G8 task.
$probe=Start-Process -FilePath $runtime -ArgumentList ('@"'+$argsFile+'"') -WorkingDirectory $run -WindowStyle Normal -RedirectStandardOutput (Join-Path $run 'stdout.log') -RedirectStandardError (Join-Path $run 'stderr.log') -PassThru
@{pid=$probe.Id;runtime=$runtime;profile=$profile;arguments=$lines;launchedAt=(Get-Date).ToString('o')} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'launch.json')
$probe.Id
