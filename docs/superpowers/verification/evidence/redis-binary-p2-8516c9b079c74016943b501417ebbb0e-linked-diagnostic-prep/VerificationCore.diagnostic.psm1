Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-VerificationToolPaths {
    return @('VerificationCore.psm1','OwnedProcessHost.ps1','evidence_tools.py','run-stage.ps1','isolated.gradle','check-core.py','check-p2.py','stage-policy.json','image_tools.py','run-owned.py','probes/G10LinkedRedisProbe.java','probes/MigrationRuntimeDriverProbe.java')
}

# Existing G11 admission is retained. New runs have a named, direct evidence root
# and a UUID owned child; this is a lexical check before any filesystem access.
function Assert-VerificationEvidencePath([string]$Repo,[string]$Path,[ValidateSet('root','owned')][string]$Kind) {
    $repoPath=Assert-AdmittedPath $Repo;$full=Assert-AdmittedPath $Path
    $parent=[IO.Path]::Combine($repoPath,'docs','superpowers','verification','evidence')
    if($full.StartsWith(($parent+[IO.Path]::DirectorySeparatorChar+'g11-'),[StringComparison]::OrdinalIgnoreCase)){return $full}
    $run=$(if($Kind -eq 'owned'){[IO.Path]::GetDirectoryName($full)}else{$full})
    if([IO.Path]::GetDirectoryName($run) -ne $parent -or [IO.Path]::GetFileName($run) -cnotmatch '^redis-binary-p[23]-[0-9a-f]{32}(?:-[a-z][a-z0-9-]{0,63})?$') {throw 'UNADMITTED_NAMED_EVIDENCE'}
    if($Kind -eq 'owned' -and [IO.Path]::GetFileName($full) -cnotmatch '^[0-9a-f]{32}$'){throw 'UNADMITTED_NAMED_SCOPE'}
    return $full
}

function Assert-OwnedExecutableRole($Scope,[string]$Exe,[string]$Role) {
    # Pure lexical admission: wrong roles/foreign paths fail before metadata.
    $full=Assert-AdmittedPath $Exe
    $roles=@{pwsh=$Scope.paths.Pwsh;python=$Scope.paths.Python;'jdk-java'=[IO.Path]::Combine($Scope.paths.Jdk,'bin','java.exe');'jdk-javac'=[IO.Path]::Combine($Scope.paths.Jdk,'bin','javac.exe');'jdk-jimage'=[IO.Path]::Combine($Scope.paths.Jdk,'bin','jimage.exe')}
    if($Scope -is [Collections.IDictionary] -and $Scope.Contains('imageBinding')) {
        $binding=$Scope.imageBinding
        if($Scope.stage -ne 'linked' -or $binding.schema -ne 'owned-image-binding/v1'){throw 'UNADMITTED_IMAGE_ROLE'}
        $runtime=Assert-AdmittedPath $binding.sourceRuntime
        if([IO.Path]::GetFileName($runtime) -notmatch '^datacube-g11-[0-9a-f]{32}$' -or [IO.Path]::GetDirectoryName($runtime) -ne $Scope.paths.RuntimeParent){throw 'UNADMITTED_IMAGE_RUNTIME'}
        $expected=[IO.Path]::Combine($runtime,'build','main','jpackage','DataCube')
        if((Assert-AdmittedPath $binding.image) -ne $expected){throw 'IMAGE_PATH_OUTSIDE_OWNED_RUNTIME'}
        $roles['image-java']=[IO.Path]::Combine($expected,'runtime','bin','java.exe')
    }
    if(-not $Role){$Role=@($roles.Keys|Where-Object {$roles[$_] -eq $full})|Select-Object -First 1}
    if(-not $Role -or -not $roles.ContainsKey($Role) -or $full -ne (Assert-AdmittedPath $roles[$Role])){throw 'EXECUTABLE_ROLE_PATH_MISMATCH'}
    return $Role
}

