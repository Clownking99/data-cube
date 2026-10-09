param([string]$Revision='',[Parameter(Mandatory)][string]$Receipt)
$ErrorActionPreference='Stop'
$repo=(Get-Location).Path;$base=$PSScriptRoot
function GitBytes([string[]]$Arguments,[string]$InputText=''){
 $info=[Diagnostics.ProcessStartInfo]::new('git');$info.WorkingDirectory=$repo;$info.UseShellExecute=$false
 $info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
 foreach($arg in $Arguments){$info.ArgumentList.Add($arg)}
 $process=[Diagnostics.Process]::new();$process.StartInfo=$info;$null=$process.Start()
 $memory=[IO.MemoryStream]::new();$copy=$process.StandardOutput.BaseStream.CopyToAsync($memory);$err=$process.StandardError.ReadToEndAsync()
 if($InputText){$process.StandardInput.Write($InputText)};$process.StandardInput.Close()
 $process.WaitForExit();$null=$copy.GetAwaiter().GetResult();$errors=$err.GetAwaiter().GetResult()
 if($process.ExitCode -ne 0){throw "Git actual exit $($process.ExitCode): $errors"}
 return ,$memory.ToArray()
}
function Guard([string]$Relative){
 if($Relative -match '(?i)(^|[\\/])(\.testagent|\.git|\.g10-verify-blobs\.ps1)([\\/]|$)' -or $Relative -match '(^|[\\/])\.\.([\\/]|$)' -or [IO.Path]::IsPathRooted($Relative)){throw 'Forbidden blob path'}
 $full=[IO.Path]::GetFullPath((Join-Path $repo $Relative))
 if(-not $full.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Outside repo'}
 return $full
}
function Sha([byte[]]$Raw){return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Raw)).ToLowerInvariant()}
$payloadPath=Join-Path $base 'delivery-payload.json';$pathsPath=Join-Path $base 'delivery-paths.nul'
$payload=[Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($payloadPath))|ConvertFrom-Json
$expected=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
foreach($row in $payload.files){$expected.Add($row.path,$row)}
foreach($path in @($payloadPath,$pathsPath)){
 $raw=[IO.File]::ReadAllBytes($path);$relative=[IO.Path]::GetRelativePath($repo,$path).Replace('\','/')
 $expected.Add($relative,@{path=$relative;length=$raw.LongLength;sha256=(Sha $raw)})
}
$pathList=[Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($pathsPath)).Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)
if(@($pathList).Count -ne $expected.Count){throw 'Path list count'}
foreach($name in $pathList){if(-not $expected.ContainsKey($name)){throw 'Unknown staging path'}}
if($Revision){$rawArgs=@('diff-tree','--no-commit-id','--raw','-r','-z','--no-abbrev','--no-renames',$Revision,'--','docs/superpowers/verification')}
else{$rawArgs=@('diff','--cached','--raw','-z','--no-abbrev','--no-renames','--','docs/superpowers/verification','scripts/verification')}
$rawDiff=[Text.Encoding]::UTF8.GetString((GitBytes $rawArgs)).Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)
$blobs=[Collections.Generic.List[object]]::new()
if($rawDiff.Count -ne 2*$expected.Count){throw "Unexpected staged/committed count $($rawDiff.Count/2) expected $($expected.Count)"}
for($i=0;$i -lt $rawDiff.Count;$i+=2){
 $meta=$rawDiff[$i].Split(' ');$path=$rawDiff[$i+1]
 if(-not $expected.ContainsKey($path) -or $meta[4] -notin @('A','M')){throw "Unexpected staged/committed path $path"}
 $row=$expected[$path];$raw=[IO.File]::ReadAllBytes((Guard $path))
 if($raw.LongLength -ne $row.length -or (Sha $raw) -ne $row.sha256){throw "Disk bytes changed $path"}
 $blobs.Add(@{path=$path;oid=$meta[3];length=$row.length;sha256=$row.sha256})
}
$toolNames=@('OwnedProcessHost.ps1','VerificationCore.psm1','check-core.py','run-stage.ps1')
$toolDiff=[Text.Encoding]::UTF8.GetString((GitBytes @('diff-tree','--no-commit-id','--raw','-r','-z','--no-abbrev',$payload.sourceCommit,'--','scripts/verification'))).Split([char]0,[StringSplitOptions]::RemoveEmptyEntries)
if($toolDiff.Count -ne 8){throw 'Source commit exact four'}
for($i=0;$i -lt $toolDiff.Count;$i+=2){
 $meta=$toolDiff[$i].Split(' ');$path=$toolDiff[$i+1]
 if([IO.Path]::GetFileName($path) -notin $toolNames){throw 'Unexpected source file'}
 $raw=[IO.File]::ReadAllBytes((Guard $path));$blobs.Add(@{path=$path;oid=$meta[3];length=$raw.LongLength;sha256=(Sha $raw)})
}
$requests=($blobs|ForEach-Object {$_.oid}) -join "`n"
$batch=GitBytes @('cat-file','--batch') ($requests+"`n")
$offset=0
foreach($row in $blobs){
 $start=$offset;while($offset -lt $batch.Length -and $batch[$offset] -ne 10){$offset++}
 if($offset -ge $batch.Length){throw 'Missing blob header'}
 $header=[Text.Encoding]::ASCII.GetString($batch,$start,$offset-$start).Split(' ');$offset++
 if($header[0] -ne $row.oid -or $header[1] -ne 'blob' -or [long]$header[2] -ne $row.length){throw 'Blob header mismatch'}
 $content=[byte[]]::new([int]$row.length);[Array]::Copy($batch,$offset,$content,0,$content.Length);$offset+=$content.Length
 if((Sha $content) -ne $row.sha256 -or $batch[$offset] -ne 10){throw "Blob raw mismatch $($row.path)"};$offset++
}
if($offset -ne $batch.Length){throw 'Trailing blob output'}
$result=[ordered]@{schema='g11-delivery-blob-audit/v1';passed=$true;revision=$Revision;sourceCommit=$payload.sourceCommit;deliveryPaths=$expected.Count;sourcePaths=4;blobsVerified=$blobs.Count;payloadSha256=(Sha ([IO.File]::ReadAllBytes($payloadPath)));pathListSha256=(Sha ([IO.File]::ReadAllBytes($pathsPath)));reportSha256=(Sha ([IO.File]::ReadAllBytes((Join-Path $repo 'docs/superpowers/verification/2026-10-09-g11-root-exit-observation-worker.md'))));files=$blobs}
[IO.File]::WriteAllText($Receipt,(($result|ConvertTo-Json -Depth 6)+"`n"),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{passed=$true;deliveryPaths=$expected.Count;blobsVerified=$blobs.Count;payloadSha256=$result.payloadSha256;pathListSha256=$result.pathListSha256;reportSha256=$result.reportSha256}
