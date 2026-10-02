param([Parameter(Mandatory=$true)][ValidateSet('developer','branch','final')][string]$Scope)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$developer='docs/superpowers/verification/evidence/schema-diff-stability'
$coordination='docs/superpowers/verification/evidence/schema-diff-stability-coordination'
$test='test/com/datacube/service/SchemaDiffServiceTest.java'
$documents=@('docs/superpowers/verification/2026-10-02-schema-diff-stability.md')
$directories=@($developer)
if($Scope -ne 'developer'){
    $documents+=@('docs/superpowers/verification/2026-10-02-schema-diff-stability-coordination.md','docs/superpowers/plans/2026-10-02-schema-diff-stability.md')
    $directories+=$coordination
}
if($Scope -eq 'final'){$documents+=@('docs/handoffs/2026-09-23-product-maturity-goal-handoff.md','docs/superpowers/plans/2026-09-23-product-maturity-roadmap.md')}
$auditPath=$coordination+'/'+$Scope+'-staged-byte-audit.json'
foreach($path in (git -C $repo -c core.quotepath=false diff --cached --name-only -- . ':(exclude).testagent' ':(exclude).testagent/**')){
    $allowed=$path -eq $test -or $path -in $documents
    foreach($directory in $directories){$allowed=$allowed -or $path.StartsWith($directory+'/',[StringComparison]::Ordinal)}
    if(!$allowed){throw 'Unexpected staged scope'}
}
$info=[Diagnostics.ProcessStartInfo]::new('git');$info.WorkingDirectory=$repo;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
$info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$info.ArgumentList.Add('cat-file');$info.ArgumentList.Add('--batch')
$process=[Diagnostics.Process]::new();$process.StartInfo=$info;[void]$process.Start();$stream=$process.StandardOutput.BaseStream
$entries=[Collections.Generic.List[object]]::new();$strictUtf8=[Text.UTF8Encoding]::new($false,$true)
try{
    $paths=@($test)+$documents+$directories
    foreach($line in (git -C $repo -c core.quotepath=false ls-files --stage -- @paths)){
        if($line -notmatch '^100644 ([0-9a-f]{40}) 0\t(.+)$'){throw 'Unexpected index entry'}
        $oid=$Matches[1];$path=$Matches[2]
        if($path -eq $auditPath){continue}
        $absolute=[IO.Path]::GetFullPath((Join-Path $repo $path))
        if(!$absolute.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Audited path escaped repository'}
        $raw=[IO.File]::ReadAllBytes($absolute)
        $process.StandardInput.WriteLine($oid);$process.StandardInput.Flush()
        $header=[Text.StringBuilder]::new()
        while(($value=$stream.ReadByte()) -ne 10){if($value -lt 0){throw 'Unexpected EOF'};[void]$header.Append([char]$value)}
        if($header.ToString() -notmatch '^([0-9a-f]{40}) blob (\d+)$' -or $Matches[1] -ne $oid){throw 'Unexpected blob header'}
        $blob=[byte[]]::new([int]$Matches[2]);$read=0
        while($read -lt $blob.Length){$count=$stream.Read($blob,$read,$blob.Length-$read);if($count -le 0){throw 'Truncated blob'};$read+=$count}
        if($stream.ReadByte() -ne 10){throw 'Missing blob delimiter'}
        $rawHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw));$blobHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($blob))
        $rawEquals=$raw.Length -eq $blob.Length -and $rawHash -eq $blobHash
        $testOnlyNewlines=$false
        if(!$rawEquals -and $path -eq $test){
            $normalized=$strictUtf8.GetBytes($strictUtf8.GetString($raw).Replace("`r`n","`n"))
            $testOnlyNewlines=$normalized.Length -eq $blob.Length -and [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($normalized)) -eq $blobHash
        }
        if(!$rawEquals -and !$testOnlyNewlines){throw ('Raw evidence and staged blob differ: '+$path)}
        $entries.Add([ordered]@{path=$path;rawBytes=$raw.Length;rawSha256=$rawHash;stagedBytes=$blob.Length;stagedSha256=$blobHash;blob=$oid;rawEqualsStaged=$rawEquals;testOnlyLfCrlf=$testOnlyNewlines})
    }
    $process.StandardInput.Close();$process.WaitForExit();if($process.ExitCode -ne 0){throw 'Git byte audit failed'}
}finally{$process.Dispose()}
[ordered]@{scope=$Scope;hostUtc=[DateTime]::UtcNow.ToString('o');passed=$true;verified=$entries.Count;excludedSelf=$auditPath;testCheckoutNormalizationOnly=@($entries | Where-Object {$_.testOnlyLfCrlf});files=$entries} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $repo $auditPath) -Encoding utf8
Write-Output "$Scope staged byte audit: $($entries.Count) files; all evidence raw bytes exact, test-only CRLF normalization explicitly proved."
