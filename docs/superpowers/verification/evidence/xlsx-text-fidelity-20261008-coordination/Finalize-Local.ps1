$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$names=@('001-main-targeted','002-main-full','003-main-buildsrc','004-main-image')
& (Join-Path $PSScriptRoot 'Audit-Runs.ps1') -Root $root -Prefix 'docs/superpowers/verification/evidence/xlsx-text-fidelity-20261008-coordination' -Names $names -Output (Join-Path $PSScriptRoot 'main-independent-tests.json')
$runs=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'main-independent-tests.json') -Raw|ConvertFrom-Json)
foreach($run in $runs){if($run.exitCode -ne 0 -or $run.failures -ne 0 -or $run.errors -ne 0){throw 'Main run failed'}}
if($runs[0].passed -ne 286 -or $runs[0].skipped -ne 0 -or $runs[1].passed -ne 4303 -or $runs[1].skipped -ne 3 -or $runs[2].passed -ne 8 -or $runs[2].skipped -ne 0){throw 'Unexpected main test counts'}
$workerFull=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'worker-full-audit.json') -Raw|ConvertFrom-Json
$oldSkips=@($workerFull.skips|ForEach-Object {$_.class+'#'+$_.name+'#'+$_.reason}|Sort-Object)
$newSkips=@($runs[1].skips|ForEach-Object {$_.class+'#'+$_.name+'#'+$_.reason}|Sort-Object)
if(($oldSkips -join "`n") -cne ($newSkips -join "`n")){throw 'Live skip reasons changed'}
$image=Get-Content -LiteralPath (Join-Path $PSScriptRoot '005-main-image-audit/audit.json') -Raw|ConvertFrom-Json
$worker=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'worker-independent-image.json') -Raw|ConvertFrom-Json
if(-not $image.passed -or -not $image.independentReaderPassed){throw 'Main image audit failed'}
$comparison=@(foreach($artifact in $image.artifacts){
 $path=Join-Path $root ('build/jpackage/DataCube/'+$artifact.path)
 $previous=@($worker.artifacts|Where-Object path -eq $artifact.path)
 $hash=(Get-FileHash -LiteralPath $path).Hash;$length=(Get-Item -LiteralPath $path).Length
 if($previous.Count -ne 1 -or $artifact.sha256 -ne $hash -or $artifact.bytes -ne $length -or $hash -ne $previous[0].sha256 -or $length -ne $previous[0].bytes){throw 'Artifact differs between main and worker'}
 @{path=$artifact.path;bytes=$length;sha256=$hash;matchesWorker=$true}
})
$source=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'main-source-start.json') -Raw|ConvertFrom-Json
foreach($file in $source.files){if((Get-FileHash -LiteralPath (Join-Path $root $file.path)).Hash -ne $file.sha256){throw 'Main source changed during verification'}}
git -C $root diff --quiet ce2a096a044db6a731774b13a8ae61ea44687953 HEAD -- src test resources buildSrc build.gradle .github/workflows
if($LASTEXITCODE -ne 0){throw 'Main source tree differs from reviewed code'}
@{utc=[datetime]::UtcNow.ToString('o');main=(git -C $root rev-parse HEAD);source='ce2a096a044db6a731774b13a8ae61ea44687953';workerEvidence='5f176b0b10af9d2de1bd338fe9b1c9518bb4d377';sourceStable=$true;mainAndWorkerArtifactsMatch=$true;artifacts=$comparison;mainTests=$runs;xmlRoundTrips=7;xlsxPackages=20;independentCells=40;malformedUtf16Rejected=6;remaining=@('Native Excel/LibreOffice and desktop','OS scaling and multiple monitors','Real databases and slow/network disks','TableExporter direct-target failure protection is outside this increment','Installer upgrade rollback and production signing','Complete M8 release acceptance','Historical CI helper timeout root cause remains unknown')}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'main-branch-comparison.json') -Encoding utf8
'Local validation complete: source stable, main/worker artifact bytes match.'
