$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$out=Join-Path $PSScriptRoot '008-image'
if(Test-Path $out){throw 'Image evidence output must be new'}
New-Item -ItemType Directory $out|Out-Null
$owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-ci-build-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $owned|Out-Null
$saved=@{}
foreach($item in @(Get-ChildItem Env:)){if($item.Name -match 'JAVA|JDK|GRADLE|DATACUBE|LIVE|ORACLE|PGHOST|PGPASSWORD|PGUSER|PGDATABASE|REDIS'){$saved[$item.Name]=$item.Value;Remove-Item -LiteralPath ('Env:'+$item.Name)}}
try{
 $env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
 $env:GRADLE_USER_HOME='C:/Users/hetia/.gradle'
 # JAVA_OPTS is consumed by gradlew for Gradle only, not JAVA_TOOL_OPTIONS recognized by child JVMs.
 $env:JAVA_OPTS='"-Duser.home='+$owned+'"'
 foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')){if(Test-Path ('Env:'+$key)){throw 'Inherited JVM injection present'}}
 $argv=@('jpackageImage','--offline','--no-daemon','--console=plain',('-Dorg.gradle.java.home='+$env:JAVA_HOME))
 @{utc=[datetime]::UtcNow.ToString('o');source=(git -C $root rev-parse HEAD);argv=$argv;gradleOnlyJavaOpts=$env:JAVA_OPTS;javaToolOptionsAbsent=$true;headlessAbsent=$true;owned=$owned}|ConvertTo-Json -Depth 4|Set-Content -Encoding utf8 (Join-Path $out 'command.json')
 Push-Location $root
 try{& ./gradlew.bat @argv 2>&1|Tee-Object -FilePath (Join-Path $out 'gradle.log');$code=$LASTEXITCODE}finally{Pop-Location}
 @{exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $out 'exit.json')
}finally{
 foreach($key in @('JAVA_HOME','GRADLE_USER_HOME','JAVA_OPTS')){Remove-Item -LiteralPath ('Env:'+$key) -ErrorAction SilentlyContinue}
 foreach($key in $saved.Keys){Set-Item -LiteralPath ('Env:'+$key) -Value $saved[$key]}
}
exit $code
