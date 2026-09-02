# DataCube — 数据库管理与迁移工具

面向 **Oracle**、**PostgreSQL** 与 **Redis** 的桌面数据库工具，通过 `DataCube.exe` 提供统一的图形化工作空间：

- **桌面应用（`DataCube.exe`）**：关系库连接/对象/SQL 管理，Redis 键浏览、五类型值编辑与安全命令控制台，结果导出（SQL / Excel / `pg_dump`）、Oracle→PostgreSQL 迁移、应用内自动更新。

发布产物内置运行时（jlink），终端用户无需安装 Java。

## 功能特性

- **多数据库支持**：通过 SPI 抽象（`spi/`）+ 提供者实现（`provider/oracle`、`provider/postgres`）统一 Oracle 与 PostgreSQL 的元数据读取、DDL 生成、SQL 方言与执行。
- **Redis 管理**：零第三方依赖的 RESP2 客户端，支持单机/密码/ACL 连接、SCAN 分页键树、String/Hash/List/Set/ZSet 编辑、TTL 与命令控制台。
- **SQL 编辑器**：基于 RichTextFX 的语法高亮、别名感知的字段补全、PL/SQL Developer “河道”风格美化、执行选中片段、查看执行计划；查询结果支持当前已加载行的即时搜索、类型化条件、本地预览、单元格/选中区域/选中行 TSV 复制，以及仅对可证明安全子集开放的显式数据库筛选。
- **对象浏览**：连接树、表/视图数据网格（分页、排序）、DDL 查看、列注释展示。
- **查询结果导出**：支持 XLSX / CSV / SQL / HTML / XML；默认导出当前筛选后的全部可见行，并保留排序和可见列顺序，可明确切换到当前活动结果的全部已加载行。预览/特殊值需确认，不能无损生成 INSERT 的值会阻止 SQL 输出；不自动重新查询。
- **导出文件保护**：查询结果先写同目录临时文件，成功后才原子发布；覆盖前确认目标，写入/发布失败保留旧文件，不支持原子发布时拒绝保存。整表导出、pg_dump 和迁移保持各自原有行为，不在此保护承诺范围内。
- **迁移**：Oracle→PostgreSQL 的完整/增量迁移与结果校验。
- **Schema Diff**：在线比较 PostgreSQL↔PostgreSQL 或 Oracle↔Oracle 的单个 Schema，按语义差异生成稳定、有序且带安全门禁的同步计划。
- **应用内自动更新**：启动时检查 GitHub Release，支持安装版与免安装版就地更新。

## 目录结构

```
朝花夕拾/
├── src/com/datacube/
│   ├── DataCube.java             # 保留的控制台迁移入口（不随 Windows 发布包分发）
│   ├── DataCubeFx.java           # GUI 入口（JavaFX）
│   ├── module-info.java          # 模块声明（模块化构建）
│   ├── cli/                      # 保留的控制台交互实现（Logger / Prompter）
│   ├── config/                   # 应用设置、连接存储、凭据加密、JVM 选项
│   ├── core/                     # 迁移核心：类型映射、SQL 工具、日志抽象
│   ├── export/                   # 导出：SQL 脚本 / Excel / pg_dump / 表导出
│   ├── fx/                       # JavaFX GUI（主壳、对话框、连接树、数据网格、
│   │                             #   DDL 视图、SQL 编辑器、设置、关于、更新 UI）
│   ├── migration/                # Oracle 导出 / PG 导入 / PG 校验
│   ├── provider/oracle/          # Oracle 的 SPI 实现
│   ├── provider/postgres/        # PostgreSQL 的 SPI 实现
│   ├── redis/                    # RESP2 客户端、Redis 会话、键树与控制台逻辑
│   ├── service/                  # 编排层：连接管理、对象树、数据浏览、DDL 服务
│   ├── spi/                      # 数据库提供者抽象接口
│   │   └── model/                # 跨提供者的数据模型（DTO）
│   ├── sqleditor/                # SQL 编辑器无 UI 逻辑（美化器、脚本切分、查询结果）
│   └── update/                   # 应用内自动更新
├── resources/com/datacube/fx/sql-highlight.css   # SQL 高亮样式
├── drivers/                      # JDBC 驱动（已内置）
│   ├── ojdbc17-23.26.1.0.0.jar
│   └── postgresql-42.7.10.jar
├── lib/                          # 打包用的非模块化 jar 与 JavaFX native
├── build.gradle / settings.gradle / gradlew      # Gradle 构建
├── .github/workflows/verify.yml  # PR/main：跨平台测试、Redis 集成、Windows jlink
├── .github/workflows/schema-diff-integration.yml # 手动、显式授权的关系库 live smoke
├── .github/workflows/release.yml # v* tag/手动：验证通过后生成 Windows 发布产物
└── docs/superpowers/specs/       # 设计文档
```

