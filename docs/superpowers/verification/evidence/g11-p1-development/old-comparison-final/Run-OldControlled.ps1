param([string]$Python,[string]$FixtureScript,[string]$Mode,[string]$Out,[string]$Xml)
$ErrorActionPreference='Stop'
$psi=[Diagnostics.ProcessStartInfo]::new()
$psi.FileName=$Python;$psi.WorkingDirectory=$Out;$psi.UseShellExecute=$false
$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
$psi.Environment.Clear()
foreach($name in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')){$value=[Environment]::GetEnvironmentVariable($name);if($value){$psi.Environment[$name]=$value}}
$psi.Environment['HOME']=$Out;$psi.Environment['USERPROFILE']=$Out;$psi.Environment['TEMP']=$Out;$psi.Environment['TMP']=$Out
foreach($arg in @('-I','-S',$FixtureScript,'--fixture',$Mode,'--','space value','中文','literal"quote','')){$psi.ArgumentList.Add($arg)}
$process=[Diagnostics.Process]::new();$process.StartInfo=$psi
try {
    # Exact process draining/waiting sequence copied from frozen Run-P3 lines
    # 52-59. Only fixture argv, explicit directories and output filenames differ.
    if (!$process.Start()) { throw 'Process not started' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $code = $process.ExitCode
    # Observation-only instrumentation preserves the first real root exit even
    # when the original following GetResult blocks forever on a child-held pipe.
    @{exitCode=$code;observedUtc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Out 'root-exit-observed.json') -Encoding utf8
    [IO.File]::WriteAllText((Join-Path $Out 'stdout.log'), $stdout.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $Out 'stderr.log'), $stderr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    @{ exitCode=$code; utc=[datetime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Out 'exit.json') -Encoding utf8
    if(Test-Path -LiteralPath $Xml) {
        $summary = @{ suites=0; tests=0; failures=0; errors=0; skipped=0; failedCases=@();skips=@() }
        foreach ($file in Get-ChildItem -LiteralPath $Xml -Filter 'TEST-*.xml' -File) {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            $summary.suites++
            foreach ($field in @('tests','failures','errors','skipped')) { $summary[$field] += [int]$xml.testsuite.$field }
            $cases=@($xml.testsuite.SelectNodes('testcase'))
            if($cases.Count -ne [int]$xml.testsuite.tests -or @($xml.testsuite.SelectNodes('testcase/skipped')).Count -ne [int]$xml.testsuite.skipped){throw 'XML attributes/cases disagree'}
            foreach ($case in $cases) { if ($null -ne $case.SelectSingleNode('skipped')){$summary.skips+=@{class=$case.classname;name=$case.name;reason=$case.SelectSingleNode('skipped').OuterXml}};if ($null -ne $case.SelectSingleNode('failure') -or $null -ne $case.SelectSingleNode('error')) { $summary.failedCases += @{ class=$case.classname; name=$case.name; failure=$case.failure.OuterXml; error=$case.error.OuterXml } } }
        }
        $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Out 'test-summary.json') -Encoding utf8
    }
} finally {$process.Dispose()}
exit $code
