$ErrorActionPreference='Stop'
$baseline=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'baseline.json') -Raw | ConvertFrom-Json
$rows=@();$images=@();$passed=$true
foreach($phase in @('branch','main')){
    $out=Join-Path $PSScriptRoot $phase
    foreach($step in @('directed','full','buildsrc')){
        $execution=Get-Content -LiteralPath (Join-Path $out ($step+'-execution.json')) -Raw | ConvertFrom-Json
        $totals=@{tests=0;failures=0;errors=0;skipped=0};$suites=0;$skipReasons=@()
        foreach($file in Get-ChildItem -LiteralPath (Join-Path $out $step) -Filter 'TEST-*.xml' -File){
            [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw;$suite=$xml.testsuite;$suites++
            foreach($metric in @('tests','failures','errors','skipped')){$totals[$metric]+=[int]$suite.GetAttribute($metric)}
            foreach($case in @($suite.testcase)){if($null -ne $case.skipped){$skipReasons+=[ordered]@{suite=$suite.name;case=$case.name;reason=$case.skipped.message}}}
        }
        $log=Get-Content -LiteralPath (Join-Path $out ($step+'.log')) -Raw
        $taskMatch=[regex]::Match($log,'(\d+) actionable tasks: (\d+) executed')
        $allTasksExecuted=$taskMatch.Success -and $taskMatch.Groups[1].Value -eq $taskMatch.Groups[2].Value
        $stepPassed=$execution.exitCode -eq 0 -and $totals.failures -eq 0 -and $totals.errors -eq 0 -and $suites -gt 0 -and $allTasksExecuted
        if($step -ne 'full'){$stepPassed=$stepPassed -and $totals.skipped -eq 0}
        if($step -eq 'full'){
            $stepPassed=$stepPassed -and $totals.skipped -eq 3 -and $skipReasons.Count -eq 3
            foreach($skip in $skipReasons){$stepPassed=$stepPassed -and $skip.suite -in @('com.datacube.redis.RedisLiveIntegrationTest','com.datacube.schemadiff.SchemaDiffLiveIntegrationTest')}
        }
        $passed=$passed -and $stepPassed
        $rows+=[ordered]@{phase=$phase;step=$step;head=$execution.head;startedUtc=$execution.startedUtc;endedUtc=$execution.endedUtc;exitCode=$execution.exitCode;suites=$suites;tests=$totals.tests;passed=$totals.tests-$totals.failures-$totals.errors-$totals.skipped;failures=$totals.failures;errors=$totals.errors;skipped=$totals.skipped;skipReasons=$skipReasons;actualTasksExecuted=if($taskMatch.Success){[int]$taskMatch.Groups[2].Value}else{$null};engineeringPassed=$stepPassed}
    }
    $imageExecution=Get-Content -LiteralPath (Join-Path $out 'image-execution.json') -Raw | ConvertFrom-Json
    $imageAudit=Get-Content -LiteralPath (Join-Path $out 'image-audit.json') -Raw | ConvertFrom-Json
    $driverLog=Get-Content -LiteralPath (Join-Path $out 'driver-discovery.log') -Raw
    $imageLog=Get-Content -LiteralPath (Join-Path $out 'image.log') -Raw
    $taskMatch=[regex]::Match($imageLog,'(\d+) actionable tasks: (\d+) executed')
    $imagePassed=$imageExecution.exitCode -eq 0 -and $imageAudit.passed -and $driverLog.Contains('connectCalls=0; no credentials or user profile loaded') -and $taskMatch.Success -and $taskMatch.Groups[1].Value -eq $taskMatch.Groups[2].Value
    $passed=$passed -and $imagePassed
    $images+=[ordered]@{phase=$phase;head=$imageExecution.head;exitCode=$imageExecution.exitCode;passed=$imagePassed;imageFileCount=$imageAudit.imageFileCount;testTypes=$imageAudit.testTypes;classLeaks=$imageAudit.classLeaks;fileLeaks=$imageAudit.fileLeaks;optionLeaks=$imageAudit.optionLeaks;frozenSourceFiles=$imageAudit.sourceFiles;sourceChanged=$imageAudit.sourceChanged;actualTasksExecuted=if($taskMatch.Success){[int]$taskMatch.Groups[2].Value}else{$null};driverDiscoveryConnectCalls=0;artifacts=$imageAudit.artifacts;branchArtifactDifferences=$imageAudit.branchArtifactDifferences}
}
$branchFreeze=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'branch/source-freeze.json') -Raw | ConvertFrom-Json
$mainFreeze=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'main/source-freeze.json') -Raw | ConvertFrom-Json
$branchFiles=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
foreach($entry in $branchFreeze.files){$branchFiles.Add($entry.path,$entry)}
$conversions=@();$strictUtf8=[Text.UTF8Encoding]::new($false,$true)
foreach($entry in $mainFreeze.files){
    if(!$branchFiles.ContainsKey($entry.path)){throw 'Main source path differs from reviewed branch'}
    $branchEntry=$branchFiles[$entry.path]
    $branchPath=Join-Path $baseline.branchRoot $entry.path;$mainPath=Join-Path $baseline.mainRoot $entry.path
    $branchRaw=[IO.File]::ReadAllBytes($branchPath);$mainRaw=[IO.File]::ReadAllBytes($mainPath)
    if($branchRaw.Length -ne $branchEntry.bytes -or [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($branchRaw)) -ne $branchEntry.sha256){throw 'Branch raw source drift'}
    if($mainRaw.Length -ne $entry.bytes -or [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($mainRaw)) -ne $entry.sha256){throw 'Main raw source drift'}
    if($branchEntry.bytes -ne $entry.bytes -or $branchEntry.sha256 -ne $entry.sha256){
        if([IO.Path]::GetExtension($entry.path) -ne '.java' -or $strictUtf8.GetString($branchRaw).Replace("`r`n","`n") -cne $strictUtf8.GetString($mainRaw).Replace("`r`n","`n")){throw 'Cross-worktree source content differs'}
        $conversions+=[ordered]@{path=$entry.path;branchRawSha256=$branchEntry.sha256;mainRawSha256=$entry.sha256;onlyLfCrlf=$true}
    }
}
if($mainFreeze.files.Count -ne $branchFreeze.files.Count){throw 'Source path count differs'}
git -C $baseline.mainRoot diff --quiet 75ed0c6be586cd6ff84e34ce64e2fba5692a8610 HEAD -- src test buildSrc build.gradle settings.gradle
if($LASTEXITCODE -ne 0){throw 'Main source/test/build tree differs from reviewed code'}
git -C $baseline.mainRoot diff --quiet $baseline.startingMain HEAD -- src buildSrc build.gradle settings.gradle
if($LASTEXITCODE -ne 0){throw 'Product changed in fixture-only task'}
[ordered]@{hostUtc=[DateTime]::UtcNow.ToString('o');branchHead=$branchFreeze.head;mainHead=$mainFreeze.head;files=$mainFreeze.files.Count;gitSourceTreeMatches=$true;rawSourceStable=$true;verifiedCheckoutConversions=$conversions;passed=$true} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'source-comparison.json') -Encoding utf8
[ordered]@{clientDate='2026-10-02';hostUtc=[DateTime]::UtcNow.ToString('o');startingMain=$baseline.startingMain;codeCommit='75ed0c6be586cd6ff84e34ce64e2fba5692a8610';testedMain=$mainFreeze.head;productChanged=$false;changedTest='test/com/datacube/service/SchemaDiffServiceTest.java';checks=$rows;images=$images;sourceFiles=$mainFreeze.files.Count;verifiedJavaCheckoutConversions=$conversions.Count;knownFixtureBugsFixed=@('concurrent ArrayList record loss/exception mechanism','mock schema double quoting');historicalSpecificCause='Not fully attributable: old raw cause absent; matching mechanism proven';newNativeRun=$false;newLiveDatabaseRun=$false;preservedFailures=@('new factory record loss and double-quoted schema RED','unsafe two-add diagnostic raw ArrayIndexOutOfBoundsException','first raw CRLF whitespace check rejection','first broad Reproducer image filter false positive','summary caller relative path failed after audit changed working directory; recovered by absolute path');remaining=@('complete native keyboard/results/Oracle/view/SELECT/config invalidation','full AppShell inflight exit and timeout recovery','window/OS scale/multimonitor/official launcher','PG/Redis/SchemaDiff live; no new Oracle run','install/upgrade/signature/CI/user tasks/release/M8','historical failure exact attribution unavailable');engineeringPassed=$passed} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'results.json') -Encoding utf8
if(!$passed){throw 'Do not accept incomplete/failed engineering checks'}
Write-Output 'Both phases raw XML/image evidence and source identity independently verified.'