分层约定：`spi/`（接口）+ `spi/model/`（DTO）→ `provider/{oracle,postgres}/`（实现）→ `service/`（编排）→ `fx/`（GUI）/ `cli/`（控制台）。

## 技术栈

- Java 25（Gradle 工具链）
- JavaFX 25（`javafx.controls`）
- RichTextFX 0.11.6（SQL 编辑器）
- Gradle + `org.beryx.jlink` 4.1.0（模块化运行时 + jpackage 打包）

## 本地构建与运行

需要联网首次下载 Gradle 发行版、插件与 JavaFX 25 模块。

```bash
# 模块化运行 GUI（开发调试）
gradlew run

# 生成免安装 app-image 目录：build/jpackage/DataCube/
gradlew jpackageImage

# 生成安装包（默认 msi；-PinstallerType=exe 生成 exe，均需 WiX Toolset v5）
gradlew jpackage
gradlew jpackage -PinstallerType=exe

# 指定版本（CI 中与 release tag 对齐）
gradlew jpackage -PappVersion=3.1.0
```

### 连接配置可靠性

连接配置先写入同目录唯一临时文件，再使用原子替换更新主文件；有效旧版本保留为
`connections.json.bak`。若主文件结构损坏，启动时仅从备份读取，不会静默覆盖损坏文件。

### 安全 SQL 会话

- 每个 SQL 标签使用独立 JDBC 会话，事务、取消和 schema 不会影响其他标签。
- 支持自动提交、手动事务、提交、回滚、查询超时和执行取消。
- 连接可标记为开发、测试或生产环境，并可设置只读模式。
- 无 WHERE 的 UPDATE/DELETE、DROP/TRUNCATE 和生产环境写入执行前需要确认。
- 关闭未提交事务标签时可提交、回滚或取消关闭；应用退出默认回滚。
- 本地全文搜索和类型化条件只处理当前已保留的结果行，永远不访问数据库。数据库筛选必须由用户明确触发，并在应用前同步提交仍处于防抖等待中的搜索文字。
- 经最终 fail-closed 审阅，数据库筛选只开放两个极窄原 SQL 子集：PostgreSQL 仅允许无 `FROM`、由原生字面量和安全括号/逗号组成的投影；投影可不带别名，如带别名则只允许显式 `AS`。Oracle 仅允许显式 `SYS.DUAL` 上的 `*` 或与表别名匹配的 `alias.*`。普通表/视图查询仍可使用全部本地筛选能力，但数据库筛选不可用。这是防止包装查询触发函数、视图、RLS、同义词或名称解析副作用的安全边界。
- 生成谓词的列标识符只来自不可变结果元数据，筛选值全部使用 JDBC 参数绑定；provider 能力同时校验 JDBC 类型码和数据库类型名，PostgreSQL 运算符限定到 `pg_catalog`。条件快照与 JDBC 诊断不记录筛选值。
- JDBC 结果值在发布前脱离驱动对象并冻结；大型二进制和 provider 特有值使用有界、确定性表示。读取路径向 driver 请求 `maxRows + 1`，只保留配置上限内的行；截断文案使用已保留结果快照，不读取后来修改的设置。
- 数据库筛选错误、超时、取消、拒绝或迟到结果不会替换当前表格；选择、焦点、排序、列顺序和列宽保持不变。复制只在 JavaFX 剪贴板实际写入成功时报告成功，确定性测试通过可注入写入边界运行。

关系型连接默认查询超时为 60 秒，可配置为 0 表示不限制。客户端风险分析用于减少误操作，数据库账户权限仍是最终安全边界。

### SQL 草稿恢复

SQL 编辑器默认在本机保留恢复检查点。编辑停顿约 1 秒后触发保存；持续输入时以约 10 秒为快照调度上限。磁盘繁忙、写入失败或突然断电仍可能丢失最后一段尚未保存的输入，请以编辑器的保存状态为准，不要把自动保存当作数据库事务或脚本备份。

