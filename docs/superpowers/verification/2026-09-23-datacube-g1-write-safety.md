# DataCube G1（M0 + M1）实施与验收账本

范围：关系库 ConnConfig 消费路径的写入防误操作。本地工程目标，不代表真库或发布验收。M2–M8 不在本次实施范围；Redis 与独立 Oracle→PG 迁移使用独立模型，未纳入此只读开关。

## 检查点 1：M0 基线

- 目标：核实 main、隔离现场、复查既有测试异常。
- 基线：main `792600c59e49bce3b300a71ccf412ed53d2b42e9`；本地 origin/main 跟踪差异 ahead 15，未 fetch。主目录仅有既有未跟踪 `.testagent/` 和两份交接/路线图；不读取 `.testagent/`。
- 工作目录：`C:\Users\hetia\.codex\worktrees\datacube-g1-write-safety\朝花夕拾`；分支 `codex/datacube-g1-write-safety`。原样复制两份获准文档，合并前核对主目录副本。
- 工具：Temurin 25.0.1+8、JavaFX 25、wrapper 9.2.0、JUnit 5.11.3；未安装更新。
- 本轮验证：`gradlew.bat clean :buildSrc:test test --offline --no-daemon --console=plain --init-script <scratch>/isolated-tests.gradle`，exit 0，2m46s；应用 262 suites / 3484 tests / 3481 passed / 0 failures / 0 errors / 3 live skips；buildSrc 8/8 passed。
- 隔离：临时 init script 仅为 Test JVM 指定独占临时 `user.home` 和 `java.awt.headless=false`，移除 live Redis/Schema Diff 环境入口。镜像构建不携带该 init script。没有读取真实 profile、连接、SQL 历史或业务数据。
- 原始日志/XML：`C:\Users\hetia\AppData\Local\Temp\datacube-g1-0d3c605a0fee4b11a131afebf823765c`，`m0-tests.log`、`m0-app-xml/`、`m0-buildSrc-xml/`。保留既有 unchecked 编译提示。
- 失败/未验：本次全量未复现旧 SchemaDiffServiceTest 的 Schema snapshot failed；根因仍未明确，不声称修复。3 live skips 是未执行，不算通过。原生桌面、真库、安装升级、远端 CI 未验。
- 开发镜像：`gradlew.bat jpackageImage --offline --no-daemon --console=plain`，exit 0，38s；`m0-image.log`。保留 jlink 的 JEP 493 模块提示，不注入测试 profile/headless 参数。
- 下一步：M1 新增零资源获取回归，实施共享门禁。

## 设计决定

1. 写入使用不可变请求对象；确认绑定该对象，不用可转移的布尔值。请求固定连接配置、注册版本、操作和完整载荷，单次使用。配置变化、删除/重建（即使恢复相同字段）令旧请求失效；不按名称寻找替代目标。
2. 共享策略无 JavaFX 依赖。服务边界先检查只读/生产确认，再获取写资源。连接打开后再次核对版本，阻止建连等待期间发生的配置变化；已进入执行的操作保留原目标，不隐式提交/回滚或重放。
3. DataEdit、表设计、对象/序列 DDL 使用独占短连接，避免共享浏览连接参与行编辑内部事务。SQL 编辑器保留独占长会话及既有取消/严格关闭机制。
4. SQL 沿用 SqlSafetyAnalyzer/SqlSafetyPolicy 的保守分类。服务直接调用同样受控；生产提交单独确认，回滚、取消和清理不受新写入门禁阻挡。词法只读不等于数据库权限沙箱。
5. 技能只用于测试设计与断言质量；遵循项目约定，不引入通用多代理流水线、额外审批或 `.testagent/` 状态文件。

## 写入口矩阵

