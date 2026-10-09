Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-AdmittedPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path -match '[\x00-\x1f*?<>|" ]$' -or $Path -match '[\x00-\x1f*?<>|" ]') {
        # Spaces inside ordinary components are legal; only controls/wildcards
        # and the component suffix rules below are rejected.
        if ([string]::IsNullOrWhiteSpace($Path) -or $Path -match '[\x00-\x1f*?<>|"]') { throw 'INVALID_PATH' }
    }
    if ($Path -notmatch '^[A-Za-z]:[\\/]' -or $Path.Substring(2).Contains(':')) { throw 'ABSOLUTE_LOCAL_PATH_REQUIRED' }
    foreach ($part in ($Path.Substring(3) -split '[\\/]')) {
        if ($part -in @('', '.', '..') -or $part -match '[ .]$') { throw 'INVALID_PATH_COMPONENT' }
        if ($part -in @('.testagent','.git','.g10-verify-blobs.ps1')) { throw 'FORBIDDEN_PATH' }
        if ($part -match '^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$') { throw 'DEVICE_PATH' }
    }
    return [IO.Path]::GetFullPath($Path)
}

function Assert-NoReparse([string]$Path) {
    $full = Assert-AdmittedPath $Path
    $node = [IO.Path]::GetPathRoot($full)
    foreach ($part in ($full.Substring($node.Length) -split '[\\/]')) {
        $node = [IO.Path]::Combine($node,$part)
        if ([IO.File]::Exists($node) -or [IO.Directory]::Exists($node)) {
            if (([IO.File]::GetAttributes($node) -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'REPARSE_PATH' }
        }
    }
    return $full
}

function Initialize-OwnedJob {
    if ('OwnedJob' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.IO;
using System.Threading.Tasks;
public sealed class HostLogPump {
 public volatile bool Overflow,Eof; public volatile string Error;
 public async Task Drain(Stream input,string path,long cap) {
  try { using(var output=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.Read,16384,true)) {
   byte[] bytes=new byte[16384];long written=0;int n;
   while((n=await input.ReadAsync(bytes,0,bytes.Length))>0) {
    int keep=(int)Math.Min(n,Math.Max(0,cap-written));if(keep>0){await output.WriteAsync(bytes,0,keep);await output.FlushAsync();written+=keep;}
    if(keep<n){Overflow=true;await output.FlushAsync();return;}
   } await output.FlushAsync();Eof=true;
  }} catch(Exception e){Error=e.GetType().Name+":"+e.Message;}
 }
}
public sealed class OwnedJob : IDisposable {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr a,string n);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool TerminateJobObject(IntPtr job,uint code);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int kind,IntPtr data,uint size);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int kind,IntPtr data,uint size,out uint needed);
 [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern uint GetShortPathName(string path,System.Text.StringBuilder result,uint count);
 IntPtr handle;
 [StructLayout(LayoutKind.Sequential)] struct BasicLimits { public long PerProcess,PerJob; public uint Flags; public UIntPtr Min,Max; public uint Active; public UIntPtr Affinity; public uint Priority,Scheduling; }
 [StructLayout(LayoutKind.Sequential)] struct IoCounters { public ulong A,B,C,D,E,F; }
 [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits { public BasicLimits Basic; public IoCounters Io; public UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob; }
 public OwnedJob() {
  handle=CreateJobObject(IntPtr.Zero,null); if(handle==IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
  var limits=new ExtendedLimits();limits.Basic.Flags=0x2000;
  int size=Marshal.SizeOf<ExtendedLimits>();IntPtr block=Marshal.AllocHGlobal(size);
  try { Marshal.StructureToPtr(limits,block,false);if(!SetInformationJobObject(handle,9,block,(uint)size)) { int error=Marshal.GetLastWin32Error();Dispose();throw new System.ComponentModel.Win32Exception(error); } }
  finally { Marshal.FreeHGlobal(block); }
 }
 public void Assign(IntPtr process) { if(!AssignProcessToJobObject(handle,process)) throw new System.ComponentModel.Win32Exception(); }
 public void Terminate() { if(!TerminateJobObject(handle,124)) throw new System.ComponentModel.Win32Exception(); }
 public int[] Pids() {
  int size=65536; IntPtr block=Marshal.AllocHGlobal(size);
  try { uint needed; if(!QueryInformationJobObject(handle,3,block,(uint)size,out needed)) throw new System.ComponentModel.Win32Exception();
   int count=Marshal.ReadInt32(block,4); if(count<0||count>(size-8)/IntPtr.Size) throw new InvalidOperationException("JOB_PID_LIMIT");
   int[] result=new int[count]; for(int i=0;i<count;i++) result[i]=checked((int)Marshal.ReadIntPtr(block,8+i*IntPtr.Size).ToInt64()); return result;
  } finally { Marshal.FreeHGlobal(block); }
 }
 public static string ShortPath(string path) { var b=new System.Text.StringBuilder(32768); uint n=GetShortPathName(path,b,(uint)b.Capacity); if(n==0||n>=b.Capacity) throw new System.ComponentModel.Win32Exception(); return b.ToString(); }
 public void Dispose() { if(handle!=IntPtr.Zero) { CloseHandle(handle);handle=IntPtr.Zero; } }
}
'@
}

function New-OwnedScope {
    param([Parameter(Mandatory)][string]$Repo,[Parameter(Mandatory)][string]$EvidenceRoot,
          [Parameter(Mandatory)][string]$Jdk,[Parameter(Mandatory)][string]$Cache,
          [Parameter(Mandatory)][string]$Pwsh,[Parameter(Mandatory)][string]$Python,
          [Parameter(Mandatory)][string]$Stage)
    $paths=@{}
    foreach ($name in @('Repo','EvidenceRoot','Jdk','Cache','Pwsh','Python')) { $paths[$name]=Assert-AdmittedPath (Get-Variable $name -ValueOnly) }
    foreach ($name in $paths.Keys) { $null=Assert-NoReparse $paths[$name] }
    if ($Stage -notmatch '^[a-z][a-z0-9-]{0,63}$') { throw 'INVALID_STAGE' }
    $prefix=[IO.Path]::Combine($paths.Repo,'docs','superpowers','verification','evidence','g11-')
    if (-not $paths.EvidenceRoot.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) { throw 'EVIDENCE_OUTSIDE_NEW_G11' }
    if ([IO.Directory]::Exists($paths.EvidenceRoot) -or [IO.File]::Exists($paths.EvidenceRoot)) { throw 'RUN_COLLISION' }
    foreach ($name in @('Repo','Jdk','Cache')) { if (-not [IO.Directory]::Exists($paths[$name])) { throw "MISSING_$name" } }
    foreach ($name in @('Pwsh','Python')) { if (-not [IO.File]::Exists($paths[$name])) { throw "MISSING_$name" } }
    Initialize-OwnedJob
    $null=[IO.Directory]::CreateDirectory($paths.EvidenceRoot)
    $marker=[IO.File]::Open([IO.Path]::Combine($paths.EvidenceRoot,'run-owner.json'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $bytes=[Text.Encoding]::UTF8.GetBytes('{"owner":"'+[guid]::NewGuid().ToString('N')+'"}');$marker.Write($bytes,0,$bytes.Length) } finally {$marker.Dispose()}
    $owned=[IO.Path]::Combine($paths.EvidenceRoot,[guid]::NewGuid().ToString('N'))
    $null=[IO.Directory]::CreateDirectory($owned)
    foreach ($name in @('processes','tools')) { $null=[IO.Directory]::CreateDirectory([IO.Path]::Combine($owned,$name)) }
    $runtime=Assert-AdmittedPath ([IO.Path]::Combine([IO.Path]::GetTempPath(),'datacube-g11-'+[guid]::NewGuid().ToString('N')))
    $null=Assert-NoReparse $runtime
    if([IO.Directory]::Exists($runtime)){throw 'RUNTIME_COLLISION'}
    foreach($name in @('home','temp','build')){$null=[IO.Directory]::CreateDirectory([IO.Path]::Combine($runtime,$name))}
    $environment=@{}
    foreach ($name in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')) {
        $value=[Environment]::GetEnvironmentVariable($name); if($null -ne $value) { $environment[$name]=$value }
    }
    $home=[IO.Path]::Combine($runtime,'home');$temp=[IO.Path]::Combine($runtime,'temp')
    $environment.HOME=$home;$environment.USERPROFILE=$home;$environment.TEMP=$temp;$environment.TMP=$temp
    $environment.JAVA_HOME=$paths.Jdk;$environment.GRADLE_USER_HOME=$paths.Cache
    $environment.G11_BUILD=[IO.Path]::Combine($runtime,'build')
    $tools=@{}
    foreach($relative in @('VerificationCore.psm1','OwnedProcessHost.ps1','evidence_tools.py','run-stage.ps1','isolated.gradle','check-core.py')) {
        $source=Assert-NoReparse ([IO.Path]::Combine($paths.Repo,'scripts','verification',$relative))
        if(-not [IO.File]::Exists($source)){throw "MISSING_TOOL:$relative"}
        $dest=[IO.Path]::Combine($owned,'tools',$relative);$null=[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))
        [IO.File]::Copy($source,$dest,$false)
        $tools[$relative]=@{source=$source;frozen=$dest;length=([IO.FileInfo]$source).Length;sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash}
    }
    $executables=@{}
    foreach($exe in @($paths.Pwsh,$paths.Python,[IO.Path]::Combine($paths.Jdk,'bin','java.exe'))){$null=Assert-NoReparse $exe;$executables[$exe]=@{length=([IO.FileInfo]$exe).Length;sha256=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash}}
    return [pscustomobject]@{schema='owned-scope/v1';paths=$paths;stage=$Stage;owned=$owned;runtime=$runtime;environment=$environment;tools=$tools;executables=$executables;shortHome=[OwnedJob]::ShortPath($home);shortTemp=[OwnedJob]::ShortPath($temp)}
}

function Invoke-OwnedProcess {
    param([Parameter(Mandatory)]$Scope,[Parameter(Mandatory)][string]$Name,
          [Parameter(Mandatory)][string]$Exe,[Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Argv,
          [Parameter(Mandatory)][string]$Cwd,[Parameter(Mandatory)][string]$HostScript,
          [int]$DeadlineMs=600000,[int]$SettleMs=5000,[long]$StreamCap=33554432,
          [Threading.CancellationToken]$CancellationToken=[Threading.CancellationToken]::None)
    $watch=[Diagnostics.Stopwatch]::StartNew()
    if($Name -notmatch '^[a-z][a-z0-9-]{0,63}$' -or $DeadlineMs -le $SettleMs -or $SettleMs -lt 100 -or $StreamCap -lt 1) { throw 'INVALID_PROCESS_POLICY' }
    $exePath=Assert-AdmittedPath $Exe;$cwdPath=Assert-AdmittedPath $Cwd;$hostPath=Assert-AdmittedPath $HostScript
    foreach($path in @($Scope.paths.Repo,$Scope.paths.Jdk,$Scope.paths.Cache,$Scope.paths.Pwsh,$Scope.paths.Python,$Scope.owned)){ $null=Assert-AdmittedPath $path }
    $ownedPrefix=[IO.Path]::Combine($Scope.paths.Repo,'docs','superpowers','verification','evidence','g11-')
    if(-not $Scope.owned.StartsWith($ownedPrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'UNADMITTED_SCOPE'}
    if($Scope.schema -ne 'owned-scope/v1' -or $exePath -notin @($Scope.paths.Pwsh,$Scope.paths.Python,[IO.Path]::Combine($Scope.paths.Jdk,'bin','java.exe')) -or $cwdPath -notin @($Scope.paths.Repo,$Scope.owned) -or $hostPath -ne $Scope.tools['OwnedProcessHost.ps1'].frozen){throw 'UNADMITTED_PROCESS_REQUEST'}
    foreach($exe in @($exePath,$Scope.paths.Pwsh)){$null=Assert-NoReparse $exe;if((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash -ne $Scope.executables[$exe].sha256 -or ([IO.FileInfo]$exe).Length -ne $Scope.executables[$exe].length){throw 'EXECUTABLE_IDENTITY_CHANGED'}}
    foreach($relative in $Scope.tools.Keys){$tool=$Scope.tools[$relative]
        if($relative -notin @('VerificationCore.psm1','OwnedProcessHost.ps1','evidence_tools.py','run-stage.ps1','isolated.gradle','check-core.py') -or $tool.source -ne [IO.Path]::Combine($Scope.paths.Repo,'scripts','verification',$relative) -or $tool.frozen -ne [IO.Path]::Combine($Scope.owned,'tools',$relative)){throw 'UNADMITTED_TOOL_BINDING'}
        foreach($path in @($tool.source,$tool.frozen)){$null=Assert-NoReparse $path;if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $tool.sha256){throw 'TOOL_IDENTITY_CHANGED'}}
    }
    foreach($path in @($exePath,$cwdPath,$hostPath)) { $null=Assert-NoReparse $path }
    $directory=[IO.Path]::Combine($Scope.owned,'processes',$Name)
    if([IO.Directory]::Exists($directory)) { throw 'PROCESS_COLLISION' }
    $null=[IO.Directory]::CreateDirectory($directory)
    $request=[ordered]@{exe=$exePath;argv=@($Argv);cwd=$cwdPath;environment=$Scope.environment;deadlineMs=$DeadlineMs;settleMs=$SettleMs;streamCap=$StreamCap
        gate="$directory/gate";identity="$directory/root-identity.json";rootExitEvent="$directory/root-exit.json";stdout="$directory/stdout.bin";stderr="$directory/stderr.bin";receipt="$directory/host-receipt.json"}
    $requestPath="$directory/request.json"
    $request|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $requestPath -Encoding utf8NoBOM
    $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$Scope.paths.Pwsh;$info.WorkingDirectory=$cwdPath;$info.UseShellExecute=$false
    $info.Environment.Clear();foreach($key in $Scope.environment.Keys){$info.Environment[$key]=$Scope.environment[$key]}
    foreach($arg in @('-NoLogo','-NoProfile','-NonInteractive','-File',$hostPath,'-Request',$requestPath)){$info.ArgumentList.Add($arg)}
    # Host has no regular console output. Redirect to avoid exposing tool/config
    # data; asynchronously drain into bounded byte pumps in the parent as well.
    $info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $hostProcess=[Diagnostics.Process]::new();$hostProcess.StartInfo=$info
    $job=[OwnedJob]::new();$failure=$null;$secondary=[Collections.Generic.List[string]]::new()
    $captured=@{};$identities=@{};$termination=$false;$hostExit=$null;$hostReceipt=$null;$rootIdentity=$null;$observedRootExit=$null;$started=$false;$hostOutPump=$null;$hostErrPump=$null;$cancelled=$false
    $failureTick=[long]::MaxValue
    $testFault=$(if($Scope -is [Collections.IDictionary] -and $Scope.Contains('testFault')){$Scope.testFault}else{$null})
    if($testFault -and $Scope.stage -ne 'fixture'){throw 'FAULT_ADAPTER_OUTSIDE_FIXTURE'}
    try {
    try {
        if($watch.ElapsedMilliseconds -ge ($DeadlineMs-$SettleMs)){throw 'ADMISSION_DEADLINE'}
        if($CancellationToken.IsCancellationRequested){$cancelled=$true;$failure='CANCELLED';$failureTick=[Diagnostics.Stopwatch]::GetTimestamp();throw 'CANCELLED_BEFORE_START'}
        if($testFault -eq 'start-failure'){$hostProcess.StartInfo.FileName=[IO.Path]::Combine($Scope.owned,'missing-host.exe')}
        $started=$hostProcess.Start()
        if($testFault -eq 'assign-failure'){throw 'SYNTHETIC_ASSIGNMENT_FAILURE_BEFORE_GATE'}
        $job.Assign($hostProcess.Handle)
        # Host output is diagnostic only; cap it without accumulating strings.
        $hostOutPump=[HostLogPump]::new();$hostErrPump=[HostLogPump]::new()
        $hostOut=$hostOutPump.Drain($hostProcess.StandardOutput.BaseStream,"$directory/host-stdout.bin",1048576)
        $hostErr=$hostErrPump.Drain($hostProcess.StandardError.BaseStream,"$directory/host-stderr.bin",1048576)
        [IO.File]::WriteAllText($request.gate,'admitted')
        while($watch.ElapsedMilliseconds -lt ($DeadlineMs-$SettleMs)) {
            if($CancellationToken.IsCancellationRequested){$cancelled=$true;if($null -eq $failure){$failure='CANCELLED';$failureTick=[Diagnostics.Stopwatch]::GetTimestamp()}else{$secondary.Add('CANCELLED')};break}
            foreach($pidValue in $job.Pids()) {
                if(-not $captured.ContainsKey($pidValue)) {
                    try {$p=[Diagnostics.Process]::GetProcessById($pidValue);$null=$p.Handle;$identities[$pidValue]=$p.StartTime.ToUniversalTime().ToString('o');$captured[$pidValue]=$p} catch {$secondary.Add('CAPTURE_RACE')}
                }
            }
            if($null -eq $rootIdentity -and [IO.File]::Exists($request.identity)) {
                try {$rootIdentity=Get-Content -LiteralPath $request.identity -Raw|ConvertFrom-Json} catch { }
            }
            if($null -ne $rootIdentity -and $captured.ContainsKey([int]$rootIdentity.pid) -and $identities[[int]$rootIdentity.pid] -eq $rootIdentity.startTimeUtc) {
                $root=$captured[[int]$rootIdentity.pid]
                if($root.HasExited -and $null -eq $observedRootExit){$observedRootExit=$root.ExitCode;if($observedRootExit -ne 0 -and $null -eq $failure){$failure='NONZERO_EXIT';$failureTick=[Diagnostics.Stopwatch]::GetTimestamp()}}
            }
            if($hostOutPump.Overflow -or $hostErrPump.Overflow){if($null -eq $failure){$failure='HOST_LOG_LIMIT'};break}
            if($hostProcess.HasExited) { break }
            Start-Sleep -Milliseconds 10
        }
        if([IO.File]::Exists($request.rootExitEvent)){$event=Get-Content -LiteralPath $request.rootExitEvent -Raw|ConvertFrom-Json;$observedRootExit=$event.exitCode;if($event.exitCode -ne 0 -and $event.observationTick -lt $failureTick){if($null -ne $failure -and $failure -ne 'NONZERO_EXIT'){$secondary.Add($failure)};$failure='NONZERO_EXIT';$failureTick=[long]$event.observationTick}}
        if(-not $hostProcess.HasExited -and -not $cancelled -and $watch.ElapsedMilliseconds -ge ($DeadlineMs-$SettleMs)) {if($null -eq $failure){$failure='DEADLINE';$failureTick=[Diagnostics.Stopwatch]::GetTimestamp()}else{$secondary.Add('DEADLINE')}}
        if($hostProcess.HasExited) {$hostExit=$hostProcess.ExitCode}
        if([IO.File]::Exists($request.receipt)) {$hostReceipt=Get-Content -LiteralPath $request.receipt -Raw|ConvertFrom-Json
            if($null -ne $hostReceipt.primaryFailure -and [long]$hostReceipt.primaryFailureTick -lt $failureTick){if($null -ne $failure -and $failure -ne $hostReceipt.primaryFailure){$secondary.Add($failure)};$failure=$hostReceipt.primaryFailure;$failureTick=[long]$hostReceipt.primaryFailureTick}
            foreach($reason in $hostReceipt.secondaryFailures){$secondary.Add($reason)}
        }
        elseif($null -eq $failure){$failure='MISSING_HOST_RECEIPT'}
        $graceUntil=[Math]::Min($DeadlineMs-$SettleMs,$watch.ElapsedMilliseconds+1000)
        while($job.Pids().Count -gt 0 -and $watch.ElapsedMilliseconds -lt $graceUntil){Start-Sleep -Milliseconds 10}
        $membersBeforeTermination=@($job.Pids())
        if($job.Pids().Count -gt 0) {
            if($null -eq $failure){$failure='OWNED_DESCENDANT_REQUIRES_TERMINATION'}
            $termination=$true;try {$job.Terminate()} catch {$secondary.Add('TERMINATION_REQUEST_FAILED')}
        }
        while($watch.ElapsedMilliseconds -lt $DeadlineMs -and ($job.Pids().Count -gt 0 -or -not $hostOut.IsCompleted -or -not $hostErr.IsCompleted)) {Start-Sleep -Milliseconds 10}
        if($hostProcess.HasExited){$hostExit=$hostProcess.ExitCode}
        if($null -ne $rootIdentity -and $captured.ContainsKey([int]$rootIdentity.pid) -and $identities[[int]$rootIdentity.pid] -eq $rootIdentity.startTimeUtc -and $captured[[int]$rootIdentity.pid].HasExited){$observedRootExit=$captured[[int]$rootIdentity.pid].ExitCode}
        $settled=$job.Pids().Count -eq 0 -and $hostProcess.HasExited -and $hostOut.IsCompleted -and $hostErr.IsCompleted
        if($testFault -eq 'unobserved-settlement'){$settled=$false}
        if(-not $settled){if($null -eq $failure){$failure='OWNED_SETTLEMENT_INCOMPLETE'}else{$secondary.Add('OWNED_SETTLEMENT_INCOMPLETE')}}
        if($null -eq $failure -and ($hostExit -ne 0 -or $hostOutPump.Error -or $hostErrPump.Error -or -not $hostOutPump.Eof -or -not $hostErrPump.Eof -or $null -eq $hostReceipt -or -not $hostReceipt.streamsCompleted -or $null -eq $hostReceipt.rootExitCode -or $hostReceipt.rootExitCode -ne 0)){ $failure='INCOMPLETE_PROCESS_RESULT' }
    } catch {
        if($null -eq $failure){$failure='PARENT_FAILURE:'+$_.Exception.Message};$settled=$false
        try{$termination=$true;$job.Terminate()}catch{$secondary.Add('TERMINATION_REQUEST_FAILED')}
        if($started){try{if(-not $hostProcess.HasExited){$hostProcess.Kill()};while(-not $hostProcess.HasExited -and $watch.ElapsedMilliseconds -lt $DeadlineMs){Start-Sleep -Milliseconds 10};if($hostProcess.HasExited){$hostExit=$hostProcess.ExitCode;$settled=$job.Pids().Count -eq 0}}catch{$secondary.Add('DIRECT_HOST_SETTLEMENT_FAILED')}}
    }
    $descendants=@(foreach($key in $captured.Keys){$p=$captured[$key];@{pid=$key;startTimeUtc=$identities[$key];ownershipProof='private-job-member';exitObserved=$p.HasExited;exitCode=$(if($p.HasExited){$p.ExitCode}else{$null})}})
    $receipt=[ordered]@{schema='process/v1';status=$(if($null -eq $failure){'passed'}else{'failed'});primaryFailure=$failure;secondaryFailures=@($secondary)
        hostPid=$(if($started){$hostProcess.Id}else{$null});hostExited=$(if($started){$hostProcess.HasExited}else{$false});hostExitCode=$hostExit;hostReceipt=$hostReceipt;observedRootExitCode=$observedRootExit
        termination=@{requested=$termination};capturedDescendants=$descendants;ownedSettlement=$(if($settled){'complete'}else{'incomplete'})
        coverage='private-job-and-captured-handles';uncapturedDescendantScope='delegated-outside-job-not-proven';elapsedMs=$watch.ElapsedMilliseconds;directory=$directory}
    $receipt['observationAdapterFault']=$testFault
    $receipt['cancelled']=$cancelled
    $receipt['primaryFailureTick']=$failureTick;$receipt['monotonicTickFrequency']=[Diagnostics.Stopwatch]::Frequency
    $receipt['rootExitCode']=$observedRootExit;$receipt['rootExited']=($null -ne $observedRootExit)
    foreach($name in @('stdout','stderr','host-stdout','host-stderr')) {
        $complete=$false
        if($name -eq 'host-stdout' -and $null -ne $hostOutPump){$complete=$hostOutPump.Eof -and -not $hostOutPump.Overflow -and -not $hostOutPump.Error}
        elseif($name -eq 'host-stderr' -and $null -ne $hostErrPump){$complete=$hostErrPump.Eof -and -not $hostErrPump.Overflow -and -not $hostErrPump.Error}
        elseif($null -ne $hostReceipt -and -not $name.StartsWith('host-')){$stream=$hostReceipt.$name;$complete=$stream.eof -and -not $stream.truncated -and -not $stream.error}
        $path=$(if($name.StartsWith('host-')){"$directory/$name.bin"}else{$request[$name]});if([IO.File]::Exists($path)){$receipt[$name]=@{path=$path;length=([IO.FileInfo]$path).Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash;partial=(-not $complete)}}
    }
    $receipt|ConvertTo-Json -Depth 16|Set-Content -LiteralPath "$directory/process-receipt.json" -Encoding utf8NoBOM
    return [pscustomobject]$receipt
    } finally {foreach($p in $captured.Values){$p.Dispose()};$hostProcess.Dispose();$job.Dispose()}
}
Export-ModuleMember -Function Assert-AdmittedPath,Assert-NoReparse,New-OwnedScope,Invoke-OwnedProcess
