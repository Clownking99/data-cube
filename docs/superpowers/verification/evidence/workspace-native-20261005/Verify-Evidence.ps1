param([Parameter(Mandatory=$true)][string]$RepoRoot,[ValidateSet('branch','main')][string]$Phase='branch')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath $RepoRoot).Path
$base='6cf6788f7f3d36ed785db6b2ec90d9489afe338c'
function Assert($value,[string]$message){if(!$value){throw $message}}
function ReadJson([string]$path){Get-Content -LiteralPath $path -Raw|ConvertFrom-Json}
function Events([string]$dir){@(Get-Content -LiteralPath (Join-Path $dir 'events.jsonl')|ForEach-Object{$_|ConvertFrom-Json})}
function NumBE([byte[]]$bytes,[int]$offset,[int]$width){[byte[]]$slice=$bytes[$offset..($offset+$width-1)];[array]::Reverse($slice);if($width -eq 4){[BitConverter]::ToInt32($slice,0)}else{[BitConverter]::ToInt64($slice,0)}}
function Decode([string]$dir){
 $path=Join-Path $dir 'sql-drafts/workspace.bin';$b=[IO.File]::ReadAllBytes($path)
 Assert ((NumBE $b 0 4) -eq 0x44435753 -and (NumBE $b 4 4) -eq 1) 'Workspace format changed'
 $count=NumBE $b 16 4;$selected=NumBE $b 20 4
 Assert ($count -eq 2 -and $b.Length -eq 72 -and $selected -eq 0) 'Expected two entries and first selected'
 $entries=@(for($i=0;$i -lt $count;$i++){$o=24+$i*24;$hex=[Convert]::ToHexString([byte[]]$b[$o..($o+15)]).ToLower();$id=$hex.Substring(0,8)+'-'+$hex.Substring(8,4)+'-'+$hex.Substring(12,4)+'-'+$hex.Substring(16,4)+'-'+$hex.Substring(20,12);Assert (Test-Path -LiteralPath (Join-Path $dir ('sql-drafts/'+$id+'.draft'))) 'Missing real draft';[ordered]@{id=$id;anchor=(NumBE $b ($o+16) 4);caret=(NumBE $b ($o+20) 4)}})
 [ordered]@{capturedAt=(NumBE $b 8 8);selected=$selected;entries=$entries;bytes=$b.Length;sha256=(Get-FileHash -LiteralPath $path).Hash.ToLower()}
}
function ValidateCancel($s){Assert ($s.outcome -eq 'CANCELLED' -and $s.passed -and $s.providerRequests -eq 0 -and $s.dispatcherCloses -eq 0 -and !$s.storeLockReopened -and $s.currentWorkspaceSHA -eq $s.oldWorkspaceSHA) 'Cancel failed old bytes or retained resources'}
function ValidateCompleted($dir){
 $s=ReadJson (Join-Path $dir 'summary.json');$ev=Events $dir
 Assert ($s.outcome -eq 'COMPLETED' -and $s.passed -and $s.providerRequests -eq 0 -and $s.dispatcherCloses -eq 1 -and $s.storeLockReopened) 'Incomplete resource/lock boundary'
 Assert (@($ev|Where-Object{$_.event -match 'FAIL|FATAL|CLEANUP'}).Count -eq 0) 'Failure or fixture cleanup is not product completion'
 Assert (@($ev|Where-Object event -eq 'COMPLETED').Count -eq 1 -and @($ev|Where-Object event -eq 'STORE_LOCK_REOPENED').Count -eq 1) 'Ambiguous completed events'
 $layout=Decode $dir
 Assert ($layout.sha256 -eq $s.currentWorkspaceSHA -and $layout.entries[0].anchor -eq 1 -and $layout.entries[0].caret -eq 7 -and $layout.entries[1].anchor -eq 2 -and $layout.entries[1].caret -eq 8) 'Stored positions/order/hash differ'
 @{summary=$s;layout=$layout}
}
$changed=@(git -C $repo diff --name-only $base -- src buildSrc build.gradle build.gradle.kts settings.gradle settings.gradle.kts gradle.properties gradle gradlew gradlew.bat)
Assert ($LASTEXITCODE -eq 0 -and $changed.Count -eq 0) 'Product/test/build changed'
$oldCount=0
foreach($name in @('workspace-native-exit-coordination','workspace-native-completion-coordination','workspace-native-20261004')){
 $dir=Join-Path $repo ('docs/superpowers/verification/evidence/'+$name);$m=ReadJson (Join-Path $dir 'raw-manifest.json')
 foreach($f in $m.files){$root=if($name -eq 'workspace-native-20261004'){$dir}else{$repo};$p=Join-Path $root $f.path;Assert ((Get-Item -LiteralPath $p).Length -eq $f.bytes -and (Get-FileHash -LiteralPath $p).Hash -eq $f.sha256) ('Historical raw drift: '+$f.path)}
 $oldCount+=@($m.files).Count
}
Assert ($oldCount -eq 951) 'Historical manifest count changed'
$compiled=ReadJson (Join-Path $PSScriptRoot 'compile/compiled.json')
Assert ((ReadJson (Join-Path $PSScriptRoot 'compile/result.json')).exit -eq 0) 'Fresh javac failed'
Assert ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'WorkspaceNativeExitProbe.java')).Hash -eq $compiled.sourceSHA256) 'Compiled source mismatch'
foreach($name in @('WorkspaceNativeExitProbe.java','Invoke-Probe.ps1')){Assert ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $name)).Hash -eq (Get-FileHash -LiteralPath (Join-Path $repo ('docs/superpowers/verification/evidence/workspace-native-20261004/'+$name))).Hash) 'Reviewed external fixture bytes changed'}
foreach($f in $compiled.imageArtifacts){Assert ((Get-FileHash -LiteralPath (Join-Path 'D:/Projects/朝花夕拾/build/jpackage/DataCube' $f.path)).Hash -eq $f.sha256) ('Main image changed: '+$f.path)}
$enterDir=Join-Path $PSScriptRoot 'human-enter/own-profile';$enterEvents=Events $enterDir
$enterKeys=@($enterEvents|Where-Object{$_.event -eq 'DIALOG_KEY' -and $_.details -match '^ENTER;'})
Assert ($enterKeys.Count -eq 1 -and @($enterEvents|Where-Object{$_.event -eq 'DIALOG_KEY' -and $_.details -match '^ESCAPE;'}).Count -eq 0) 'Human Enter evidence differs'
$cancel=ReadJson (Join-Path $enterDir 'summary-1-CANCELLED.json');ValidateCancel $cancel
$cancelEvent=@($enterEvents|Where-Object event -eq 'CANCELLED');$release=@($enterEvents|Where-Object event -eq 'FIXTURE_RELEASE')
Assert ($cancelEvent.Count -eq 1 -and @($enterEvents|Where-Object event -eq 'CANCEL_TASK_PULSE').Count -eq 1 -and $release.Count -eq 1) 'Human cancel/resume/release events differ'
Assert ($enterKeys[0].utc -lt $cancelEvent[0].utc -and $cancelEvent[0].utc -lt $release[0].utc) 'Enter/cancel/release order differs'
$enterCompleted=ValidateCompleted $enterDir
Assert ($enterCompleted.summary.lastDecision -eq 'none') 'Later normal close must not be claimed as native Retry'
# Reconstruct the fixture seed independently from the real final entry bytes.
# It is a hash consistency check; the actual cancellation-time read was recorded by the original probe.
$finalBytes=[IO.File]::ReadAllBytes((Join-Path $enterDir 'sql-drafts/workspace.bin'))
[byte[]]$seed=$finalBytes.Clone();[array]::Clear($seed,8,8);$seed[15]=1
[array]::Copy($finalBytes,48,$seed,24,24);[array]::Copy($finalBytes,24,$seed,48,24)
$seedHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($seed)).ToLower()
Assert ($seedHash -eq $cancel.oldWorkspaceSHA) 'Reconstructed old seed differs from cancellation-time SHA'
$retryDir=Join-Path $PSScriptRoot 'main-retry-desktop/own-profile';$retry=ValidateCompleted $retryDir;$retryEvents=Events $retryDir
Assert ($retry.summary.lastDecision -eq '重试' -and $retry.summary.cancellations -eq 0 -and $retry.summary.workspaceAttemptSHAs[-1] -eq $retry.summary.workspaceAttemptSHAs[-2] -and $retry.summary.workspaceAttemptSHAs[-1] -eq $retry.layout.sha256) 'Main native Retry did not republish same frozen bytes'
Assert (@($retryEvents|Where-Object event -eq 'PRODUCTION_ALERT').Count -eq 1 -and @($retryEvents|Where-Object{$_.event -eq 'PRODUCTION_BUTTON_ACTION' -and $_.details -like '重试;*'}).Count -eq 1) 'Missing actual Retry decision'
$escapeDir=Join-Path $PSScriptRoot 'escape-only/start-snapshot';$escapeEvents=Events $escapeDir;$escapeLayout=Decode $escapeDir
Assert (@($escapeEvents|Where-Object event -eq 'PRODUCTION_ALERT').Count -eq 1 -and @($escapeEvents|Where-Object event -eq 'DIALOG_KEY').Count -eq 0) 'Escape initial snapshot must not assert a delivered key'
$escapePassed=$false
$finalEscapeDir=Join-Path $PSScriptRoot 'escape-only/own-profile'
if(Test-Path -LiteralPath $finalEscapeDir){
 $ev=Events $finalEscapeDir;$s=ReadJson (Join-Path $finalEscapeDir 'summary-1-CANCELLED.json');ValidateCancel $s
 Assert (@($ev|Where-Object{$_.event -eq 'DIALOG_KEY' -and $_.details -match '^ESCAPE;'}).Count -eq 1 -and @($ev|Where-Object event -eq 'CANCEL_TASK_PULSE').Count -eq 1) 'Missing native Escape/cancel/resume'
 Assert ($s.currentWorkspaceSHA -eq $escapeLayout.sha256) 'Escape changed actual before bytes'
 $escapeComplete=ValidateCompleted $finalEscapeDir
 Assert ($escapeComplete.summary.lastDecision -eq '重试') 'Escape fixture final native Retry missing'
 $escapePassed=$true
}
$images=0;$records=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'native') -Filter '*.json' -File)
foreach($file in $records){$r=ReadJson $file.FullName;foreach($s in $r.screenshots){$p=Join-Path $file.DirectoryName $s.file;Assert ((Get-Item -LiteralPath $p).Length -eq $s.bytes -and (Get-FileHash -LiteralPath $p).Hash -eq $s.sha256) 'Native screenshot bytes changed';$images++}}
$manifest=Join-Path $PSScriptRoot 'raw-manifest.json'
if(Test-Path -LiteralPath $manifest){foreach($f in (ReadJson $manifest).files){$p=Join-Path $PSScriptRoot $f.path;Assert ((Get-Item -LiteralPath $p).Length -eq $f.bytes -and (Get-FileHash -LiteralPath $p).Hash -eq $f.sha256) ('New raw changed: '+$f.path)}}
$result=[ordered]@{phase=$Phase;utc=[DateTime]::UtcNow.ToString('o');head=(git -C $repo rev-parse HEAD);baseline=$base;passed=$true;historicalRawVerified=$oldCount;productTestBuildChanged=$false;freshJavacExit=0;currentMainImageArtifactsMatched=3;humanEnterKey=$enterKeys[0];humanCancelSummary=$cancel;oldSeedReconstructedSHA=$seedHash;laterNormalClose=$enterCompleted;mainNativeRetry=$retry;escapePassed=$escapePassed;nativeMatrixPassed=(7+[int]$escapePassed);nativeMatrixUnverified=(1-[int]$escapePassed);nativeGoalComplete=$escapePassed;nativeStateRecords=$records.Count;archivedScreenshotFiles=$images;gradleExecutedThisRound=$false;formalLauncherAccepted=$false;M8Complete=$false;noDatabaseAccess=$true}
$output=Join-Path $PSScriptRoot ('audit-'+$Phase+'.json');Assert (!(Test-Path -LiteralPath $output)) 'Preserve previous audit output'
$result|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $output -Encoding utf8
Write-Output ('Audit '+$Phase+' passed: 951 old raw files unchanged; main native Retry passed; matrix '+$result.nativeMatrixPassed+'/8; '+$images+' stored images; no new Gradle claim.')