| 入口 | 服务/执行边界 | 资源与规则 | 本轮行为证据 |
| --- | --- | --- | --- |
| 表新增/修改/删除（含离行/翻页提交） | DataEditService → ConnectionManager.prepareWrite → DataEditor | 独占短连接；冻结表、参数和定位值；一次请求一次确认 | RelationalWriteSafetyTest：只读零 provider/建连/写入、开发/测试/生产、参数快照；JdbcDataEditorTest 保留影响行数和事务护栏 |
| 表结构应用 | TableDesignService → prepareWrite → SqlRunner | 独占短连接、完整 DDL 请求 | RelationalWriteSafetyTest 的 everyServiceWrite… / eachPreparedWrite… 覆盖 Oracle/PG |
| 对象 DDL / 序列应用 | DdlService → prepareWrite → SqlRunner | 独占短连接、完整 DDL 请求；旧 executeDdl 直接调用也受控 | 同上；WriteSafetyIntegrationTest 覆盖控件、目标/DDL 确认、取消零写 |
| SQL 脚本/预处理 SQL | JdbcEditorSession → WriteOperation.sql → SqlRunner | 固定会话，复用 SqlSafetyAnalyzer/SqlSafetyPolicy，冻结 SQL/Schema/参数 | JdbcWriteSafetyTest：只读拒绝、生产目标和参数请求绑定、旧许可不可转移；参数化事务控制拒绝 |
| EXPLAIN / EXPLAIN ANALYZE | JdbcEditorSession.prepareExplain → SqlRunner | ANALYZE 按执行准入；Oracle 普通 EXPLAIN 写 PLAN_TABLE 也按写入；PG 纯读取计划可用 | JdbcWriteSafetyTest 的 directReadonly… / productionRequests…，两 provider |
| 提交/模式切换/关闭时提交 | JdbcEditorSession.prepareCommit / SQL COMMIT | 绑定会话与事务版本，生产提交单独确认；SQL Editor 关闭计划携带相同请求 | JdbcWriteSafetyTest 的 productionCommit… / tighteningConfiguration… / closeAndRollback…；SqlEditorSessionContractTest 和既有关闭集成测试 |
| 回滚/取消/关闭清理 | JdbcEditorSession 生命周期 | 回滚不受新写入门禁阻断；不重放、不把取消视为回滚，保留严格清理 | JdbcWriteSafetyTest、JdbcEditorSessionTest、SchemaDeploymentCancellationTest、既有 FX 生命周期回归 |
| Schema Diff 部署 | SchemaDeploymentService → JdbcEditorSession | 保留漂移/计划门禁；目标配置注册版本、完整配置、目标 Schema、计划摘要绑定；逐步复核 | SchemaDeploymentServiceTest：只读零 fresh-read/建连、跨目标/Schema/版本/计划拒绝、中途收紧保留部分证据、control 单次使用 |

低层 provider/JDBC SPI 仍是数据库适配接口；本次封闭的是应用消费 ConnConfig 的编排入口，不将客户端只读描述成数据库权限沙箱。Redis 与独立迁移连接不在此矩阵中。

## 检查点 2：M1 服务与界面实现

- 目标：所有适用写入口共用服务层准入；基线为 M0 提交 `7b52198`。
- 改动：新增无 JavaFX 依赖的 WriteTarget / WriteOperation；目标固定到同一个 ConnectionManager 和配置注册版本；不可转移的请求确认与单次执行。建连后、分派前再次检查。行修改与短 DDL 不再借用浏览连接。
- 配置变化：删除、改名、provider/属性/环境/只读变化和 ABA 更新均使旧写请求失效。已打开编辑页接收失效通知，关闭时取消订阅；保留读/预览/导出。生产确认显示连接 ID、端点、库、用户、环境和操作范围，不显示密码，不写入日志。
- 事务：在途请求保持原目标；未提交事务不会因配置更新被隐式提交或回滚。新的提交同时检查目标及事务版本；用户仍可回滚或关闭。SQL 非法事务控制继续按既有分析器保守拒绝。
- Schema Diff：复用原有 SQL 计划校验和漂移检查。旧静态 plan digest 只表示计划，不可作为部署授权；实际界面和 deploy 使用 instance admission 的目标绑定 token。中途配置收紧停止后续步骤，保留已执行步骤及部分失败状态。
- 界面：DataGrid、表设计、对象、序列页面使用同一准入提示与确认；SQL Editor 的执行、分析、提交、模式切换和关闭提交携带已审阅请求。迟到确认明确显示失效原因。没有“忽略只读”入口，也未修改连接设置。
- 验证：最终定向选择 service.*、WriteSafetyIntegrationTest、SchemaDiff*、SqlEditor*、ObjectEditorPaneLifecycleTest，共 39 suites / 408 tests，408 passed、0 failures/errors/skips，26s。日志 `m1-targeted-final.log`、XML `m1-targeted-final-xml/`。
- 失败/未验：以下首次失败均保留；原生桌面和真库待验。下一步为最终全量/buildSrc/镜像、提交和 main 集成复验。

### 失败与修正记录（不以复跑覆盖）

