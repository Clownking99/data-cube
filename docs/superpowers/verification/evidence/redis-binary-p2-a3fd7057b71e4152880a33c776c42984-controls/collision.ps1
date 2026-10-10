param([Parameter(Mandatory)][string]$Package)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$config=Get-Content -LiteralPath (Join-Path $Package 'spec.json') -Raw|ConvertFrom-Json
$matrix=Join-Path (Split-Path $Package) ((Split-Path $Package -Leaf)+'-process')
$spec=Get-Content -LiteralPath (Join-Path $matrix 'normal-owner-spec.json') -Raw|ConvertFrom-Json
$result=Get-Content -LiteralPath (Join-Path $matrix 'results.json') -Raw|ConvertFrom-Json
$normal=@($result.cases|Where-Object {$_.mode -eq 'normal'})
if($normal.Count -ne 1){throw 'MISSING_ACTUAL_NORMAL_CASE'}
Import-Module (Join-Path $Package 'tools/VerificationCore.psm1') -Force
$failure=$null
try{New-OwnedScope -Repo $config.repo -EvidenceRoot $spec.stageEvidence -Jdk $config.jdk -Cache $config.cache -Pwsh $config.pwsh -Python $config.python -RuntimeParent $config.runtimeParent -Stage fixture|Out-Null}catch{$failure=$_.Exception.Message}
if($failure -ne 'RUN_COLLISION'){throw "COLLISION_ASSERTION:$failure"}
@{passed=$true;actualRefusal=$failure;actualNormalSpec=(Join-Path $matrix 'normal-owner-spec.json');actualRoot=$spec.stageEvidence;scope='Real New-OwnedScope against completed normal fixture root; no fabricated existing directory'}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $Package 'collision-result.json') -Encoding utf8NoBOM