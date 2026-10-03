param([ValidateSet('branch','main')][string]$Phase='branch')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
Set-Location -LiteralPath $repo
$base='4cc2866d7352c562d97015ec049096d540c04f39'
$changed=@(git diff --name-only $base -- src buildSrc build.gradle build.gradle.kts settings.gradle settings.gradle.kts gradle.properties gradle gradlew gradlew.bat)
if($LASTEXITCODE -ne 0 -or $changed.Count){throw 'Product/test/build drift'}
$oldCount=0
foreach($relative in @('workspace-native-exit-coordination','workspace-native-completion-coordination')){
 $manifest=Get-Content -LiteralPath (Join-Path $repo ('docs/superpowers/verification/evidence/'+$relative+'/raw-manifest.json')) -Raw|ConvertFrom-Json
 foreach($f in $manifest.files){$p=Join-Path $repo $f.path;if((Get-Item -LiteralPath $p).Length -ne $f.bytes -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash -ne $f.sha256){throw ('Old raw drift: '+$f.path)}}
 $oldCount+=@($manifest.files).Count
}
$compiled=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'compile/compiled.json') -Raw|ConvertFrom-Json
if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'WorkspaceNativeExitProbe.java') -Algorithm SHA256).Hash -ne $compiled.sourceSHA256){throw 'Probe source drift'}
if((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'compile/result.json') -Raw|ConvertFrom-Json).exit -ne 0){throw 'New javac failed'}
foreach($f in $compiled.imageArtifacts){if((Get-FileHash -LiteralPath (Join-Path 'D:/Projects/朝花夕拾/build/jpackage/DataCube' $f.path) -Algorithm SHA256).Hash -ne $f.sha256){throw ('Actual main image drift: '+$f.path)}}
function NumberBE([byte[]]$bytes,[int]$offset,[int]$width){[byte[]]$slice=$bytes[$offset..($offset+$width-1)];[array]::Reverse($slice);if($width -eq 4){[BitConverter]::ToInt32($slice,0)}else{[BitConverter]::ToInt64($slice,0)}}
function DecodeWorkspace([string]$path){
 $bytes=[IO.File]::ReadAllBytes($path)
 if((NumberBE $bytes 0 4) -ne 0x44435753 -or (NumberBE $bytes 4 4) -ne 1){throw 'Unexpected workspace v1 header'}
 $count=NumberBE $bytes 16 4;$selected=NumberBE $bytes 20 4
 if($count -lt 0 -or $count -gt 1000 -or $bytes.Length -ne (24+24*$count) -or $selected -lt -1 -or $selected -ge $count){throw 'Invalid workspace bounds'}
 $entries=@(for($i=0;$i -lt $count;$i++){$offset=24+$i*24;$hex=([Convert]::ToHexString([byte[]]$bytes[$offset..($offset+15)])).ToLower();$id=$hex.Substring(0,8)+'-'+$hex.Substring(8,4)+'-'+$hex.Substring(12,4)+'-'+$hex.Substring(16,4)+'-'+$hex.Substring(20,12);[ordered]@{id=$id;anchor=(NumberBE $bytes ($offset+16) 4);caret=(NumberBE $bytes ($offset+20) 4)}})
 [ordered]@{capturedAt=(NumberBE $bytes 8 8);selected=$selected;entries=$entries;bytes=$bytes.Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()}
}
$scenarios=@(foreach($name in @('cancel-sequence','ignore')){
 $own=Join-Path $PSScriptRoot ($name+'/own-profile')
 $summary=Get-Content -LiteralPath (Join-Path $own 'summary.json') -Raw|ConvertFrom-Json
 $events=@(Get-Content -LiteralPath (Join-Path $own 'events.jsonl')|ForEach-Object{$_|ConvertFrom-Json})
 if($summary.outcome -ne 'COMPLETED' -or !$summary.passed -or !$summary.storeLockReopened -or $summary.providerRequests -ne 0 -or $summary.dispatcherCloses -ne 1){throw ('Invalid real completed boundaries: '+$name)}
 if(@($events|Where-Object{$_.event -match '^FAIL|^FATAL|^PRODUCT_CLEANUP'}).Count -ne 0 -or @($events|Where-Object{$_.event -eq 'COMPLETED'}).Count -ne 1 -or @($events|Where-Object{$_.event -eq 'STORE_LOCK_REOPENED'}).Count -ne 1){throw ('Fallback/ambiguous outcome: '+$name)}
 $layout=DecodeWorkspace (Join-Path $own 'sql-drafts/workspace.bin')
 if($layout.sha256 -ne $summary.currentWorkspaceSHA){throw 'Summary differs from real workspace bytes'}
 foreach($entry in $layout.entries){if(!(Test-Path -LiteralPath (Join-Path $own ('sql-drafts/'+$entry.id+'.draft')) -PathType Leaf)){throw 'Persisted layout references missing real draft'}}
 if($name -eq 'cancel-sequence'){
  $first=Get-Content -LiteralPath (Join-Path $own 'summary-1-CANCELLED.json') -Raw|ConvertFrom-Json
  $second=Get-Content -LiteralPath (Join-Path $own 'summary-2-CANCELLED.json') -Raw|ConvertFrom-Json
  foreach($s in @($first,$second)){if(!$s.passed -or $s.providerRequests -ne 0 -or $s.dispatcherCloses -ne 0 -or $s.currentWorkspaceSHA -ne $s.oldWorkspaceSHA){throw 'Cancel old bytes/resources failed'}}
  if($second.cancellations -ne 2 -or $second.workspaceAttemptSHAs[-1] -ne $second.workspaceAttemptSHAs[-2]){throw 'Repeat cancel changed actual frozen bytes'}
  if($summary.lastDecision -ne '重试' -or $summary.workspaceAttemptSHAs[-1] -ne $summary.workspaceAttemptSHAs[-2] -or $summary.workspaceAttemptSHAs[-1] -ne $layout.sha256){throw 'Native retry differs from frozen publication'}
  if($layout.entries.Count -ne 1 -or $layout.entries[0].anchor -ne 11 -or $layout.entries[0].caret -ne 11 -or $layout.selected -ne 0){throw 'Post-save positions/selection failed'}
  if([IO.File]::ReadAllText((Join-Path $own 'after-cancel.sql')) -ne 'select 404;'){throw 'Actual native edited file bytes not saved'}
  if(@($events|Where-Object{$_.event -eq 'CANCEL_TASK_PULSE'}).Count -ne 2){throw 'Cancel runner not resumed'}
 }else{
  if($summary.lastDecision -ne '忽略本次工作区更新并退出' -or $summary.currentWorkspaceSHA -ne $summary.oldWorkspaceSHA -or @($events|Where-Object{$_.event -eq 'FIXTURE_RELEASE'}).Count -ne 0){throw 'Ignore changed prior bytes or released fault'}
  if($layout.capturedAt -ne 1 -or $layout.entries.Count -ne 2 -or $layout.selected -ne 0 -or $layout.entries[0].anchor -ne 2 -or $layout.entries[1].anchor -ne 1){throw 'Ignore did not retain seeded order/selection/positions'}
 }
 [ordered]@{name=$name;summary=$summary;independentlyDecodedWorkspace=$layout;productionAlerts=@($events|Where-Object{$_.event -eq 'PRODUCTION_ALERT'}).Count;nativeDialogKeys=@($events|Where-Object{$_.event -eq 'DIALOG_KEY'}).Count;actualGlobalTaskPulses=@($events|Where-Object{$_.event -eq 'CANCEL_TASK_PULSE'}).Count;completed=$true;noFallback=$true}
})
$images=0;$requests=0
$records=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'native') -Filter '*.json' -File)
foreach($file in $records){$r=Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json;if($r.action.method){$requests++};foreach($s in $r.screenshots){$p=Join-Path $file.DirectoryName $s.file;if((Get-Item -LiteralPath $p).Length -ne $s.bytes -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower() -ne $s.sha256){throw ('Screenshot archive drift: '+$s.file)};$images++}}
$rawManifest=Join-Path $PSScriptRoot 'raw-manifest.json'
if(Test-Path -LiteralPath $rawManifest){$m=Get-Content -LiteralPath $rawManifest -Raw|ConvertFrom-Json;foreach($f in $m.files){$p=Join-Path $PSScriptRoot $f.path;if((Get-Item -LiteralPath $p).Length -ne $f.bytes -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash -ne $f.sha256){throw ('New frozen raw drift: '+$f.path)}}}
$result=[ordered]@{clientDate='2026-10-04';hostUtc=[DateTime]::UtcNow.ToString('o');phase=$Phase;head=(git rev-parse HEAD);startingMain=$base;productTestBuildChanged=$false;historicalRawFilesVerified=$oldCount;newJavacVerified=$true;currentMainImageArtifactsMatched=3;scenarios=$scenarios;nativeArchiveRecords=$records.Count;archivedScreenshotFiles=$images;nativeToolInputRequests=$requests;nativeMatrixPassed=6;nativeMatrixUnverified=2;remaining=@('actual default Enter','actual Escape');nativeGoalComplete=$false;formalLauncherAccepted=$false;M8Complete=$false;gradleExecutedThisRound=$false;passed=$true}
$output=Join-Path $PSScriptRoot ('audit-'+$Phase+'.json')
if(Test-Path -LiteralPath $output){throw 'Audit output exists; retain original'}
$result|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $output -Encoding utf8
Write-Output ('Independent '+$Phase+' audit: old raw '+$oldCount+'; completed own scenarios 2; native matrix 6/8; screenshot files '+$images+'; product unchanged; no new Gradle claim.')
