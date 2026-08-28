# Task 2 检查点：异步连接测试与关闭保护

初始检查点日期：2026-08-28。2026-08-29 已补齐任务 2 回归及桌面复验；下文保留初始检查点证据，最新结论见末尾补验记录。

## Git 与范围

- 本轮先通过单次命令代理 `http://127.0.0.1:7897` 推送 `codex/connection-onboarding`，`ls-remote` 核对远端为 `9b73c8e502a8c86a82163883eae32332c2ad9d3a`。未修改全局 Git 代理，未合并 main 或打标签。
- 随后继续任务 2；本记录所在的新代码检查点仅本地提交，尚未再次推送。
- 生产修改仅 `ConnectionDialog`、`ConnectionTreePane` 和新增 `ConnectionTestController`。复用应用 runner，各对话框只关闭自己的 scope，不修改 provider、连接管理服务、持久化格式或凭据方案。
- 测试新增两个类，并在 `ConnectionManagerDedicatedSessionTest` 增加一项真实 service 路径回归；未读取、修改或暂存既有 `.testagent/`。
- 按已确认的执行方式在当前任务内顺序实施并自查，没有启动子代理，也不将自查描述为独立审查。

## 实现与计划差异

- 测试使用点击时的配置快照，在后台执行；对话框显示进度，测试中禁用表单/测试/保存，保留取消和关闭。
- null 结果才表示成功；错误字符串、异常和提交拒绝均转换为固定安全文案，不展示原始异常或连接 URL。
- 修改任意配置字段会使之前结果失效；测试失败后仍可独立保存有效配置，不隐式重测或连接。
- 关闭窗口请求中断并抑制迟到回调，不承诺驱动立即停止；测试不发布缓存会话。
- 补齐全部表单标签关联、动态名称和字段 ID；原校验规则不变，校验后聚焦对应字段。
- **修正计划实现细节：** 原 resultConverter 在返回 null 时仍关闭对话框。新增测试实际复现“无效保存后表单消失”，因此把保存校验移至按钮事件拦截；仅有效配置设置结果并关闭。
- **视觉检查发现：** 较长失败说明会被原单行高度压缩。新增深浅主题布局测试，两项均先失败；已设置状态 Label 最小高度为其首选高度。修复后需在可访问桌面重新验证，不能仅凭构建成功认定修复完成。

## 本轮运行证据

| 阶段 | 结果 |
| --- | --- |
| 实施前 `test --rerun-tasks --offline --no-daemon --console=plain` | 退出 0 |
| 控制器 TDD 红灯 | 新类型缺失导致编译失败，随后实现；4 项状态测试通过 |
| 对话框 TDD 红灯 | 新 create API 缺失导致编译失败，随后接入 |
| 保存边界红灯 | 18 项中 `invalidSaveKeepsDialogAndFocusWithoutRequest` 失败，实际窗口已关闭 |
| 修正保存后相关回归 | 8 套件，49 项，0 失败、0 跳过；退出 0 |
| 布局调整前全量重跑 | 114 套件，811 项，0 失败、0 错误、3 跳过；14 项新对话框用例实际执行 |
| 布局红灯 | `failureTextFitsAfterIdleToFailedTransition` 深浅主题两项均失败，状态区高度不足 |
| 布局调整后窄测试命令 | 退出 0，但该轮 XML 未在下一次全量运行前核对保留，不能证明两项实际执行而非跳过 |
| 最后全量重跑 | 114 套件，813 项，0 失败、0 错误、27 跳过；退出 0。`ConnectionDialogTest` 16 项全部因显示环境不可用跳过，控制器 4 项实际运行 |

全量命令：

```powershell
./gradlew.bat test --rerun-tasks --offline --no-daemon --console=plain
```

相关回归命令：

```powershell
./gradlew.bat test --tests 'com.datacube.fx.ConnectionTestControllerTest' --tests 'com.datacube.fx.ConnectionDialogTest' --tests 'com.datacube.fx.task.FxTaskScopeTest' --tests 'com.datacube.fx.ConnectionTree*' --tests 'com.datacube.service.ConnectionManager*' --tests 'com.datacube.redis.RedisSessionManagerTest' --tests 'com.datacube.config.CredentialCipherTest' --offline --no-daemon --console=plain
```