- 从顶部“SQL 草稿”进入管理页，选择记录后查看完整文本，再明确恢复。重复恢复会定位已经打开的标签，不额外复制编辑器；启动时不会自动重建全部标签。
- 恢复 SQL 和 Schema 提示不会连接数据库、读取元数据或执行语句。连接通过稳定 ID 和类型识别，不按同名连接替换；目标缺失时可继续编辑，再显式重新选择连接。
- 草稿存放在当前用户目录的 `.datacube/sql-drafts/`。仅保存 SQL、Schema 提示、连接身份和时间等编辑信息，不保存结果集、数据库会话、密码配置或事务状态。SQL 本身仍可能包含密码、个人或业务信息，草稿文件**不是加密存储**。
- 管理页提供关闭自动保存、删除和清空入口。关闭草稿保护不会关闭原有 SQL 历史；清空草稿不等于清空历史或安全擦除磁盘。仍在编辑的文本不会因清空列表而消失，后续编辑可形成新检查点。
- 存储有 7 天保留规则及 100 条、合计 32 MiB 上限，单条 SQL 最大 1 MiB；当前打开草稿受清理保护。超限、目录锁占用或写入失败会显示状态，不默默截断文本或假报已保存。

本轮实现和验收边界见[SQL 草稿验证记录](docs/superpowers/verification/2026-08-30-sql-draft-recovery.md)。草稿恢复不包含工作区标签顺序、查询结果或未提交事务恢复。

### SQL 脚本文件

- 顶部“SQL 文件”菜单可打开 `.sql` 文件，也可从“最近文件”再次打开；`Ctrl+O` 打开，`Ctrl+S` 保存当前选中的 SQL 标签，`Ctrl+Shift+S` 另存为。普通新建、历史和恢复草稿标签第一次保存会先进入“另存为”；历史文本以打开时内容为干净基线，恢复文本仍保留草稿自动保存身份。快捷键可在“设置”中改绑。
- 打开只以 UTF-8 读取（可带 UTF-8 BOM），完整保留原文本和换行；单个文件上限为 8 MiB。读取失败、编码无效、文件过大或读取期间发生外部变化时不会创建新标签，并只显示固定失败提示。
- 打开的脚本不会自动连接数据库、读取元数据、推断数据库类型或执行 SQL。同一规范路径（包括指向同一文件的别名）只对应一个标签；再次打开会选中已有标签并保留其中未保存的文本。文件路径不会写入草稿或工作区恢复数据；最近文件仅保存本机规范化路径，不保存 SQL 正文、连接、Schema 或凭据。
- 保存和另存为沿用标签自己的保存/关闭状态：写入使用同目录临时文件和原子发布，外部修改或发布失败不会覆盖磁盘旧内容。另存为会先临时保留新路径；若该路径已由另一标签占用，只选中已有标签，不提示覆盖也不写磁盘。取消或失败仍绑定原路径，成功后才原子改绑。关闭有未保存更改的文件标签时可选择保存、不保存或取消；应用退出不会隐式保存文件。
- “清空最近文件”仅删除最近路径索引，不删除 SQL 文件，也不等同于安全擦除。

### Schema Diff

Schema Diff 只支持在线连接之间的同库对比：PostgreSQL↔PostgreSQL、Oracle↔Oracle；一次从一个源 Schema（期望状态）同步到一个目标 Schema。连接树中的关系型连接或 Schema 节点提供“Schema 对比...”入口，Redis 不显示该入口。标签内可选择源/目标、筛选和分组差异、查看结构化属性与源/目标定义、预览或导出已选 SQL，并在同一个受管 JavaFX 工作流中部署、取消和审查结果。关闭标签或应用时会封住新工作、取消并等待自有虚拟线程、严格回收专用 JDBC 会话，再执行 FX 清理。

支持对象如下；约束和索引属于表的结构化子对象，不作为独立顶层类别：

| Provider | 顶层对象 | Provider 特有限制或未知项 |
|---|---|---|
| PostgreSQL | 表（含列、主键、唯一/外键/检查约束、普通/表达式/部分索引）、序列、视图、物化视图、函数、过程、触发器、类型（enum/composite/domain） | 目标为 PostgreSQL 11+ 目录语义；扩展定义、极端类型格式或无法高置信解析的定义会变为手工项。 |
| Oracle | 表（含列、约束和索引）、序列、视图、物化视图、函数、过程、触发器、包 spec/body、类型 spec/body | Oracle 不公开序列原始 `START WITH`，因此从快照创建序列和修改起始值不能自动执行；需要猜测表重建、存储子句或无法证明身份/语法安全的定义会变为手工项。 |

差异按规范标识和结构化语义比较，顺序、change ID 和选择摘要稳定；系统生成名称只有在完整结构相同时才视为等价。疑似重命名仅供显示，不会自动转换或执行 rename。元数据权限不足、不支持或依赖无法解析会形成明确的不完整/未知范围，而不是把对象误判为不存在。

安全执行规则：

