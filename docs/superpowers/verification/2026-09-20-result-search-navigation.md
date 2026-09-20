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

## 原生验收待补

使用 computer-use 技能准备新的隔离副本 `build/result-search-navigation-desktop-20260920/DataCube`；仅该副本配置注入既有合成 fixture.jar，并指定独立 `script-details-desktop-profile`。生产配置未改。启动时桌面接口返回 `GetCursorPos failed: 拒绝访问 (0x80070005)`，刷新窗口列表未发现本轮窗口，独立路径进程检查也未发现运行实例；随后停止原生操作并请求解锁。

因此本轮尚未观察原生 Ctrl+F → 输入 → Esc 的实际焦点、零匹配返回、仅结果模式与明暗窄窗，不能用 FX 自动事件及 CSS 断言代替这些结果。没有访问真实连接或执行 SQL。桌面恢复后从该隔离副本继续补验，不必重做已经成功的自动测试。
