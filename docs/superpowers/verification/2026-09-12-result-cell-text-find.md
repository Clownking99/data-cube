# 单元格正文查找验收

基线 `4714114`；独立分支 `codex/result-cell-find`。范围见[设计与分步计划](../specs/2026-09-12-result-cell-text-find.md)。仅使用合成结果和临时配置，没有连接真实数据库、读写用户 SQL/历史/凭证或触碰 `.testagent/`。

## 自动行为证据

采用项目 JUnit 5 / FxUiTestSupport，直接显示真实 Dialog；复用 SqlTextSearch。测试技能用于 Java 行为矩阵和可观察断言，不套用通用多代理或状态文件流程，也不声称覆盖率百分比。

| 要求 | 回归证据 |
| --- | --- |
| 输入保留反向选区，下一处/上一处循环，区分大小写，手动移动光标，无匹配/清空 | `ResultCellFindTest.inputPreservesSelectionAndExplicitNavigationCyclesBothDirections` |
| 正则字符按字面、空格不裁剪、中文/emoji、Unicode 大小写、不重叠匹配 | `literalWhitespaceUnicodeAndNonOverlappingMatchesUseDisplayedOffsets`，5 组 |
| CR/LF 规范化后按显示文本定位、不修改正文 | `normalizedLineEndingsAndEmojiDoNotShiftSelection` |
| 查找词 1023/1024 接受、1025 整体拒绝且能恢复 | `queryLimitRejectsWholeInputAndCanRecover`，3 组 |
| 最多定位前 10000 处、65536 之后尾部与列名不参与查找；保留截断提示 | `matchLimitAndSnapshotLimitAreExplicitAndNeverSearchOmittedTailOrMetadata`；既有 `SqlTextSearchTest.matchLimitDistinguishesExactCountFromTruncation` 验证 9999/10000/10001 |
| NULL/空字符串正文不搜索辅助占位说明 | `nullAndEmptyRemainDistinctButHaveNoSearchablePlaceholder`，2 组 |
| Ctrl+F、Enter/Shift+Enter、F3/Shift+F3（含关闭按钮焦点）、Esc，旧动作关闭后失效 | `shortcutsStayInDialogAndClosedActionsCannotSelect` |
| 显示文本改变时更新偏移、owner onHidden 不覆盖清理、关闭后解除监听 | `changedDisplayedTextInvalidatesOffsetsAndOwnerHiddenHandlerDoesNotReplaceCleanup` |
| 明暗主题 480/720 窗宽：提示字聚焦可读、长状态换行、所有新增控件与既有警告/正文/关闭可见 | 扩展 `ResultCellDialogTest.shownDialogKeepsLongValueWarningAndControlsVisible`，4 组 |
| 焦点在查找框时正文真实 selection Path 可见且使用品牌实色 | 同上，断言实际选中文字和可见非空 Path，而非仅搜索 CSS 源码 |
| 原表身份/排序/筛选/隐藏/焦点/多选与导出、SQL 反向选区/撤销/文件保持，新结果不替换旧正文，标签关闭清理 | 扩展 `SqlResultCellIntegrationTest.sortedFilteredReorderedDuplicateColumnsResolveByIdentityWithoutChangingEditorOrFile` 与 `explicitToolbarAndContextEntryShowImmutableSnapshotAndFinalizationClosesDialog` |

实际 Pane 测试用 @TempDir、DraftConnectionProbe，断言 provider/session/metadata/network 计数为零，注入失败剪贴板 writer 检查没有自动复制。不用真实连接、网络、屏幕文本替代这些隔离断言。

## 命令与结果

