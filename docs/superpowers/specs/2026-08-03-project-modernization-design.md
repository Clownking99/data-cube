# DataCube 项目现代化与资源优化设计

- 日期：2026-08-03
- 状态：已获用户批准（对话确认）
- 目标平台：Windows 为主要正式发行平台，同时保留跨平台运行能力
- 交付方式：直接在 `main` 分阶段提交，每阶段可独立验证和回退

## 1. 背景与目标

DataCube 已具备 Oracle、PostgreSQL 和 Redis 管理能力，但当前还存在以下工程问题：

1. `jlink` 裁剪了运行时镜像，却没有自动降低 JVM、GC 和 JavaFX 的运行时内存。
2. GitHub Release 流程没有 PR/Push 测试门禁，也没有真实 Redis 集成环境。
3. `ConnectionStore` 直接覆盖配置文件，写入中断可能损坏全部连接配置。
4. 当前 AES-GCM 密钥由本机用户名和用户目录派生，只适合防止明文直接可读。
5. JavaFX Pane 各自创建平台线程或线程池，任务取消、Tab 关闭和 UI 回调生命周期不统一。
6. `SqlEditorPane`、`ConnectionTreePane`、`RedisKeyBrowserPane` 等类职责偏多，自动化 UI 覆盖不足。
7. Gradle 10 兼容警告、JDK 25/jlink 插件提示和过时 JavaFX API 尚未系统治理。

本设计采用“分阶段、风险优先”路线，不做一次性重写。先固定 Redis 一期基线，再依次交付资源优化、CI、配置可靠性、凭据安全、异步架构、UI 拆分和构建兼容性。

## 2. 已确认的架构决策

- Redis 一期作为独立基线提交，后续优化不得混入该提交。
- 默认采用 G1 256MB 平衡模式，不把 SerialGC 设为正式默认值。
- Windows 使用 DPAPI 保护新写入的密码，非 Windows 使用版本化 AES-GCM 回退。
- 旧密码无需用户立即重新输入；解密成功后随下一次正常保存迁移。
- 适合的阻塞任务尽可能使用 JDK 25 虚拟线程。
- 虚拟线程不替代 JavaFX Application Thread，也不取消数据库、连接和迁移的资源并发上限。
- UI 拆分采用渐进抽取，保留现有交互和业务行为。
- 每个阶段单独测试和提交，阶段失败时可回退而不影响已完成阶段。

## 3. 阶段 0：Redis 一期基线

基线提交包括 Redis 协议、会话、键浏览器、控制台、连接树集成、单元/真实集成测试、README、设计和实施文档，以及 CodeGraph 初始化文件。

`.testagent` 属于本地测试研究记录，不纳入正式源码提交。真实 Redis 凭据不得进入代码、文档、提交历史、测试报告或日志。

基线验收：

- `clean test` 通过；无凭据时真实 Redis 测试安全跳过。
- 使用显式环境变量时真实 Redis 测试通过并清理随机测试键。
- `jlink` 通过且运行镜像存在。
- `git diff --check` 通过。

## 4. 阶段 1：G1 256MB 与启动资源优化

### 4.1 默认 JVM 配置

GUI 启动器默认参数调整为：

```text
-Xms16m
-Xmx256m
-XX:+UseG1GC
-XX:MinHeapFreeRatio=5
-XX:MaxHeapFreeRatio=20
-XX:G1PeriodicGCInterval=30000
```

`AppSettings.DEFAULT_MAX_HEAP_MB` 同步改为 256。设置页仍允许用户在 128–8192MB 内调整最大堆；自定义值只影响 `Xmx`，保留经过验证的 G1 参数。

本机空闲测量基线为当前 G1/512MB 配置约 189MB 工作集；G1/256MB 候选约 162MB。验收采用相对指标：相同机器和步骤下，启动 12 秒后的空闲工作集至少下降 10%，目标区间为 160–170MB。测量脚本记录 JVM 参数、PID、工作集、私有内存和线程数，并确保关闭测量进程。

### 4.2 惰性初始化

`MigrationPane` 改为首次点击“数据迁移”时创建的惰性单例。未创建时：

- 不构造 `MainController`、迁移表单和日志 `TextArea`；
- `isRunning()` 返回 false；
- 应用退出不调用不存在的迁移控制器。

`UpdateService` 可延迟到启动更新检查或手动检查时创建。轻量配置、主题和连接树仍在启动时创建，避免为了少量对象增加复杂状态。

### 4.3 CDS

当前镜像没有 CDS 归档。实现阶段验证 JDK 25 与 beryx jlink 插件的 CDS 生成能力；只有满足以下条件才默认启用：

- `jlink` 和 `jpackageImage` 均成功；
- GUI 与 CLI 启动器均可运行；
- 不引入机器相关、不可复现的构建产物；
- 启动时间或内存测量有可重复收益。

若插件/JDK 组合不稳定，CDS 延后处理，不阻塞 256MB 和惰性初始化交付。

## 5. 阶段 2：CI 与发布门禁

新增验证工作流并调整发布依赖：