function Bind-OwnedImage($Scope,[string]$SourceScopePath) {
    if($Scope.stage -ne 'linked'){throw 'IMAGE_BINDING_OUTSIDE_LINKED_STAGE'}
    $sourcePath=Assert-AdmittedPath $SourceScopePath
    $null=Assert-VerificationEvidencePath $Scope.paths.Repo ([IO.Path]::GetDirectoryName($sourcePath)) 'owned'
    if([IO.Path]::GetFileName($sourcePath) -ne 'scope.json'){throw 'UNOWNED_IMAGE_SOURCE_SCOPE'}
    $null=Assert-NoReparse $sourcePath
    $source=Get-Content -LiteralPath $sourcePath -Raw|ConvertFrom-Json -AsHashtable
    if($source.stage -ne 'image' -or $source.paths.Repo -ne $Scope.paths.Repo -or $sourcePath -ne [IO.Path]::Combine($source.owned,'scope.json')){throw 'INVALID_IMAGE_SOURCE_SCOPE'}
    $runtime=Assert-AdmittedPath $source.runtime
    if([IO.Path]::GetFileName($runtime) -notmatch '^datacube-g11-[0-9a-f]{32}$' -or [IO.Path]::GetDirectoryName($runtime) -ne $Scope.paths.RuntimeParent){throw 'UNADMITTED_IMAGE_RUNTIME'}
    $markerPath=[IO.Path]::Combine($runtime,'runtime-owner.json');$null=Assert-NoReparse $markerPath
    if((Get-FileHash -LiteralPath $markerPath).Hash -ne $source.runtimeMarkerSha256){throw 'RUNTIME_OWNER_IDENTITY_CHANGED'}
    $marker=Get-Content -LiteralPath $markerPath -Raw|ConvertFrom-Json
    if($marker.scope -ne $source.owned){throw 'RUNTIME_OWNER_SCOPE_MISMATCH'}
    foreach($relative in (Get-VerificationToolPaths)){if($source.tools[$relative].sha256 -ne $Scope.tools[$relative].sha256){throw 'IMAGE_SOURCE_TOOL_VERSION_MISMATCH'}}
    $resultPath=Assert-NoReparse ([IO.Path]::Combine($source.owned,'stage-result.json'))
    $result=Get-Content -LiteralPath $resultPath -Raw|ConvertFrom-Json -AsHashtable
    $image=[IO.Path]::Combine($runtime,'build','main','jpackage','DataCube')
    if($result.status -ne 'passed' -or $result.mode -ne 'image' -or $result.artifact.image -ne $image){throw 'IMAGE_STAGE_NOT_ACCEPTED'}
    $manifestPath=Assert-NoReparse ([IO.Path]::Combine($source.owned,'image-manifest.json'))
    $manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json -AsHashtable
    if($manifest.schema -ne 'image-files/v1' -or $manifest.image -ne $image){throw 'INVALID_IMAGE_MANIFEST'}
    if(@($manifest.core).Count -ne 4 -or @($manifest.core|ForEach-Object {$_.path}|Sort-Object -Unique).Count -ne 4 -or @($manifest.core|Where-Object {$_.path -notin @('DataCube.exe','app/DataCube.cfg','runtime/lib/modules','runtime/bin/java.exe')}).Count){throw 'INVALID_IMAGE_CORE_INVENTORY'}
    $java=Assert-NoReparse ([IO.Path]::Combine($image,'runtime','bin','java.exe'))
    $identity=$manifest.files|Where-Object {$_.path -eq 'runtime/bin/java.exe'}
    if(@($identity).Count -ne 1 -or (Get-FileHash -LiteralPath $java).Hash -ne $identity.sha256 -or ([IO.FileInfo]$java).Length -ne $identity.length){throw 'IMAGE_EXECUTABLE_IDENTITY_CHANGED'}
    $Scope.imageBinding=@{schema='owned-image-binding/v1';image=$image;sourceRuntime=$runtime;sourceScope=$sourcePath;sourceManifest=$manifestPath;sourceManifestSha256=(Get-FileHash -LiteralPath $manifestPath).Hash;sourceResultSha256=(Get-FileHash -LiteralPath $resultPath).Hash;runtimeMarker=$markerPath;runtimeMarkerSha256=$source.runtimeMarkerSha256;core=$manifest.core}
    $Scope.executables[$java]=@{role='image-java';length=$identity.length;sha256=$identity.sha256}
    return $Scope.imageBinding
}

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