1. 新入口红灯：`ResultCellFindTest` 14 项全部失败，均因旧 Dialog 不存在查找输入框；21s，exit 1。
2. 实现后首轮定向通过（16s）；扩展布局/生命周期/集成和补足 1 项测试后，58 项通过（17s，exit 0）。实际类数量：Find 15、Dialog 9、SqlTextSearch 9、CellIntegration 25。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*ResultCellFindTest' --tests '*ResultCellDialogTest' --tests '*SqlTextSearchTest' --tests '*SqlResultCellIntegrationTest' --no-daemon --console=plain
```

3. 首次全量 `clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain` 通过（2m05s，exit 0）：2252 总项、2249 通过、3 既有 live 跳过、0 失败/错误。
4. 桌面发现正文失焦后的选区过暗，补实际 Path 高亮断言，明暗/窄宽 4 组先全部失败（11s，exit 1）；只给 `#result-cell-text` 增加 `-brand-accent-solid` 高亮底与白色选中文字后，58 项通过（14s，exit 0）。不改变其他文本区。
5. 最终全量 `clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain` 再次通过（2m08s，exit 0）：2252 总项、2249 通过、0 失败/错误；3 项跳过来自既有 RedisLiveIntegrationTest（1）与 SchemaDiffLiveIntegrationTest（2）。

全量命令使用独占临时目录的 Windows ShortPath 设置 `java.io.tmpdir`，非 headless。保留既有 unchecked、JAVA_TOOL_OPTIONS 工具链探测及 JEP 493 输出，不称为无警告构建。

## 桌面证据与交付

- 最终 `jimage list` 确认生产运行时包含 ResultCellFindBar，不含 DesktopFixture 或 DraftConnectionProbe；生产 cfg 入口仍是 `com.datacube/com.datacube.DataCubeFx`。
- 在生产开发镜像的独立副本中加入测试类 patch-module，使用 `ResultCellFindDesktopFixture` 作为唯一窗口，避免控制通道激活 owner 时键盘进入父窗口。夹具只生成含中文、emoji、字面标记、90 行隔离段、三处 alpha/ALPHA 和超长尾部的合成文本，要求专属临时 user.home；不进入生产包。
- 首轮桌面确认输入 `alpha` 计数 3，不自动跳转；Enter 到第一/第二处，第二处能滚到屏幕内。但失焦选区过暗，因此未将首轮作为最终验收，关闭夹具后修正并重新全量打包。
- 最终镜像：Ctrl+F 聚焦，输入 alpha 计数 3；Enter 第一处的紫色实底白字高亮清楚，焦点仍在查找框；再 Enter 滚动到第 90 行之后的 ALPHA；Shift+Enter 回首处，Shift+F3 从首处循环到末尾第三处。
- 点击区分大小写后变为第 2/2 处，保持当前正文选区；F7 设为 480 宽、550 高，F6 切换亮色，控件与截断说明可见。亮色窄窗 F3 循环回首处，高亮可读。
- Ctrl+F 全选原查询，输入 TAIL_ONLY 后明确无匹配、导航禁用，正文旧选区按设计保留。输入 x 后显示“仅定位前 10000 处，请缩小查找范围”，在亮/暗窄窗及恢复 720 宽后均可读。
- 清空查询恢复聚焦提示字并禁用导航；Esc 从查找框一次关闭窗口。夹具已正常退出。没有访问业务连接、执行 SQL、自动复制或新写入用户配置。
- UIA 的部分文本会落后一帧，selected_text 可能返回辅助名称片段；以上交互以刷新后的截图确认，不把该字段当作实际正文。1024 边界、物理换行偏移、原表隔离与生命周期由自动测试覆盖，不冒称桌面逐一输入验证。

自审覆盖有界文本与匹配列表、显式导航、不抢正文焦点、正确选区偏移、独立关闭清理和样式作用域。代码不修改 Pane、数据模型、数据库协议、线程或存储，不增加依赖。`git diff --check` 通过。按既有授权本地提交后快进 main 并进行主线定向回归；不推送、打 tag 或发布。本地测试与开发镜像不代表远端 CI、安装升级、真实用户效率实验或正式发布 gate 完成。
