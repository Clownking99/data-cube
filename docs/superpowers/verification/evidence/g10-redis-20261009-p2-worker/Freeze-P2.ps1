$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$dest=Join-Path $PSScriptRoot 'final-freeze'
if(Test-Path -LiteralPath $dest){throw 'Fresh freeze required'}
$runs=@('003-complete-targeted','004-clean-full','005-buildsrc-forced','006-image-forced')
$baseline=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot '004-clean-full/inputs-before.json') -Raw | ConvertFrom-Json)
foreach($run in $runs){
 $folder=Join-Path $PSScriptRoot $run
 $before=@(Get-Content -LiteralPath (Join-Path $folder 'inputs-before.json') -Raw | ConvertFrom-Json)
 $after=@(Get-Content -LiteralPath (Join-Path $folder 'inputs-after.json') -Raw | ConvertFrom-Json)
 if($before.Count -ne $baseline.Count -or $after.Count -ne $baseline.Count){throw 'Input counts differ'}
 foreach($item in $baseline){$a=$before | Where-Object path -EQ $item.path;$b=$after | Where-Object path -EQ $item.path;$live=Join-Path $repo $item.path;if($a.sha256 -ne $item.sha256 -or $b.sha256 -ne $item.sha256 -or $a.length -ne $item.length -or $b.length -ne $item.length -or (Get-FileHash -LiteralPath $live).Hash -ne $item.sha256 -or (Get-Item -LiteralPath $live).Length -ne $item.length){throw ('Changed input: '+$item.path)}}
 $exit=Get-Content -LiteralPath (Join-Path $folder 'exit.json') -Raw | ConvertFrom-Json
 if($exit.exitCode -ne 0){throw ('Run not passed: '+$run)}
}
$priorResults=@()
foreach($phase in @('p1a','p1b')){
 $manifest=Join-Path $repo "docs/superpowers/verification/evidence/g10-redis-20261009-$phase-worker/raw-manifest.json"
 $expected=if($phase -eq 'p1a'){'C2453E9FDB4AFC618C4F6BE8C41E3AFA2CEFDE1C6BA23746452250C92C01695F'}else{'1A4E9C6580159C4E00B1ECE2F7B09E8907E7F41AACD7C84659BF1B32B74C23F9'}
 if((Get-FileHash -LiteralPath $manifest).Hash -ne $expected){throw 'Prior manifest changed'}
 $prior=Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
 foreach($item in $prior.files){$file=Join-Path $repo $item.path;if((Get-FileHash -LiteralPath $file).Hash -ne $item.sha256 -or (Get-Item -LiteralPath $file).Length -ne $item.length){throw 'Prior frozen artifact changed'}}
 $priorResults+=@{stage=$phase;files=$prior.files.Count;sha256=$expected}
}
$summaries=@()
foreach($run in $runs[0..2]){
 $sum=@{run=$run;suites=0;tests=0;failures=0;errors=0;skipped=0;skips=@()}
 foreach($file in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "$run/xml") -Filter 'TEST-*.xml' -File){[xml]$xml=Get-Content -LiteralPath $file.FullName -Raw;$sum.suites++;foreach($field in @('tests','failures','errors','skipped')){$sum[$field]+=[int]$xml.testsuite.$field};if(@($xml.testsuite.SelectNodes('testcase')).Count -ne [int]$xml.testsuite.tests -or @($xml.testsuite.SelectNodes('testcase/skipped')).Count -ne [int]$xml.testsuite.skipped){throw 'Invalid XML counts'};foreach($case in $xml.testsuite.SelectNodes('testcase[skipped]')){$sum.skips+=@{class=$case.classname;name=$case.name;reason=$case.SelectSingleNode('skipped').OuterXml}}}
 if($sum.failures -or $sum.errors -or !$sum.tests){throw 'Invalid final suite results'}
 $summaries+= $sum
}
$audit=Get-Content -LiteralPath (Join-Path $PSScriptRoot '007-image-linked-audit/audit.json') -Raw | ConvertFrom-Json
if(!$audit.passed -or !$audit.allChildProcessesExited){throw 'Image audit not passed'}
$imageManifest=@(Get-Content -LiteralPath (Join-Path $PSScriptRoot '007-image-linked-audit/image-manifest.json') -Raw | ConvertFrom-Json)
foreach($item in $imageManifest){$file=Join-Path $audit.image $item.path;if((Get-FileHash -LiteralPath $file).Hash -ne $item.sha256 -or (Get-Item -LiteralPath $file).Length -ne $item.length){throw 'Image changed'}}
$head=(& git -C $repo rev-parse HEAD);$branch=(& git -C $repo branch --show-current);$old=(& git -C $repo rev-parse codex/g9-table-export-reliability-20261008)
if($head -ne '66edd24f50a327a82628d70de72fb7b7911c2ae2' -or $old -ne '6b8ceb93c7413b3882137d322d88b9a1cb14ef5c' -or $branch -ne 'codex/g10-redis-resource-budget-20261009'){throw 'Unexpected branch'}
New-Item -ItemType Directory -Path $dest | Out-Null
$products=@('RedisResourceLimits','RedisException','RespCodec','RespClient','RedisSession','RedisSessionManager','RedisDisplayLimits','RedisDisplaySupport','RedisKeySnapshot','RedisTextRetention','KeyTreeBuilder','RedisConsoleSupport') | ForEach-Object {"src/com/datacube/redis/$_.java"}
$products+=@('src/com/datacube/fx/RedisConsolePane.java','src/com/datacube/fx/RedisKeyBrowserPane.java')
$testNames=@('RedisSessionManagerTest','RespClientTest','RespBudgetTest','RespRecoveryTest','RedisSessionBudgetTest','RedisManagerBudgetTest','RedisDisplayBudgetTest','RedisTestSession')
$sources=@($products)+@($testNames | ForEach-Object {"test/com/datacube/redis/$_.java"})+@('test/com/datacube/fx/RedisPaneBudgetTest.java')
foreach($relative in $sources){$priorSource=Join-Path $repo ('docs/superpowers/verification/evidence/g10-redis-20261009-p1b-worker/final-freeze/sources/'+$relative);if((Get-FileHash -LiteralPath (Join-Path $repo $relative)).Hash -ne (Get-FileHash -LiteralPath $priorSource).Hash){throw ('G10 source changed since P1b: '+$relative)}}
foreach($relative in $sources){$copy=Join-Path $dest ('inputs/'+$relative);New-Item -ItemType Directory -Path (Split-Path -Parent $copy) -Force | Out-Null;Copy-Item -LiteralPath (Join-Path $repo $relative) -Destination $copy}
$classBindings=@()
foreach($run in @('004-clean-full','005-buildsrc-forced','006-image-forced')){
 $cmd=Get-Content -LiteralPath (Join-Path $PSScriptRoot "$run/command.json") -Raw | ConvertFrom-Json
 $relative=if($run -eq '005-buildsrc-forced'){'build/buildSrc/classes/java'}else{'build/main/classes/java'}
 $classes=Join-Path $cmd.owned $relative
 $entries=@(Get-ChildItem -LiteralPath $classes -Recurse -File | ForEach-Object {@{path=[IO.Path]::GetRelativePath($classes,$_.FullName).Replace('\','/');length=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
 $entries | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $dest "$run-all-class-manifest.json") -Encoding utf8
 $classBindings+=@{run=$run;uuidClasses=$classes;classCount=$entries.Count;completeManifest="$run-all-class-manifest.json"}
 foreach($entry in $entries){
  $selected=if($run -eq '005-buildsrc-forced'){$true}else{$entry.path -match '^main/com/datacube/redis/' -or $entry.path -match '^main/com/datacube/fx/Redis(ConsolePane|KeyBrowserPane)(\$|\.class)' -or $entry.path -match '^test/com/datacube/fx/RedisPaneBudgetTest(\$|\.class)' -or ($testNames | Where-Object {$entry.path -match ('^test/com/datacube/redis/'+$_+'(\$|\.class)')})}
  if($selected){$copy=Join-Path $dest ("compiled/$run/"+$entry.path);New-Item -ItemType Directory -Path (Split-Path -Parent $copy) -Force | Out-Null;Copy-Item -LiteralPath (Join-Path $classes $entry.path) -Destination $copy}
 }
 if($run -eq '006-image-forced'){$jars=Join-Path $cmd.owned 'build/main/libs';Copy-Item -LiteralPath $jars -Destination (Join-Path $dest 'product-jars') -Recurse}
}
New-Item -ItemType Directory -Path (Join-Path $dest 'image-launcher') | Out-Null
Copy-Item -LiteralPath (Join-Path $audit.image 'DataCube.exe'),(Join-Path $audit.image 'app/DataCube.cfg') -Destination (Join-Path $dest 'image-launcher')
Copy-Item -LiteralPath (Join-Path $repo 'docs/superpowers/verification/2026-10-09-g10-redis-worker.md') -Destination (Join-Path $dest 'worker-report.md')
$scope=@(foreach($run in @('001-complete-targeted','002-complete-targeted')+$runs){$cmd=Get-Content -LiteralPath (Join-Path $PSScriptRoot "$run/command.json") -Raw | ConvertFrom-Json;$cmd.owned;$cmd.shortTemp})+@($audit.owned,$audit.shortTemp)
$alive=@(foreach($ownedPath in $scope){
 $literal=$ownedPath.Replace('\','\\').Replace("'","\'")
 $query="SELECT ProcessId,ParentProcessId FROM Win32_Process WHERE (Name='java.exe' OR Name='javaw.exe') AND CommandLine LIKE '%$literal%'"
 foreach($process in Get-CimInstance -Query $query){@{pid=$process.ProcessId;parentPid=$process.ParentProcessId;ownedRange=$ownedPath}}
})
$knownProbeExits=@(foreach($step in @('module-index','probe-compile','driver-discovery','redis-linked')){$receipt=Get-Content -LiteralPath (Join-Path $PSScriptRoot "007-image-linked-audit/$step/exit.json") -Raw | ConvertFrom-Json;if(!$receipt.processExited -or $receipt.exitCode -ne 0){throw 'Probe exit not settled'};@{step=$step;pid=$receipt.pid;processExited=$receipt.processExited;utc=$receipt.utc}})
@{utc=[datetime]::UtcNow.ToString('o');scopes=$scope;ownedJavaProcesses=$alive;ownedJavaCount=$alive.Count;queryPolicy='CIM service-side owned-path predicates; retrieve PID/parentPID only, no unrelated process command lines';coverage='Recorded Gradle wrapper exits, known probe PID exits and explicit owned UUID/temp command paths; no claim about all machine background processes';knownProbeExits=$knownProbeExits;allRunChildrenExited=$true;probeChildrenExited=$audit.allChildProcessesExited} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $dest 'process-settlement.json') -Encoding utf8
if($alive.Count){throw 'Owned Java processes still alive'}
$baseline | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $dest 'input-binding.json') -Encoding utf8
@{utc=[datetime]::UtcNow.ToString('o');branch=$branch;head=$head;stage='P2';inputs=$baseline.Count;copiedSourceCount=$sources.Count;priorImmutable=$priorResults;runs=$summaries;compiledOutputs=$classBindings;unchangedInputs='Complete 863-file hash binding; unchanged sources/drivers/build inputs remain at Git HEAD 66edd24f and workspace, no duplicate raw trees/jars';image=$audit.image;imageFiles=$imageManifest.Count;imageArtifacts=$audit.artifacts;allProcessesSettled=$true;oldG9=$old;committed=$false;productTestGradleStopped=$true;P3Executed=$false} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $dest 'binding.json') -Encoding utf8
$diff=(& git -C $repo diff --check -- . ':(exclude).testagent' ':(exclude).testagent/**' ':(exclude)**/.testagent/**' 2>&1 | Out-String);if($LASTEXITCODE -ne 0){throw 'Diff check failed'}
[IO.File]::WriteAllText((Join-Path $dest 'diff-check.log'),$diff,[Text.UTF8Encoding]::new($false))
$manifest=Join-Path $PSScriptRoot 'raw-manifest.json'
$items=@(foreach($file in @(& rg --files --hidden --no-ignore -g '!**/.testagent/**' $PSScriptRoot | Sort-Object)){if($file -eq $manifest){continue};@{path=[IO.Path]::GetRelativePath($repo,$file).Replace('\','/');length=(Get-Item -LiteralPath $file).Length;sha256=(Get-FileHash -LiteralPath $file).Hash}})
@{note='Immutable P2 runs and first harness failures; complete input/all-class manifests with selective G10 source/class/report snapshots; full image retained at bound UUID path with complete image manifest';files=$items} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifest -Encoding utf8
$saved=Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
foreach($item in $saved.files){$file=Join-Path $repo $item.path;if((Get-FileHash -LiteralPath $file).Hash -ne $item.sha256 -or (Get-Item -LiteralPath $file).Length -ne $item.length){throw 'Freeze validation failed'}}
@{frozenFiles=$saved.files.Count;manifestSha256=(Get-FileHash -LiteralPath $manifest).Hash;inputs=$baseline.Count;summaries=$summaries;prior=$priorResults;imageFiles=$imageManifest.Count;processCount=$alive.Count} | ConvertTo-Json -Depth 7