function Assert-RootIdentity($Identity) {
    if($null -eq $Identity -or $Identity -isnot [Collections.IDictionary] -or $Identity.Count -ne 2 -or -not $Identity.Contains('pid') -or -not $Identity.Contains('startTimeUtc')){throw 'ROOT_IDENTITY_SCHEMA'}
    if(($Identity.pid -isnot [int] -and $Identity.pid -isnot [long]) -or $Identity.pid -le 0 -or $Identity.pid -gt [int]::MaxValue){throw 'ROOT_IDENTITY_PID'}
    if($Identity.startTimeUtc -isnot [string] -or $Identity.startTimeUtc -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{7}Z$'){throw 'ROOT_IDENTITY_TIME_TYPE'}
    $time=[DateTime]::ParseExact($Identity.startTimeUtc,'o',[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::RoundtripKind)
    if($time.Kind -ne [DateTimeKind]::Utc -or -not [string]::Equals($time.ToString('o'),$Identity.startTimeUtc,[StringComparison]::Ordinal)){throw 'ROOT_IDENTITY_TIME'}
}
function Read-RootIdentity([string]$Path) {
    $null=Assert-NoReparse $Path
    if(-not [IO.File]::Exists($Path)){throw 'MISSING_ROOT_IDENTITY'}
    $identity=Get-Content -LiteralPath $Path -Raw|ConvertFrom-Json -AsHashtable -DateKind String
    Assert-RootIdentity $identity
    return $identity
}
function Read-RootExitEvent([string]$Path,$Identity) {
    $null=Assert-NoReparse $Path
    if(-not [IO.File]::Exists($Path)){throw 'MISSING_ROOT_EXIT_EVENT'}
    $event=Get-Content -LiteralPath $Path -Raw|ConvertFrom-Json -AsHashtable -DateKind String
    Assert-RootExitEvent $event $Identity
    return $event
}
function Assert-RootExitEvent($Event,$Identity) {
    Assert-RootIdentity $Identity
    $keys=@('schema','pid','startTimeUtc','exitCode','observationTick','elapsedMs')
    if($null -eq $Event -or $Event -isnot [Collections.IDictionary] -or $Event.Count -ne $keys.Count -or @($keys|Where-Object {-not $Event.Contains($_)}).Count -or $Event.schema -cne 'root-exit/v1'){throw 'ROOT_EXIT_EVENT_SCHEMA'}
    Assert-RootIdentity @{pid=$Event.pid;startTimeUtc=$Event.startTimeUtc}
    if($Event.pid -ne $Identity.pid -or -not [string]::Equals($Event.startTimeUtc,$Identity.startTimeUtc,[StringComparison]::Ordinal)){throw 'ROOT_EXIT_EVENT_IDENTITY_MISMATCH'}
    if(($Event.exitCode -isnot [int] -and $Event.exitCode -isnot [long]) -or $Event.exitCode -lt [int]::MinValue -or $Event.exitCode -gt [int]::MaxValue){throw 'ROOT_EXIT_EVENT_CODE'}
    if(($Event.observationTick -isnot [int] -and $Event.observationTick -isnot [long]) -or $Event.observationTick -le 0 -or $Event.observationTick -gt [Diagnostics.Stopwatch]::GetTimestamp()){throw 'ROOT_EXIT_EVENT_TICK'}
    if(($Event.elapsedMs -isnot [int] -and $Event.elapsedMs -isnot [long]) -or $Event.elapsedMs -lt 0){throw 'ROOT_EXIT_EVENT_ELAPSED'}
}
function Resolve-RootExitProof($Identity,$Event,$HostReceipt,$CapturedStart,$CapturedExit) {
    Assert-RootExitEvent $Event $Identity
    if($null -eq $HostReceipt -or $HostReceipt.started -isnot [bool] -or $HostReceipt.rootExited -isnot [bool] -or $HostReceipt.rootEventPublished -isnot [bool] -or ($HostReceipt.rootExitCode -isnot [int] -and $HostReceipt.rootExitCode -isnot [long]) -or ($HostReceipt.rootObservationTick -isnot [int] -and $HostReceipt.rootObservationTick -isnot [long]) -or $HostReceipt.schema -cne 'host-process/v1' -or -not $HostReceipt.started -or -not $HostReceipt.rootExited -or -not $HostReceipt.rootEventPublished -or $HostReceipt.rootPid -ne $Identity.pid -or $HostReceipt.rootStartTimeUtc -isnot [string] -or -not [string]::Equals($HostReceipt.rootStartTimeUtc,$Identity.startTimeUtc,[StringComparison]::Ordinal) -or $null -eq $HostReceipt.rootExitCode -or $HostReceipt.rootExitCode -ne $Event.exitCode -or $HostReceipt.rootObservationTick -ne $Event.observationTick){throw 'ROOT_EXIT_HOST_CONTRADICTION'}
    if($null -ne $CapturedStart -and ($CapturedStart -isnot [string] -or -not [string]::Equals($CapturedStart,$Identity.startTimeUtc,[StringComparison]::Ordinal) -or $null -eq $CapturedExit -or $CapturedExit -ne $Event.exitCode)){throw 'ROOT_EXIT_CAPTURE_CONTRADICTION'}
    return @{schema='root-exit-proof/v1';pid=$Identity.pid;startTimeUtc=$Identity.startTimeUtc;exitCode=$Event.exitCode;observationTick=$Event.observationTick;source=$(if($null -ne $CapturedStart){'host-held-root-handle-event-and-parent-captured-handle'}else{'host-held-root-handle-event'});parentCaptured=($null -ne $CapturedStart)}
}

function New-OwnedScope {
    param([Parameter(Mandatory)][string]$Repo,[Parameter(Mandatory)][string]$EvidenceRoot,
          [Parameter(Mandatory)][string]$Jdk,[Parameter(Mandatory)][string]$Cache,
          [Parameter(Mandatory)][string]$Pwsh,[Parameter(Mandatory)][string]$Python,
          [Parameter(Mandatory)][string]$Stage,[string]$RuntimeParent='')
    if(-not $RuntimeParent){$RuntimeParent=([IO.Path]::GetTempPath()).TrimEnd([char]92,[char]47)}
    $paths=@{}
    foreach ($name in @('Repo','EvidenceRoot','Jdk','Cache','Pwsh','Python','RuntimeParent')) { $paths[$name]=Assert-AdmittedPath (Get-Variable $name -ValueOnly) }
    $null=Assert-VerificationEvidencePath $paths.Repo $paths.EvidenceRoot 'root'
    foreach ($name in $paths.Keys) { $null=Assert-NoReparse $paths[$name] }
    if ($Stage -notmatch '^[a-z][a-z0-9-]{0,63}$') { throw 'INVALID_STAGE' }
    if ([IO.Directory]::Exists($paths.EvidenceRoot) -or [IO.File]::Exists($paths.EvidenceRoot)) { throw 'RUN_COLLISION' }
    foreach ($name in @('Repo','Jdk','Cache','RuntimeParent')) { if (-not [IO.Directory]::Exists($paths[$name])) { throw "MISSING_$name" } }
    foreach ($name in @('Pwsh','Python')) { if (-not [IO.File]::Exists($paths[$name])) { throw "MISSING_$name" } }
    Initialize-OwnedJob
    $null=[IO.Directory]::CreateDirectory($paths.EvidenceRoot)
    $marker=[IO.File]::Open([IO.Path]::Combine($paths.EvidenceRoot,'run-owner.json'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $bytes=[Text.Encoding]::UTF8.GetBytes('{"owner":"'+[guid]::NewGuid().ToString('N')+'"}');$marker.Write($bytes,0,$bytes.Length) } finally {$marker.Dispose()}
    $owned=[IO.Path]::Combine($paths.EvidenceRoot,[guid]::NewGuid().ToString('N'))
    $null=[IO.Directory]::CreateDirectory($owned)
    foreach ($name in @('processes','tools')) { $null=[IO.Directory]::CreateDirectory([IO.Path]::Combine($owned,$name)) }
    $runtime=Assert-AdmittedPath ([IO.Path]::Combine($paths.RuntimeParent,'datacube-g11-'+[guid]::NewGuid().ToString('N')))
    $null=Assert-NoReparse $runtime
    if([IO.Directory]::Exists($runtime)){throw 'RUNTIME_COLLISION'}
    foreach($name in @('home','temp','build')){$null=[IO.Directory]::CreateDirectory([IO.Path]::Combine($runtime,$name))}
    $runtimeMarker=[IO.Path]::Combine($runtime,'runtime-owner.json')
    $markerStream=[IO.File]::Open($runtimeMarker,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$markerBytes=[Text.Encoding]::UTF8.GetBytes(('{"scope":"'+$owned.Replace('\','\\')+'"}'));$markerStream.Write($markerBytes,0,$markerBytes.Length)}finally{$markerStream.Dispose()}
    $environment=@{}
    foreach ($name in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')) {
        $value=[Environment]::GetEnvironmentVariable($name); if($null -ne $value) { $environment[$name]=$value }
    }
    $ownedHome=[IO.Path]::Combine($runtime,'home');$ownedTemp=[IO.Path]::Combine($runtime,'temp')
    $environment.HOME=$ownedHome;$environment.USERPROFILE=$ownedHome;$environment.TEMP=$ownedTemp;$environment.TMP=$ownedTemp
    $environment.JAVA_HOME=$paths.Jdk;$environment.GRADLE_USER_HOME=$paths.Cache
    $environment.G11_BUILD=[IO.Path]::Combine($runtime,'build')
    $tools=@{}
    foreach($relative in (Get-VerificationToolPaths)) {
        $source=Assert-NoReparse ([IO.Path]::Combine($paths.Repo,'scripts','verification',$relative))
        if(-not [IO.File]::Exists($source)){throw "MISSING_TOOL:$relative"}
        $dest=Assert-AdmittedPath ([IO.Path]::Combine($owned,'tools',$relative));$null=[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))
        [IO.File]::Copy($source,$dest,$false)
        $tools[$relative]=@{source=$source;frozen=$dest;length=([IO.FileInfo]$source).Length;sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash}
    }
    $executables=@{}
    foreach($role in @('pwsh','python','jdk-java','jdk-javac','jdk-jimage')){
        $exe=switch($role){'pwsh'{$paths.Pwsh};'python'{$paths.Python};default{[IO.Path]::Combine($paths.Jdk,'bin',($role.Substring(4)+'.exe'))}}
        $null=Assert-NoReparse $exe;$executables[$exe]=@{role=$role;length=([IO.FileInfo]$exe).Length;sha256=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash}
    }
    return [pscustomobject]@{schema='owned-scope/v1';paths=$paths;stage=$Stage;owned=$owned;runtime=$runtime;runtimeMarkerSha256=(Get-FileHash -LiteralPath $runtimeMarker).Hash;environment=$environment;tools=$tools;executables=$executables;shortHome=[OwnedJob]::ShortPath($ownedHome);shortTemp=[OwnedJob]::ShortPath($ownedTemp)}
}

function Invoke-OwnedProcess {
    param([Parameter(Mandatory)]$Scope,[Parameter(Mandatory)][string]$Name,
          [Parameter(Mandatory)][string]$Exe,[Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Argv,
          [Parameter(Mandatory)][string]$Cwd,[Parameter(Mandatory)][string]$HostScript,
          [int]$DeadlineMs=600000,[int]$SettleMs=5000,[long]$StreamCap=33554432,
          [Threading.CancellationToken]$CancellationToken=[Threading.CancellationToken]::None,
          [string]$Role='')
    $watch=[Diagnostics.Stopwatch]::StartNew()
    if($Name -notmatch '^[a-z][a-z0-9-]{0,63}$' -or $DeadlineMs -le $SettleMs -or $SettleMs -lt 100 -or $StreamCap -lt 1) { throw 'INVALID_PROCESS_POLICY' }
    $exePath=Assert-AdmittedPath $Exe;$cwdPath=Assert-AdmittedPath $Cwd;$hostPath=Assert-AdmittedPath $HostScript
    foreach($path in @($Scope.paths.Repo,$Scope.paths.Jdk,$Scope.paths.Cache,$Scope.paths.Pwsh,$Scope.paths.Python,$Scope.owned)){ $null=Assert-AdmittedPath $path }
    $null=Assert-VerificationEvidencePath $Scope.paths.Repo $Scope.owned 'owned'
    $Role=Assert-OwnedExecutableRole $Scope $exePath $Role
    if($Scope.schema -ne 'owned-scope/v1' -or $cwdPath -notin @($Scope.paths.Repo,$Scope.owned) -or $hostPath -ne $Scope.tools['OwnedProcessHost.ps1'].frozen){throw 'UNADMITTED_PROCESS_REQUEST'}
    foreach($exe in @($exePath,$Scope.paths.Pwsh)){$null=Assert-NoReparse $exe;if((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash -ne $Scope.executables[$exe].sha256 -or ([IO.FileInfo]$exe).Length -ne $Scope.executables[$exe].length){throw 'EXECUTABLE_IDENTITY_CHANGED'}}
    if($Scope.stage -eq 'linked' -and $Scope.Contains('imageBinding')){
        foreach($pair in @(@($Scope.imageBinding.sourceManifest,$Scope.imageBinding.sourceManifestSha256),@([IO.Path]::Combine([IO.Path]::GetDirectoryName($Scope.imageBinding.sourceScope),'stage-result.json'),$Scope.imageBinding.sourceResultSha256),@($Scope.imageBinding.runtimeMarker,$Scope.imageBinding.runtimeMarkerSha256))){
            $path=Assert-NoReparse $pair[0];if((Get-FileHash -LiteralPath $path).Hash -ne $pair[1]){throw 'IMAGE_BINDING_IDENTITY_CHANGED'}
        }
        foreach($item in $Scope.imageBinding.core){
            if($item.path -notin @('DataCube.exe','app/DataCube.cfg','runtime/lib/modules','runtime/bin/java.exe')){throw 'UNKNOWN_IMAGE_CORE_PATH'}
            $path=Assert-NoReparse ([IO.Path]::Combine($Scope.imageBinding.image,$item.path))
            if((Get-FileHash -LiteralPath $path).Hash -ne $item.sha256 -or ([IO.FileInfo]$path).Length -ne $item.length){throw 'IMAGE_CORE_IDENTITY_CHANGED'}
        }
    }
    foreach($relative in $Scope.tools.Keys){$tool=$Scope.tools[$relative]
        if($relative -notin (Get-VerificationToolPaths)){throw 'UNADMITTED_TOOL_BINDING'}
        $expectedSource=Assert-AdmittedPath ([IO.Path]::Combine($Scope.paths.Repo,'scripts','verification',$relative))
        $expectedFrozen=Assert-AdmittedPath ([IO.Path]::Combine($Scope.owned,'tools',$relative))
        if((Assert-AdmittedPath $tool.source) -ne $expectedSource -or (Assert-AdmittedPath $tool.frozen) -ne $expectedFrozen){throw 'UNADMITTED_TOOL_BINDING'}
        foreach($path in @($tool.source,$tool.frozen)){$null=Assert-NoReparse $path;if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $tool.sha256){throw 'TOOL_IDENTITY_CHANGED'}}
    }
    foreach($path in @($exePath,$cwdPath,$hostPath)) { $null=Assert-NoReparse $path }
    $directory=[IO.Path]::Combine($Scope.owned,'processes',$Name)
    if([IO.Directory]::Exists($directory)) { throw 'PROCESS_COLLISION' }
    $null=[IO.Directory]::CreateDirectory($directory)
    $testFault=$(if($Scope -is [Collections.IDictionary] -and $Scope.Contains('testFault')){$Scope.testFault}else{$null})
    $exitFaults=@('exit-tail-zero','exit-tail-seven','exit-late-identity','exit-no-capture-zero','exit-no-capture-seven','exit-missing-event','exit-corrupt-event','exit-wrong-pid','exit-wrong-time','exit-wrong-code','exit-missing-identity','exit-corrupt-identity','exit-summary-mismatch','exit-missing-seven','exit-cancel-missing','exit-budget-missing','exit-date-coercion','exit-root-is-host')
    if($testFault -and ($Scope.stage -ne 'fixture' -or $testFault -notin (@('start-failure','assign-failure','unobserved-settlement')+$exitFaults))){throw 'FAULT_ADAPTER_OUTSIDE_FIXTURE'}
    $request=[ordered]@{role=$Role;exe=$exePath;argv=@($Argv);cwd=$cwdPath;environment=$Scope.environment;deadlineMs=$DeadlineMs;settleMs=$SettleMs;streamCap=$StreamCap
        stage=$Scope.stage;observationFault=$(if($testFault -in @('exit-tail-zero','exit-tail-seven','exit-late-identity')){$testFault}else{$null});gate="$directory/gate";identity="$directory/root-identity.json";rootExitEvent="$directory/root-exit.json";stdout="$directory/stdout.bin";stderr="$directory/stderr.bin";receipt="$directory/host-receipt.json"}
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
    $rootProof=$null;$rootEvent=$null;$rootEvidenceError=$null
    # DIAGNOSTIC ONLY: observations and held handles may change scheduling.
    $diagEvents=[Collections.Generic.List[object]]::new();$diagRows=[Collections.Generic.List[object]]::new();$diagHandles=@{};$diagOverflow=$false
    $queryJob={param([string]$site)
        $before=[Diagnostics.Stopwatch]::GetTimestamp();$members=@($job.Pids());$after=[Diagnostics.Stopwatch]::GetTimestamp()
        if($diagRows.Count -lt 4096){
            $states=@(foreach($member in $members){
                $entry=[ordered]@{pid=$member;startTimeUtc=$null;exitObserved=$null;exitCode=$null;error=$null}
                try {
                    if(-not $diagHandles.ContainsKey($member) -and $diagHandles.Count -lt 128){$candidate=[Diagnostics.Process]::GetProcessById($member);$null=$candidate.Handle;$diagHandles[$member]=$candidate}
                    if($diagHandles.ContainsKey($member)){$held=$diagHandles[$member];$entry.startTimeUtc=$held.StartTime.ToUniversalTime().ToString('o');$entry.exitObserved=$held.HasExited;if($entry.exitObserved){$entry.exitCode=$held.ExitCode}}
                    else{$entry.error='DIAGNOSTIC_HANDLE_CAP'}
                }catch{$entry.error=$_.Exception.GetType().FullName+':'+$_.Exception.Message}
                $entry
            })
            $diagRows.Add(@{site=$site;queryBeforeTick=$before;queryAfterTick=$after;members=$members;identities=$states;terminationRequested=$termination;hostExited=$(if($started){$hostProcess.HasExited}else{$null})})
        }else{$script:diagOverflow=$true}
        return ,$members
    }
    $terminateJob={
        $diagEvents.Add(@{event='termination-request';tick=[Diagnostics.Stopwatch]::GetTimestamp()})
        try {$job.Terminate();$diagEvents.Add(@{event='termination-return';tick=[Diagnostics.Stopwatch]::GetTimestamp();succeeded=$true})}
        catch {$diagEvents.Add(@{event='termination-return';tick=[Diagnostics.Stopwatch]::GetTimestamp();succeeded=$false;error=$_.Exception.Message});throw}
    }
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
            foreach($pidValue in $(if($testFault -in @('exit-late-identity','exit-no-capture-zero','exit-no-capture-seven')){@()}else{(& $queryJob 'query-01')})) {
                if(-not $captured.ContainsKey($pidValue)) {
                    try {$p=[Diagnostics.Process]::GetProcessById($pidValue);$null=$p.Handle;$identities[$pidValue]=$p.StartTime.ToUniversalTime().ToString('o');$captured[$pidValue]=$p} catch {$secondary.Add('CAPTURE_RACE')}
                }
            }
            if($testFault -ne 'exit-late-identity' -and $null -eq $rootIdentity -and [IO.File]::Exists($request.identity)) {
                try {$rootIdentity=Read-RootIdentity $request.identity} catch { }
            }
            if($null -ne $rootIdentity -and $captured.ContainsKey([int]$rootIdentity.pid) -and [string]::Equals($identities[[int]$rootIdentity.pid],$rootIdentity.startTimeUtc,[StringComparison]::Ordinal)) {
                $root=$captured[[int]$rootIdentity.pid]
                if($root.HasExited -and $null -eq $observedRootExit){$observedRootExit=$root.ExitCode;if($observedRootExit -ne 0 -and $null -eq $failure){$failure='NONZERO_EXIT';$failureTick=[Diagnostics.Stopwatch]::GetTimestamp()}}
            }
            if($hostOutPump.Overflow -or $hostErrPump.Overflow){if($null -eq $failure){$failure='HOST_LOG_LIMIT'};break}
            if($hostProcess.HasExited) { break }
            Start-Sleep -Milliseconds 10
        }
        # Read a directly observed event before settlement; final reconciliation below is mandatory.
        try {
            $rootIdentity=Read-RootIdentity $request.identity
            $rootEvent=Read-RootExitEvent $request.rootExitEvent $rootIdentity
            if($null -eq $observedRootExit){$observedRootExit=$rootEvent.exitCode}
            if($rootEvent.exitCode -ne 0 -and $rootEvent.observationTick -lt $failureTick){if($null -ne $failure -and $failure -ne 'NONZERO_EXIT'){$secondary.Add($failure)};$failure='NONZERO_EXIT';$failureTick=[long]$rootEvent.observationTick}
        } catch { } # Missing/incomplete evidence is rejected after actual settlement, never silently accepted.

        if(-not $hostProcess.HasExited -and -not $cancelled -and $watch.ElapsedMilliseconds -ge ($DeadlineMs-$SettleMs)) {if($null -eq $failure){$failure='DEADLINE';$failureTick=[Diagnostics.Stopwatch]::GetTimestamp()}else{$secondary.Add('DEADLINE')}}
        if($hostProcess.HasExited) {$hostExit=$hostProcess.ExitCode}
        if([IO.File]::Exists($request.receipt)) {$hostReceipt=Get-Content -LiteralPath $request.receipt -Raw|ConvertFrom-Json -DateKind String
            if($null -ne $hostReceipt.primaryFailure -and [long]$hostReceipt.primaryFailureTick -lt $failureTick){if($null -ne $failure -and $failure -ne $hostReceipt.primaryFailure){$secondary.Add($failure)};$failure=$hostReceipt.primaryFailure;$failureTick=[long]$hostReceipt.primaryFailureTick}
            foreach($reason in $hostReceipt.secondaryFailures){$secondary.Add($reason)}
        }
        elseif($null -eq $failure){$failure='MISSING_HOST_RECEIPT'}
        $graceUntil=[Math]::Min($DeadlineMs-$SettleMs,$watch.ElapsedMilliseconds+1000)
        while((& $queryJob 'query-02').Count -gt 0 -and $watch.ElapsedMilliseconds -lt $graceUntil){Start-Sleep -Milliseconds 10}
        $membersBeforeTermination=@((& $queryJob 'query-03'))
        if((& $queryJob 'query-04').Count -gt 0) {
            if($null -eq $failure){$failure='OWNED_DESCENDANT_REQUIRES_TERMINATION'}
            $termination=$true;try {& $terminateJob} catch {$secondary.Add('TERMINATION_REQUEST_FAILED')}
        }
        while($watch.ElapsedMilliseconds -lt $DeadlineMs -and ((& $queryJob 'query-05').Count -gt 0 -or -not $hostOut.IsCompleted -or -not $hostErr.IsCompleted)) {Start-Sleep -Milliseconds 10}
        if($hostProcess.HasExited){$hostExit=$hostProcess.ExitCode}
        if($null -ne $rootIdentity -and $captured.ContainsKey([int]$rootIdentity.pid) -and [string]::Equals($identities[[int]$rootIdentity.pid],$rootIdentity.startTimeUtc,[StringComparison]::Ordinal) -and $captured[[int]$rootIdentity.pid].HasExited){$observedRootExit=$captured[[int]$rootIdentity.pid].ExitCode}
        $settled=(& $queryJob 'query-06').Count -eq 0 -and $hostProcess.HasExited -and $hostOut.IsCompleted -and $hostErr.IsCompleted -and @($captured.Values|Where-Object {-not $_.HasExited}).Count -eq 0
        if($testFault -eq 'unobserved-settlement'){$settled=$false}
        if(-not $settled){if($null -eq $failure){$failure='OWNED_SETTLEMENT_INCOMPLETE'}else{$secondary.Add('OWNED_SETTLEMENT_INCOMPLETE')}}
        if($null -eq $failure -and ($hostExit -ne 0 -or $hostOutPump.Error -or $hostErrPump.Error -or -not $hostOutPump.Eof -or -not $hostErrPump.Eof -or $null -eq $hostReceipt -or -not $hostReceipt.streamsCompleted -or $null -eq $hostReceipt.rootExitCode -or $hostReceipt.rootExitCode -ne 0)){ $failure='INCOMPLETE_PROCESS_RESULT' }
    } catch {
        if($null -eq $failure){$failure='PARENT_FAILURE:'+$_.Exception.Message};$settled=$false
        try{$termination=$true;& $terminateJob}catch{$secondary.Add('TERMINATION_REQUEST_FAILED')}
        if($started){try{if(-not $hostProcess.HasExited){$hostProcess.Kill()};while(-not $hostProcess.HasExited -and $watch.ElapsedMilliseconds -lt $DeadlineMs){Start-Sleep -Milliseconds 10};if($hostProcess.HasExited){$hostExit=$hostProcess.ExitCode;$settled=(& $queryJob 'query-07').Count -eq 0 -and @($captured.Values|Where-Object {-not $_.HasExited}).Count -eq 0 -and (($null -eq $hostOutPump -and $null -eq $hostErrPump -and -not [IO.File]::Exists($request.gate)) -or ($null -ne $hostOutPump -and $null -ne $hostErrPump -and $hostOut.IsCompleted -and $hostErr.IsCompleted))}}catch{$secondary.Add('DIRECT_HOST_SETTLEMENT_FAILED')}}
    }
    try {
        if($testFault -in @('exit-missing-event','exit-corrupt-event','exit-wrong-pid','exit-wrong-time','exit-wrong-code','exit-missing-identity','exit-corrupt-identity','exit-summary-mismatch','exit-missing-seven','exit-cancel-missing','exit-budget-missing','exit-date-coercion','exit-root-is-host')) {
            foreach($path in @($request.identity,$request.rootExitEvent,$request.receipt)){if([IO.File]::Exists($path)){[IO.File]::Copy($path,$path+'.before-fixture-fault',$false)}}
        }
        if($testFault -in @('exit-missing-event','exit-missing-seven','exit-cancel-missing','exit-budget-missing')){[IO.File]::Delete($request.rootExitEvent)}
        if($testFault -eq 'exit-corrupt-event'){[IO.File]::WriteAllText($request.rootExitEvent,'{broken')}
        if($testFault -eq 'exit-missing-identity'){[IO.File]::Delete($request.identity)}
        if($testFault -eq 'exit-corrupt-identity'){[IO.File]::WriteAllText($request.identity,'{broken')}
        $rootIdentity=Read-RootIdentity $request.identity
        if($testFault -eq 'exit-date-coercion'){$rootIdentity=Get-Content -LiteralPath $request.identity -Raw|ConvertFrom-Json -AsHashtable}
        $rootEvent=Read-RootExitEvent $request.rootExitEvent $rootIdentity
        if($testFault -in @('exit-wrong-pid','exit-wrong-time','exit-wrong-code')) {
            switch($testFault){'exit-wrong-pid'{$rootEvent.pid++};'exit-wrong-time'{$rootEvent.startTimeUtc='2000-01-01T00:00:00.0000000Z'};'exit-wrong-code'{$rootEvent.exitCode=7}}
            $rootEvent|ConvertTo-Json|Set-Content -LiteralPath $request.rootExitEvent -Encoding utf8NoBOM
        }
        if($testFault -eq 'exit-summary-mismatch'){$hostReceipt.rootExitCode=7;$hostReceipt|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $request.receipt -Encoding utf8NoBOM}
        if($testFault -eq 'exit-root-is-host') {
            $rootIdentity.pid=$hostProcess.Id;$rootEvent.pid=$hostProcess.Id
            $rootIdentity|ConvertTo-Json|Set-Content -LiteralPath $request.identity -Encoding utf8NoBOM
            $rootEvent|ConvertTo-Json|Set-Content -LiteralPath $request.rootExitEvent -Encoding utf8NoBOM
        }
        if($rootIdentity.pid -eq $hostProcess.Id){throw 'ROOT_IDENTITY_IS_HOST'}
        $capturedStart=$null;$capturedExit=$null
        if($captured.ContainsKey([int]$rootIdentity.pid)) {
            $capturedStart=$identities[[int]$rootIdentity.pid]
            if($captured[[int]$rootIdentity.pid].HasExited){$capturedExit=$captured[[int]$rootIdentity.pid].ExitCode}
        }
        $rootProof=Resolve-RootExitProof $rootIdentity $rootEvent $hostReceipt $capturedStart $capturedExit
        if($null -ne $observedRootExit -and $observedRootExit -ne $rootProof.exitCode){throw 'ROOT_EXIT_OBSERVATIONS_CONTRADICT'}
        $observedRootExit=$rootProof.exitCode
        if($observedRootExit -ne 0 -and $rootProof.observationTick -lt $failureTick){if($null -ne $failure -and $failure -ne 'NONZERO_EXIT'){$secondary.Add($failure)};$failure='NONZERO_EXIT';$failureTick=[long]$rootProof.observationTick}
    } catch {
        $rootProof=$null;$rootEvidenceError='ROOT_EXIT_EVIDENCE:'+ $_.Exception.Message
        if($null -eq $failure){$failure=$rootEvidenceError;$failureTick=[Diagnostics.Stopwatch]::GetTimestamp()}else{$secondary.Add($rootEvidenceError)}
    }
    # A successful host summary alone cannot prove the root exited, nor override stream/Job settlement.
    if($null -eq $failure -and ($null -eq $rootProof -or $null -eq $observedRootExit -or $observedRootExit -ne 0 -or -not $settled -or -not $hostReceipt.rootExited -or -not $hostReceipt.stdout.eof -or -not $hostReceipt.stderr.eof -or $hostReceipt.stdout.truncated -or $hostReceipt.stderr.truncated -or $hostReceipt.stdout.error -or $hostReceipt.stderr.error)){$failure='INCOMPLETE_ROOT_EXIT_RESULT'}
    $descendants=@(foreach($key in $captured.Keys){$p=$captured[$key];@{pid=$key;startTimeUtc=$identities[$key];ownershipProof='private-job-member';exitObserved=$p.HasExited;exitCode=$(if($p.HasExited){$p.ExitCode}else{$null})}})
    $receipt=[ordered]@{schema='process/v1';status=$(if($null -eq $failure){'passed'}else{'failed'});primaryFailure=$failure;secondaryFailures=@($secondary)
        hostPid=$(if($started){$hostProcess.Id}else{$null});hostExited=$(if($started){$hostProcess.HasExited}else{$false});hostExitCode=$hostExit;hostReceipt=$hostReceipt;observedRootExitCode=$observedRootExit
        termination=@{requested=$termination};capturedDescendants=$descendants;ownedSettlement=$(if($settled){'complete'}else{'incomplete'})
        coverage='private-job-and-captured-handles';uncapturedDescendantScope='delegated-outside-job-not-proven';elapsedMs=$watch.ElapsedMilliseconds;directory=$directory}
    $receipt['observationAdapterFault']=$testFault
    $receipt['cancelled']=$cancelled
    $receipt['primaryFailureTick']=$failureTick;$receipt['monotonicTickFrequency']=[Diagnostics.Stopwatch]::Frequency
    $receipt['rootExitProof']=$rootProof;$receipt['rootExitEvidenceError']=$rootEvidenceError
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
    } finally {
        try {
            $final=@(foreach($key in $diagHandles.Keys){$held=$diagHandles[$key];@{pid=$key;startTimeUtc=$held.StartTime.ToUniversalTime().ToString('o');exitObserved=$held.HasExited;exitCode=$(if($held.HasExited){$held.ExitCode}else{$null})}})
            @{diagnosticOnly=$true;queryCap=4096;handleCap=128;overflow=($diagOverflow -or $script:diagOverflow);rows=@($diagRows);events=@($diagEvents);finalHeldHandles=$final;failure=$failure;failureTick=$failureTick;terminationRequested=$termination;completedTick=[Diagnostics.Stopwatch]::GetTimestamp()}|ConvertTo-Json -Depth 16|Set-Content -LiteralPath "$directory/job-observations.json" -Encoding utf8NoBOM
        } finally {foreach($held in $diagHandles.Values){$held.Dispose()};foreach($p in $captured.Values){$p.Dispose()};$hostProcess.Dispose();$job.Dispose()}
    }
}
Export-ModuleMember -Function Assert-AdmittedPath,Assert-NoReparse,New-OwnedScope,Invoke-OwnedProcess,Get-VerificationToolPaths,Assert-OwnedExecutableRole,Bind-OwnedImage,Assert-VerificationEvidencePath
