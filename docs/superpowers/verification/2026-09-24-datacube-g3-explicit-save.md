# G3 / M3 显式保存实施与验收账本

范围：表数据页内存变更集、预览、显式保存、离开页面/关闭守卫。仅本地工程；不自动扩大到 M4–M8。原生桌面、真实数据库及发布证据独立列为待验。

状态：分支本地验证与审查通过，实现已提交 `92a03a1`；等待合并 main 及复验，尚未宣称目标完成。

## 检查点 1：范围、基线与事务决定

- 当前目标：用户在明确询问是否授权 G3/M3 后回复“继续推进”，据此继续此阶段；先前 G1 已交付，不重新实现 M1。
- 分支：`codex/datacube-g3-explicit-save`；目录 `C:\Users\hetia\.codex\worktrees\datacube-g3-explicit-save\朝花夕拾`。初始基线 `9b334a18b0d5561ba86e13b1128dae419c0ead24`；本次恢复时核实既有未提交改动，随后快进到只增加 G1 复核文档的 main `d2808f59a65a6937c0d95cd3402d065e920a4d70`。没有覆盖其他工作区改动。
- 决策先于实现落盘：[实施设计](../plans/2026-09-24-g3-explicit-grid-save.md)。每行专用连接/独立事务，按原始加载及新增顺序保存，失败停止；已提交行不能整体回滚。预览与保存使用相同不可变载荷；列排序不改变执行顺序。
- 隔离证据根目录：`C:\Users\hetia\AppData\Local\Temp\datacube-g3-6f6c379b1d4e4e04a46560e9c8a20e03`。Test JVM 独占合成 user.home，headless=false；移除 live Redis/Schema Diff 环境入口，Gradle 全程 offline。没有访问真实应用 profile、连接、凭据、SQL 历史或业务文件；不读写 `.testagent/`。
- 基线定向：`g3-baseline.log` exit 0，4 suites / 37 tests / 37 passed / 0 skipped；源码编译时间早于实现改动，基线 XML 保留在 `baseline-xml/`。未把 G1/G2 的旧测试计为本轮通过。
- 下一步：先证明离行隐式写入，再接入变更集和 M1 批次确认。

## 检查点 2：页面变更、事务结果和首轮行为验证

- 改动：GridChangeSet 保留新增/修改/删除/已提交/不确定行身份；普通 Enter、离行、翻页不写入；逐项/全部放弃仅影响未保存修改。新增 Sql 共享构造、不可变批次确认、逐行结果和旧值冲突检查；AppShell 将数据页接入交互/强制关闭守卫。
- 事务：INSERT/UPDATE/DELETE 都检查影响 1 行；0/多行回滚。提交应答不确定或回滚失败不恢复自动提交，防止隐式提交；已提交后清理失败明确标为已提交。页面锁定已提交及不确定行，不把失败重试变成已提交行重放。
- 新验证：`g3-row-leave-red.log` 1/1 failed，证明原离行路径启动数据库工作；实现后 `g3-first-green.log` exit 0。`g3-transaction-first.log`、`g3-service-first.log` 均 exit 0，使用实际 JdbcDataEditor + 动态 JDBC mock，验证绑定、提交、回滚、旧许可、配置收紧、部分成功、取消及浏览事务隔离。
- 首轮 FX：`g3-fx-first.log` 7 tests / 5 failed，XML 原样保留在 `g3-fx-first-xml/`。两处产品问题为活动编辑器的首个修改不能直接预览/保存、未输入就离开 NULL 字符单元格误改为空串；已修复。另有用例将原值作为“修改”及多标签关闭只回应一次对话框的问题，修正测试设置；余下超时受未回应对话框影响。
- 修正后 `g3-fx-second.log` exit 0（27 tests / 27 passed，XML `g3-fx-second-xml/`）；增加真实单元格事件、取消导航/查询/关闭、生产确认取消、部分失败重试、多标签与退出守卫行为断言。没有加 sleep、降低断言或新增 skip。
- 失败/未验：首轮失败不被复跑覆盖。尚未完成最终定向/全量/buildSrc/镜像、差异审查或本地集成；原生桌面与真库未验。
- 下一步：补强强制关闭等待驱动结束、迟到生产确认回归，运行完整本地门槛后审查集成。

