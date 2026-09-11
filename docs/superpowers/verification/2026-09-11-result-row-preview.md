# 当前结果行详情验收

基线 `2c36f7f`，独立 `codex/result-row-preview`。[设计与范围](../specs/2026-09-11-result-row-preview.md)。不访问真实连接、凭据、SQL 历史或业务数据，不触碰 `.testagent/`。

## 行为与证据

| 要求 | 精确测试 |
|---|---|
| 可见投影顺序/原列号及显示/原始行号准确，隐藏值不进入快照，集合只读且诊断不泄漏正文 | `ResultRowPreviewTest.capturesOnlyRequestedColumnsInVisibleOrderWithOriginalIdentity` |
| 字段正文 4095/4096/4097 边界，原单元格上限不变 | `rowLimitsDoNotReduceExistingCellViewerLimit`（3 组）及既有 `ResultCellPreviewTest` |
| 1/199/200/201 字段边界，200 字段和 819,200 正文单元总界限，明确省略 | `fieldLimitIsExplicitAndTextBudgetIsBounded`（4 组） |
| NULL/空串/文字 NULL/特殊二进制区别，长文本和代理对截断、元数据 512 界限，源字节变化不污染快照 | `preservesNullEmptyUnicodeAndSpecialValueSemantics` |
| null/非查询、行号/显示行、空投影/负数/越界/重复字段、残缺行拒绝，不部分成功或猜 NULL | `invalidOrAmbiguousProjectionNeverProducesPartialOrGuessedData`（11 组） |
| 限额外非法/空索引仍拒绝；包内文本上限不允许负数、零、超过原查看上限 | `validatesEvenOmittedColumnsBeforeCapturingAndRejectsNullIndexes`、`invalidInternalTextLimitsCannotBypassPreviewBounds`（3 组） |
| 列表及正文区分 NULL/空串/文字 NULL，正文不可编辑 | `ResultRowDialogTest.nullEmptyAndLiteralNullStayDistinctInListAndDetail`（3 组） |
| 同名字段初选按原列号，切换正文保留换行/Unicode/HTML 字面值，自动换行保留选区；清选清空详情，无排序/重排/写动作 | `selectingFieldsAndTogglingWrapKeepOnlySnapshotTextAndOneCloseAction` |
| 字段表和正文 Escape 均关闭 | `escapeFromEitherReadingAreaCloses`（2 组） |
| 明暗 480/720px，病理元数据单行化并滚动；正文截断/界限提示在滚动元数据之外，控件在界内且关闭实际文字完整 | `boundedWarningsAndControlsRemainReadableWithPathologicalMetadata`（4 组） |
| 焦点列超过 200 限额时显示明确省略提示，首字段回退和末字段浏览正确 | `omittedFocusedFieldFallsBackExplicitlyToFirstIncludedField` |
| 特殊表示警告在元数据滚动区之外可见，不承诺原值完整 | `specialRepresentationWarningIsVisibleOutsideMetadataScroll` |
| 实际 Pane 在筛选/倒序/重排/隐藏/同名/多选下捕获焦点行；字段浏览不改行对象、选择、SQL、文件、撤销；新结果/清空后保留旧快照，重新打开更新，单窗口和最终关闭 | `SqlResultCellIntegrationTest.rowDetailsCaptureSortedFilteredVisibleProjectionWithoutChangingSourceAndRemainFrozen` |
| 非焦点的可见字段残缺也不显示部分记录 | `invalidNonFocusedVisibleFieldRejectsWholeRowInsteadOfShowingPartialData` |
| 无焦点、未选择、序号、隐藏、脱离身份、结果不一致、清空和残缺行不回退 | `missingOrStaleCellsNeverFallBackToAnotherValue`（8 组，扩展原用例） |
| 关闭/资源/任务/运行/队列关闭或待处理/禁用/计划/终结拒绝旧入口 | `closedBusyOrNonTableStatesRejectOldViewActions`（9 组，扩展原用例） |
| 打开行详情不提前提交防抖行搜索，稍后筛选不改变快照 | `pendingSearchDoesNotChangeTheCellCapturedAtClickTime`（扩展原用例） |

