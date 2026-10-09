param([Parameter(Mandatory)][string]$Name, [ValidateSet('targeted','full','buildsrc','image')][string]$Mode)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$out = Join-Path $PSScriptRoot $Name
if (Test-Path -LiteralPath $out) { throw 'Fresh evidence directory required' }
New-Item -ItemType Directory -Path $out | Out-Null
New-Item -ItemType Directory -Path (Join-Path $out 'launcher') | Out-Null
Copy-Item -LiteralPath $PSCommandPath,(Join-Path $PSScriptRoot 'isolated.gradle') -Destination (Join-Path $out 'launcher')
$owned = Join-Path ([IO.Path]::GetTempPath()) ('datacube-g10-p2-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $owned 'profile'), (Join-Path $owned 'temp') | Out-Null
$jdk = 'D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$sourcePaths = @(& rg --files -g '!**/.testagent/**' src test buildSrc resources datacube-brand-assets/assets drivers gradle .github/workflows) + @('build.gradle','settings.gradle','gradle.properties','README.md','gradlew','gradlew.bat')
$sourcePaths = @($sourcePaths | Where-Object { Test-Path -LiteralPath (Join-Path $repo $_) -PathType Leaf } | Sort-Object -Unique)
function Get-Inputs {
    @(foreach ($path in $sourcePaths) { $file = Join-Path $repo $path; @{ path=$path; length=(Get-Item -LiteralPath $file).Length; sha256=(Get-FileHash -LiteralPath $file).Hash } })
}
$before = Get-Inputs
$before | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-before.json') -Encoding utf8
$tasks=switch($Mode){'targeted'{@('cleanTest','test')};'full'{@('clean','test')};'buildsrc'{@(':buildSrc:test')};'image'{@('jpackageImage')}}
$argv = @($tasks)+@('--rerun-tasks','--offline','--no-daemon','--console=plain', ('-Dorg.gradle.java.home='+$jdk), '-I', (Join-Path $PSScriptRoot 'isolated.gradle'))
$Filters=if($Mode -eq 'targeted'){@('com.datacube.redis.*','com.datacube.fx.RedisPane*Test','com.datacube.fx.*Close*Test','com.datacube.fx.*Shutdown*Test','com.datacube.fx.*Construction*Test','com.datacube.fx.*Lifecycle*Test','com.datacube.fx.ShutdownQuarantineTest','com.datacube.fx.task.*','com.datacube.fx.WindowShutdownControllerTest','com.datacube.service.ConnectionManager*Test')}else{@()}
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
Add-Type 'using System.Text;using System.Runtime.InteropServices;public static class G10P2ShortName {[DllImport("kernel32.dll",CharSet=CharSet.Unicode)]public static extern uint GetShortPathName(string path,StringBuilder result,uint size);}'
$buffer=[Text.StringBuilder]::new(4096);$length=[G10P2ShortName]::GetShortPathName((Join-Path $owned 'temp'),$buffer,4096);$short=$buffer.ToString()
if($length -eq 0 -or $length -ge 4096){throw 'Actual owned temp alias required'}
$psi.Environment['TEMP'] = $short
$psi.Environment['TMP'] = $short
$psi.Environment['G10_BUILD'] = Join-Path $owned 'build'
$psi.Environment['G10_HEADLESS'] = 'false'
$psi.Environment['JAVA_TOOL_OPTIONS'] = '"-Duser.home='+$owned+'/profile" "-Djava.io.tmpdir='+$short+'" -Djava.awt.headless=false'
foreach ($arg in @('-NoProfile','-NonInteractive','-Command')) { $psi.ArgumentList.Add($arg) }
$psi.ArgumentList.Add('& ./gradlew.bat '+(($argv | ForEach-Object { "'"+$_.Replace("'","''")+"'" }) -join ' ')+'; exit $LASTEXITCODE')
@{ utc=[datetime]::UtcNow.ToString('o'); head=(& git -C $repo rev-parse HEAD); branch=(& git -C $repo branch --show-current); mode=$Mode;argv=$argv; owned=$owned;shortTemp=$short;inputCount=$before.Count; environmentPolicy='Clear all; five named OS variables only, explicit existing JDK/cache, owned synthetic home/temp/build; native FX toolkit'; childCommand=$psi.ArgumentList[3] } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'command.json') -Encoding utf8
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
    $xmlPath = Join-Path $owned ('build/'+$(if($Mode -eq 'buildsrc'){'buildSrc'}else{'main'})+'/test-results/test')
    if (Test-Path -LiteralPath $xmlPath) {
        $dest = Join-Path $out 'xml'
        New-Item -ItemType Directory -Path $dest | Out-Null
        Get-ChildItem -LiteralPath $xmlPath -Filter 'TEST-*.xml' -File | Copy-Item -Destination $dest
        $summary = @{ suites=0; tests=0; failures=0; errors=0; skipped=0; failedCases=@();skips=@() }
        foreach ($file in Get-ChildItem -LiteralPath $dest -Filter 'TEST-*.xml' -File) {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            $summary.suites++
            foreach ($field in @('tests','failures','errors','skipped')) { $summary[$field] += [int]$xml.testsuite.$field }
            $cases=@($xml.testsuite.SelectNodes('testcase'))
            if($cases.Count -ne [int]$xml.testsuite.tests -or @($xml.testsuite.SelectNodes('testcase/skipped')).Count -ne [int]$xml.testsuite.skipped){throw 'XML attributes/cases disagree'}
            foreach ($case in $cases) { if ($null -ne $case.SelectSingleNode('skipped')){$summary.skips+=@{class=$case.classname;name=$case.name;reason=$case.SelectSingleNode('skipped').OuterXml}};if ($null -ne $case.SelectSingleNode('failure') -or $null -ne $case.SelectSingleNode('error')) { $summary.failedCases += @{ class=$case.classname; name=$case.name; failure=$case.failure.OuterXml; error=$case.error.OuterXml } } }
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
