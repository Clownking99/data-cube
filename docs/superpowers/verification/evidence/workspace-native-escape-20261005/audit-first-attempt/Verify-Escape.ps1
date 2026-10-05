param([Parameter(Mandatory=$true)][string]$RepoRoot,[ValidateSet('branch','main')][string]$Phase='branch')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath $RepoRoot).Path
function Assert($value,[string]$message){if(!$value){throw $message}}
function Json([string]$path){Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}
function Hash([string]$path){(Get-FileHash -LiteralPath $path).Hash.ToLower()}
function Events([string]$dir){@(Get-Content -LiteralPath (Join-Path $dir 'events.jsonl')|ForEach-Object{$_|ConvertFrom-Json})}
function NumBE([byte[]]$b,[int]$o,[int]$n){[byte[]]$part=$b[$o..($o+$n-1)];[array]::Reverse($part);if($n -eq 4){[BitConverter]::ToInt32($part,0)}else{[BitConverter]::ToInt64($part,0)}}
function Decode([string]$path){
 $b=[IO.File]::ReadAllBytes($path);Assert ($b.Length -eq 72 -and (NumBE $b 0 4) -eq 0x44435753 -and (NumBE $b 4 4) -eq 1 -and (NumBE $b 16 4) -eq 2 -and (NumBE $b 20 4) -eq 0) 'Actual workspace header/bounds differ'
 $entries=@(foreach($o in @(24,48)){$hex=[Convert]::ToHexString([byte[]]$b[$o..($o+15)]).ToLower();$id=$hex.Substring(0,8)+'-'+$hex.Substring(8,4)+'-'+$hex.Substring(12,4)+'-'+$hex.Substring(16,4)+'-'+$hex.Substring(20,12);Assert (Test-Path -LiteralPath (Join-Path (Split-Path $path -Parent) ($id+'.draft'))) 'Missing real draft';@{id=$id;anchor=(NumBE $b ($o+16) 4);caret=(NumBE $b ($o+20) 4)}})
 @{capturedAt=(NumBE $b 8 8);selected=0;entries=$entries;bytes=$b.Length;sha256=(Hash $path)}
}
$base='f776f6ed979dced95530774d9b1a29a898931cf8'
$changed=@(git -C $repo diff --name-only $base -- src buildSrc build.gradle build.gradle.kts settings.gradle settings.gradle.kts gradle.properties gradle gradlew gradlew.bat)
Assert ($LASTEXITCODE -eq 0 -and $changed.Count -eq 0) 'Product/test/build drift'
$historical=0
foreach($name in @('workspace-native-exit-coordination','workspace-native-completion-coordination','workspace-native-20261004','workspace-native-20261005')){
 $dir=Join-Path $repo ('docs/superpowers/verification/evidence/'+$name);$manifest=Json (Join-Path $dir 'raw-manifest.json')
 foreach($f in $manifest.files){$root=if($name -in @('workspace-native-exit-coordination','workspace-native-completion-coordination')){$repo}else{$dir};$path=Join-Path $root $f.path;Assert ((Get-Item -LiteralPath $path).Length -eq $f.bytes -and (Hash $path) -eq $f.sha256) ('Historical raw drift: '+$f.path)}
 $historical+=@($manifest.files).Count
}
Assert ($historical -eq 1038) 'Historical raw count differs'
$previous=Join-Path $repo 'docs/superpowers/verification/evidence/workspace-native-20261005'
$priorAudit=Json (Join-Path $previous 'audit-main.json')
Assert ($priorAudit.passed -and $priorAudit.nativeMatrixPassed -eq 7 -and $priorAudit.nativeMatrixUnverified -eq 1 -and $priorAudit.mainNativeRetry.summary.lastDecision -eq '重试') 'Previous 7/8 and main Retry evidence unavailable'
foreach($f in (Json (Join-Path $PSScriptRoot 'source-binding.json')).files){Assert ((Hash (Join-Path $repo $f.path)) -eq $f.sha256) ('Reviewed source drift: '+$f.path)}
$compiled=Json (Join-Path $previous 'compile/compiled.json')
foreach($f in $compiled.imageArtifacts){Assert ((Hash (Join-Path 'D:/Projects/朝花夕拾/build/jpackage/DataCube' $f.path)) -eq $f.sha256) ('Current main image changed: '+$f.path)}
$after=Join-Path $PSScriptRoot 'after-escape';$done=Join-Path $PSScriptRoot 'completed-profile'
foreach($dir in @($after,$done)){$snapshot=Json (Join-Path $dir 'snapshot.json');foreach($f in $snapshot.files){$path=Join-Path $dir $f.destination;Assert ((Hash $path) -eq $f.sha256 -and (Get-Item -LiteralPath $path).Length -eq $f.bytes) 'Snapshot bytes differ'}}
$beforeFile=Join-Path $previous 'escape-only/start-snapshot/sql-drafts/workspace.bin'
$afterFile=Join-Path $after 'sql-drafts/workspace.bin'
Assert ((Hash $beforeFile) -eq (Hash $afterFile)) 'Escape changed actual pre-existing workspace bytes'
$afterEvents=Events $after;$events=Events $done
$key=@($afterEvents|Where-Object{$_.event -eq 'DIALOG_KEY' -and $_.details -eq 'ESCAPE;ctrl=false;shift=false'})
$cancelEvent=@($afterEvents|Where-Object event -eq 'CANCELLED')
Assert ($key.Count -eq 1 -and $cancelEvent.Count -eq 1 -and $key[0].utc -lt $cancelEvent[0].utc -and @($afterEvents|Where-Object event -eq 'CANCEL_TASK_PULSE').Count -eq 1) 'Missing real Esc/cancel/resumed runner or wrong order'
Assert (@($afterEvents|Where-Object event -eq 'FIXTURE_RELEASE').Count -eq 0 -and @($afterEvents|Where-Object event -eq 'COMPLETED').Count -eq 0) 'Cancellation snapshot is not isolated from later completion'
$cancel=Json (Join-Path $after 'summary-1-CANCELLED.json')
Assert ($cancel.passed -and $cancel.outcome -eq 'CANCELLED' -and $cancel.providerRequests -eq 0 -and $cancel.dispatcherCloses -eq 0 -and !$cancel.storeLockReopened -and $cancel.cancellations -eq 1 -and $cancel.lastDecision -eq '取消退出' -and $cancel.currentWorkspaceSHA -eq $cancel.oldWorkspaceSHA -and $cancel.currentWorkspaceSHA -eq (Hash $afterFile)) 'Actual cancellation resource/byte boundary failed'
Assert ((Hash (Join-Path $after 'summary-1-CANCELLED.json')) -eq (Hash (Join-Path $done 'summary-1-CANCELLED.json'))) 'Numbered cancellation summary changed'
$s=Json (Join-Path $done 'summary.json');$layout=Decode (Join-Path $done 'sql-drafts/workspace.bin')
Assert ($s.passed -and $s.outcome -eq 'COMPLETED' -and $s.dispatcherCloses -eq 1 -and $s.providerRequests -eq 0 -and $s.storeLockReopened -and $s.lastDecision -eq '重试' -and $s.cancellations -eq 1) 'Actual final completion failed'
Assert ($layout.sha256 -eq $s.currentWorkspaceSHA -and $s.workspaceAttemptSHAs[-1] -eq $s.workspaceAttemptSHAs[-2] -and $s.workspaceAttemptSHAs[-1] -eq $cancel.workspaceAttemptSHAs[-1] -and $s.workspaceAttemptSHAs[-1] -eq $layout.sha256) 'Frozen bytes changed across Esc and Retry'
Assert ($layout.entries[0].id -eq '9839f875-594b-4bd5-bd05-f35bd90513ef' -and $layout.entries[1].id -eq '67d2ceb2-9826-4db0-b0b0-edf161c05eec' -and $layout.entries[0].anchor -eq 1 -and $layout.entries[0].caret -eq 7 -and $layout.entries[1].anchor -eq 2 -and $layout.entries[1].caret -eq 8) 'Real persisted IDs/order/selection/positions differ'
Assert (@($events|Where-Object{$_.event -match 'FAIL|FATAL|CLEANUP'}).Count -eq 0 -and @($events|Where-Object event -eq 'STORE_LOCK_REOPENED').Count -eq 1 -and @($events|Where-Object event -eq 'COMPLETED').Count -eq 1 -and @($events|Where-Object event -eq 'PRODUCTION_ALERT').Count -eq 2) 'Ambiguous completion or fallback'
$records=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'native') -Filter '*.json' -File|Where-Object{$_.Name -match '^\d'});$images=0;$retryRequests=0
foreach($file in $records){$r=Json $file.FullName;if($r.action.method -eq 'click'){$retryRequests++};foreach($shot in $r.screenshots){$p=Join-Path $file.DirectoryName $shot.file;Assert ((Hash $p) -eq $shot.sha256 -and (Get-Item -LiteralPath $p).Length -eq $shot.bytes) 'Native screenshot archive drift';$images++}}
Assert ($retryRequests -eq 1 -and $images -eq 5) 'Unexpected native action evidence'
$exit=Json (Join-Path $PSScriptRoot 'process-exit.json');Assert ($exit.processAbsent -and !$exit.stopProcessUsedThisRound) 'Process cleanup cannot replace normal exit'
$matrix=Json (Join-Path $PSScriptRoot 'native-matrix.json');Assert ($matrix.passed -eq 8 -and $matrix.unverified -eq 0 -and $matrix.goalComplete -and !$matrix.M8Complete -and @($matrix.items|Where-Object status -ne 'PASS').Count -eq 0) 'Matrix status does not match evidence'
$rawCount=0;$manifestPath=Join-Path $PSScriptRoot 'raw-manifest.json'
if(Test-Path -LiteralPath $manifestPath){$m=Json $manifestPath;foreach($f in $m.files){$p=Join-Path $PSScriptRoot $f.path;Assert ((Hash $p) -eq $f.sha256 -and (Get-Item -LiteralPath $p).Length -eq $f.bytes) ('New raw drift: '+$f.path)};$rawCount=@($m.files).Count}
$result=[ordered]@{phase=$Phase;utc=[DateTime]::UtcNow.ToString('o');head=(git -C $repo rev-parse HEAD);baseline=$base;passed=$true;historicalFrozenFilesVerified=$historical;newFrozenFilesVerified=$rawCount;actualEscapeKey=$key[0];actualBeforeAfterSHA=(Hash $afterFile);cancel=$cancel;completed=$s;independentlyDecodedFinalWorkspace=$layout;nativeStateRecords=$records.Count;screenshots=$images;currentMainImageArtifactsMatched=3;previousMatrixPassed=7;newMatrixPassed=8;unverifiedInBoundedMatrix=0;boundedNativeObjectiveComplete=$true;productTestBuildChanged=$false;newJavacExecuted=$false;gradleExecutedThisRound=$false;formalDataCubeFxLauncherAccepted=$false;M8Complete=$false;priorAuditSHA=(Hash (Join-Path $previous 'audit-main.json'))}
$output=Join-Path $PSScriptRoot ('audit-'+$Phase+'.json');Assert (!(Test-Path -LiteralPath $output)) 'Retain prior audit output';$result|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $output -Encoding utf8
Write-Output ('Independent '+$Phase+' audit PASS: actual Esc old bytes, frozen Retry, completed resources/lock; historical raw '+$historical+'; bounded native matrix 8/8; M8 incomplete.')
