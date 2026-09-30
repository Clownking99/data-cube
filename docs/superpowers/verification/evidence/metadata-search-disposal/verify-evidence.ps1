param([string]$Repository,[string]$Name,[switch]$Staged,[switch]$NoRecord)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $Repository
$evidenceRelative33='docs/superpowers/verification/evidence/metadata-search-disposal'
$evidence33=Join-Path $Repository $evidenceRelative33
$manifest33=Get-Content -LiteralPath (Join-Path $evidence33 'manifest.json') -Raw | ConvertFrom-Json
foreach($entry33 in $manifest33.files) {
 $path33=Join-Path $evidence33 $entry33.path
 if((Get-FileHash -LiteralPath $path33).Hash -ne $entry33.sha256 -or (Get-Item -LiteralPath $path33).Length -ne $entry33.bytes){throw ('Archive bytes mismatch: '+$entry33.path)}
 if($Staged) {
  $gitPath33=$evidenceRelative33+'/'+$entry33.path
  if((git hash-object --no-filters -- $path33) -ne (git rev-parse (':'+$gitPath33))){throw ('Staged raw bytes mismatch: '+$gitPath33)}
 }
}
$result33=Get-Content -LiteralPath (Join-Path $Repository 'docs/superpowers/verification/2026-09-30-metadata-search-disposal-results.json') -Raw | ConvertFrom-Json
foreach($check33 in $result33.checks) {
 $directory33=Join-Path $evidence33 ('checks/'+$check33.name)
 if($check33.logSha256 -and (Get-FileHash -LiteralPath (Join-Path $directory33 $check33.log)).Hash -ne $check33.logSha256){throw 'Archived log mismatch'}
 if($check33.driverLogSha256 -and (Get-FileHash -LiteralPath (Join-Path $directory33 ($check33.name+'-driver.log'))).Hash -ne $check33.driverLogSha256){throw 'Driver log mismatch'}
 if($check33.actualTestTaskRan) {
  $xmlManifest33=Join-Path $directory33 'xml-manifest.txt'
  if((Get-FileHash -LiteralPath $xmlManifest33).Hash -ne $check33.xmlManifestSha256){throw 'XML manifest mismatch'}
  $entries33=@(Get-Content -LiteralPath $xmlManifest33)
  if($entries33.Count -ne $check33.suites){throw 'XML suite count mismatch'}
  foreach($line33 in $entries33) {
   $parts33=$line33.Split(' ',2)
   $xml33=Join-Path $directory33 $parts33[0]
   if((Test-Path -LiteralPath $xml33) -and (Get-FileHash -LiteralPath $xml33).Hash -ne $parts33[1]){throw 'XML bytes mismatch'}
  }
 }
}
$sources33=@(foreach($source33 in $result33.sourceSnapshot.files) {
 $blob33=git hash-object --path=$($source33.path) -- $source33.path
 if($blob33 -ne $source33.gitBlob){throw ('Source blob mismatch: '+$source33.path)}
 if($Staged -and (git rev-parse (':'+$source33.path)) -ne $source33.gitBlob){throw 'Staged source mismatch'}
 [ordered]@{path=$source33.path;gitBlob=$blob33;rawSha256=(Get-FileHash -LiteralPath $source33.path).Hash}
})
if(!$NoRecord) {
 [ordered]@{name=$Name;head=(git rev-parse HEAD);completedAt=(Get-Date).ToString('o');archiveFiles=$manifest33.files.Count;checks=$result33.checks.Count;rawHashesMatch=$true;stagedRawBytesMatch=[bool]$Staged;sourceBlobsMatch=$true;sources=$sources33} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PSScriptRoot ($Name+'.json')) -Encoding utf8
}
Write-Output ('Verified archiveFiles='+$manifest33.files.Count+' checks='+$result33.checks.Count+' staged='+[bool]$Staged)
