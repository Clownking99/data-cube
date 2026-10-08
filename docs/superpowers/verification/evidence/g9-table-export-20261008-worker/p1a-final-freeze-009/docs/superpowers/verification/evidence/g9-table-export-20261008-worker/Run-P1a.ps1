param([Parameter(Mandatory)][string]$Name,[ValidateSet('red','green')][string]$Mode='green')
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$runRoot=Join-Path $PSScriptRoot $Name
if(Test-Path -LiteralPath $runRoot){throw 'Evidence run must be new'}
$ownedRoot=Join-Path ([IO.Path]::GetTempPath()) ('datacube-g9a-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot,(Join-Path $ownedRoot 'profile'),(Join-Path $ownedRoot 'temp') | Out-Null
Add-Type 'using System.Text;using System.Runtime.InteropServices;public static class G9aShortName {[DllImport("kernel32.dll",CharSet=CharSet.Unicode)]public static extern uint GetShortPathName(string path,StringBuilder result,uint size);}'
$shortBuffer=[Text.StringBuilder]::new(4096)
$shortLength=[G9aShortName]::GetShortPathName((Join-Path $ownedRoot 'temp'),$shortBuffer,4096)
$shortTmp=$shortBuffer.ToString()
if($shortLength -eq 0 -or $shortLength -ge 4096 -or $shortTmp -eq (Join-Path $ownedRoot 'temp')){throw 'Actual owned 8.3 temporary alias required'}
$tests=if($Mode -eq 'red'){@('com.datacube.export.TableExporterBaselineRedTest','com.datacube.export.ResultExportOperationTest')}else{@('com.datacube.export.*','com.datacube.fx.ExportDialog*Test','com.datacube.fx.SqlResult*Export*Test','com.datacube.fx.task.FxTaskRunnerTest','com.datacube.fx.task.FxTaskScopeTest')}
$argv=@('cleanTest','test','--offline','--no-daemon','--console=plain','-Dorg.gradle.java.home=D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8')
foreach($test in $tests){$argv+=@('--tests',$test)}
$psi=[Diagnostics.ProcessStartInfo]::new()
$psi.FileName=(Get-Process -Id $PID).Path
$psi.WorkingDirectory=$repoRoot
$psi.UseShellExecute=$false
$psi.CreateNoWindow=$true
$psi.RedirectStandardOutput=$true
$psi.RedirectStandardError=$true
$psi.Environment.Clear()
# Copy only named runtime necessities, never enumerate or collect credential/live variables.
foreach($key in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')){
 $value=[Environment]::GetEnvironmentVariable($key)
 if($value){$psi.Environment[$key]=$value}
}
$psi.Environment['JAVA_HOME']='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$psi.Environment['GRADLE_USER_HOME']='C:/Users/hetia/.gradle'
$psi.Environment['USERPROFILE']=Join-Path $ownedRoot 'profile'
$psi.Environment['TEMP']=$shortTmp
$psi.Environment['TMP']=$shortTmp
$psi.Environment['JAVA_TOOL_OPTIONS']='"-Duser.home='+$ownedRoot+'/profile" "-Djava.io.tmpdir='+$shortTmp+'" -Djava.awt.headless=false'
$psi.ArgumentList.Add('-NoProfile')
$psi.ArgumentList.Add('-NonInteractive')
$psi.ArgumentList.Add('-Command')
$psi.ArgumentList.Add('& ./gradlew.bat '+(($argv|ForEach-Object {"'"+$_+"'"}) -join ' '))
@{utc=[datetime]::UtcNow.ToString('o');head=(& git -C $repoRoot rev-parse HEAD);root=$repoRoot;owned=$ownedRoot;shortTmp=$shortTmp;argv=$argv;mode=$Mode;environment='cleared, named OS runtime essentials and isolated profile/temp only'}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $runRoot 'command.json') -Encoding utf8
$process=[Diagnostics.Process]::new()
$process.StartInfo=$psi
try{
 if(!$process.Start()){throw 'Gradle process did not start'}
 $stdout=$process.StandardOutput.ReadToEndAsync()
 $stderr=$process.StandardError.ReadToEndAsync()
 $process.WaitForExit()
 $code=$process.ExitCode
 $stdoutText=$stdout.GetAwaiter().GetResult()
 $stderrText=$stderr.GetAwaiter().GetResult()
 [IO.File]::WriteAllText((Join-Path $runRoot 'stdout.log'),$stdoutText,[Text.UTF8Encoding]::new($false))
 [IO.File]::WriteAllText((Join-Path $runRoot 'stderr.log'),$stderrText,[Text.UTF8Encoding]::new($false))
 @{exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $runRoot 'exit.json') -Encoding utf8
 if($stdoutText -match '(?m)^> Task :test(?: FAILED)?\r?$'){
  $reportPath=Join-Path $repoRoot 'build/test-results/test'
  if(Test-Path -LiteralPath $reportPath){
   $xmlOut=Join-Path $runRoot 'xml'
   New-Item -ItemType Directory -Path $xmlOut|Out-Null
   Get-ChildItem -LiteralPath $reportPath -Filter 'TEST-*.xml' -File|Copy-Item -Destination $xmlOut
  }
 }
 Write-Output $stdoutText
 Write-Output $stderrText
}finally{$process.Dispose()}
exit $code