布局窄测试命令：

```powershell
./gradlew.bat test --tests 'com.datacube.fx.ConnectionDialogTest.failureTextFitsAfterIdleToFailedTransition' --offline --no-daemon --console=plain
```

最后的 27 项跳过不同于此前 3 项外部集成跳过。**必须在显示环境恢复后重跑，并确认新对话框 16 项 skipped=0；不能把跳过视为通过。** 本轮使用 rerun-tasks 而非 clean，以保留任务 1 的本地截图。报告位于忽略目录 `build/test-results/test/`。

## Requirement / Evidence

| Requirement | Evidence |
| --- | --- |
| 一个对话框最多一个测试请求，使用快照而非后台读取控件 | `ConnectionTestControllerTest.singleFlightSnapshotAndNullSuccess` |
| 正常返回错误字符串和异常都算失败，可重试且不显示原文 | `nonNullResultAndExceptionAreSafeFailuresAndCanRetry` |
| 提交失败恢复可操作状态 | `submissionRejectionRecoversAndCanRetryWithoutRawException`、`ConnectionDialogTest.submissionRejectionRestoresControlsAndKeepsDialogOpen` |
| 测试中禁用表单和保存，取消可用，失败保留输入且仍能保存 | `pendingDisablesSaveAndFormButRetainsCancelAndFailureInput` |
| 保存独立，三种数据库编辑时留空沿用原密文 | `saveIsIndependentAndEditPreservesCipherForEveryProvider`（3 种类型） |
| 无请求时显示未测试，字段有名称，类型切换更新标签/显隐 | `newDialogHasNoRequestAndDynamicFieldsHaveNames` |
| 任意配置字段修改后旧结果失效 | `everyEditedFieldInvalidatesPreviousResult` |
| 必填、端口、超时、Redis 范围校验不请求且聚焦字段 | `invalidTestKeepsDialogAndFocusWithoutRequest`（4 组） |
| 无效保存保留表单 | `invalidSaveKeepsDialogAndFocusWithoutRequest` |
| 后台慢操作不阻塞 FX；取消/关闭请求中断；runner 仍可用 | `slowOperationLeavesFxResponsiveAndCloseSuppressesResult`（2 种关闭方式） |
| 隐藏后忽略迟到成功与失败，清理幂等 | `ConnectionTestControllerTest.closeDropsBothLateCallbacksAndIsIdempotent`，以及既有 `FxTaskScopeTest.dropsQueuedUiCallbackWhenClosedBeforeDispatch` |
| 不走缓存会话建连路径，不污染注册配置 | `ConnectionManagerDedicatedSessionTest.probeUsesTestPathWithoutOpeningOrCachingASession` |
| 深浅主题下失败说明不裁切、不覆盖按钮 | `failureTextFitsAfterIdleToFailedTransition`（2 种主题；修复后仍待确认非跳过绿灯） |

## 视觉验收：部分完成

使用忽略目录 `build/product-verification/connection-test/` 内的临时 `ConnectionDialogSmoke.java` 和 `smoke.init.gradle`，以真实 `ConnectionDialog.create`、`FxTaskRunner` 和当前主题样式构造独立窗口。它没有 ConnectionManager 或 ConnectionStore，只返回假错误/成功；设置文件也限定在该 build 子目录。临时源码不参与正式打包或 CI。

```powershell
./gradlew.bat connectionDialogSmoke -I build/product-verification/connection-test/smoke.init.gradle --offline --no-daemon --console=plain
```