- `SAFE_AUTOMATIC` 默认选择；破坏性项默认关闭并需逐项启用，`MANUAL_ONLY` 不可选择。生产目标还需要最终确认。
- 破坏性确认要求再次输入目标 snapshot 的安全 Schema 显示 token；任何选择变化都会使旧 selection digest 和确认失效。
- 部署前重新读取完整目标 snapshot。fingerprint 漂移或 fresh snapshot 不完整会硬阻止执行，不能通过普通确认绕过。
- 导出和部署都保持 planner 的依赖顺序。部署逐 statement 记录结果，首个失败后停止；依赖项标记 `SKIPPED_DEPENDENCY`，其他未执行项 fail-fast 跳过。
- 取消不会被描述成回滚。无法确认 driver/server 最终结果时显示 `UNKNOWN_AFTER_CANCEL`。
- Oracle DDL 可能隐式提交；工具只报告逐步事实，不承诺整个批次可以回滚。PostgreSQL 同样不作 whole-batch rollback 承诺。

当前明确排除：跨 provider 对比/迁移、数据 diff 或同步、用户/角色/权限/表空间、在线 Schema 与文件 snapshot/DDL 的对比、自动 rename、多 Schema 整库对比，以及 whole-batch rollback。

#### Opt-in 关系库 live smoke

关系库 smoke 会真实创建和删除数据库对象，默认始终跳过。仅可对明确授权的一次性非生产数据库运行；不得复用应用保存的连接，也不得因地址位于本机、私网或名称看似“测试”而推断写权限。运行必须将 `DATACUBE_SCHEMA_DIFF_TEST_ALLOW_WRITE` 精确设为 `true`，并同时提供对应 provider 的完整环境变量：

- PostgreSQL：`DATACUBE_SCHEMA_DIFF_POSTGRES_HOST`、`_PORT`、`_DATABASE`、`_USERNAME`、`_PASSWORD`。
- Oracle：`DATACUBE_SCHEMA_DIFF_ORACLE_HOST`、`_PORT`、`_DATABASE`、`_USERNAME`、`_PASSWORD`、`_TABLESPACE`。

测试用加密随机前缀创建唯一的源/目标 Schema 和安全对象，只部署本次测试的安全变更，重新读取验证收敛，并在 `finally` 中仅删除内存中记录的精确名称。Oracle 账号需被明确授予创建/删除一次性用户及对象的权限；PostgreSQL 账号需能创建/删除一次性 Schema。仓库的 `Schema Diff live integration` workflow 只能手动选择 provider，并通过受保护的 provider-specific environment 注入相应 secrets；变量缺失时 JUnit 会报告真实 skip。测试报告不得包含完整 JDBC URL、凭据、原始连接属性或完整生成 SQL。

### 凭据保护

新保存的密码使用版本化格式：Windows 优先通过当前用户的 DPAPI 生成
`v2:dpapi:` 密文；其他平台或 DPAPI 暂时不可用时使用 `v2:aesgcm:`。DPAPI 密文通常
只能由同一台 Windows 计算机上的同一登录用户解密。原有无前缀 AES-GCM 密文仍可读取，
并在下一次新增、编辑或删除连接、成功保存完整配置快照时迁移；无法解密的旧密文原样保留。

### 内存基线

GUI 启动器默认使用 G1 平衡配置（初始堆 16MB、最大堆 256MB）。构建
`jlink` 镜像后可在 Windows PowerShell 运行：

```powershell
.\tools\measure-memory.ps1
```

脚本等待主窗口稳定后输出工作集、私有内存、线程数和完整 JVM 参数，并只关闭自己
启动的进程。需要降低单次采样波动时，可连续测量三个独立进程：

```powershell
.\tools\measure-memory.ps1 -Samples 3
```

CDS 对照实验可显式使用 `-CdsMode off` 或 `-CdsMode on`；`on` 要求当前 jlink
镜像已通过 `build\image\bin\java.exe -Xshare:dump` 生成归档。默认 `auto` 在没有归档时
仍可正常启动。当前 JDK 25 静态 CDS 实验没有得到可重复的启动或空闲内存收益，因此发布
镜像暂不内置归档，详见
[`docs/performance/2026-08-09-jdk25-cds-validation.md`](docs/performance/2026-08-09-jdk25-cds-validation.md)。

### 后台任务与资源生命周期

