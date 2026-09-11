# 结果单元格查看验收

基线 `b8487aa`；独立 `codex/result-cell-preview`。[设计与实施范围](../specs/2026-09-11-result-cell-preview.md)。只读查看已加载快照，不读公司数据库、已有 SQL 历史、凭据或业务文件。

## 行为与测试

| 要求 | 证据 |
|---|---|
| NULL / 空字符串 / 文字 NULL / Unicode / 多行 / HTML 均按文字展示 | `ResultCellPreviewTest.distinguishesNullEmptyAndLiteralContentWithoutChangingText`（5 组）、`ResultCellDialogTest.nullEmptyAndLiteralNullHaveDifferentVisibleStates`（3 组） |
| 正文限制前/等于/超过 65,536，明确截断，不拆代理对，元数据有界 | `boundsDisplayWithoutClaimingLongContentIsComplete`（3 组）、`truncationDoesNotSplitSurrogatePairsAndBoundsMetadata` |
| 特殊值只用已冻结显示表示；不保留调用方可变字节，日志摘要不带内容 | `specialValuesRemainDisplayOnlyAndSnapshotDoesNotRetainMutableBytes` |
| 同名列按位置、缺失行字段不能当作 NULL，非法索引拒绝 | `duplicateLabelsUseColumnPositionAndMalformedRowsAreNotNullValues` |
| 真正只读 TextArea、切换折行保留文本与选区、Esc 关闭，无写入/执行按钮 | `ResultCellDialogTest.readOnlyContentAndWrapKeepSnapshotIntactAndEscapeCloses` |
| 明暗主题，480/720px 查看窗口，截断警告/正文/关闭按钮可见 | `shownDialogKeepsLongValueWarningAndControlsVisible`（4 组） |
| 病理多行超长元数据滚动，不挤掉正文和截断提示 | `pathologicalMetadataScrollsWithoutHidingTruncationWarningOrValue` |
| 实际 Pane：本地筛选、降序、重排、隐藏、同名列组合仍按行对象身份/原列索引准确捕获；不改 SQL 选区/撤销/文件 | `SqlResultCellIntegrationTest.sortedFilteredReorderedDuplicateColumnsResolveByIdentityWithoutChangingEditorOrFile` |
| 工具栏/右键实际显示窗口；不重复打开；新结果不改变旧窗口，FX 终结关闭 | `explicitToolbarAndContextEntryShowImmutableSnapshotAndFinalizationClosesDialog` |
| 搜索防抖期间只读当前点击时的值；不主动提交筛选；后续筛选不改窗口 | `pendingSearchDoesNotChangeTheCellCapturedAtClickTime` |
| 无焦点/未选焦点/序号/隐藏列/脱离身份的行/不匹配结果/清空/短行不猜另一格 | `missingOrStaleCellsNeverFallBackToAnotherValue`（8 组） |
| 关闭准入、资源、任务、运行、关闭队列、禁用根节点、执行计划拒绝旧动作 | `closedBusyOrNonTableStatesRejectOldViewActions`（7 组） |
| 880/640/480px 明暗结果工具栏完整可读，布局不派发动作 | `SqlResultToolbarLayoutTest.resultActionsKeepFullLabelsAndWrapWithoutDispatching`（6 组） |
| 原复制/INSERT 失败反馈保持不变 | `SqlEditorResultFilterContractTest.clipboardFailureNeverClaimsSuccessAndInsertCopyUsesTheSameSeam` |

集成测试使用独占临时文件和 DraftConnectionProbe，断言 provider/session/metadata/network 为 0。生命周期部分采用直接状态注入，不宣称真实数据库并发压测。未运行覆盖率工具，不宣称覆盖率百分比。

## 自动化过程

首轮编译发现 JavaFX 焦点 API 返回原始 TableColumn，严格 unchecked/Werror 门禁拒绝；改为从实际可见列中按身份取带泛型的列，没有禁用告警。随后定向 exit 0，23s。增加可滚动元数据和手工 fixture 时，误把 final CredentialCipher 当可继承类型，编译拒绝；改用无注册连接的标准实例，未修改凭据代码。该轮定向 exit 0，12s，63 项全部通过、0 跳过。

首轮全量暴露既有 INSERT 复制测试依赖右键菜单下标；新增入口使位置改变。改为按确切菜单文字找到原动作，保留两次确认、成功文本和剪贴板失败断言，没有弱化行为要求。

定向命令（`JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`）：

```powershell
.\gradlew.bat test --tests com.datacube.sqleditor.result.ResultCellPreviewTest --tests com.datacube.fx.ResultCellDialogTest --tests com.datacube.fx.SqlResultCellIntegrationTest --tests com.datacube.fx.SqlResultToolbarLayoutTest --tests com.datacube.fx.SqlResultToolbarTest --no-daemon --console=plain
```

完整构建使用独占 ASCII ShortPath 临时目录作为 `java.io.tmpdir`，同时保持非 headless：

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

最终完整构建 exit 0，2m45s。JUnit XML 共 200 类、2,074 项：2,071 通过、0 失败、0 错误、3 项既有 live 跳过（Redis 1、Schema Diff 2）。本轮新增快照 11、窗口 9、Pane 集成 18，共 38 项全部实际执行。jlink 和 jpackageImage 成功；用 jimage 检查正式 runtime，只含 ResultCellPreview / ResultCellDialog，不含 ResultCellDesktopFixture 或 DraftConnectionProbe。正式 DataCube.cfg 仍为 DataCubeFx 入口。既有测试 unchecked 提示和工具探测输出未阻止构建，未通过关闭检查来规避。

## 桌面

使用手工专用 ResultCellDesktopFixture，将内存合成 QueryResult 发布到实际 SqlEditorPane，无注册连接、不执行 SQL；独占 `build/result-cell-desktop-profile` 与开发镜像副本，通过 patch-module 注入手工 fixture，不改正式包入口。

Windows 真实鼠标验收完成：

- 暗色主题选第 4 行 value，工具栏打开多行内容；显示当前/原始第 4 行、原列 3、VARCHAR/JDBC 12，内容保持普通文本。关闭自动换行后出现横向滚动，重新打开默认换行。
- 第 1 行空值明确显示 NULL（数据库空值）、长度 0；第 2 行明确显示空字符串、长度 0；亮色主题右键第 3 行“查看当前单元格”，显示非 NULL 值、长度 4 和正文 NULL。
- 亮色主题选第 5 行 65,537 单元合成长文本，窗口明确显示总显示长度 65,537 和仅显示前 65,536 的截断警告，正文滚动区域可读。
- 实际拖动查看窗口、点击关闭按钮以及窗口 X 均正常；最后正常关闭隔离主窗口，并通过窗口列表确认已退出。

桌面右下角有独立 Audio Software 系统重启提示，未操作该弹窗；仅移动合成查看窗口避开遮挡。没有操作真实数据库、用户历史或系统剪贴板。物理 Esc、排序/筛选/列重排交叉以及 480px 窄栏未在本轮桌面手工重复，它们的证据限于上表实际 JavaFX 自动测试，不混称为手工通过。

本轮使用 code-testing-agent / Java 指南建立行为矩阵和状态交叉回归，Computer Use 用于合成窗口的真实可见验收；按照本机约定不引入通用多代理流程或 `.testagent/` 文件。

交付边界：本地提交与快进 main，不推送/tag/发布；没有真实用户耗时、Linux 或远端 CI 本轮结果。
