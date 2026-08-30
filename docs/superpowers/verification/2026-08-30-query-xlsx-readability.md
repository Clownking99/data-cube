# 查询结果 XLSX 可读性验收

日期：2026-08-30。实施基线：`c73224c`。当前状态：Task 3 代码与自动化验收完成；真实合成文件/Excel UI 验收仍由主代理执行。

## 基线与已完成检查

- 基线运行：临时追加 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后执行 `./gradlew clean test --no-daemon --console=plain`，结束恢复变量。退出码 0，25 秒。
- 主代理汇总基线 JUnit XML：135 suites、1196 tests、1193 passed、0 failures/errors、3 skipped。
- Task 1 提交 `5ff2d1a`：新增不可变列宽描述和有限取样估计器。子代理记录了缺少 API 的编译失败，以及固定宽度入口下 5 项断言失败；完整实现后同一类 5 项测试通过。
- Task 1 定向命令：`./gradlew test --tests com.datacube.export.QueryXlsxLayoutEstimatorTest --no-daemon --console=plain`。
- Task 1 全套 `test` 退出码 0；主代理读取该次 XML：136 suites、1201 tests、1198 passed、0 failures/errors、3 skipped。
- 独立任务审查：Task 1 设计符合、质量 Approved；存在一项控制字符宽度解释的 Minor，已请求用户澄清，尚未修改。
- 基线编译存在既有 `SqlEditorResultFilterContractTest` 未检查/不安全操作提示，不属于本轮新增告警。

## Task 3 查询接入与安全边界证据

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

## 未完成及限制

- 主代理尚待执行批准设计第 7 节 Step 6：使用 `.superpowers/sdd/xlsx-fixture.jsh` 生成本轮合成文件，记录绝对路径和 SHA-256，并用可用查看器核对中文/时间/长文本换行、表头样式与首行冻结。该项未在本代理中执行，不能写成通过。
- 实际 XLSX 文件、渲染预览、Excel 冻结/换行交互尚未验收。本机应用列表发现 Excel，但尚未由本代理打开文件；若交互查看器不可用，应明确记录“冻结/自动行高仅验证 OOXML，未验证真实 Excel 交互”。
- 用户数据库、真实凭证、已有业务文件及 `.testagent/` 均未访问或修改。没有推送、合并或创建 tag。
- 控制字符歧义：当前示例实现对 DEL/C1 计宽，与“可见字符”文字存在歧义；建议仅在宽度估计中排除，不改变单元格输出。等待用户决定。
