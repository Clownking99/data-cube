param([Parameter(Mandatory)][string]$Name, [string[]]$Filters = @('com.datacube.redis.RedisDisplayBudgetTest'), [switch]$Fx)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$out = Join-Path $PSScriptRoot $Name
if (Test-Path -LiteralPath $out) { throw 'Fresh evidence directory required' }
New-Item -ItemType Directory -Path $out | Out-Null
$owned = Join-Path ([IO.Path]::GetTempPath()) ('datacube-g10-p1b-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $owned 'profile'), (Join-Path $owned 'temp') | Out-Null
$jdk = 'D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$sourcePaths = @(& rg --files -g '!**/.testagent/**' src test buildSrc) + @('build.gradle','settings.gradle','gradle.properties','gradle/wrapper/gradle-wrapper.properties')
$sourcePaths = @($sourcePaths | Where-Object { Test-Path -LiteralPath (Join-Path $repo $_) -PathType Leaf } | Sort-Object -Unique)
function Get-Inputs {
    @(foreach ($path in $sourcePaths) { $file = Join-Path $repo $path; @{ path=$path; length=(Get-Item -LiteralPath $file).Length; sha256=(Get-FileHash -LiteralPath $file).Hash } })
}
$before = Get-Inputs
$before | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-before.json') -Encoding utf8
$argv = @('test','--rerun-tasks','--offline','--no-daemon','--console=plain', ('-Dorg.gradle.java.home='+$jdk), '-I', (Join-Path $PSScriptRoot 'isolated.gradle'))
foreach ($filter in $Filters) { $argv += @('--tests', $filter) }
$psi = [Diagnostics.ProcessStartInfo]::new()
$psi.FileName = (Get-Process -Id $PID).Path
$psi.WorkingDirectory = $repo
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.Environment.Clear()
foreach ($key in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')) {
    $value = [Environment]::GetEnvironmentVariable($key)
    if ($value) { $psi.Environment[$key] = $value }
}
$psi.Environment['JAVA_HOME'] = $jdk
$psi.Environment['GRADLE_USER_HOME'] = 'C:/Users/hetia/.gradle'
$psi.Environment['USERPROFILE'] = Join-Path $owned 'profile'
$psi.Environment['TEMP'] = Join-Path $owned 'temp'
$psi.Environment['TMP'] = Join-Path $owned 'temp'
$psi.Environment['G10_BUILD'] = Join-Path $owned 'build'
$psi.Environment['G10_HEADLESS'] = (!$Fx).ToString().ToLowerInvariant()
$psi.Environment['JAVA_TOOL_OPTIONS'] = '"-Duser.home='+$owned+'/profile" "-Djava.io.tmpdir='+$owned+'/temp" -Djava.awt.headless='+(!$Fx).ToString().ToLowerInvariant()
foreach ($arg in @('-NoProfile','-NonInteractive','-Command')) { $psi.ArgumentList.Add($arg) }
$psi.ArgumentList.Add('& ./gradlew.bat '+(($argv | ForEach-Object { "'"+$_.Replace("'","''")+"'" }) -join ' ')+'; exit $LASTEXITCODE')
@{ utc=[datetime]::UtcNow.ToString('o'); head=(& git -C $repo rev-parse HEAD); branch=(& git -C $repo branch --show-current); argv=$argv; owned=$owned; environmentPolicy='Clear all; five named OS variables only, explicit existing JDK/cache, owned synthetic home/temp/build'; childCommand=$psi.ArgumentList[3] } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'command.json') -Encoding utf8
$process = [Diagnostics.Process]::new()
$process.StartInfo = $psi
try {
    if (!$process.Start()) { throw 'Process not started' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $code = $process.ExitCode
    [IO.File]::WriteAllText((Join-Path $out 'stdout.log'), $stdout.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $out 'stderr.log'), $stderr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
    @{ exitCode=$code; utc=[datetime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'exit.json') -Encoding utf8
    $xmlPath = Join-Path $owned 'build/main/test-results/test'
    if (Test-Path -LiteralPath $xmlPath) {
        $dest = Join-Path $out 'xml'
        New-Item -ItemType Directory -Path $dest | Out-Null
        Get-ChildItem -LiteralPath $xmlPath -Filter 'TEST-*.xml' -File | Copy-Item -Destination $dest
        $summary = @{ suites=0; tests=0; failures=0; errors=0; skipped=0; failedCases=@() }
        foreach ($file in Get-ChildItem -LiteralPath $dest -Filter 'TEST-*.xml' -File) {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            $summary.suites++
            foreach ($field in @('tests','failures','errors','skipped')) { $summary[$field] += [int]$xml.testsuite.$field }
            foreach ($case in $xml.testsuite.testcase) { if ($case.failure -or $case.error) { $summary.failedCases += @{ class=$case.classname; name=$case.name; failure=$case.failure.OuterXml; error=$case.error.OuterXml } } }
        }
        $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $out 'test-summary.json') -Encoding utf8
    }
    $after = Get-Inputs
    $after | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-after.json') -Encoding utf8
    if (($before | ConvertTo-Json -Depth 5 -Compress) -ne ($after | ConvertTo-Json -Depth 5 -Compress)) { throw 'Source changed during run' }
    Get-Content -LiteralPath (Join-Path $out 'stdout.log') -Tail 35
    Get-Content -LiteralPath (Join-Path $out 'stderr.log') -Tail 15
} finally { $process.Dispose() }
exit $code
