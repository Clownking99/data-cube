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
 public long BytesRead, BytesWritten, FailureTick; public volatile bool Eof, Overflow; public volatile string Error;
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
$failure = $null; $failureTick=[long]::MaxValue; $rootExit = $null; $rootExitTick=$null; $rootEventPublished=$false; $tailBoundaryForced=$false; $rootIdentity=$null; $started = $false; $secondary = [Collections.Generic.List[string]]::new()
function Save-FirstFailure([string]$Kind,[long]$Tick) {
 if($Tick -lt $script:failureTick){if($null -ne $script:failure){$script:secondary.Add($script:failure)};$script:failure=$Kind;$script:failureTick=$Tick}
 elseif($Kind -ne $script:failure){$script:secondary.Add($Kind)}
}
function Write-AtomicObservation([string]$Path,$Value) {
    $temporary=$Path+'.tmp'
    $bytes=[Text.Encoding]::UTF8.GetBytes(($Value|ConvertTo-Json -Depth 8))
    $file=[IO.File]::Open($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$file.Write($bytes,0,$bytes.Length);$file.Flush($true)} finally {$file.Dispose()}
    [IO.File]::Move($temporary,$Path)
}
function Observe-RootExit {
    if(-not $script:started -or -not $process.HasExited){return}
    if($null -eq $script:rootExitTick) {
        $script:rootExit=$process.ExitCode
        $script:rootExitTick=[Diagnostics.Stopwatch]::GetTimestamp()
        if($script:rootExit -ne 0){Save-FirstFailure 'NONZERO_EXIT' $script:rootExitTick}
    }
    if(-not $script:rootEventPublished) {
        Write-AtomicObservation $requestObject.rootExitEvent @{schema='root-exit/v1';pid=$rootIdentity.pid;startTimeUtc=$rootIdentity.startTimeUtc;exitCode=$script:rootExit;observationTick=$script:rootExitTick;elapsedMs=$watch.ElapsedMilliseconds}
        $script:rootEventPublished=$true
    }
}
$observationFault=$requestObject.observationFault
if($observationFault -and ($requestObject.stage -ne 'fixture' -or $observationFault -notin @('exit-tail-zero','exit-tail-seven','exit-late-identity'))){throw 'HOST_FAULT_OUTSIDE_FIXTURE'}
try {
    $started = $process.Start()
    $rootIdentity=@{pid=$process.Id;startTimeUtc=$process.StartTime.ToUniversalTime().ToString('o')}
    Write-AtomicObservation $requestObject.identity $rootIdentity
    $outTask = $stdout.Drain($process.StandardOutput.BaseStream,$requestObject.stdout,[long]$requestObject.streamCap)
    $errTask = $stderr.Drain($process.StandardError.BaseStream,$requestObject.stderr,[long]$requestObject.streamCap)
    while ($watch.ElapsedMilliseconds -lt ($requestObject.deadlineMs - $requestObject.settleMs)) {
        Observe-RootExit
        if($observationFault -and -not $process.HasExited) {
            # The synthetic root cannot exit until this iteration has observed HasExited=false.
            $tailBoundaryForced=$true
            [IO.File]::WriteAllText($requestObject.identity+'.tail-release','release')
            $remaining=[Math]::Max(0,($requestObject.deadlineMs-$requestObject.settleMs)-$watch.ElapsedMilliseconds)
            $null=$process.WaitForExit([int]$remaining)
            if($process.HasExited){$null=[Threading.Tasks.Task]::WaitAll(@($outTask,$errTask),[int][Math]::Max(0,($requestObject.deadlineMs-$requestObject.settleMs)-$watch.ElapsedMilliseconds))}
        }
        if ($stdout.Overflow -or $stderr.Overflow) { $tick=(@($stdout.FailureTick,$stderr.FailureTick)|Where-Object {$_ -gt 0}|Measure-Object -Minimum).Minimum;Save-FirstFailure 'LOG_LIMIT' ([long]$tick); break }
        if ($stdout.Error -or $stderr.Error) { $tick=(@($stdout.FailureTick,$stderr.FailureTick)|Where-Object {$_ -gt 0}|Measure-Object -Minimum).Minimum;Save-FirstFailure 'PIPE_FAILURE' ([long]$tick); break }
        if ($process.HasExited -and $outTask.IsCompleted -and $errTask.IsCompleted) { Observe-RootExit; break }
        Start-Sleep -Milliseconds 10
    }
    Observe-RootExit
    if (-not $process.HasExited -or -not $outTask.IsCompleted -or -not $errTask.IsCompleted) {
        if($watch.ElapsedMilliseconds -ge ($requestObject.deadlineMs-$requestObject.settleMs)){Save-FirstFailure 'DEADLINE' ([Diagnostics.Stopwatch]::GetTimestamp())}
        else{$secondary.Add('STREAMS_OR_ROOT_INCOMPLETE_AT_FAILURE')}
    }
    # Publish before settlement so the parent preserves a first nonzero exit
    # when it terminates the private job at the enclosing deadline.
    Observe-RootExit

} catch { Save-FirstFailure ('START_OR_HOST_FAILURE:' + $_.Exception.Message) ([Diagnostics.Stopwatch]::GetTimestamp()) } finally {
    try {Observe-RootExit} catch {Save-FirstFailure ('EXIT_EVENT_PUBLICATION_FAILED:'+ $_.Exception.Message) ([Diagnostics.Stopwatch]::GetTimestamp())}
}
$receipt = [ordered]@{
    schema='host-process/v1';started=$started;rootPid= $(if($started){$process.Id}else{$null});rootExitCode=$rootExit
    rootExited=($null -ne $rootExit);rootStartTimeUtc=$(if($rootIdentity){$rootIdentity.startTimeUtc}else{$null});rootObservationTick=$rootExitTick;rootEventPublished=$rootEventPublished;tailBoundaryForced=$tailBoundaryForced;primaryFailure=$failure;primaryFailureTick=$failureTick;secondaryFailures=@($secondary)
    stdout=@{bytesRead=$stdout.BytesRead;bytesWritten=$stdout.BytesWritten;eof=$stdout.Eof;truncated=$stdout.Overflow;error=$stdout.Error}
    stderr=@{bytesRead=$stderr.BytesRead;bytesWritten=$stderr.BytesWritten;eof=$stderr.Eof;truncated=$stderr.Overflow;error=$stderr.Error}
    streamsCompleted=($stdout.Eof -and $stderr.Eof);elapsedMs=$watch.ElapsedMilliseconds
    powershellVersion=$PSVersionTable.PSVersion.ToString();dotnetVersion=[Environment]::Version.ToString()
}
$receipt | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $requestObject.receipt -Encoding utf8NoBOM
# Never wait indefinitely for pumps. This dedicated host exits even when EOF
# cannot be proved; the parent owns job termination and settlement reporting.
exit $(if($null -eq $failure){0}else{1})