- 已观察 PG 新建表单的未测试、测试中和失败状态，字段可访问性名称可读。测试中有进度、表单/保存禁用、取消可用；假操作完成后恢复按钮，无额外结果弹窗。
- 截图：`01-pg-idle.jpg`、`02-pg-testing.jpg`、`03-failure-clipped-before-fix.jpg`。第三张记录的是**修复前裁切问题**，不得用作验收通过证据。
- 界面工具对所属模态窗口的索引定位不可靠，实际输入以刚获取的模态截图坐标为准；未按错误位置输入任何内容。
- 布局调整后的重启/截图复验被桌面访问中断，重试激活仍报 `GetCursorPos failed: 拒绝访问。 (0x80070005)`。已停止桌面输入，没有绕过锁屏限制。
- 剩余：修复后失败说明截图、亮色状态反馈、新建/编辑 PG/Oracle/Redis 的实际显隐及键盘顺序。自动化用例不能替代这些视觉验收。
- 旧验收实例可能仍在运行（其代码不含最后的高度调整）；恢复桌面后先关闭旧实例，再运行以上命令。原 DataCube 窗口未被更改。本轮未执行真实 SQL、测试实际网络或使用公司凭据。

## 下一步

恢复桌面访问后完成布局和交互复验、全量测试非跳过核对，再将任务 2 标记完成。任务 3 保持待实施。当前检查点不是发布或合并完成的声明。

## 2026-08-29 补验与修正（最新）

- 在 `f3f5117` 上恢复工作，先重跑 `ConnectionDialogTest` 16 项及 `ConnectionTestControllerTest` 4 项：全部通过，skipped=0。
- 真实窗口复验发现：此前仅保证失败文字自身高度，仍会把底部按钮挤出窗口。为 `failureTextFitsAfterIdleToFailedTransition` 增加“测试、保存、取消全部位于 Scene 可见边界内”的断言，深浅主题两项均先失败。
- 修复：反馈区用不可见但参与布局的失败文案预留换行空间；不改变测试状态、网络、保存、取消或凭据逻辑。修复后 20 项窄回归全部通过。
- 新增 `tabTraversalFollowsVisibleFieldsAndReachesActions`，向真实 FX 控件发送 Tab，逐一断言三种类型的焦点顺序，Redis 跳过关系型专属字段，最后到达测试、保存和取消；不发起请求。三项通过。桌面工具向所属模态窗口发送按键不稳定，因此不把工具未确认的物理按键效果计为通过；键盘行为证据来自上述控件测试。
- 桌面观察覆盖 PG/Oracle/Redis 的新建和编辑：服务名/DB 索引、默认端口、Redis 字段显隐和编辑密码留空提示符合预期；PG 深浅主题失败反馈完整，三个按钮均未裁切。只用 `example.invalid` 假配置和模拟操作，不调用真实连接服务。
- 本轮早期普通全量运行曾出现 27 项显示依赖跳过；显式图形模式的本地复核恢复为 3 项外部集成跳过。随后**不带额外图形参数**的普通全量也通过，不能据此把早期差异定因为代码或锁屏。未修改 CI、测试默认配置或 OS 设置。
- 最后普通全量命令：`./gradlew.bat test --rerun-tasks --offline --no-daemon --console=plain`。116 套件、827 项、0 失败、0 错误、3 项外部集成跳过；`ConnectionDialogTest` 19 项、控制器 4 项全部实际执行。该全量包含本轮 SQL 引导增量，详见 `2026-08-28-connection-onboarding.md`。
- 使用 `--rerun-tasks` 重新编译和运行，以保留 build 内验收截图；没有运行 `clean`，也不把它写成 clean 验证。

本轮新截图位于 `build/product-verification/connection-test/`（本地忽略产物）：

| 文件 | 观察内容 |
| --- | --- |
| `04-dark-failure-fixed.jpg` | 新建 PG 暗色失败反馈，按钮完整 |
| `05-light-failure-fixed.jpg` | 编辑 PG 亮色失败反馈，按钮完整 |
| `06-oracle-new.jpg` / `07-oracle-edit.jpg` | Oracle 新建/编辑字段与默认端口 |
| `08-redis-new.jpg` / `09-redis-edit.jpg` | Redis 新建/编辑字段显隐与密码提示 |

这些截图替代修复前的裁切截图作为视觉证据。验收工具是本地临时代码，不进入应用、CI 或提交。其退出时曾记录 `Platform.exit` 早于窗口默认关闭引起的异常（进程最终退出）；这是验收工具的关闭顺序，不是生产 `ConnectionDialog` 的关闭路径，也不作为生产关闭通过证据。生产关闭证据仍是实际执行的 scope/对话框回归测试。
