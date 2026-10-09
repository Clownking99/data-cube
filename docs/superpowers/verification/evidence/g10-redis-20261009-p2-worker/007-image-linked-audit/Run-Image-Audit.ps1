param([string]$Name='007-image-linked-audit',[string]$ImageRun='006-image-forced')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$out=Join-Path $PSScriptRoot $Name
if(Test-Path -LiteralPath $out){throw 'Fresh audit required'}
New-Item -ItemType Directory -Path $out | Out-Null
$build=Get-Content -LiteralPath (Join-Path $PSScriptRoot "$ImageRun/command.json") -Raw | ConvertFrom-Json
$image=Join-Path $build.owned 'build/main/jpackage/DataCube'
if(!(Test-Path -LiteralPath (Join-Path $image 'runtime/lib/modules'))){throw 'Image missing'}
$owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-g10-linked-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $owned 'classes'),(Join-Path $owned 'profile'),(Join-Path $owned 'temp') | Out-Null
$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
Add-Type 'using System.Text;using System.Runtime.InteropServices;public static class G10LinkedShort {[DllImport("kernel32.dll",CharSet=CharSet.Unicode)]public static extern uint GetShortPathName(string path,StringBuilder result,uint size);}'
$buffer=[Text.StringBuilder]::new(4096);$length=[G10LinkedShort]::GetShortPathName((Join-Path $owned 'temp'),$buffer,4096);$short=$buffer.ToString()
if($length -eq 0 -or $length -ge 4096){throw 'Owned temp alias unavailable'}
$baseline=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot "$ImageRun/inputs-before.json") -Raw | ConvertFrom-Json)
function Verify-Inputs {
 foreach($item in $baseline){$file=Join-Path $repo $item.path;if((Get-Item -LiteralPath $file).Length -ne $item.length -or (Get-FileHash -LiteralPath $file).Hash -ne $item.sha256){throw ('Changed product input: '+$item.path)}}
}
Verify-Inputs
$baseline | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-before.json') -Encoding utf8
$probeNames=@('G10LinkedRedisProbe.java','MigrationRuntimeDriverProbe.java','Run-Image-Audit.ps1')
$probes=@(foreach($name in $probeNames){$file=Join-Path $PSScriptRoot $name;Copy-Item -LiteralPath $file -Destination $out;@{path=$name;length=(Get-Item -LiteralPath $file).Length;sha256=(Get-FileHash -LiteralPath $file).Hash}})
$probes | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $out 'probe-inputs.json') -Encoding utf8
function Run-Child([string]$Label,[string]$Exe,[string[]]$Argv) {
 $step=Join-Path $out $Label;New-Item -ItemType Directory -Path $step | Out-Null
 $psi=[Diagnostics.ProcessStartInfo]::new();$psi.FileName=$Exe;$psi.WorkingDirectory=$owned;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true;$psi.Environment.Clear()
 foreach($key in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')){$value=[Environment]::GetEnvironmentVariable($key);if($value){$psi.Environment[$key]=$value}}
 $psi.Environment['USERPROFILE']=Join-Path $owned 'profile';$psi.Environment['TEMP']=$short;$psi.Environment['TMP']=$short
 $psi.Environment['JAVA_TOOL_OPTIONS']='"-Duser.home='+$owned+'/profile" "-Djava.io.tmpdir='+$short+'"'
 foreach($arg in $Argv){$psi.ArgumentList.Add($arg)}
 @{exe=$Exe;argv=$Argv;owned=$owned;shortTemp=$short;exeSha256=(Get-FileHash -LiteralPath $Exe).Hash;environment='Cleared; five named OS variables plus UUID home/temp; no service configuration'} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $step 'command.json') -Encoding utf8
 $p=[Diagnostics.Process]::new();$p.StartInfo=$psi
 try {
  if(!$p.Start()){throw 'Child did not start'};$stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync();$timeout=!$p.WaitForExit(30000)
  if($timeout){$p.Kill($true);$p.WaitForExit()}
  $code=$p.ExitCode
  [IO.File]::WriteAllText((Join-Path $step 'stdout.log'),$stdout.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
  [IO.File]::WriteAllText((Join-Path $step 'stderr.log'),$stderr.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
  @{exitCode=$code;pid=$p.Id;processExited=$p.HasExited;timedOut=$timeout;utc=[datetime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $step 'exit.json') -Encoding utf8
  if($timeout -or $code -ne 0){throw ('Child failed: '+$Label)}
 }finally{$p.Dispose()}
}
Run-Child 'module-index' "$jdk/bin/jimage.exe" @('list',"$image/runtime/lib/modules")
$index=Get-Content -LiteralPath (Join-Path $out 'module-index/stdout.log')
$types=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$testSourceCount=0
foreach($item in $baseline){$normalized=$item.path.Replace('\','/');if($normalized -match '^test/.*\.java$'){$testSourceCount++;[void]$types.Add(($normalized -replace '^test/','' -replace '\.java$',''))}}
if($testSourceCount -ne 408 -or $types.Count -ne $testSourceCount){throw 'Test-type leak inventory must cover all 408 frozen test sources'}
$leaks=@($index | Where-Object {$type=$_.Trim() -replace '\$.*$','' -replace '\.class$','';$types.Contains($type) -or $type -match '^(org/junit/|org/mockito/|org/testfx/|acceptance/)' -or $type -match '(G10Linked|RuntimeDriverProbe|DesktopProbe|ShutdownDesktopProbe)'})
$files=@(Get-ChildItem -LiteralPath $image -Recurse -File | ForEach-Object {@{path=[IO.Path]::GetRelativePath($image,$_.FullName).Replace('\','/');length=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
$fileLeaks=@($files | Where-Object path -Match '(?i)(\.datacube|(^|/)profiles?(/|$)|test-results|acceptance|Probe|fixture|isolation)')
$cfg=Get-Content -LiteralPath "$image/app/DataCube.cfg" -Raw
$optionLeaks=$cfg -match '(?i)(user.home|headless|java.io.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic)'
$required=@('RedisDisplayLimits','RedisDisplaySupport','RedisKeySnapshot','RedisTextRetention','RespClient','RedisSessionManager')
foreach($name in $required){if(!($index | Where-Object {$_.Trim() -eq "com/datacube/redis/$name.class"})){throw ('Product missing from linked module: '+$name)}}
$files | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $out 'image-manifest.json') -Encoding utf8
Copy-Item -LiteralPath "$image/app/DataCube.cfg" -Destination (Join-Path $out 'DataCube.cfg')
if($leaks.Count -or $fileLeaks.Count -or $optionLeaks){@{classLeaks=$leaks;fileLeaks=$fileLeaks;optionLeaks=$optionLeaks;passed=$false} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'audit.json');throw 'Image leakage'}
$exports=@('--add-exports=com.datacube/com.datacube.redis=ALL-UNNAMED','--add-exports=com.datacube/com.datacube.migration=ALL-UNNAMED')
Run-Child 'probe-compile' "$jdk/bin/javac.exe" (@('--system',"$image/runtime",'--add-modules','com.datacube','-encoding','UTF-8')+$exports+@('-d',"$owned/classes",(Join-Path $PSScriptRoot 'G10LinkedRedisProbe.java'),(Join-Path $PSScriptRoot 'MigrationRuntimeDriverProbe.java')))
$runtime=@('--add-modules','com.datacube')+$exports+@('--add-opens=com.datacube/com.datacube.redis=ALL-UNNAMED','--enable-native-access=com.datacube','-cp',"$owned/classes")
Run-Child 'driver-discovery' "$image/runtime/bin/java.exe" ($runtime+@('MigrationRuntimeDriverProbe'))
Run-Child 'redis-linked' "$image/runtime/bin/java.exe" ($runtime+@('-Xmx64m','-Xss256k','G10LinkedRedisProbe'))
$driver=Get-Content -LiteralPath (Join-Path $out 'driver-discovery/stdout.log') -Raw
$redis=Get-Content -LiteralPath (Join-Path $out 'redis-linked/stdout.log') -Raw
$passed=$driver -match 'connectCalls=0' -and $redis -match 'G10_LINKED_REDIS=true' -and $redis -match 'socketsSettled=true; realServices=0'
Verify-Inputs
foreach($item in $probes){if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $item.path)).Hash -ne $item.sha256){throw 'Probe changed during run'}}
$baseline | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'inputs-after.json') -Encoding utf8
Copy-Item -LiteralPath (Join-Path $owned 'classes') -Destination (Join-Path $out 'compiled-probes') -Recurse
$artifacts=@('DataCube.exe','app/DataCube.cfg','runtime/lib/modules') | ForEach-Object {$name=$_;$files | Where-Object path -EQ $name}
@{utc=[datetime]::UtcNow.ToString('o');image=$image;owned=$owned;shortTemp=$short;head=$build.head;inputCount=$baseline.Count;testTypeCount=$types.Count;classLeaks=$leaks;fileLeaks=$fileLeaks;optionLeaks=$optionLeaks;artifacts=$artifacts;driverOutput=$driver;redisOutput=$redis;nativeDesktop=$false;realServices=0;allChildProcessesExited=$true;passed=$passed} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $out 'audit.json') -Encoding utf8
if(!$passed){throw 'Linked probe assertions failed'}
Write-Output 'G10_IMAGE_LINKED_AUDIT_PASSED'
