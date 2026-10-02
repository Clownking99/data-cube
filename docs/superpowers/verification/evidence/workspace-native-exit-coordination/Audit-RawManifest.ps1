param([ValidateSet('create','verify')][string]$Mode='verify')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$dirs=@('docs/superpowers/verification/evidence/workspace-native-exit-worker','docs/superpowers/verification/evidence/workspace-native-exit-coordination')
$manifest=Join-Path $PSScriptRoot 'raw-manifest.json'
$audit=Join-Path $PSScriptRoot 'raw-manifest-audit.json'
function OwnFiles {
    foreach($dir in $dirs){
        $absolute=[IO.Path]::GetFullPath((Join-Path $repo $dir))
        if(!$absolute.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Manifest scope escaped workspace'}
        foreach($file in Get-ChildItem -LiteralPath $absolute -File -Recurse){
            $path=$file.FullName.Substring($repo.Length+1).Replace('\','/')
            if($path -match '/(raw-manifest(?:-audit)?|(?:developer(?:-correction)?|branch|final)-staged-byte-audit)\.json$'){continue}
            [ordered]@{path=$path;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
        }
    }
}
if($Mode -eq 'create'){
    if(Test-Path -LiteralPath $manifest){throw 'Manifest already created; do not overwrite original snapshot'}
    $files=@(OwnFiles)
    [ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');files=$files;exclusions=@('manifest/audit self','staged-byte audits that grow at later checkpoints')} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifest -Encoding utf8
    Write-Output "New raw manifest contains $($files.Count) files."
}else{
    $snapshot=Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
    $actual=@(OwnFiles);$expected=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach($entry in $snapshot.files){$expected.Add($entry.path,$entry)}
    foreach($entry in $actual){if(!$expected.ContainsKey($entry.path) -or $entry.bytes -ne $expected[$entry.path].bytes -or $entry.sha256 -ne $expected[$entry.path].sha256){throw ('Raw evidence drift: '+$entry.path)}}
    if($actual.Count -ne $expected.Count){throw 'Raw evidence file count changed'}
    [ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');files=$actual.Count;passed=$true;manifestSha256=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash} | ConvertTo-Json | Set-Content -LiteralPath $audit -Encoding utf8
    Write-Output "Verified $($actual.Count) unchanged raw evidence files."
}