实际 Pane 使用 @TempDir 和 DraftConnectionProbe，断言 provider/session/metadata/network 均为 0。排队路径是 latch 控制的空操作，不是 JDBC；未收集覆盖率百分比。共用焦点身份解析的原单元格回归仍保留。

## 构建

先写模型测试，ResultRowPreview 缺失时编译失败（16s、exit 1）；实现后模型/原单元格/Pane 首轮通过（17s），窗口矩阵通过（14s）。补齐生命周期/交叉与非法边界后，定向五类 112 项通过（14s、exit 0），无跳过：RowPreview 24、RowDialog 12、CellPreview 11、CellIntegration 25、FilterContract 40。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.sqleditor.result.ResultRowPreviewTest --tests com.datacube.sqleditor.result.ResultCellPreviewTest --tests com.datacube.fx.ResultRowDialogTest --tests com.datacube.fx.SqlResultCellIntegrationTest --tests com.datacube.fx.SqlEditorResultFilterContractTest --no-daemon --console=plain
```

全量使用本轮独占 ASCII ShortPath 临时目录设置 java.io.tmpdir，非 headless：

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

全量 clean test 与 jpackageImage 通过（2m16s、exit 0）：XML 合计 2,171 项，2,168 通过、0 失败/错误、3 项既有外部集成测试跳过；本轮新增 38 项均运行。构建仍有既有 unchecked、JAVA_TOOL_OPTIONS 探测输出和 JEP 493 提示，不记为无警告构建。

`jimage list` 确认生产镜像包含 ResultRowDialog/ResultRowPreview，不包含 ResultRowDesktopFixture 或 DraftConnectionProbe；生产启动类仍为 `com.datacube/com.datacube.DataCubeFx`。

## 隔离桌面检查

Computer Use 操作开发镜像的独立副本 `build/desktop-fixture-image/DataCube`。只有该副本的 cfg 被设为合成夹具入口、测试类 patch-module 和独立 `build/result-row-desktop-profile`；生产镜像 cfg 未变。夹具是未绑定连接的实际 SqlEditorPane，12 个合成字段，原列 9 隐藏；不加载用户配置、历史或业务文件。

- 实际结果右键包含“查看当前行（可见列）”；打开显示第 1 行、结果第 1 行、可见 11 列，初选原焦点 note（原列 3）。HTML 是字面文本，换行、空格、中文和 emoji 保留。
- 逐项点击 nullable、empty、literal：分别显示“NULL（数据库空值）”、空字符串、“非 NULL 值”；前两项正文空，文字 NULL 正文为 `NULL`。
- binary 正文 `01020f`，显示长度 6，并在元数据滚动区之外明确“显示表示可能已在读取时截断，不代表完整原值”。
- 滚动字段列表，原列 8 后为原列 10，不出现隐藏的原列 9。long_text 显示长度 6,011，明确“正文已截断，仅保留前 4096 个单元”；取消正文自动换行后出现横向滚动，提示和字段身份不变。
- 选择同名字段原列 11 和 12，正文分别为 `left` 和 `right`，原列身份明确。
- 关闭后原表 note 选择保留。切换亮色并重新从实际右键打开，初选仍为 note；明暗主题的字段列表、正文、边界提示和关闭按钮均可见。重新打开时自动换行恢复默认，鼠标关闭正常。
- 结束时通过应用关闭按钮正常退出。未在桌面手动验收键盘 Escape、窄窗或 200 字段边界；这些由上方 JavaFX/模型矩阵覆盖，不混称桌面通过。没有连接真实数据库、导出、自动复制或执行 SQL。

## 验证边界

按本机约定使用 code-testing-agent 的 Java 指南和行为矩阵，不引入通用多代理/状态文件模板；Computer Use 仅用于本轮隔离合成窗口。已执行、受限和未执行分别记录，不把单元/JavaFX 测试混称为真实业务桌面验收。交付仅本地提交并快进 main，不推送/tag/发布，不代表真实用户效率或远端 CI 已通过。
