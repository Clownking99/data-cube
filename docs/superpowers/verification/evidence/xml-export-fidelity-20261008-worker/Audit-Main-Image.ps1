param([Parameter(Mandatory)][string]$ReviewedCommit,[string]$Name='004-main-image-audit')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$image=Join-Path $root 'build/jpackage/DataCube'
$out=Join-Path $PSScriptRoot $Name
if(Test-Path $out){throw 'Output must be new'}
New-Item -ItemType Directory $out|Out-Null
$dirty=@(git -C $root diff --name-only HEAD -- src test resources buildSrc build.gradle .github/workflows)
if($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0){throw 'Source dirty after main verification'}
git -C $root diff --quiet "$ReviewedCommit" HEAD -- src test resources buildSrc build.gradle .github/workflows
if($LASTEXITCODE -ne 0){throw 'Main source differs from reviewed worker source'}
$index=@(& "$jdk/bin/jimage.exe" list "$image/runtime/lib/modules")
if($LASTEXITCODE -ne 0){throw 'jimage failed'}
$index|Set-Content -Encoding utf8 (Join-Path $out 'module-list.txt')
$types=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
git -C $root -c core.quotepath=false ls-files -- test | Where-Object {$_ -match '\.java$'} | ForEach-Object {[void]$types.Add(($_ -replace '^test/','' -replace '\.java$',''))}
$leaks=@($index|Where-Object {$type=$_.Trim() -replace '\$.*$','' -replace '\.class$','';$types.Contains($type) -or $type -match '^(org/junit/|org/mockito/|org/testfx/|acceptance/)' -or $type -match '(DesktopProbe|RuntimeDriverProbe|ShutdownDesktopProbe)$'})
$files=@(Get-ChildItem -LiteralPath $image -Recurse -File|ForEach-Object{$_.FullName.Substring($image.Length+1).Replace('\','/')})
$fileLeaks=@($files|Where-Object{$_ -match '(?i)(\.datacube|(^|/)profiles?(/|$)|test-results|acceptance|Probe|fixture|isolation)'})
$files|Set-Content -Encoding utf8 (Join-Path $out 'file-list.txt')
$cfg=Get-Content "$image/app/DataCube.cfg" -Raw
$cfg|Set-Content -Encoding utf8 (Join-Path $out 'DataCube.cfg.txt')
$optionLeaks=$cfg -match '(?i)(user.home|headless|java.io.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic)'
$artifacts=@('DataCube.exe','app/DataCube.cfg','runtime/lib/modules')|ForEach-Object{@{path=$_;bytes=(Get-Item (Join-Path $image $_)).Length;sha256=(Get-FileHash (Join-Path $image $_)).Hash}}
$saved=@{}
foreach($item in @(Get-ChildItem Env:)){if($item.Name -match 'JAVA|JDK|GRADLE|DATACUBE|LIVE|ORACLE|PGHOST|PGPASSWORD|PGUSER|PGDATABASE|REDIS'){$saved[$item.Name]=$item.Value;Remove-Item -LiteralPath ('Env:'+$item.Name)}}
try{
 $owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-xml-export-fidelity-image-'+[guid]::NewGuid().ToString('N'))
 New-Item -ItemType Directory (Join-Path $owned 'classes'),(Join-Path $owned 'profile')|Out-Null
 $probe=Join-Path $root 'docs/superpowers/verification/evidence/sol-p0-p2/MigrationRuntimeDriverProbe.java'
 & "$jdk/bin/javac.exe" --system "$image/runtime" --add-modules com.datacube -d "$owned/classes" $probe *> (Join-Path $out 'driver-compile.log')
 if($LASTEXITCODE -ne 0){throw 'driver compile failed'}
 & "$image/runtime/bin/java.exe" --add-modules com.datacube --add-exports=com.datacube/com.datacube.migration=ALL-UNNAMED "-Duser.home=$owned/profile" -cp "$owned/classes" MigrationRuntimeDriverProbe *> (Join-Path $out 'driver-discovery.log')
 $driverExit=$LASTEXITCODE
 $driver=Get-Content (Join-Path $out 'driver-discovery.log') -Raw
 $xmlProbe=Join-Path $PSScriptRoot 'XmlRuntimeExportProbe.java'
 & "$jdk/bin/javac.exe" --system "$image/runtime" --add-modules com.datacube --add-exports=com.datacube/com.datacube.export=ALL-UNNAMED -d "$owned/classes" $xmlProbe *> (Join-Path $out 'xml-compile.log')
 if($LASTEXITCODE -ne 0){throw 'XML runtime probe compile failed'}
 & "$image/runtime/bin/java.exe" --add-modules com.datacube --add-exports=com.datacube/com.datacube.export=ALL-UNNAMED "-Duser.home=$owned/profile" -cp "$owned/classes" XmlRuntimeExportProbe *> (Join-Path $out 'xml-runtime.log')
 $xmlExit=$LASTEXITCODE
 $xmlOutput=Get-Content (Join-Path $out 'xml-runtime.log') -Raw
 $passed=$leaks.Count -eq 0 -and $fileLeaks.Count -eq 0 -and !$optionLeaks -and $driverExit -eq 0 -and $driver -match 'connectCalls=0' -and $xmlExit -eq 0 -and $xmlOutput -match 'XML_RUNTIME_ROUND_TRIPS=7; INVALID_CHARACTER_REJECTED=true'
 @{utc=[datetime]::UtcNow.ToString('o');source=(git -C $root rev-parse HEAD);artifacts=$artifacts;classLeaks=$leaks;fileLeaks=$fileLeaks;optionLeaks=$optionLeaks;driverExit=$driverExit;driverOutput=$driver;xmlExit=$xmlExit;xmlOutput=$xmlOutput;profile=$owned+'/profile';passed=$passed}|ConvertTo-Json -Depth 6|Set-Content -Encoding utf8 (Join-Path $out 'audit.json')
 if(!$passed){throw 'Image audit failed'}
}finally{foreach($key in $saved.Keys){Set-Item -LiteralPath ('Env:'+$key) -Value $saved[$key]}}

