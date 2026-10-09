Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Get-Location).Path;$prefix=Join-Path $repo 'docs/superpowers/verification/evidence';$tag='g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935'
$roots=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach($name in @($tag,($tag+'-python'),($tag+'-process'),($tag+'-root-exit'),'g11-p2-root-exit-path-fix-f2651246cd2e4a219312a113267034d9')){$null=$roots.Add((Join-Path $prefix $name))}
foreach($group in @('process','root-exit')){foreach($file in [IO.Directory]::EnumerateFiles((Join-Path $prefix ($tag+'-'+$group)),'*-owner-spec.json',[IO.SearchOption]::TopDirectoryOnly)){$s=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json;foreach($p in @($s.out,$s.stageEvidence)){$null=$roots.Add($p)}}}
$files=[Collections.Generic.List[object]]::new();$queue=[Collections.Generic.Queue[string]]::new()
foreach($root in $roots){if(-not $root.StartsWith(($prefix+[IO.Path]::DirectorySeparatorChar),[StringComparison]::OrdinalIgnoreCase)){throw 'ROOT_OUTSIDE_EVIDENCE'};$queue.Enqueue($root)}
while($queue.Count){$directory=$queue.Dequeue();foreach($path in [IO.Directory]::GetFileSystemEntries($directory)){
 foreach($part in ($path -split '[\\/]')){if($part.TrimEnd(' ','.').ToLowerInvariant() -in @('.testagent','.git','.g10-verify-blobs.ps1')){throw 'FORBIDDEN_COMPONENT'}}
 $attrs=[IO.File]::GetAttributes($path);if(($attrs -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'REPARSE'}
 if(($attrs -band [IO.FileAttributes]::Directory) -ne 0){$queue.Enqueue($path)}else{$files.Add(@{path=[IO.Path]::GetRelativePath($repo,$path).Replace('\','/');length=([IO.FileInfo]$path).Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()})}
}}
$final=Join-Path $prefix ($tag+'-rejected');$null=[IO.Directory]::CreateDirectory($final);$manifest=Join-Path $final 'manifest.json'
@{schema='frozen-evidence/v1';accepted=$false;phase='G11 root-exit control first attempt';testedCommit='e368a1b16bdee226925b363f06b4dc4f003c1f0f';roots=@($roots|Sort-Object|ForEach-Object {[IO.Path]::GetRelativePath($repo,$_).Replace('\','/')});files=@($files|Sort-Object path);fileCount=$files.Count;totalBytes=($files|Measure-Object length -Sum).Sum;runtimeTreesExcluded=$true;passedGroups=@{python=21;process=21};firstFailedCase='exit-tail-zero';expectedTotal=98;reason='264-character tail gate exists in .NET but Python Path.is_file=false/stat WinError3; DEADLINE retained, no retry';engineeringRun=$false}|ConvertTo-Json -Depth 10|Set-Content -LiteralPath $manifest -Encoding utf8NoBOM
@{manifest=$manifest;sha256=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash;roots=$roots.Count;files=$files.Count;bytes=($files|Measure-Object length -Sum).Sum}|ConvertTo-Json