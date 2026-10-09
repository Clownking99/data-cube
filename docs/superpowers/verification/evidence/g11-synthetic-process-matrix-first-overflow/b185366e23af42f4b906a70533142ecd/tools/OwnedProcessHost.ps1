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
 public long BytesRead, BytesWritten; public bool Eof, Overflow; public string Error;
 public async Task Drain(Stream input,string path,long cap) {
  try {
   using(var output=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.Read,16384,true)) {
    byte[] buffer=new byte[16384]; int count;
    while((count=await input.ReadAsync(buffer,0,buffer.Length))!=0) {
     Interlocked.Add(ref BytesRead,count);
     int keep=(int)Math.Min(count,Math.Max(0,cap-BytesWritten));
     if(keep>0) { await output.WriteAsync(buffer,0,keep); Interlocked.Add(ref BytesWritten,keep); }
     if(keep<count) { Overflow=true; await output.FlushAsync(); return; }
    }
    await output.FlushAsync(); Eof=true;
   }
  } catch(Exception error) { Error=error.GetType().Name+":"+error.Message; }
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
$failure = $null; $rootExit = $null; $started = $false; $secondary = [Collections.Generic.List[string]]::new()
try {
    $started = $process.Start()
    @{pid=$process.Id;startTimeUtc=$process.StartTime.ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $requestObject.identity -Encoding utf8NoBOM
    $outTask = $stdout.Drain($process.StandardOutput.BaseStream,$requestObject.stdout,[long]$requestObject.streamCap)
    $errTask = $stderr.Drain($process.StandardError.BaseStream,$requestObject.stderr,[long]$requestObject.streamCap)
    while ($watch.ElapsedMilliseconds -lt ($requestObject.deadlineMs - $requestObject.settleMs)) {
        if ($process.HasExited -and $null -eq $rootExit) {
            $rootExit=$process.ExitCode
            $tempEvent=$requestObject.rootExitEvent+'.tmp'
            @{exitCode=$rootExit;elapsedMs=$watch.ElapsedMilliseconds}|ConvertTo-Json|Set-Content -LiteralPath $tempEvent -Encoding utf8NoBOM
            [IO.File]::Move($tempEvent,$requestObject.rootExitEvent)
            if ($rootExit -ne 0 -and $null -eq $failure) { $failure='NONZERO_EXIT' }
        }
        if ($stdout.Overflow -or $stderr.Overflow) { if($null -eq $failure){$failure='LOG_LIMIT'}else{$secondary.Add('LOG_LIMIT')}; break }
        if ($stdout.Error -or $stderr.Error) { if($null -eq $failure){$failure='PIPE_FAILURE'}else{$secondary.Add('PIPE_FAILURE')}; break }
        if ($process.HasExited -and $outTask.IsCompleted -and $errTask.IsCompleted) { break }
        Start-Sleep -Milliseconds 10
    }
    if ($process.HasExited) { $rootExit=$process.ExitCode }
    if (-not $process.HasExited -or -not $outTask.IsCompleted -or -not $errTask.IsCompleted) {
        if ($null -eq $failure) { $failure='DEADLINE' } else { $secondary.Add('DEADLINE') }
    }
    # Publish before settlement so the parent preserves a first nonzero exit
    # when it terminates the private job at the enclosing deadline.
    if ($process.HasExited) { $rootExit=$process.ExitCode }
    if ($null -eq $failure -and $rootExit -ne 0) { $failure='NONZERO_EXIT' }
} catch { if ($null -eq $failure) { $failure='START_OR_HOST_FAILURE:' + $_.Exception.Message } }
$receipt = [ordered]@{
    schema='host-process/v1';started=$started;rootPid= $(if($started){$process.Id}else{$null});rootExitCode=$rootExit
    rootExited=($null -ne $rootExit);primaryFailure=$failure;secondaryFailures=@($secondary)
    stdout=@{bytesRead=$stdout.BytesRead;bytesWritten=$stdout.BytesWritten;eof=$stdout.Eof;truncated=$stdout.Overflow;error=$stdout.Error}
    stderr=@{bytesRead=$stderr.BytesRead;bytesWritten=$stderr.BytesWritten;eof=$stderr.Eof;truncated=$stderr.Overflow;error=$stderr.Error}
    streamsCompleted=($stdout.Eof -and $stderr.Eof);elapsedMs=$watch.ElapsedMilliseconds
}
$receipt | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $requestObject.receipt -Encoding utf8NoBOM
# Never wait indefinitely for pumps. This dedicated host exits even when EOF
# cannot be proved; the parent owns job termination and settlement reporting.
exit $(if($null -eq $failure){0}else{1})
