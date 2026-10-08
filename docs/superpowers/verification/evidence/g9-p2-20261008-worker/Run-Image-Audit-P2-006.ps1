$ErrorActionPreference='Stop'
$out=Join-Path $PSScriptRoot '006-image-audit-wrapper';if(Test-Path -LiteralPath $out){throw 'Fresh wrapper root required'}
New-Item -ItemType Directory -Path $out|Out-Null
$psi=[Diagnostics.ProcessStartInfo]::new();$psi.FileName=(Get-Process -Id $PID).Path;$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true;$psi.Environment.Clear()
foreach($key in @('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT')){$value=[Environment]::GetEnvironmentVariable($key);if($value){$psi.Environment[$key]=$value}}
$owned=Join-Path ([IO.Path]::GetTempPath()) ('datacube-g9-audit-wrapper-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $owned 'profile'),(Join-Path $owned 'temp')|Out-Null
$psi.Environment['USERPROFILE']=Join-Path $owned 'profile';$psi.Environment['TEMP']=Join-Path $owned 'temp';$psi.Environment['TMP']=Join-Path $owned 'temp'
foreach($arg in @('-NoProfile','-NonInteractive','-File',(Join-Path $PSScriptRoot 'Audit-P2-Image-006.ps1'))){$psi.ArgumentList.Add($arg)}
@{exe=$psi.FileName;argv=@($psi.ArgumentList);environment='cleared; five named OS variables plus UUID-owned profile/temp; audit creates nested UUID profile/temp with actual 8.3 alias and each tool clears environment again';owned=$owned;launcherSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash;auditSha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'Audit-P2-Image-006.ps1')).Hash}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $out 'command.json') -Encoding utf8
$process=[Diagnostics.Process]::new();$process.StartInfo=$psi
try{
 if(!$process.Start()){throw 'Audit process did not start'}
 $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync();$process.WaitForExit();$code=$process.ExitCode
 [IO.File]::WriteAllText((Join-Path $out 'stdout.log'),$stdout.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
 [IO.File]::WriteAllText((Join-Path $out 'stderr.log'),$stderr.GetAwaiter().GetResult(),[Text.UTF8Encoding]::new($false))
 @{exitCode=$code;utc=[datetime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $out 'exit.json') -Encoding utf8
 Write-Output "Image audit child exit: $code"
}finally{$process.Dispose()}
exit $code
