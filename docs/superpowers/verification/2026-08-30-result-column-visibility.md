# 查询结果列控制验收

日期：2026-08-30；生产/测试提交：`719685b`；worktree：`codex/release-acceptance`。

## 结论与范围

结果工具栏新增“列（可见/全部）”：逐列显示/隐藏、最后一列保护、显示全部列。同一结果的搜索和筛选保留列视图；新结果重置。只操作已加载结果，不新增数据库请求、持久化、依赖或剪贴板操作。

本增量的自动化与列控件桌面观察已完成，任务审查通过；整分支审查和本地集成状态见末节。P0.2 的安装升级、真实 Excel、弹窗键盘、全部已加载范围桌面保存及同 SHA 远端 CI 等发布门槛仍未全部完成，不据此宣称可以发布。

## 自动化证据

实施前，三个新增测试运行成功编译后在“结果工具栏必须存在列菜单”断言失败：`columnMenuGuardsLastColumnAndRestoresAllWithoutChangingRows`、`columnMenuPersistsThroughSearchAndExportsBothScopes`、`columnMenuResetsForNewResultAndIgnoresOldItems`。当次 32 tests、3 failures。

**过程偏差：** 第四个 `columnMenuPreservesItsViewAcrossDebounceAndConditionTransitions` 是实现后增加的回归测试，不在上述 RED 运行内。不能追认其为先失败后实现。任务审查指出后，实施报告已纠正；主代理接受透明保留该过程偏差，不删除测试或虚构历史运行。审查者复核当前行为无阻断缺陷。

定向结果：`SqlEditorResultFilterContractTest` 33/33；`SqlResultToolbarTest` 通过。实现者随后执行完整非 headless 强制重跑，主代理独立读取全部 XML：**138 suites、1215 tests、1212 passed、0 failures/errors、3 skipped**。

重跑方式（临时追加选项并恢复原环境）：

```powershell
$columnPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$columnPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $columnTestExit = $LASTEXITCODE
} finally {
    $env:JAVA_TOOL_OPTIONS = $columnPreviousJavaOptions
}
exit $columnTestExit
```

三项跳过：`RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle`、`SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas`、`SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas`。未启用真实数据库，跳过不算通过。既有契约测试未检查操作编译提示保留，不是本次新增警告。

| Requirement | Evidence（均在 SqlEditorResultFilterContractTest） |
| --- | --- |
| 同名列可区分、序号列排除、最后一列保护、显示全部不改行 | `columnMenuGuardsLastColumnAndRestoresAllWithoutChangingRows` |
| 隐藏、列重排、211px 列宽、降序与本地搜索共存；两种范围 CSV 内容准确 | `columnMenuPersistsThroughSearchAndExportsBothScopes` |
| 防抖实际触发及增删条件后保留列视图 | `columnMenuPreservesItsViewAcrossDebounceAndConditionTransitions` |
| 无结果禁用、零行查询可用、新结果重置、旧菜单不影响新列 | `columnMenuResetsForNewResultAndIgnoresOldItems` |
| 复制按保留后的用户列顺序，不回到初始列顺序 | `allCopyModesFollowVisibleColumnOrderAndHandleRaggedRowsAndShortcut` |

CSV 测试实际调用现有写入器并读取临时文件，仅去掉写入器原有 UTF-8 BOM 后比较确切表头与行序；不是只断言内存快照。当前范围为 `score,name / 3,Ada / 1,Ada`，全部已加载为 `score,name / 1,Ada / 3,Ada / 9,Bob`，隐藏数据均不输出。原复制测试在筛选后期待重置列顺序，与新需求冲突；现保留精确行/单元格断言，改为期望用户选择的 `CREATED_AT, NAME, SCORE`。

## 实际桌面观察

通过受支持 computer-use 控制既有 `ExportSmokeLauncher`，使用真实 `SqlEditorPane` 和三行合成结果、无数据库绑定。独占目录 `C:/Users/hetia/AppData/Local/Temp/datacube-export-smoke-7227768473704797525`；未打开真实连接、历史或凭据。

- 初始 `列（3/3）`；实际菜单点击隐藏“创建时间”后 `2/3`，再隐藏“姓名”后 `1/3`，表内保留序号和分数。
- 重新打开菜单，最后可见“分数”呈禁用状态；点击“显示全部列”恢复三列及原三行内容。
- 再次实际隐藏“创建时间”，在搜索框输入 `Ada`，防抖完成后两行可见，列数仍为 `2/3`。这是防抖后状态观察，不是输入后小于120ms立即导出的计时验证。
- 通过夹具切到紧凑窗口（配置900×620，捕获约888×614）及亮色，搜索框、列菜单和操作按钮可见且未重叠；此前深色普通窗口也通过。
- 从真实导出菜单选择 CSV，确认摘要为“2 行 · 2 列”；点击“取消”，结果仍是 Ada 两行、两可见数据列。未进入文件保存器，因此此项只证明 UI 摘要与取消保留，不冒充桌面实际保存。
- 点击“标量样例”载入新 QueryResult，搜索清除、三行及 `列（3/3）` 恢复。
- 正常关闭，`runExportSmoke` 退出码0、6分51秒（含交互等待）；最终窗口列表无 DataCube。独占目录仅有合成 `history.txt` 与 `settings.properties`，无导出文件，未触碰剪贴板。

一次主题切换调用因 screenshotId 过期失败，重新观察后按新截图重试成功；不是产品缺陷。JavaFX 无障碍树部分值滞后，结论以上述可见截图为准。classpath 夹具的 unnamed-module 提示不代表打包应用配置。未重试先前被拒绝的 Excel 路径，亦未改用其他 Windows 自动化接口。

## 审查与本地集成

- 任务审查范围 `ca3a157..719685b`，`column_controls_review`：当前行为符合设计，质量 Approved，0 Critical/Important/Minor；历史 TDD 偏差单独保留如上。
- 主代理核对生产差异、XML、实际桌面操作与退出；没有依据工具延迟或旧断言推断数据库故障。
- 整分支只读审查、本地快进合并及合并后完整回归待记录。用户已授权本地集成完成增量；不自动推送、打 tag、安装或发布。
