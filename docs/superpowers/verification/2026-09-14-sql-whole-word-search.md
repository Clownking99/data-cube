# SQL 整词查找与替换验收

## 范围

独立 `codex/sql-whole-word-search`，基线 main `d98ed90`；设计见[范围说明](../specs/2026-09-14-sql-whole-word-search.md)。
生产变更仅 SqlTextSearch 的可选标识符边界过滤与 SqlFindBar 的局部开关，不修改执行、连接、文件或草稿格式。
`.testagent/` 未读取、修改、暂存。未新增依赖、持久化、SQL 执行或自动保存源文件。

## 需求与证据

| 需求 | 具体测试 |
| --- | --- |
| 独立词/大小写/默认兼容 | `SqlWholeWordSearchTest.independentIdentifiersKeepUtf16OffsetsAndCaseOption` |
| 两侧 SQL/Unicode 连续字符 | `identifierNeighborsOnEitherSideAreNotBoundaries`、`supplementaryCharactersAndCombiningMarksKeepOffsetsAndAreNotSplit` |
| 标点、注释/字符串仍按文字匹配 | `punctuationAndQuotesAreBoundariesButCommentsAndStringsAreNotExcluded`、`phraseAndSymbolsStayLiteralAndWhitespaceIsNotTrimmed` |
| 拒绝候选不吞重叠有效词组 | `rejectedOverlappingPhraseDoesNotHideLaterWholeMatch` |
| 仅有效匹配计入限额、原查询限额/取消 | `onlyAcceptedWholeMatchesConsumeTheLimit`、`queryBoundsEmptyAndCancellationStillApply` |
| 切换不移选区、定位与计数一致、循环 | `SqlFindBarTest.wholeWordsShareCountsAndNavigationWithoutMovingSelectionOnToggle` |
| 选项与大小写组合、迟到扫描失效、标签隔离 | `wholeWordToggleInvalidatesLateCallbacksAndCombinesWithCaseWithoutLeakingAcrossEditors` |
| 当前与全部替换范围、单步撤销 | `SqlReplaceBarTest.wholeWordReplacementChangesOnlyCountedMatchesAndUndoRestoresAllText` |
| 零匹配拒绝/旧替换取消且迟到无效 | `switchingToWholeWordsRejectsOldReplacementAndZeroMatchesCannotEdit`、`staleCandidatesNeverOverwriteNewEditorState[words]` |
| 实际 Pane/快捷键/文件脏标记/草稿/离线 | `SqlEditorWholeWordIntegrationTest.wholeWordReplaceUsesRealPaneFileAndDraftWithoutDatabaseOrSourceWrites` |
| 明暗 480/640/880 窄宽栏布局 | 扩展 `SqlEditorUsabilityTest.openFindBarWrapsWithoutClippingControlsOrCoveringTheEditor`，逐项检查整词控件边界与可读宽度 |

测试技能用于按现有 JUnit/FX 约定组织边界及异步状态验证；按项目约定直接实现，不增加通用流程工件。

## 自动验证进度

所有命令均在功能 worktree，设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，Gradle 使用 `--no-daemon --console=plain`。

1. 新入口行为 RED：只跑 `SqlFindBarTest.wholeWordsShareCountsAndNavigationWithoutMovingSelectionOnToggle`，39 秒 exit 1，1 项失败；旧代码确实缺少整词入口，不是构建失败。
2. 范围、旧字面搜索/替换、查找条、替换条定向：13 秒 exit 0。
3. 加入实际 Pane/布局后 98 项中 1 项失败，17 秒 exit 1：新测试误把草稿正文期望为文件物理 CRLF。
   源码 `SqlEditorPane.bindDraft` 明确捕获 CodeArea 逻辑文本；修正测试为 LF，文件物理换行仍单独逐字断言，未改生产草稿语义。