1. PR 和推送到 `main` 触发 `clean test`。
2. Linux 验证任务启动带测试密码的 Redis 服务，运行 `RedisLiveIntegrationTest`。
3. Windows 验证任务执行单元测试和 `jlink`，覆盖正式平台模块链接。
4. Release Job 依赖验证成功；正式打包前仍执行测试，随后运行 `jpackageImage` 和安装包构建。
5. Gradle wrapper、Temurin JDK 25 和 Gradle 缓存配置在验证/发布工作流间保持一致。

CI 中的 Redis 密码仅为临时服务测试值，不使用生产或用户提供的秘密。测试键继续使用随机 `datacube:smoke:*` 命名空间并在 `finally` 清理。

## 6. 阶段 3：连接配置原子持久化

`ConnectionStore.saveAll` 使用同目录写入和替换流程。备份步骤使用复制，绝不在新文件就绪前移动或删除当前主文件：

```text
序列化内存快照
  → 写入同目录临时文件
  → 完成并关闭文件
  → 将有效旧文件复制为 .bak
  → ATOMIC_MOVE 替换主文件
  → 不支持原子移动时安全降级为 REPLACE_EXISTING
```

规则：

- 临时文件名不可与其他进程/线程冲突。
- 新内容必须先完成写入，才能动主文件。
- 写入或替换失败时保留原主文件，并清理可安全删除的临时文件。
- 主文件解析失败时尝试 `.bak`；恢复只用于本次读取，不静默覆盖损坏主文件。
- 损坏条目继续逐条跳过，但完整文件结构损坏必须记录明确警告。

测试覆盖正常往返、空列表、Unicode/转义字符、写入失败、原子移动降级、主文件损坏、备份恢复和 Redis 配置。

## 7. 阶段 4：跨平台版本化凭据

### 7.1 抽象与格式

保留 `CredentialCipher` 作为对业务层稳定的门面，在内部引入可测试的 `CredentialProtector`：

```text
v2:dpapi:<base64>    Windows DPAPI 密文
v2:aesgcm:<base64>  非 Windows或DPAPI不可用时的兼容密文
<base64>             现有无前缀 AES-GCM v1 密文
```

空密码仍存为空串。未知版本或损坏密文必须抛出不包含秘密内容的明确异常。

### 7.2 Windows 与跨平台行为

- Windows 通过 JDK FFM 调用 `CryptProtectData`/`CryptUnprotectData`，不引入 JNA。
- DPAPI 数据绑定当前 Windows 用户；复制到其他用户或机器后需要重新输入密码。
- 非 Windows 延续 AES-GCM，并通过格式前缀与 DPAPI 明确区分。
- Windows DPAPI 暂时不可用时记录脱敏警告，使用 `v2:aesgcm` 回退；后续仍能按前缀解密。
- “保留跨平台运行”指应用功能可在其他平台运行，不承诺跨机器无感迁移受保护密码。

### 7.3 迁移

无前缀密文按现有 v1 算法解密。解密成功后标记为可升级，在用户下一次新增、编辑或删除连接并正常保存整个配置快照时，重新保护为当前平台 v2 格式。迁移失败不覆盖原密文，也不阻止其他有效连接加载。

日志、异常、测试名称和诊断信息不得包含明文密码、AUTH 参数或完整密文。

## 8. 阶段 5：虚拟线程与 JavaFX 任务生命周期

### 8.1 执行器策略

新增应用级执行器组件：

- `FxTaskRunner`：基于 `Executors.newVirtualThreadPerTaskExecutor()`，执行 JDBC、Redis、HTTP、文件读写、导出等待等阻塞 I/O。
- `FxTaskScope`：每个 Pane/Tab 持有，跟踪 Future、取消任务并屏蔽关闭后的 UI 回调；名称刻意避开 JDK `StructuredTaskScope`。
- `FxSerialTaskQueue`：Redis 单会话和其他需要严格顺序的操作使用；底层任务仍可由虚拟线程执行，但同一会话一次只运行一个命令。
- `CpuTaskRunner`：仅供明确的 CPU 密集任务使用有界平台线程池；不得把所有计算无差别放入虚拟线程并发执行。

虚拟线程统一命名为 `DataCube-io-*`，便于线程转储和故障诊断。应用关闭时关闭执行器并最多等待 3 秒，超时后取消剩余任务。

### 8.2 适用与不适用边界

优先迁移：

- SQL 执行、执行计划和元数据预热；
- 数据页加载、提交和删除；
- 连接树元数据/Redis INFO 加载；
- Redis 键浏览器和控制台；
- DDL、对象/序列/表设计器加载；
- 导出、更新检查和下载；
- 迁移任务中的阻塞 JDBC 操作。

保持平台线程或现有机制：

- JavaFX Application Thread 上的控件创建和修改；
- JavaFX `Timeline`/动画和纯定时 UI 行为；
- 明确的 CPU 密集批量转换；
- JDK、JavaFX 和 JDBC 驱动内部线程。

虚拟线程降低线程成本，但不放宽外部资源上限：

