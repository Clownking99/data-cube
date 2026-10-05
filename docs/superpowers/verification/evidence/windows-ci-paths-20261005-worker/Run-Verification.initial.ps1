param([Parameter(Mandatory)][string]$Name,[Parameter(Mandatory)][string[]]$Tasks)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$evidence=$PSScriptRoot
$owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-ci-paths-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force (Join-Path $owned 'profile'),(Join-Path $owned 'temp') | Out-Null
Add-Type 'using System.Text;using System.Runtime.InteropServices;public static class CiShortName {[DllImport("kernel32.dll",CharSet=CharSet.Unicode)]public static extern uint GetShortPathName(string path,StringBuilder result,uint size);}' -ErrorAction SilentlyContinue
$sb=[Text.StringBuilder]::new(4096);[CiShortName]::GetShortPathName((Join-Path $owned 'temp'),$sb,4096)|Out-Null
$short=$sb.ToString()
if(-not $short -or $short -eq (Join-Path $owned 'temp')){throw 'Actual short-name root required'}
$run=Join-Path $evidence $Name
if(Test-Path $run){throw 'Run must be new'}
New-Item -ItemType Directory $run|Out-Null
$saved=@{}
foreach($item in @(Get-ChildItem Env:)){if($item.Name -match 'JAVA|JDK|GRADLE|DATACUBE|LIVE|ORACLE|PGHOST|PGPASSWORD|PGUSER|PGDATABASE|REDIS'){$saved[$item.Name]=$item.Value;Remove-Item -LiteralPath ('Env:'+ $item.Name)}}
try{
 $env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
 $env:GRADLE_USER_HOME='C:/Users/hetia/.gradle'
 $env:JAVA_TOOL_OPTIONS='"-Duser.home='+$owned+'/profile" "-Djava.io.tmpdir='+$short+'" -Djava.awt.headless=false'
 $argv=@($Tasks)+@('--offline','--no-daemon','--console=plain',('-Dorg.gradle.java.home='+$env:JAVA_HOME))
 @{utc=[datetime]::UtcNow.ToString('o');owned=$owned;shortTmp=$short;javaToolOptions=$env:JAVA_TOOL_OPTIONS;argv=$argv;source=(git -C $root rev-parse HEAD)}|ConvertTo-Json -Depth 4|Set-Content -Encoding utf8 (Join-Path $run 'command.json')
 Push-Location $root
 try{& ./gradlew.bat @argv 2>&1 | Tee-Object -FilePath (Join-Path $run 'gradle.log');$code=$LASTEXITCODE}finally{Pop-Location}
 @{exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $run 'exit.json')
 foreach($report in @('build/test-results/test','buildSrc/build/test-results/test')){if(Test-Path (Join-Path $root $report)){Copy-Item -LiteralPath (Join-Path $root $report) -Destination (Join-Path $run ($report.Replace('/','-'))) -Recurse}}
 Write-Output ('RUN_EXIT='+$code+' EVIDENCE='+$run)
}finally{
 foreach($key in @('JAVA_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')){Remove-Item -LiteralPath ('Env:'+$key) -ErrorAction SilentlyContinue}
 foreach($key in $saved.Keys){Set-Item -LiteralPath ('Env:'+$key) -Value $saved[$key]}
}
