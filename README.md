# DataCube - 数据库迁移工具

将 Oracle 数据库完整迁移到 PostgreSQL，包括表结构、序列、索引、约束、存储过程、触发器和全量数据。

v3.0: 项目重构，主类更名为 DataCube，代码按职责拆分为多文件

v2.4: 布尔转换可控（按字段注释判断）、导入流式读取（避免大表OOM）、SQL转义修正、DDL注释转义修正

v2.3: 统一路径（DDL+数据均按PG schema命名）、导入失败表名日志、SQL错误不再静默吞掉

v2.2: 并行导出（4线程）、流式读取（避免大表OOM）、日志文件、友好错误提示

## 目录结构

```
朝花夕拾/
├── src/com/datacube/
│   ├── DataCube.java                # 主入口：bootstrap + 菜单编排
│   ├── cli/                         # CLI 交互层
│   │   ├── ConsoleLogger.java       # MigrationLogger 的控制台实现
│   │   └── ConsolePrompter.java     # 用户输入
│   ├── core/                        # 核心层（与 UI 无关）
│   │   ├── MigrationLogger.java     # 日志抽象接口（未来 GUI/Web 实现此接口）
│   │   ├── ColumnInfo.java          # 列信息数据类
│   │   ├── TypeConverter.java       # Oracle→PG 类型映射
│   │   └── SqlUtils.java            # SQL 工具方法
│   ├── source/                      # 数据源导出
│   │   └── OracleExporter.java      # Oracle DDL + 多线程数据导出
│   └── target/                      # 目标端导入
│       ├── PgImporter.java          # PostgreSQL 完整/增量导入
│       └── PgVerifier.java          # PostgreSQL 验证
├── DataCube.jar                     # 可执行 JAR（CLI + GUI）
├── build.sh                         # 编译打包脚本
├── run.sh                           # CLI 启动脚本
├── run-gui.sh                       # GUI 启动脚本（JavaFX）
├── drivers/                         # JDBC 驱动（已内置）
│   ├── ojdbc17-23.26.1.0.0.jar
│   └── postgresql-42.7.10.jar
└── pg_migration/                    # 运行后生成的迁移脚本（统一按 PG schema 命名）
    └── <pg_schema>/
        ├── 01_sequences.sql
        ├── 02_tables.sql
        ├── 03_indexes.sql
        ├── 04_constraints.sql
        ├── 05_functions.sql
        ├── 06_packages.sql
        ├── 07_triggers.sql
        └── data/*.sql               # 每张表一个文件
```

## 运行

### CLI 模式

```bash
java -jar DataCube.jar
```

程序会依次提示输入数据库连接信息，每个 Oracle 用户运行一次：

```bash
# 迁移 Oracle 用户 SCOTT → PG schema scott
java -jar DataCube.jar
```

### GUI 模式

```bash
# 纯 JAR 版（JavaFX 已内置在 JAR 中）
java -jar DataCube.jar --gui

# 内置 JRE 版（解压后直接运行）
./run-gui.sh        # Linux/Mac
run-gui.bat         # Windows
```

### 输入示例

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  第一步：Oracle 数据库连接信息
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    (格式: jdbc:oracle:thin:@IP:端口/服务名)
  Oracle JDBC URL [jdbc:oracle:thin:@127.0.0.1:1521/orcl]:
    (将导出该用户下的所有对象)
  Oracle 用户名 [scott]:
  Oracle 密码:

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  第二步：PostgreSQL 数据库连接信息
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    (格式: jdbc:postgresql://IP:端口/数据库名)
  PostgreSQL JDBC URL [jdbc:postgresql://127.0.0.1:5432/postgres]:
  PostgreSQL 用户名 [postgres]:
  PostgreSQL 密码:
  PostgreSQL Schema [scott]:
```

## 功能菜单

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

| 选项 | 功能 | 说明 |
|------|------|------|
| 1 | 导出 DDL | 从 Oracle 导出序列、表结构、索引、约束、存储过程、包、触发器 |
| 2 | 导出数据 | 全量导出，每张表一个 SQL 文件，无行数限制 |
| 3 | 完整导入 | 建表 → 修复缺失表 → 建序列 → 建索引 → 导入数据 |
| 4 | 增量导入 | 跳过已存在的表，仅创建缺失表；跳过已有数据的表 |
| 5 | 一键全部 | 依次执行 1→2→4→6（增量模式） |
| 6 | 验证 | 检查表数量、总行数、序列数、函数数、TOP10 数据量 |

## 增量 vs 完整

| | 完整模式（选项3） | 增量模式（选项4/5） |
|---|---|---|
| 已存在的表 | 不删除，直接建（IF NOT EXISTS） | 跳过 |
| 缺失的表 | 创建 + 修复 | 创建 + 修复 |
| 已有数据的表 | 全量覆盖 | 跳过 |
| 空表 | 导入数据 | 导入数据 |

## 日志输出示例

```
  ─────────────────────────────────────────────
  导出 DDL：SCOTT → scott
  ─────────────────────────────────────────────
  [19:30:01]  [OK] 序列: 127 个
  [19:30:02]  导出表结构 [████████████████████] 100% (264/264)
  [19:30:02]  [OK] 表: 264 个
  [19:30:03]  [OK] 索引: 89 个
  [19:30:03]  [OK] 约束: 156 个
  [19:30:04]  [OK] 存储过程/函数: 12 个

  ─────────────────────────────────────────────
  DDL 导出统计 (3s)
  ─────────────────────────────────────────────
    ✓ 序列: 127
    ✓ 表: 264
    ✓ 索引: 89
    ✓ 约束: 156
    ✓ 存储过程/函数: 12
  ─────────────────────────────────────────────
```

## 下载发布

从 [Releases](https://github.com/Clownking99/data-cube/releases) 下载：

| 包 | 说明 |
|---|---|
| `data-cube-vX.X.X.zip` | 纯 JAR（需本机安装 Java 21+） |
| `data-cube-vX.X.X-win64-jre.zip` | 内置 JRE（无需安装 Java，仅 Windows x64） |

驱动已内置在 JAR 中，无需额外下载。

## 本地编译

```bash
bash build.sh
```

代码推送到 main 分支后，GitHub Actions 自动编译并发布 Release。

## 已修复的 Bug

- DDL 与数据导出路径不一致（统一使用 PG schema 命名目录）
- 导入数据时 SQL 错误被静默吞掉，导致显示"成功"但实际 0 行
- 导入失败未显示具体表名
- `SYS_%` LIKE 过滤误删 `SYSTEM_*` 表（Oracle 中 `_` 是通配符，需用 `ESCAPE '\\'` 转义）
- NVARCHAR2/NCLOB/BINARY_FLOAT 类型转换
- 注释中分号导致 SQL 解析失败
- `$$` 美元引用正确分割
- 序列值溢出（改用 getString）
- SYSDATE → CURRENT_TIMESTAMP
- 缺失表自动检测并重建
- NUMBER(1,0) 误转布尔值（改为按字段注释判断，用户可控）
- 大表导入 OOM（改为流式逐行读取）
- 单引号转义顺序错误导致 SQL 语法异常
- DDL 注释中 `/*` `*/` 未转义导致 SQL 解析失败

## 兼容性类型映射

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
