param([string]$Repository,[string]$Phase,[string]$Implementation='',[string]$Merge='')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $Repository
$base='docs/superpowers/verification/evidence/metadata-search-cancellation'
$names=@('cancellation-red','cancellation-green','targeted-final','branch-full','branch-buildSrc','branch-image','branch-runtime','main-full','main-buildSrc','main-image','main-runtime')
$records=@()
foreach($name in $names) {
 $path=Join-Path $PSScriptRoot ($name+'.json')
 if(!(Test-Path -LiteralPath $path)){continue}
 $record=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
 $records += $record
 Copy-Item -LiteralPath $path -Destination "$base/checks"
 foreach($suffix in @('.log','-driver.log')) {
  $log=Join-Path $PSScriptRoot ($name+$suffix)
  if(Test-Path -LiteralPath $log){Copy-Item -LiteralPath $log -Destination "$base/checks"}
 }
}
[ordered]@{
 scope='Local metadata search cancellation completion and explicit retry only'
 baseline='83a87f5d9f3ab0448629586f5a9500874e1f2192'
 phase=$Phase
 implementationCommit=$Implementation
 mainMergeCommit=$Merge
 recordedAt=(Get-Date).ToString('o')
 sourceEvidence='evidence/metadata-search-cancellation/branch-source-manifest.json'
 codeStateNotes=@(
  'cancellation-red precedes the product change: 8 cancellation completion failures and 2 closed/invalidated passes',
  'cancellation-green accidentally used the wrong package for SqlExecutionControlTest: only 2 suites ran',
  'targeted-final corrected the package, ran 3 suites and uses the final code source manifest',
  'Before the implementation commit, run HEAD denotes baseline plus uncommitted changes')
 nativeAttempt='evidence/metadata-search-cancellation/desktop/desktop-attempt.json'
 nativeResult='unverified; activation and capture failed; no native screenshots or inputs'
 externalAcceptance='not performed; no real database, credentials, installation, signing, CI or release'
 records=$records
} | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath 'docs/superpowers/verification/2026-09-26-metadata-search-cancellation-results.json' -Encoding utf8
$manifest=@(Get-ChildItem -LiteralPath "$base/checks","$base/desktop" -File | Sort-Object FullName | ForEach-Object {
 [ordered]@{path=([IO.Path]::GetRelativePath((Join-Path $Repository $base),$_.FullName)).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$base/evidence-manifest.json" -Encoding utf8
foreach($record in $records) {
 if($record.logSha256 -and (Get-FileHash -LiteralPath (Join-Path "$base/checks" $record.log)).Hash -ne $record.logSha256) {
  throw "Log hash mismatch: $($record.name)"
 }
}
"Collected $($records.Count) run records and $($manifest.Count) original evidence files"
