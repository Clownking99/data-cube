param([Parameter(Mandatory)][string]$Package)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$config=Get-Content -LiteralPath (Join-Path $Package 'spec.json') -Raw|ConvertFrom-Json
$Repo=$config.repo;$Tools=Join-Path $Package 'tools'
Import-Module (Join-Path $Tools 'VerificationCore.psm1') -Force
function Require($Value,[string]$Reason){if(-not $Value){throw "BOUNDARY_REFUSAL:$Reason"}}
$rows=[Collections.Generic.List[object]]::new()
$parent=Join-Path $Repo 'docs/superpowers/verification/evidence';$uuid='a'*32;$owned='b'*32
foreach($suffix in @('', '-fixture','-targeted','-full','-buildsrc','-image','-linked','-owner','-package')){
 foreach($phase in @('2','3')){
  $root=Join-Path $parent ('redis-binary-p'+$phase+'-'+$uuid+$suffix)
  Require ((Assert-VerificationEvidencePath $Repo $root root) -eq $root) 'valid root'
  Require ((Assert-VerificationEvidencePath $Repo (Join-Path $root $owned) owned) -eq (Join-Path $root $owned)) 'valid owned'
  $rows.Add(@{case='valid-'+$phase+$suffix;accepted=$true;root=$root;metadataObserved=0;scope='pure lexical helper'})
 }
}
foreach($name in @('g11-p2-legacy','g11-p3-legacy')){
 $root=Join-Path $parent $name
 Require ((Assert-VerificationEvidencePath $Repo $root root) -eq $root) 'legacy root'
 $rows.Add(@{case=$name;accepted=$true;scope='pure lexical helper'})
}
$badRoots=@(
 (Join-Path $parent ('redis-other-p2-'+$uuid)),
 (Join-Path $parent 'redis-binary-p2-'),
 (Join-Path $parent ('redis-binary-p2-'+('a'*31))),
 (Join-Path $parent ('redis-binary-p2-'+('A'*32))),
 (Join-Path $parent ('redis-binary-p2-'+$uuid+'-')),
 (Join-Path $parent ('redis-binary-p2-'+$uuid+'-'+('x'*65))),
 (Join-Path $parent ('nested/redis-binary-p2-'+$uuid)),
 (Join-Path $Repo ('docs/superpowers/verification/foreign/redis-binary-p2-'+$uuid))
)
$module=Get-Module VerificationCore
# Observe real NoReparse calls and delegate unchanged; never swallow filesystem checks.
& $module {
 $script:ObservedNoReparse=[Collections.Generic.List[string]]::new()
 $script:RealNoReparse=${function:Assert-NoReparse}
 function script:Assert-NoReparse([string]$Path){$script:ObservedNoReparse.Add($Path);& $script:RealNoReparse $Path}
}
foreach($root in $badRoots){
 & $module {$script:ObservedNoReparse.Clear()}
 $failure=$null
 try{New-OwnedScope -Repo $Repo -EvidenceRoot $root -Jdk $config.jdk -Cache $config.cache -Pwsh $config.pwsh -Python $config.python -RuntimeParent $config.runtimeParent -Stage fixture|Out-Null}catch{$failure=$_.Exception.Message}
 Require ($failure -eq 'UNADMITTED_NAMED_EVIDENCE') 'foreign root exact refusal'
 $visits=@(& $module {$script:ObservedNoReparse.ToArray()})
 Require ($visits.Count -eq 0) 'foreign root visited NoReparse'
 $rows.Add(@{case='new-scope-foreign-root';target=$root;actualRefusal=$failure;targetOrAncestorMetadataCalls=$visits;legitimateReads='spec and frozen module loaded before observation'})
}
foreach($leaf in @('not-a-uuid',('A'*32),($owned+'/nested'))){
 $failure=$null;try{Assert-VerificationEvidencePath $Repo (Join-Path (Join-Path $parent ('redis-binary-p2-'+$uuid)) $leaf) owned|Out-Null}catch{$failure=$_.Exception.Message}
 Require ($null -ne $failure) 'invalid owned accepted';$rows.Add(@{case='invalid-owned';leaf=$leaf;actualRefusal=$failure;metadataObserved=0})
}
# Forbidden components stay synthetic strings: only the lexical function sees them.
foreach($value in @('C:\never\.testagent\x','C:\never\..\x','\\server\share\x','C:\never\CON\x')){
 $failure=$null;try{Assert-VerificationEvidencePath $Repo $value root|Out-Null}catch{$failure=$_.Exception.Message}
 Require ($null -ne $failure) 'forbidden lexical accepted';$rows.Add(@{case='forbidden-synthetic-string';actualRefusal=$failure;metadataObserved=0})
}
$stage=Join-Path $Tools 'run-stage.ps1';$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($stage,[ref]$tokens,[ref]$errors);Require (-not $errors) 'stage AST'
$source=$ast.Extent.Text
Require ($source.IndexOf("GetFileName(`$ScopePath)") -lt $source.IndexOf('Assert-NoReparse $ScopePath')) 'basename before metadata'
Require ($source.IndexOf("GetFileName(`$ScopePath)") -lt $source.IndexOf('Get-Content -LiteralPath $ScopePath')) 'basename before read'
$wrong=Join-Path (Join-Path (Join-Path $parent ('redis-binary-p2-'+$uuid)) $owned) 'wrong.json'
$global:RedisBinaryObservedCommands=0
$breakpoint=Set-PSBreakpoint -Command Assert-NoReparse,Get-Content -Action {$global:RedisBinaryObservedCommands++}
try{
 $failure=$null;try{& $stage -Repo $Repo -ScopePath $wrong}catch{$failure=$_.Exception.Message}
 Require ($failure -eq 'SCOPE_FILE_IDENTITY_MISMATCH') 'wrong scope basename actual entry'
 Require ($global:RedisBinaryObservedCommands -eq 0) 'wrong scope reached metadata/read'
 $rows.Add(@{case='actual-stage-wrong-basename';target=$wrong;actualRefusal=$failure;targetNoReparseOrGetContentCommands=$global:RedisBinaryObservedCommands;legitimateReads='actual stage imports its legal frozen module before target checks';nativeMetadataProof='actual exception plus AST order before first target metadata/read'})
}finally{Remove-PSBreakpoint $breakpoint;Remove-Variable RedisBinaryObservedCommands -Scope Global}
@{schema='redis-binary-path-boundaries/v1';passed=$true;cases=@($rows);scope='Rejected target phase only; legal spec/tools/module reads are allowed and real downstream checks are not mocked away.'}|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $Package 'boundary-powershell-result.json') -Encoding utf8NoBOM
