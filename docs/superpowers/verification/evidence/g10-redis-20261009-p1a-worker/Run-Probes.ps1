param([Parameter(Mandatory)][string]$Name)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$out = Join-Path $PSScriptRoot $Name
if (Test-Path -LiteralPath $out) { throw 'Fresh evidence required' }
New-Item -ItemType Directory -Path (Join-Path $out 'inputs') | Out-Null
$owned = Join-Path ([IO.Path]::GetTempPath()) ('datacube-g10-probe-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $owned 'classes'), (Join-Path $owned 'profile'), (Join-Path $owned 'temp') | Out-Null
$jdk = 'D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$sources = @('src/com/datacube/redis/RedisResourceLimits.java','src/com/datacube/redis/RedisException.java','src/com/datacube/redis/RespCodec.java', 'docs/superpowers/verification/evidence/g10-redis-20261009-p1a-worker/BudgetProbe.java')
$inputs = @(foreach ($source in $sources) {
    $file = Join-Path $repo $source
    Copy-Item -LiteralPath $file -Destination (Join-Path $out 'inputs')
    @{ path=$source; length=(Get-Item -LiteralPath $file).Length; sha256=(Get-FileHash -LiteralPath $file).Hash }
})
$inputs | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-before.json') -Encoding utf8
function Run-Child([string]$Label, [string]$Exe, [string[]]$Argv) {
    $step = Join-Path $out $Label
    New-Item -ItemType Directory -Path $step | Out-Null
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Exe; $psi.WorkingDirectory = $owned
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $psi.Environment.Clear()
    foreach ($key in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')) { $value=[Environment]::GetEnvironmentVariable($key); if ($value) { $psi.Environment[$key]=$value } }
    $psi.Environment['USERPROFILE'] = Join-Path $owned 'profile'
    $psi.Environment['TEMP'] = Join-Path $owned 'temp'; $psi.Environment['TMP'] = Join-Path $owned 'temp'
    foreach ($arg in $Argv) { $psi.ArgumentList.Add($arg) }
    @{ executable=$Exe; argv=$Argv; owned=$owned; utc=[datetime]::UtcNow.ToString('o'); environmentPolicy='Clear all; five named OS variables and owned home/temp; no network or profiles' } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $step 'command.json') -Encoding utf8
    $child=[Diagnostics.Process]::new(); $child.StartInfo=$psi
    try {
        if (!$child.Start()) { throw 'Child not started' }
        $stdout=$child.StandardOutput.ReadToEndAsync(); $stderr=$child.StandardError.ReadToEndAsync()
        $timedOut = !$child.WaitForExit(30000)
        if ($timedOut) { $child.Kill($true); $child.WaitForExit() }
        $code=$child.ExitCode
        [IO.File]::WriteAllText((Join-Path $step 'stdout.log'),$stdout.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $step 'stderr.log'),$stderr.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
        @{ exitCode=$code; processExited=$child.HasExited; timedOut=$timedOut; utc=[datetime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $step 'exit.json') -Encoding utf8
        if ($timedOut) { throw 'Probe child exceeded controlled deadline; killed and physically awaited, logs preserved' }
        if ($code -ne 0) { throw "Probe failed: $Label" }
        Get-Content -LiteralPath (Join-Path $step 'stdout.log')
    } finally { $child.Dispose() }
}
$copied = @($sources | ForEach-Object { Join-Path (Join-Path $out 'inputs') ([IO.Path]::GetFileName($_)) })
Run-Child 'java-version' (Join-Path $jdk 'bin/java.exe') @('-version')
Run-Child 'javac-version' (Join-Path $jdk 'bin/javac.exe') @('-version')
Run-Child 'compile' (Join-Path $jdk 'bin/javac.exe') (@('-encoding','UTF-8','-d',(Join-Path $owned 'classes')) + $copied)
foreach ($mode in @('array','bulk','depth','line','nodes','request')) {
    Run-Child $mode (Join-Path $jdk 'bin/java.exe') @('-Xmx32m','-Xss256k',('-Duser.home='+$owned+'/profile'),('-Djava.io.tmpdir='+$owned+'/temp'),'-cp',(Join-Path $owned 'classes'),'com.datacube.redis.BudgetProbe',$mode)
}
Copy-Item -LiteralPath (Join-Path $owned 'classes') -Destination (Join-Path $out 'classes') -Recurse
@(foreach ($exe in @('bin/java.exe','bin/javac.exe')) { $file=Join-Path $jdk $exe; @{ path=$file; sha256=(Get-FileHash -LiteralPath $file).Hash; length=(Get-Item -LiteralPath $file).Length } }) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'runtime-identity.json') -Encoding utf8
$after = @(foreach ($source in $sources) { $file=Join-Path $repo $source; @{ path=$source; length=(Get-Item -LiteralPath $file).Length; sha256=(Get-FileHash -LiteralPath $file).Hash } })
$after | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-after.json') -Encoding utf8
if (($inputs | ConvertTo-Json -Depth 5 -Compress) -ne ($after | ConvertTo-Json -Depth 5 -Compress)) { throw 'Probe inputs changed' }
@{ probes=6; failures=0; owned=$owned; compileExit=0; allProcessesExited=$true; network=$false } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'summary.json') -Encoding utf8
