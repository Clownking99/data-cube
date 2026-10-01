$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$e='docs/superpowers/verification/evidence/sol-p0-p2'
$report='docs/superpowers/verification/2026-10-02-sol-p0-p2-acceptance.md'
$auditPath="$e/staged-byte-audit.json"
$entries=[Collections.Generic.List[object]]::new()
$info=[Diagnostics.ProcessStartInfo]::new('git')
$info.WorkingDirectory=$repo;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
$info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$info.ArgumentList.Add('cat-file');$info.ArgumentList.Add('--batch')
$process=[Diagnostics.Process]::new();$process.StartInfo=$info
[void]$process.Start()
$stream=$process.StandardOutput.BaseStream
try {
 foreach($line in (& git -C $repo -c core.quotepath=false ls-files --stage -- $e $report)) {
  if($line -notmatch '^\d+ ([0-9a-f]+) 0\t(.+)$'){throw "Unexpected index entry: $line"}
  $oid=$Matches[1];$relative=$Matches[2]
  if($relative -eq $auditPath){continue}
  $raw=[IO.File]::ReadAllBytes((Join-Path $repo $relative))
  $process.StandardInput.WriteLine($oid);$process.StandardInput.Flush()
  $header=[Text.StringBuilder]::new()
  while(($value=$stream.ReadByte()) -ne 10) {
   if($value -lt 0){throw 'Unexpected batch EOF'}
   [void]$header.Append([char]$value)
  }
  if($header.ToString() -notmatch '^([0-9a-f]+) blob (\d+)$'){throw "Unexpected blob header: $header"}
  $size=[int]$Matches[2];$blob=[byte[]]::new($size);$read=0
  while($read -lt $size) {
   $count=$stream.Read($blob,$read,$size-$read)
   if($count -le 0){throw 'Truncated staged blob'}
   $read+=$count
  }
  if($stream.ReadByte() -ne 10){throw 'Missing batch delimiter'}
  $rawHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw))
  $blobHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($blob))
  if($raw.Length -ne $blob.Length -or $rawHash -ne $blobHash){throw "Staged bytes differ: $relative"}
  $entries.Add([ordered]@{path=$relative;bytes=$raw.Length;sha256=$rawHash;stagedBytes=$blob.Length;stagedSha256=$blobHash;blob=$oid})
 }
 $process.StandardInput.Close();$process.WaitForExit()
 if($process.ExitCode -ne 0){throw $process.StandardError.ReadToEnd()}
} finally {$process.Dispose()}
[ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');passed=$true;verified=$entries.Count;excludedSelf=$auditPath;files=$entries} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $repo $auditPath) -Encoding utf8
Write-Output "Actual staged blob bytes match raw files: $($entries.Count) files (audit self excluded)."
