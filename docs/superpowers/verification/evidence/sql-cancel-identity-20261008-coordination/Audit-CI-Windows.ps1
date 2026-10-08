param([Parameter(Mandatory)][string]$Commit,[Parameter(Mandatory)][string]$ReceiptDirectory)
$ErrorActionPreference='Stop'
$root='D:/Projects/朝花夕拾'
$dir=[IO.Path]::GetFullPath($ReceiptDirectory,$root)
if((Split-Path -Parent $dir) -ne (Join-Path $root 'build') -or (Split-Path -Leaf $dir) -notmatch '^owned-ci-[a-f0-9]{32}$'){throw 'Receipt scope'}
$run=Get-Content -LiteralPath (Join-Path $dir 'workflow-complete.json') -Raw|ConvertFrom-Json
$detected=Get-Content -LiteralPath (Join-Path $dir 'workflow-detected.json') -Raw|ConvertFrom-Json
if($run.headSha -ne $Commit -or $run.status -ne 'completed' -or $run.conclusion -ne 'success' -or $detected.headSha -ne $Commit){throw 'Not an exact-SHA success'}
$windows=@($run.jobs|Where-Object name -eq 'Test (windows-latest)')
if($windows.Count -ne 1 -or $windows[0].conclusion -ne 'success'){throw 'Windows success missing'}
$logPath=Join-Path $dir 'windows-job.log'
if(Test-Path -LiteralPath $logPath){throw 'Preserve existing raw log'}
$oldHttp=$env:HTTP_PROXY;$oldHttps=$env:HTTPS_PROXY
try{
 $env:HTTP_PROXY='http://127.0.0.1:7897';$env:HTTPS_PROXY=$env:HTTP_PROXY
 & 'C:/Program Files/GitHub CLI/gh.exe' run view $detected.databaseId --repo Clownking99/data-cube --job $windows[0].databaseId --log | Set-Content -LiteralPath $logPath -Encoding utf8
 if($LASTEXITCODE -ne 0){throw 'Windows raw log read failed'}
}finally{$env:HTTP_PROXY=$oldHttp;$env:HTTPS_PROXY=$oldHttps}
$lines=@(Get-Content -LiteralPath $logPath)
$actual=@{}
foreach($task in @(':buildSrc:test',':test',':jlink')){
 $matches=@($lines|Where-Object {$_ -match (' > Task '+[regex]::Escape($task)+'\s*$')})
 if($matches.Count -ne 1){throw ('Expected one actually executed task '+$task)}
 $actual[$task]=$matches
}
$steps=@($windows[0].steps|Where-Object {$_.name -in @('Unit tests','Windows linked image')})
if($steps.Count -ne 2 -or @($steps|Where-Object conclusion -ne 'success').Count -ne 0){throw 'Required steps missing'}
@{utc=[datetime]::UtcNow.ToString('o');headSha=$Commit;runId=$detected.databaseId;jobId=$windows[0].databaseId;url=$run.url;passed=$true;logBytes=(Get-Item -LiteralPath $logPath).Length;logSha256=(Get-FileHash -LiteralPath $logPath).Hash;requiredSteps=$steps;actualTasks=$actual}|ConvertTo-Json -Depth 7|Set-Content -LiteralPath (Join-Path $dir 'windows-log-audit.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $dir 'windows-log-audit.json')
