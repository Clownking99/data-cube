# CI 单次超时：独立诊断

受验提交 bdf274cac590f27105f02e20835da1fe8bf49848；Verify 37593098366 attempt 1，Windows job 112699064541。4000 项总计、1 失败、3 跳过；唯一失败为未改动的 SqlOverviewDurationSortTest.switchingToQueryDoesNotChangeUserDataOrItsComparator，FutureTask.get → FxUiTestSupport.call:32 → test:110，5 秒等待超时。Windows linked image 被跳过，不算通过。其他三个任务成功。首次原始日志和 API 身份保存在 attempt-1/，不能由重跑覆盖。

root 与 GPT-6.1-sol 独立源码核对：该用例只用 9 条合成概览和 2 行查询结果，没有调用收藏或连接真库。fixture 的此前三个 FX 调用已返回，故不是 startup latch 超时；现有日志无法区分未开始执行任务与执行中的排序/layout 耗时。旧 helper、旧用例及 SqlEditorPane 从本轮基线至受验提交没有修改。

收藏的 Scene 焦点监听在 close 移除，布局回调在 layout 前检查 closed；viewport/content 监听属于自己的场景，未发现跨 Scene 绑定、定时自重排或闭后持续布局链。fixture 的 FX 收尾后才在测试线程关闭 runner。源码审查未找到可据此修复的确定缺陷，亦不能据此排除时序、GC 或未捕获的 FX 工作耗时。

本轮两次新全量中的同一用例分别 0.051 秒/0.050 秒通过，是当前局部证据，不证明远端原因已解决。未增加超时、删断言、过滤失败用例或标记跳过，也未用历史通过代替新证据。没有足够证据声称托管基础设施故障或产品根因已修复。

诊断决定：保留 attempt 1，对精确同一 SHA 的失败 Windows job 仅发起一次重跑（attempt 2）。若重复同一超时，停止盲目重跑，先补充 FX 线程栈或任务开始/结束观测再决定修复。重跑结果单独归档；成功仅说明本次未复现，首次失败和根因未知继续作为待验项。

边界：只读源码、合成测试原件及此项目 CI；没有新本地构建、真库、用户配置/历史、系统剪贴板、.testagent 或发布/tag 操作。没有新功能范围。
