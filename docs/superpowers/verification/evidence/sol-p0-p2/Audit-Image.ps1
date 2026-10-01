$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$image=(Resolve-Path (Join-Path $repo 'build/jpackage/DataCube')).Path
$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$module=Join-Path $image 'runtime/lib/modules'
& "$jdk/bin/jimage.exe" list $module *> (Join-Path $PSScriptRoot 'image-module-list.log')
if($LASTEXITCODE -ne 0){throw 'jimage failed'}
$moduleEntries=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'image-module-list.log') | ForEach-Object {$_.Trim()})
$testPaths=@(& git -C $repo ls-files -- test ':!.testagent' ':!.testagent/**' | Where-Object {$_ -match '\.java$'} | ForEach-Object {($_ -replace '^test/','') -replace '\.java$',''})
$classes=@($moduleEntries | Where-Object {
 $entry=$_
 $entry -cmatch '^com/datacube/.*(?:Test|Probe|Fixture)(?:\$[^/]*)?\.class$' -or @($testPaths | Where-Object {$entry -ceq ($_+'.class') -or $entry.StartsWith($_+'$',[StringComparison]::Ordinal)}).Count -gt 0
})
$files=@(Get-ChildItem -LiteralPath $image -File -Recurse | ForEach-Object {$_.FullName.Substring($image.Length+1).Replace('\','/')})
$leakedFiles=@($files | Where-Object {$_ -match '(?i)(\.datacube|sol-.*profile|desktop-runtime|test-results|Sol.*Probe|MigrationRuntimeDriverProbe|isolation\.init)'})
$cfg=Get-Content -LiteralPath (Join-Path $image 'app/DataCube.cfg') -Raw
$leakedOptions=($cfg -match '(?i)(user\.home|headless|sol\.|datacube\.acceptance|java-tool-options|jdk-java-options)')
$files | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'image-file-list.log') -Encoding utf8
Copy-Item -LiteralPath (Join-Path $image 'app/DataCube.cfg') -Destination (Join-Path $PSScriptRoot 'image-DataCube.cfg')
$hashes=@('DataCube.exe','app/DataCube.cfg','runtime/lib/modules') | ForEach-Object {
 $path=Join-Path $image $_
 [ordered]@{path=$_;bytes=(Get-Item -LiteralPath $path).Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
}
$snapshot=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source-freeze.json') -Raw | ConvertFrom-Json
$changed=@($snapshot.files | Where-Object {
 $path=Join-Path $repo $_.path
 (Get-Item -LiteralPath $path).Length -ne $_.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $_.sha256
})
[ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');imageFileCount=$files.Count;testProbeClasses=$classes.Count;leakedFiles=$leakedFiles;leakedOptions=$leakedOptions;artifacts=$hashes;sourceFileCount=$snapshot.files.Count;sourceChanged=$changed;passed=($classes.Count -eq 0 -and $leakedFiles.Count -eq 0 -and !$leakedOptions -and $changed.Count -eq 0)} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'image-audit.json') -Encoding utf8
if($classes.Count -gt 0 -or $leakedFiles.Count -gt 0 -or $leakedOptions -or $changed.Count -gt 0){throw 'Image/source audit failed'}
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('datacube-sol-driver-'+[guid]::NewGuid().ToString('N'))
$classesDir=Join-Path $temporary 'classes';$profileDir=Join-Path $temporary 'profile'
New-Item -ItemType Directory -Path $classesDir,$profileDir | Out-Null
& "$jdk/bin/javac.exe" --system (Join-Path $image 'runtime') --add-modules com.datacube -d $classesDir (Join-Path $PSScriptRoot 'MigrationRuntimeDriverProbe.java') *> (Join-Path $PSScriptRoot 'driver-compile.log')
if($LASTEXITCODE -ne 0){throw 'Driver discovery compile failed'}
& (Join-Path $image 'runtime/bin/java.exe') --add-modules com.datacube --add-exports=com.datacube/com.datacube.migration=ALL-UNNAMED "-Duser.home=$profileDir" -cp $classesDir MigrationRuntimeDriverProbe *> (Join-Path $PSScriptRoot 'driver-discovery.log')
if($LASTEXITCODE -ne 0){throw 'Driver discovery failed'}
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'driver-discovery.log')
Write-Output "Audit passed: $($snapshot.files.Count) frozen source files unchanged; image has $($files.Count) files; no probe/test classes, profile files or validation options."
