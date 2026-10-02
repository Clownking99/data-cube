param(
    [Parameter(Mandatory=$true)][ValidateSet('branch','main')][string]$Phase,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedCommit
)
$ErrorActionPreference='Stop'
$baseline=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'baseline.json') -Raw | ConvertFrom-Json
$repo=if($Phase -eq 'main'){$baseline.mainRoot}else{$baseline.branchRoot}
Set-Location -LiteralPath $repo
if((git rev-parse HEAD).Trim() -ne $ExpectedCommit){throw 'Image audit source baseline changed'}
$env:JAVA_HOME=$baseline.jdk
$env:PATH=$env:JAVA_HOME+'/bin;'+$env:PATH
foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS')){Remove-Item -LiteralPath ('Env:'+$key) -ErrorAction SilentlyContinue}
foreach($key in @(Get-ChildItem Env: | Where-Object {$_.Name.ToUpperInvariant() -match 'LIVE|ORACLE|REDIS|^PG|^DATACUBE_'} | Select-Object -ExpandProperty Name)){Remove-Item -LiteralPath ('Env:'+$key)}
$out=Join-Path $PSScriptRoot $Phase
$image=Join-Path $repo 'build/jpackage/DataCube'
$module=Join-Path $image 'runtime/lib/modules'
$index=@(& (Join-Path $baseline.jdk 'bin/jimage.exe') list $module)
if($LASTEXITCODE -ne 0){throw 'jimage list failed'}
$index | Set-Content -LiteralPath (Join-Path $out 'image-module-list.log') -Encoding utf8
$types=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
git -c core.quotepath=false ls-files -- test | Where-Object {$_ -match '\.java$'} | ForEach-Object {[void]$types.Add(($_ -replace '^test/','' -replace '\.java$',''))}
$probes=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach($probeType in @('MigrationRuntimeDriverProbe','TwoAddProbe','StabilityProbe','SolShellProbe','SolGridProbe','SolRowProbe','SolShellDesktopProbe','MetadataNativeKeyboardProbe','AppShellShutdownDesktopProbe','AppShellGridShutdownProbe','MetadataDraftInitializationDiagnosticTest')){[void]$probes.Add($probeType)}
$classLeaks=@($index | Where-Object {$type=$_.Trim() -replace '\$.*$','' -replace '\.class$','';$simple=($type -split '/')[-1];$types.Contains($type) -or $probes.Contains($simple) -or $type -cmatch '^(org/junit/|org/mockito/|org/testfx/)'})
$files=@(Get-ChildItem -LiteralPath $image -File -Recurse | ForEach-Object {$_.FullName.Substring($image.Length+1).Replace('\','/')})
$fileLeaks=@($files | Where-Object {$_ -match '(?i)(\.datacube|acceptance|stability|metadata-keyboard|shell-shutdown|shutdown-recovery|grid-exit|workspace-exit|desktop-runtime|test-results|Probe|isolation\.init)'})
$files | Set-Content -LiteralPath (Join-Path $out 'image-file-list.log') -Encoding utf8
$cfg=Get-Content -LiteralPath (Join-Path $image 'app/DataCube.cfg') -Raw
Copy-Item -LiteralPath (Join-Path $image 'app/DataCube.cfg') -Destination (Join-Path $out 'image-DataCube.cfg')
$optionLeaks=$cfg -match '(?i)(user\.home|headless|stability|metadata.keyboard|shell.shutdown|shutdown.recovery|grid.exit|workspace.exit|datacube\.acceptance|java-tool-options|jdk-java-options|synthetic\.invalid|example\.invalid)'
$artifacts=@('DataCube.exe','app/DataCube.cfg','runtime/lib/modules') | ForEach-Object {$file=Join-Path $image $_;[ordered]@{path=$_;bytes=(Get-Item -LiteralPath $file).Length;sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash}}
$freeze=Get-Content -LiteralPath (Join-Path $out 'source-freeze.json') -Raw | ConvertFrom-Json
$sourceChanged=@(foreach($entry in $freeze.files){$file=Get-Item -LiteralPath $entry.path;if($file.Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne $entry.sha256){$entry.path}})
$sourceClean=@(git status --porcelain -- src test buildSrc build.gradle settings.gradle).Count -eq 0
$branchDifferences=@()
if($Phase -eq 'main'){
    $branch=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'branch/image-audit.json') -Raw | ConvertFrom-Json
    $branchDifferences=@($artifacts | Where-Object {$entry=$_;($branch.artifacts | Where-Object {$_.path -eq $entry.path}).sha256 -ne $entry.sha256})
}
$passed=$classLeaks.Count -eq 0 -and $fileLeaks.Count -eq 0 -and !$optionLeaks -and $sourceChanged.Count -eq 0 -and $sourceClean -and $branchDifferences.Count -eq 0
[ordered]@{phase=$Phase;head=$ExpectedCommit;hostUtc=[DateTime]::UtcNow.ToString('o');imageFileCount=$files.Count;testTypes=$types.Count;classLeaks=$classLeaks;fileLeaks=$fileLeaks;optionLeaks=$optionLeaks;sourceFiles=$freeze.files.Count;sourceChanged=$sourceChanged;sourceClean=$sourceClean;artifacts=$artifacts;branchArtifactDifferences=$branchDifferences;passed=$passed} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $out 'image-audit.json') -Encoding utf8
if(!$passed){throw 'Image/source audit failed'}
$runRoot=[IO.Path]::GetFullPath($baseline.runRoot)
if((Split-Path -Leaf $runRoot) -notmatch '^datacube-shell-workspace-exit-[0-9a-f]{32}$' -or -not $runRoot.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected discovery temporary root'}
$classes=Join-Path $runRoot ($Phase+'/driver-classes');$profile=Join-Path $runRoot ($Phase+'/driver-profile')
New-Item -ItemType Directory -Path $classes,$profile -ErrorAction Stop | Out-Null
$probe=Join-Path $repo 'docs/superpowers/verification/evidence/sol-p0-p2/MigrationRuntimeDriverProbe.java'
& (Join-Path $baseline.jdk 'bin/javac.exe') --system (Join-Path $image 'runtime') --add-modules com.datacube -d $classes $probe *> (Join-Path $out 'driver-compile.log')
if($LASTEXITCODE -ne 0){throw 'Driver discovery compile failed'}
& (Join-Path $image 'runtime/bin/java.exe') --add-modules com.datacube --add-exports=com.datacube/com.datacube.migration=ALL-UNNAMED "-Duser.home=$profile" -cp $classes MigrationRuntimeDriverProbe *> (Join-Path $out 'driver-discovery.log')
if($LASTEXITCODE -ne 0){throw 'Zero-connect driver discovery failed'}
Get-Content -LiteralPath (Join-Path $out 'driver-discovery.log')
Write-Output "$Phase image audit passed."
