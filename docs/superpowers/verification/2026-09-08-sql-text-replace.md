# 当前 SQL 替换验证

## 交付边界

基线 `ea9c867`；新增 Ctrl+H（可改绑）及查找栏展开入口，当前精确选中匹配/完整匹配集替换。
字面匹配、大小写、数量上限沿用查找；无数据库、文件保存、跨文件或正则执行入口。
后台只准备候选；FX 核对代次/正文/焦点/关闭与编辑门禁，再用 RichTextFX 单个 multi-change
按逆序原始范围提交，前后 preventMerge。未改范围不进入文件文本变更流，保留混合换行。

按测试技能组织边界证据，没有按测试数量代替行为验证。测试使用合成 SQL、独立临时文件/草稿和探针。

## 行为证据

| 要求 | 精确测试证据 |
| --- | --- |
| 字面符号、Unicode、大小写组合、非重叠删除 | `SqlTextReplacementTest.literalUnicodeAndCaseInsensitiveMatchesPreserveUnmatchedText` / `emptyReplacementDeletesNonOverlappingMatches` |
| 未匹配/内容相同不生成编辑，混合大小写只统计实际变化 | `SqlTextReplacementTest.noMatchesAndIdenticalReplacementProduceNoChange` |
| 完整 10000 处允许，超限不得部分替换 | `SqlTextReplacementTest.completeLimitIsAllowedButTruncatedSearchCannotPartiallyReplace` |
| 替换及输出容量边界、非法范围、线程取消 | `SqlTextReplacementTest.replacementAndOutputLimitsRejectExpansionBeforeAllocation` / `malformedRangesCannotCorruptTheCandidate` / `interruptedWorkCannotProduceAnEdit` |
| 当前替换要求精确匹配，字面内容与撤销/重做 | `SqlReplaceBarTest.replaceCurrentRequiresAnExactMatchAndIsAnIndependentUndoUnit` |
| 全部替换一次撤销且隔离前后键入 | `SqlReplaceBarTest.replaceAllIsOneUndoSeparatedFromTypingBeforeAndAfter` |
| 空替换删除，相同内容不新增撤销项 | `SqlReplaceBarTest.emptyReplacementDeletesAndIdenticalReplacementAddsNoUndo` |
| 文本/查找/大小写/替换/选区/隐藏/收起/关闭/冻结/编辑禁用/解冻/焦点/Scene 改变拒绝迟到结果 | `SqlReplaceBarTest.staleCandidatesNeverOverwriteNewEditorState`（13 个分区） |
| 无匹配、UI 超限与失败重试，异常详情不泄露 | `SqlReplaceBarTest.incompleteMatchesAndOversizedInputCannotSchedulePartialReplacement` / `noMatchesAndFailedOrRejectedWorkLeaveSqlIntactAndPermitRetry` |
| 文件身份、混合物理换行、dirty、撤销 clean、只读数据库配置允许离线编辑、文件字节不变 | `SqlEditorReplaceIntegrationTest.replacementPreservesPhysicalLineEndingsAndFileIdentityWhileUndoRestoresCleanState` |
| 草稿保存替换后正文，SQL 文件仍不写入，零数据库访问 | `SqlEditorReplaceIntegrationTest.replacementUsesExistingDraftCheckpointWithoutSavingTheSqlFile` |
| 真实编辑器关闭准入拒绝旧按钮 | `SqlEditorReplaceIntegrationTest.closingAdmissionBlocksReplacementEvenIfOldButtonsRemainVisible` |
| 快捷键改绑、历史快捷键不变、展开不产生编辑 | `SqlEditorUsabilityTest.replaceEntryAndReboundShortcutRemainSeparateFromHistoryAndExecution` |
| 明暗主题及 480/640/880px 宽度，替换控件不覆盖正文 | `SqlEditorUsabilityTest.openFindBarWrapsWithoutClippingControlsOrCoveringTheEditor`（扩展既有 6 个布局分区） |

最终定向命令通过（14s）：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorReplaceIntegrationTest --tests com.datacube.fx.SqlEditorUsabilityTest --tests com.datacube.fx.SqlReplaceBarTest --tests com.datacube.sqleditor.SqlTextReplacementTest --tests com.datacube.fx.SqlFindBarTest --no-daemon --console=plain
```

## 开发中发现并修正

- 首次编译失败为字段前向引用；测试构造曾误用不存在的草稿单参数构造器，均已修正。
- 草稿初始化后立刻 refresh 得到 BUSY；用独立 writer 屏障再进入 FX 回调，不修改生产运行时或放宽断言。
- 480px 明暗布局真实断言失败：展开替换挤占了正文。为虚拟编辑滚动区域保留 80px 最小高度，原有“正文可用高度”断言保持并通过。
- 采用范围 multi-change 而非整段正文替换，回归确认不同物理换行、文件 identity 和撤销/重做保持正确。

## 全量与桌面

`clean test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain` 通过（1m 58s）。
新 XML 汇总 1,802 项，0 失败、0 错误、3 跳过，即 1,799 通过；本轮新增 29 项执行。
`git diff --check` 通过；原有 unchecked 编译提示未升级为本轮失败。

桌面只使用本轮开发镜像和独立 user.home，不访问真实连接或已有 SQL。
已准备合成 `替换 验收.sql`，初始 SHA-256 为
`4F8C3E9E53DE03AD89E0DB7E3A19DBCDD7C7D7E6E11B7A3BE49A8F6B1ACC779B`。
启动验收时 Computer Use 返回 `GetCursorPos failed: 拒绝访问 (0x80070005)`，停止桌面操作并请求用户解锁。
当前不能把自动化事件与布局回归称为实际窗口交互验收；不宣称已完成桌面 Ctrl+H、替换、撤销或启动检查。

## 不涵盖

- 无真实数据库执行、安装升级、发布版本或用户任务耗时统计。
- 替换不是 SQL 语义重命名；注释和字符串中的字面匹配也在范围内。
- 撤销作用于内存编辑；SQL 文件需明确保存，草稿仍遵循原有本地保护机制。
