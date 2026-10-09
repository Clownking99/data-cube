Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$worker='C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾'
$manifestRelative='docs/superpowers/verification/evidence/g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935-rejected/manifest.json'
function Require([bool]$Condition,[string]$Message) { if(-not $Condition){throw $Message} }
function SafePath([string]$Relative) {
    Require ($Relative.StartsWith('docs/superpowers/verification/evidence/') -and $Relative -notmatch '[:\\\r\n\t]') 'unadmitted relative path'
    foreach($part in $Relative.Split('/')){Require ($part -and $part -notin @('.','..','.testagent','.git') -and $part -eq $part.TrimEnd(' ','.')) 'forbidden path component'}
    $path=[IO.Path]::GetFullPath([IO.Path]::Combine($worker,$Relative))
    Require ($path.StartsWith([IO.Path]::GetFullPath($worker)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) 'outside worker'
    $node=$path
    while($node){Require (([IO.File]::GetAttributes($node) -band [IO.FileAttributes]::ReparsePoint) -eq 0) 'reparse';$node=[IO.Path]::GetDirectoryName($node)}
    return $path
}
function Sha([string]$Path){return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([IO.File]::ReadAllBytes($Path))).ToLowerInvariant()}
$manifestPath=SafePath $manifestRelative
$manifestSha=Sha $manifestPath
Require ($manifestSha -eq '95a136cd033d36fdcee971106b3e84bc6d01cf056cbaa352099e52ff7fac3624') 'manifest identity'
$manifest=[IO.File]::ReadAllText($manifestPath)|ConvertFrom-Json -DateKind String
Require ($manifest.accepted -eq $false -and $manifest.fileCount -eq 736 -and $manifest.roots.Count -eq 49) 'rejected archive schema'
$expected=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$total=0L
foreach($row in $manifest.files){
    $path=SafePath $row.path
    Require ($expected.Add($row.path)) 'duplicate file'
    Require (([IO.FileInfo]$path).Length -eq $row.length -and (Sha $path) -eq $row.sha256) ('raw identity '+$row.path)
    $total+=$row.length
}
$actual=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
function Inventory([string]$Directory){
    foreach($entry in [IO.Directory]::GetFileSystemEntries($Directory)){
        $relative=[IO.Path]::GetRelativePath($worker,$entry).Replace('\','/')
        $safe=SafePath $relative
        if([IO.Directory]::Exists($safe)){Inventory $safe}else{Require ($actual.Add($relative)) 'duplicate inventory'}
    }
}
foreach($relative in $manifest.roots){
    $name=$relative.Split('/')[4]
    Require ($name.StartsWith('g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935') -or $name.StartsWith('g11-p2-synthetic-g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935-') -or $name -eq 'g11-p2-root-exit-path-fix-f2651246cd2e4a219312a113267034d9') 'foreign root'
    Inventory (SafePath $relative)
}
Require ($actual.SetEquals($expected) -and $total -eq 8413693) 'inventory or bytes'
$fix='docs/superpowers/verification/evidence/g11-p2-root-exit-path-fix-f2651246cd2e4a219312a113267034d9/'
$python=[IO.File]::ReadAllText((SafePath ($fix+'python-stdout.raw')))|ConvertFrom-Json -DateKind String
$dotnet=[IO.File]::ReadAllText((SafePath ($fix+'powershell-stdout.raw')))|ConvertFrom-Json -DateKind String
$commands=[IO.File]::ReadAllText((SafePath ($fix+'probe-commands.json')))|ConvertFrom-Json -DateKind String
Require ($commands.python.actualExit -eq 0 -and $commands.powershell.actualExit -eq 0) 'diagnostic exit'
Require ($python.pathLength -eq 264 -and -not $python.isFile -and $python.matchesHostGate -and $python.statFailure.winerror -eq 3) 'Python diagnostic'
$out=[IO.Path]::Combine($PSScriptRoot,'rejected-controls-review.json')
Require (-not [IO.File]::Exists($out)) 'output collision'
$receipt=[ordered]@{schema='root-rejected-controls-review/v1';manifestSha256=$manifestSha;archiveVerified=$true;roots=49;files=736;bytes=$total;accepted=$false;diagnosticActualExit=0;pythonPathLength=264;pythonVisible=$false;pythonWinError=3;dotnetDiagnostic=$dotnet;reviewRuntime=$PSVersionTable.PSVersion.ToString()}
[IO.File]::WriteAllText($out,($receipt|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$receipt|ConvertTo-Json -Depth 8
