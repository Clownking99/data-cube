param([ValidateSet('100','150')][string]$Scale,[string]$Name,[ValidateSet('migration','cancellation','discovery','workflow','shell')][string]$Mode)
$ErrorActionPreference='Stop'
$scratch=$PSScriptRoot
if($Name -notmatch '^[a-z0-9-]+$') { throw 'Invalid evidence name' }
$types=@{
 cancellation=@('g8-migration-desktop-profile','com.datacube.fx.G8MigrationDesktopFixture$Launcher')
 migration=@('g7-migration-desktop-profile','com.datacube.fx.G7MigrationDesktopFixture$Launcher')
 discovery=@('g6-discovery-desktop-profile','com.datacube.fx.G6DiscoveryDesktopFixture$Launcher')
 workflow=@('g8-workflow-desktop-profile','com.datacube.fx.G8WorkflowDesktopFixture$Launcher')
 shell=@('g8-shell-desktop-profile','com.datacube.fx.G8ShellDesktopFixture$Launcher')
}
$run=Join-Path $scratch $Name
if(Test-Path -LiteralPath $run) { throw 'Evidence name already used' }
$profile=Join-Path $run $types[$Mode][0]
New-Item -ItemType Directory -Path $profile | Out-Null
$cp=[IO.File]::ReadAllText((Join-Path $scratch 'desktop-classpath.txt'))
$lines=@(('-Duser.home="'+$profile.Replace('\','/')+'"'),'-Djava.awt.headless=false',('-Dglass.win.uiScale='+$Scale+'%'),'--enable-native-access=ALL-UNNAMED','-cp',('"'+$cp.Replace('\','/')+'"'),$types[$Mode][1])
$arguments=Join-Path $run 'launch.args'
[IO.File]::WriteAllLines($arguments,$lines)
# The maintainer explicitly authorized visible isolated acceptance windows for G8.
$process=Start-Process -FilePath 'D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8/bin/javaw.exe' -ArgumentList ('@'+$arguments) -WorkingDirectory $run -WindowStyle Normal -PassThru -RedirectStandardOutput (Join-Path $run 'stdout.log') -RedirectStandardError (Join-Path $run 'stderr.log')
[pscustomobject]@{pid=$process.Id;profile=$profile;scale=$Scale;mode=$Mode;arguments=$arguments;launchedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'launch.json') -Encoding utf8
$process.Id