Redis GUI、数据网格、SQL 编辑器与连接树的阻塞 I/O 统一提交到应用级 JDK 25 虚拟线程运行器，
线程名为 `DataCube-io-*`。Redis 键浏览器和控制台保持单会话 FIFO 串行执行；数据网格的
分页加载、逐行提交和批量删除使用标签级任务作用域；SQL 执行、执行计划、结果导出和
表跳转使用标签级作用域，补全元数据仍按队列串行读取；连接树的关系库元数据和 Redis
INFO 首次展开加载使用面板级作用域；DDL 查看器的数据库读取以及对象编辑器的加载、执行
以及序列/表设计器的加载、执行使用标签级作用域。受管标签关闭时会取消尚未完成的任务、
解绑监听器、关闭专用 Redis 会话并丢弃延迟 UI 回调；应用退出会依次释放全部受管标签、
连接树作用域，再等待后台任务最多 3 秒，最后关闭连接资源。该机制仅使用标准 Java/JavaFX
API，Windows 打包与其他平台运行使用同一套实现。单表导出使用独立任务作用域并保持每页
500 行的流式背压；关闭导出进度框会协作式取消任务并屏蔽完成回调。其他 GUI I/O 面板将
按相同方式迁移。自动/手动更新检查和更新包下载同样使用应用级虚拟线程；更新服务关闭后
会跳过尚未开始的操作，并屏蔽已排队的 JavaFX 回调。GUI 迁移页的顶层 Oracle/PostgreSQL
JDBC 操作也由应用级虚拟线程和面板任务作用域管理；关闭应用会同时触发业务取消标记、
中断活动任务并关闭当前根连接及表级 JDBC 连接；嵌套表任务会取消剩余 Future、停止内部
虚拟线程执行器并进行有界等待。“一键全部”在各阶段之间重新检查同一取消令牌，取消后不会
重新进入导入或验证。表级导出/导入仍保留用户配置的并发上限和 `Semaphore` 背压，不因使用
虚拟线程而增加数据库连接压力。

## 下载发布

从 [Releases](https://github.com/Clownking99/data-cube/releases) 下载（Windows x64，均内置运行时）：

| 文件 | 说明 |
|------|------|
| `DataCube-vX.X.X-win64-portable.zip` | 免安装绿色版。解压后进入 `DataCube` 文件夹，双击 `DataCube.exe` 启动。 |
| `DataCube-vX.X.X-win64-setup.exe` | 安装程序。按向导安装（可选目录），创建开始菜单项与桌面快捷方式。 |

PR 和推送到 `main` 会运行 [verify.yml](.github/workflows/verify.yml)：Windows、Linux
执行完整单元测试，Linux 使用一次性 CI 密码验证真实 Redis 协议，Windows 额外验证
`jlink`。该流程不使用开发或生产 Redis 凭据。

推送 `v*` tag 或手动运行 [release.yml](.github/workflows/release.yml) 才会发布版本。
发布任务必须先通过同一验证门禁，并在 jpackage 前再次执行测试；手动发布会在最新
tag 上递增 patch。

## Oracle → PostgreSQL 迁移

启动 `DataCube.exe` 后，点击顶部“数据迁移”，填写 Oracle 与 PostgreSQL 连接信息。可分别执行“导出 DDL”“导出数据”“完整导入”“增量导入”和“验证”，或使用“一键全部”完成导出、增量导入与结果校验。每个 Oracle 用户单独运行一次迁移。

| | 完整导入 | 增量导入 / 一键全部 |
|---|---|---|
| 已存在的表 | 不删除，直接建（IF NOT EXISTS） | 跳过 |
| 缺失的表 | 创建 + 修复 | 创建 + 修复 |
| 已有数据的表 | 全量覆盖 | 跳过 |
| 空表 | 导入数据 | 导入数据 |

迁移脚本生成到 `pg_migration/<pg_schema>/`（序列、表、索引、约束、函数、包、触发器，以及每表一个的数据文件）。

## Oracle → PostgreSQL 类型映射

| Oracle | PostgreSQL |
|--------|------------|
| VARCHAR2 / NVARCHAR2 | VARCHAR |
| CHAR / NCHAR | CHAR |
| NUMBER | NUMERIC |
| INTEGER | INTEGER |
| FLOAT / BINARY_FLOAT / BINARY_DOUBLE | DOUBLE PRECISION |
| DATE / TIMESTAMP | TIMESTAMP |
| CLOB / NCLOB / LONG | TEXT |
| BLOB / RAW | BYTEA |
| SYSDATE | CURRENT_TIMESTAMP |
| SYS_GUID() | gen_random_uuid() |

## 开源许可

DataCube 自有源码采用 [Apache License 2.0](LICENSE) 开源许可。

Copyright 2026 Clownking99

本仓库包含的 JavaFX、Oracle/PostgreSQL JDBC、RichTextFX 及其他第三方组件不因
DataCube 的许可证而重新授权；它们继续遵循各自的许可证和分发条款。
