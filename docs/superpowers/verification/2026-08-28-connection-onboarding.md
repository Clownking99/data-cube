# 连接到查询体验：本地验收记录

日期：2026-08-29。分支：`codex/connection-onboarding`。任务按当前任务内顺序实施、自查，没有启动子代理，不将自查称为独立审查。

## 交付范围

- 任务 1：空工作区引导已在 `707f71a`、`9b73c8e` 完成并推送；本轮重新运行 7 项相关测试，未改动该实现。
- 任务 2：异步连接测试检查点 `f3f5117` 补验；`e166ea1` 修复长失败反馈挤出窗口底部按钮，并验证三类型的 Tab 遍历。详见 `2026-08-28-connection-test.md` 的最新补验节。
- 任务 3：新增 `SqlConnectionGuidance` 纯投影并接入 `SqlEditorPane`。无连接或 Redis 时禁用执行与执行计划，F5 和直接动作处理器也拒绝执行；编辑和美化保持可用。隐藏无连接时的环境/只读标签，关系型候选仍显示待绑定。
- 首次准入固定连接时立即更新固定名称和提示，即使尚未创建专用会话也不继续显示“待绑定”。不新增绑定来源，不改动连接准入、事务、安全策略、关闭流程或后台执行队列。
- 执行按钮的状态同时考虑正在执行、会话队列待处理及关闭状态。没有新增网络、存储、密码读取、依赖、版本号、遥测或发布行为。
- 本轮保留本地提交，不推送、不合并、不打标签；既有 `.testagent/` 未读取、修改或暂存。

## 测试运行证据

| 阶段 | 结果 |
| --- | --- |
| 连接反馈新增可见边界断言 | 深浅主题两项均先失败；修复后通过 |
| SQL 纯投影 TDD | 新类型缺失导致编译失败；实现后 6 项通过 |
| 实际 SQL 页 TDD | `missingAndRedisPagesBlockButtonsAndShortcutWithoutOpeningSessions` 在“未绑定仍可执行”断言失败；接入后通过 |
| 固定目标提示边界 | `pinnedDisplayDoesNotFollowRedisOrAnotherCandidate` 先失败（准入后仍显示待绑定），修复后通过 |
| SQL/准入/队列/专用会话相关回归 | 10 套件、89 项、0 失败、0 跳过（补充最后一项队列忙碌用例之前） |
| 最后普通全量 | 116 套件、827 项、0 失败、0 错误、3 跳过；退出 0 |

最终运行命令（无额外图形参数）：

```powershell
./gradlew.bat test --rerun-tasks --offline --no-daemon --console=plain
git diff --check
```

保留 build 下的本轮截图，所以使用 `--rerun-tasks` 而非 `clean`；重新执行了 compileJava、compileTestJava 与 test，不把该命令写成 clean 验证。早期普通全量曾因显示检测跳过 UI 用例，后续普通运行恢复，根因未定；没有修改 CI 默认策略。

最终 XML 中 `ConnectionDialogTest` 19 项、`ConnectionTestControllerTest` 4 项、`SqlConnectionGuidanceTest` 6 项、`SqlEditorConnectionGuidanceTest` 5 项、`WorkspaceStartPaneTest` 7 项，全部 skipped=0。仅 `RedisLiveIntegrationTest` 1 项和 `SchemaDiffLiveIntegrationTest` 2 项外部集成测试跳过。XML/HTML 位于 `build/test-results/test/` 和 `build/reports/tests/test/`，为本地忽略产物。

## Requirement / Evidence

