param([string]$Repository,[string]$Name)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $Repository
$imageRoot=Join-Path $Repository 'build/jpackage/DataCube'
$cfg=Get-Content -LiteralPath "$imageRoot/app/DataCube.cfg" -Raw
if($cfg -match 'headless|user.home|G8Runtime|DesktopFixture|datacube-g8'){throw 'Test options leaked into application config'}
$moduleList=& "$env:JAVA_HOME/bin/jimage.exe" list "$imageRoot/runtime/lib/modules"
if($LASTEXITCODE -ne 0){throw 'jimage failed'}
$leaked=@($moduleList | Select-String 'DesktopFixture|G8RuntimeShellProbe|GridSaveProbe|DraftConnectionProbe')
if($leaked.Count){throw 'Test classes leaked into image'}
$probeSource=Join-Path $Repository 'docs/superpowers/verification/evidence/g7/MigrationRuntimeDriverProbe.java'
$classes=Join-Path $PSScriptRoot ($Name+'-driver-classes')
New-Item -ItemType Directory -Path $classes -ErrorAction Stop | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -d $classes $probeSource
if($LASTEXITCODE -ne 0){throw 'Driver probe compilation failed'}
$driverLog=Join-Path $PSScriptRoot ($Name+'-driver.log')
& "$imageRoot/runtime/bin/java.exe" --add-modules com.datacube --add-exports com.datacube/com.datacube.migration=ALL-UNNAMED -cp $classes MigrationRuntimeDriverProbe *> $driverLog
$driverExit=$LASTEXITCODE
$output=Get-Content -LiteralPath $driverLog -Raw
if($driverExit -ne 0 -or $output -notmatch 'oracle=oracle.jdbc.OracleDriver' -or $output -notmatch 'postgres=org.postgresql.Driver' -or $output -notmatch 'connectCalls=0'){throw 'Packaged driver discovery failed'}
$artifacts=@(foreach($relative in @('DataCube.exe','app/DataCube.cfg','runtime/lib/modules')){
 $file=Get-Item -LiteralPath (Join-Path $imageRoot $relative)
 [ordered]@{path=$relative;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash}
})
$record=[ordered]@{name=$Name;head=(git rev-parse HEAD);completedAt=(Get-Date).ToString('o');testClassLeaks=0;testConfigLeaks=0;driverExit=$driverExit;driverOutput=$output;driverLogSha256=(Get-FileHash -LiteralPath $driverLog).Hash;artifacts=$artifacts}
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PSScriptRoot ($Name+'.json'))
$record | ConvertTo-Json -Depth 6
