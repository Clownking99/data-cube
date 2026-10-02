param(
    [Parameter(Mandatory=$true)][ValidateSet('branch','main')][string]$Phase,
    [Parameter(Mandatory=$true)][ValidateSet('directed','full','buildsrc','image')][string]$Step,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ExpectedCommit
)
$ErrorActionPreference='Stop'
$baseline=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'baseline.json') -Raw | ConvertFrom-Json
$repo=if($Phase -eq 'main'){$baseline.mainRoot}else{$baseline.branchRoot}
$expectedBranch=if($Phase -eq 'main'){'main'}else{'codex/app-shell-grid-exit'}
Set-Location -LiteralPath $repo
if((git rev-parse HEAD).Trim() -ne $ExpectedCommit -or (git branch --show-current).Trim() -ne $expectedBranch){throw 'Unexpected validation baseline'}
if(@(git status --porcelain -- src test buildSrc build.gradle settings.gradle).Count -ne 0){throw 'Source/test/build workspace changed before verification'}
$runRoot=[IO.Path]::GetFullPath($baseline.runRoot)
if((Split-Path -Leaf $runRoot) -notmatch '^datacube-shell-grid-exit-[0-9a-f]{32}$' -or -not $runRoot.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected temporary root'}
$out=Join-Path $PSScriptRoot $Phase
if(!(Test-Path -LiteralPath $out)){New-Item -ItemType Directory -Path $out | Out-Null}
$execution=Join-Path $out ($Step+'-execution.json')
if(Test-Path -LiteralPath $execution){throw 'Do not overwrite an executed verification'}
$freezePath=Join-Path $out 'source-freeze.json'
if(!(Test-Path -LiteralPath $freezePath)){
    $sources=@(git -c core.quotepath=false ls-files -- src test buildSrc build.gradle settings.gradle | ForEach-Object {
        if($_ -notmatch '^(src/|test/|buildSrc/|build\.gradle$|settings\.gradle$)' -or $_ -match '(^|/)\.testagent(/|$)'){throw 'Unexpected source path'}
        $file=Get-Item -LiteralPath $_
        [ordered]@{path=$_;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
    })
    [ordered]@{head=$ExpectedCommit;hostUtc=[DateTime]::UtcNow.ToString('o');files=$sources} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $freezePath -Encoding utf8
}
$profile=Join-Path $runRoot ($Phase+'/'+$Step+'-final')
if(Test-Path -LiteralPath $profile){throw 'Validation profile is not fresh'}
New-Item -ItemType Directory -Path $profile | Out-Null
$env:JAVA_HOME=$baseline.jdk
$env:PATH=$env:JAVA_HOME+'/bin;'+$env:PATH
foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','GRADLE_OPTS')){Remove-Item -LiteralPath ('Env:'+$key) -ErrorAction SilentlyContinue}
foreach($key in @(Get-ChildItem Env: | Where-Object {$_.Name.ToUpperInvariant() -match 'LIVE|ORACLE|REDIS|^PG|^DATACUBE_'} | Select-Object -ExpandProperty Name)){Remove-Item -LiteralPath ('Env:'+$key)}
$init=Join-Path $repo 'docs/superpowers/verification/evidence/sol-p0-p2/isolation.init.gradle'
$arguments=@('--offline','--no-daemon','--console=plain','--rerun-tasks')
$report='build/test-results/test'
switch($Step){
    'directed' {
        $arguments+=@('-I',$init,('-Ddatacube.acceptance.root='+$profile),'test')
        foreach($suite in $baseline.directedSuites){$arguments+=@('--tests',$suite)}
    }
    'full' {$arguments+=@('-I',$init,('-Ddatacube.acceptance.root='+$profile),'test')}
    'buildsrc' {$arguments+=@('-p','buildSrc','-I',$init,('-Ddatacube.acceptance.root='+$profile),'clean','test');$report='buildSrc/build/test-results/test'}
    'image' {$arguments+=@('jpackageImage')}
}
$startedAt=[DateTime]::UtcNow
$started=$startedAt.ToString('o')
& (Join-Path $repo 'gradlew.bat') @arguments *> (Join-Path $out ($Step+'.log'))
$exitCode=$LASTEXITCODE
[ordered]@{phase=$Phase;step=$Step;head=$ExpectedCommit;startedUtc=$started;endedUtc=[DateTime]::UtcNow.ToString('o');exitCode=$exitCode;arguments=$arguments;profileRoot=if($Step -eq 'image'){$null}else{$profile}} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $execution -Encoding utf8
if($Step -ne 'image'){
    $logText=Get-Content -LiteralPath (Join-Path $out ($Step+'.log')) -Raw
    if($logText -notmatch '(?m)^> Task :test(?:\s|$)') { throw 'No test task executed: retained command failure is not new XML evidence' }
    $xmlOut=Join-Path $out $Step
    New-Item -ItemType Directory -Path $xmlOut | Out-Null
    $totals=@{tests=0;failures=0;errors=0;skipped=0};$suites=@();$skips=@()
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $repo $report) -Filter 'TEST-*.xml' -File){
        if($file.LastWriteTimeUtc -lt $startedAt) { throw ('Stale test XML: '+$file.Name) }
        [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
        $suite=$xml.testsuite;$row=[ordered]@{suite=$suite.name}
        foreach($metric in @('tests','failures','errors','skipped')){$row[$metric]=[int]$suite.GetAttribute($metric);$totals[$metric]+=$row[$metric]}
        $suites+=$row
        foreach($case in $suite.SelectNodes('testcase')){if($null -ne $case.skipped){$skips+=[ordered]@{suite=$suite.name;case=$case.name;reason=$case.skipped.message}}}
        Copy-Item -LiteralPath $file.FullName -Destination $xmlOut
    }
    [ordered]@{suites=$suites.Count;tests=$totals.tests;passed=$totals.tests-$totals.failures-$totals.errors-$totals.skipped;failures=$totals.failures;errors=$totals.errors;skipped=$totals.skipped;skipReasons=$skips;details=$suites} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $xmlOut 'summary.json') -Encoding utf8
    Write-Output "$Phase/$Step suites=$($suites.Count) tests=$($totals.tests) failures=$($totals.failures) errors=$($totals.errors) skipped=$($totals.skipped)"
    if($suites.Count -eq 0 -and $exitCode -eq 0){throw 'No raw test XML: do not count success'}
    if(($totals.failures -ne 0 -or $totals.errors -ne 0) -and $exitCode -eq 0){throw 'XML failures contradict command exit'}
    if($Step -eq 'directed'){
        $missing=@($baseline.directedSuites | Where-Object {$_ -notin $suites.suite})
        if($missing.Count -gt 0){throw ('Requested suites did not execute: '+($missing -join ', '))}
    }
}
$freeze=Get-Content -LiteralPath $freezePath -Raw | ConvertFrom-Json
foreach($entry in $freeze.files){$file=Get-Item -LiteralPath $entry.path;if($file.Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne $entry.sha256){throw 'Frozen source bytes changed during verification'}}
Write-Output "$Phase/$Step exit=$exitCode; frozen source files=$($freeze.files.Count)"
exit $exitCode