## 检查点 3：差异审查与全量验证

- 当前目标：完成最终本地门槛再集成。`g3-targeted-review` 实际 14 suites / 106 passed，0 failures/errors/skips；新增强制关闭屏障和迟到生产确认用例通过。
- 差异审查：核对所有消费 DataEditor 的生产调用点仍经 DataEditService/M1；表格只保留显式保存入口。逐行确认由已获准的不可变批次内部创建，每行再次复核目标/取消；专用连接不提交浏览或 SQL 编辑器事务。UNKNOWN 不恢复自动提交、不提供直接重试；成功行不进入下一次 snapshot。
- 审查修正：发现 AppShell 已全局处理 SQL 文件 Ctrl+S，移除本轮网格新增的同名快捷键，保留文件语义；数据库保存使用明确按钮。补加保存后查询失败仍锁定成功行、快捷键不写库的回归。README 更新保存、取消、内存修改和冲突覆盖范围。
- 已执行 buildSrc：`g3-buildSrc-final`，8/8 passed、0 skipped，4 tasks 均 executed。首轮全量 `g3-full-final` 虽命名含 final，实际只作为**审查修正前的中间证据**：278 suites / 3636 tests / 3633 passed / 3 live skips，0 failures/errors，2m27s；后续追加测试和快捷键修改，不能用它认证最终代码。
- 失败/未验：本检查点没有新的失败；保留编译 unchecked 提示。3 live skips 为 Redis 及 Oracle/PG Schema Diff 的原有 opt-in 测试，未运行、不算通过。最终代码全量及镜像尚待完成；外部验收不变。
- 下一步：最终定向、重新 clean 全量及 jpackageImage；验证后本地提交/合并并在 main 再验。

## 检查点 4：最终分支验证、提交与待集成

- 目标：完成经审查代码的本地门槛并提交。实现/测试/README/设计提交 `92a03a15fdeaca7b34c81110b63f56f0dfa1580c`；与测试前 `final-code-manifest.json` 逐文件 SHA-256 一致。
- 定向：`g3-targeted-final`，14 suites / 107 tests / 107 passed / 0 failures/errors/skips，12s。
- 最终全量：`g3-full-reviewed`，278 suites / 3637 tests / 3634 passed / 0 failures / 0 errors / 3 live skips，2m30s。3 skipped 为 Redis live 及 Oracle/PG Schema Diff live，均未运行、不计通过；没有新增 skip。
- buildSrc：`g3-buildSrc-final`，8/8 passed，0 skipped，4 tasks executed，8s。其后没有修改构建代码。
- 镜像：`g3-image-final`，jpackageImage exit 0，40s；生成 DataCube.exe（595968 bytes），检查 DataCube.cfg 不含测试 user.home/headless/合成入口。命令不带 init script，也清除进程内本任务 profile 环境变量；没有安装/启动更新或真实用户 profile。
- 自行审查：仅 M3 页面/服务/JDBC 行事务及必要接线；保留 M1 门禁、SQL 编辑器独立事务及读取功能。审阅新文件和调用点，`git diff --cached --check` 通过。没有格式化器扩展、连接身份变更或持久化修改；模块打包通过。
- 失败/未验：最终分支无失败；既有 unchecked 编译和 JEP 493 jlink 提示保留，不称零警告。旧 SchemaDiff 偶发异常本轮未复现，根因仍未知。原生桌面、真库、安装/CI/发布未验。
- 证据：[机器可读结果](2026-09-24-datacube-g3-results.json) 汇总命令、实际 XML 计数/跳过、日志和 XML 整组摘要。分支运行时 HEAD 为 `d2808f5` 加本轮已验证工作区，随后原样提交为 `92a03a1`；不把运行时 HEAD 误称为无修改的源码版本。
- 下一步：重新核对 main SHA/工作区；本地合并后在独立 `profile-main` 上重新 clean 全量、实际执行 buildSrc 并构建镜像。通过前不把 M3 标为本地工程完成。

## 行为证据索引

