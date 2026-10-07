$ErrorActionPreference='Stop'
$co=$PSScriptRoot
$root=(Resolve-Path (Join-Path $co '../../../../..')).Path
$merge='f94c102223c52b69a91881df99792604445cd9ac'
$source='6ef4b3eee757f79f46499bd483579b71fa777104'
$branchEvidence='b6cd398ddccc2058274282fe4d1eb8ef03b898ab'
if((git -C $root rev-parse HEAD) -ne $merge){throw 'Unexpected main source'}
$branch=Get-Content -LiteralPath (Join-Path $co 'branch-independent-tests.json') -Raw|ConvertFrom-Json
$summaries=@()
$names=@('main-directed','main-full','main-buildsrc')
for($i=0;$i -lt $names.Count;$i++){
 $item=Get-Content -LiteralPath (Join-Path $co ($names[$i]+'/summary.json')) -Raw|ConvertFrom-Json
 if($item.source -ne $merge -or $item.exitCode -ne 0 -or $item.failures -ne 0 -or $item.errors -ne 0 -or !$item.freshXml){throw 'Main test result invalid'}
 if($item.tests -ne $branch.tests[$i].tests -or $item.skipped -ne $branch.tests[$i].skipped){throw 'Main and reviewed branch test counts differ'}
 $summaries+=,$item
}
$mainImage=Get-Content -LiteralPath (Join-Path $co 'main-image-audit/audit.json') -Raw|ConvertFrom-Json
$branchImage=Get-Content -LiteralPath (Join-Path $co 'branch-image-audit/audit.json') -Raw|ConvertFrom-Json
$imageExit=Get-Content -LiteralPath (Join-Path $co 'main-image/exit.json') -Raw|ConvertFrom-Json
$imageLog=Get-Content -LiteralPath (Join-Path $co 'main-image/gradle.log') -Raw
if(!$mainImage.passed -or !$branchImage.passed -or $imageExit.exitCode -ne 0 -or $imageLog -notmatch '(?m)^> Task :jpackageImage\r?$'){throw 'Image build/audit not passed'}
$comparison=@()
foreach($artifact in $mainImage.artifacts){
 $peer=@($branchImage.artifacts|Where-Object path -eq $artifact.path)
 if($peer.Count -ne 1 -or $peer[0].bytes -ne $artifact.bytes -or $peer[0].sha256 -ne $artifact.sha256){throw ('Image differs: '+$artifact.path)}
 $comparison+=@{path=$artifact.path;bytes=$artifact.bytes;sha256=$artifact.sha256;identical=$true}
}
if($comparison.Count -ne 3){throw 'Three artifact identities required'}
$raw=& 'D:/NodeJs/nodejs/node.exe' (Join-Path $co 'Verify-Archived-Raw.cjs') 'docs/superpowers/verification/evidence/shutdown-pending-20261007-worker'
if($LASTEXITCODE -ne 0){throw 'Worker raw Git audit failed'}
$raw|Set-Content -LiteralPath (Join-Path $co 'main-worker-raw-audit.json') -Encoding utf8
$pending=@('native keyboard/mouse and migration confirmation on current runtime','OS scaling and multiple monitors','remaining full in-flight workflow acceptance','FAILED_PARTIAL in-process recovery','formal startup without acceptance gate','installation, upgrade, rollback and production signatures','complete M8 release acceptance')
$scope=@{sourceCommit=$source;branchEvidenceCommit=$branchEvidence;mainMerge=$merge;pending=$pending;tag='v3.2.9 object and target must remain unchanged';remoteStatus='Pending final main push and exact-SHA Verify; final receipts under exclusive build/owned-ci-UUID';historicalPending='Prior isolated five-second FX timeout cause unknown; timeout unchanged'}
$scope|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $co 'delivery-scope.json') -Encoding utf8
@{utc=[datetime]::UtcNow.ToString('o');tests=$summaries;imageTaskExecuted=$true;imageAuditPassed=$true;imageFiles=@(Get-Content -LiteralPath (Join-Path $co 'main-image-audit/file-list.txt')).Count;imageComparison=$comparison;sourceTreeMatchesReviewed=$true;scope=$scope}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $co 'results.json') -Encoding utf8
$review=Join-Path $root 'docs/superpowers/verification/2026-10-07-shutdown-pending-feedback-review.md'
$note=@'

## S6 main完整复验与本地交付

main集成f94c102上新执行定向40/40、全量314套4012总数/4009执行通过/3live跳过、强制root buildSrc8/8，均0failure/error；jpackageImage强制实际执行成功。每次新UUID profile/真实8.3独占temp、实际Task/exit/新鲜XML逐次归档。三跳过仍为Redis standalone与Oracle/PG SchemaDiff live，不计通过；没有使用真实连接。统计helper返回成功后调度误读遗留native LASTEXITCODE，曾在全量启动前被拒；原40项通过未变，保存main-dispatch-diagnostic后首次启动全量，不是测试失败或复跑。

