# SQL 执行范围与编辑位置验证

## 范围与实现

基线 `main` / `df36e2c`，独立 `codex/sql-execution-scope`。
只补充下一次执行范围及位置提示，执行和计划沿用非空白选区优先、否则全文的既有行为。
`SqlExecutionRange` 与旧 `trim()` 判定等价；不会在每次选区通知中复制选区正文。
`SqlEditorScopeBar` 是只读状态组件，标签的执行按钮绑定其文案属性；不改变 disabled 状态。
状态监听在构造失败及 FX 关闭时释放，快捷键订阅使用可安全移除的列表，后台通知转回 FX。

测试按具体行为组织，不以数量替代正确性；数据库执行验收使用既有模拟 Connection/SqlRunner，
只记录合成 SQL，不访问真实数据库或已有连接、历史、文件；根目录 `.testagent/` 未读取或改动。

## 要求与证据

| 要求 | 精确测试证据 |
| --- | --- |
| 保留原全文/选区规则，空、反向、空白、多行、Unicode 与未裁剪文本 | `SqlExecutionRangeTest.preservesTheExistingSelectionOrAllContract`（10 个分区） |
| 非法偏移、空输入引用、非法范围不能成为可执行选择 | `SqlExecutionRangeTest.invalidOffsetsCannotBecomeAValidExecution` / `nullOrMalformedRangesAreRejected` |
| 行列、UTF-16 选区长度、正文缩短后准确更新，且不移动选区或产生编辑 | `SqlEditorScopeBarTest.caretSelectionAndTextChangesStayAccurateWithoutEditingOrMovingSelection` |
| 连续改绑和恢复默认、两个编辑器选区独立、关闭后解绑 | `SqlEditorScopeBarTest.rebindingUpdatesBothEditorsWithoutSharingSelectionsAndDetachesOnClose` |
| 后台快捷键回调晚于关闭不得复活状态栏 | `SqlEditorScopeBarTest.queuedBackgroundShortcutRefreshCannotReviveAClosedBar` |
| 完整编辑器文案与消费文本一致、文件 dirty/字节不变、无绑定执行仍禁用 | `SqlEditorUsabilityTest.visibleScopeMatchesTheExecutionTextWithoutChangingFileState` |
| 真实查找导航产生选区，关闭查找仍保留选中执行提示，折叠选区恢复全文 | `SqlFindBarTest.findingAPhraseUpdatesExecutionScopeAndClosingFindDoesNotClearTheSelection` |
| 按钮/改绑快捷键/执行计划实际传给模拟 runner 的 SQL 匹配提示，计划仍取第一句 | `SqlEditorResultFilterContractTest.visibleScopeIsTheTextSubmittedByExecuteShortcutAndExplain`（6 个分区） |
| 运行后改变选区不会改已捕获 SQL，也不会重新启用执行按钮；旧快捷键不再准入 | 同上 execute/shortcut 分区 |
| 明暗 480/640/880px 下状态条不覆盖正文、不超出容器 | `SqlEditorUsabilityTest.openFindBarWrapsWithoutClippingControlsOrCoveringTheEditor`（扩展既有 6 个分区） |
| 连接为空/Redis 时按钮及事件均不创建会话 | `SqlEditorConnectionGuidanceTest.missingAndRedisPagesBlockButtonsAndShortcutWithoutOpeningSessions`（原测试改为稳定 ID 定位及新文案断言） |

最终定向验证（20s，58 项全部通过）：

```powershell
.\gradlew.bat test --tests com.datacube.sqleditor.SqlExecutionRangeTest --tests com.datacube.fx.SqlEditorScopeBarTest --tests com.datacube.fx.SqlEditorUsabilityTest --tests com.datacube.fx.SqlFindBarTest --tests com.datacube.fx.SqlEditorConnectionGuidanceTest --tests 'com.datacube.fx.SqlEditorResultFilterContractTest.visibleScopeIsTheTextSubmittedByExecuteShortcutAndExplain' --no-daemon --console=plain
```

## 开发与审查记录

