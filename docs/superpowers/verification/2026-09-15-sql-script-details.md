# 多语句执行详情验收

## 范围

独立 `codex/sql-script-details`，基线 main `49f21bf`。设计见[执行详情](../specs/2026-09-15-sql-script-details.md)。
生产变更为有界执行报告模型、只读详情弹窗/控制器及 SqlEditorPane 接入；不新增依赖、网络、持久化、SQL 执行或事务动作。
根目录 `.testagent/` 未读取、修改、暂存。本轮不推送、不打 tag。

## 需求与证据

| 需求 | 测试 |
| --- | --- |
| 混合失败不能显示绿色；正常/超时/取消分别统计 | `SqlScriptDetailsIntegrationTest.mixedFailureIsNotReportedAsGreenSuccess`、`summaryDistinguishesNormalTimeoutAndCancellation` |
| 所有已返回项计数，包括 1000 条显示上限以外的失败 | `SqlScriptExecutionReportTest.entryCapDoesNotHideFailuresFromTotalCounts` |
| 查询行数/截断、更新行数未知、失败分类 | `countsReturnedKindsAndExplainsUnknownAndTruncatedRowCounts` |
| SQL/错误单段 16 Ki、合计 1 Mi UTF-16 上限；代理对完整 | `fieldsAndAggregateAreBoundedWithExplicitOmission`、`exactLimitIsNotTruncationAndSurrogatePairIsNotSplit` |
| 不读取查询行/单元格，不保留数据对象；文本仍为只读纯文本 | `detachedSnapshotReadsOnlyRowCountAndNeverAccessesQueryCells`、`SqlScriptDetailsDialogTest` |
| 排序后正确映射原 SQL，完整长错误、Esc、重复打开 | `sortedRowOpensOriginalSqlAndFullErrorWithoutChangingFileEditorOrUndo`（真实 Stage） |
| Enter 需选中；修饰键/内容相等但身份不同的外来行无效 | `enterRequiresSelectionAndIgnoresModifiedShortcutAndEqualButForeignRows` |
| 查询/错误/计划/清除/新批次/关闭清理旧弹窗与映射 | `replacingResultClosesDialogAndDiscardsOldSelection`（6 个场景，真实 Stage） |
| 无效候选查询保留当前批量证据 | `rejectedQueryDoesNotDiscardCurrentBatchEvidence` |
| 关闭/任务域关闭/禁用/运行/准入及停止接收守卫 | `staleActionsCannotOpenBlockedPane`（8 个场景） |
| 窄/宽、明暗主题按钮可达 | `batchEntryFitsNarrowAndWidePanels`（480/640/880） |

测试技能用于以上行为与边界检查，不增加通用流程工件。集成夹具的连接/provider/session/metadata/network 探针为零，源 SQL 文件逐字不变；编辑后的选区、脏状态和撤销可正常恢复。

## 自动验证

环境 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，Gradle 参数 `--no-daemon --console=plain`。

1. 初次测试夹具 FxTaskRunner 构造签名写错，修正为项目现有默认构造器；该编译失败不计为行为 RED。
2. 真正 RED：真实 SqlEditorPane 混合错误仍含 `-status-ok`，1 项失败，16 秒 exit 1。
3. 接入后同一回归 GREEN：1 项通过，13 秒 exit 0。
4. 新增边界初跑 31 项、3 项失败：测试调用计划方法签名错误、把 UPDATE 误当作会拒绝的查询候选、试图通过会冻结校验的 QueryResult 工厂传入任意对象。分别改为实际签名、已有空候选失败路径、计数可读但行访问会抛错的测试替身；不改变生产语义来迁就夹具。
5. 定向联跑新功能、现有筛选和面板布局：101 项通过，无失败/错误/跳过，17 秒 exit 0。
6. `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`：2 分 50 秒 exit 0。XML 2586 项 / 2583 通过 / 3 既有 live 跳过 / 0 failures/errors；buildSrc 8 项通过。
   保留既有 unchecked、JAVA_TOOL_OPTIONS 导致的工具探测文本和 JEP 493 提示，不称无警告。

## 隔离桌面验收

使用电脑操作技能启动 `build/script-details-desktop/DataCube/DataCube.exe`；仅在镜像副本 patch 单一测试夹具类和独立 `script-details-desktop-profile`。
没有注册连接，5 条 QUERY/UPDATE/失败/超时/取消均为合成返回值。生产镜像入口仍是 `com.datacube.DataCubeFx`，runtime 不含 DesktopFixture 或 DraftConnectionProbe。

- 暗色 980 宽窗：摘要显示正常 2、失败 1、超时 1、取消 1，底部红色；选择语句 #3 后 Enter 打开原 SQL、5ms、完整长错误与末尾 SQLState=42703。`<script>` 保持普通文本。
- # 列倒序成为 5/4/3/2/1；选择首行点击执行详情，仍显示语句 #5、取消、50ms 和 cancelled_result SQL。
- 640 窄窗下入口和统计可见，宽表可横滚。切换亮色后弹窗文字、字段和关闭按钮可见；弹窗独立于较窄的父窗，可完整显示。
- 使用关闭按钮返回；切换合成单查询后详情入口消失，原查询数据表和工具恢复。
- 原生 Enter 已实测。工具未将 JavaFX 模态弹窗独立列为可操作窗口，向父窗发送 Esc 未关闭，因此**不将原生 Esc 计为通过**；Esc 关闭由真实 Stage 的自动化事件测试覆盖，不等同于原生键盘验收。
- 正常关闭夹具后精确 exe 路径进程数为 0；源 SQL 按物理换行逐字比对不变。

经验证生产 runtime/lib/modules SHA-256：`A6DF32DF7BCD2D6CC01DE1FC696E2F71072203B7A3A763DAFEF8016F6741798F`。

## 本地集成

功能提交及 main 合并后的定向测试/镜像核对在集成后补记。本轮不推送、不打 tag。
