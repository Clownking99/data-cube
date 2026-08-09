# DataCube — 数据库管理与迁移工具

面向 **Oracle**、**PostgreSQL** 与 **Redis** 的桌面数据库工具，提供图形界面（GUI）与命令行（CLI）两个入口：

- **GUI（`DataCube.exe`）**：关系库连接/对象/SQL 管理，Redis 键浏览、五类型值编辑与安全命令控制台，结果导出（SQL / Excel / `pg_dump`）、Oracle→PostgreSQL 迁移、应用内自动更新。
- **CLI（`DataCubeCli.exe`）**：将 Oracle 用户的表结构、序列、索引、约束、存储过程、触发器与全量数据迁移到 PostgreSQL。

发布产物内置运行时（jlink），终端用户无需安装 Java。

## 功能特性

- **多数据库支持**：通过 SPI 抽象（`spi/`）+ 提供者实现（`provider/oracle`、`provider/postgres`）统一 Oracle 与 PostgreSQL 的元数据读取、DDL 生成、SQL 方言与执行。
- **Redis 管理**：零第三方依赖的 RESP2 客户端，支持单机/密码/ACL 连接、SCAN 分页键树、String/Hash/List/Set/ZSet 编辑、TTL 与命令控制台。
- **SQL 编辑器**：基于 RichTextFX 的语法高亮、别名感知的字段补全、PL/SQL Developer “河道”风格美化、执行选中片段、查看执行计划。
- **对象浏览**：连接树、表/视图数据网格（分页、排序）、DDL 查看、列注释展示。
- **导出**：查询结果或整表导出为 SQL 脚本 / Excel（xlsx）/ `pg_dump`。
- **迁移**：Oracle→PostgreSQL 的完整/增量迁移与结果校验（CLI 与 GUI 均可）。
- **应用内自动更新**：启动时检查 GitHub Release，支持安装版与免安装版就地更新。

## 目录结构

```
朝花夕拾/
├── src/com/datacube/
│   ├── DataCube.java             # CLI 入口（迁移工具）
│   ├── DataCubeFx.java           # GUI 入口（JavaFX）
│   ├── module-info.java          # 模块声明（模块化构建）
│   ├── cli/                      # 控制台交互（Logger / Prompter）
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

脚本等待主窗口稳定后输出工作集、私有内存和线程数，并只关闭自己启动的进程。

### 后台任务与资源生命周期

Redis GUI、数据网格、SQL 编辑器与连接树的阻塞 I/O 统一提交到应用级 JDK 25 虚拟线程运行器，
线程名为 `DataCube-io-*`。Redis 键浏览器和控制台保持单会话 FIFO 串行执行；数据网格的
分页加载、逐行提交和批量删除使用标签级任务作用域；SQL 执行、执行计划、结果导出和
表跳转使用标签级作用域，补全元数据仍按队列串行读取；连接树的关系库元数据和 Redis
INFO 首次展开加载使用面板级作用域；DDL 查看器的数据库读取以及对象编辑器的加载、执行
以及序列/表设计器的加载、执行使用标签级作用域。受管标签关闭时会取消尚未完成的任务、
解绑监听器、关闭专用 Redis 会话并丢弃延迟 UI 回调；应用退出会依次释放全部受管标签、
连接树作用域，再等待后台任务最多 3 秒，最后关闭连接资源。该机制仅使用标准 Java/JavaFX
API，Windows 打包与其他平台运行使用同一套实现；其他 GUI I/O 面板将按相同方式迁移。

## 下载发布

从 [Releases](https://github.com/Clownking99/data-cube/releases) 下载（Windows x64，均内置运行时）：

| 文件 | 说明 |
|------|------|
| `DataCube-vX.X.X-win64-portable.zip` | 免安装绿色版。解压后进入 `DataCube` 文件夹，双击 `DataCube.exe` 启动 GUI；`DataCubeCli.exe` 为命令行迁移工具。 |
| `DataCube-vX.X.X-win64-setup.exe` | 安装程序。按向导安装（可选目录），创建开始菜单项与桌面快捷方式。 |

PR 和推送到 `main` 会运行 [verify.yml](.github/workflows/verify.yml)：Windows、Linux
执行完整单元测试，Linux 使用一次性 CI 密码验证真实 Redis 协议，Windows 额外验证
`jlink`。该流程不使用开发或生产 Redis 凭据。

推送 `v*` tag 或手动运行 [release.yml](.github/workflows/release.yml) 才会发布版本。
发布任务必须先通过同一验证门禁，并在 jpackage 前再次执行测试；手动发布会在最新
tag 上递增 patch。

## CLI 迁移工具

`DataCubeCli.exe`（或 `gradlew run` 后以 `com.datacube.DataCube` 为主类）按提示输入 Oracle 与 PostgreSQL 连接信息，每个 Oracle 用户运行一次：

```
  ─────────────────────────────────────────────
  功能菜单
  ─────────────────────────────────────────────
  1. 导出 DDL（表/序列/索引/约束/函数）
  2. 导出数据（全量）
  3. 导入到 PostgreSQL（完整模式 - 先清空再导入）
  4. 导入到 PostgreSQL（增量模式 - 仅补充缺失）
  5. 一键全部（导出DDL + 导出数据 + 增量导入）
  6. 验证导入结果
  0. 退出
```

| | 完整模式（选项3） | 增量模式（选项4/5） |
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
