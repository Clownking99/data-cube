# 执行概览恢复执行顺序验收

## 范围与实现

基线 main `8e0fc8a`，分支 `codex/sql-overview-reset-order`。仅修改 `SqlScriptDetails` 生产代码，复用有界 `sourceRows` 与既有筛选/身份恢复流程。增加“恢复执行顺序”按钮，清除活动排序后按捕获顺序重建当前筛选结果；保留所选记录、名称/异常条件、拒绝提示和计数分母，不按语句编号排序、不重新执行 SQL。

自审特别覆盖 JavaFX 的原地排序：取消最后一个表头箭头并不恢复原行序，因此启用状态同时检查实际可见行是否仍为原快照的有序子序列。扫描最多 1,000 个已保留记录，使用对象身份，不因重复值错认。排序监听在 close 时移除；动作复核忙碌/禁用/关闭/概览状态。恢复沿用单条选择规则并关闭已有详情，无选择不代选。

使用 code-testing-agent 聚焦回归方式，未扩展依赖、未创建通用测试代理状态或启动子代理。以下需求矩阵即本轮测试证据；没有独立外部代码审查。

## 需求与证据

新增 `SqlOverviewResetOrderTest`，共 23 次执行，复用已有离线面板 fixture。

| 需求 | 精确测试 |
| --- | --- |
| 原快照次序而非编号；重复记录身份；所选结果跳转；无选择不代选；SQL/撤销/批次/导出不变 | `restoresSnapshotOrderRatherThanIndexAndKeepsOutcomeIdentity`（有/无选择） |
| 关键词与异常筛选交集、拒绝提示、原分母；所有排序列清除 | `preservesBothFiltersSelectionAndRejectedQueryNoticeWhileClearingAllSortColumns` |
| 取消表头箭头但行仍乱序时可恢复 | `removingHeaderSortStillAllowsRestorationOfReorderedRows` |
| 零匹配可重置，放宽条件后保持原顺序、不复活旧选择 | `emptyFilteredOverviewCanResetSortWithoutRestoringHiddenSelection` |
| 保留上限不扩大、遗漏记录不补入 | `resetCannotAddOmittedOutcomesBeyondRetainedLimit`（1,001 条原始结果） |
| 资源/任务/关闭/禁用/执行中/准入/队列屏障拒绝迟到动作，执行结束恢复可用 | `staleResetCannotChangeBlockedOverview`（8 组） |
| 单查询排序不受影响；空结果/计划/新批次/关闭不复活旧概览 | `replacementRejectsOldResetAndNeverRestoresPreviousBatch`（5 组） |
| 480/880 宽度、明暗主题下入口和原控件不裁切，表格仍可用 | `resetActionAndExistingControlsFitNarrowAndWideThemes`（4 组） |

首轮编译缺少 `FxTaskScope` import，修正后进入失败先行：23 项因缺少恢复入口失败，23 秒、exit 1（4 组布局为缺失节点，其余为明确入口断言）。最小实现后新类 14 秒通过；最终五类定向命令 22 秒、exit 0，共 129 项，零失败/错误/跳过：新类 23、耗时排序 8、异常筛选 32、关键词查找 42、执行详情集成 24。

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.fx.SqlOverviewResetOrderTest --tests com.datacube.fx.SqlOverviewDurationSortTest --tests com.datacube.fx.SqlOverviewSearchTest --tests com.datacube.fx.SqlOverviewFailureFilterTest --tests com.datacube.fx.SqlScriptDetailsIntegrationTest --no-daemon --console=plain
```

fixture 验证源 SQL 文件保持原内容，provider/session/metadata/network 调用均为零。不访问真实连接、SQL、历史、凭据、剪贴板或 `.testagent/`。

## 全量与打包

同样设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后执行：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

3 分 26 秒、exit 0 / BUILD SUCCESSFUL。应用 XML 汇总 3,217 项：3,214 通过、3 项既有 live 跳过、0 失败/错误；buildSrc 8 项通过。Windows 开发镜像构建成功，没有升级版本或正式发布。

保留既有警告：`SqlEditorResultFilterContractTest` 未检查操作提示；jlink 辅助调用 `javac/java failed: Picked up JAVA_TOOL_OPTIONS...`；JEP 493 工具链模块提示。没有把这些消息当作已修复，但最终 Gradle exit 0、XML 和镜像启动均已核实。

## 桌面验收进度

复用未修改的 `SqlScriptDetailsDesktopFixture`，启动本轮镜像副本，固定六条合成结果，不注册连接或提交 SQL。目录 `build/sql-overview-reset-order-desktop-20260920`，独立 `script-details-desktop-profile`；只在副本配置追加测试入口与 patch-module，不改变生产入口。原/副本 runtime/lib/modules SHA-256 均为 `BA8E35FA22CDAD1502A053B3CB108723B07C91F7D9DB6D6AB48FB6047C1BDFD4`。

使用 computer-use 原生操作成功启动镜像并从结果下拉框切换至“执行概览”。暗色宽窗初始按 1–6 顺序，新增“恢复执行顺序”禁用，其他操作与计数均可见。随后点击耗时表头返回 `failed to activate captured window`；重新选择窗口并观察得到黑屏。立即停止输入并请求用户解锁，没有通过其他自动化绕过桌面限制。

尚未完成本轮原生表头排序、鼠标/Tab+Space 恢复、组合筛选与明暗窄窗复验。上述功能有自动测试覆盖，但不将自动测试或初始截图冒充真实交互已验收。隔离窗口保留，待解锁后继续；不进行真实数据库或真实用户效率验证。

## 集成

本轮实现保留在独立分支，等待桌面补验后合回本地 main；当前未合并、未推送、未打 tag。用户原有 `.testagent/` 保持原样。`git diff --check` 通过。
