param(
 [Parameter(Mandatory)][ValidateSet('Compile','Prepare','Probe','Launch','Finish','Stop')][string]$Action,
 [string]$ImageRoot, [string]$Profile, [string]$Run,
 [string]$Jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8', [switch]$WrongHome
)
$ErrorActionPreference='Stop'
$tool=$PSScriptRoot
function Owned([string]$path) {
 $resolved=(Resolve-Path -LiteralPath $path).Path
 $name=Split-Path $resolved -Leaf
 if ($name -notmatch '^datacube-formal-([0-9a-f-]{36})$') { throw 'Not an owned UUID profile' }
 $id=[guid]::Parse($Matches[1]).ToString()
 if ([IO.File]::ReadAllText((Join-Path $resolved 'ownership.txt')).Trim() -ne $id) { throw 'Ownership mismatch' }
 return $resolved
}
function Manifest([string]$image) {
 foreach($relative in @('DataCube.exe','app/DataCube.cfg','runtime/lib/modules')) {
  $path=Join-Path $image $relative
  [pscustomobject]@{path=(Resolve-Path -LiteralPath $path).Path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
 }
}
function SaveNew($value,[string]$path) {
 $json=$value | ConvertTo-Json -Depth 8
 $stream=[IO.File]::Open($path,[IO.FileMode]::CreateNew)
 try {$bytes=[Text.Encoding]::UTF8.GetBytes($json);$stream.Write($bytes,0,$bytes.Length)} finally {$stream.Dispose()}
}
if ($Action -eq 'Compile') {
 New-Item -ItemType Directory -Force (Join-Path $tool 'classes') | Out-Null
 & "$Jdk/bin/javac.exe" -encoding UTF-8 -d "$tool/classes" "$tool/Gate.java" "$tool/Probe.java"
 if($LASTEXITCODE -ne 0){throw 'javac failed'}
 & "$Jdk/bin/jar.exe" --create --file "$tool/gate.jar" -C "$tool/classes" 'acceptance/Gate.class' -C "$tool/classes" 'acceptance/Gate$1.class'
 if($LASTEXITCODE -ne 0){throw 'jar failed'}
 & "$Jdk/bin/jar.exe" --create --file "$tool/probe.jar" -C "$tool/classes" 'acceptance/Probe.class'
 if($LASTEXITCODE -ne 0){throw 'probe jar failed'}
 Get-FileHash "$tool/gate.jar"
 return
}
if ($Action -eq 'Prepare') {
 $id=[guid]::NewGuid().ToString()
 $Profile=Join-Path ([IO.Path]::GetTempPath()) ('datacube-formal-'+$id)
 New-Item -ItemType Directory (Join-Path $Profile 'runs') | Out-Null
 [IO.File]::WriteAllText((Join-Path $Profile 'ownership.txt'),$id)
 SaveNew @{utc=[datetime]::UtcNow.ToString('o');image=(Resolve-Path $ImageRoot).Path;artifacts=@(Manifest $ImageRoot);gateSha=(Get-FileHash "$tool/gate.jar").Hash} (Join-Path $Profile 'prepared.json')
 $Profile
 return
}
$Profile=Owned $Profile
$prepared=Get-Content -LiteralPath (Join-Path $Profile 'prepared.json') -Raw | ConvertFrom-Json
$ImageRoot=$prepared.image
$current=@(Manifest $ImageRoot)
for($i=0;$i -lt 3;$i++){if($current[$i].sha256 -ne $prepared.artifacts[$i].sha256){throw 'Image SHA changed'}}
if((Get-FileHash "$tool/gate.jar").Hash -ne $prepared.gateSha){throw 'Gate SHA changed'}
if($Action -in @('Probe','Launch')) {
 $id=[IO.File]::ReadAllText((Join-Path $Profile 'ownership.txt')).Trim()
 $Run=Join-Path $Profile ('runs/'+[datetime]::UtcNow.ToString('yyyyMMddTHHmmssfffffff')+'-'+[guid]::NewGuid().ToString('N'))
 New-Item -ItemType Directory $Run | Out-Null
 $actualHome=$Profile
 if($WrongHome){if($Action -ne 'Probe'){throw 'WrongHome only allowed for non-GUI probe'};$actualHome=Join-Path $Profile 'wrong-home';New-Item -ItemType Directory -Force $actualHome | Out-Null}
 $options=@(('"-Xbootclasspath/a:'+$tool+'/gate.jar"'),'-Djava.system.class.loader=acceptance.Gate',('"-Duser.home='+$actualHome+'"'),('"-Dacceptance.home='+$Profile+'"'),('-Dacceptance.token='+$id),('"-Dacceptance.run='+$Run+'"')) -join ' '
 $exe=if($Action -eq 'Probe'){Join-Path $ImageRoot 'runtime/bin/java.exe'}else{Join-Path $ImageRoot 'DataCube.exe'}
 $psi=[Diagnostics.ProcessStartInfo]::new($exe)
 $psi.UseShellExecute=$false
 $psi.WorkingDirectory=$ImageRoot
 $psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
 foreach($key in @($psi.Environment.Keys)){if($key -match 'JAVA|JDK|GRADLE|DATACUBE|LIVE|ORACLE|PGHOST|PGPASSWORD|PGUSER|PGDATABASE|REDIS'){$psi.Environment.Remove($key)|Out-Null}}
 $psi.Environment['JAVA_TOOL_OPTIONS']=$options
 $psi.Environment['USERPROFILE']=$Profile;$psi.Environment['HOME']=$Profile;$psi.Environment['APPDATA']=$Profile;$psi.Environment['LOCALAPPDATA']=$Profile
 if($Action -eq 'Probe'){$psi.ArgumentList.Add('-cp');$psi.ArgumentList.Add((Join-Path $tool 'probe.jar'));$psi.ArgumentList.Add('acceptance.Probe')}
 SaveNew @{utc=[datetime]::UtcNow.ToString('o');action=$Action;exe=$exe;arguments=@($psi.ArgumentList);javaToolOptions=$options;home=$actualHome;artifacts=$current;gateSha=$prepared.gateSha} (Join-Path $Run 'launch.json')
 $p=[Diagnostics.Process]::Start($psi)
 # Dedicated observer remains alive until process exits; append-only originals.
 [IO.File]::WriteAllText((Join-Path $Run 'pid.txt'),[string]$p.Id)
 $outTask=$p.StandardOutput.ReadToEndAsync();$errTask=$p.StandardError.ReadToEndAsync()
 Write-Host "RUN=$Run PID=$($p.Id) ACTION=$Action"
 $p.WaitForExit()
 [IO.File]::WriteAllText((Join-Path $Run 'stdout.txt'),$outTask.GetAwaiter().GetResult())
 [IO.File]::WriteAllText((Join-Path $Run 'stderr.txt'),$errTask.GetAwaiter().GetResult())
 SaveNew @{utc=[datetime]::UtcNow.ToString('o');pid=$p.Id;exitCode=$p.ExitCode;artifactsAfter=@(Manifest $ImageRoot)} (Join-Path $Run 'exit.json')
 Write-Host "EXIT=$($p.ExitCode) RUN=$Run"
 return
}
$Run=(Resolve-Path -LiteralPath $Run).Path
if((Split-Path $Run -Parent) -ne (Join-Path $Profile 'runs')){throw 'Run outside owned profile'}
if($Action -eq 'Stop') {
 $targetPid=[int][IO.File]::ReadAllText((Join-Path $Run 'pid.txt'))
 $p=Get-Process -Id $targetPid -ErrorAction SilentlyContinue
 if($p){
  $record=Get-Content (Join-Path $Run 'launch.json') -Raw|ConvertFrom-Json
  if($p.Path -ne $record.exe){throw 'PID executable mismatch'}
  $metadata=Get-CimInstance Win32_Process -Filter "ProcessId = $targetPid"
  if($metadata.CreationDate.ToUniversalTime() -lt [datetime]::Parse($record.utc).ToUniversalTime().AddSeconds(-2)){throw 'PID creation mismatch'}
  SaveNew @{utc=[datetime]::UtcNow.ToString('o');pid=$targetPid;forced=$true;reason='Explicit stop; cannot count as normal exit'} (Join-Path $Run 'forced-stop.json')
  Stop-Process -Id $targetPid
 }
 return
}
if(-not(Test-Path (Join-Path $Run 'exit.json'))){throw 'Process observer has not recorded exit'}
$files=@(Get-ChildItem -LiteralPath $Run -File | ForEach-Object {[pscustomobject]@{path=$_.FullName;length=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
SaveNew @{utc=[datetime]::UtcNow.ToString('o');files=$files;artifacts=@(Manifest $ImageRoot);note='Does not infer GUI pass, screenshots, draft correctness, or normal close'} (Join-Path $Run 'finish.json')

