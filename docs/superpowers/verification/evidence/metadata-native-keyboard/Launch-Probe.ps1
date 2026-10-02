$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
Set-Location -LiteralPath $repo
if((git rev-parse HEAD).Trim() -ne '7afbe280d92b68b0780333f3a7942157d4197234' -or (git branch --show-current).Trim() -ne 'codex/metadata-native-keyboard'){throw 'Unexpected baseline'}
if(@(git diff --name-only 75ed0c6be586cd6ff84e34ce64e2fba5692a8610 HEAD -- src build.gradle).Count -ne 0){throw 'Image product sources differ from this baseline'}
foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS')){Remove-Item -LiteralPath ('Env:'+$key) -ErrorAction SilentlyContinue}
foreach($key in @(Get-ChildItem Env: | Where-Object {$_.Name.ToUpperInvariant() -match 'LIVE|ORACLE|REDIS|^PG|^DATACUBE_'} | Select-Object -ExpandProperty Name)){Remove-Item -LiteralPath ('Env:'+$key)}
$runtime=(Resolve-Path (Join-Path $repo 'build/jpackage/DataCube/runtime')).Path
$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$id=[guid]::NewGuid().ToString('N')
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('datacube-metadata-native-'+$id)
$classes=Join-Path $temporary 'classes'; $profile=Join-Path $temporary 'metadata-native-profile'
New-Item -ItemType Directory -Path $classes,$profile | Out-Null
$packages=@('com.datacube.fx','com.datacube.fx.task','com.datacube.config','com.datacube.service','com.datacube.spi','com.datacube.spi.model','com.datacube.provider.postgres','com.datacube.provider.jdbc')
$exports=$packages|ForEach-Object {"--add-exports=com.datacube/$_=ALL-UNNAMED"}
$source=Join-Path $PSScriptRoot 'MetadataNativeKeyboardProbe.java'
& "$jdk/bin/javac.exe" --system $runtime --add-modules com.datacube @exports -d $classes $source *> (Join-Path $temporary 'compile.log')
$compileExit=$LASTEXITCODE
Copy-Item -LiteralPath (Join-Path $temporary 'compile.log') -Destination (Join-Path $PSScriptRoot 'compile-first.log')
if($compileExit -ne 0){throw "Compile failed: $temporary/compile.log"}
$title='DataCube N1 '+$id
$arguments=@('--add-modules','com.datacube')+$exports+@('--add-opens=com.datacube/com.datacube.fx=ALL-UNNAMED','--add-opens=com.datacube/com.datacube.service=ALL-UNNAMED',('-Dprobe.title="'+$title+'"'),('-Duser.home="'+$profile+'"'),'--enable-native-access=com.datacube,javafx.graphics','-cp',('"'+$classes+'"'),'MetadataNativeKeyboardProbe$Launcher')
$process=Start-Process -FilePath "$runtime/bin/javaw.exe" -ArgumentList $arguments -WindowStyle Normal -RedirectStandardOutput (Join-Path $temporary 'launcher.stdout.log') -RedirectStandardError (Join-Path $temporary 'launcher.stderr.log') -PassThru
[ordered]@{head=(git rev-parse HEAD).Trim();imageProductSourceHead='75ed0c6be586cd6ff84e34ce64e2fba5692a8610';pid=$process.Id;root=$temporary;profile=$profile;title=$title;compileExit=$compileExit;arguments=$arguments;runtime=$runtime;modulesSha256=(Get-FileHash -LiteralPath (Join-Path $runtime 'lib/modules') -Algorithm SHA256).Hash;startedUtc=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $PSScriptRoot 'launch.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'launch.json')
