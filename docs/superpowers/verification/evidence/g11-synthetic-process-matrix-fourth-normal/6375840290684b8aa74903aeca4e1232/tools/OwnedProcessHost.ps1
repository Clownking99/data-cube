param([Parameter(Mandatory)][string]$Request)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$watch = [Diagnostics.Stopwatch]::StartNew()
# The parent validates paths and assigns this host to its private job before
# releasing the gate. No tool may start before that gate exists.
$requestObject = Get-Content -LiteralPath $Request -Raw | ConvertFrom-Json
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Diagnostics;
using System.Threading.Tasks;
using System.Threading;
public sealed class BoundedPump {
 public long BytesRead, BytesWritten, FailureTick; public bool Eof, Overflow; public string Error;
 public async Task Drain(Stream input,string path,long cap) {
  try {
   using(var output=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.Read,16384,true)) {
    byte[] buffer=new byte[16384]; int count;
    while((count=await input.ReadAsync(buffer,0,buffer.Length))!=0) {
     Interlocked.Add(ref BytesRead,count);
     int keep=(int)Math.Min(count,Math.Max(0,cap-BytesWritten));
     if(keep>0) { await output.WriteAsync(buffer,0,keep); await output.FlushAsync(); Interlocked.Add(ref BytesWritten,keep); }
     if(keep<count) { FailureTick=Stopwatch.GetTimestamp(); Overflow=true; await output.FlushAsync(); return; }
    }
    await output.FlushAsync(); Eof=true;
   }
  } catch(Exception error) { FailureTick=Stopwatch.GetTimestamp(); Error=error.GetType().Name+":"+error.Message; }
 }
}
'@
while (-not [IO.File]::Exists($requestObject.gate)) {
    if ($watch.ElapsedMilliseconds -ge $requestObject.deadlineMs) { exit 124 }
    Start-Sleep -Milliseconds 10
}
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = $requestObject.exe
$info.WorkingDirectory = $requestObject.cwd
$info.UseShellExecute = $false
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$info.Environment.Clear()
foreach ($property in $requestObject.environment.psobject.Properties) { $info.Environment[$property.Name] = [string]$property.Value }
foreach ($argument in $requestObject.argv) { $info.ArgumentList.Add([string]$argument) }
$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
$stdout = [BoundedPump]::new(); $stderr = [BoundedPump]::new()
$failure = $null; $failureTick=[long]::MaxValue; $rootExit = $null; $started = $false; $secondary = [Collections.Generic.List[string]]::new()
function Save-FirstFailure([string]$Kind,[long]$Tick) {
 if($Tick -lt $script:failureTick){if($null -ne $script:failure){$script:secondary.Add($script:failure)};$script:failure=$Kind;$script:failureTick=$Tick}
 elseif($Kind -ne $script:failure){$script:secondary.Add($Kind)}
}
try {
    $started = $process.Start()
    @{pid=$process.Id;startTimeUtc=$process.StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $requestObject.identity -Encoding utf8NoBOM
    $outTask = $stdout.Drain($process.StandardOutput.BaseStream,$requestObject.stdout,[long]$requestObject.streamCap)
    $errTask = $stderr.Drain($process.StandardError.BaseStream,$requestObject.stderr,[long]$requestObject.streamCap)
    while ($watch.ElapsedMilliseconds -lt ($requestObject.deadlineMs - $requestObject.settleMs)) {
        if ($process.HasExited -and $null -eq $rootExit) {
            $rootExit=$process.ExitCode
            $tempEvent=$requestObject.rootExitEvent+'.tmp'
            $rootExitTick=[Diagnostics.Stopwatch]::GetTimestamp()
            @{exitCode=$rootExit;observationTick=$rootExitTick;elapsedMs=$watch.ElapsedMilliseconds}|ConvertTo-Json|Set-Content -LiteralPath $tempEvent -Encoding utf8NoBOM
            [IO.File]::Move($tempEvent,$requestObject.rootExitEvent)
            if ($rootExit -ne 0) { Save-FirstFailure 'NONZERO_EXIT' $rootExitTick }
        }
        if ($stdout.Overflow -or $stderr.Overflow) { $tick=(@($stdout.FailureTick,$stderr.FailureTick)|Where-Object {$_ -gt 0}|Measure-Object -Minimum).Minimum;Save-FirstFailure 'LOG_LIMIT' ([long]$tick); break }
        if ($stdout.Error -or $stderr.Error) { $tick=(@($stdout.FailureTick,$stderr.FailureTick)|Where-Object {$_ -gt 0}|Measure-Object -Minimum).Minimum;Save-FirstFailure 'PIPE_FAILURE' ([long]$tick); break }
        if ($process.HasExited -and $outTask.IsCompleted -and $errTask.IsCompleted) { break }
        Start-Sleep -Milliseconds 10
    }
    if ($process.HasExited) { $rootExit=$process.ExitCode }
    if (-not $process.HasExited -or -not $outTask.IsCompleted -or -not $errTask.IsCompleted) {
        Save-FirstFailure 'DEADLINE' ([Diagnostics.Stopwatch]::GetTimestamp())
    }
    # Publish before settlement so the parent preserves a first nonzero exit
    # when it terminates the private job at the enclosing deadline.
    if ($process.HasExited) { $rootExit=$process.ExitCode }
    if ($null -eq $failure -and $rootExit -ne 0) { Save-FirstFailure 'NONZERO_EXIT' ([Diagnostics.Stopwatch]::GetTimestamp()) }
} catch { Save-FirstFailure ('START_OR_HOST_FAILURE:' + $_.Exception.Message) ([Diagnostics.Stopwatch]::GetTimestamp()) }
$receipt = [ordered]@{
    schema='host-process/v1';started=$started;rootPid= $(if($started){$process.Id}else{$null});rootExitCode=$rootExit
    rootExited=($null -ne $rootExit);primaryFailure=$failure;primaryFailureTick=$failureTick;secondaryFailures=@($secondary)
    stdout=@{bytesRead=$stdout.BytesRead;bytesWritten=$stdout.BytesWritten;eof=$stdout.Eof;truncated=$stdout.Overflow;error=$stdout.Error}
    stderr=@{bytesRead=$stderr.BytesRead;bytesWritten=$stderr.BytesWritten;eof=$stderr.Eof;truncated=$stderr.Overflow;error=$stderr.Error}
    streamsCompleted=($stdout.Eof -and $stderr.Eof);elapsedMs=$watch.ElapsedMilliseconds
    powershellVersion=$PSVersionTable.PSVersion.ToString();dotnetVersion=[Environment]::Version.ToString()
}
$receipt | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $requestObject.receipt -Encoding utf8NoBOM
# Never wait indefinitely for pumps. This dedicated host exits even when EOF
# cannot be proved; the parent owns job termination and settlement reporting.
exit $(if($null -eq $failure){0}else{1})
