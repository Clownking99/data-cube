# 执行详情内查找验收

基线 main `7d5b1df`，分支 `codex/sql-detail-find`；[设计](../specs/2026-09-19-sql-detail-find.md)。仅合成结果、隔离源文件与零网络探针，不读取用户真实配置/SQL 或 `.testagent/`。

## 行为证据

| 需求 | 回归证据 |
| --- | --- |
| 范围独立、保留查找条件、切换不跳选区、重新计算计数 | `SqlScriptDetailFindTest.switchingScopeKeepsQueryOptionsAndSelectionsButRecomputesMatches`：双向循环、大小写、脱离旧正文监听再切回 |
| 查询/更新仅 SQL；失败/超时/取消可查错误；不搜索标签/结果数据 | `resultKindsOfferOnlyExistingScopesAndDoNotSearchMetadataOrRows` 五类结果 |
| Ctrl+F 跟随正文焦点，Enter/F3 与反向定位使用明确范围 | `shortcutsSelectFocusedBodyButNavigationUsesVisibleScope`；附加修饰键不误导航，正文保持只读 |
| 输入上限、清空和恢复 | `queryLimitRejectsWholeInputAndRecoversOnEitherScope`：1,023/1,024/1,025，两个范围 |
| 不搜索截断尾部，匹配上限有提示 | `boundedSnapshotExcludesTailAndMatchLimitIsExplicit`：SQL 和错误两范围，16,384 字段、10,000 匹配、首尾循环 |
| 字面正则字符、空格、Unicode 及换行偏移正确 | `literalWhitespaceUnicodeAndNormalizedNewlinesUseShownOffsets` |
| 关闭清理独立于 onHidden，旧动作不能继续定位，新窗口重置 | `hiddenCleanupSurvivesOwnerCallbackAndRejectsOldActions`：Esc/关闭按钮/调用方关闭 |
| 明暗窄窗控件和正文可用 | `controlsAndBodiesFitNarrowAndWideThemes`：480/880，dark/light |
| 焦点留在查找框时匹配高亮可见，空查询提示不透明 | `nonFocusedMatchesAndFocusedEmptyPromptRemainVisible`：明暗两主题，两个正文均检查实际 CSS 求值和选区 |
| 真实入口集成不改变编辑器/选区/撤销/文件/筛选/导出或连接 | 扩展 `SqlBatchDetailsIntegrationTest.directDetailsUseReturnedOutcomeAndPreserveEditorAndResult` 五类结果；概览排序入口扩展 `SqlScriptDetailsIntegrationTest.sortedRowOpensOriginalSqlAndFullErrorWithoutChangingFileEditorOrUndo` |
| 新结果/忙碌/关闭等控制器路径清理查找 | 扩展 `SqlBatchDetailsIntegrationTest.changingResultOrLifecycleClosesOldDetails` 十种生命周期 |

## 自动验证

- RED：新增 `SqlScriptDetailFindTest` 20 项全部因缺少查找控件失败，28 秒 exit 1，无编译错误。
- 最小实现修改 `SqlScriptDetailsDialog` 与复用的 `ResultCellFindBar`；不改执行器/报告/SQL 编辑器。新查找类、详情单元/集成、批次直接详情及原单元格/行正文查找六类定向回归：23 秒 exit 0。
- 初次全量和开发镜像：2 分 54 秒 exit 0，2,973 通过、3 既有 live 跳过；buildSrc 8 项通过。随后补编译纯合成桌面 fixture，6 秒 exit 0。
- 桌面发现原 TextArea 非焦点选区不可见、聚焦空查找提示透明。扩展 `theme-base.css` 既有只读查找规则，限定新控件 ID，不改变其他控件行为。新增两主题视觉回归先 RED（11 秒 exit 1），修复后查找类 22 项全通过（10 秒 exit 0）。该发现要求重做最终全量与镜像，不把初次构建当最终交付。

- 最终运行 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`、`gradlew.bat clean :buildSrc:test test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`：3 分 1 秒 exit 0，主测试 XML 2,978 项、2,975 通过、3 既有 live 跳过、0 failures/errors；buildSrc 8 项本轮已通过，此次 up-to-date。沿用已有 unchecked 和 JAVA_TOOL_OPTIONS/JEP 493 打包提示，BUILD SUCCESSFUL。

## 最终隔离桌面与镜像

复制最终生产镜像为 `build/detail-find-desktop-image`，仅在副本 patch `SqlScriptDetailFindDesktopFixture`；独立 `build/script-detail-find-profile`。直接展示真实 `SqlScriptDetailsDialog` 的合成快照，没有连接管理器、SQL 文件、执行器或剪贴板访问。

- 暗色宽窗：SQL/错误从首行显示，空查找框提示在 Ctrl+F 聚焦后仍可见；输入 alpha 只显示 SQL 匹配 3 处，不主动跳转。
- Enter 定位 SQL 第一处，F3 滚动到 80 行合成内容之后的第二处；焦点保留查找框，紫色匹配高亮清晰可见。
- 点击错误正文再 Ctrl+F，范围自动变为“错误信息”，查找词 alpha 保留并选中，计数为 2，SQL 选区/滚动位置保留。
- Enter 定位错误末端的 ALPHA；Shift+F3 返回首处，Shift+Enter 从首处循环回末端，错误正文随之滚动，高亮可见。
- 暗色和亮色 480 宽窄窗：范围、查找框、前后按钮、大小写、计数和两块正文均可用，匹配与提示清晰。鼠标选择 SQL 范围，原词/选区保留、计数恢复 3；勾选大小写后计数为 2，鼠标“下一个”定位小写 alpha。
- 原生 Esc 正常关闭；随后窗口列表不含验收实例、进程数为 0，profile 仅主题设置和 JavaFX 缓存。没有真实用户数据或系统剪贴板操作。
- 桌面为隔离真实 Dialog，不冒充完整 AppShell 数据库流程；实际批次/概览入口、忙碌/关闭和工作区不变性由集成回归覆盖。

最终开发镜像应用模块经 `jimage extract` 验证为 824 文件、809 class，零 Fixture 类。正式 `DataCube.cfg` 保持 `DataCubeFx` 入口，无 patch/profile 参数；SHA-256 为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。验收副本不交付。

## 本地 main 集成

- 实现提交 `7e7e88d` 从 `7d5b1df` 快进到 main；合并前后 tracked 工作区干净，没有覆盖用户改动。
- main 八类定向回归：`SqlScriptDetailFindTest`、`SqlScriptDetailsDialogTest`、`SqlBatchDetailsIntegrationTest`、`SqlScriptDetailsIntegrationTest`、`ResultCellFindTest`、`ResultRowTextFindTest`、`SqlBatchFailureNavigationTest`、`SqlEditorResultFilterContractTest`。XML 172 项全部通过，0 failures/errors/skipped；与 `jpackageImage -PappVersion=0.0.0` 同次运行，1 分 8 秒 exit 0。
- main 与 worktree 最终开发镜像应用模块均为 824 文件、809 class，零 Fixture 类。逐文件 SHA-256 比较有 823 个完全相同（包含全部 class）；唯一差异是 `theme-base.css` 换行：worktree 94 CRLF + 2 LF，main 96 CRLF，规范化 CRLF 后正文逐字符完全相同。main 的两主题 CSS 求值回归亦通过，不把此结果写成 824 文件字节一致。
- 两份 `DataCube.cfg` 哈希与上文一致，正常入口且无 fixture/profile 参数。`git diff --check` 通过；本段记录另作 docs 提交，不修改生产代码。

不把合成验收扩大为 live 数据库、真实用户效率或远端发布。本轮不推送、不打 tag。
