# P2.2 工作区持久化设计

基线 `184c142c8228d5df278ea7ca7825549931b51912`，独立 worktree `codex/sql-workspace-recovery`。P2.1 已完成，不重复实现清单格式或解析器。用户已授权按现有产品路线自主推进常规选型。

## 选择与范围

选择 **SqlDraftStore 持有同目录的工作区存储组件**：共用一个 SqlDraftDirectory、一个操作系统锁和同一个 store monitor，避免两个实例竞争同一个目录。替代方案是独立目录/锁（无法自然保持草稿禁用与工作区写入的顺序）或把清单塞入现有偏好文件（破坏既有格式且扩大故障范围），均不采用。

本阶段只提供同步阻塞的存储API，不在当前应用中自动调用；P2.3负责FX捕获/串行队列/失效代次/关闭生命周期，P2.4负责入口与提示。阻塞I/O必须由将来的writer线程调用。

## Global Constraints

- Java 25、JavaFX 25、JUnit Jupiter 5.11.3、Gradle wrapper 9.2.0；不添加依赖。
- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 工作区清单只含草稿 UUID、顺序、选中项、时间、光标/选择锚点；不复制 SQL、连接身份、Schema、凭据或结果集。连接身份与 Schema 由 P1 草稿提供。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- P2 完整验收和整分支审查通过才本地合并 main；基础模块完成不等于用户入口完成。

## 接口与职责

新增 `SqlWorkspaceStore`（公开结果类型，构造与操作仅包内可用，无open/close方法），由SqlDraftStore唯一持有，不暴露实例给外部。公开调用入口均位于SqlDraftStore且synchronized：

- `SqlWorkspaceStore.Snapshot workspaceSnapshot() throws IOException`
- `void saveWorkspace(SqlWorkspace workspace) throws IOException`
- `void setWorkspaceEnabled(boolean enabled) throws IOException`
- `boolean clearWorkspace() throws IOException`

Snapshot字段为 `workspace`、`status`、`recordingEnabled`、`preferenceValid`。status取 `ABSENT / AVAILABLE / CORRUPT / UNSUPPORTED_VERSION / UNREADABLE`；除AVAILABLE外workspace均为空；recordingEnabled仅描述工作区自己的严格偏好，不等同于整个草稿系统当前允许记录。有效偏好缺省为开启，初始化不创建新文件。丢失引用允许存在，由P2.1解析器统一处理，存储层不二次扫描/复制所有SQL草稿。

文件名白名单只增加 `workspace.bin`、`workspace-preferences.bin`。前者使用P2.1 codec（2424字节上限），后者为大端9字节：magic `0x44435750`，version `1`，最后字节仅0/1。旧 `preferences.bin` 与 `.draft` 不变，旧草稿清理/容量计算忽略两个新文件。

`saveWorkspace` 先检查目录有效、原有草稿偏好有效且开启，再检查工作区偏好、原有清单状态；任一条件不符不写盘。null清单拒绝，空合法清单可以写。工作区格式/偏好问题不能使正常草稿读取失效。

## 清空、禁用与错误

- 关闭工作区记录写入独立偏好，不删除清单，不影响草稿保护偏好及正文；重启后保持关闭。重新开启不自行捕获当前界面。
- 清空只对可验证清单操作，**原子发布规范空清单** `SqlWorkspace(0, [], null)`；不删除草稿、不需要额外删除器。清单不存在或已经是规范空清单返回false且不写；成功清空返回true。规范空清单重开仍为AVAILABLE但没有可恢复项。
- 显式清空允许在两个记录开关关闭时执行，也允许在偏好损坏但清单可验证时执行；不修复/覆盖损坏偏好。未知版本/损坏/不可读清单拒绝清空与覆盖，保留文件供人工处理。
- 只有文件不存在才采用开启默认；偏好损坏/未知版本/超限均是valid=false、enabled=false；两个方向的设置修改都拒绝覆盖损坏偏好。
- 工作区领域失败使用固定 `SqlWorkspaceStore.FailureCode`：`DISABLED / INVALID_WORKSPACE / PROTECTED_WORKSPACE / PREFERENCE_CORRUPT / DRAFT_PROTECTION_UNAVAILABLE`，消息 `SQL workspace store failed: <CODE>`，不附带可能含路径/正文的cause。
- 目录层READ（包括超限/读失败）作为UNREADABLE清单或无效偏好呈现；BUSY/CLOSED/UNSAFE/SCAN_LIMIT等结构性错误直接保留原有SqlDraftDirectory.Failure阶段，不能吞成“不存在”。WRITE/PUBLISH/CLEANUP直接向上传播，不声称写入/清理成功。
- 本存储组件不自动重试。CLEANUP后的粘性停用与修复后重启属于P2.3运行时职责，沿用P1对同目录writer的安全边界；本阶段测试检查失败阶段及旧文件保留，不宣称运行时已接入。
- 目录的锁、NOFOLLOW_LINKS、大小限制、身份戳与原子移动保持不变。不宣传防御不遵守锁且可任意修改用户目录的恶意本机进程；写临时文件期间目标改变仍必须拒绝覆盖。

## 测试与交付

1. 首次无文件、精确字节、重开恢复、独立禁用/重开/再开启、草稿总开关有效性、清空规范空文件与幂等、不改变正文/偏好。
2. 损坏/未知/超限清单、损坏/未知/超限偏好、无效输入、关闭后的调用；有效草稿邻居始终保留可读。
3. 对保存、修改偏好、清空分别注入WRITE/PUBLISH/CLEANUP失败，验证实际旧文件字节、临时文件数量和固定诊断；目标在写临时文件时变化不得覆盖。
4. 新文件名的大小写别名、目录冒充普通文件、符号链接（如环境不支持明确记录不冒充通过）；同JVM第二store与新JVM竞争同一锁，释放后新JVM能读取清单和偏好。
5. 现有Directory/Store与新组件定向回归，然后一次全量回归、独立任务审查。工作区UI、队列失效/清空竞态和退出冻结仍需P2.3/P2.4验收。

设计自查：明确两个偏好与两种清空的语义；格式兼容与失败退路都不依赖UI。清空采用原子空清单避免增加删除机制，满足“只清除布局引用”的产品契约。共享monitor只保证本store调用顺序，不代替下一阶段对排队旧快照的失效处理。
