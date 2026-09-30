# Oracle 授权真库验收账本

日期：2026-09-30。基线 main 4fa68014bab0ba3d4874d02bb31db874da66033e，独立 codex/oracle-live-acceptance。本轮按维护者提供的 Oracle scmtest 目标及随后明确的专用表授权验收；不包含 PG/Redis 真库、签名、安装升级或发布授权。

## 权限与实际测试边界

只创建唯一前缀表，新增、修改自己的合成行及注释；事务测试回滚未提交的合成变更。保留所有表及已提交数据，无 DELETE/TRUNCATE/DROP/用户或权限变更，不查询既有业务行。真实目标与口令通过临时进程环境注入，连接配置和加密口令仅在内存；user.home 为独占新目录，未读取原有连接/凭据/历史/业务文件/.testagent。探针与日志无硬编码凭据。现有 SchemaDiffLiveIntegrationTest 会创建并 DROP 用户，明确不启用，不因当前授权而放宽边界。

外置 Java 探针编译到独占临时目录，通过 --add-exports/--add-opens 调用实际 jpackage 运行时的服务。连接工厂只允许当前 Oracle 目标；JDBC 代理记录资源、拒绝删除等语句、约束专用表写入。元数据服务读取账户可见数据字典，结果断言只检查新表/字段，不输出其他对象名称或业务行。上述服务验收是真实 Oracle JDBC；后续程序化初始化与原生点击分开标注。

## 真库矩阵

Oracle 返回版本 23.26；指定用户和服务身份先比对，再创建表。首张表 DCA_D161141EA4BB43_T 已保留，最终提交合成 ID 为 1、2、12。旧镜像真实取消分类失败；没有删除、重建或抹除首轮证据。

| 精确探针项 | 必须断言 | 首轮 |
| --- | --- | --- |
| connect_identity / create_unique_fixture | 真实身份与授权服务一致，唯一新表和注释 | 通过 |
| real_insert_update_and_conflict | DataEditService 实际 INSERT/UPDATE 各影响 1 行，旧值冲突影响 0 且回滚，旧值不变 | 通过 |
| readonly_rejects_sql_insert_and_data_update_before_resources | 只读 SQL INSERT 与行 UPDATE 在连接获取/执行计数变化前拒绝 | 通过 |
| production_confirmation_bound_to_request_and_config | 未确认、异请求确认、重复使用、配置收紧后的旧确认均拒绝，自己的确认才写 1 行 | 通过 |
| manual_rollback_visibility / manual_commit_visibility / pending_close_rolls_back | 未提交跨连接不可见，rollback/close 不提交，明确 commit 可见 | 通过 |
| schema_name_catalog | 实际 SchemaObjectCatalog 能找到新表 | 通过 |
| metadata_column_name / metadata_object_comment / metadata_column_comment | 实际 SchemaMetadataSearch 三模式均只命中新表/字段的唯一 marker，无截断 | 通过 |
| readonly_data_page_and_oracle_empty_string | 只读分页读自己的 3 行，Oracle 字符空串为 NULL | 通过 |
| ddl_preview_only | 从真实 DBMS_METADATA 取得新表 CREATE TABLE，写计数不变 | 通过 |
| real_driver_cancel_lock_wait_and_recover | 仅锁自己的行，明确取消返回 CANCELLED，未提交修改，后继查询恢复 | **失败**：实际 TIMEOUT |
| real_driver_timeout_lock_wait_and_recover | 仅锁自己的行，查询超时返回 TIMEOUT，未提交修改，后继查询恢复 | 通过 |
| connections_closed_and_fixture_retained | 开关平衡，3 行保留，无删除语句 | 通过：32/32 |

首轮 acceptance 为 16 passed/1 failed；cancel-diagnostic 再次证实 ERROR/TIMEOUT，断言 CANCEL_TIMEOUT_CLASSIFICATION，约 9 秒返回。不是网络不可达或假定的代码风险。Oracle 对明确取消也可抛 SQLTimeoutException；普通/预编译/EXPLAIN 与共享预编译路径原先优先按异常子类标为超时。修复只交换取消与超时分类优先级，保留真实超时、既有脱敏和资源释放；PG 同代码模式以 mock 对照修复，无 PG 真库访问。

## 本轮工程验证和失败

