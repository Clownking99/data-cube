$ErrorActionPreference='Stop'
$main='D:/Projects/朝花夕拾'
$baseline=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'baseline.json') -Raw | ConvertFrom-Json
Set-Location -LiteralPath $main
if((git branch --show-current) -ne 'main' -or (git rev-parse HEAD) -ne $baseline.main){throw 'Main source baseline changed'}
$image=Join-Path $main 'build/jpackage/DataCube'
$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$module=Join-Path $image 'runtime/lib/modules'
$index=@(& "$jdk/bin/jimage.exe" list $module)
if($LASTEXITCODE -ne 0){throw 'jimage failed'}
$index | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'image-module-list.log') -Encoding utf8
$testTypes=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
git -c core.quotepath=false ls-files -- test ':(exclude).testagent' ':(exclude).testagent/**' | Where-Object {$_ -match '\.java$'} | ForEach-Object {[void]$testTypes.Add(($_ -replace '^test/','' -replace '\.java$',''))}
$leaks=@($index | Where-Object {$entry=$_.Trim() -replace '\$.*$','' -replace '\.class$','';$testTypes.Contains($entry) -or $_ -cmatch '(org/junit/|org/mockito/|org/testfx/|(^|/)Sol(Shell|Grid|Row)[^/]*\.class)'})
$files=@(Get-ChildItem -LiteralPath $image -File -Recurse | ForEach-Object {$_.FullName.Substring($image.Length+1).Replace('\','/')})
$fileLeaks=@($files | Where-Object {$_ -match '(?i)(\.datacube|sol-.*profile|desktop-runtime|test-results|Sol.*Probe|MigrationRuntimeDriverProbe|isolation\.init)'})
$files | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'image-file-list.log') -Encoding utf8
$cfg=Get-Content -LiteralPath (Join-Path $image 'app/DataCube.cfg') -Raw
$optionLeaks=$cfg -match '(?i)(user\.home|headless|sol\.|datacube\.acceptance|java-tool-options|jdk-java-options|synthetic\.invalid|example\.invalid)'
Copy-Item -LiteralPath (Join-Path $image 'app/DataCube.cfg') -Destination (Join-Path $PSScriptRoot 'image-DataCube.cfg')
$artifacts=@('DataCube.exe','app/DataCube.cfg','runtime/lib/modules') | ForEach-Object {$file=Join-Path $image $_;[ordered]@{path=$_;bytes=(Get-Item -LiteralPath $file).Length;sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash}}
$freeze=Get-Content -LiteralPath (Join-Path $main 'docs/superpowers/verification/evidence/sol-p0-p2/source-freeze.json') -Raw | ConvertFrom-Json
$changed=@();$checkoutConversions=@();$mainSources=@()
$developerRoot='C:/Users/hetia/.codex/worktrees/b07c/朝花夕拾'
if((git -C $developerRoot rev-parse HEAD) -ne $baseline.developer){throw 'Developer source baseline changed'}
git diff --quiet $baseline.developer HEAD -- src test build.gradle settings.gradle buildSrc
$sourceTreeMatches=$LASTEXITCODE -eq 0
git diff --quiet -- src test build.gradle settings.gradle buildSrc
$mainSourceClean=$LASTEXITCODE -eq 0
git -C $developerRoot diff --quiet -- src test build.gradle settings.gradle buildSrc
$developerSourceClean=$LASTEXITCODE -eq 0
$strictUtf8=[Text.UTF8Encoding]::new($false,$true)
foreach($entry in $freeze.files){
 $relative=$entry.path.Replace('\','/')
 if($relative -notmatch '^(src/|test/|buildSrc/|build\.gradle$|settings\.gradle$)' -or $relative -match '(^|/)\.testagent(/|$)'){throw 'Unexpected frozen source path'}
 $file=[IO.Path]::GetFullPath((Join-Path $main $relative))
 if(-not $file.StartsWith($main.Replace('/',[IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Source path escaped main'}
 $raw=[IO.File]::ReadAllBytes($file)
 $rawHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw))
 $mainSources+=[ordered]@{path=$relative;bytes=$raw.Length;sha256=$rawHash}
 if($raw.Length -ne $entry.bytes -or $rawHash -ne $entry.sha256){
  # Prove only LF/CRLF differences against the still-frozen source; do not mask other changes.
  $developerFile=Join-Path $developerRoot $relative
  $developerRaw=[IO.File]::ReadAllBytes($developerFile)
  $developerHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($developerRaw))
  $onlyNewlines=[IO.Path]::GetExtension($relative) -eq '.java' -and $developerRaw.Length -eq $entry.bytes -and $developerHash -eq $entry.sha256
  if($onlyNewlines){$onlyNewlines=$strictUtf8.GetString($raw).Replace("`r`n","`n") -ceq $strictUtf8.GetString($developerRaw).Replace("`r`n","`n")}
  if($onlyNewlines){$checkoutConversions+=[ordered]@{path=$relative;mainRawSha256=$rawHash;developerRawSha256=$developerHash;onlyLfCrlf=$true}}
  else{$changed+=$relative}
 }
}
[ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');main=$baseline.main;files=$mainSources} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'main-source-freeze.json') -Encoding utf8
$branchAudit=Get-Content -LiteralPath (Join-Path $main 'docs/superpowers/verification/evidence/sol-p0-p2/image-audit.json') -Raw | ConvertFrom-Json
$artifactDifferences=@($artifacts | Where-Object {$entry=$_;($branchAudit.artifacts | Where-Object {$_.path -eq $entry.path}).sha256 -ne $entry.sha256})
$passed=$leaks.Count -eq 0 -and $fileLeaks.Count -eq 0 -and !$optionLeaks -and $changed.Count -eq 0 -and $artifactDifferences.Count -eq 0 -and $sourceTreeMatches -and $mainSourceClean -and $developerSourceClean
[ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');main=$baseline.main;imageFileCount=$files.Count;testTypes=$testTypes.Count;classLeaks=$leaks;fileLeaks=$fileLeaks;optionLeaks=$optionLeaks;frozenSourceFiles=$freeze.files.Count;sourceChanged=$changed;sourceGitTreeMatches=$sourceTreeMatches;mainSourceClean=$mainSourceClean;developerSourceClean=$developerSourceClean;verifiedCheckoutConversions=$checkoutConversions;artifacts=$artifacts;branchArtifactDifferences=$artifactDifferences;passed=$passed} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'image-audit.json') -Encoding utf8
if(!$passed){throw 'Main image/source/branch comparison failed'}
$runRoot=[IO.Path]::GetFullPath($baseline.runRoot)
if((Split-Path -Leaf $runRoot) -notmatch '^datacube-sol-p3-[0-9a-f]{32}$' -or -not $runRoot.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected P3 run root'}
$classes=Join-Path $runRoot 'driver-classes';$profile=Join-Path $runRoot 'driver-profile'
New-Item -ItemType Directory -Path $classes,$profile -ErrorAction Stop | Out-Null
& "$jdk/bin/javac.exe" --system (Join-Path $image 'runtime') --add-modules com.datacube -d $classes (Join-Path $main 'docs/superpowers/verification/evidence/sol-p0-p2/MigrationRuntimeDriverProbe.java') *> (Join-Path $PSScriptRoot 'driver-compile.log')
if($LASTEXITCODE -ne 0){throw 'Driver discovery compile failed'}
& (Join-Path $image 'runtime/bin/java.exe') --add-modules com.datacube --add-exports=com.datacube/com.datacube.migration=ALL-UNNAMED "-Duser.home=$profile" -cp $classes MigrationRuntimeDriverProbe *> (Join-Path $PSScriptRoot 'driver-discovery.log')
if($LASTEXITCODE -ne 0){throw 'Driver discovery failed'}
Get-Content -LiteralPath (Join-Path $PSScriptRoot 'driver-discovery.log')
Write-Output 'Main image audit passed; three artifact hashes match reviewed developer image.'