main镜像183文件隔离审计与仅驱动发现通过，Oracle/PG connectCalls=0；exe/cfg/modules三项长度与SHA均和分支相同，modules102405401字节、SHA06D4956F06D5B54C4EB6D7633C735BBADFDDA20F30D4B243C2B4D14BEA5D160D。main产品树与已审6ef4b3e一致；366份worker原件再次通过原始/Git字节审计。详见results.json和各原始运行目录。

本限定等待反馈目标本地完成：关闭已进入等待时持续可见、主体外可读；取消/可恢复异常/null清除并恢复交互，成功清除并关闭，partial替换已有保护说明；反复关闭不重复清理，生产workspace Alert仍可决策，旧资源所有权和5秒/15秒均保留。纯合成Scene及实际handler/modal不是原生输入或正式Application.start。原生、完整在途/终态恢复、系统缩放多屏、无Gate启动、安装升级回退/生产签名和完整M8仍待验；旧孤立FX超时根因未知。

最终只按既有授权推送main，精确最终SHA Verify和远端refs由Push-Verify-Main.ps1保存在独占build/owned-ci-UUID；这里不预报CI结论。v3.2.9不移动、datacube不恢复，交付后不自动扩展下一功能。
'@
Add-Content -LiteralPath $review -Value $note -Encoding utf8
$plan=Join-Path $root 'docs/superpowers/plans/2026-10-07-shutdown-pending-feedback.md'
Add-Content -LiteralPath $plan -Value "`nS6：main f94c102 新定向40/全量4009通过+3live跳过/buildSrc8/image及183文件镜像隔离/零连接审计通过；分支/main三产物SHA一致，366份worker原件Git字节复核。本地限定交付完成，原生/完整在途与终态恢复/安装签名/完整M8仍待；最终提交、main推送及精确SHA CI以实际回执为准。" -Encoding utf8
$latest='**2026-10-07 退出等待可见反馈（最新产品）：** 关闭进入在途后即时显示等待与对话框指引；取消/可恢复异常清除、成功关闭清除、partial互斥切换保护说明，保留事务/资源/关闭时限。GPT-6.1-sol开发、root独立审核；源码6ef4b3e、证据b6cd398d、main集成f94c102。分支/main各新定向40、全量4009通过/3明确live跳过、buildSrc8、jpackageImage及镜像零连接审计通过，三产物SHA一致；366份worker原件Git字节复核。详见[独立审查记录](LINK)。首红与调度诊断保留。仅工程与合成FX交付；原生输入/OS缩放、完整在途/终态恢复、无Gate启动、安装升级签名及完整M8仍待验。最终main推送与精确SHA CI以交付回执核对；v3.2.9不动，datacube保持PAUSED。下方各“最新”按历史轮次解读。'
$handoff=Join-Path $root 'docs/handoffs/2026-09-23-product-maturity-goal-handoff.md'
$roadmap=Join-Path $root 'docs/superpowers/plans/2026-09-23-product-maturity-roadmap.md'
foreach($path in @($handoff,$roadmap)){
 $text=[IO.File]::ReadAllText($path)
 $link=if($path -eq $handoff){'../superpowers/verification/2026-10-07-shutdown-pending-feedback-review.md'}else{'../verification/2026-10-07-shutdown-pending-feedback-review.md'}
 $text=$text.Insert($text.IndexOf("`n")+1,"`n"+$latest.Replace('LINK',$link)+"`n")
 if($path -eq $roadmap){
  $pattern='(?m)^\| M8 \| 本地工程[^\r\n]*'
  if([regex]::Matches($text,$pattern).Count -ne 1){throw 'Unique M8 status row required'}
  $row='| M8 | 本地工程与部分原生验收已交付，v3.2.9自动打包已发布；完整M8未完成 | 最新[退出等待反馈](../verification/2026-10-07-shutdown-pending-feedback-review.md)：分支/main各定向40、全量4009通过/3 live跳过、buildSrc8、image/零连接镜像审计；此前失败反馈/收藏/查找小窗及原生工作区/启动器记录保持历史运行时身份 | 原生收藏/名称字段输入与完整下游动作、完整在途/终态恢复、OS缩放/多屏、无Gate启动/闪屏、安装升级/回退/生产签名仍待验；datacube保持PAUSED |'
  $text=[regex]::Replace($text,$pattern,$row)
 }
 [IO.File]::WriteAllText($path,$text.TrimEnd()+"`n",[Text.UTF8Encoding]::new($false))
}
foreach($path in @($review,$plan)){[IO.File]::WriteAllText($path,[IO.File]::ReadAllText($path).TrimEnd()+"`n",[Text.UTF8Encoding]::new($false))}
'Main results, review, handoff and roadmap updated from actual evidence; final remote verification remains pending.'
