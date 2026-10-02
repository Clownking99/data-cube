param([Parameter(Mandatory=$true)][string]$FinalRun,[Parameter(Mandatory=$true)][ValidateRange(3,20)][int]$ExpectedNewCases,[ValidatePattern('^[a-z0-9-]+\.json$')][string]$AuditName='n2-worker-review.json')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$worker=Join-Path $repo 'docs/superpowers/verification/evidence/app-shell-workspace-exit-worker'
$rows=@();$profiles=@()
foreach($dir in Get-ChildItem -LiteralPath $worker -Directory | Sort-Object Name){
    $parametersPath=Join-Path $dir.FullName 'parameters.json'
    if(!(Test-Path -LiteralPath $parametersPath)){continue}
    $parameters=Get-Content -LiteralPath $parametersPath -Raw | ConvertFrom-Json
    $result=Get-Content -LiteralPath (Join-Path $dir.FullName 'result.json') -Raw | ConvertFrom-Json
    $profile=[IO.Path]::GetFullPath($parameters.profile)
    if(!$profile.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $profile) -notmatch '^datacube-shell-workspace-worker-(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$'){throw 'Unexpected synthetic worker profile'}
    $profiles+=$profile
    $log=Get-Content -LiteralPath (Join-Path $dir.FullName 'raw.log') -Raw
    $executed=$log -match '(?m)^> Task :test(?:\s|$)'
    $xmls=@();$cases=@()
    if($executed){
        foreach($file in Get-ChildItem -LiteralPath $dir.FullName -Filter 'TEST-*.xml' -File){
            if($file.LastWriteTimeUtc -lt [DateTime]::Parse($parameters.started).ToUniversalTime()){throw 'Stale worker XML'}
            [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
            $suite=$xml.testsuite
            $xmls+=[pscustomobject]@{suite=$suite.GetAttribute('name');tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
            foreach($case in $suite.SelectNodes('testcase')){$cases+=[pscustomobject]@{suite=$suite.GetAttribute('name');name=$case.GetAttribute('name');time=$case.GetAttribute('time');failed=$null -ne $case.failure;error=$null -ne $case.error;skipped=$null -ne $case.skipped}}
        }
    }
    $rows+=[pscustomobject]@{run=$dir.Name;exit=$result.exit;testTaskExecuted=$executed;xmlAccepted=($executed -and $xmls.Count -gt 0);noCaseEvidence=($executed -and $xmls.Count -eq 0);suites=$xmls;cases=$cases;staleXmlExcluded=(!$executed -and @(Get-ChildItem -LiteralPath $dir.FullName -Filter 'TEST-*.xml' -File).Count -gt 0)}
}
if(@($profiles | Select-Object -Unique).Count -ne $profiles.Count){throw 'Worker profile reused'}
$final=@($rows | Where-Object {$_.run -eq $FinalRun})
if($final.Count -ne 1 -or $final[0].exit -ne 0 -or !$final[0].testTaskExecuted){throw 'Missing successful final worker run'}
$last=$final[0];$total=0
foreach($suite in $last.suites){$total+=$suite.tests;if($suite.failures -or $suite.errors -or $suite.skipped){throw 'Final focused suite failed/skipped'}}
$newSuite=@($last.suites | Where-Object {$_.suite -eq 'com.datacube.fx.AppShellWorkspaceShutdownTest'})
if($newSuite.Count -ne 1 -or $newSuite[0].tests -ne $ExpectedNewCases){throw 'Actual shell workspace suite not fully executed'}
$hashes=Get-Content -LiteralPath (Join-Path $worker ($FinalRun+'/source-hashes.json')) -Raw | ConvertFrom-Json
foreach($entry in $hashes){if($entry.path -match '(^|/)\.testagent(/|$)'){throw 'Forbidden hash path'};if((Get-FileHash -LiteralPath (Join-Path $repo $entry.path) -Algorithm SHA256).Hash -ne $entry.sha256){throw 'Worker source drift'}}
[ordered]@{checkpoint='N2 independent raw evidence and source review';hostUtc=[DateTime]::UtcNow.ToString('o');finalRun=$FinalRun;workerFinalTests=$total;newActualShellWorkspaceCases=$ExpectedNewCases;workerFinalSourceHashesStable=$true;distinctProfiles=$profiles.Count;runs=$rows;newNativeRun=$false;newLiveDatabaseRun=$false;releaseAcceptanceComplete=$false;passed=$true} | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $PSScriptRoot $AuditName) -Encoding utf8
Write-Output "Independent worker XML/hash audit passed; $total focused cases and $ExpectedNewCases new AppShell Workspace cases."