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

## 写入口矩阵（实施中）

| 入口 | 服务/执行边界 | 资源与规则 | 本轮行为证据 |
| --- | --- | --- | --- |
| 表新增/修改/删除（含离行/翻页提交） | DataEditService → DataEditor | 独占短连接、明确写入 | RelationalWriteSafetyTest（先红后绿，待执行） |
| 表结构应用 | TableDesignService → SqlRunner | 独占短连接、完整 DDL 请求 | 待实施 |
| 对象 DDL / 序列应用 | DdlService → SqlRunner | 独占短连接、完整 DDL 请求 | 待实施 |
| SQL 脚本/预处理 SQL | JdbcEditorSession → SqlRunner | 固定会话、保守分析 | 待实施 |
| EXPLAIN / EXPLAIN ANALYZE | JdbcEditorSession → SqlRunner | 区分计划与执行；Oracle 计划写 PLAN_TABLE | 待实施 |
| 提交/模式切换/关闭时提交 | JdbcEditorSession.commit / SQL COMMIT | 绑定事务版本，生产确认 | 待实施 |
| 回滚/取消/关闭清理 | JdbcEditorSession 生命周期 | 保持可达；不重放、不将取消视为回滚 | 待实施 |
| Schema Diff 部署 | SchemaDeploymentService → JdbcEditorSession | 漂移/计划门禁复用并绑定目标 | 待实施 |

## 待外部验收（不计为本地通过）

- 原生桌面：合成 profile 的窗口、键盘、确认取消、明暗主题、缩放。
- 授权的一次性 Oracle/PG：真实驱动只读、生产确认、事务、取消、DDL 隐式提交与 Schema Diff。
- 安装/便携升级和失败恢复；同 SHA 远端 CI；签名/凭据及发布。均未授权执行。