| 运行 | 实际结果 | 处理 |
| --- | --- | --- |
| m1-red.log | 2 tests / 2 failed | 原 DataEdit 只读写操作进入 provider，mock 哨兵失败；Oracle/PG 同样暴露缺口 |
| m1-core-first.log | 54 tests / 11 failed | 服务层规则生效后，旧生命周期用例的无 WHERE 写入触发确认；改为明确 WHERE，保持原事务断言；不完整/不支持的事务 SQL 改为断言拒绝且不建连 |
| m1-services-second.log | 106 tests / 1 failed | 取消用例仍传旧 plan digest；改用绑定目标的 admission token，保留原取消结果断言 |
| m1-service-fx.log | 1890 tests / 4 failed | 对象构造签名及 SQL 源码路由契约改变；更新为不可变请求/确认路径，并补行为集成测试 |
| m1-targeted-third.log | 9 suites / 91 passed | 核心修正通过，属于中间证据 |
| m1-full-first.log | 265 suites / 3514 tests；3511 passed / 3 live skips | 这一轮编译后还发生审查修正，故不作为最终代码验证；buildSrc UP-TO-DATE 也不作为本轮新通过 |
| m1-authority-review.log | 定向通过，13s | 封住来自另一个 ConnectionManager 的 WriteTarget，增加建连后检查 |

### 本地差异审查

- 已检查所有修改的服务、FX 接线、测试契约及调用点；`git diff --check` 通过。只读拒绝在写连接获取/准备/执行前；生产确认不能跨请求、操作、目标或配置代际复用。
- 检查参数防变更：字符串映射复制、RowKey 的 Timestamp/Date/Time/byte[] 复制；只允许明确的不可变标量和标准 java.time 类型，拒绝未知可变类型。
- 建连期间配置变化由 latch 测试覆盖；驱动失败关闭独占连接；线程取消在准入处拒绝；页面关闭后的回调/剩余删除不再触发新写入。已进入驱动的请求仍遵循既有取消与部分结果契约。
- 保留现有 SQL 风险分类、事务、超时和严格清理；没有 SQL 格式化扩展、持久化格式变化、全局连接重构或 M2–M7 功能。
- 自动 FX 测试证明控件与对话框行为；不是原生桌面肉眼、键盘、缩放、主题验收。现有编译和 jlink 提示仍记录为提示。

## 检查点 3：M1 最终本地验证与待集成

- 目标：完成分支定向/全量/buildSrc/镜像门槛，审查后提交。源码与测试提交 `965c3a395db1166ecabb6cf2103c40eacee9e4b8`；M0 提交 `7b52198`。
- 最终全量：`gradlew.bat clean test --offline --no-daemon --console=plain --init-script <scratch>/isolated-tests.gradle`，exit 0，2m34s；265 suites / 3517 tests / 3514 passed / 0 failures / 0 errors / 3 live skips。`m1-full-final.log`、`m1-full-final-xml/`。
- buildSrc：单独执行 `gradlew.bat :buildSrc:test --rerun-tasks --offline --no-daemon --console=plain --init-script <scratch>/isolated-tests.gradle`，exit 0，8s；4 tasks 均实际执行，8/8 passed，0 skipped。`m1-buildSrc-final.log`、`m1-buildSrc-final-xml/`。
- 镜像：`gradlew.bat jpackageImage --offline --no-daemon --console=plain`，exit 0，39s；`m1-image-final.log`。DataCube.exe 生成，595968 bytes；检查 app/DataCube.cfg 不含测试 profile、headless 或合成验收入口。未启动安装/升级/发布流程。
- 跳过明确列项：RedisLiveIntegrationTest 的 standaloneRedisSupportsFiveTypesScanTtlAndLifecycle；SchemaDiffLiveIntegrationTest 的 oracleSafeDeploymentConvergesInDisposableSchemas 和 postgresqlSafeDeploymentConvergesInDisposableSchemas。测试子进程移除对应 live 环境入口，因此按原有 assumption 跳过，不计为通过；没有新增 skip。
- 失败/未验：最终测试无失败；保留 unchecked 和 JEP 493 提示。旧 SchemaDiffServiceTest 偶发失败本轮未复现，根因仍未知。外部验收仍未执行。
- 下一步：检查 main 未变、保留原始未跟踪交接/路线图副本，本地合并后在 main 再跑全量、fresh buildSrc 和镜像。复验完成前 M1 不标记本地工程完成。

## 待外部验收（不计为本地通过）

- 原生桌面：合成 profile 的窗口、键盘、确认取消、明暗主题、缩放。
- 授权的一次性 Oracle/PG：真实驱动只读、生产确认、事务、取消、DDL 隐式提交与 Schema Diff。
- 安装/便携升级和失败恢复；同 SHA 远端 CI；签名/凭据及发布。均未授权执行。