- 新测试初次引用不存在的 `document()` 方法导致编译失败，改为沿用既有测试的字段观察方式，没有为测试新增生产访问器。
- 改绑断言曾硬编码 `Ctrl+Enter`，本机 JavaFX 实际展示 `Ctrl+↵`。改为对确定的 KeyCombination 使用平台展示文本，仍完整校验按钮前缀、组合键与多次改绑，不放宽成 contains。
- 核对构建顺序：编辑器先于工具栏构建，因此使用只读文案属性绑定，而非在状态组件初始化时依赖尚未创建的按钮。
- 检查执行与计划入口均消费同一范围帮助类；原风险确认、固定连接、会话排队、运行快照和文件监听没有被替换。
- 当前未声称真实数据库、安装升级、用户耗时、发布或远端 CI 已验证。

## 完整回归与开发镜像

在本 worktree 新建独占临时目录，将其 Windows 短路径通过 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=...` 传给构建，运行：

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

2m39s，exit 0；XML 汇总 1,831 项，1,828 通过、3 项原有 live 跳过、0 failures/errors。
`jlink`、`jpackageImage` 实际执行，生成开发版 `0.0.0` 镜像；不是新发布版本。
已有测试 unchecked 提示保留，不是新增编译失败。

完整构建后仅加强后台通知测试：用 CompletableFuture 传播线程异常，并确认 F6 已实际写入设置，
避免仅观察线程结束而误判成功。没有修改生产代码；单独重跑 `SqlEditorScopeBarTest`，3 项通过、10s、exit 0。

## 实际 Windows 桌面验收

使用 Computer Use 技能控制本轮构建的 `build/jpackage/DataCube/DataCube.exe`，
仅对忽略目录内的开发镜像配置 `-Duser.home=.../build/desktop-profile`，不修改真实配置。
该隔离配置没有保存连接；只打开本轮生成的 `build/desktop-fixtures/执行范围 验收.sql`：
第一行 `select 1;`，第二行四个空格，第三行 `select 'scope_demo';`，文件末尾换行。

- 打开文件后显示“执行全部 (F5)”，标签没有未保存星号，未绑定连接提示保留，执行及计划仍禁用。
- `Ctrl+Home` 显示行 1 / 列 1；`Shift+End` 选中首行，按钮显示“执行选中 (F5)”，底栏显示行 1 / 列 10 / 选中 9 / 选中内容。
- 再次 `Ctrl+Home` 取消选区，按钮及底栏恢复全部 SQL；移到第二行并 `Shift+End` 选中四个空格，显示行 2 / 列 5 / 选中 4，明确“全部 SQL（选区仅含空白）”。
- 拖动左右分隔条，右侧编辑区收窄至约 480px；暗色及亮色主题下底栏完整可读，正文仍可见，工具栏换行没有覆盖 SQL。
- 从编辑器工具栏打开查找，输入 `scope_demo` 并按 Enter 导航，显示 1/1、行 3 / 列 19 / 选中 10，按钮同步“执行选中”。
- Escape 关闭查找后仍保留匹配选区和“执行选中”；Right 折叠选区后恢复“执行全部”。窄栏查找开启/关闭均未遮挡底栏或正文。
- 正常 Alt+F4 退出后，重新列举目标镜像窗口为空。未连接数据库、点击执行或计划、保存 SQL 或改动真实用户数据。

文件验收前后 SHA-256 相同：
`FD6BB0A298D81082897CF77963D3D0C542ABFFEBCC0EA2D31E4D71D0DA399F01`。
真实 UI 只验证提示与布局；SQL 准入、快捷键改绑、执行快照和计划首句规则由上表模拟 runner 测试覆盖，
不将桌面离线操作描述为真实数据库执行验证。

## 本地整合边界

合并前重新核对根目录仍为 `main` / `df36e2c`，仅有原有未跟踪 `.testagent/`；不读取、不暂存该目录。
本轮变更在独立分支显式暂存、提交后快进合并本地 main；保留 worktree，不清理其他分支或目录。
本轮不推送、不打 tag、不发布，也没有新远端 CI 结果。
