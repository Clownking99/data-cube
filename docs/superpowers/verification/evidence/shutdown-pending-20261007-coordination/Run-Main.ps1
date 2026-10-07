param([Parameter(Mandatory)][string]$Name,[Parameter(Mandatory)][string[]]$Tasks,[switch]$Image)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$run=Join-Path $PSScriptRoot $Name
if(Test-Path -LiteralPath $run){throw 'Evidence run must be new'}
$owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-shutdown-pending-main-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run,(Join-Path $owned 'profile'),(Join-Path $owned 'temp') | Out-Null
Add-Type 'using System.Text;using System.Runtime.InteropServices;public static class MainShortName {[DllImport("kernel32.dll",CharSet=CharSet.Unicode)]public static extern uint GetShortPathName(string path,StringBuilder result,uint size);}'
$buffer=[Text.StringBuilder]::new(4096)
$length=[MainShortName]::GetShortPathName((Join-Path $owned 'temp'),$buffer,4096)
$short=$buffer.ToString()
if($length -eq 0 -or $length -ge 4096 -or $short -eq (Join-Path $owned 'temp')){throw 'Actual owned 8.3 temporary alias required'}
$saved=@{}
foreach($item in @(Get-ChildItem Env:)){
 if($item.Name -match 'JAVA|JDK|GRADLE|DATACUBE|LIVE|ORACLE|PGHOST|PGPASSWORD|PGUSER|PGDATABASE|REDIS'){
  $saved[$item.Name]=$item.Value
  Remove-Item -LiteralPath ('Env:'+$item.Name)
 }
}
$code=1
try{
 $env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
 $env:GRADLE_USER_HOME='C:/Users/hetia/.gradle'
 $argv=@($Tasks)+@('--offline','--no-daemon','--console=plain',('-Dorg.gradle.java.home='+$env:JAVA_HOME))
 if($Image){
  # Only Gradle uses this fresh build home. Packaging subprocesses inherit no test JVM flags.
  $env:JAVA_OPTS='"-Duser.home='+$owned+'/profile"'
  $argv+=('-Dorg.gradle.jvmargs=-Xmx512m -Duser.home='+$owned+'/profile')
 }else{
  $env:JAVA_TOOL_OPTIONS='"-Duser.home='+$owned+'/profile" "-Djava.io.tmpdir='+$short+'" -Djava.awt.headless=false'
 }
 $head=git -C $root rev-parse HEAD
 if($LASTEXITCODE -ne 0){throw 'HEAD query failed'}
 @{utc=[datetime]::UtcNow.ToString('o');head=$head;root=$root;owned=$owned;shortTmp=$short;argv=$argv;image=$Image.IsPresent;javaOpts=$env:JAVA_OPTS;javaToolOptions=$env:JAVA_TOOL_OPTIONS;noLiveEnvironment=$true}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $run 'command.json') -Encoding utf8
 Push-Location $root
 try{& ./gradlew.bat @argv 2>&1 | Tee-Object -FilePath (Join-Path $run 'gradle.log');$code=$LASTEXITCODE}finally{Pop-Location}
 @{exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $run 'exit.json') -Encoding utf8
 # Only snapshot the reports for tasks that actually ran in this invocation.
 $log=Get-Content -LiteralPath (Join-Path $run 'gradle.log') -Raw
 $reports=@()
 if(!$Image){
  if($log -match '(?m)^> Task :test(?: FAILED)?\r?$'){
   $reports+=if(($Tasks -contains '-p') -and ($Tasks -contains 'buildSrc')){'buildSrc/build/test-results/test'}else{'build/test-results/test'}
  }
  if($log -match '(?m)^> Task :buildSrc:test(?: FAILED)?\r?$'){$reports+='buildSrc/build/test-results/test'}
 }
 foreach($report in @($reports | Select-Object -Unique)){
  $path=Join-Path $root $report
  if(Test-Path -LiteralPath $path){
   $xmlOut=Join-Path $run ($report.Replace('/','-'))
   New-Item -ItemType Directory -Path $xmlOut | Out-Null
   Get-ChildItem -LiteralPath $path -Filter 'TEST-*.xml' -File | Copy-Item -Destination $xmlOut
  }
 }
}finally{
 foreach($key in @('JAVA_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS','JAVA_OPTS')){Remove-Item -LiteralPath ('Env:'+$key) -ErrorAction SilentlyContinue}
 foreach($key in $saved.Keys){Set-Item -LiteralPath ('Env:'+$key) -Value $saved[$key]}
}
exit $code