| Requirement | Evidence |
| --- | --- |
| 空工作区显示引导，打开/关闭标签后恢复，关闭拒绝不替换内容 | `WorkspaceStartPaneTest.emptyOpenAndCloseKeepTheSameTabNode`、`rejectedManagedCloseDoesNotReplaceTheContent` |
| 开始页按钮仅调用对应入口，不自动选择/展开连接 | `WorkspaceStartPaneTest.actionsDoNothingUntilClickedAndInvokeOnlyTheirCallback`；其余聚焦测试见任务 1 记录 |
| 慢测试不阻塞 FX，重复点击仅单请求 | `ConnectionDialogTest.slowOperationLeavesFxResponsiveAndCloseSuppressesResult`、`pendingDisablesSaveAndFormButRetainsCancelAndFailureInput` |
| 成功、错误字符串、异常、提交拒绝都恢复可操作状态 | `ConnectionTestControllerTest.nonNullResultAndExceptionAreSafeFailuresAndCanRetry`、`submissionRejectionRecoversAndCanRetryWithoutRawException`、`ConnectionDialogTest.submissionRejectionRestoresControlsAndKeepsDialogOpen` |
| 修改配置使旧结果失效；保存独立；密码留空沿用原密文 | `ConnectionDialogTest.everyEditedFieldInvalidatesPreviousResult`、`saveIsIndependentAndEditPreservesCipherForEveryProvider` |
| 关闭后抑制迟到回调且不关闭应用 runner | `ConnectionTestControllerTest.closeDropsBothLateCallbacksAndIsIdempotent`、`ConnectionDialogTest.slowOperationLeavesFxResponsiveAndCloseSuppressesResult` |
| 测试不建立缓存会话 | `ConnectionManagerDedicatedSessionTest.probeUsesTestPathWithoutOpeningOrCachingASession` |
| 错误不泄露原文，动态字段有可访问名称 | `ConnectionDialogTest.pendingDisablesSaveAndFormButRetainsCancelAndFailureInput`、`newDialogHasNoRequestAndDynamicFieldsHaveNames` |
| 深浅主题长反馈和按钮均完整；键盘遍历可见字段与操作 | `ConnectionDialogTest.failureTextFitsAfterIdleToFailedTransition`、`tabTraversalFollowsVisibleFieldsAndReachesActions` |
| 无连接/Redis 不执行，F5/直接事件同样拒绝，编辑与美化仍可用 | `SqlEditorConnectionGuidanceTest.missingAndRedisPagesBlockButtonsAndShortcutWithoutOpeningSessions` |
| PG/Oracle 仅待绑定，不能因候选变化重新启用忙碌/关闭页 | `SqlEditorConnectionGuidanceTest.candidateSelectionShowsPendingAndCannotOverrideBusyOrClosing` |
| 非 SQL 执行中的会话操作同样阻止执行入口 | `SqlEditorConnectionGuidanceTest.candidateChangeRespectsPendingSessionOperationEvenWhenSqlIsNotRunning` |
| 固定 A 后选择 B/Redis/无连接不改变目标显示 | `SqlEditorConnectionGuidanceTest.pinnedDisplayDoesNotFollowRedisOrAnotherCandidate` |
| 原准入固定与关闭后不得发布会话的规则不回退 | `SqlEditorConnectionAdmissionTest.firstRelationalAdmissionPinsAndLaterActionsCannotSwitchConnection`、`closeAfterExecuteAdmissionButBeforeSessionPublicationCannotCreateSession` |

SQL UI 测试仅注入空服务；关系型候选标记为已预热以排除原有元数据 I/O。它们断言真实按钮、提示、场景、快捷键和准入状态，不执行数据库 SQL。队列忙碌测试用可控 latch 和真实队列，不依赖真实网络或固定睡眠。

## 当前源码的桌面检查

使用 `build/product-verification/connection-test/` 内两个临时验收工具，分别组合真实 `ConnectionDialog` 与 `SqlEditorPane`，只用虚构配置和模拟操作。工具、截图不进入应用打包或提交；未使用公司凭据、展开真实连接或执行真实 SQL。

| 截图 | 内容 |
| --- | --- |
| `04-dark-failure-fixed.jpg`、`05-light-failure-fixed.jpg` | PG 新建/编辑，深浅主题失败反馈与按钮完整 |
| `06-oracle-new.jpg`、`07-oracle-edit.jpg` | Oracle 服务名、默认端口、编辑密码提示 |
| `08-redis-new.jpg`、`09-redis-edit.jpg` | Redis DB 索引、关系型字段隐藏、编辑密码提示 |
| `10-sql-unbound-dark.jpg` | 暗色未绑定，执行/执行计划禁用，环境标签隐藏 |
| `11-sql-candidate-pg.jpg`、`12-sql-candidate-oracle.jpg` | 关系型候选显示待绑定，未写成已连接 |
| `13-sql-redis-dark.jpg`、`14-sql-redis-light.jpg` | 两主题下 Redis 控制台指引，SQL 入口禁用 |
| `15-sql-unbound-light.jpg` | 亮色恢复无连接提示 |

SQL 提示在当前 1200×800 验收窗口完整显示。三种类型表单的字段名称通过可访问性检查；模态物理按键投递不稳定，Tab 顺序以真实 FX 控件事件测试为证据。任务 1 的完整应用启动/标签工作流桌面验收沿用已完成记录，本轮没有伪称重新执行了该整段人工流程。

## 自查与边界

- 自查了生产 diff、准入/当前候选监听、事务模式处理及关闭路径；固定连接刷新仅改名称和提示，不重置事务控件。未发现本轮新增的阻断问题；这不是独立第三方审查。
- 未验证真实 Oracle/PostgreSQL/Redis 服务的连接、查询、驱动中断耗时，以及新安装包的端到端行为。关闭 UI 仍不承诺驱动立刻中断；没有制造这类测试数据。
- 未重复此前已验收的完整应用启动流程，也未进行专业读屏器验收。截图为当前源码控件而非安装包。
- Git 仓库没有新增依赖、凭据或本地验收文件；仅保留当前分支，等待用户决定推送/合并。
