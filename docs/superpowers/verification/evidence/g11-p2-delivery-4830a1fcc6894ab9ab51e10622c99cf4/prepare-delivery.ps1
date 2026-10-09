$ErrorActionPreference='Stop'
$repo=(Get-Location).Path
$evidence=Join-Path $repo 'docs/superpowers/verification/evidence'
$base=$PSScriptRoot
function Guard([string]$Path){
 if($Path -match '(?i)(^|[\\/])(\.testagent|\.git|\.g10-verify-blobs\.ps1)([\\/]|$)' -or $Path -match '(^|[\\/])\.\.([\\/]|$)'){throw 'Forbidden delivery path'}
 $full=[IO.Path]::GetFullPath($Path)
 if(-not $full.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Outside repo'}
 for($p=$full;$p -ne $repo;$p=[IO.Path]::GetDirectoryName($p)){
  if([IO.File]::Exists($p) -or [IO.Directory]::Exists($p)){if(([IO.File]::GetAttributes($p) -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'Reparse delivery path'}}
 }
 return $full
}
function Sha([string]$Path){return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([IO.File]::ReadAllBytes($Path))).ToLowerInvariant()}
$rows=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::OrdinalIgnoreCase)
function Add([string]$Path,$Expected){
 $full=Guard $Path;$raw=[IO.File]::ReadAllBytes($full);$sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw)).ToLowerInvariant()
 if($null -ne $Expected -and ($raw.LongLength -ne $Expected.length -or $sha -ne $Expected.sha256)){throw "Frozen mismatch $Path"}
 $relative=[IO.Path]::GetRelativePath($repo,$full).Replace('\','/')
 $rows[$relative]=[ordered]@{path=$relative;length=$raw.LongLength;sha256=$sha}
}
$manifests=@(
 @{name='g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935-rejected';sha='95a136cd033d36fdcee971106b3e84bc6d01cf056cbaa352099e52ff7fac3624';count=736;repoRelative=$true},
 @{name='g11-p2-rx2-e46234f8c9fa48e9b504156a3f0c9054-frozen';sha='b2de1c70097c8baa033030a469dbca6e10a2d133b4ee924d2325e08ffaedcce9';count=1357;repoRelative=$false},
 @{name='g11-p2-eng-11965af76c694c6798f2074fff74d8bf-rejected';sha='9cc7a935cbfe5ac1d7c4826c9cdb5e4ceff99be3bb88438ace25002b5bb3552f';count=213;repoRelative=$false},
 @{name='g11-p2-eng2-861d631887ec4c9790f3deb3f062ecae-frozen';sha='02cb7a0b73bd7647b3ddaead8fdf2395d5bc51cc4c1f5ea8368f82c1da77e479';count=995;repoRelative=$false}
)
foreach($binding in $manifests){
 $path=Guard (Join-Path $evidence ($binding.name+'/manifest.json'))
 if((Sha $path) -ne $binding.sha){throw 'Manifest identity'}
 $manifest=[Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($path))|ConvertFrom-Json
 if($manifest.files.Count -ne $binding.count){throw 'Manifest count'}
 foreach($row in $manifest.files){
  $parent=$(if($binding.repoRelative){$repo}else{$evidence})
  Add (Join-Path $parent $row.path) $row
 }
 Add $path $null
}
$extras=@('g11-p2-root-exit-source-abddf454863f4846921eea16c475b20d','g11-p2-root-exit-final-source-0c7e63f31b1043aab36e321208837958','g11-p2-root-exit-controller-review-b1c95f19622f4eabb00d399aa91c6e33','g11-p2-eng-native-review-3c86ac32bf824e729d81c7ef043c885a')
foreach($name in $extras){
 $root=Guard (Join-Path $evidence $name)
 foreach($file in [IO.Directory]::EnumerateFiles($root,'*',[IO.SearchOption]::TopDirectoryOnly)){Add $file $null}
 if([IO.Directory]::EnumerateDirectories($root).GetEnumerator().MoveNext()){throw 'Unexpected nested extra root'}
}
Add (Join-Path $repo 'docs/superpowers/verification/2026-10-09-g11-root-exit-observation-worker.md') $null
Add (Join-Path $repo 'docs/superpowers/verification/.gitattributes') $null
foreach($name in @('prepare-delivery.ps1','audit-blobs.ps1')){Add (Join-Path $base $name) $null}
$payload=[ordered]@{schema='g11-delivery-payload/v1';sourceCommit='a59b3f15ae21c1514acfc5060b15ab41fe90c94b';manifests=$manifests;extraRoots=$extras;fileCount=$rows.Count;files=@($rows.Values|Sort-Object path);selfExcluded='delivery-payload.json and delivery-paths.nul are separately hashed and blob-verified by audit-blobs.ps1.'}
$json=[Text.UTF8Encoding]::new($false).GetBytes(($payload|ConvertTo-Json -Depth 8)+"`n")
[IO.File]::WriteAllBytes((Join-Path $base 'delivery-payload.json'),$json)
$paths=@($rows.Keys)+@([IO.Path]::GetRelativePath($repo,(Join-Path $base 'delivery-payload.json')).Replace('\','/'),[IO.Path]::GetRelativePath($repo,(Join-Path $base 'delivery-paths.nul')).Replace('\','/'))
$nul=[Text.UTF8Encoding]::new($false).GetBytes(($paths|Sort-Object) -join [char]0)
[IO.File]::WriteAllBytes((Join-Path $base 'delivery-paths.nul'),$nul)
[pscustomobject]@{payloadFiles=$rows.Count;stagingPaths=$paths.Count;payloadSha=Sha (Join-Path $base 'delivery-payload.json');pathListSha=Sha (Join-Path $base 'delivery-paths.nul')}
