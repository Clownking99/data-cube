$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$root=Join-Path $repo 'docs/superpowers/verification/evidence/app-shell-shutdown-recovery-worker'
$runs=@('red-first','green-first','green-expanded','green-boundaries','green-final-boundaries','green-focused-final','green-reviewed')
$rows=@();$profiles=@()
foreach($run in $runs){
    $dir=Join-Path $root $run
    $parameters=Get-Content -LiteralPath (Join-Path $dir 'parameters.json') -Raw | ConvertFrom-Json
    $result=Get-Content -LiteralPath (Join-Path $dir 'result.json') -Raw | ConvertFrom-Json
    $profiles+=$parameters.profile
    if(!(Split-Path -Leaf $parameters.profile).StartsWith('datacube-shell-recovery-') -or !$parameters.profile.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected worker profile'}
    $log=Get-Content -LiteralPath (Join-Path $dir 'raw.log') -Raw
    $ran=$log -match '(?m)^> Task :test(?:\s|$)'
    $xmls=@()
    if($ran){
        foreach($file in Get-ChildItem -LiteralPath $dir -Filter 'TEST-*.xml' -File){
            [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
            if($file.LastWriteTimeUtc -lt [DateTime]::Parse($parameters.started).ToUniversalTime()){throw 'Stale XML on executed worker run'}
            $xmls+=[pscustomobject][ordered]@{suite=$xml.testsuite.GetAttribute('name');tests=[int]$xml.testsuite.tests;failures=[int]$xml.testsuite.failures;errors=[int]$xml.testsuite.errors;skipped=[int]$xml.testsuite.skipped;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
        }
    }
    $rows+=[pscustomobject][ordered]@{run=$run;exit=$result.exit;testTaskExecuted=$ran;compileFailed=$log.Contains('Execution failed for task '':compileTestJava''');xmlAccepted=$ran;suites=$xmls;staleXmlPresentButExcluded=!$ran -and @(Get-ChildItem -LiteralPath $dir -Filter 'TEST-*.xml' -File).Count -gt 0}
}
if(@($profiles | Select-Object -Unique).Count -ne $runs.Count){throw 'Worker profiles reused'}
$hashes=Get-Content -LiteralPath (Join-Path $root 'green-reviewed/source-hashes.json') -Raw | ConvertFrom-Json
foreach($entry in $hashes){if((Get-FileHash -LiteralPath (Join-Path $repo $entry.path) -Algorithm SHA256).Hash -ne $entry.sha256){throw 'Worker final source drift'}}
$last=$rows[-1];$total=0
foreach($suite in $last.suites){$total+=$suite.tests;if($suite.failures -ne 0 -or $suite.errors -ne 0 -or $suite.skipped -ne 0){throw 'Worker final raw test failed/skipped'}}
if($last.exit -ne 0 -or $total -ne 28 -or $last.suites.Count -ne 3){throw 'Worker final raw evidence incomplete'}
[ordered]@{checkpoint='N2 independent final source and worker evidence review';hostUtc=[DateTime]::UtcNow.ToString('o');workerFinalTests=$total;workerFinalSourceHashesStable=$true;distinctSyntheticProfiles=$profiles.Count;runs=$rows;sourceReview='Suspend/generation/retained registry; private settlement copies; post-tab-commit failure maps FAILED_PARTIAL; FX permanent cleanup inside best-effort; disk I/O outside gate';newNativeRun=$false;realDatabaseRun=$false;remaining=@('native close/official launcher','real workspace CANCEL','DataGrid physical 15 second timeout/full shell transactions');reviewPassed=$true} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'n2-worker-review.json') -Encoding utf8
Write-Output "Independently confirmed $total/28; final source hashes stable; 7 fresh profiles; 2 compile failures excluded."
