# DataCube Redis 支持设计（一期）

- 日期：2026-07-08
- 状态：已获用户批准（对话确认）
- 目标：为 DataCube 加入 Redis 管理能力，一期达到主流 Redis 管理工具
  （Another Redis Desktop Manager / RedisInsight）的核心交互水准。

## 1. 背景与范围

DataCube 现为 Oracle/PostgreSQL 管理工具，SPI 分层 provider→service→fx，
契约深度绑定 `java.sql.Connection` 与 SQL/schema/表模型。Redis 是非 JDBC
的键值模型，无法直接套用现有 SPI。

**一期范围**（用户确认）：

| 模块 | 一期 | 二期 |
| --- | --- | --- |
| 键浏览器 + 五类型值编辑器 | ✅ | |
| 命令行控制台 | ✅ | |
| 连接：单机 + 密码 + ACL 用户名 | ✅ | |
| 服务器信息面板（INFO 可视化） | | ⏳ |
| Pub/Sub、SLOWLOG | | ⏳ |
| TLS、集群/哨兵 | | ⏳ |

**客户端方式**（用户确认）：自写 RESP2 协议客户端，零第三方依赖
（契合项目 XlsxWriter 同款零依赖传统，模块化 jlink 打包零成本）。

## 2. 架构决策：平行体系（方案 A）

三个候选中选定 **平行体系**：不动现有关系型 SPI，新增独立的
`com.datacube.redis` 子系统，仅在连接树/连接对话框做少量类型分叉。

- 备选 B（SPI 泛化为抽象 Session）：触及全部现有 provider/service/fx，
  为单一 Redis 做全局重构违反 YAGNI，弃。
- 备选 C（Redis 伪装 JDBC 表模型）：TTL/五类型/SCAN 游标无处安放，
  达不到主流工具水准，弃。

分层依赖方向：`fx → redis(service+protocol) → java.base`；
`service.ConnectionManager → redis`（薄分派）。现有 SPI/provider 零改动。

## 3. RESP 协议客户端（com.datacube.redis）

### 3.1 RespCodec（纯函数编解码，可单测）

- 编码：`String[] args → byte[]`，RESP 数组 + Bulk String
  （`*N\r\n$len\r\n<bytes>\r\n...`），参数以 UTF-8 编码。
- 解码：面向 `InputStream` 解析五种响应类型：
  - Simple String (`+`) / Bulk String (`$`) → `byte[]`（`$-1` → `null`）
  - Integer (`:`) → `Long`
  - Array (`*`) → `List<Object>`（递归，`*-1` → `null`）
  - Error (`-`) → 抛 `RedisException`（保留服务器原始消息，如 `WRONGTYPE ...`）

### 3.2 RespClient

- 纯 JDK `Socket`；连接超时 5s、读超时 10s（`soTimeout`）。
- `Object call(String... args)`：编码→发送→读响应；`synchronized`
  串行化（单连接单飞行命令，UI 层已在后台线程调用）。
- 握手：connect → 有密码则 `AUTH [user] pass`（ACL 双参 / 传统单参，
  按 username 是否为空自适应）→ `SELECT db`。
- 值统一以 `byte[]` 承载；展示层尝试 UTF-8 解码，含不可打印字节则
  以十六进制视图展示（正确处理二进制 value）。

### 3.3 RedisException

运行时异常，携带服务器错误前缀（ERR/WRONGTYPE/NOAUTH…），UI 原样展示。

## 4. 会话与连接管理

### 4.1 RedisSession

包装 RespClient 的类型化命令门面：

- 键空间：`scan(cursor, pattern, count)`、`type`、`ttl`、`expire`、
  `persist`、`del`、`rename`、`exists`、`dbsize`、`select`、`info(section)`
- String：`get` / `set`
- Hash：`hscan` / `hset` / `hdel`
- List：`llen` / `lrange` / `lpush` / `rpush` / `lset` / `lrem`
- Set：`sscan` / `sadd` / `srem`
- ZSet：`zscan` / `zadd` / `zrem`、`zcard`
- 透传：`Object raw(String... args)`（控制台直发任意命令）

### 4.2 RedisSessionManager

平行于 JDBC 缓存的生命周期管理：`acquire(connId)` 惰性建连 + PING 校验
失效重建；`test(cfg)` 直连校验返回错误消息或 null；`release` / `closeAll`。

### 4.3 复用现有连接配置体系

- `DbType` 加 `REDIS("Redis", "redis://", 6379)`。
- `ConnConfig` 记录零改动：`database` 字段承载 DB 索引（字符串 "0"），
  `username` 可空（ACL），密码沿用 AES 加密持久化；
  `jdbcUrl()` switch 补 `redis://host:port/database` 分支（枚举穷举，
  新值强制编译期补齐）。
- `ConnectionManager` 薄分派：`test()`/`release()`/`closeAll()`/
  `unregister()`/`isConnected()` 遇 `type == REDIS` 委托 RedisSessionManager
  （`isConnected` 支撑连接树“断开连接”菜单可用性判断）；
  `acquire()`（返回 java.sql.Connection）对 REDIS 抛
  IllegalStateException（防误用）。UI 保持单一入口。

## 5. 连接树与连接对话框

### 5.1 ConnectionDialog

