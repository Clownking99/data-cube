param([ValidateSet('Baseline','Staged','PostMerge')][string]$Mode='Baseline',[string]$ExpectedCommit)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
Set-Location -LiteralPath $repo
$base='c4807bc4bd6fb428cd3f17c968d994cb30d32207'
if($ExpectedCommit -and (git rev-parse HEAD) -ne $ExpectedCommit){throw 'Unexpected HEAD'}
$oldManifest=Get-Content -LiteralPath (Join-Path $repo 'docs/superpowers/verification/evidence/workspace-native-exit-coordination/raw-manifest.json') -Raw|ConvertFrom-Json
foreach($f in $oldManifest.files){$p=Join-Path $repo $f.path;if(!(Test-Path -LiteralPath $p -PathType Leaf) -or (Get-Item -LiteralPath $p).Length -ne $f.bytes -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash -ne $f.sha256){throw ('Historical raw drift: '+$f.path)}}
$sourcePaths=@('src','buildSrc','build.gradle','build.gradle.kts','settings.gradle','settings.gradle.kts','gradle.properties','gradle','gradlew','gradlew.bat')
$productDiff=@(git diff --name-only $base -- @sourcePaths)
if($LASTEXITCODE -ne 0 -or $productDiff.Count -ne 0){throw 'Product/test/build changed'}
$manifestPath=Join-Path $PSScriptRoot 'raw-manifest.json'
if(Test-Path -LiteralPath $manifestPath){
 $manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json
 foreach($f in $manifest.files){$p=Join-Path $repo $f.path;if(!(Test-Path -LiteralPath $p -PathType Leaf) -or (Get-Item -LiteralPath $p).Length -ne $f.bytes -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash -ne $f.sha256){throw ('New raw drift: '+$f.path)}}
}
if($manifest){
 $actual=@(foreach($dir in @('workspace-native-completion-worker','workspace-native-completion-coordination')){Get-ChildItem -LiteralPath (Join-Path $repo ('docs/superpowers/verification/evidence/'+$dir)) -File -Recurse|Where-Object{$_.Name -notin @('raw-manifest.json','staged-audit.json','postmerge-audit.json')}})
 if($actual.Count -ne @($manifest.files).Count){throw 'New raw file count changed'}
}
$worker=Join-Path $repo 'docs/superpowers/verification/evidence/workspace-native-completion-worker'
$compiled=Get-Content -LiteralPath (Join-Path $worker 'native-compile/compiled.json') -Raw|ConvertFrom-Json
if((Get-FileHash -LiteralPath (Join-Path $worker 'WorkspaceNativeExitProbe.java') -Algorithm SHA256).Hash -ne $compiled.sourceSHA256){throw 'Compiled source drift'}
foreach($f in $compiled.imageArtifacts){if((Get-FileHash -LiteralPath (Join-Path 'D:/Projects/朝花夕拾/build/jpackage/DataCube' $f.path) -Algorithm SHA256).Hash -ne $f.sha256){throw ('Main image drift: '+$f.path)}}
$events=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'events.jsonl')|ForEach-Object{$_|ConvertFrom-Json})
if(@($events|Where-Object{$_.event -match '^(SHELL|DIALOG)_(KEY|CLICK)$|^PRODUCTION_|^CANCELLED$|^COMPLETED$'}).Count -ne 0){throw 'Unexpected native event'}
$ready=@($events|Where-Object{$_.event -eq 'READY'})
$workspace=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'own-workspace-initial.bin') -Algorithm SHA256).Hash.ToLower()
if($ready.Count -ne 1 -or !$ready[0].details.Contains('oldWorkspaceSHA='+$workspace)){throw 'Initial workspace bytes differ from READY'}
$checked=0
if($Mode -eq 'Staged'){
 $paths=@(git diff --cached --name-only)
 foreach($path in $paths){
  if($path -match '^\.testagent(?:/|$)'){throw 'Forbidden staging'}
  if($path -notmatch '^docs/(?:handoffs/2026-09-23-product-maturity-goal-handoff\.md|superpowers/plans/(?:2026-09-23-product-maturity-roadmap|2026-10-02-workspace-native-completion)\.md|superpowers/verification/(?:2026-10-02-workspace-native-completion-coordination\.md|evidence/workspace-native-completion-(?:worker|coordination)/))'){throw ('Unexpected staging: '+$path)}
  $disk=git hash-object --no-filters -- $path
  $index=git rev-parse (':'+$path)
  if($disk -ne $index){throw ('Staged bytes differ: '+$path)}
  $checked++
 }
 git diff --cached --check
 if($LASTEXITCODE -ne 0){throw 'Staged whitespace check failed'}
}
$ownStop=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'owned-process-stop.json') -Raw|ConvertFrom-Json
if($ownStop.productCompleted -ne $false -or $ownStop.ownProcessRemaining -ne 0){throw 'Cleanup classified incorrectly'}
$result=[ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');mode=$Mode;head=(git rev-parse HEAD);baseline=$base;historicalRawFilesVerified=@($oldManifest.files).Count;newRawFilesVerified=if($manifest){@($manifest.files).Count}else{0};productTestBuildChanged=$false;newJavacSourceBound=$true;mainImageArtifactsMatched=3;initialWorkspaceBytesMatched=$true;nativeInputOrOutcomeEvents=0;stagedExactByteFiles=$checked;passed=$true;nativeGoalComplete=$false}
$output=Join-Path $PSScriptRoot ($Mode.ToLower()+'-audit.json')
if(Test-Path -LiteralPath $output){throw 'Audit output exists; preserve original'}
$result|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $output -Encoding utf8
$result|ConvertTo-Json -Depth 6
