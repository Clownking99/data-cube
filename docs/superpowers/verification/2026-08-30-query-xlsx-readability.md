# 查询结果 XLSX 可读性验收

日期：2026-08-30。实施基线：`c73224c`，审查收尾基线：`4eb2e8c`。当前状态：实现、审查与最新全量测试完成，DEL/C1 计宽问题已修复。上轮合成文件只读导入/渲染通过；真实 Excel 交互未验收，仍保留一项非阻断测试覆盖限制。最新结果见“审查收尾验证”。

## 基线与已完成检查

- 基线运行：临时追加 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后执行 `./gradlew clean test --no-daemon --console=plain`，结束恢复变量。退出码 0，25 秒。
- 主代理汇总基线 JUnit XML：135 suites、1196 tests、1193 passed、0 failures/errors、3 skipped。
- Task 1 提交 `5ff2d1a`：新增不可变列宽描述和有限取样估计器。子代理记录了缺少 API 的编译失败，以及固定宽度入口下 5 项断言失败；完整实现后同一类 5 项测试通过。
- Task 1 定向命令：`./gradlew test --tests com.datacube.export.QueryXlsxLayoutEstimatorTest --no-daemon --console=plain`。
- Task 1 全套 `test` 退出码 0；主代理读取该次 XML：136 suites、1201 tests、1198 passed、0 failures/errors、3 skipped。
- 独立任务审查：Task 1 设计符合、质量 Approved；存在一项控制字符宽度解释的 Minor，已请求用户澄清，尚未修改。
- 基线编译存在既有 `SqlEditorResultFilterContractTest` 未检查/不安全操作提示，不属于本轮新增告警。

## Task 2/3 样式、查询接入与安全边界证据

Task 2 提交 `033de19`：可选样式入口的 RED 运行 4 项中 2 项失败；完整实现后 4 项通过，导出包 37 项通过。主代理补充独立全套 `test`（非 UP-TO-DATE），退出码 0、19 秒；137 suites、1205 tests、1202 passed、0 failures/errors、3 skipped。Task 2 独立审查设计符合、质量 Approved、无问题。

- Task 3 代码基线为 `033de19`（Task 2 完成）。
- TDD RED：先新增 `QueryXlsxExportTest`，执行 `./gradlew test --tests com.datacube.export.QueryXlsxExportTest --no-daemon --console=plain`；4 tests completed，`scopesUseTheirOwnSampleAndOrderingWithTheSameProjection` 在 `QueryXlsxExportTest.java:44` 失败（期望新布局列宽 `60`，旧固定宽度行为不满足），其余 3 个测试通过。该失败确认查询入口尚未接入估计布局。
- TDD GREEN：仅替换 `QueryResultFileWriter.write` 的 XLSX 分支，先以 `originalRows` 调用 `QueryXlsxLayoutEstimator.estimate(..., operation::check)`，再以显示视图 `rows` 调用四参数 `XlsxWriter.write`。执行 `./gradlew test --tests 'com.datacube.export.*' --tests com.datacube.fx.SqlResultExportCoordinatorTest --no-daemon --console=plain`，Gradle 退出码 0、BUILD SUCCESSFUL。
- 查询接入测试覆盖：CURRENT_FILTERED/ALL_LOADED 各自取样和投影顺序、特殊值 consent 与 SQL 拒绝、采样异常/取消时旧文件保留和临时文件清理、实际序列化取消时停止后续行并不发布。
- Task 3 全套验证：临时追加 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后执行 `./gradlew clean test --no-daemon --console=plain`，退出码 0、BUILD SUCCESSFUL；结束后恢复原环境变量。此次 XML 汇总（不复用旧计数）：138 suites、1209 tests、1206 passed、0 failures、0 errors、3 skipped。跳过数为 3，不计入通过。编译仍有既有 `SqlEditorResultFilterContractTest` 未检查/不安全操作提示。

## 当前行为证据

