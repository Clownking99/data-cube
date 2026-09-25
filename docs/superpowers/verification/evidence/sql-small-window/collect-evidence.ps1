param([string]$Repository, [string]$Phase, [string]$Implementation = '', [string]$Merge = '')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $Repository
$base='docs/superpowers/verification/evidence/sql-small-window'
$names=@('viewport-red','viewport-red-corrected','viewport-green-candidate','keyboard-entry-check','targeted-final','branch-final-full','targeted-final-expanded','branch-full-verified','branch-buildSrc','branch-image','branch-runtime','main-full','main-buildSrc','main-image','main-runtime')
$records=@()
foreach($name in $names) {
 $recordPath=Join-Path $PSScriptRoot ($name+'.json')
 if(!(Test-Path -LiteralPath $recordPath)){continue}
 $record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
 $records += $record
 Copy-Item -LiteralPath $recordPath -Destination "$base/checks"
 foreach($suffix in @('.log','-driver.log')) {
  $log=Join-Path $PSScriptRoot ($name+$suffix)
  if(Test-Path -LiteralPath $log){Copy-Item -LiteralPath $log -Destination "$base/checks"}
 }
}
[ordered]@{
 scope='Local SQL small-window follow-up only; native and external acceptance pending'
 baseline='5b721aa62bf1d1e616552094740be8e87051a8c4'
 phase=$Phase
 implementationCommit=$Implementation
 mainMergeCommit=$Merge
 recordedAt=(Get-Date).ToString('o')
 sourceEvidence='evidence/sql-small-window/branch-source-manifest.json'
 codeStateNotes=@(
  'viewport-red and viewport-red-corrected precede the product change; first also has a newline assertion mistake',
  'branch-final-full is the preserved failed run before four fixture CSS initialization fixes',
  'targeted-final-expanded, branch-full-verified and later builds use final source manifest',
  'Early run HEAD is the baseline with uncommitted changes, not evidence that pristine baseline passed')
 nativeAttempt='evidence/sql-small-window/desktop/attempt.json'
 nativeResult='unverified; two desktop tool failures; no screenshot or input succeeded'
 externalAcceptance='not performed; no real database, credentials, installation, signing, CI or release'
 records=$records
} | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath 'docs/superpowers/verification/2026-09-25-sql-small-window-results.json' -Encoding utf8
$manifest=@(Get-ChildItem -LiteralPath "$base/checks","$base/desktop" -File | Sort-Object FullName | ForEach-Object {
 [ordered]@{path=([IO.Path]::GetRelativePath((Join-Path $Repository $base),$_.FullName)).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$base/evidence-manifest.json" -Encoding utf8
foreach($record in $records) {
 if($record.logSha256) {
  $actual=(Get-FileHash -LiteralPath (Join-Path "$base/checks" $record.log)).Hash
  if($actual -ne $record.logSha256){throw "Log hash mismatch: $($record.name)"}
 }
}
"Collected $($records.Count) records; verified $($manifest.Count) evidence files"
