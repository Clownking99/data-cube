$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$e='docs/superpowers/verification/evidence/sol-p3-main'
$documents=@('docs/superpowers/verification/2026-10-02-sol-p3-main-acceptance.md','docs/superpowers/verification/2026-10-02-sol-coordination-review.md','docs/superpowers/plans/2026-10-02-sol-development-coordination.md','docs/handoffs/2026-09-23-product-maturity-goal-handoff.md','docs/superpowers/plans/2026-09-23-product-maturity-roadmap.md')
$auditPath=$e+'/staged-byte-audit.json'
$staged=@(& git -C $repo -c core.quotepath=false diff --cached --name-only -- . ':(exclude).testagent' ':(exclude).testagent/**')
foreach($path in $staged){if($path -notin $documents -and -not $path.StartsWith($e+'/',[StringComparison]::Ordinal)){throw 'Unexpected staged scope'}}
$info=[Diagnostics.ProcessStartInfo]::new('git')
$info.WorkingDirectory=$repo;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
$info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$info.ArgumentList.Add('cat-file');$info.ArgumentList.Add('--batch')
$process=[Diagnostics.Process]::new();$process.StartInfo=$info
[void]$process.Start();$stream=$process.StandardOutput.BaseStream
$entries=[Collections.Generic.List[object]]::new()
try{
 foreach($line in (& git -C $repo -c core.quotepath=false ls-files --stage -- $e @documents)){
  if($line -notmatch '^100644 ([0-9a-f]{40}) 0\t(.+)$'){throw 'Unexpected index entry'}
  $oid=$Matches[1];$relative=$Matches[2]
  if($relative -eq $auditPath){continue}
  if($relative -notin $documents -and -not $relative.StartsWith($e+'/',[StringComparison]::Ordinal)){throw 'Unexpected audited path'}
  $absolute=[IO.Path]::GetFullPath((Join-Path $repo $relative))
  if(-not $absolute.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Path escaped repository'}
  $raw=[IO.File]::ReadAllBytes($absolute)
  $process.StandardInput.WriteLine($oid);$process.StandardInput.Flush()
  $header=[Text.StringBuilder]::new()
  while(($value=$stream.ReadByte()) -ne 10){if($value -lt 0){throw 'Unexpected EOF'};[void]$header.Append([char]$value)}
  if($header.ToString() -notmatch '^([0-9a-f]{40}) blob (\d+)$' -or $Matches[1] -ne $oid){throw 'Unexpected blob header'}
  $size=[int]$Matches[2];$blob=[byte[]]::new($size);$read=0
  while($read -lt $size){$count=$stream.Read($blob,$read,$size-$read);if($count -le 0){throw 'Truncated blob'};$read+=$count}
  if($stream.ReadByte() -ne 10){throw 'Missing blob delimiter'}
  $rawHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw))
  $blobHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($blob))
  if($raw.Length -ne $blob.Length -or $rawHash -ne $blobHash){throw ('Raw and staged bytes differ: '+$relative)}
  $entries.Add([ordered]@{path=$relative;bytes=$raw.Length;sha256=$rawHash;stagedBytes=$blob.Length;stagedSha256=$blobHash;blob=$oid})
 }
 $process.StandardInput.Close();$process.WaitForExit();if($process.ExitCode -ne 0){throw 'Git blob audit failed'}
}finally{$process.Dispose()}
[ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');passed=$true;verified=$entries.Count;excludedSelf=$auditPath;files=$entries} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $repo $auditPath) -Encoding utf8
Write-Output "P3 actual staged blob bytes match raw files: $($entries.Count) files; audit self excluded."
