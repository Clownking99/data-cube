# G7 / M7：迁移预检查、逐表结果与对账

授权：维护者在确认 G7/M7 范围后要求“继续推进”，沿用原有边界。仅本地工程；不访问真实连接、凭据、数据库、历史或业务文件，不读写 `.testagent/`，不推送/tag/PR/安装更新/发布/外部联系，不进入 G8。

## CP0：基线与实施切口

- 当前目标：完成 M7a–d 的本地实现、行为回归、审查、提交与 main 复验。
- 基线：main `0fb3583a1f0a22640e1f86d721852633d472fce7`，授权范围工作区干净；独立 `codex/datacube-g7-migration-evidence`，worktree `C:\Users\hetia\.codex\worktrees\datacube-g7-migration-evidence\朝花夕拾`。
- 改动：本检查点建立计划与新证据目录，产品实现尚未开始。无子代理，无新依赖。
- 验证：全新合成 profile 的 baseline clean test 正在执行。G6 的旧通过不作本轮结果。使用 Java 25.0.1+8、Gradle 9.2.0，离线；Test 子进程隔离 user.home 并去除 live 环境参数，镜像不带测试参数。
- 已发现：PgVerifier 仅目标统计，n_live_tup 是估算且总数被收窄到 int；导入逐批 commit 后自动重试整文件；DDL/逐表失败可被吞掉；完成标签不区分失败/取消；connect 在导出前也会创建目标 Schema；导出无跨表一致性窗口，部分类型有有损转换。
- 下一步：先 M7a 精确标注目标统计并参数化/关闭资源；再建立预检查、不可变请求/确认、逐表状态及重试门禁；最后做有明确范围的统计对账，并接入实际 UI。先 mock/合成目录，禁止用真实数据寻找证据。
- 失败/未验：本轮新测试结果未产生；真实 JDBC/catalog/快照/锁语义、原生桌面、安装升级和外部发布仍待验。

## 常规设计决定

- 不把对象存在、估算行数或有限统计相同称为数据完全一致。源读取窗口与目标校验时点分别记录；没有共同快照的比较必须明确范围，变化中的源库不能被宣传为全量一致。
- 失败/取消/提交结果未知分别记录；移除未经幂等证明的自动重放。重试须再次验证请求身份、输入文件摘要、目标状态及上次提交结论；恢复报告只读，不自动执行写入。
- 预检查仅读取；不能先创建 Schema 再确认。对目标已有数据、不支持转换/对象和权限未知给出具体限制，拒绝不能证明安全的自动路径；不承诺任意 PL/SQL 等价转换、CDC 或全迁移一键回滚。
- 导出文件属于敏感数据，测试只在独占临时目录生成合成文件。脱敏报告不保存 URL、用户名、密码、SQL、原始异常或数据值；本地界面明确目标，报告用操作内标识关联。

证据目录：`C:\Users\hetia\AppData\Local\Temp\datacube-g7-75f4f39caf5d46e4b1c30922a0804c7b`。

## CP1：目标端统计已明确边界（2026-09-25）

- 当前目标：M7a 完成本地定向验证，继续 M7b–d，不将单个增量当作 G7 完成。
- 改动：GUI/CLI 明确“目标端统计”；PgVerifier 保留 long 计数，未知估算与零区分；参数绑定 Schema，保留原 JDBC URL，限时只读读取并逐次关闭 statement/result；取消后不公布部分成功。
- 验证：baseline-full 新执行 297 suites / 3756 tests / 3753 passed / 3 live skipped，4m36s；日志 SHA256 `01E8574AB7C25C89F98E929AFF4EE87BA2AE454F9AECA4D9DA58456F2DD541E0`。基线 class 编译时间 23:59:13，源修改在次日 00:01:30，javap 确认仍为修改前 void verify 签名，未把新实现混作旧基线。
- 定向：m7a-targeted 实际 14/14、0 skipped，9s；日志 SHA256 `E1FF12B140216BAD5565CDFFF1ECA68C4F678CAA14D91E29974C29089D58C5D5`。PgVerifierTest 的 long/unknown/zero、查询失败、连接前/中/读取后取消均有直接断言。
- 失败/未验：首次 patch 因同文件 delete/add 被工具拒绝，无测试运行；改为有效修改后通过。M7b–d 尚未实现；真库、原生界面等仍未验。
- 下一步：预检查与不可变确认、逐表导入及状态报告；移除不安全自动重放，再接入有限统计对账。所有新增行为仍须后续全量和 main 复验。

参考：[PostgreSQL n_live_tup 定义](https://www.postgresql.org/docs/16/monitoring-stats.html)、[Oracle 事务级读取一致性](https://docs.oracle.com/en/database/oracle/oracle-database/21/cncpt/data-concurrency-and-consistency.html)、[PostgreSQL 表锁](https://www.postgresql.org/docs/16/sql-lock.html)。这些是设计依据，不能代替真库验收。
