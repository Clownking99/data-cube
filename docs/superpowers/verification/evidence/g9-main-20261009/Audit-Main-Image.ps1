$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$out=Join-Path $PSScriptRoot '005-image-audit';if(Test-Path -LiteralPath $out){throw 'Fresh audit root required'}
New-Item -ItemType Directory -Path $out|Out-Null
$image=Join-Path $repo 'build/jpackage/DataCube';$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-g9-linked-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $owned 'classes'),(Join-Path $owned 'profile'),(Join-Path $owned 'temp')|Out-Null
Add-Type 'using System.Text;using System.Runtime.InteropServices;public static class G9AuditShortName {[DllImport("kernel32.dll",CharSet=CharSet.Unicode)]public static extern uint GetShortPathName(string path,StringBuilder result,uint size);}'
$buffer=[Text.StringBuilder]::new(4096);$length=[G9AuditShortName]::GetShortPathName((Join-Path $owned 'temp'),$buffer,4096);$short=$buffer.ToString()
if($length -eq 0 -or $length -ge 4096 -or $short -eq (Join-Path $owned 'temp')){throw 'Actual 8.3 temp required'}
function Invoke-Owned([string]$Name,[string]$Exe,[string[]]$Arguments){
 $psi=[Diagnostics.ProcessStartInfo]::new();$psi.FileName=$Exe;$psi.WorkingDirectory=$repo;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true;$psi.Environment.Clear()
 foreach($key in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')){$value=[Environment]::GetEnvironmentVariable($key);if($value){$psi.Environment[$key]=$value}}
 $psi.Environment['USERPROFILE']=Join-Path $owned 'profile';$psi.Environment['TEMP']=$short;$psi.Environment['TMP']=$short
 $psi.Environment['JAVA_TOOL_OPTIONS']='"-Duser.home='+$owned+'/profile" "-Djava.io.tmpdir='+$short+'"'
 foreach($arg in $Arguments){$psi.ArgumentList.Add($arg)}
 @{exe=$Exe;argv=$Arguments;profile=(Join-Path $owned 'profile');shortTemp=$short;environment='cleared; five named OS runtime variables and isolated home/temp only';head=(& git -C $repo rev-parse HEAD)}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $out "$Name-command.json") -Encoding utf8
 $p=[Diagnostics.Process]::new();$p.StartInfo=$psi
 try{if(!$p.Start()){throw 'Probe did not start'};$stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync();$p.WaitForExit();$code=$p.ExitCode
  [IO.File]::WriteAllText((Join-Path $out "$Name-stdout.log"),$stdout.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
  [IO.File]::WriteAllText((Join-Path $out "$Name-stderr.log"),$stderr.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
  @{exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $out "$Name-exit.json") -Encoding utf8
  if($code -ne 0){throw "$Name failed with child exit $code"}
 }finally{$p.Dispose()}
}
$probeFiles=@('G9RuntimeJdbcMocks.java','G9RuntimeTableExportProbe.java','MigrationRuntimeDriverProbe.java','XmlRuntimeExportProbe.java','XlsxRuntimeTextProbe.java','Audit-Xlsx-Text.py','Audit-Main-Image.ps1')
$probeBindings=foreach($name in $probeFiles){$path=Join-Path $PSScriptRoot $name;@{path=$name;length=(Get-Item -LiteralPath $path).Length;sha256=(Get-FileHash -LiteralPath $path).Hash}}
$probeBindings|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $out 'probe-inputs.json') -Encoding utf8
Invoke-Owned 'module-index' "$jdk/bin/jimage.exe" @('list',"$image/runtime/lib/modules")
$index=Get-Content -LiteralPath (Join-Path $out 'module-index-stdout.log')
$testTypes=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$baseline=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'input-freeze.json') -Raw|ConvertFrom-Json
foreach($item in $baseline.files){if($item.path -match '^test/.*\.java$'){[void]$testTypes.Add(($item.path -replace '^test/','' -replace '\.java$',''))}}
$leaks=@($index|Where-Object {$type=$_.Trim() -replace '\$.*$','' -replace '\.class$','';$testTypes.Contains($type) -or $type -match '^(org/junit/|org/mockito/|org/testfx/|acceptance/)' -or $type -match '(G9Runtime|DesktopProbe|RuntimeDriverProbe|ShutdownDesktopProbe)'})
$files=@(Get-ChildItem -LiteralPath $image -Recurse -File|ForEach-Object{@{path=$_.FullName.Substring($image.Length+1).Replace('\','/');length=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
$fileLeaks=@($files|Where-Object{$_.path -match '(?i)(\.datacube|(^|/)profiles?(/|$)|test-results|acceptance|Probe|fixture|isolation)'})
$cfg=Get-Content -LiteralPath "$image/app/DataCube.cfg" -Raw
$optionLeaks=$cfg -match '(?i)(user.home|headless|java.io.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic)'
$artifacts=@('DataCube.exe','app/DataCube.cfg','runtime/lib/modules')|ForEach-Object{$path=$_;$files|Where-Object{$_.path -eq $path}}
$files|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $out 'image-manifest.json') -Encoding utf8
Copy-Item -LiteralPath "$image/app/DataCube.cfg" -Destination (Join-Path $out 'DataCube.cfg')
if($leaks.Count -or $fileLeaks.Count -or $optionLeaks){@{classLeaks=$leaks;fileLeaks=$fileLeaks;optionLeaks=$optionLeaks;passed=$false}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $out 'audit.json');throw 'Image isolation audit failed'}
$exports=@('export','service','config','spi','spi.model','provider.postgres','provider.oracle','migration')|ForEach-Object{"--add-exports=com.datacube/com.datacube.$_=ALL-UNNAMED"}
$compile=@('--system',"$image/runtime",'--add-modules','com.datacube')+$exports+@('-d',"$owned/classes")+@($probeFiles|Where-Object{$_ -like '*.java'}|ForEach-Object{Join-Path $PSScriptRoot $_})
Invoke-Owned 'probe-compile' "$jdk/bin/javac.exe" $compile
$runtime=@('--add-modules','com.datacube')+$exports+@('--add-opens=com.datacube/com.datacube.service=ALL-UNNAMED','--enable-native-access=com.datacube','-cp',"$owned/classes")
Invoke-Owned 'driver-discovery' "$image/runtime/bin/java.exe" ($runtime+@('MigrationRuntimeDriverProbe'))
Invoke-Owned 'xml-runtime' "$image/runtime/bin/java.exe" ($runtime+@('XmlRuntimeExportProbe'))
$packages=Join-Path $out 'xlsx-packages'
Invoke-Owned 'xlsx-runtime' "$image/runtime/bin/java.exe" ($runtime+@('XlsxRuntimeTextProbe',$packages))
Invoke-Owned 'xlsx-reader' 'C:/Users/hetia/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' @((Join-Path $PSScriptRoot 'Audit-Xlsx-Text.py'),$packages,(Join-Path $out 'xlsx-independent-audit.json'))
Invoke-Owned 'g9-table-export' "$image/runtime/bin/java.exe" ($runtime+@('G9RuntimeTableExportProbe',(Join-Path $out 'g9-packages')))
$driver=Get-Content -LiteralPath (Join-Path $out 'driver-discovery-stdout.log') -Raw
$xml=Get-Content -LiteralPath (Join-Path $out 'xml-runtime-stdout.log') -Raw
$xlsx=Get-Content -LiteralPath (Join-Path $out 'xlsx-runtime-stdout.log') -Raw
$g9=Get-Content -LiteralPath (Join-Path $out 'g9-table-export-stdout.log') -Raw
$reader=Get-Content -LiteralPath (Join-Path $out 'xlsx-independent-audit.json') -Raw|ConvertFrom-Json
$passed=$driver -match 'connectCalls=0' -and $xml -match 'XML_RUNTIME_ROUND_TRIPS=7; INVALID_CHARACTER_REJECTED=true' -and $xlsx -match 'XLSX_RUNTIME_PACKAGES=20; INVALID_UTF16_REJECTED=6' -and $reader.passed -and $reader.cells -eq 40 -and $g9 -match 'G9_LINKED_SQL_XLSX_SUCCESS=2; FAILURE_TARGET_RETAINED=2; actualDriverConnectCalls=0'
foreach($item in $probeBindings){if((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $item.path)).Hash -ne $item.sha256){throw 'Probe changed during audit'}}
foreach($item in $baseline.files){$path=Join-Path $repo $item.path;if((Get-FileHash -LiteralPath $path).Hash -ne $item.sha256){throw 'Product input changed during audit'}}
@{utc=[datetime]::UtcNow.ToString('o');head=(& git -C $repo rev-parse HEAD);artifacts=$artifacts;classLeaks=$leaks;fileLeaks=$fileLeaks;optionLeaks=$optionLeaks;driverOutput=$driver;xmlOutput=$xml;xlsxOutput=$xlsx;independentReader=$reader.passed;readerCells=$reader.cells;g9Output=$g9;owned=$owned;shortTemp=$short;nativeUI=$false;realDatabaseConnections=0;realPgDump=$false;passed=$passed}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $out 'audit.json') -Encoding utf8
if(!$passed){throw 'Linked runtime acceptance failed'}
Write-Output 'P2_IMAGE_AUDIT_PASSED'
