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
| 工具栏查找、Ctrl+F、Ctrl+H 打开前关闭真实补全弹窗，不接受候选或执行 SQL | `SqlEditorUsabilityTest.findAndReplaceDismissCompletionBeforeTakingFocus`（3 个入口） |

最终定向命令通过（14s）：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorReplaceIntegrationTest --tests com.datacube.fx.SqlEditorUsabilityTest --tests com.datacube.fx.SqlReplaceBarTest --tests com.datacube.sqleditor.SqlTextReplacementTest --tests com.datacube.fx.SqlFindBarTest --no-daemon --console=plain
```

## 开发中发现并修正

- 首次编译失败为字段前向引用；测试构造曾误用不存在的草稿单参数构造器，均已修正。
- 草稿初始化后立刻 refresh 得到 BUSY；用独立 writer 屏障再进入 FX 回调，不修改生产运行时或放宽断言。
- 480px 明暗布局真实断言失败：展开替换挤占了正文。为虚拟编辑滚动区域保留 80px 最小高度，原有“正文可用高度”断言保持并通过。
- 采用范围 multi-change 而非整段正文替换，回归确认不同物理换行、文件 identity 和撤销/重做保持正确。
- 实际桌面发现撤销后补全弹窗会覆盖新打开的替换栏；在工具栏查找及 Ctrl+F/Ctrl+H 入口显式关闭补全。新增 3 个真实 Popup 回归；首次测试在 CSS/skin 初始化前 lookup 编辑器得到 null，调整为先 applyCss/layout，再查找，原断言不变。补全与可用性定向 28 项通过（12s）。

## 全量与桌面

`clean test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain` 通过（1m 58s）。
新 XML 汇总 1,802 项，0 失败、0 错误、3 跳过，即 1,799 通过；本轮新增 29 项执行。
`git diff --check` 通过；原有 unchecked 编译提示未升级为本轮失败。

桌面弹窗修正后重新执行 `test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`，
通过（2m 3s）。最终 XML 为 1,805 项，0 失败、0 错误、3 跳过，即 1,802 通过。

桌面只使用本轮开发镜像和独立 user.home，不访问真实连接或已有 SQL。
已准备合成 `替换 验收.sql`，初始 SHA-256 为
`4F8C3E9E53DE03AD89E0DB7E3A19DBCDD7C7D7E6E11B7A3BE49A8F6B1ACC779B`。
启动验收时 Computer Use 返回 `GetCursorPos failed: 拒绝访问 (0x80070005)`，停止桌面操作并请求用户解锁。
用户确认解锁后，使用 Computer Use 完成第一轮真实窗口交互：

- 开发镜像正常启动；Ctrl+O 打开上述合成文件，未绑定连接，执行和事务入口保持禁用。
- Ctrl+H 展开替换；`replace_demo` 显示 3 处，将其替换为 `replaced_中文`。
- 第一次全部替换返回“编辑状态已变化，未应用替换，请重试”，正文未改；刷新观察后再次点击成功。未确认第一次焦点变化的来源，不将其写成执行成功，也未放宽焦点保护。
- 成功替换包括两个字符串字面量及一条注释，显示已替换 3 处和文件未保存星号。关闭查找后一次 Ctrl+Z 恢复三处原文，星号消失。
- 再次定位第一处并点击“替换当前”，只有第一行改变；关闭查找后一次 Ctrl+Z 恢复原文及 clean 状态。
- 全部替换之后直接 Esc 未关闭面板；通过可见“关闭查找”按钮正常退出面板，不宣称此次 Esc 已验收。
- 退出前合成文件的 SHA-256 与初始值完全一致；未点击保存、执行或连接入口。Alt+F4 正常退出，仅检查本轮实例消失。

功能提交 `39ca72d` 的 [Verify](https://github.com/Clownking99/data-cube/actions/runs/34214147682) 全部通过：
wrapper-validation、Ubuntu/Windows 单测、Windows linked image、Redis integration。

## 不涵盖

- 无真实数据库执行、安装升级、发布版本或用户任务耗时统计。
- 替换不是 SQL 语义重命名；注释和字符串中的字面匹配也在范围内。
- 撤销作用于内存编辑；SQL 文件需明确保存，草稿仍遵循原有本地保护机制。
