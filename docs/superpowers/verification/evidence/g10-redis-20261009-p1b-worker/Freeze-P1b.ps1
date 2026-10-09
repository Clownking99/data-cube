$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$freeze=Join-Path $PSScriptRoot 'final-freeze'
if(Test-Path -LiteralPath $freeze){throw 'Fresh freeze required'}
$run=Join-Path $PSScriptRoot '007-p1b-final-targeted'
$before=@(Get-Content (Join-Path $run 'inputs-before.json') -Raw | ConvertFrom-Json)
$after=@(Get-Content (Join-Path $run 'inputs-after.json') -Raw | ConvertFrom-Json)
if($before.Count -ne 841 -or $after.Count -ne 841){throw 'Unexpected input count'}
foreach($item in $before){
    $other=$after | Where-Object path -EQ $item.path
    $path=Join-Path $repo $item.path
    if($other.sha256 -ne $item.sha256 -or $other.length -ne $item.length -or (Get-Item -LiteralPath $path).Length -ne $item.length -or (Get-FileHash -LiteralPath $path).Hash -ne $item.sha256){throw ('Input changed: '+$item.path)}
}
$p1a=Join-Path $repo 'docs/superpowers/verification/evidence/g10-redis-20261009-p1a-worker'
$priorManifest=Join-Path $p1a 'raw-manifest.json'
if((Get-FileHash -LiteralPath $priorManifest).Hash -ne 'C2453E9FDB4AFC618C4F6BE8C41E3AFA2CEFDE1C6BA23746452250C92C01695F'){throw 'P1a manifest changed'}
$prior=Get-Content -LiteralPath $priorManifest -Raw | ConvertFrom-Json
foreach($item in $prior.files){$path=Join-Path $repo $item.path;if((Get-Item -LiteralPath $path).Length -ne $item.length -or (Get-FileHash -LiteralPath $path).Hash -ne $item.sha256){throw ('P1a original changed: '+$item.path)}}
$serviceNames=@('RedisResourceLimits','RedisException','RespCodec','RespClient','RedisSession','RedisSessionManager')
foreach($name in $serviceNames){$live=Join-Path $repo ('src/com/datacube/redis/'+$name+'.java');$saved=Join-Path $p1a ('final-freeze/sources/src/com/datacube/redis/'+$name+'.java');if((Get-FileHash -LiteralPath $live).Hash -ne (Get-FileHash -LiteralPath $saved).Hash){throw ('P1a product changed: '+$name)}}
$summary=@{suites=0;tests=0;failures=0;errors=0;skipped=0}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $run 'xml') -Filter 'TEST-*.xml' -File){[xml]$xml=Get-Content -LiteralPath $file.FullName -Raw;$summary.suites++;foreach($field in @('tests','failures','errors','skipped')){$summary[$field]+=[int]$xml.testsuite.$field}}
[xml]$fx=Get-Content -LiteralPath (Join-Path $run 'xml/TEST-com.datacube.fx.RedisPaneBudgetTest.xml') -Raw
if($summary.tests -ne 88 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.skipped -ne 1 -or [int]$fx.testsuite.tests -ne 11 -or [int]$fx.testsuite.skipped -ne 0){throw 'Unexpected test result'}
$exit=Get-Content (Join-Path $run 'exit.json') -Raw | ConvertFrom-Json
if($exit.exitCode -ne 0){throw 'Final run failed'}
$head=(& git -C $repo rev-parse HEAD)
$branch=(& git -C $repo branch --show-current)
$old=(& git -C $repo rev-parse codex/g9-table-export-reliability-20261008)
if($head -ne '66edd24f50a327a82628d70de72fb7b7911c2ae2' -or $branch -ne 'codex/g10-redis-resource-budget-20261009' -or $old -ne '6b8ceb93c7413b3882137d322d88b9a1cb14ef5c'){throw 'Unexpected branch identity'}
New-Item -ItemType Directory -Path $freeze | Out-Null
$sources=@(& rg --files -g '!**/.testagent/**' src/com/datacube/redis test/com/datacube/redis)
$sources+=@('src/com/datacube/fx/RedisConsolePane.java','src/com/datacube/fx/RedisKeyBrowserPane.java','test/com/datacube/fx/RedisPaneBudgetTest.java','test/com/datacube/fx/FxUiTestSupport.java','test/com/datacube/fx/ManagedPaneConstructionContractTest.java','test/com/datacube/fx/RedisPaneCloseSequenceTest.java')
foreach($relative in $sources){$dest=Join-Path $freeze ('sources/'+$relative);New-Item -ItemType Directory -Path (Split-Path -Parent $dest) -Force | Out-Null;Copy-Item -LiteralPath (Join-Path $repo $relative) -Destination $dest}
$command=Get-Content (Join-Path $run 'command.json') -Raw | ConvertFrom-Json
$compiled=Join-Path $command.owned 'build/main/classes/java'
foreach($kind in @('main','test')){
    $redis=Join-Path $compiled ($kind+'/com/datacube/redis')
    $dest=Join-Path $freeze ('compiled/'+$kind+'/com/datacube/redis')
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Get-ChildItem -LiteralPath $redis -File -Filter '*.class' | Copy-Item -Destination $dest
    $fxDir=Join-Path $compiled ($kind+'/com/datacube/fx')
    $fxDest=Join-Path $freeze ('compiled/'+$kind+'/com/datacube/fx')
    New-Item -ItemType Directory -Path $fxDest -Force | Out-Null
    $patterns=if($kind -eq 'main'){@('RedisConsolePane*.class','RedisKeyBrowserPane*.class')}else{@('RedisPaneBudgetTest*.class','FxUiTestSupport*.class','ManagedPaneConstructionContractTest*.class','RedisPaneCloseSequenceTest*.class')}
    foreach($pattern in $patterns){Get-ChildItem -LiteralPath $fxDir -File -Filter $pattern | Copy-Item -Destination $fxDest}
}
Copy-Item -LiteralPath (Join-Path $repo 'docs/superpowers/verification/2026-10-09-g10-redis-worker.md') -Destination (Join-Path $freeze 'worker-report.md')
@{utc=[datetime]::UtcNow.ToString('o');branch=$branch;head=$head;stage='P1b only';run='007-p1b-final-targeted';verifiedInputCount=$before.Count;summary=$summary;passed=$summary.tests-$summary.skipped;fxActual=11;fxSkipped=0;p1aImmutableFiles=$prior.files.Count;p1aManifestSha256=(Get-FileHash -LiteralPath $priorManifest).Hash;p1aProductsUnchanged=$serviceNames;oldG9=$old;committed=$false;productWritesStopped=$true;environment='Native JavaFX toolkit with synthetic executors; isolated home/temp/build; no real services';argv=$command.argv} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $freeze 'binding.json') -Encoding utf8
$diff=(& git -C $repo diff --check -- . ':(exclude).testagent' ':(exclude).testagent/**' ':(exclude)**/.testagent/**' 2>&1 | Out-String)
if($LASTEXITCODE -ne 0){throw 'Diff check failed'}
[IO.File]::WriteAllText((Join-Path $freeze 'diff-check.log'),$diff,[Text.UTF8Encoding]::new($false))
$manifest=Join-Path $PSScriptRoot 'raw-manifest.json'
$files=@(foreach($path in @(& rg --files --hidden --no-ignore -g '!**/.testagent/**' $PSScriptRoot | Sort-Object)){
    if($path -eq $manifest){continue};$relative=[IO.Path]::GetRelativePath($repo,$path).Replace('\','/');@{path=$relative;length=(Get-Item -LiteralPath $path).Length;sha256=(Get-FileHash -LiteralPath $path).Hash}
})
@{note='Immutable P1b raw runs (including failures), frozen inputs/classes/report; manifest excludes itself';files=$files} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifest -Encoding utf8
$saved=Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
foreach($item in $saved.files){$path=Join-Path $repo $item.path;if((Get-Item -LiteralPath $path).Length -ne $item.length -or (Get-FileHash -LiteralPath $path).Hash -ne $item.sha256){throw ('Frozen artifact changed: '+$item.path)}}
@{frozenFiles=$saved.files.Count;manifestSha256=(Get-FileHash -LiteralPath $manifest).Hash;verifiedInputs=$before.Count;verifiedP1a=$prior.files.Count;tests=$summary;head=$head;oldG9=$old} | ConvertTo-Json -Depth 5