4. 修正后同组 98 项全部通过，15 秒 exit 0；包括真实异步替换、文件/草稿与四个数据库副作用探针为零。
5. 全量 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`：2 分 48 秒 exit 0。
   XML 2522 项 / 2519 通过 / 3 既有 live 跳过 / 0 failures/errors；buildSrc 8 项通过。
   新增 26 项（纯边界 20、查找条 2、替换条 3、真实 Pane 1）；6 个既有布局用例扩展整词控件断言。
   jlink / jpackageImage 均实际执行。保留原 unchecked、JAVA_TOOL_OPTIONS 工具探测及 JEP 493 提示，不称为无警告。
6. 生产镜像仍以 `com.datacube/com.datacube.DataCubeFx` 启动；jimage 含 SqlTextSearch/SqlFindBar，
   不含 SqlWholeWordDesktopFixture 或 DraftConnectionProbe。运行时 modules SHA-256：
   `77D63F0E2BF0D19F39EDFDA231518B1AC2A556F4810D52E84ED3D3D14DE2AD07`。

## 桌面与集成

- 使用最新生产镜像副本 `build/whole-word-fixture-image/DataCube`，仅 patch 单个合成夹具类，
  `user.home` 为独立 `build/whole-word-desktop-profile`；无注册连接，不启动应用更新服务。
  合成文件为包含 `id, user_id, id2, ID, t.id` 与注释内 `id` 的混合 CRLF/LF 脚本。
- 原生 Ctrl+F 打开查找并输入 `id`，默认 6 处；点击整词后 4 处，正文和未保存标记不变。
  “下一个”从 `user_id` 内光标直接定位大写 `ID`，显示 2/4，没有停在 `id2`。
- 原生 Ctrl+H 展开替换，整词选项保留。显式将 4 处换为 `record_key`，包括普通注释文本，
  `user_id` 和 `id2` 保持原样；反馈 4 处并出现文件脏标记。聚焦正文后一次 Ctrl+Z 恢复全部文字和干净标记。
- 首次无障碍按钮点击触发既有“编辑状态已变化”保护，未改正文；重新观察后原生鼠标点击成功。
  不据此弱化焦点守卫。替换后按钮禁用时第一次 Esc 未收起；最终从聚焦查找选项按 Esc 收起通过，不宣称全窗口 Esc 都关闭查找。
- 暗色宽窗、暗色/亮色 640 窄窗均可读，查找条自动折行、SQL 保留显示空间。
  亮色窄窗组合区分大小写后大写 `ID` 为 1 处；空格关闭大小写恢复 4 处，Tab 移到整词后空格关闭恢复 6 处。
- 一次截图 ID 缓存失效，重新观察后切换主题成功。用户继续消息后 JS 会话失效，重新初始化并从真实返回窗口重选；
  被其他窗口遮挡时先激活本验收窗口，没有对遮挡窗口操作。
- 正常关闭后精确夹具 exe 路径存活进程数为 0；磁盘合成 SQL 与初始混合换行文本逐字一致，未保存源文件或执行 SQL。
  版本 0.0.0 仅本地验收，不是发布版本；真实数据库、真实用户配置、安装升级不在本轮范围。

## 审查与本地合并

复查默认 API 兼容、UTF-16/代理对、拒绝候选的重叠恢复、有效匹配限额、选项切换的异步代际失效、
只应用一致匹配集、单步撤销与控件折行。生产代码只改两个文件，无新的 I/O、连接、依赖或持久化格式。
- 实现提交 `a36b4d0`（`feat: add whole-word SQL find and replace`）已快进本地 main。
  合并时 main 与功能分支 Git tree 均为 `122ecc593e5adbea3d5f4479f30146524ece9b7d`，功能 worktree 干净。
- 首次合并后验证随会话中断，没有完成记录；恢复时检查无存活 Java 进程且 XML/镜像仍为旧版，未将其算为通过。
  随后重新运行 buildSrc、整词/旧字面查找与替换、实际 Pane、易用性测试及 jpackageImage：
  1 分 24 秒 exit 0，98 项全部通过 / 0 failures/errors/skipped；构建期 8 项原已通过、本次 up-to-date。
  jlink / jpackageImage 实际执行；main 镜像 modules 哈希与上方验收镜像完全一致，入口仍为 DataCubeFx。
- 根目录仅保留既有未跟踪 `.testagent/`，未读取、修改或暂存。本轮不推送、不打 tag。

## 已知边界

- 整词定义是保守的文本边界，不识别 SQL 语法、引用作用域、注释或字符串；不承诺语义安全。
- 多词/标点查询检查整个字面片段外边界，不把查询拆成词；大小写沿用原 Unicode 大小写匹配，不新增语言学归一化。
- 草稿保存逻辑 LF 文本；文件控制器单独保留未涉及的物理换行，源文件不自动保存。
- 仅当前编辑器，无跨文件/数据库搜索；不宣称真实用户耗时改善、CI 或正式发布完成。
