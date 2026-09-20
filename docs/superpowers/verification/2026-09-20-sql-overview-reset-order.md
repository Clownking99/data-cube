# 执行概览恢复执行顺序验收

当前状态：实现已集成本地 main；2026-09-20 后续桌面恢复可用，本文所列恢复顺序原生交互已补验完成。此前黑屏记录保留为历史，不再是本功能的待验项。未进行真实数据库验证、推送、打 tag 或正式发布。

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

当时尚未完成原生表头排序、鼠标/Tab+Space 恢复、组合筛选与明暗窄窗复验。上述功能有自动测试覆盖，但不将自动测试或初始截图冒充真实交互已验收。隔离窗口保留，等待解锁；后续补验见下文。

## 集成

首轮实现提交 `f1a8882`，当时保留在独立分支等待桌面补验，没有合并、推送或打 tag。

2026-09-20 续轮用户要求继续推进。重新选择既有隔离窗口，截图仍黑屏；按 computer-use 安全要求停止输入，不尝试其他方式操作锁定桌面，再次请求用户解锁。原生表头/恢复/键盘/主题验收仍未完成。

为推进已通过全量验证的实现，本轮将本地集成与原生/发布验收分开记录。确认 main 为 `8e0fc8a`、无跟踪或暂存修改后，快进到 `f1a8882`；保留 `.testagent/` 未跟踪目录，没有读取或修改它。合并后的 main 重新运行五类定向测试和开发打包：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.fx.SqlOverviewResetOrderTest --tests com.datacube.fx.SqlOverviewDurationSortTest --tests com.datacube.fx.SqlOverviewSearchTest --tests com.datacube.fx.SqlOverviewFailureFilterTest --tests com.datacube.fx.SqlScriptDetailsIntegrationTest jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

59 秒、exit 0 / BUILD SUCCESSFUL，XML 确认 129 项全部通过，无失败/错误/跳过。复核 worktree 的上一轮全量 XML 仍为 3,214 通过、3 live 跳过；没有把这次定向重跑称为新的全量测试。

从两处镜像提取 `com.datacube`，按相对路径逐文件比较 SHA-256：均为 827 个文件、812 个 class，0 差异、0 Fixture。main 生产启动配置仍使用原入口，SHA-256 为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。证明本地集成没有混入测试入口或改变已验证生产模块，不代表原生交互验证已完成。

后续仅补记录的文档提交 `5975647` 随分支快进 main；生产代码未再变化。`git diff --check` 通过。当时仍未推送、未打 tag、未正式发布，桌面待补验。

## 原生补验完成（2026-09-20）

本次继续时，重新激活既有隔离 DataCube 窗口后桌面可用。按 computer-use 技能使用原生截图、鼠标和键盘逐步检查，不通过其他接口绕过窗口限制。仍使用同一合成 fixture，不注册连接、不执行 SQL、不读取真实用户配置。

| 原生操作 | 观察结果 |
| --- | --- |
| 初始概览与耗时表头三次点击 | 初始 1–6 且恢复按钮禁用；升序为 4、3、1、2、6、5，降序为 5、6、2、1、3、4；第三次取消箭头后仍为降序，恢复按钮保持可用 |
| 降序选中第 6 条后点击恢复 | 恢复 1–6，仍选中第 6 条，恢复按钮禁用；没有改变批次统计或编辑器文本 |
| 再次升序，开启“仅看异常”并输入 `select` | 显示 4、6、5，计数 3 / 6，仍保留第 6 条选择 |
| 从关键词框两次 Shift+Tab，空格触发恢复 | 焦点依次经过异常复选框和恢复按钮；恢复为 4、5、6，关键词、复选框、3 / 6 计数及第 6 条选择保留，按钮禁用 |
| 明暗主题与宽窄窗口 | fixture 设置的 640 / 980 宽度下检查两种主题；概览操作和筛选自然换行、入口可见，窄窗表格保留滚动条。与自动测试的 480 / 880 宽度分开记录 |
| 恢复后点击“查看结果” | 下拉框与结果标题均显示第 6 条取消结果，正文为 fixture 的取消说明，确认没有跳到当前行号对应的其他语句 |
| 点击窗口关闭按钮 | 窗口从原生窗口列表消失，指定验收可执行文件的进程已退出，无需强制终止 |

退出后独立 profile 顶层仅有 `settings.properties` 与 `synthetic-details.sql`，另有 JavaFX 缓存目录。合成 SQL 的 SHA-256 仍为 `3E0C0DFB331C462DC9B0325DA390A8B8381B5C7722F8260C455F3210DA07BDF4`，与验收前相同。再次核对原/副本 runtime/lib/modules 仍为上文 `BA8E35FA...`，main 生产启动配置仍为 `AD4F0A4A...`；测试入口仅位于验收副本。

本次仅更新验收、设计和路线图文档，未改变生产代码或测试，不把既有 129 项定向和 3,214 项全量通过数称为新一轮执行。此前其他功能尚未覆盖的待验项、真实用户效率、live 数据库和远端发布不因本次补验而自动完成。
