# P2.3 工作区运行时协调设计

基线 `3a7eb044efa111e0cc928ddfba6b32834b0388cc`，继续使用独立 `codex/sql-workspace-recovery`。P2.1/P2.2已完成；主目录的未提交SqlDraftStore改动及`.testagent/`均保留、不访问其内容。

## 方案与分期

选择在现有SqlDraftCoordinator内接通同一个backend和SqlDraftWriteQueue，随后增加活动工作区状态与FX接入。相比独立executor/store，避免第二owner与跨队列清空顺序；相比现在同时修改关闭UI、保存与恢复入口，能够先独立验证运行时安全边界。常规选型依据用户“后续无需我确认”的授权，不等待例行确认。

本阶段分为两个可独立验收的增量：

1. **P2.3a异步存储桥（本轮实施）**：四个workspace API、共享串行队列、清空/启停/删除的代次失效、运行时故障传播与关闭排空；不在应用中自动捕获或写布局。
2. **P2.3b活动状态及关闭接入（另写实施计划）**：FX标签顺序/位置捕获、只记录已保存草稿、变更合并与防抖、退出冻结、取消/部分失败、重试/明确忽略。须在a通过后实施，不把a通过当成整个P2.3完成。

## Global Constraints

- Java 25、JavaFX 25、JUnit Jupiter 5.11.3、Gradle wrapper 9.2.0；不添加依赖。
- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 工作区清单只含草稿 UUID、顺序、选中项、时间、光标/选择锚点；不复制 SQL、连接身份、Schema、凭据或结果集。连接身份与 Schema 由 P1 草稿提供。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- P2 完整验收和整分支审查通过才本地合并 main；基础模块完成不等于用户入口完成。
- 工作区与草稿共用同一个store、目录锁、writer队列；不改变P1文件格式、原子发布和事务关闭语义。

## P2.3a接口与顺序

四个public方法都在UI owner调用，返回防调用者取消传播的future副本：

- `workspaceSnapshot(): CompletableFuture<SqlWorkspaceStore.Snapshot>`
- `saveWorkspace(SqlWorkspace): CompletableFuture<Void>`
- `setWorkspaceEnabled(boolean): CompletableFuture<Void>`
- `clearWorkspace(): CompletableFuture<Boolean>`

首次初始化仅沿用P1草稿初始化，不读取/创建workspace文件；读取是显式请求。同步I/O仅在现有queue上执行。workspace管理操作与P1管理共用busy，管理完成结果在UI任务处理后交付，避免调用者在完成回调中仍遇到上个操作的busy。关闭后新调用遵循P1的IllegalStateException契约；已接受操作由shutdown排空，已排入的完成回调即使closing也必须结算，不悬挂future。

同时最多接受一个尚未交付完成结果的workspace保存；重复请求返回BUSY，不堆积快照。P2.3b负责在捕获层保留最新候选、完成后再次提交，不在此桥中制造另一个防抖器或保存队列。所有已接受保存均有明确成功或失败结果，成功只表示该不可变快照写出，不表示之后的编辑已保存。

接受清空布局、修改workspace开关、P1清空、删除或总开关操作时，立即增加workspace epoch；尚未执行的旧保存返回CANCELLED且不调用backend。已开始的保存允许完成，但管理操作在同一串行队列后执行，所以不能在清空完成后复活布局。管理操作本身失败也不恢复旧任务。P1纯refresh不增加epoch。workspace管理不取消草稿保存；P1原有管理取消草稿的规则保持不变。

## 故障与隐私

新增runtime原因 `DISABLED` 和 `CANCELLED`，保持固定无cause消息。workspace领域失败保留P2.2公开Failure及code；WRITE/PUBLISH等普通I/O映射WRITE，允许显式重试、不自动重试。workspace损坏清单/偏好/独立开关拒绝只影响workspace，不停止可用的草稿保护。

目录CLEANUP/UNSAFE等结构性错误沿用P1 stop，立即停用共享writer、取消待执行草稿，后续workspace action执行前也检查faulted。DRAFT_PROTECTION_UNAVAILABLE表明P1保护配置失效，同样停止共享writer；workspace DISABLED不能据此停用P1，因为可能仅关闭了独立开关。调度/回调拒绝必须结算future并停用，不暴露原始异常路径/正文。关闭仍释放唯一store锁。

内部Backend为已有测试backend增加四个默认的显式“不支持”IOException入口；LocalBackend必须覆盖全部并委托同一store。默认实现不返回假成功/空快照，且已有P1路径不调用它们，不要求修改无关测试fixture。

## 验收与后续边界

使用实际临时SqlDraftStore、可手动推进的UI/disk executor及既有Backend故障接缝，验证落盘字节/顺序/结果、无自动文件、调用者取消隔离、管理代次、运行中写入与清空、损坏偏好隔离、CLEANUP停止后续写入、调度拒绝、关闭排空释放锁。另用public Path构造覆盖真实LocalBackend桥接。不使用真实配置、网络、FX窗口或计时sleep。

P2.3b须明确：只在实际用户活动后捕获，退出前冻结完整布局，保存成功的草稿身份不能由installedContent直接推断，调用BUSY后保留最新候选，CANCELLED不能显示为磁盘损坏，失败/取消退出不自动写空清单。恢复UI仍属P2.4；P2.5验收及全分支审查前不合并main。

设计自查：保存背压与变更合并分别归属a/b；workspace错误不会误停正常草稿；管理失败不会复活旧快照；关闭不丢future；无新线程/存储owner/自动写盘。没有将尚未实现的UI语义当成已通过验收。