新增 DriverCancellationClassificationTest：九路径 × 有/无取消，共 18 项。Oracle 普通/预编译/EXPLAIN 及 Schema 切换、PG 普通及 Schema 预编译、共享预编译；同一 SQLTimeoutException(vendorCode=1013) 下，明确取消必须 CANCELLED，无取消必须 TIMEOUT；执行/关闭/取消计数、控制句柄释放及预编译诊断脱敏同时断言。

| 记录 | 实际结果 |
| --- | --- |
| preflight | 真实身份 1/1 |
| acceptance | 真实 16 passed/1 failed，32/32 连接，0 删除语句 |
| cancel-diagnostic | 真实身份通过，取消再次失败，原日志保留 |
| cancellation-red | 18 tests：9 passed/9 failed，0 skipped，明确取消全部错分为超时 |
| branch-directed | 6 suites /114 passed，0 skipped，0 failure/error |
| branch-full | 310 suites /3919 tests：3916 passed、3 live skipped，0 failure/error |
| branch-buildsrc | 强制 --rerun-tasks，实际 buildSrc:test 8/8，0 skipped |
| branch-image / branch-image-audit | 新 jpackageImage；0 测试类/配置泄漏，PG/Oracle 零连接驱动发现通过 |
| branch-live | 修复镜像真实 17/17，33/33 连接，55 次执行、14 次专用对象变更、0 删除语句 |

全量测试进程剔除 live 环境；三项旧 live 跳过仍是 Redis、PG 与 Oracle Schema Diff，不计通过。本次新 Oracle 真库探针与这些跳过是不同场景，不能宣传原 Schema Diff 真库 smoke 通过。历史 SchemaDiffServiceTest 偶发失败根因未证实关闭。

## 修复后真库与原生桌面

修复镜像 runtime modules SHA 为 242CE98352FD2F1AE829C3F41CC3B078E05ECAD2AB43ED2B2F84C526AA28AD0E。第二表 DCA_D161141EA4BB44_T 保留 3 行，ID 1、2、12。17 项全通过，包括首轮失败的真实取消：CANCELLED，后继查询恢复；真正超时仍 TIMEOUT。分别约 9179/9189ms，不能称为立即响应。首轮红灯、诊断和 mock 红灯继续保留。

桌面夹具使用实际打包 AppShell，程序化初始化独占 profile、内存连接与仅专用表的树节点。后续点击和输入使用 Computer Use 原生 API，没有程序化按钮 fire。原生取得：只读数据页 3 行及禁用新增/删除/保存，Schema 直接字段入口、字段名查询真实 1 命中、选择后生成 SELECT 尚未执行、显式执行返回 3 行，表菜单查看真实 DDL（预览无执行）。图与 accessibility 原文在 evidence/oracle-live-acceptance/native；合成行与对象均为本轮专用。正常关闭只在 ShutdownOutcome.COMPLETED 后记录 returned：3 opened/3 closed、7 executions、0 mutations/deletion。新 profile 文件清单只有合成 SQL 草稿/历史和 FX 缓存，无持久连接/凭据文件；未读取 SQL 历史内容。

隐藏启动无可见窗口、工具索引失败、一次取屏夹入前台画面、第二次模态输入不可用/键盘未生效保留在记录。前台不符时未操作或保存错误画面；重绑后继续。第二弹窗的字段命中转数据/DDL 未验，表菜单证据仅证明表菜单路径，不替代它们。原生生产确认/写入、完整键盘、关闭在途等本轮未验。

## 当前未验与下一步

归档首次敏感字面扫描阻止暂存：五份 accessibility 文本含 10 处目标 host/service，口令命中 0；已替换为固定标记，记录原始与脱敏 SHA。第一轮 diff --check 的原字节 CRLF 误判亦保留；归档属性声明 cr-at-eol 后重查，不改写真实日志/XML。这些审查失败不算通过。

本地集成和 main 新工程/镜像/真库复验仍待执行。没有旧通过替代本次证据。

取消约 9 秒返回的观察保留；分类成功不能承诺立即响应或所有网络/驱动版本可中断。尚未验真实数据库账号权限收紧、故障网络或多用户压力，也未验证 Oracle 视图/全部对象设计/Schema Diff/迁移等全功能矩阵。PG/Redis 真库、完整原生/键盘/OS 多屏、正式启动器/安装升级/签名/远端 CI/真实用户任务/发布仍待验，M8 不称完成。
