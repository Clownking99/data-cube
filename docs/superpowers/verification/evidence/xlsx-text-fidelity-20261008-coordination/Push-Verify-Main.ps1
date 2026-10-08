param([Parameter(Mandatory)][string]$Commit,[Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath 'D:/Projects/朝花夕拾'
$receiptDir=[IO.Path]::GetFullPath($OutputDirectory,(Get-Location).Path)
$expectedRoot=Join-Path (Get-Location) 'build'
if((Split-Path -Parent $receiptDir) -ne $expectedRoot -or (Split-Path -Leaf $receiptDir) -notmatch '^owned-ci-[a-f0-9]{32}$'){throw 'Receipts require dedicated build/owned-ci-UUID directory'}
if(Test-Path -LiteralPath $receiptDir){throw 'CI receipt directory must be new'}
New-Item -ItemType Directory -Path $receiptDir|Out-Null
if((git branch --show-current) -ne 'main' -or (git rev-parse HEAD) -ne $Commit){throw 'Unexpected main head'}
if(@(git status --porcelain -- . ':(exclude).testagent' ':(exclude).testagent/**').Count -ne 0){throw 'Main must be clean'}
if((git remote get-url origin) -ne 'https://github.com/Clownking99/data-cube.git'){throw 'Unexpected remote target'}
@{utc=[datetime]::UtcNow.ToString('o');commit=$Commit;operation='push main only; preserve v3.2.9';proxy='http://127.0.0.1:7897'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $receiptDir 'intent.json') -Encoding utf8
git -c http.proxy=http://127.0.0.1:7897 push origin 'main:refs/heads/main' 2>&1|Tee-Object -FilePath (Join-Path $receiptDir 'push.log')
if($LASTEXITCODE -ne 0){throw 'Main push failed; no force attempted'}
$pushedRefs=@(git -c http.proxy=http://127.0.0.1:7897 ls-remote origin 'refs/heads/main' 'refs/tags/v3.2.9' 'refs/tags/v3.2.9^{}')
if($LASTEXITCODE -ne 0){throw 'Post-push remote refs read failed'}
$pushedRefs|Set-Content -LiteralPath (Join-Path $receiptDir 'remote-refs-after-push.txt') -Encoding utf8
$pushedMap=@{}
foreach($line in $pushedRefs){$parts=$line -split '\s+',2;if($parts.Count -ne 2){throw 'Invalid post-push ref line'};$pushedMap[$parts[1]]=$parts[0]}
if($pushedMap['refs/heads/main'] -ne $Commit -or $pushedMap['refs/tags/v3.2.9'] -ne 'cfe83d64d313ad343fe504e8136299d8a7181fe8' -or $pushedMap['refs/tags/v3.2.9^{}'] -ne '0f6ba02656fcf3752b180514c72e79151a3320df'){throw 'Post-push main/tag mismatch'}
@{utc=[datetime]::UtcNow.ToString('o');commit=$Commit;remoteMainMatches=$true;preservedTag='v3.2.9';ci='pending; not inferred from push'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $receiptDir 'push-verified.json') -Encoding utf8
$gh='C:/Program Files/GitHub CLI/gh.exe'
$oldHttp=$env:HTTP_PROXY;$oldHttps=$env:HTTPS_PROXY
try{
 $env:HTTP_PROXY='http://127.0.0.1:7897';$env:HTTPS_PROXY=$env:HTTP_PROXY
 $found=$null
 for($attempt=0;$attempt -lt 24;$attempt++){
  $raw=& $gh run list --repo Clownking99/data-cube --workflow verify.yml --commit $Commit --limit 5 --json databaseId,status,conclusion,headSha,url
  if($LASTEXITCODE -ne 0){throw 'CI list failed'}
  @{utc=[datetime]::UtcNow.ToString('o');attempt=$attempt+1;response=($raw -join "`n")}|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $receiptDir 'workflow-queries.jsonl') -Encoding utf8
  $found=@($raw|ConvertFrom-Json)|Where-Object {$_.headSha -eq $Commit}|Select-Object -First 1
  if($found){break};Start-Sleep -Seconds 10
 }
 if(!$found){throw 'No exact-commit Verify run within bounded wait'}
 $found|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $receiptDir 'workflow-detected.json') -Encoding utf8
 $prior='';$readFailures=0
 for($attempt=0;$attempt -lt 100;$attempt++){
  $raw=& $gh run view $found.databaseId --repo Clownking99/data-cube --json headSha,status,conclusion,jobs,url,workflowName,attempt
  if($LASTEXITCODE -ne 0){
   $readFailures++
   ('Read-only CI query failed at '+[datetime]::UtcNow.ToString('o'))|Add-Content -LiteralPath (Join-Path $receiptDir 'read-errors.log')
   if($readFailures -ge 3){throw 'Three consecutive CI reads failed'}
   Start-Sleep -Seconds 15;continue
  }
  $readFailures=0
  $run=$raw|ConvertFrom-Json
  if($run.headSha -ne $Commit){throw 'CI source mismatch'}
  $windowsProgress=@($run.jobs|Where-Object name -eq 'Test (windows-latest)'|ForEach-Object {$_.steps|Where-Object name -in @('Unit tests','Windows linked image')|ForEach-Object {$_.name+'='+$_.status+'/'+$_.conclusion}})
  $progress=$run.status+': '+(($run.jobs|ForEach-Object{$_.name+'='+$_.status+'/'+$_.conclusion}) -join '; ')+ ' | '+($windowsProgress -join '; ')
  if($progress -ne $prior){
   $progress;$prior=$progress
   @{utc=[datetime]::UtcNow.ToString('o');snapshot=$run}|ConvertTo-Json -Depth 12 -Compress|Add-Content -LiteralPath (Join-Path $receiptDir 'workflow-progress.jsonl') -Encoding utf8
  }
  if($run.status -eq 'completed'){
   $raw|Set-Content -LiteralPath (Join-Path $receiptDir 'workflow-complete.json') -Encoding utf8
   if($run.conclusion -ne 'success'){throw ('Verify concluded '+$run.conclusion)}
   if(@($run.jobs).Count -ne 4 -or @($run.jobs|Where-Object {$_.conclusion -ne 'success'}).Count -ne 0){throw 'Four successful jobs required'}
   $windows=@($run.jobs|Where-Object name -eq 'Test (windows-latest)')
   if($windows.Count -ne 1){throw 'Windows job missing'}
   foreach($stepName in @('Unit tests','Windows linked image')){
    $steps=@($windows[0].steps|Where-Object name -eq $stepName)
    if($steps.Count -ne 1 -or $steps[0].conclusion -ne 'success'){throw ('Required Windows step did not pass: '+$stepName)}
   }
   $refs=@(git -c http.proxy=http://127.0.0.1:7897 ls-remote origin 'refs/heads/main' 'refs/tags/v3.2.9' 'refs/tags/v3.2.9^{}')
   if($LASTEXITCODE -ne 0){throw 'Remote refs read failed'}
   $refs|Set-Content -LiteralPath (Join-Path $receiptDir 'remote-refs.txt') -Encoding utf8
   $refMap=@{}
   foreach($line in $refs){$parts=$line -split '\s+',2;if($parts.Count -ne 2){throw 'Invalid ref line'};$refMap[$parts[1]]=$parts[0]}
   if($refMap['refs/heads/main'] -ne $Commit){throw 'Remote main mismatch'}
   if($refMap['refs/tags/v3.2.9'] -ne 'cfe83d64d313ad343fe504e8136299d8a7181fe8' -or $refMap['refs/tags/v3.2.9^{}'] -ne '0f6ba02656fcf3752b180514c72e79151a3320df'){throw 'Published tag changed'}
   if((git rev-parse HEAD) -ne $Commit -or @(git status --porcelain -- . ':(exclude).testagent' ':(exclude).testagent/**').Count -ne 0){throw 'Local state changed'}
   @{utc=[datetime]::UtcNow.ToString('o');commit=$Commit;remoteMainMatches=$true;localScopeClean=$true;preservedTag='v3.2.9';verifyUrl=$run.url;verifyConclusion=$run.conclusion;jobs=$run.jobs|Select-Object name,status,conclusion}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $receiptDir 'delivery-verified.json') -Encoding utf8
   'DELIVERED '+$run.url
   exit 0
  }
  Start-Sleep -Seconds 15
 }
 throw 'Bounded Verify watch expired; outcome not assumed'
}finally{$env:HTTP_PROXY=$oldHttp;$env:HTTPS_PROXY=$oldHttps}