| 要求 | 可追溯行为回归 |
| --- | --- |
| 离行/Enter 只暂存、NULL/空串/撤销 | DataGridExplicitSaveTest；DataGridSaveFlowTest 的 activeCellDraft… / enteringNullCell… / deletingAndDiscarding…；GridChangeSetTest |
| 原始主键/旧值、并发冲突及无主键限制 | GridChangeSetTest 的 comparableOriginalValues… / editsDistinguishNull…；GridSaveServiceTest 的 conflictingOldValues…；JdbcRowTransactionTest 的 zeroAndMultipleMatches… |
| 预览与冻结载荷、类型绑定、M1 零资源拒绝 | GridSaveServiceTest 的 previewAndConfirmedSave… / readOnlyAndUnconfirmed… / invalidNumericValue… / previewIsBounded…；既有 RelationalWriteSafetyTest |
| 逐行提交、失败/不确定/清理失败、不重放 | GridSaveServiceTest 的 firstFailure… / uncertainCommit…；JdbcRowTransactionTest；DataGridSaveFlowTest 的 partialFailure… / failedRefresh… |
| 翻页/查询/过滤/标签关闭/退出默认取消 | DataGridSaveFlowTest 的 navigationRefreshFilter… / safetyTighteningKeepsPendingRows…，实际 ContentTabPane 管理守卫 |
| 保存中关闭、取消剩余、强制关闭等待/迟到回调 | DataGridSaveFlowTest 的 savingRejectsInteractiveClose… / mandatoryCloseWaitsForInFlightRow…，CountDownLatch 控制真实 worker 时序 |
| 配置变化及迟到生产确认 | GridSaveServiceTest 的 configurationTightening… / staleApproval…；DataGridSaveFlowTest 的 lateProductionApproval… |

以上均为合成数据和 mock JDBC。页面失败报告只使用固定原因说明，预览不写日志或文件；不能推导真库触发器、真实驱动故障或原生桌面验收结论。

## 待外部验收

- 原生桌面：合成 profile 中的鼠标/键盘、Tab 焦点、明暗主题、100%/150% 缩放及窄窗口；FX 自动化不能替代肉眼验收。
- 获授权的一次性 Oracle/PG：实际驱动、权限/触发器、副作用、空字符串与 NULL、并发旧值冲突、连接故障与取消时序。客户端护栏不是数据库权限沙箱。
- 真实安装/升级/恢复、签名、同 SHA 远端 CI 和发布均未执行；M2 外部待验不因 G3 本地测试通过而关闭。

## 复现方式

独占临时目录的 `isolated-tests.gradle` 只配置 Test JVM，镜像命令不携带该脚本；未修改系统配置或 JAVA_TOOL_OPTIONS。

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'user.home', System.getenv('DATACUBE_G3_PROFILE')
        systemProperty 'java.awt.headless', 'false'
        environment.keySet().findAll {
            it.startsWith('DATACUBE_REDIS_') || it.startsWith('DATACUBE_SCHEMA_DIFF_')
        }.each { environment.remove(it) }
    }
}
```

```powershell
$env:DATACUBE_G3_PROFILE = Join-Path $scratch 'profile'
.\gradlew.bat test --tests 'com.datacube.fx.DataGrid*' --tests 'com.datacube.fx.GridChangeSetTest' --tests 'com.datacube.service.GridSaveServiceTest' --tests 'com.datacube.provider.jdbc.Jdbc*Test' --tests 'com.datacube.service.RelationalWriteSafetyTest' --tests 'com.datacube.fx.WriteSafetyIntegrationTest' --tests 'com.datacube.fx.ContentTabPane*' --tests 'com.datacube.fx.ManagedPaneCloseContractTest' --offline --no-daemon --console=plain --init-script (Join-Path $scratch 'isolated-tests.gradle')
.\gradlew.bat :buildSrc:test --rerun-tasks --offline --no-daemon --console=plain --init-script (Join-Path $scratch 'isolated-tests.gradle')
.\gradlew.bat clean test --offline --no-daemon --console=plain --init-script (Join-Path $scratch 'isolated-tests.gradle')
.\gradlew.bat jpackageImage --offline --no-daemon --console=plain
```

main 复验改用独立 `profile-main`；实际结果以每次归档 XML 为准，UP-TO-DATE 和跳过不计为新通过。