- 类型下拉加 REDIS；切换联动：「DB 索引:」默认 `0`、
  用户名提示「（可选，ACL）」、密码提示「（可选）」，两者允许留空。
- **校验放宽**：现有 `build()` 强制“数据库/用户名非空”，REDIS 类型时
  用户名允许留空，DB 索引需为 0-15 整数（留空视为 0）。
- 测试连接沿用 `connMgr.test(probe)`（内部已分派）。

### 5.2 ConnectionTreePane

- `Kind` 枚举加 `REDIS_DB`。
- `connectionItem()` 分叉：`type == REDIS` 时子节点为 db0～db15
  （懒加载读 `INFO keyspace` 标注键数，如 `db0 (1,234)`；空库灰显）。
- db 节点双击/右键「打开键浏览器」；连接节点右键加「命令行控制台」。
- `Actions` 接口加 `openRedisKeys(ConnConfig conn, int db)`、
  `openRedisConsole(ConnConfig conn)`；`AppShell.TreeActions` 路由到
  新 Pane 并经 `ContentTabPane.openTab` 打开。
- **SQL 入口拦截**：选中 Redis 节点会把它设为活动连接，顶栏
  「新建 SQL」对 REDIS 活动连接温和提示（不适用）而非打开后报错。

## 6. 键浏览器 RedisKeyBrowserPane（核心）

SplitPane 左右分栏。

### 6.1 左侧键树

- 顶部：模式搜索框（glob，默认 `*`）、DB 切换下拉（db0-15）、刷新。
- **SCAN 游标分页**：每批 `COUNT 500`，底部「加载更多（已加载 N）」；
  绝不使用 `KEYS *`。
- `KeyTreeBuilder`（纯函数）：按分隔符 `:`（面板内可改）把键名聚合为
  层级树，文件夹节点显示子键数；键与文件夹同名时并列展示。
- 键节点右键：删除、重命名、复制键名。
- 「新建键」按钮：类型 + 键名 + 初始值对话框。

### 6.2 右侧值编辑器（按 TYPE 动态切换）

- 通用头部：键名（可编辑 = RENAME）、类型徽章、TTL 显示与设置
  （含「持久化」= PERSIST）、删除、刷新。
- String：文本区 + 纯文本 / JSON 美化 / 十六进制三视图；保存 = SET。
- Hash：field-value 表格，HSCAN 分页，增/改/删行。
- List：index-value 表格，LRANGE 分页，头/尾插入、LSET 修改、
  按值删除（LREM count=1）。
- Set：member 表格，SSCAN 分页，增删。
- ZSet：score-member 表格，ZSCAN 分页，增删改 score。
- 大值保护：value 超 1MB 只显示大小 + 前 4KB 预览，手动确认才全量加载。

## 7. 命令行控制台 RedisConsolePane

redis-cli 风格：上方只读输出区 + 下方单行输入。

- 命令解析支持引号包裹参数；↑/↓ 会话内历史。
- 结果按 RESP 类型格式化：数组缩进编号、`(integer) N`、`(nil)`、
  错误红色（复用 -status-error 主题色）。
- 危险命令（FLUSHALL / FLUSHDB / SHUTDOWN / DEBUG / CONFIG SET）
  执行前弹确认。
- 阻塞类命令（SUBSCRIBE / PSUBSCRIBE / MONITOR / BLPOP / BRPOP /
  WAIT…）一期拦截并提示暂不支持。
- 控制台持有独立 RedisSession（不与键浏览器共享，避免 SELECT 串扰）。

## 8. 线程与错误处理

- 全部网络调用在后台线程执行，`Platform.runLater` 回 UI
  （沿用 SqlEditorPane / DataGridPane 现有模式）。
- 断连自动重连一次，再失败才报错。
- `RedisException` 原样展示服务器错误消息。
- 面板（Tab）关闭时释放其独立会话；应用退出 `closeAll` 一并关闭。

## 9. 测试

- `RespCodecTest`：命令编码字节序列；五种响应解码、嵌套数组、
  nil、错误抛出、多字节 UTF-8、二进制安全。
- `KeyTreeBuilderTest`：普通/深层/无分隔符/键与文件夹同名冲突。
- UI 与真实网络交互不做自动化测试（与项目现状一致）。

## 10. 文件清单

```
新增  src/com/datacube/redis/     RespCodec, RespClient, RedisException,
                                  RedisSession, RedisSessionManager, KeyTreeBuilder
新增  src/com/datacube/fx/        RedisKeyBrowserPane, RedisConsolePane
新增  test/com/datacube/redis/    RespCodecTest, KeyTreeBuilderTest
修改  spi/model/DbType.java       + REDIS 枚举值
修改  spi/model/ConnConfig.java   jdbcUrl() 补 redis:// 分支
修改  service/ConnectionManager   薄分派（test/release/closeAll/unregister/isConnected）
修改  fx/ConnectionDialog         类型下拉 + 表单联动分支 + 校验放宽
修改  fx/ConnectionTreePane       REDIS_DB 节点 + Actions 扩展
修改  fx/AppShell                 TreeActions 路由两个新 Pane + SQL 入口拦截
不动  现有 SPI 能力接口 / provider.* / 其余 service；module-info 无新增 requires
```
