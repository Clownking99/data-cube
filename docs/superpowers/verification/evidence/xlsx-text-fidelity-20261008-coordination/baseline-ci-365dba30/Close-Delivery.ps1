$ErrorActionPreference='Stop'
$dir=$PSScriptRoot
$root='D:/Projects/朝花夕拾'
$expected='365dba304a5e198e72ae7633d88a1b5258d058fa'
$run=Get-Content -LiteralPath (Join-Path $dir 'workflow-retry-complete.json') -Raw|ConvertFrom-Json
if($run.headSha -ne $expected -or $run.attempt -ne 2 -or $run.status -ne 'completed' -or $run.conclusion -ne 'success'){throw 'Exact-SHA rerun success required'}
$first=Get-Content -LiteralPath (Join-Path $dir 'workflow-first-attempt-complete.json') -Raw|ConvertFrom-Json
$firstAudit=Get-Content -LiteralPath (Join-Path $dir 'first-attempt-audit.json') -Raw|ConvertFrom-Json
if($first.headSha -ne $expected -or $first.conclusion -ne 'failure' -or (Get-FileHash -LiteralPath (Join-Path $dir 'windows-first-attempt.log')).Hash -ne $firstAudit.rawLogSha256){throw 'First failure evidence mismatch'}
& (Join-Path $root 'docs/superpowers/verification/evidence/xml-export-fidelity-20261008-coordination/Audit-CI-Windows.ps1') -Commit $expected -ReceiptDirectory $dir
if((git -C $root rev-parse HEAD) -ne $expected -or (git -C $root branch --show-current) -ne 'main'){throw 'Local main moved'}
$status=@(git -C $root status --short -- . ':(exclude).testagent' ':(exclude).testagent/**')
if($LASTEXITCODE -ne 0 -or $status.Count -ne 0){throw 'Local scoped status not clean'}
$remote=@(git -C $root -c http.proxy=http://127.0.0.1:7897 ls-remote -- origin refs/heads/main refs/tags/v3.2.9 'refs/tags/v3.2.9^{}')
if($LASTEXITCODE -ne 0){throw 'Remote ref read failed'}
$remote|Set-Content -LiteralPath (Join-Path $dir 'remote-refs-final.txt') -Encoding utf8
$refs=@{}
foreach($line in $remote){$parts=$line -split '\s+';if($parts.Count -eq 2){$refs[$parts[1]]=$parts[0]}}
if($refs['refs/heads/main'] -ne $expected -or $refs['refs/tags/v3.2.9'] -ne 'cfe83d64d313ad343fe504e8136299d8a7181fe8' -or $refs['refs/tags/v3.2.9^{}'] -ne '0f6ba02656fcf3752b180514c72e79151a3320df'){throw 'Remote main/tag mismatch'}
if((git -C $root rev-parse refs/tags/v3.2.9) -ne $refs['refs/tags/v3.2.9'] -or (git -C $root rev-parse 'refs/tags/v3.2.9^{}') -ne $refs['refs/tags/v3.2.9^{}']){throw 'Local tag mismatch'}
$local=Get-Content -LiteralPath (Join-Path $dir 'local-verification.json') -Raw|ConvertFrom-Json
@{utc=[datetime]::UtcNow.ToString('o');headSha=$expected;localMain=$expected;remoteMainMatches=$true;localScopeClean=$true;excludedPath='.testagent';tag='v3.2.9';tagUnchanged=$true;ciRun=37714054981;ciUrl=$run.url;ciConclusion=$run.conclusion;attempt=2;rerun='one failed Windows job only; other three successes reused from first attempt';jobs=@($run.jobs|Select-Object name,status,conclusion,databaseId,startedAt,completedAt);firstAttemptFailureRetained=$true;firstAttemptRootCause='unknown';localHelperRegression='11 passed, 0 skipped; actual execution; affected case 0.604 seconds';sourceAndTimeoutUnchanged=$true;validation=$local.branchAndMain;unverified=($local.unverified+@('Root cause of initial Windows synthetic helper subprocess timeout; diagnostics absent from the first CI run'))}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $dir 'delivery-verified.json') -Encoding utf8
$checkpoint=@'
# S8 推送与精确 SHA CI 收尾

目标：交付本轮 XML 导出忠实性修复；不扩展下一轮。

main 365dba304a5e198e72ae7633d88a1b5258d058fa 已推送，最终远端读回与本地一致，范围内工作区干净，v3.2.9 对象及目标未变化。分支与 main 的新定向 144/144、全量 4161 passed / 3 live skipped、强制 buildSrc 8/8、jpackageImage 与 linked XML 探针通过，详见跟踪内 S7 和本目录 local-verification.json。

Verify 37714054981 首次 Windows 在未改动的 PortableUpdateHelperTest.installedHandoffCannotClaimInstallerCompletionOrReplaceTheRunningImage 中触及原有 20 秒子进程等待限时，1 failed / 3 skipped，linked image 未执行。wrapper、Ubuntu、Redis 已通过。第一次结算、完整原日志及 SHA 均保留；没有发布子进程输出，根因未知，不能以重跑通过称已修复。

保持同一 SHA、源码和 20 秒限时，根线程新合成 profile 本地专项实际执行 11/11、0 skip，相关用例 0.604 秒。仅失败 Windows 任务重跑一次后通过，全流程最终四项 success；另外三项沿用首次结果，不称重新执行。最终 Windows 原日志另存并核对 :buildSrc:test、:test、:jlink 实际执行和 Unit tests / Windows linked image 成功。

未验/失败：首轮 CI 超时原因仍待诊断；原生桌面/键盘/OS 缩放多屏、真实数据库/慢或网络磁盘、安装升级回退/生产签名及完整 M8 仍待验。全量 3 项 live 跳过不计通过；本轮无新增真库、凭据、原配置、历史或业务文件访问。

下一步：本轮交付；后续先在 clean 前归档本目录原件及 receipt-manifest.json，再选择新的有界目标。若同类 CI 超时再次出现，先补可定位子进程诊断，不静默放宽断言或重复重跑。PAUSED 跟进保持，不自动启动下一轮，不打 tag 或发布。
'@
$checkpoint|Set-Content -LiteralPath (Join-Path $dir 'final-checkpoint.md') -Encoding utf8
$files=@(Get-ChildItem -LiteralPath $dir -File -Recurse|Where-Object Name -ne 'receipt-manifest.json'|Sort-Object FullName|ForEach-Object {@{path=[IO.Path]::GetRelativePath($dir,$_.FullName);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
@{utc=[datetime]::UtcNow.ToString('o');headSha=$expected;fileCount=$files.Count;files=$files}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $dir 'receipt-manifest.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $dir 'delivery-verified.json')
