param([Parameter(Mandatory=$true)][string]$Profile,[Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference='Stop'
$resolved=(Resolve-Path -LiteralPath $Profile).Path
if($resolved -notmatch '^C:\\Users\\hetia\\AppData\\Local\\Temp\\datacube-workspace-native-profile-[a-f0-9-]+$'){throw 'Only own isolated fixture profiles allowed'}
if(Test-Path -LiteralPath $Destination){throw 'Do not overwrite an evidence snapshot'}
New-Item -ItemType Directory -Path $Destination | Out-Null
$paths=@('events.jsonl','initial-a.sql','initial-b.sql','after-cancel.sql')
$paths+=@(Get-ChildItem -LiteralPath $resolved -File -Filter 'summary*.json'|ForEach-Object{$_.Name})
$paths+=@('.datacube/sql-drafts/workspace.bin')
$paths+=@(Get-ChildItem -LiteralPath (Join-Path $resolved '.datacube/sql-drafts') -File -Filter '*.draft'|ForEach-Object{'.datacube/sql-drafts/'+$_.Name})
$files=@(foreach($rel in $paths){
 $from=Join-Path $resolved $rel;$targetRel=$rel -replace '^\.datacube/','';$to=Join-Path $Destination $targetRel
 $parent=Split-Path $to -Parent;if(!(Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent|Out-Null}
 $input=[IO.File]::Open($from,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
 try{$output=[IO.File]::Open($to,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read);try{$input.CopyTo($output)}finally{$output.Dispose()}}finally{$input.Dispose()}
 @{source=$rel;destination=$targetRel;bytes=(Get-Item -LiteralPath $to).Length;sha256=(Get-FileHash -LiteralPath $to).Hash}
})
@{utc=[DateTime]::UtcNow.ToString('o');sourceProfile=$resolved;files=$files;onlySyntheticOwnProfile=$true}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $Destination 'snapshot.json') -Encoding utf8