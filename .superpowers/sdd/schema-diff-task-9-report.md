# Schema Diff Task 9 实施报告

## 结果

Task 9 已完成：新增 JavaFX Schema Diff 可视化工作流、稳定的分组/筛选/选择模型、部署确认与导出交互、连接树入口，以及受管标签的完整生命周期接线。实现只复用 Task 1–8 的 Schema Diff 服务与渲染契约；未连接 live DB，未改变 provider SQL、计划顺序、部署状态或安全确认语义。

## 实施范围

- `SchemaDiffSelectionModel`：按 planner 顺序稳定分组和筛选；安全自动项默认选中，破坏性项默认关闭，手工项不可选择；依赖缺失保持阻塞；选择变化使确认失效；重命名建议仅展示。
- `SchemaDiffViewModel`：覆盖 `IDLE / LOADING / READY / DEPLOYING / CANCELLING / COMPLETED / FAILED / DRIFTED`；比较、部署、取消和导出文件均提交到 pane 自有 JDK 25 虚拟线程 scope；晚到回调由 generation、closed 和 UI revision 隔离。
- `SchemaDiffPane`：提供源/目标连接和 Schema、差异树、对象/风险/自动化/选择筛选、结构化详情、源/目标定义、所选 SQL 预览、诊断、导出、部署与取消；错误和状态使用固定脱敏文案。
- `SchemaDiffDialogs`：确认信息包含目标身份、Schema、变更数、生产环境和 Oracle 提示；生产或破坏性计划使用精确 digest；破坏性计划还要求再次输入目标 Schema comparison key。
- `ConnectionTreePane`：关系型 CONNECTION/SCHEMA 节点增加 `Schema 对比...`，Redis 节点不出现该入口。
- `AppShell`：通过一次 `openManagedTab` reservation factory 打开受管标签；ConstructionOwner 在构造过程中立即接管阻塞资源；交互关闭和强制关闭复用同一 fatal-once guard；先后台清理，后 FX-only finalizer。

## 已批准的窄生命周期 seam

为满足 Task 9 受管关闭必须严格回收 Task 8 retained deployment sessions 的要求，在 `SchemaDeploymentService` 新增同步 `closeRetainedSessionsStrict()`。这是唯一超出纯 FX 文件的生产改动，已由父任务明确批准。

契约和实现：

- 首次调用封住新 deploy admission，并等待所有已接纳部署结束；该阻塞方法只由 pane 的后台关闭守卫调用。
- 对所有 retained sessions best-effort 重试；只有 strict close 成功后才移除 ownership。
- 任一 session 仍失败时抛出固定脱敏异常，使 close guard 进入 `FAILED_PARTIAL`，不报告假成功。
- 生命周期锁串行化并发/重复 close，成功后幂等；deploy 与 close 竞态由 active deployment 计数和 seal 保证，不丢失晚到 retained session。
- 未改变 SQL、执行顺序、部署状态映射、确认 token 或 provider 行为。

## TDD 证据

严格按 RED → GREEN 实施：

- Selection model：先得到缺少 `SchemaDiffSelectionModel` 的编译 RED，再实现默认选择、破坏性 opt-in、手工/阻塞禁用、稳定筛选分组、确认失效和重命名展示，4 tests GREEN。
- ViewModel：先得到缺少 `SchemaDiffViewModel` 的编译 RED，再实现八状态、异步 compare/deploy/cancel/export、确认与晚回调隔离，6 tests GREEN。
- Pane/lifecycle/dialog/tree：先得到缺少 Pane/Dialogs 的编译 RED，以及 tree action/menu 运行时 RED；实现后 10 tests GREEN。
- Construction ownership：新增“虚拟线程 scope 创建后立即登记 ConstructionOwner”契约，先 RED 后 GREEN。
- 选择控件：新增“手工/阻塞项拒绝点击后恢复 checkbox”契约，先 RED 后 GREEN。
- AppShell 构造失败：新增保留 `SafeConstructionFailure` mandatory-abort ownership 的契约，先 RED 后 GREEN。
- Task 8 seam：先得到缺少 `closeRetainedSessionsStrict()` 的编译 RED，再实现 retained cleanup、固定失败、串行幂等和 service seal。
- deploy/close race：测试先用有意移除等待的临时回归证明 RED，再恢复 active-deployment 等待后 GREEN；覆盖 retained session 第三次 strict close 成功和 close 后拒绝新 deploy。

## 生命周期与安全自审

- 关闭顺序为：seal pane admission → 请求 cancel → `ExecutorService.close()` 等待自有工作 → strict service cleanup → FX finalizer 移除监听/handler 并禁用节点。
- interactive close 只有存在运行工作或选择时才询问；mandatory close 从不弹窗；用户拒绝后标签仍可用。
- compare/deploy 的 `CompletionStage.join()` 只出现在 owned virtual-thread worker 中；Pane/FX handler 无 JDBC、无阻塞等待。
- 失败状态、对话框、树节点和 `toString()` 不拼接 provider exception message、JDBC URL、凭据或整段 SQL；SQL/DDL 仅在明确的预览和定义详情区域显示。
- AppShell 只调用一次 `openManagedTab`；binding 在 spec 返回前安装；构造失败保持 mandatory-abort ownership。
- 未发现阻断性自审 finding；没有已知功能残留。

## 新鲜验证

- Task 9 focused：5 suites，20 tests，0 failures，0 errors，0 skipped。
- Task 1–9 matrix：28 suites，251 tests，0 failures，0 errors，0 skipped。
- Full：106 suites，675 tests，0 failures，0 errors，1 skipped。
- `codegraph sync`：Already up to date；`codegraph status`：index is up to date（358 files / 9,536 nodes / 29,562 edges）。
- 未连接 live DB。
- 未读取、修改、暂存或提交 `.testagent/`。

## 提交

独立提交消息：`feat: 添加 Schema Diff 可视化工作流`。最终 SHA 由完成回报提供；提交对象包含本报告，报告自身不能内嵌其最终对象 SHA。