| Requirement | Evidence |
| --- | --- |
| 每列考虑表头和最多前 100 行；空值贡献宽度 0。 | `QueryXlsxLayoutEstimatorTest.measuresHeaderScalarsUnicodeAndLimits`、`limitsRowsWithoutReadingThe101st` |
| 字符串最多扫描前 256 个 Unicode 码点。 | `QueryXlsxLayoutEstimatorTest.measuresHeaderScalarsUnicodeAndLimits`，包含扫描边界和代理对断言 |
| 布局不额外调用任意精度值或特殊值的完整格式化。 | `QueryXlsxLayoutEstimatorTest.expensiveValuesAreNotFormattedForLayout` |
| 取消发生在取样期间时停止后续访问。 | `QueryXlsxLayoutEstimatorTest.cancellationBetweenColumnsPreventsFurtherValueAccess` |
| 布局不可变、宽度受限并支持短行。 | `QueryXlsxLayoutEstimatorTest.supportsHeadersOnlyAndRaggedRowsWithoutMutatingInput` |
| 样式引用、列宽、冻结首行及旧入口不变。 | `XlsxWriterLayoutTest.styledPackageHasWidthsFreezeAndValidStyleReferences`、`legacyEntryKeepsItsOriginalPartsAndPlainSheet` |
| 值、类型、顺序不因样式改变；错误布局不打开输出或消费行。 | `XlsxWriterLayoutTest.layoutDoesNotChangeCellValuesTypesOrOrder`、`invalidLayoutDoesNotOpenOutputOrConsumeRows` |
| 两种查询范围独立取样并保持投影顺序。 | `QueryXlsxExportTest.scopesUseTheirOwnSampleAndOrderingWithTheSameProjection` |
| 特殊值仍须许可且不能成为 SQL。 | `QueryXlsxExportTest.specialValuesStillNeedConsentAndNeverBecomeSql` |
| 采样失败/取消保护旧文件；写出取消停止后续行并阻止发布。 | `QueryXlsxExportTest.samplingFailureAndCancellationPreserveOldFileAndCleanTemporary`、`cancellationDuringActualXlsxSerializationStopsLaterRowsAndPublication` |

## 合成文件只读验收

- 使用当前生产入口生成：查询通过 `QueryResultFileWriter`，对照通过 `XlsxWriter` 三参数旧入口。文件目录：`C:/Users/hetia/AppData/Local/Temp/datacube-xlsx-readability-1001545991734689139`。
- `query-styled.xlsx` SHA-256：`b4450a354bc3bc08efc69911e2d580345ea1f5fc0dcf0ae7b5c6d31fb03c5b31`。
- `legacy-plain.xlsx` SHA-256：`01514505d18423d38d78196d0e00c9736544cd171c786380fe4fd2d3b21afa19`。
- 只读 spreadsheet 导入确认两份 Sheet1 A1:D4 值矩阵一致，分数仍为数值 12/25/8，空值保持空值，纳秒时间字符串完整。检查前后文件哈希一致，没有重新导出或修改工作簿。
- 同目录保留 `query-styled-preview.png` 与 `legacy-plain-preview.png`。主代理查看渲染：新样式表头加粗、浅蓝灰背景；中文、完整时间可读，长文本和显式换行得到展示。对照文件默认窄列存在裁切。
- 以上是文件结构和独立渲染器的证据，不等同于 Microsoft Excel 交互验收。

## 主代理最终全量验证

- 在 `bebab8b` 产品代码上重新执行非 headless `./gradlew clean test --no-daemon --console=plain`，退出码 0，26 秒；不是复用子代理报告或 UP-TO-DATE 测试。环境变量执行后恢复。
- 本次 XML：138 suites、1209 tests、1206 passed、0 failures、0 errors、3 skipped。
- 跳过项：`RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle`、`SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas`、`SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas`。没有启用真实 Redis/Oracle/PostgreSQL 测试环境，跳过不计为通过。
- `git diff --check` 通过；仅有工作区 LF/CRLF 转换提示，编译仍为既有未检查操作提示。

## 未完成及限制

- Excel 文件对话框的截图/可访问性树刷新被安全检查拒绝，原因是可能暴露无关私人文件元数据。主代理停止这条交互路径，未尝试绕过；没有打开工作簿。已退出对话框并关闭本轮启动的 Excel，窗口列表确认无 Excel 窗口。
- 冻结首行仅验证 OOXML；未设置固定行高、文本换行及渲染器自适应高度已经验证，但真实 Excel 滚动冻结和自动行高未验证。
- 没有访问数据库、真实凭证、已有业务文件内容及 `.testagent/`；Excel 启动页曾呈现无关最近文件元数据，未用于任务、未复制进本记录。没有推送、合并或创建 tag。
- 原控制字符计宽问题已在 `aeaa37e` 修复，只调整布局估计，不修改单元格输出；下方保留最初审查历史。
- Task 3 的采样异常测试仍直接组合发布器与估计器；本轮核实未找到符合现有冻结快照契约的可靠公开输入注入方式。保留为非阻断覆盖限制，不标记为已完成 writer 级故障验收。

