$ErrorActionPreference='Stop'
Set-Location -LiteralPath 'D:/Projects/朝花夕拾'
$receipt=$PSScriptRoot
$commit='fccc58ba95bb0deec19463cd75f2bb32a33dbc8a'
$coord='docs/superpowers/verification/evidence/xlsx-text-fidelity-20261008-coordination'
$delivery=Get-Content -LiteralPath (Join-Path $receipt 'delivery-verified.json') -Raw|ConvertFrom-Json
$windows=Get-Content -LiteralPath (Join-Path $receipt 'windows-log-audit.json') -Raw|ConvertFrom-Json
$local=Get-Content -LiteralPath (Join-Path $coord 'main-branch-comparison.json') -Raw|ConvertFrom-Json
if($delivery.commit -ne $commit -or $delivery.verifyConclusion -ne 'success' -or !$delivery.remoteMainMatches -or !$delivery.localScopeClean){throw 'Delivery state mismatch'}
if($windows.headSha -ne $commit -or !$windows.passed){throw 'Windows raw evidence mismatch'}
if((git rev-parse HEAD) -ne $commit -or (git branch --show-current) -ne 'main'){throw 'Local HEAD changed'}
if(@(git status --porcelain -- . ':(exclude).testagent' ':(exclude).testagent/**').Count -ne 0){throw 'Local scope dirty'}
git diff --quiet ce2a096a044db6a731774b13a8ae61ea44687953 HEAD -- src test resources buildSrc build.gradle .github/workflows
if($LASTEXITCODE -ne 0){throw 'Reviewed source changed'}
foreach($artifact in $local.artifacts){
 $file=Join-Path 'build/jpackage/DataCube' $artifact.path
 if((Get-Item -LiteralPath $file).Length -ne $artifact.bytes -or (Get-FileHash -LiteralPath $file).Hash -ne $artifact.sha256){throw ('Artifact changed: '+$artifact.path)}
}
$snapshot=@{
 utc=[datetime]::UtcNow.ToString('o');commit=$commit;goal='XLSX text fidelity increment';status='delivered';source='ce2a096a044db6a731774b13a8ae61ea44687953';
 changes='Preserve C0/CR/FFFE/FFFF and literal OOXML escape-looking text; reject unpaired UTF16; preserve query export failure safety';
 local=$local;ci=$delivery;windowsAudit=$windows;remaining=$local.remaining;next='Stop this increment; no automatic next feature, tag or release';
 boundaries='Synthetic profiles and files only; no live database or native Excel acceptance; existing v3.2.9 unchanged'
}
$snapshot|ConvertTo-Json -Depth 16|Set-Content -LiteralPath (Join-Path $receipt 'final-checkpoint.json') -Encoding utf8
@"
# XLSX 文本保真交付完成

- main：$commit，已推送，受验源码 ce2a096a 保持不变。
- 行为：控制字符、回车和字面转义外观按 XLSX 格式保存；孤立 UTF16 明确拒绝；查询结果失败保留原目标并支持重试。
- 本地新证据：定向 286/286，全量 4303 通过/3 live 跳过，buildSrc 8/8，jpackageImage 通过；新镜像 20 包/40 单元格独立回读、6 项拒绝，三产物与已审核分支一致。
- 精确提交 CI：$($delivery.verifyUrl)，四任务成功；Windows 原日志确认 buildSrc:test、test、jlink 实际执行。
- 失败/未验：首红及工具诊断保留；三项 live 跳过不是通过。原生 Excel/LibreOffice/桌面、OS 缩放/多屏、真库、慢/网络磁盘、整表原目标失败保护、安装升级回退/生产签名与完整 M8 未完成。历史 CI 助手超时根因仍未知。
- 下一步：本轮结束交付，不自动扩展功能或发布；v3.2.9 不变，既有跟进保持 PAUSED。

实际证据见本目录 final-checkpoint.json、delivery-verified.json、windows-log-audit.json 及仓库内独立审核文档。
"@|Set-Content -LiteralPath (Join-Path $receipt 'delivery.md') -Encoding utf8
$manifestPath=Join-Path $receipt 'receipt-manifest.json'
if(Test-Path -LiteralPath $manifestPath){throw 'Receipt was already frozen'}
$files=@(Get-ChildItem -LiteralPath $receipt -File|Sort-Object Name|ForEach-Object {
 if($_.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Receipt link rejected'}
 @{path=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
})
@{utc=[datetime]::UtcNow.ToString('o');commit=$commit;files=$files}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $manifestPath -Encoding utf8
foreach($file in $files){
 $path=Join-Path $receipt $file.path
 if((Get-Item -LiteralPath $path).Length -ne $file.bytes -or (Get-FileHash -LiteralPath $path).Hash -ne $file.sha256){throw 'Receipt freeze mismatch'}
}
'Final receipt files verified: '+$files.Count