- 单 Redis 会话保持单飞行命令；
- 数据库连接数受连接/任务策略约束；
- 迁移并发继续受用户上限和 `Semaphore` 限制；
- 大量导出任务继续使用背压，不能一次性把全部表提交给数据库。

任务取消通过中断、关闭语句/连接或业务取消标记协作完成。驱动不响应中断时，Tab 关闭后至少禁止其回调访问 UI，并在资源层最终关闭连接。

### 8.3 Tab 生命周期

`ContentTabPane` 增加：

```text
openManagedTab(title, node, disposer)
```

关闭按钮、应用退出和主动关闭都保证 `disposer` 至多执行一次。Redis 会话、SQL 元数据任务、数据加载任务、监听器和 `FxTaskScope` 统一挂载到该生命周期，替代 `AppShell` 中分散的 `setOnClosed`。

取消是正常终态，不弹错误；真实失败交给统一 `FxErrorHandler`。Pane 已关闭时丢弃成功和失败 UI 回调。

## 9. 阶段 6：大型 Pane 渐进拆分

不改变现有视觉结构，按以下边界逐步抽取：

1. `RedisKeyBrowserPane`
   - `RedisKeyBrowserModel` 保存 DB、SCAN 游标、键列表、选中键和分页状态。
   - `RedisKeyController` 执行命令和状态转换。
   - Pane 只构建控件、读取输入和渲染模型。
2. `ConnectionTreePane`
   - 抽取连接配置增删改和元数据加载。
   - JavaFX `TreeItem` 和上下文菜单仍留在 Pane。
3. `SqlEditorPane`
   - 抽取 SQL 执行、执行计划、结果状态和历史快照。
   - RichTextFX 编辑、快捷键和补全弹层仍留在 Pane。
4. `DataGridPane`
   - 保留现有 `EditableGridModel`，只统一加载、提交和删除的任务生命周期。

每完成一个边界就补测试和提交，不要求一次性完成全部拆分。

## 10. 错误处理与安全

后台任务统一产生成功、失败和取消三种结果：

- UI 展示简洁错误原因，完整堆栈写本地日志。
- 异常格式化必须脱敏密码、Redis AUTH 参数和可能携带秘密的 URL。
- 配置备份恢复、DPAPI 回退、CDS 不支持等可恢复问题记录警告，不阻断启动。
- OOM、JavaFX 无法初始化等不可恢复错误由顶层兜底处理。
- 关闭后的 Pane 不接收任何延迟回调。

## 11. 测试与验收

### 11.1 自动化层次

1. 纯单元测试：配置原子写入、凭据版本/迁移、控制器状态、串行队列和取消。
2. 生命周期测试：受管理 Tab 只释放一次、关闭后不回调 UI、Redis/JDBC 资源关闭。
3. JavaFX 冒烟：连接类型切换、迁移页惰性创建、打开/关闭 Redis Tab。
4. 集成测试：真实 Redis 和关键 JDBC 测试缝。
5. 构建测试：`clean test`、`jlink`、`jpackageImage`。

控制器使用同步/可控测试执行器，避免单元测试依赖休眠和随机线程调度。TestFX 只覆盖关键流程，不进行脆弱的像素级断言。

### 11.2 统一完成条件

- Redis 基线和新增测试全部通过。
- CI 在 PR、main 和 release 路径上形成门禁。
- Windows app-image 可启动，CLI 入口不回归。
- 空闲工作集相对当前基线至少下降 10%。
- 主配置损坏时可从备份读取，写入失败不破坏旧配置。
- v1 密文可读，Windows 新密文使用 DPAPI，非 Windows 可运行。
- 关闭 Tab/应用后无 DataCube 任务线程、Redis 会话或 JDBC 连接泄漏。
- 真实 Redis 集成测试通过且不遗留测试键。
- `git diff --check` 通过，CodeGraph 索引同步。

## 12. 构建兼容性与提交顺序

使用 `--warning-mode all` 区分项目、脚本和插件警告。优先清理 `TableDesignerPane` 过时 API 和项目自身 Gradle 警告；beryx/JDK 25 的 JEP 493 提示通过插件兼容验证解决，不直接隐藏。配置缓存只在自定义任务声明完整输入输出后启用。

建议提交顺序：

1. Redis 一期基线。
2. G1 256MB、内存测量与迁移页惰性创建。
3. CI 验证和 Release 门禁。
4. `ConnectionStore` 原子写入与恢复。
5. 版本化 DPAPI/AES-GCM 凭据。
6. 虚拟线程 `FxTaskRunner`、`FxTaskScope` 和受管理 Tab。
7. Redis/连接树/SQL/DataGrid 渐进拆分及 UI 冒烟。
8. Gradle 10、JDK 25/CDS 和过时 API 收尾。

## 13. 明确不在本轮的内容

- 不迁移到 GraalVM Native Image。
- 不一次性重写全部 JavaFX UI。
- 不引入新的数据库连接池框架或响应式数据库栈。
- 不承诺 DPAPI 密文跨 Windows 用户或机器迁移。
- 不为降低内存删除 Oracle/PostgreSQL/Redis 已有能力。
