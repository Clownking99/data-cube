# 查询结果搜索键盘连续性验收

基线 main `026c6e4`，分支 `codex/sql-result-search-navigation`。范围是既有本地结果搜索的快捷键接线与主题提示，不增加查询、搜索算法、后台任务或独立快捷键配置。`sql.find` 持久化键和默认 Ctrl+F 不变，只扩展设置说明；按事件来源区分结果区域与 SQL 编辑区。

## 自动验证

使用 code-testing-agent 技能的定向行为检查，沿用项目 JUnit、FX 线程和离线合成 fixture。未读写真实连接、SQL 文件、历史、凭据或剪贴板，未读取用户 `.testagent/`。

| Requirement | Evidence |
| --- | --- |
| 表格、表头子节点、工具栏、搜索框与仅结果模式路由正确；不提交待处理搜索、不改筛选/排序/隐藏列/选区/SQL/撤销 | `findFromResultAreaSelectsPendingSearchWithoutChangingResultOrEditor`（5 组） |
| Esc 提交本地文字并返回、保留列条件；匹配/零匹配/空文字，不代选行，仍可再次进入搜索 | `escapeCommitsOnlyLocalSearchAndReturnsWithoutInventingSelection`（3 组） |
| 空查询仍可搜索；SQL 编辑区和显式 SQL 查找按钮原行为不变 | `emptyQueryStillOffersSearchAndSqlEditorAndButtonStillFindSql` |
| 概览、更新、错误、计划保留原 SQL 查找 | `nonQueryResultsRetainExistingSqlFindRoute`（4 组） |
| 改绑即时生效，旧键不拦截；带修饰键 Esc 不提交或离开搜索 | `liveRebindingWorksAndModifiedEscapeDoesNotSubmitOrLeaveSearch` |
| 忙碌、运行、资源/任务/界面关闭、控件禁用、准入/队列停止拒绝本次按键转移焦点或提交 | `blockedResultShortcutsDoNotCommitOrStealFocus`（9 组） |
| 明暗主题切换、聚焦前后提示色与已有语义色一致 | `searchPromptRemainsReadableAcrossFocusAndThemeChanges`（2 组） |

先记录设计并实现，再执行回归；不称为红绿 TDD。首轮五类定向命令 39 秒、exit 0 / BUILD SUCCESSFUL，100 项全部通过（新类 25、布局集成 26、工具栏 22、工具栏布局 7、浏览菜单 20），无失败、错误或跳过。

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.fx.SqlResultSearchNavigationTest --tests com.datacube.fx.SqlPanelLayoutIntegrationTest --tests com.datacube.fx.SqlResultToolbarTest --tests com.datacube.fx.SqlResultToolbarLayoutTest --tests com.datacube.fx.SqlResultViewMenuTest --no-daemon --console=plain
```

自审核对事件父链、查询类型、原快捷键匹配、零匹配可用性、状态复核与次级可观察状态。没有引入跨窗口监听；待处理搜索沿用原防抖语义，阻塞测试证明的是本次快捷键不主动提交，不扩大为改变原防抖任务行为。未进行独立外部审查。

## 全量与镜像

同一 worktree 执行以下命令，3 分 20 秒、exit 0 / BUILD SUCCESSFUL。JUnit XML 核实 3,285 项中 3,282 通过、3 个既有 live 跳过，无失败/错误；buildSrc 的 `IcoGeneratorTest` 8 项通过。

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

保留既有 unchecked 编译提示、JAVA_TOOL_OPTIONS 与插件辅助探测的 javac/java failed 文本、JEP 493 提示；任务最终成功，不称这些警告已修复。版本只用于本地开发镜像，没有修改产品版本或正式发布。

- 生产配置 SHA-256：`AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。
- worktree 生产模块 SHA-256：`E0B0D8D0C8EB0611D4FDE0D7232F6FCA2533FD3AAAEDFB50C45A3AAC48D91A6A`。

## 原生验收

使用 computer-use 技能准备新的隔离副本 `build/result-search-navigation-desktop-20260920/DataCube`；仅该副本配置注入既有合成 fixture.jar，并指定独立 `script-details-desktop-profile`。生产配置未改。启动时桌面接口返回 `GetCursorPos failed: 拒绝访问 (0x80070005)`，刷新窗口列表未发现本轮窗口，独立路径进程检查也未发现运行实例；随后停止原生操作并请求解锁。

用户随后确认桌面已解锁，本轮在同一隔离副本重新启动成功，完成以下补验：

- 切换合成语句 #3 的两行结果，点击 message 单元格后原生 Ctrl+F 聚焦结果搜索；单元格内容没有带入，SQL 查找栏没有打开。
- 输入 `second` 显示 1/2 行；Esc 后搜索词保留、焦点边框返回表格，无新增选中高亮。再次 Ctrl+F 全选 `second`。
- 替换为 `absent` 显示 0/2 行；Esc 后仍能 Ctrl+F 返回搜索并全选词。Backspace 清空、Esc 后恢复两行。
- 切换“仅看结果”，从表格 Ctrl+F 聚焦搜索，SQL 编辑区保持隐藏。
- 暗色宽窗及窄窗、亮色窄窗下提示可读，搜索框和换行工具栏可见；亮色聚焦提示仍可读。
- 顶部显式“查找”按钮从仅结果模式打开 SQL 查找；Esc 关闭后点击 SQL 编辑区再 Ctrl+F，仍打开 SQL 查找，不串到结果搜索。
- Alt+F4 正常退出；后续窗口列表及独立路径进程检查均无本轮实例。合成 SQL 文件前后 SHA-256 一致：`3E0C0DFB331C462DC9B0325DA390A8B8381B5C7722F8260C455F3210DA07BDF4`。

缩窗后一次截图缓存失效（unknown screenshotId），重新获取截图并重试一次后成功。辅助功能 focused_element 曾显示旧 Schema 输入框，与可见结果搜索焦点不一致；以当前截图和随后的实际搜索输入为依据，不沿用过期元素。没有访问真实连接、SQL 文件、历史、凭据或剪贴板，也未执行 SQL。原生验证不扩展为用户效率研究；即时防抖提交、改绑与关闭屏障的精细边界仍由上述自动回归证明。

## main 集成复验

实现 `06f0a14` 从 `026c6e4` 快进合入本地 main。合并后执行同样五类定向测试并附加 `jpackageImage -PappVersion=0.0.0`，1 分 17 秒、exit 0 / BUILD SUCCESSFUL，XML 核实 100 项全部通过，无跳过。

生产模块提取到独立 `build/result-search-navigation-module-check-20260920` 比较：两边均 828 文件、813 class，813 class 全部逐字节相同，无 Fixture 类；唯一文件差异为 `theme-base.css` 的 CRLF/LF，规范化换行后内容完全一致，其余 827 文件逐字节一致。main 模块 SHA-256 为 `60D718D81933C04B0DE085A562CF69009C15A2FE98BC048A6A2ACE26F4C8B067`，生产配置哈希仍与上述相同。未把整模块哈希不同误称为整包逐字节一致。

本轮本地代码、自动回归、开发镜像与隔离原生验收完成；未推送、未打 tag、未发布。用户已有 `.testagent/` 未触碰。
