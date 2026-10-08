param([Parameter(Mandatory)][string]$Name,[ValidateSet('targeted','full','buildsrc','image')][string]$Mode)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$out=Join-Path $PSScriptRoot $Name
if(Test-Path -LiteralPath $out){throw 'Fresh evidence root required'}
New-Item -ItemType Directory -Path $out|Out-Null
$jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-g9-p3-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $owned 'profile'),(Join-Path $owned 'temp')|Out-Null
Add-Type 'using System.Text;using System.Runtime.InteropServices;public static class G9P3ShortName {[DllImport("kernel32.dll",CharSet=CharSet.Unicode)]public static extern uint GetShortPathName(string path,StringBuilder result,uint size);}'
$buffer=[Text.StringBuilder]::new(4096)
$length=[G9P3ShortName]::GetShortPathName((Join-Path $owned 'temp'),$buffer,4096)
$short=$buffer.ToString()
if($length -eq 0 -or $length -ge 4096 -or $short -eq (Join-Path $owned 'temp')){throw 'Actual 8.3 temp required'}
$baseline=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'input-freeze.json') -Raw|ConvertFrom-Json
$binding=foreach($item in $baseline.files){
 $file=Join-Path $repo $item.path
 $hash=(Get-FileHash -LiteralPath $file).Hash
 if($hash -ne $item.sha256 -or (Get-Item -LiteralPath $file).Length -ne $item.length){throw "Changed frozen input: $($item.path)"}
 @{path=$item.path;length=$item.length;sha256=$hash}
}
@{head=(& git -C $repo rev-parse HEAD);files=@($binding)}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $out 'inputs-before.json') -Encoding utf8
$tasks=switch($Mode){'targeted'{@('cleanTest','test','--rerun-tasks')};'full'{@('clean','test','--rerun-tasks')};'buildsrc'{@(':buildSrc:test','--rerun-tasks')};'image'{@('jpackageImage','--rerun-tasks')}}
$argv=@($tasks)+@('--offline','--no-daemon','--console=plain',"-Dorg.gradle.java.home=$jdk")
if($Mode -eq 'targeted'){
 foreach($filter in @('com.datacube.export.*','com.datacube.fx.ExportDialog*Test','com.datacube.fx.SqlResult*Export*Test','com.datacube.fx.task.FxTaskRunnerTest','com.datacube.fx.task.FxTaskScopeTest','com.datacube.fx.AppShell*ShutdownTest','com.datacube.fx.AsyncShutdownCoordinatorTest','com.datacube.fx.BestEffortCloseSequenceTest','com.datacube.fx.DataCubeFxShutdownContractTest','com.datacube.fx.WindowShutdownControllerTest','com.datacube.service.ConnectionManagerDedicatedSessionTest','com.datacube.service.ConnectionManagerExport*Test','com.datacube.service.JdbcEditorSession*Test','com.datacube.spi.SqlExecutionControlTest','com.datacube.provider.*','com.datacube.migration.*','com.datacube.fx.Migration*Test')){$argv+=@('--tests',$filter)}
}
$psi=[Diagnostics.ProcessStartInfo]::new()
$psi.FileName=(Get-Process -Id $PID).Path;$psi.WorkingDirectory=$repo;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true
$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true;$psi.Environment.Clear()
foreach($key in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')){$value=[Environment]::GetEnvironmentVariable($key);if($value){$psi.Environment[$key]=$value}}
$psi.Environment['JAVA_HOME']=$jdk;$psi.Environment['GRADLE_USER_HOME']='C:/Users/hetia/.gradle'
$psi.Environment['USERPROFILE']=Join-Path $owned 'profile';$psi.Environment['TEMP']=$short;$psi.Environment['TMP']=$short
$psi.Environment['JAVA_TOOL_OPTIONS']='"-Duser.home='+$owned+'/profile" "-Djava.io.tmpdir='+$short+'" -Djava.awt.headless=false'
foreach($arg in @('-NoProfile','-NonInteractive','-Command')){$psi.ArgumentList.Add($arg)}
$psi.ArgumentList.Add('& ./gradlew.bat '+(($argv|ForEach-Object{"'"+$_+"'"}) -join ' ')+'; exit $LASTEXITCODE')
@{utc=[datetime]::UtcNow.ToString('o');head=(& git -C $repo rev-parse HEAD);branch=(& git -C $repo branch --show-current);mode=$Mode;argv=$argv;owned=$owned;profile=(Join-Path $owned 'profile');temp=(Join-Path $owned 'temp');shortTemp=$short;environmentPolicy='Clear all; copy only five named OS runtime variables; explicit existing JDK/cache and UUID-owned home/temp. No live credentials.';childCommand=$psi.ArgumentList[3]}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $out 'command.json') -Encoding utf8
$process=[Diagnostics.Process]::new();$process.StartInfo=$psi
try{
 if(!$process.Start()){throw 'Process did not start'}
 $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$process.WaitForExit();$code=$process.ExitCode
 $output=$stdout.GetAwaiter().GetResult();$errors=$stderr.GetAwaiter().GetResult()
 [IO.File]::WriteAllText((Join-Path $out 'stdout.log'),$output,[Text.UTF8Encoding]::new($false))
 [IO.File]::WriteAllText((Join-Path $out 'stderr.log'),$errors,[Text.UTF8Encoding]::new($false))
 @{exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $out 'exit.json') -Encoding utf8
 $report=if($Mode -eq 'buildsrc'){Join-Path $repo 'buildSrc/build/test-results/test'}else{Join-Path $repo 'build/test-results/test'}
 if($Mode -ne 'image' -and (Test-Path -LiteralPath $report)){
  $xmlOut=Join-Path $out 'xml';New-Item -ItemType Directory -Path $xmlOut|Out-Null
  Get-ChildItem -LiteralPath $report -Filter 'TEST-*.xml' -File|Copy-Item -Destination $xmlOut
  $cases=@();$skips=@();$failures=@();$suites=0
  foreach($xmlFile in Get-ChildItem -LiteralPath $xmlOut -Filter 'TEST-*.xml'){
   [xml]$xml=Get-Content -LiteralPath $xmlFile.FullName -Raw;$suites++
   foreach($case in @($xml.testsuite.testcase)){
    $cases+=@{class=$case.classname;name=$case.name}
    if($null -ne $case.SelectSingleNode('skipped')){$skips+=@{class=$case.classname;name=$case.name;reason=$case.skipped.message;text=$case.skipped.InnerText}}
    if($case.failure -or $case.error){$failures+=@{class=$case.classname;name=$case.name;failure=$case.failure.OuterXml;error=$case.error.OuterXml}}
   }
  }
  $totals=@{suites=$suites;tests=$cases.Count;failures=0;errors=0;skipped=$skips.Count;skips=$skips;failedCases=$failures}
  foreach($xmlFile in Get-ChildItem -LiteralPath $xmlOut -Filter 'TEST-*.xml'){[xml]$xml=Get-Content -LiteralPath $xmlFile.FullName -Raw;$totals.failures+=[int]$xml.testsuite.failures;$totals.errors+=[int]$xml.testsuite.errors}
  $totals|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $out 'test-summary.json') -Encoding utf8
 }
 $after=foreach($item in $baseline.files){$file=Join-Path $repo $item.path;$hash=(Get-FileHash -LiteralPath $file).Hash;if($hash -ne $item.sha256 -or (Get-Item -LiteralPath $file).Length -ne $item.length){throw "Changed input after run: $($item.path)"};@{path=$item.path;length=$item.length;sha256=$hash}}
 @{head=(& git -C $repo rev-parse HEAD);files=@($after)}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $out 'inputs-after.json') -Encoding utf8
 Write-Output $output;Write-Output $errors
}finally{$process.Dispose()}
exit $code
