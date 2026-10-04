param(
 [Parameter(Mandatory=$true)][ValidateSet('Compile','Launch')][string]$Mode,
 [Parameter(Mandatory=$true)][string]$ImageRoot,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Run,
 [string]$CompileDirectory,
 [switch]$VisibleInteractive
)
$ErrorActionPreference='Stop'
$image=(Resolve-Path -LiteralPath $ImageRoot).Path
$runtime=Join-Path $image 'runtime'
$artifactNames=@('DataCube.exe','app/DataCube.cfg','runtime/lib/modules')
$artifacts=@(foreach($name in $artifactNames){$path=Join-Path $image $name;if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw ('Invalid image artifact: '+$name)};@{path=$name;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}})
$source=Join-Path $PSScriptRoot 'WorkspaceNativeExitProbe.java'
$sourceHash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$destination=Join-Path $PSScriptRoot $Run
if(Test-Path -LiteralPath $destination){throw 'Evidence run exists'}
New-Item -ItemType Directory -Path $destination | Out-Null
Get-ChildItem Env:|Where-Object{$_.Name.ToUpper().Contains('LIVE') -or $_.Name.ToUpper().Contains('ORACLE') -or $_.Name.ToUpper().StartsWith('PG') -or $_.Name.ToUpper().Contains('REDIS') -or $_.Name.ToUpper().StartsWith('DATACUBE_') -or $_.Name -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS')}|ForEach-Object{Remove-Item ('Env:'+$_.Name)}
$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$env:JAVA_HOME=$jdk
$packages=@('com.datacube.fx','com.datacube.fx.task','com.datacube.config','com.datacube.service','com.datacube.spi','com.datacube.spi.model')
$exports=@($packages|ForEach-Object{'--add-exports=com.datacube/'+$_+'=ALL-UNNAMED'})
$opened=@('com.datacube.fx','com.datacube.config','com.datacube.service')|ForEach-Object{'--add-opens=com.datacube/'+$_+'=ALL-UNNAMED'}
$started=[DateTime]::UtcNow.ToString('o')
if($Mode -eq 'Compile'){
 $temporary=Join-Path ([IO.Path]::GetTempPath()) ('datacube-workspace-native-compile-'+[guid]::NewGuid())
 $classes=Join-Path $temporary 'classes'
 New-Item -ItemType Directory -Path $classes | Out-Null
 $arguments=@('--system',$runtime,'--add-modules','com.datacube,org.fxmisc.richtext')+$exports+@('-d',$classes,$source)
 @{mode=$Mode;started=$started;imageRoot=$image;imageArtifacts=$artifacts;sourceSHA256=$sourceHash;arguments=$arguments;classes=$classes;javac=$jdk+'/bin/javac.exe'}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $destination 'parameters.json') -Encoding utf8
 & "$jdk/bin/javac.exe" @arguments *> (Join-Path $destination 'raw.log')
 $result=$LASTEXITCODE
 @{exit=$result;finished=[DateTime]::UtcNow.ToString('o');guiStarted=$false;gradleExecuted=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $destination 'result.json') -Encoding utf8
 if($result -eq 0){@{imageArtifacts=$artifacts;sourceSHA256=$sourceHash;classes=$classes;imageRoot=$image}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $destination 'compiled.json') -Encoding utf8}
 Get-Content -LiteralPath (Join-Path $destination 'raw.log')
 Write-Output $destination
 exit $result
}
if(-not $CompileDirectory){throw 'Launch requires explicit successful CompileDirectory'}
$compiled=Get-Content -Raw -LiteralPath (Join-Path $CompileDirectory 'compiled.json')|ConvertFrom-Json
if($compiled.sourceSHA256 -ne $sourceHash){throw 'Probe source changed since compilation'}
foreach($artifact in $artifacts){$old=@($compiled.imageArtifacts|Where-Object{$_.path -eq $artifact.path});if($old.Count -ne 1 -or $old[0].sha256 -ne $artifact.sha256){throw 'Image differs from compile image; compile again against intended image'}}
$profile=Join-Path ([IO.Path]::GetTempPath()) ('datacube-workspace-native-profile-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $profile | Out-Null
$title='DataCube WORKSPACE NATIVE '+[guid]::NewGuid().ToString('N')
$arguments=@('--add-modules','com.datacube,org.fxmisc.richtext')+$exports+$opened+@('--enable-native-access=com.datacube,javafx.graphics',('-Duser.home="'+$profile+'"'),('-Dprobe.title="'+$title+'"'),'-cp',('"'+$compiled.classes+'"'),'WorkspaceNativeExitProbe$Launcher')
@{mode=$Mode;started=$started;imageRoot=$image;imageArtifacts=$artifacts;sourceSHA256=$sourceHash;arguments=$arguments;profile=$profile;title=$title;compileDirectory=$CompileDirectory;releaseMarker=(Join-Path $profile 'release-workspace-fault.marker');programmaticInitialization=$true;externalStageHook=$true;nativeOperator='root only';visibleInteractive=$VisibleInteractive.IsPresent}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $destination 'parameters.json') -Encoding utf8
# Explicitly requested interactive fixture only; javaw still creates no terminal.
$windowStyle=if($VisibleInteractive){'Normal'}else{'Hidden'}
$process=Start-Process -FilePath (Join-Path $runtime 'bin/javaw.exe') -ArgumentList $arguments -WindowStyle $windowStyle -RedirectStandardOutput (Join-Path $destination 'launcher.stdout.log') -RedirectStandardError (Join-Path $destination 'launcher.stderr.log') -PassThru
@{pid=$process.Id;profile=$profile;title=$title;releaseMarker=(Join-Path $profile 'release-workspace-fault.marker');started=$started;visibleInteractive=$VisibleInteractive.IsPresent;windowStyle=$windowStyle}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $destination 'launch.json') -Encoding utf8
Write-Output $destination
exit 0