## 初次整分支审查与交付（历史）

- 独立最终审查范围：`c811802ce89884af551193b37947e35bac21243e..bebab8b`，覆盖 23 次提交和此前安全导出集成；最新 XLSX 实现范围为 `c73224c..bebab8b`。
- 结论：代码审查角度 Ready to merge，0 Critical、0 Important、2 非阻断 Minor。该结论不表示已合并，也不表示真实 Excel 交互通过。
- Minor 1：DEL/C1 被计入列宽是外观/规范解释问题，不影响单元格实际内容或文件保护；等待用户确认后可仅调整估计器并补控制字符回归。
- Minor 2：采样失败测试是发布器与估计器的组合级证据；查询接入另有布局/范围集成测试，但失败注入本身没有贯穿 writer。保留为测试增强，不为此增加产品测试后门。
- 按原授权保留本地 `codex/safe-result-export` 和现有工作区；没有合并、推送、tag 或清理用户 `.testagent/`。合成产物留在上述新建临时目录，便于人工 Excel 验收。

## 审查收尾验证

- 用户认可继续后，本轮只处理上述两项审查意见。修复提交：`aeaa37e fix(export): ignore DEL and C1 in XLSX width estimates`。产品差异仅在 `QueryXlsxLayoutEstimator` 的字符判断；旧 writer、发布器、UI 及其他导出格式未改。
- 新增 `QueryXlsxLayoutEstimatorTest.ignoresDelAndC1ControlsInHeadersAndValuesWithinTheScanBudget`：控制字符表头/正文宽度 12；11 个 U+007E 宽度 13、6 个 U+00A0 宽度 14，混合文本宽度 15；256 个 DEL 后的长可见后缀不影响宽度。可见边界断言高于最小列宽，避免被宽度下限掩盖。
- 新增 `QueryXlsxExportTest.isoControlsDoNotWidenQueryXlsxButRemainInSerializedText`：通过真实查询 writer 生成 XLSX，断言单列宽度 12、两格原始控制字符文本逐字保留。没有因为排版修复清洗或截断内容。
- TDD：原定向基线 13 项通过；新增回归后强制执行 15 项，2 项宽度断言按预期失败；仅修改判断后强制执行同一组 15 项全部通过，0 failures/errors/skipped。命令：`./gradlew test --tests com.datacube.export.QueryXlsxLayoutEstimatorTest --tests com.datacube.export.QueryXlsxExportTest --tests com.datacube.export.XlsxWriterLayoutTest --rerun-tasks --no-daemon --console=plain`。临时非 headless 环境变量运行后恢复。
- 测试缺口核实：`QueryResult.queryWithMetadata/freezeRows` 复制并冻结普通行集合，`ResultExportSnapshot` 复制且校验索引/投影；估计器只格式化明确列出的不可变标量，其他值使用固定宽度。因此没有找到可维护的公开输入用例，在快照成功建立之后专门触发非取消类采样异常。没有添加反射、Unsafe、静态 mock、新依赖或产品测试后门，也没有将这个缺口写成已覆盖。原组合级采样失败测试及真实 writer 序列化取消/文件保护测试继续保留。
- 独立最终复审范围更新为 `c811802..aeaa37e`：计宽 Minor 已关闭，采样失败直连覆盖 Minor 暂缓且非阻断；0 Critical、0 Important，无新问题，代码审查角度 Ready to merge。
- 主代理在 `aeaa37e` 上重新运行 `./gradlew clean test --no-daemon --console=plain`，临时追加 `-Djava.awt.headless=false` 后恢复；退出码 0，28 秒。最新 XML 为 **138 suites、1211 tests、1208 passed、0 failures、0 errors、3 skipped**。跳过仍为上文 Redis/Oracle/PostgreSQL 三项真实环境测试，未计入通过。
- 既有 `SqlEditorResultFilterContractTest` 未检查操作编译提示仍在；`git diff --check` 通过。本轮未重新执行 Excel UI 或旧预览验收，不改变相应限制。仅本地提交，保留当前分支和用户 `.testagent/`。
