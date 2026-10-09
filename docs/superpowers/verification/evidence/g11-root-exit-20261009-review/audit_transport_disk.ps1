param([Parameter(Mandatory)][string]$Checkout,[Parameter(Mandatory)][string]$OutputName)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($Checkout -notin @('D:/Projects/朝花夕拾','C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾')){throw 'checkout'}
if($OutputName -notmatch '^transport-(worker|main)-disk\.json$'){throw 'output name'}
$repo=[IO.Path]::GetFullPath($Checkout)
$out=Join-Path $PSScriptRoot $OutputName
if([IO.File]::Exists($out)){throw 'output exists'}
$receipt=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'transport-blobs-review.json'))|ConvertFrom-Json -DateKind String
if(-not $receipt.committedBlobsAccepted -or $receipt.files -ne 3324){throw 'missing blob review'}
$total=0L;$longest=0;$longCount=0
foreach($row in $receipt.entries){
    $relative=$row.path
    if($relative -match '[:\\\r\n\t]' -or $relative -notmatch '^(docs/superpowers/verification/|scripts/verification/)'){throw 'relative scope'}
    foreach($part in $relative.Split('/')){if(-not $part -or $part -in @('.','..','.testagent','.git','.g10-verify-blobs.ps1') -or $part -ne $part.TrimEnd(' ','.')){throw 'forbidden component'}}
    $path=[IO.Path]::GetFullPath([IO.Path]::Combine($repo,$relative))
    if(-not $path.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'outside checkout'}
    $node=$path
    while($node){if(([IO.File]::GetAttributes($node) -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'linked path'};$node=[IO.Path]::GetDirectoryName($node)}
    $bytes=[IO.File]::ReadAllBytes($path)
    $sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    if($bytes.LongLength -ne $row.length -or $sha -ne $row.sha256){throw "Disk transport mismatch: $relative"}
    $total+=$bytes.LongLength;$longest=[Math]::Max($longest,$path.Length);if($path.Length -gt 259){$longCount++}
}
$result=[ordered]@{schema='root-transport-disk/v1';checkout=$repo;revision=$receipt.revision;files=$receipt.files;bytes=$total;rawDiskMatchesCommittedBlobs=$true;longestPath=$longest;pathsOver259=$longCount;readApi='.NET ReadAllBytes; no recursive enumeration';fullAcceptance=$false}
[IO.File]::WriteAllText($out,($result|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
$result|ConvertTo-Json
