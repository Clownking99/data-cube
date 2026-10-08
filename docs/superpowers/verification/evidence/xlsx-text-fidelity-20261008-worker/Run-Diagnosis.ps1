$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$run=Join-Path $PSScriptRoot '002-baseline-source-diagnosis'
if(Test-Path -LiteralPath $run){throw 'Evidence run must be new'}
$owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-xlsx-diagnosis-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run,(Join-Path $owned 'profile'),(Join-Path $owned 'temp'),(Join-Path $owned 'classes') | Out-Null
Add-Type 'using System.Text;using System.Runtime.InteropServices;public static class XlsxShortName {[DllImport("kernel32.dll",CharSet=CharSet.Unicode)]public static extern uint GetShortPathName(string path,StringBuilder result,uint size);}'
$buffer=[Text.StringBuilder]::new(4096)
$length=[XlsxShortName]::GetShortPathName((Join-Path $owned 'temp'),$buffer,4096)
$short=$buffer.ToString()
if($length -eq 0 -or $length -ge 4096 -or $short -eq (Join-Path $owned 'temp')){throw 'Actual owned 8.3 temporary alias required'}
$saved=@{}
foreach($item in @(Get-ChildItem Env:)){
 if($item.Name -match 'JAVA|JDK|GRADLE|DATACUBE|LIVE|ORACLE|PGHOST|PGPASSWORD|PGUSER|PGDATABASE|REDIS'){
  $saved[$item.Name]=$item.Value;Remove-Item -LiteralPath ('Env:'+$item.Name)
 }
}
try{
 $env:JAVA_TOOL_OPTIONS='"-Duser.home='+$owned+'/profile" "-Djava.io.tmpdir='+$short+'"'
 $jdk='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8/bin'
  $mirror=Join-Path $owned 'source'
 foreach($package in @('export','spi/model','sqleditor/result')){
  $destination=Join-Path $mirror ('com/datacube/'+$package)
  New-Item -ItemType Directory -Path $destination -Force | Out-Null
  Get-ChildItem -LiteralPath (Join-Path $root ('src/com/datacube/'+$package)) -Filter '*.java' -File | Copy-Item -Destination $destination
 }
 $spi=Join-Path $mirror 'com/datacube/spi'
 Get-ChildItem -LiteralPath (Join-Path $root 'src/com/datacube/spi') -Filter '*.java' -File | Copy-Item -Destination $spi
 Copy-Item -LiteralPath (Join-Path $root 'src/com/datacube/sqleditor/InsertSqlGenerator.java') -Destination (Join-Path $mirror 'com/datacube/sqleditor')
 $compile=@('-encoding','UTF-8','-sourcepath',$mirror,'-d',(Join-Path $owned 'classes'),(Join-Path $PSScriptRoot 'XlsxTextDiagnosis.java'))
 $execute=@('-cp',(Join-Path $owned 'classes'),'XlsxTextDiagnosis',$run,(Join-Path $owned 'temp'))
 @{utc=[datetime]::UtcNow.ToString('o');head=(git -C $root rev-parse HEAD);root=$root;owned=$owned;shortTmp=$short;compile=$compile;execute=$execute;javaToolOptions=$env:JAVA_TOOL_OPTIONS;noLiveEnvironment=$true}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $run 'command.json') -Encoding utf8
 & (Join-Path $jdk 'javac.exe') @compile 2>&1 | Tee-Object -FilePath (Join-Path $run 'compile.log')
 $compileCode=$LASTEXITCODE
 if($compileCode -eq 0){& (Join-Path $jdk 'java.exe') @execute 2>&1 | Tee-Object -FilePath (Join-Path $run 'diagnosis.log');$runCode=$LASTEXITCODE}else{$runCode=$null}
 @{compileExit=$compileCode;runExit=$runCode;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $run 'exit.json') -Encoding utf8
 $original=Join-Path $run 'source-at-diagnosis'
 New-Item -ItemType Directory -Path $original | Out-Null
 foreach($path in @('src/com/datacube/export/XlsxWriter.java','src/com/datacube/export/QueryResultFileWriter.java','src/com/datacube/export/SafeResultFilePublisher.java','src/com/datacube/export/TableExporter.java','test/com/datacube/export/XlsxTestDocuments.java')){Copy-Item -LiteralPath (Join-Path $root $path) -Destination (Join-Path $original ([IO.Path]::GetFileName($path)))}
 Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'XlsxTextDiagnosis.java'),$PSCommandPath -Destination $original
 Get-ChildItem -LiteralPath $original -File | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'source-sha256.json') -Encoding utf8
}finally{
 Remove-Item -LiteralPath Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
 foreach($key in $saved.Keys){Set-Item -LiteralPath ('Env:'+$key) -Value $saved[$key]}
}
if($compileCode -ne 0){exit $compileCode};exit $runCode


