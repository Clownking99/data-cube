$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$latest='p1b-010-affected-targeted'
$runRoot=Join-Path $PSScriptRoot $latest
$freezeRoot=Join-Path $PSScriptRoot 'p1b-final-freeze-010'
if(Test-Path -LiteralPath $freezeRoot){throw 'Freeze must be new'}
$before=Get-Content -LiteralPath (Join-Path $runRoot 'sources-before-run.json') -Raw|ConvertFrom-Json
$report='docs/superpowers/verification/2026-10-08-g9-table-export-worker.md'
$binding=foreach($entry in $before.files){
 if($entry.path -eq $report){continue} # Final prose records results after execution; runtime/launcher/README remain exact.
 $live=Join-Path $repoRoot $entry.path
 $copy=Join-Path (Join-Path $runRoot 'source-before-run') $entry.path
 if((Get-FileHash -LiteralPath $copy).Hash -ne $entry.sha256){throw ('Before-run copy drift: '+$entry.path)}
 if((Get-FileHash -LiteralPath $live).Hash -ne $entry.sha256){throw ('Source changed after test: '+$entry.path)}
 [pscustomobject]@{path=$entry.path;sha256=$entry.sha256;matchesBeforeRun=$true}
}
$oldRoot=Join-Path $repoRoot 'docs/superpowers/verification/evidence/g9-table-export-20261008-worker'
$old=Get-Content -LiteralPath (Join-Path $oldRoot 'p1a-artifact-manifest-012.json') -Raw|ConvertFrom-Json
foreach($entry in $old){if((Get-FileHash -LiteralPath (Join-Path $oldRoot $entry.path)).Hash -ne $entry.sha256){throw ('Old P1a evidence drift: '+$entry.path)}}
$summaries=foreach($folder in (Get-ChildItem -LiteralPath $PSScriptRoot -Directory|Where-Object Name -Match '^p1b-\d{3}-')){
 $commandPath=Join-Path $folder.FullName 'command.json'
 if(!(Test-Path -LiteralPath $commandPath)){continue}
 $exit=Get-Content -LiteralPath (Join-Path $folder.FullName 'exit.json') -Raw|ConvertFrom-Json
 $command=Get-Content -LiteralPath $commandPath -Raw|ConvertFrom-Json
 $stdout=[IO.File]::ReadAllText((Join-Path $folder.FullName 'stdout.log'))
 $suites=foreach($file in (Get-ChildItem -LiteralPath (Join-Path $folder.FullName 'xml') -Filter 'TEST-*.xml' -File)){
  [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
  $suite=$xml.testsuite
  if([int]$suite.tests -ne @($suite.testcase).Count){throw 'Suite and case counts differ'}
  [pscustomobject]@{name=$suite.name;tests=[int]$suite.tests;cases=@($suite.testcase).Count;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped}
 }
 [pscustomobject]@{run=$folder.Name;exitCode=[int]$exit.exitCode;actualTest=($stdout -match '(?m)^> Task :test(?: FAILED)?\r?$');
  head=$command.head;suites=@($suites).Count;tests=[int](($suites|Measure-Object tests -Sum).Sum);
  cases=[int](($suites|Measure-Object cases -Sum).Sum);failures=[int](($suites|Measure-Object failures -Sum).Sum);
  errors=[int](($suites|Measure-Object errors -Sum).Sum);skipped=[int](($suites|Measure-Object skipped -Sum).Sum);details=@($suites)}
}
$green=$summaries|Where-Object run -EQ $latest
if($green.exitCode -ne 0 -or !$green.actualTest -or $green.failures -ne 0 -or $green.errors -ne 0 -or $green.skipped -ne 0){throw 'Latest real targeted run not green'}
New-Item -ItemType Directory -Path $freezeRoot|Out-Null
$files=@($before.files.path)+@('docs/superpowers/verification/evidence/g9-pgdump-20261008-worker/Freeze-P1b.ps1')
$manifest=foreach($relative in ($files|Sort-Object -Unique)){
 $source=Join-Path $repoRoot $relative
 $destination=Join-Path $freezeRoot $relative
 New-Item -ItemType Directory -Force -Path (Split-Path $destination)|Out-Null
 Copy-Item -LiteralPath $source -Destination $destination
 $tree=& git -C $repoRoot ls-tree HEAD -- $relative
 if($LASTEXITCODE -ne 0){throw 'Blob lookup failed'}
 $blob=if($tree -match '^\d+\s+blob\s+([0-9a-f]+)\s+'){$Matches[1]}else{$null}
 [pscustomobject]@{path=$relative;bytes=(Get-Item -LiteralPath $source).Length;sha256=(Get-FileHash -LiteralPath $source).Hash;headBlob=$blob}
}
$manifest|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $freezeRoot 'manifest.json') -Encoding utf8
$summaries|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'p1b-run-summary-010.json') -Encoding utf8
$receipts=foreach($file in (Get-ChildItem -LiteralPath (Join-Path $runRoot 'xml') -Filter 'TEST-*.xml' -File)){
 [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
 $output=$xml.testsuite.'system-out'.InnerText
 foreach($line in ($output -split '\r?\n')){if($line -match '^(PGDUMP_PHYSICAL|PGDUMP_FAMILY|TABLE_WINDOW|TABLE_CLIENT|TABLE_BASELINE) '){[pscustomobject]@{suite=$xml.testsuite.name;line=$line}}}
}
$receipts|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'p1b-physical-receipts-010.json') -Encoding utf8
$head=& git -C $repoRoot rev-parse HEAD
if($head -ne 'b81923f29e0a6b6603710a589504aab490995491'){throw 'HEAD changed'}
$branch=& git -C $repoRoot branch --show-current
$status=@(& git -C $repoRoot status --short -- . ':(exclude).testagent' ':(exclude).testagent/**')
$statusExit=$LASTEXITCODE
$check=@(& git -C $repoRoot diff --check -- . ':(exclude).testagent' ':(exclude).testagent/**')
$checkExit=$LASTEXITCODE
$runtime=@($before.files.path|Where-Object {$_ -match '^(src|test)/'})
$patch=@(& git -C $repoRoot diff --binary -- $runtime README.md)
$patchExit=$LASTEXITCODE
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'p1b-tracked-product-test-010.patch'),($patch -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
$whitespace=foreach($relative in $files){$line=0;foreach($text in [IO.File]::ReadAllLines((Join-Path $repoRoot $relative))){$line++;if($text -match '\s+$'){[pscustomobject]@{path=$relative;line=$line}}}}
if($statusExit -ne 0 -or $checkExit -ne 0 -or $patchExit -ne 0 -or @($whitespace).Count -ne 0){throw 'Final checks failed'}
[ordered]@{utc=[datetime]::UtcNow.ToString('o');head=$head;branch=$branch;latestRun=$latest;tests=$green.tests;suites=$green.suites;
 failures=$green.failures;errors=$green.errors;skipped=$green.skipped;sourceBinding=@($binding);runtimeFiles=$runtime.Count;
 frozenFiles=@($manifest).Count;oldP1aArtifactsVerified=$old.Count;status=$status;diffCheckExit=$checkExit;diffCheckOutput=$check;
 finalReport='Updated after results; runtime, README and launcher match before-run bytes';stageCommitPush='not performed';
 fullBuildSrcTestImage='not run';nextState='P1b frozen; stop for independent coordination review'}|
 ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'p1b-final-checks-010.json') -Encoding utf8
$artifacts=foreach($file in (Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse|Sort-Object FullName)){
 if($file.Name -eq 'p1b-artifact-manifest-010.json'){continue}
 if(!$file.FullName.StartsWith($PSScriptRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Artifact escaped evidence root'}
 [pscustomobject]@{path=$file.FullName.Substring($PSScriptRoot.Length+1).Replace('\','/');bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash}
}
$artifacts|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'p1b-artifact-manifest-010.json') -Encoding utf8
Write-Output ('Frozen files='+@($manifest).Count+'; runtime files='+$runtime.Count+'; bindings='+@($binding).Count+'; artifacts='+@($artifacts).Count+'; tests='+$green.tests+'; old artifacts verified='+$old.Count)
