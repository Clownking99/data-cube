# G10 Redis 资源预算：开发 P0 设计审查

日期：2026-10-09（Asia/Shanghai）。状态：**P0 已完成，等待 root 审查；没有准入 P1，没有 G10 测试通过声明。**

本记录落实 [G10 计划](../plans/2026-10-09-g10-redis-resource-budget.md) 和 [协调记录](2026-10-09-g10-redis-coordination.md)。已完整阅读 [维护成本计划](../plans/2026-10-09-maintenance-cost-review.md)，该工作仍等待 G10 交付，不在本设计实施范围。也已纳入 root 的补充：FX 回调的恢复不能依赖格式化成功；重连必须恢复最后确认成功的 SELECT，而非总用构造时 DB；完整顶层 server error 保留现有有用正文，不扩展为全局脱敏改造。

## 1. 身份、冻结与本轮证据范围

| 项目 | 核对结果 |
| --- | --- |
| 开发会话 | `01a11b86-2026-7ed3-86c5-1ec232640653` |
| root 会话 | `01a0ceef-5ee3-7753-97a6-bdf68a148aae` |
| 工作目录 | `C:\Users\hetia\.codex\worktrees\aed5\朝花夕拾` |
| 新分支 | `codex/g10-redis-resource-budget-20261009` |
| 新分支基线/当前 HEAD | `66edd24f50a327a82628d70de72fb7b7911c2ae2`，root 已提交计划和当前诊断的本地 main 基线 |
| 产品基线 | `b6622c95d5efc0b240dc7ae4b788f44542c87b5a`；至上述 HEAD 的 Redis 产品差异为空 |
| 旧 G9 分支 | `codex/g9-table-export-reliability-20261008` 仍为 `6b8ceb93c7413b3882137d322d88b9a1cb14ef5c` |

创建新分支前核对旧冻结原件：G9 P2 manifest 的 3,516 项、G9 CI 修正 manifest 的 524 项，长度和 SHA-256 全部匹配。随后只在指定基线创建 G10 分支，没有改写旧分支、旧报告或旧 evidence。旧 manifest 中包含旧轮活源码路径，切换分支后不把当前活源码当旧快照；旧冻结 evidence 和旧分支仍保留。

root 的 [baseline.json](evidence/g10-redis-20261009-baseline/baseline.json) 是本轮当前源码的独占子 JVM 诊断：13 字节巨大数组头触发 OOM、6,000 层嵌套触发 SOE、100,000 字节 simple line 被完整接受。开发本 P0 **没有重新编译或运行这些诊断**；这里只核对输入源码身份并审查其含义，诊断 exit0 不等于保护措施通过。

| 源码 | 当前工作树原始长度/SHA-256 | CRLF 规范化为 LF 后，与 root baseline 比对 |
| --- | --- | --- |
| `RespCodec.java` | 4,374；`9D8F2D033D9A95BDBF79A3875B9D51919C8D4D3FDCBCBC5C54130ECED69D0B12` | 4,263；`17BD31B075F13B577BC4B7C5D18007866003454B6B735E1519EE8D0BCBD72329`，匹配 |
| `RedisException.java` | 359；`1C28902E0248320C75DFEADB512AB9E61501B071B538B315F9A45B694D93DCCA` | 346；`565994901284CC0CA46083EF848D49164554409DD2C3423AD032E0BE2D5E52E7`，匹配 |

差异仅为行尾。两份源码的 Git blob 分别为 `03aa7d0f01656b909d4a5b066d04f874337a6fbf`、`d46aec3de993f5e6ee1e38a82e8ffe79ac21ee68`，与产品基线相同。没有为使原始哈希相等而改写源码。

本 P0 只做允许路径的源码、测试、计划和公开规范读取，创建授权分支并写本文件。没有修改产品/测试，没有执行 Gradle、暂存或提交；没有接触真实服务、原有 profile/凭据/业务数据、`.testagent`，没有新会话/子代理、安装、fetch/push/merge/tag/PR。G9 Verify 37828177473 的四任务成功和 PAUSED 是历史信息，不计入 G10 新证据。

## 2. 当前调用与分配矩阵

已直接读取以下实际文件及相关测试；无需建立 CodeGraph 索引。这里的“现状”是当前源码观察，“准入点”是待实施设计。

| 入口/调用链 | 当前缺口 | 必须设置的准入点/提交边界 |
| --- | --- | --- |
| `RespCodec.encode(String...)` | 先逐项 UTF-8 `getBytes`，再无界 BAOS、`toByteArray` | 字符串和所有参数先计字节与 frame，再分配；禁止先做 UTF-8 大副本 |
| `RespClient.call(String...)` → `callBytes` | 流式创建整个 `byte[][]`；命令 token 全量 ASCII String/uppercase | 共享请求预检；allowlist 只做有界 ASCII 比较；业务 frame 在任何联网前准备 |
| `RedisSession.call(Object...)` → executor | `String.valueOf` 可调用未知对象；UTF-8 复制发生在 client 之前 | 先检查全体参数数目、类型和 UTF-8 长度；只支持现有内部 String/byte[]/数值类型，未知对象拒绝，不调用任意 `toString` |
| `RespClient.ensureConnected` → AUTH/SELECT → `exchange` | 握手没有独立准入；初始 `database` 永不更新 | AUTH、恢复 SELECT、业务请求各自预检；所有响应共用同一调用期限；用已确认 DB 恢复 |
| `RespCodec.decode` → line/bulk/array | line 无界、bulk `readNBytes(length)`、`ArrayList(length)`、递归且无共享计数 | 一个响应一个 wire/node 上下文；声明后、分配前检查；显式栈；固定数组原位读 |
| `RespClient.callBytes` 异常处理 | 所有 IO 异常进入读重试；RedisException 保留 socket；嵌套 error 留尾 | typed 分类先于普通 IO；不完整/损坏/资源/期限废弃本次 lease；普通完整顶层 error 可继续 |
| `RedisSession` 响应投影 | cursor 用 signed long；TYPE/OK/PONG/score 可先复制大文本；扫描投影再复制列表 | ASCII 元数据在转换前检查；cursor 无符号往返；集合实际行数在构造行对象前检查；只复制引用，不复制 payload |
| `RedisSessionManager.acquire` → cached PING | catch 任意 Runtime 后替换连接，能绕过 client 的“预算不重试”；monitor 内做 I/O | terminal 分类不替换重试；短状态锁，PING/关闭/工厂不持状态锁；迟到创建要校验配置代际 |
| `ConnectionTreePane.redisDatabaseItems` → session INFO keyspace → `split` | 大 bulk 转 String 后按全部行拆分 | 只对 typed INFO keyspace 加原始字节和行数限制，先检查再转换；无需改全局树/SQL 分支 |
| console input → tokenize/policy → raw | tokenize、history、Label 无界；所有参数 uppercase | 输入先准入，token 数逐个检查；策略 ASCII 比较；双维保留模型 |
| console raw → FX success → `format` | 在 FX 回调递归/全量 UTF-8/hex，异常会绕过输入恢复 | worker 做纯有界格式化；FX 回调 try/catch/finally；输入恢复独立于格式化和 append 成功 |
| key browser SCAN → loadedKeys → KeyTreeBuilder → TreeItem | 先推 cursor，再 lossy 解码/add/regex split/递归建树；refresh 先清空 | worker 构建完整候选，全部检查通过后 FX 提交；拒绝保留旧 cursor/树/来源；增量分段 |
| string STRLEN → GETRANGE/GET → text/hex/JSON → Save | 原预览存在，但 full 绕过；STRLEN 有竞态；hex/JSON 放大；保存前复制 | 底层 hard cap、显示 cap、complete/preview 状态分离；先预检再编码；所有预览禁保存 |
| h/l/s/z 页面 → ValueRow → 编辑/删除 | FX 全量格式化；COUNT 不限制实际返回；display 可能作身份 | worker 准入行数/每格/整页；raw field/member/value/index 独立保存；候选替换后才提交下一 cursor/offset |
| pane → FxSerialTaskQueue → close sequence | queue 无界 pending；cancel 不证明 I/O 停止；回调可晚于后续 worker | Redis pane 自身单飞、至多一个待刷新意图；请求代际；保留既有 best-effort 关闭并独立核对物理结束 |

`FxSerialTaskQueue` 已有关闭后回调抑制和串行 worker，不重写它、全局 Fx runner、ConnectionManager 或 AppShell。必要恢复和准入留在 Redis 局部。

## 3. 集中预算及计量定义

新增一个不可变 `RedisResourceLimits`，集中列出协议、请求和展示默认值。生产固定使用默认值，不添加环境变量、用户配置或临时绕过开关。包内测试可注入**更小**预算及单调时钟；不能以测试 seam 修改生产默认值。所有加法使用 remaining/subtraction 或 checked arithmetic，不让计数溢出回绕。

| 项目 | P0 拟定值 | 计量及边界 |
| --- | --- | --- |
| 单 bulk | 8 MiB | payload bytes；声明等于可接受，超过即拒绝；另须放得下本响应余下 wire |
| simple/error line | 64 KiB | 内容 bytes，不含 CRLF；在 append 前检查，终止符计 wire |
| 数字头 | 32 bytes（新增） | integer、bulk/array 长度的 ASCII 内容；覆盖正常 64-bit 文本并限制无限前导零，不构造大数字 String |
| 单响应 wire | 16 MiB | marker、头、CRLF、payload 全部计入；所有子数组共享，精确读入字节计数 |
| 单响应节点 | 100,000 | 每个 marker 是一节点：scalar、容器、null 均计；根也计；不按子数组重置 |
| 单数组元素 | 10,000 | 数组声明的直接元素数；不是全响应总节点；巨大声明立即拒绝 |
| 数组深度 | 32 | 根数组深度 1，根 scalar 0；进入任何数组（含 empty/null array）检查，scalar 子项不增加数组层数 |
| 请求 | payload 8 MiB；frame 16 MiB；10,000 参数 | payload 是各参数原始 UTF-8/byte[] 总和；frame 包含所有 RESP 头/CRLF；三项同时检查 |
| 响应读取总期限 | 30 s | 一次逻辑调用一个单调期限，首次响应 read 前启动，握手、业务和 transport retry 不重置；第 5 节详述 |
| console 输入（新增） | 64 Ki UTF-16；10,000 tokens | 先于 tokenize/substring/StringBuilder 和编码；粘贴准入用长度算术，不先生成整个新文本 |
| console 单条/输出 | 64 Ki UTF-16；200 条且总 512 Ki UTF-16 | 标记、省略提示、序号、换行也占字符；命令回显、错误条目同样受限 |
| console history | 100 条且总 64 Ki UTF-16 | 与 output 独立，超限从旧条目淘汰，index 同步；单条不能装入则不保留 |
| 累计键 | 5,000 unique；原始键总量 2 MiB | 同一已提交扫描的唯一键；重复页不重复收费；准入不能按 lossy String 去重 |
| 单键（新增） | 64 KiB raw | 防止单键占满预算并制造大分段/状态/对话框；超限整页拒绝，不能仅漏掉该键 |
| 键树 | 20,000 节点；深度 32 | root 计节点，root 深度 0；空段计节点/层级；创建段和节点之前检查 |
| 分隔符/MATCH/键标签（新增） | 64 / 4 Ki / 4 Ki UTF-16 | 控制本地输入及 UI 标签放大；标签仅预览，操作身份仍是完整键；预算拒绝保留旧树 |
| collection 页面 | 1,000 rows | H/Z 一对算一行，底层 pair 必须偶数；L/S 一项一行；与请求 COUNT 分开 |
| collection 显示 | cell 4 Ki；page 256 Ki UTF-16 | 包括两列、序号、页标题和省略标记；行上限与总字符上限独立检查 |
| string 编辑/显示（新增） | 1 Mi UTF-16 | 保留原有 ≤1 MiB 普通文本编辑；hex/JSON 膨胀不能超出；显示未完整时 read-only 且禁 Save |
| typed INFO keyspace（新增） | 64 KiB raw；1,024 行 | UTF-8/String/split 之前检查；只限导航用途，raw console INFO 仍走通用协议与预览预算 |

保留计划候选数值，不提高预算。新增限制针对已有前置分配/放大路径；它们是应用政策，不是 Redis 协议最大值。对超长单键、超长 MATCH、巨量 DB 导航和膨胀的完整 hex/JSON 展示会有新的明确拒绝/预览行为，不能把这些行为称为无条件兼容。

### 3.1 响应准入先于危险分配

RESP2 五种类型及 null 语义按 [官方 RESP 规范](https://redis.io/docs/latest/develop/reference/protocol-spec/)。实现仅保留现有 RESP2 范围。

1. 读 marker 前检查 deadline 与 remaining wire；成功读 marker 才增加 node。每次读取用实际 byte 数扣账，未知 marker 是 protocol failure。
2. 数字头使用固定小缓冲/逐位解析：integer 支持规范中的 signed 64-bit（含可选正负号），length 接受非负十进制和规范的 `-1` null。空、非法字符、越过 long 范围、非法负长度、坏 CRLF 都是 protocol failure；合法巨大长度越过应用 cap 是 resource failure。前导零仍受 32 bytes 限制。
3. bulk 在分配前检查 length ≤8 MiB、length+尾部 CRLF ≤剩余 wire。使用减法检查防止 length+2 溢出；只分配一个精确 byte[]，固定长度原位填充，不用 `readNBytes`/BAOS 再拼接副本。不尝试为了“恢复”而排空超限 bulk。
4. array 声明先检查元素 cap、深度、剩余 node 的必要下界。不能按声明预分配 `ArrayList(length)`；用已实际读取子项增长的 list 和最多 32 个 frame 的显式栈。共享 node 限制总对象量，若预留 pending node，则将已声明未完成子项一起扣账，不能反复用同一 credit。
5. line 只允许受限内容缓冲。达到上限后可读一个终止/溢出判别 byte；它也必须有 wire credit、计入 wire，不能在预算耗尽后继续探测。不得宣传“任何拒绝都不读一个额外 byte”；准确承诺是不继续任意读尾、不生成超限内容缓冲、不越过 wire 预算。
6. 成功返回前再次检查期限；完整响应也不能在期限已耗尽后发布。null/empty/binary 保留；不得依赖 catch OOM/SOE 作为准入。

已知 wire 不完整时不 drain。完整响应之后的 Session shape/页面展示拒绝是独立投影失败，不能伪称 wire 损坏，也不应为此自动重放命令。

### 3.2 请求预检与副本

`RespCodec`、`RespClient.call`、`RedisSession.call` 和 UI text/hex 输入必须共用预检规则，而不是只给最后的 BAOS 加上限。

- 先检查参数数目、null、合法类型、每项和累计 payload、十进制头及 frame 长度，再创建 `byte[][]` 或 UTF-8 byte[]。String 的 UTF-8 长度通过 char/code point 遍历计算，不用 `getBytes` 计长；未配对 surrogate 的计长/编码采用当前 JDK UTF-8 replacement 行为，增加精确测试，不因估计错误放过超限或拒绝恰好边界。
- unknown Object 不调用任意 `toString`。内部 Integer/Long/Double 转文本是有界的；raw String/byte[] 保持现有入口。空参数数组不发送，零长度参数可保留。
- frame 精确计算后一次分配并填充，替代 BAOS+`toByteArray`。byte[] 复制进 frame 后请求不可变，retry 复用该 frame。String 入口优先直接写精确 frame；若 Session 仍需 byte[][] 门面，中间 UTF-8 总量仍先受 8 MiB 控制，不能把这份副本漏出峰值说明。
- 业务请求及需要的配置 AUTH/已确认 DB SELECT 的长度预检都在联网前完成。业务预算拒绝不能先发送 AUTH；握手 frame 分阶段创建，避免将三份最大 frame 长期同时持有。
- allowlist 命令识别只比较短 ASCII command token；不能为了识别“是否 GET”复制/uppercase 一个 8 MiB token。UI policy 同样只对必要 token 做有界比较，不建立全部 upperArgs 集合。
- hex 输入先遍历验证允许空白/合法 nibble 和 byte 数，再分配精确结果；去除 `replaceAll` 全量中间 String。text/new key/field/member/value 先算 UTF-8 和请求总量再 `getBytes`。编辑区 TextFormatter 在新 String 构造前检查 prospective 长度；业务入口仍复检，不能把控件限制当唯一保护。

### 3.3 峰值不是 wire cap 或 RSS cap

这些是每个命令/每个 pane 的准入，不是整个 JVM 的统一内存配额：

- 响应 16 MiB wire 限制 payload 总量，但另有至多 100,000 节点的对象头、Long boxing、array list 及引用；增长 list 可能保有额外 capacity。固定 line scratch ≤64 KiB、数字头 ≤32 bytes、stack ≤32 frame。最大合法响应仍可能需要数十 MiB，不能承诺在 32 MiB JVM 中全部可接受。
- 请求 8 MiB payload 加上 10,000 项各最多 12 bytes 的 bulk 头/尾及 array 头，正常编码 frame 的理论上界约为 8 MiB+120,008 bytes，仍保留独立 16 MiB envelope 检查。caller 原有 String/byte[] 不由 client 创建；Session 中间 UTF-8（如保留）最多另占 8 MiB，frame 另占上述量；不得用“frame 仅一份”掩盖它们。
- collection 的 raw 引用可指向最多一份 16 MiB 响应，显示再占 ≤256 Ki UTF-16。旧页与候选页在提交时可同时存活；每行 rawA/rawB 共享原数组，不对值复制多遍。
- 键树候选与旧树可同时各持 5,000 键/2 MiB raw；候选 mutable、immutable 和 FX TreeItem 是不同对象图，各自至多 20,000 逻辑节点。不能把节点预算解释为整个堆只有 20,000 对象。分段 String 总长度受 raw key 总量控制，冻结/提交后释放不再需要的 mutable/旧引用。
- 输出/历史按逻辑 UTF-16 总量淘汰；JavaFX 控件、字符串内部编码、对象头、renderer、多个标签、OS socket 缓冲和 GC 时机另计。close/淘汰释放引用不等于 RSS 当场下降。

小堆子 JVM 用于证明不可信头/深度在危险分配前拒绝；大合法响应以结构和计数证明，不用提高小堆来掩盖拒绝失效。

## 4. 失败类别、发送状态与重试

在 `RedisException` 增加内部 kind/delivery 信息，保留现有公开构造器以兼容调用者；codec 内使用可明确分类的异常，若继承 IOException，client 必须在泛 IO catch **之前**识别。不能靠 error message 或 cause 字符串猜重试策略。

| 失败 | socket 处置 | 同次逻辑调用自动重试 | 调用者语义 |
| --- | --- | --- | --- |
| 请求/输入预检超限或非法 | 尚未创建/发送业务；已有健康 socket 可保留 | 否 | NOT_SENT，固定消息，不带命令/参数/凭据 |
| 完整顶层 `-` server error，line/CRLF 全部已读完 | 业务 socket 可保留；启动 AUTH/SELECT 未就绪 lease 废弃 | 否 | server failure；保留现有有用正文，受64KiB line/总wire保护 |
| array 中 `-`，本实现提前抛出 | 不尝试读剩余尾部，废弃本次 lease | 否 | NESTED_SERVER_ERROR/响应未完成；不能让尾部落进下一命令 |
| wire/node/line/bulk/array/depth 超限 | 废弃本次 lease | 否，包括 GET/PING/manager 健康检查 | RESOURCE；已发写标明结果可能已生效、不重放 |
| 已知坏 marker/数字/CRLF/溢出 | 废弃本次 lease | 否 | PROTOCOL；消息不包含原始字节 |
| 总读取期限耗尽 | 废弃本次 lease | 否 | DEADLINE；不因持续到达 byte 或新子数组重置 |
| 提前 EOF、真实 transport IOException、早于总期限的 idle read timeout | 废弃本次 lease | 仅现有 safe-read allowlist 最多再一次；不延长总期限 | TRANSPORT；非幂等命令不重放 |
| 已完整解码但 Session shape/metadata 或 UI 页面预算拒绝 | 没有未读 RESP 尾部；通常可保留 | 否 | 投影/展示拒绝，旧 UI 保留；与 wire failure 区分 |
| 显式 close/cancel 后被关闭的 socket | 从状态中移除；物理 close best effort | 否 | CLOSED/CANCELLED，不允许关闭后重连 |

server error 可以是合法嵌套 RESP 元素；本实现继续保持抛异常的门面，不新增 error-value 数据模型。既然提前抛出，就必须 discard，不能假定嵌套错误代表顶层已结束。

**消息兼容决策（按 root 最新澄清收敛）：**完整顶层 RESP error 已受 line/wire 预算保护，保留原有消息及连接可继续语义。`RespCodecTest.serverErrorPreservesOriginalMessage`、client NOAUTH 精确正文断言继续保留，不改为固定摘要、不普遍放宽。固定安全消息只用于客户端新建的预算/协议/期限拒绝、未完成的嵌套 error 和审计记录，不带命令、key/value、凭据或原始服务端尾部。嵌套错误不复制正文后再拒绝；top-level line 超限也不能把已读 prefix 带入资源异常。审计只记类别/计量/发送状态，不新建全量错误正文日志。public legacy ctor 的 mock 消息不自动拿来判 server/transport；生产 RespClient 必须总带明确 kind。不新增全局服务端错误脱敏改造。

发送状态以**业务请求**为准：调用 output.write 前是 NOT_SENT；从尝试 write 起即可能部分发送，记 MAY_HAVE_SENT，不要求 flush 成功才承认不确定。握手已发送但业务未发送，业务仍 NOT_SENT。写请求发出后的资源/协议/期限/嵌套错误/断开必须保留“结果可能已生效，未重放”；完整 server error 表示收到错误回复，也不承诺某些脚本/复合命令绝无副作用。generic IO cause 不拼入公开 message，避免携带服务器或输入内容。

## 5. 读取期限与连接所有权

### 5.1 一个期限，多个响应上下文

一次逻辑调用在首次 response read 前启动单调 30 s deadline。AUTH、恢复 SELECT、业务各有新的 wire/node 计数，但共用该 deadline；安全读 transport retry 也共用它。没有 handshake 时由业务 read 启动。期限不在每次 read、每个数组或每个新 socket 重置。

每次 blocking read 之前将 SO_TIMEOUT 调整为 `min(10 s idle cap, ceil(remainingMillis))`，不能将不足 1 ms 算成 0（0 代表无限）。read 返回后、解析循环、分配前和最终返回前检查；用 `nanoTime` elapsed/remaining 运算处理溢出。若 socket timeout 发生而总期限已耗尽，分类 DEADLINE；若尚未到总期限，只是 idle timeout，保留旧 transport 语义。

[JDK Socket 文档](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/Socket.html) 的 SO_TIMEOUT 针对单次阻塞 read，超时本身不使 socket 自动失效；因此不能只 set 一次 30 s 来实现累计期限。socket.close 会使阻塞 I/O 抛出，但仍需测试等待实际 worker/peer 结束。

本设计不新增 watchdog/计时器线程，不声称总调用最多 30 s：初次 DNS、connect、阻塞 write、flight lock 等待和 native close 不由 read deadline 中断；首次 read 之后的这些耗时会在控制权返回时消耗已启动期限。connect 保留独立 5 s 上限，若 read deadline 已启动可取更小 remaining；DNS 建址仍无已实现硬期限。codec 内存流测试也使用同一检查点，受控时钟优先。批量读取限制为已知剩余 payload/wire，避免缓冲预读任意尾部后宣称未读。

### 5.2 调用串行与关闭解耦

`RespClient` 使用一个 flight lock 串行完整逻辑调用，另用短 state lock 管理 closed/current lease。close 不获取 flight lock：先 state lock 内设 closed、detach 指定当前 socket，随后在锁外 close。不能由 synchronized call 持有 monitor 到 read timeout 才允许 close。

新建 socket 以 provisional lease 发布，使 close 能拿到尚在 connect/handshake 的 socket；握手成功且仍未关闭才标 ready。发布前/后、exchange/return 前和 retry 前检查 closed。所有 failure cleanup 以该 attempt 的 lease identity 进行 compare-and-detach，只能清除匹配的 current，关闭旧 socket 不得误关后来发布的 socket。late connect/handshake 成功但会话已 closed 必须关闭自身并拒绝发布。

取消 future 是逻辑状态变化，不等于 socket 已关闭或虚拟线程已退出。保留 native close best effort；loopback 测试要等待 peer EOF、client 任务退出、fixture 工作线程退出，不能仅看 future cancelled。无需添加可共享的全局连接池或状态机。

### 5.3 最后确认 DB：typed/raw SELECT

当前 `final database` 只保存构造值，但 `RedisSession.select` 和 raw SELECT 已能改变实际 DB。必须在 client 的串行调用中维护 `confirmedDatabase`，初始为配置 DB，成功握手只是确认此值。

- typed 和 raw SELECT 共用识别入口：ASCII 大小写无关命令、两个参数。解析 DB 参数无需生成大 String，覆盖合法十进制的正号/前导零以及可识别的零表示，且不溢出 int；负 DB/非法参数保留失败语义，不事先写入 confirmed 值。
- 只有**完整解码、期限未过且精确 OK ack** 的 SELECT 才更新 confirmedDatabase，更新与当前调用串行完成。top-level server error、发送前拒绝、EOF、部分 `+OK`、超限/损坏/超时都不更新。没有 ack 的 SELECT 不重放。
- 对无法解析/保存目标 DB 的 raw SELECT，若服务器竟返回 OK，不能让一个无法恢复的连接继续冒充旧 DB：discard 并报固定 session-context failure，保留最后已确认值；不猜新 DB、不将任意 raw 命令纳入状态机。
- 后续**新的显式调用**在新 socket 用配置/合成 AUTH 身份恢复最后确认 DB。确认 DB 为 0 时新 socket 默认就是 0；确认非零则握手 SELECT 对应值。恢复握手失败，业务不发送。连接损坏后不能静默回构造 DB。
- AUTH 恢复只用会话构造时的配置/合成身份，不读取旧配置文件，不追踪 raw AUTH/ACL 修改成新的全局权限模型。并发 SELECT/GET 仍由 flight lock 保证顺序。

核心回归：typed/raw（含小写）SELECT 2 成功 → GET 超限/断开 → 新显式请求必须看到恢复 SELECT 2；SELECT 失败或仅部分 OK → 后继只恢复旧确认 DB。脚本服务器记录每个连接的合成认证和目标 DB，断言没有未确认 SELECT replay、没有在错误 DB 发送业务。DB0/非零初始值均覆盖。

root 最新通知另有独立合成 loopback 原件：`D:/Projects/朝花夕拾/docs/superpowers/verification/evidence/g10-redis-20261009-design-review/result.json`，root docs commit `ad913334`。通知描述初始 DB2 → 成功 SELECT7 → GET 断开 → 新连接错误 SELECT2，两个连接与服务器线程均实际结束。这是 root 提供的当前缺陷诊断，开发未重新读取/执行或合并该 docs commit，不能记为开发修复通过证据；P1a 必须以新断言证明修复。

### 5.4 Session/manager 的必要局部修正

Session closed 可见且 close-once；关闭后新调用拒绝，已准入调用最终由 client close 状态拦截。manager 当前 catch-any-runtime 的 cached PING 替换会把预算/协议失败变成另一次连接，必须修改：明确 terminal kind 直接失败并清理不合适的缓存，不在同次 acquire 自动 reopen。下一次显式 acquire 可以创建新会话。只保留已有 transport 健康失败的替换行为；不以字符串判断。旧 mock `RedisException("connection lost")` 应升级成明确 transport kind，保留“仅传输健康失败替换”测试意图。

manager 不能在一个状态 monitor 内 PING/close/factory.open，否则 closeAll/closeIndependent 会被活动 PING 挡住。最小实现是短 map/state lock 加 acquire 专用串行 gate：snapshot config/generation、释放状态锁后探活/创建，再验证配置/关闭代际后安装；配置变更、unregister/closeAll 使晚到创建失效并自行关闭。independent open 同样验证代际。remove/detach/snapshot 在状态锁内，实际 close 在外，closeAll 对所有 snapshot 逐一 best effort；失败不能漏掉后面的 session。closeAll 不是永久禁止以后显式打开，但不能安装本次 close 之前在途的迟到 session。

此改动限 `RedisSessionManager`，不改全局 ConnectionManager/AppShell 关闭框架。测试以 latch 控制 PING/factory 和关闭次序；close 抢占 I/O 的物理能力由 RespClient loopback 另证，不能把 manager map 已清空当物理 close 完成。

## 6. UI 候选、保留与保存边界

### 6.1 纯格式化与 console

有界格式化返回 `{text, complete/preview}`，创建内容前检查预算；省略标记必须计入预算，UTF-16 截断不能拆 surrogate pair。用迭代遍历/受限 stack，深度和访问节点也有限，测试中的循环 list/未知对象不会调用任意 `toString` 或深递归。binary 的 printable UTF-8 判断用受限 scratch/流式验证，输出 prefix，不能先 decode 整个 8 MiB 到 CharBuffer/String；hex 每 byte 用固定表写 prefix，不能全量 `String.format`。

请求完成后的纯格式化在 worker 完成，成功回调只拿候选文本提交 Label。output retention 先算新条目并淘汰最旧条目，使 count 和总字符同时满足；history 用独立模型和 index，不混用 output 额度。请求/响应/错误/确认对话框文本都有显示 cap。

execute 的 input disable/busy 由请求 id 所有。成功或失败回调都用 try/catch/finally：当前且未关闭时必恢复输入/按钮；格式化预算失败走 worker failure，append/控件设置意外失败也走明确本地错误并 finally 恢复。不能指望 FxSerialTaskQueue 捕获 FX success consumer 的异常。closed/stale callback 不重新启用已关闭控件；不 catch OOM/SOE 当常规预算拒绝。

console 单飞并拒绝重复 execute；raw response 只在短 worker阶段持有，格式化后不留在 history/output。关闭先设 closed、停接任务，然后 queue cancel 和 session close 按既有 best-effort 次序，即使 queue close 抛错仍 close session。

### 6.2 SCAN 整页与树提交

[官方 SCAN 文档](https://redis.io/docs/latest/commands/scan/) 明确 COUNT 是提示、可空页、可重复、只有 cursor 0 结束，cursor 是无符号 64-bit。因此 Session 用 `parseUnsignedLong`/`toUnsignedString`（或等价有界 ASCII 实现）保持 long bit pattern，不能因超过 signed MAX 抛出或把负十进制发送。响应 cursor 字段最多 20 位且语法精确，转换前检查；0 与非零按 bit pattern 判断。

候选步骤如下，全部纯数据步骤在 worker 完成：

1. 捕获此次 session、DB/MATCH/separator、旧已提交 snapshot/cursor、扫描代际。refresh 的起始 cursor 为 0，但尚不清旧 UI。
2. 获得完整 page 后验证 shape、raw key、实际唯一键数和 raw byte 总量。COUNT500 返回600条只要预算满足就接受；空页 cursor 非零继续；重复键不累加 byte。对将超限的整页拒绝，不保留任意前几条、不推进 cursor。
3. 当前 browser 操作入口以 String key 为身份，lossy UTF-8 会碰撞/误操作。P0 选择对不能严格 UTF-8 往返的 key **明确拒绝整 UI 页**，保留旧树/cursor，提示该浏览器暂不支持此键页；Session 的 binary key/raw 值接口不改成 lossy，binary value 五类编辑仍保留。此处是显式安全限制，不宣称新增 binary-key editor；不按 replacement String 去重。
4. 单键/累计 bytes 先检查再转文本。KeyTreeBuilder 用增量 `indexOf` 分段，段/层级/新节点准入早于 substring/节点创建，保留前缀键、空段、自定义/空 separator 和顺序。显式 stack 冻结 model，避免 regex split 先生成巨量数组。
5. 全页可接受后返回 immutable candidate（keys/raw计量、tree、nextCursor、source、generation）。FX 构造有界新 TreeItem 图并准备控件后，才替换 root、提交 keys/cursor；只有当前 generation 可提交。预期预算错误已在 worker 解决。若 FX 安装失败，回滚旧 snapshot/root/cursor，finally 恢复 busy，错误提示有界。
6. 任何拒绝，包括被拒页的 nextCursor=0，都显示“未完整加载/请缩小 MATCH”，旧 cursor 不变；load-more 不伪称完成。重试同一页或更换 MATCH 是用户的新显式请求，不能自动跳过。

refresh、separator 变化也用候选；新的第一页面被接受前旧树保持可见。切 DB 时旧 display 标记原来源且 read-only，不能拿旧 DB 的 key 去操作新 session。新 DB/扫描失败不把旧内容标成新 DB 成功，不恢复旧页面针对新 DB 的 mutation。保留已提交 source 与 session 的关联，刷新成功后才移交可操作状态。

pane 增加 request/scan/key-selection generation，捕获 operation 的 session，避免 worker 运行时重新读取 volatile session。旧 value 页迟到不能覆盖新选中 key。至多一个活动任务，加一个可替换的“最新刷新/切 DB 意图”；不能每次刷新都 cancel 后向无界队列塞新 Future。当前请求 finally 负责结算/调度 pending；stale 回调不提交视图也不错误解除新请求 busy。late session 开启后若已 close/过代际，必须关闭自身。

### 6.3 string：读取完整与显示完整分开

保留现有 >1 MiB 时 GETRANGE 4096 字节和 collection 请求页200，不重做已有预览。forceFull 先读 STRLEN，超过单 bulk 8 MiB 时明确拒绝 full GET；即使 STRLEN 可接受，随后 GET 仍由 codec 管预算，不能用 STRLEN 证明值没增长。

value 模型保存 raw bytes、readComplete、displayComplete、模式与 previewReason：

- GETRANGE 是 partial 读取，无论返回是否变短都不变成完整编辑值；只可预览、禁 Save。
- 完整 GET 的 actual bytes 决定读取完整性。text/hex/JSON 若显示超 1 Mi UTF-16，显示 prefix并标预览，禁 Save；不能通过切模式重新启用 Save。
- strict UTF-8 文本失败时可显示完整 hex；若仅有 lossy text，则禁 Save，不能把 replacement 文本写回。完整 hex 编辑仍二进制安全。
- JSON formatting 在 worker，以 charAt/受限 builder、深度32处理，去掉全量 toCharArray 和无界 indentation。若输入嵌套或放大超限，明确预览/格式化拒绝并恢复输入；原 raw 不变，不把半截 JSON 当可保存结果。
- 保存前检查 editor mode/complete，再做字符/hex、UTF-8、整个 SET 请求预算；新 value 必须完整，不能从 truncated display 自动生成。≤1 MiB 普通文本、正常完整二进制值的编辑回归保留。

### 6.4 h/l/s/z：raw 身份与页预算

Session/页面构造在复制行集合前检查实际 rows，H/Z 先验证 pair 偶数。每格生成有界 text/hex preview，保留 rawA/rawB 和独立 long listIndex，不从截断 a/b 解析索引或成员。page 累计显示字符超过256Ki，或实际超过1000行，整页拒绝，保留旧 table/next cursor/offset；不能静默丢尾后推进。单元格截断允许标明预览，raw身份不丢。

HDEL/SREM/ZREM/LREM 等既有操作只能用对应完整 raw field/member/value；LSET 使用独立 raw index。编辑新值独立输入、完整准入；不能把被截断的旧 display 预填后当完整保存。可保存编辑值本身不截断，若无法完整展现则 read-only preview。表头、页摘要、prompt/status/Alert 都使用有界标签或固定消息。

完整 RESP 的展示页拒绝不废弃健康连接、不自动重新发 SCAN。busy/input 恢复和代际检查同上一节，即使格式化/控件安装抛错也可继续明确的新操作。

## 7. 最小实施文件与分阶段边界

P0 仅本报告。以下为 root 审查后的候选，不是已修改列表；实施若需超出本表，先写具体原因，不顺势开展维护成本重构。

| 阶段 | 产品文件 | 必要职责 |
| --- | --- | --- |
| P1a | 新 `src/com/datacube/redis/RedisResourceLimits.java`；可增加一个包内预算/失败 helper | immutable defaults、算术/UTF-8计长、deadline及分类，避免各入口各写一套 |
| P1a | `RespCodec.java` | 响应共享计数、非递归解码、精确请求编码、分类拒绝 |
| P1a | `RedisException.java` | 新客户端拒绝固定安全消息、kind/delivery；保留完整顶层server正文及公开构造器 |
| P1a | `RespClient.java` | 预检零发送、typed重试、deadline、lease/close、confirmed SELECT |
| P1a | `RedisSession.java` | 请求前置预检、可见关闭、unsigned cursor、小元数据、typed投影准入 |
| P1a | `RedisSessionManager.java` | terminal PING不替换重试，短状态锁、迟到创建结算 |
| P1b | `RedisConsoleSupport.java`；一个 Redis 局部纯展示/保留 helper（必要时） | tokenize/策略、迭代有界 text/hex/JSON、retention和候选数据，不引入通用框架 |
| P1b | `KeyTreeBuilder.java` | 原始准入配合、增量分段、节点/深度、冻结 |
| P1b | `src/com/datacube/fx/RedisConsolePane.java` | input准入、worker格式化、保留、回调恢复、closed |
| P1b | `src/com/datacube/fx/RedisKeyBrowserPane.java` | 候选/来源/代际、raw身份、preview-save、单飞pending、恢复 |
| 回归优先读，不预定修改 | `FxSerialTaskQueue`、`RedisPaneCloseSequence`、`ConnectionTreePane`、`ConnectionManager`、`AppShell` | 保留现有通用行为；INFO guard在Session完成，不需要为此改全局分支 |

需要小元数据限制的理由：PONG/OK 按 byte 直接比较，TYPE 上限短元数据128 bytes；score 上限128 bytes 后解析、cursor最多20位；不先把8Mi bulk复制成 String 再发现不合法。INFO keyspace 特定限制如第3节，其他 raw INFO 不新增全文展示。

P1a 完成后先交源码/定向证据给 root 检查失败分类、零发送、SELECT、物理关闭，再 P1b。P2 按计划保存新的定向、clean全量、强制 buildSrc:test、jpackageImage 和隔离镜像/linked probe；开发是唯一 Gradle 执行者，停写冻结后 root 接管。P3 的 main 集成/独立验收/push/精确 SHA CI 由 root 掌握。本 P0 停在审查点。

## 8. 断言与证据矩阵（待实施/执行）

所有数据合成；内存流/mock 优先，网络仅显式绑定 `127.0.0.1` 的临时端口脚本服务器。现有 RespClientTest 的 `new ServerSocket(0)` 会绑定 wildcard，新增/复用 fixture 前需改为显式 loopback，记录 server 异常并断言 accept/read worker 结束，不能吞 Throwable 再仅看 done latch。只用合成身份和独占 UUID 目录，不读取现有 profile。

| 用例组 | 必须证明的具体断言 | 承载位置/方式 |
| --- | --- | --- |
| marker/头/line | 每类正常/null/empty/binary；数字 signed边界/非法负长度/空/溢出/坏CRLF；line/numeric cap恰好/+1；巨大声明不读取尾部、不分配声明长度 | RespCodecTest + 计数/禁止尾读 InputStream |
| bulk/wire | 单bulk恰好/+1；多个各自合法bulk合计超wire；header/CRLF计账；预算恰好终止；EOFmidpayload独立transport类别 | 更小immutable预算、精确输入计数；不依赖默认巨大fixture |
| array/node/depth | per-array恰好/+1；分支累计nodes；root/null计数；depth32/33；整数计算不回绕；父/子不重置 | RespCodecTest；受控小预算；小堆子进程另外测原巨大头/6000深度 |
| 请求 | String/bytes/Session/raw/握手等于/+1、参数数目、聚合payload/frame；UTF-8 ASCII/中文/emoji/unpaired surrogate；未知对象toString不被调用 | RespCodecTest/RedisSessionTest/RespClientTest；oversized对象预检beforecopy |
| 零发送 | 超限业务在无连接时连AUTH都不发送；已有健康连接拒绝后仍可正常新调用；参数拒绝NOT_SENT | loopback accept/byte记录+mock，不只断言抛异常 |
| 完整/嵌套错误 | 顶层WRONGTYPE/NOAUTH原消息精确保留，完整后同socket PING成功；嵌套error+剩余兄弟拒绝后peerEOF，新连接下一响应不串线；客户端拒绝消息不含合成secret/body | RespCodecTest/RespClientTest，保留原精确正文契约，新增拒绝安全消息断言 |
| wire拒绝不重试 | AUTH/SELECT/business分别资源、protocol、deadline；safe GET/PING同次只有1次相应attempt；next显式调用重新AUTH/confirmedSELECT；managercachedPING同次不factory重开 | loopback每连接/业务发送计数；RedisSessionManagerTest typed failure |
| 传输重试/写不确定 | 原有PING/GET断开最多retry一次仍成功；INCR发送后EOF/超限/坏CRLF/期限不重放且MAY_HAVE_SENT；握手失败业务NOT_SENT | RespClientTest；服务端计数与消息/delivery同时断言 |
| confirmed DB | typed/raw/mixedcase SELECT成功后drop/超限，后继目标DB正确；toperror/partialOK/期限失败仅恢复旧DB；DB0/非零、并发序列、无法追踪的ACK discard | 脚本server记录每个合成连接AUTH/SELECT/业务次序及DB，不碰真Redis |
| 期限 | 持续小片段每次不足idle cap仍累计到deadline；数组/握手/业务/读retry不重置；beforeallocate/afterread/return检查；不足1ms非无限；早期idle与deadline分类不同 | 单调fake clock/分段InputStream优先；少量短loopback physical-close证明 |
| client/manager关闭 | active read时close不等flightlock；peerEOF/调用退出；旧leasecleanup不清新lease；close后无retry；manager PING/factory阻塞时detach不阻塞，lateopen自关，closeAll失败仍处理后续 | latch/受控factory；显式join/EOF及异常收集，非长sleep |
| Session metadata/cursor | unsigned MAX、signed MAX+1往返、非法cursor；H/Z oddpair、rowscap；TYPE/score/INFO转换前拒绝；binary values保持 | RedisSessionTest synthetic executor，不运行真实连接 |
| console纯格式化 | 深list/循环/未知对象；8Mi binary输入不全量输出；text/hex/indent/prefix恰好/+1、surrogate完整、截断标记计费 | RedisConsoleSupportTest/纯helper测试 |
| retention/input | output条数和总chars分别触发淘汰；history100/64Ki分别触发/index正确；input在tokenize和getBytes前拒绝；失败输入恢复 | 纯模型+Redis pane FX 回归 |
| key候选 | COUNT500返600；多页累计/重复/empty-nonzero/unsignedcursor；单键bytes/多分隔深度/节点/+1；binary key页明确拒绝；失败cursor/旧树不变，被拒终页不报完成 | KeyTreeBuilderTest + 纯candidate/FX mock session |
| refresh/DB/选择 | 第一页失败旧树保留；旧来源只读；新DB迟到和新key选择不覆盖；pending≤1；close后lateopen关闭且latecallback无UI提交 | latch控制mock + FX；不只做源码grep |
| collection/string保存 | rows/aggregate过限整页拒绝不推cursor；raw字段/成员/index不取display；截断预填不能保存；GETRANGE/read-full/display-full区别；STRLEN后增长；forceFull≤8Mi guard；text/hex/JSON预览切模式仍禁Save | 纯value/candidate模型 + Redis pane FX；五类正常编辑/binary回归 |
| FX callback异常 | 格式化预算failure和主动注入安装consumer异常后input/busy恢复；旧page/cursor回滚；stale不能解除新busy；close后不reenable | 显式FX测试seam，finally状态断言+后续请求可执行 |
| 关闭/构造旧契约 | RedisPaneCloseSequence queue失败仍sessionclose；ConstructionOwner、ManagedPaneConstructionContract、已有Task6/背景tab契约不退化 | 现有相关定向测试；不以新预算模型测试取代生命周期回归 |
| 工程/镜像 | 当前XML、命令/exit/raw、fresh产物SHA、首失败保留；smallheap probes受控异常，无OOM/SOE；镜像/linked退出与worker/socket结算 | P2全新UUID环境；G9旧XML不复用，UP-TO-DATE不记新通过 |

FX 测试先验证桌面/工具包可用，再创建资源/执行 assertThrows。headless skip 要单列，不能把图形 unavailable 吞成产品期望异常；沿用 G9 对前置环境失败的教训。超时是证据失败，不通过延长统一等待或加 sleep 换绿。真实 OS/DNS/网络、所有合法最大响应的RSS、原生桌面和真 Redis 的局限在 P2/P3 报告保留，不能凭 synthetic loopback 推广为全面外部验收。

## 9. P0 审查结论与待 root 准入项

设计保留全部候选预算，资源/已知协议/累计期限拒绝不借用传输读重试；完整顶层 server error 与嵌套未读尾分开；写发送状态、confirmed SELECT 和 manager 健康检查闭合到同一策略。UI 的预期格式化/候选检查在 worker，回调另有 finally 恢复；SCAN/collection拒绝不提前推进 cursor；raw 身份和可保存完整值不从展示预览生成。

需要 root 审查的具体政策是：新增局部上限、不支持的非 UTF-8 键整页明确拒绝、Redis manager 必要短锁/代际范围、confirmed raw SELECT 的恢复边界。完整顶层 server error 保留原正文及精确测试；不做全局脱敏。无产品实现提前落地。

P0 新分支保持在上述 HEAD；仅新增本文件，产品/测试无差异。没有 Gradle、暂存或提交。下一动作是 root 审查并准入 P1a；开发本轮到此停写，不开始维护成本整理。

## 10. P1a 实施与冻结交审（2026-10-09）

**当前状态：P1a 产品修复和新定向验证完成，产品/测试停写交 root 审查；未进入 P1b 或 P2，未暂存/提交。** 上文第1—9节是已审 P0 历史记录，不把其“未实施”状态误作当前状态。root C1 准入来自外部主工作树的协调记录，未合并其文档提交。P0 原件 SHA-256 为 `AE0E2FDC3E5DF1E179D92A7EEC16CFA5A78AADC49076D498A983A953288C4030`，本轮追加前再次匹配并复制为 [p0-approved-original.md](evidence/g10-redis-20261009-p1a-worker/p0-approved-original.md)，root 的独立归档仍不变。

### 10.1 实際实施范围

六个产品文件全部限 `src/com/datacube/redis/`：新增 `RedisResourceLimits`，修改 `RespCodec`、`RedisException`、`RespClient`、`RedisSession`、`RedisSessionManager`。没有修改两个 Redis pane、ConsoleSupport、KeyTreeBuilder、全局 queue/runner、ConnectionManager/AppShell、构建配置或依赖。

- 默认预算保持 P0 值，record 的构造校验只允许正值且不高于默认值，测试注入更小预算；无环境绕过。
- Codec 是迭代数组栈，一个响应共用 wire/node/pending-node 计数；声明、数字语法/溢出和深度在危险分配前拒绝。bulk 原位读到精确 byte[]；array 使用初始容量0的小 list，按实际子项增长，不按声明分配。line 逐步增长但始终先检查 cap；正常完整顶层 error 保留原文，嵌套 error 在 marker 后直接安全拒绝、不读原正文/尾部。
- 请求在 String UTF-8/byte矩阵/frame 复制前预检。参数数先限制，再做有界外层引用快照，String 不可变、byte[]长度固定。实际编码帧固定后，client 从该**帧本身**读取短命令和 SELECT 目标，重试复用同一帧，不再从 caller 的可变 args 决定读重试或上下文策略。caller 复制期间修改 byte[] 内容不承诺原子快照，但策略始终对应实际发出的 frame；后续 mutation 不能让 retry 换命令/目标。
- 资源、已知协议、嵌套尾部、累计期限分别 typed；不走 safe-read retry。EOF/传输失败的原 allowlist 仍最多retry一次。请求/握手发送前拒绝为业务 NOT_SENT，尝试业务write以后为 MAY_HAVE_SENT，写异常保留结果不确定/未重放；完整顶层 server error 原消息和 REPLIED 保留。
- deadline 首次 response read 启动，共享 AUTH/SELECT/业务/安全读retry；每次读、解析/分配/返回，以及每次握手/业务写出前检查。已启动期限下 connect 取剩余与5s较小值；不足1ms不能变成无限SO_TIMEOUT。DNS/阻塞write仍不受已实现硬中断，控制返回才检查期限。
- client flight/state分锁，provisional lease 让 close 可到达 connect/握手/阻塞read。全部 socket.close 在状态锁外，cleanup只detach对应 lease。非 Redis RuntimeException/Error 同样执行lease清理后**原样重抛**；合成 AssertionError 测试验证对象身份和peer关闭，不实际制造OOM，也不把VM Error当预算保护。
- Session 使用可见且一次性的关闭状态、同样的请求前置预检、无符号cursor往返、小元数据转换前检查。typed INFO keyspace 先检查64KiB/1,024行再UTF-8转换，导航不产生巨量split；此行数按1+LF保守计量，尾部空行也计。普通raw INFO仍按通用RESP预算。
- manager 仅持短registry锁，PING/factory/close都在外。工厂返回且代际有效后，首次acquire的session在PING前进入pending ownership，closeAll能取得真实活动会话；探活/安装结束撤销pending。closeAll、配置变更后的迟到结果不安装并关闭。closeAll snapshot覆盖cached/independent/pending，尝试全部、汇总suppressed，Error优先原样保留。cached PING的terminal失败不reopen；纯transport失败仍替换，若旧session关闭失败则报告该失败并停止本次替换。

六个测试文件：新增 `RespBudgetTest`、`RespRecoveryTest`、`RedisSessionBudgetTest`、`RedisManagerBudgetTest`；修改旧 `RespClientTest` 的fixture明确绑定127.0.0.1、记录server异常并join断言物理结束；旧manager健康失败mock改成明确TRANSPORT kind，保留原替换行为断言。原顶层WRONGTYPE/NOAUTH正文精确测试未放宽。无需修改旧产品测试来降低预算或删掉失败类别。

### 10.2 raw 上下文恢复的具体政策

直接 SELECT 只有完整OK、目标可解析且响应期限有效才更新confirmed DB；typed、raw、小写、正号/前导零都经过实际frame识别。失败/partial OK不改变最后确认值、不重放SELECT。后继新连接恢复最后确认DB和构造时合成/配置AUTH，测试证明初始2→确认7→GET断开后恢复7，失败/未确认时仍恢复2。

不实施完整CLI状态机，也不发送前禁用普通数据命令。以下已识别raw modes在尝试发送时将恢复标为不可信：`AUTH/MULTI/EXEC/DISCARD/RESET/WATCH/UNWATCH/HELLO/CLIENT`、订阅/取消订阅模式和`MONITOR`。同一仍可用的socket继续执行原有命令；一旦它损坏/不完整而被discard，本session持续context-invalid，禁止自动读retry和后继显式调用按旧AUTH/DB重建，固定提示重开session。为了保守性，即使这些mode返回普通server error，恢复信任也不会自动恢复；该新兼容限制仅针对这类会话模式断线后的恢复，不扩大UI只读权限门禁。

SELECT 返回QUEUED不会记录为确认，MULTI→SELECT→EXEC的数组结果不猜DB；RESET不假装仍为旧DB；raw AUTH不把新身份伪记成旧配置。无法解析目标的SELECT却收到OK时立即discard并永久context-invalid。四种场景都有合成回归，断言后继业务无新连接/错误上下文发送。完整top-level server error仍有界保留正文，client新建拒绝/审计不回显输入内容。

### 10.3 原始证据和首次失败

全部位于 [P1a evidence](evidence/g10-redis-20261009-p1a-worker/)。`Run-P1a.ps1` 每次新UUID home/temp/build，清空环境只传五个具名OS运行变量、现有JDK/cache及owned路径；offline/no-daemon/rerun-tasks，未传live Redis变量。每轮有真实argv/exit/stdout/stderr、前后源哈希、当前XML及从XML计算的summary。未执行clean全量、buildSrc:test或image；buildSrc compile/jar仅为正常定向Gradle启动依赖。

| 原件目录 | 实际结果 | 与最终冻结的关系 |
| --- | --- | --- |
| `001-codec-first` | exit1，Gradle在编译前拒绝空org.gradle.java.home | 首次失败保留；PowerShell array里未加括号导致argv拼接分项；修正脚本后用新目录，不覆盖日志 |
| `002-codec-corrected-runner` | 15 total，15 pass，0 failure/error/skip，exit0 | Codec阶段，不冒充最终client/manager证据 |
| `003-client-manager-first` | 63 total，62 pass，1 skip，exit0 | 首轮client/manager；输入前后一致 |
| `004-client-manager-expanded` | 73 total，72 pass，1 skip，exit0 | 补充deadline/关闭和纯生命周期回归 |
| `005-smallheap-probes` | compile+6 probes全部exit0 | 首批独占小堆diagnostics，不代替最终源冻结 |
| `006-p1a-final-targeted` | 74 total，73 pass，1 skip，exit0 | 补充请求快照/握手零发送/健康连接保留 |
| `007-p1a-final-cleanup` | 75 total，74 pass，1 skip，exit0 | 补充合成Error清理，Error身份保留 |
| `008-final-smallheap-probes` | version/compile+6 probes全部exit0，所有子进程实际exit | 最终Codec的源副本、class副本、JDK binary hash及argv/raw均保留；后续仅client关闭锁位置收尾，不改Codec输入 |
| `009-p1a-frozen-targeted` | **75 total，74 pass，1 skip，0 failure/error，exit0** | 本P1a最终冻结定向；全部任务实际执行，输入前后与冻结源码绑定 |

唯一skip是 `RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle`：缺`DATACUBE_REDIS_HOST/PASSWORD`的前置Assumption在创建连接/凭据前退出。清空环境是本轮主动隔离，未读取真实值、未尝试真实Redis。不是桌面通过，也不是live五类验收。此次FX过滤仅纯关闭/构造契约，不宣称真实pane/桌面验证。

首次补丁曾因同一文件Delete/Add共用一批被工具拒绝，拆成顺序操作后落盘；没有把工具错误记为测试失败。产品/测试编译和实际测试未出现red；不捏造红绿历史。后续重复仅因新测试、root具体收尾和最终源变化，不用旧XML或UP-TO-DATE替代新执行。

### 10.4 小堆诊断、分配量与物理结算

`Run-Probes.ps1` 显式现有JDK25.0.1编译三份最终产品源码和helper；每个probe独立`-Xmx32m -Xss256k` JVM，零网络、合成输入、owned目录、清空环境。编译和运行argv、每个exit/raw、源副本、编译class、JDK binary hash均归档。timeout分支先kill+wait并保存日志/exit，再失败，不把逻辑timeout当物理结束。

| probe | 输入/读入 | 结果 |
| --- | --- | --- |
| 巨大array | 13 byte声明；13次单byte read | RESOURCE，无按2,147,483,647项分配，无尾读取 |
| 巨大bulk | 13 byte声明；13次read | RESOURCE，无按声明分配 |
| 6,000层array | 输入24,004 bytes；仅132次read（33个头） | RESOURCE，无递归SOE |
| 100,000 byte simple line | 输入100,003 bytes；65,538次read | RESOURCE；包含marker、64Ki内容及一个溢出判别byte，不任意读尾 |
| 多个合法子数组累计nodes | 输入400,085 bytes；360,085次read | RESOURCE；共享pending/node credit在最后子数组声明拒绝，不按子数组重置 |
| 请求聚合payload超限 | caller已创建8Mi ASCII value，加SET/key后超限 | RESOURCE/NOT_SENT；在UTF-8/完整frame副本前拒绝 |

这些是危险路径在小堆下受控停止的实测，不是所有最大合法命令在32Mi堆内通过，也不是RSS测量/上限。默认bulk、wire、node、depth不因探针而提高。

实现的副本说明补足P0估算：String client入口直接填单个精确frame；Session门面仍有预检后的UTF-8参数副本（payload合计≤8Mi），随后client外层snapshot/int lengths和一个frame。外层/lengths各≤10,000项，非payload无界副本。line采用受限增长+最终copy，返回瞬间buffer与result可各≤64KiB，旧增长数组待GC，单行累计分配约≤3×cap；没有把它写成“整个堆只有64KiB scratch”。响应list从容量0按实际增长，显式frame栈≤32；object headers、Long boxing、引用容量、旧垃圾、多会话仍另计。

新loopback fixture只绑定127.0.0.1，每个脚本记录Throwable、socket有独立短等待、fixture显式join；拒绝用peer EOF证明连接实际关闭，late-open/close工作线程也join断言。已覆盖AUTH/SELECT/business分别超限、ordinary error同socket继续、nested尾部不串线、协议写失败不replay、safe GET transport retry、confirmed DB、raw上下文失效、读取期限、caller mutation、显式close阻塞read、manager首次PING关闭、合成clock Error原样抛出。没有新增产品线程/watchdog/计时器，临时fixture线程均物理结算。

### 10.5 冻结入口与后续边界

[raw-manifest.json](evidence/g10-redis-20261009-p1a-worker/raw-manifest.json) 对本轮所有原件、最终Redis源码/测试副本、最终Gradle Redis class、probe class和报告快照给出长度/SHA-256（manifest不含自身）。[final-freeze](evidence/g10-redis-20261009-p1a-worker/final-freeze/binding.json) 绑定新分支/HEAD、最终定向输入及实际75/74/1计数；冻结时逐项重核最终run输入与活源码一致。class文件也纳入manifest，避免忽略规则导致只统计文本日志。旧G9分支/evidence未改。

分支仍 `codex/g10-redis-resource-budget-20261009`，HEAD仍 `66edd24f50a327a82628d70de72fb7b7911c2ae2`。没有暂存/提交、main合并、fetch/push、tag/PR、发布或安装；root之外无并行Gradle执行者。未读原有profile/凭据/history/业务文件、未触碰`.testagent`。

余留范围明确：UI输入/hex前置分配、格式化/保留量、键树/SCAN候选、preview禁保存、实际collection页面限额和FX回调恢复仍在P1b，未宣称本轮解决；P2全量/buildSrc/image和root P3独立集成后置。工厂自身阻塞在返回session之前无法被manager物理关闭，只能由代际拒绝迟到结果；DNS/阻塞write/native close/JVM GC无已实现即时硬限，不以空map、future取消或已抛异常声称全部物理停止。P1a产品/测试现在停写，等待root独立审查后才进入P1b。

## 11. P1b 实现与新定向冻结（2026-10-09）

**当前状态：P1b 实现与新定向完成，产品/测试停写交 root 审查；没有进入 P2，没有暂存或提交。** 第10节及其冻结目录是 P1a 的历史记录，不改写原件。P1b 准入来自 root 已审 C2；后续具体来源绑定、opening 所有权、集合早停、pending refresh、共享 zset action、list 页保留及旧 action 无入队要求已纳入实现。未合并 root 文档提交。

### 11.1 实现范围与预算

P1b 修改八个产品文件：`RedisConsoleSupport`、`KeyTreeBuilder`、两 Redis pane，新增四个 Redis 局部纯模型 `RedisDisplayLimits/RedisDisplaySupport/RedisTextRetention/RedisKeySnapshot`。新增 `RedisDisplayBudgetTest/RedisPaneBudgetTest/RedisTestSession` 三个测试文件。P1a 六个产品文件保持其冻结内容，没有修改通用 queue、ConnectionManager、AppShell、连接权限或其他编辑器。

不可变 display record 固定最大值，只允许更小正值注入，没有环境绕过：console 输入/单条64Ki UTF-16，输出200条且512Ki，历史100条且64Ki；键5,000个唯一值、原始字节合计2Mi、单键64KiB；树20,000节点（含root）、深度32；separator64、MATCH4Ki、label4Ki；collection1,000行、单格4Ki、页256Ki UTF-16；string editor1Mi UTF-16。原 SCAN COUNT500、collection COUNT/请求200、STRLEN>1Mi时GETRANGE4096保留；COUNT仍只是提示。

console 在 tokenize/复制和 prospective 全文生成前准入，策略只检查命令/短参数；显式迭代 formatter 约束节点/深度/字符，循环列表和未知对象不能调用任意 toString。UTF-8 流式校验、hex/JSON 在 builder 写入前检查额度；无完整多倍 hex、全量 toCharArray/repeat 或不受限递归。格式化在 worker 完成，回调 finally 独立恢复输入；关闭/旧回调不重新启用或追加。输出/历史独立双维淘汰，命令回显、错误和省略标记计入单条/总字符。

SCAN 候选在发布前检查原始 single-key、UTF-8、唯一数/总字节、树节点/深度/label，整页构造成功后才移交 cursor/source/tree。无效 UTF-8 key 拒绝整页，不创造 binary-key 编辑功能；duplicate不重复计费，空非零页/无符号cursor保留。树按实际节点增长，先检查深度和节点，再建模型，显式冻结栈避免递归构建输入风险。worker 的 TreeModel 递归只访问已准入的最多32层。

collection 在投影前检查实际 rows，再逐行累计两格/序号/省略字符；64字符预留覆盖固定 collection 标题/工具文字，键详情来源头另受 label cap。超总量时最多持有一个有界被拒行，随后元素不再访问；候选不丢尾后推进。raw field/member/value 和独立 long index 保留原引用，操作不解析显示文本。list 修改捕获接受页的 offset 并刷新同页；zset 分数编辑和测试共用 updateScore→addMember 路径，不保留仅测试使用的 zset updateRow 分支。

### 11.2 来源、编辑完整性与关闭

浏览器至多一个 active 请求和一个最新 pending 意图；已 pending 的 refresh 在 loadKey 改变 generation 前优先，旧树选择不能使刷新失效。每项操作捕获 session、snapshot、DB、generation、value epoch；TreeCell菜单、详情按钮和 modal 返回均校验产生时的 binding。切 DB 失败保留旧 db 来源且只读，旧 action 不向新 session 入队。成功候选 UI 安装后才发布 session/snapshot；部分安装抛错恢复旧 tree/details/分页控件。错误状态优先显示来源和“未完整加载”，剩余额度才附错误详情，长 server error 不再截掉关键状态；P1a 原 server error 消息语义未改。

新 session 在 factory 返回后、PING 前登记 opening 所有权；关闭不依赖 queue 已关闭后会被丢弃的 FX callback。factory 未返回阶段只能拒绝迟到结果并关闭；factory/PING/等待FX安装三个 mock窗口均实际结算 worker，session close计数正确，无迟到安装。通用queue/future cancel本身不被当作物理结算证据。

StringViews 分别记录 readComplete 和各模式 displayComplete。GETRANGE始终只读；完整 raw 的 HEX 被截断不会永久禁掉可完整显示的 TEXT，切回可重新编辑。模式监听 valueProperty，权限与值同步，不依赖控件 skin 是否已安装。完整读取先检查 STRLEN≤8Mi，实际GET仍受 P1a bulk/wire硬门限；文本/hex输入按整条SET/HSET等前置预检后才产生UTF-8/byte副本。二进制值通过完整 HEX 正常编辑。

### 11.3 原始运行与失败保留

新证据独立位于 [P1b evidence](evidence/g10-redis-20261009-p1b-worker/)，不改 P1a 任何冻结文件。每轮新UUID隔离 home/temp/build，清空环境、只保留具名OS变量及现有 JDK/cache；offline/no-daemon/rerun-tasks，实际8任务执行。运行参数、exit、stdout/stderr、输入before/after、XML及其统计均保留。

| 目录 | 实际结果 | 范围和失败解释 |
| --- | --- | --- |
| `001-pure-first` | 16 total/pass，exit0，无skip | 首版纯helper，不覆盖后续集合早停/pane |
| `002-pane-compile-pure` | 20 total/pass，exit0，无skip | headless=true，early-stop/tiny-notice及纯构造/关闭；不声称FX交互 |
| `003-fx-first` | 29 total，8 failure，exit1，无skip | headless=false；测试查找器未遍历SplitPane/ScrollPane，八项未到行为断言；原失败保留 |
| `004-fx-controls-and-pagination` | exit1，未启动Gradle编译 | 工作区沙箱访问既有Gradle wrapper缓存锁被拒；环境失败，后续具名提权复用缓存执行，不是产品失败 |
| `005-fx-controls-and-pagination` | 30 total，3 failure，exit1，无skip | tiny label截掉来源/未完整标记、无skin的模式setValue未触发onAction；修产品状态顺序及valueProperty监听，没有加大cap或删除断言 |
| `006-fx-mode-and-source-status` | 30 total/pass，exit0，无skip | native JavaFX toolkit、10项FX mock实际执行；后续又补两处旧action无入队及collection候选回归 |
| `007-p1b-final-targeted` | **88 total = 87 pass + 1 skip，0 failure/error，exit0** | **15 XML、841项输入前后/当前一致，11项FX mock实际执行且0skip**；包含全部Redis定向、P1a回归、纯构造/关闭 |

唯一skip仍为未配置真实Redis的 `RedisLiveIntegrationTest`，其前置Assumption在建连接/资源前退出，没有把live五类计为通过。FX每个方法先初始化toolkit gate，再创建mock/runner/断言；最终 native-toolkit gate实际通过，无headless替代skip。没有连接任何真实Redis/DB，只有既有P1a的显式127.0.0.1脚本socket及纯synthetic executor。未创建Scene/完整AppShell的本机桌面会话，因此这些是实际FX控件/handler/mock回归，不是用户桌面端到端验收。

11项FX覆盖：console双维保留/安装失败再操作；关闭阻塞worker和迟到回包；key最终cursor0拒绝及树安装失败恢复；pending refresh和最新DB意图；旧GET不能覆盖新选择；DB失败保留来源/旧DEL不入队；完整TEXT→截断HEX→TEXT编辑恢复、range/oversize full禁止保存；五类binary/完整field/member身份；factory/PING/queued安装关闭；list长index显示仅两字符但LSET发送123456789且LRANGE重读同offset；collection旧next17→cursor0三行超限拒绝→值页安装失败→仍从17成功重试。后两项集合拒绝与来源操作测试同时检查旧table/raw identity、busy恢复、activeRequest不变，避免异步命令尚未执行就误判零写。

### 11.4 冻结与局限

[final-freeze/binding.json](evidence/g10-redis-20261009-p1b-worker/final-freeze/binding.json) 绑定分支/HEAD、最终argv和XML统计、841项before/after/当前输入、P1a冻结284项复核及六份P1a产品未变检查。[raw-manifest.json](evidence/g10-redis-20261009-p1b-worker/raw-manifest.json) 包含所有新原件、最终源/测试副本、实际最终编译class、报告快照、冻结脚本的长度/SHA-256；manifest不含自身，随后逐项重核。旧G9分支仍6b8ceb93，HEAD仍66edd24f；首次只读命令误用不存在的G9分支名exit128，随后用第1节原分支名核对，不把读命令失败当产品证据。

有界显示不等于整体RSS硬上限：decoded raw可保留至P1a响应限额，old与candidate在提交时短暂共存；text/hex/JSON各有editor cap，builders扩容/最终String副本与GC垃圾另外计量。collection可保留≤pageChars显示和一个被拒行的临时两格，raw数组共享；tree/String/FX对象开销另计。默认最大合法多会话RSS未测。factory返回前、DNS、阻塞write、native close、GC的P1a局限保留；mock gate线程已join，不外推为实际服务/native故障硬中断。

只完成本阶段新定向，没有clean全量、buildSrc:test、jpackageImage、安装、合main、CI、fetch/push、tag/PR或发布。P1b现在停写等待root审查；P2及P3必须另获准入，G10尚未最终交付。未读取原profile/凭据/history/业务文件，未触碰`.testagent`，未创建其他会话/代理或自动化。

## 12. P2 新工程验证与最小原件冻结（2026-10-09）

**当前状态：root C4 准入的 P2 已完成；产品、测试、Gradle全部停写停测，交 root 独立审查并接管 P3。没有暂存、提交、合 main 或推送。** 第10/11节为已验收历史，P1a/P1b冻结原件不变。开发分支仍 `codex/g10-redis-resource-budget-20261009`、HEAD仍 `66edd24f50a327a82628d70de72fb7b7911c2ae2`；root主工作树的后续文档提交未合入。本阶段未修改产品、测试或入库 build.gradle。

### 12.1 隔离输入、配置故障与实际执行

新证据位于 [P2 evidence](evidence/g10-redis-20261009-p2-worker/)。每个 Gradle run 使用全新UUID home/temp/build，清空子环境，仅具名OS运行变量、现有JDK25.0.1/Gradle9.2.0缓存和owned路径；offline/no-daemon/rerun-tasks，headless=false。通过 Win32 GetShortPathName 记录真实owned temp的8.3路径，不手写别名。`isolated.gradle` 将主/buildSrc输出、版本/品牌生成资源和打包图标重定向UUID build；原生成器和版本内容保持，生成步骤的当次脚本在各run/launcher归档。

绑定 **863** 项输入：427主源码、408测试源码、3 buildSrc文件、6资源、7品牌素材、2本地driver jar、2 Gradle wrapper文件、3 CI workflow及具名构建/README/launcher。每次保存before/after长度与SHA-256，四个成功run及linked audit和冻结时重新与当前输入逐项核对。G10全部14产品/9测试源同时与P1b冻结副本一致；没有因工程验证重写产品或降低断言。

| 原件run | 当前命令/结果 | 计数与范围 |
| --- | --- | --- |
| `001-complete-targeted` | exit1，Gradle配置前段失败 | init误用jpackage DSL属性；只执行buildSrc compile/jar，没有产品测试结果；原日志和失败脚本保留 |
| `002-complete-targeted` | exit1，generateBrandIcons失败 | init无法直接解析buildSrc类名；主编译执行、未执行测试；修为实际buildscript classloader反射，不改产品 |
| `003-complete-targeted` | `cleanTest test --rerun-tasks`，exit0，2m29s | **56 suite、292 total = 291 pass + 1 live skip**，0 failure/error；Redis、相关关闭/构造/生命周期、queue、AppShell shutdown、ConnectionManager契约 |
| `004-clean-full` | `clean test --rerun-tasks`，exit0，6m15s | **351 suite、4,714 total = 4,711 pass + 3 live skip**，0 failure/error；11项Redis pane FX实际执行，无FX skip |
| `005-buildsrc-forced` | `:buildSrc:test --rerun-tasks`，exit0，7s | **1 suite、8 total/pass**，0 failure/error/skip，4任务实际执行；不以compile/jar替代buildSrc:test |
| `006-image-forced` | `jpackageImage --rerun-tasks`，exit0，1m51s | **14任务全部实际执行**；主compile/jar/merged-module/jlink/app-image，不生成安装包 |
| `007-image-linked-audit` | 四个工具/compile/probe子命令全部exit0 | image隔离、外置compile、driver发现、G10 linked Redis/展示探针；全部子进程WaitForExit，实际socket/peer join |

全新UUID目录内cleanTest/clean的UP-TO-DATE表示没有旧输出需要删除，实际compile/test均执行；没有复用旧XML。每suite属性与case/skip节点数量交叉检查，使用SelectSingleNode避免空`<skipped/>`的PowerShell truthiness漏计。首次配置/工具类可见性故障保留，不能称产品测试RED；容量中断消息到达时006已有真实run，恢复同session读取exit0，没有重复启动Gradle或把尚无exit当通过。

三个服务skip原文与ID均在新XML/summary：RedisLiveIntegrationTest缺显式Redis环境；SchemaDiffLiveIntegrationTest的Oracle/PostgreSQL缺write gate/完整provider环境。前置Assumption在创建服务连接/资源前退出；未传或读取真实凭据，未访问真实Redis/DB/pg_dump。没有强制headless来大量跳过FX。

006 stderr保留 **12条`javac failed: Picked up JAVA_TOOL_OPTIONS...`及12条`java failed: Picked up JAVA_TOOL_OPTIONS...`**，stdout保留JEP493提示；未删日志或称stderr为空。实际任务exit0，后续新linked javac及两probe均exit0，镜像模块/默认cfg通过独立检查，故没有用工具提示伪造失败或掩盖实际非零退出。

### 12.2 新镜像身份、泄漏审计与 linked 行为

镜像仅为免安装app-image，实际位置 `C:/Users/hetia/AppData/Local/Temp/datacube-g10-p2-5b6bb3b120d441fbbb7e9cbe9e001f65/build/main/jpackage/DataCube/`。完整 [image-manifest.json](evidence/g10-redis-20261009-p2-worker/007-image-linked-audit/image-manifest.json)记录所有文件长度/哈希，最终冻结逐项重算；镜像保持在该UUID目录，不删除或安装。外置验收class/home/temp不进入镜像。

| 实际产物 | 字节长度 | SHA-256 |
| --- | ---: | --- |
| DataCube.exe | 595968 | `6C32DDB83447C5754B5484B7D0C0F501CF48AD515993F96143388D2B4A32074F` |
| app/DataCube.cfg | 369 | `E53F0D480A7462920E5D0B6DF5E12BB24BBAA011298317A090CA174FBCC6153D` |
| runtime/lib/modules | 102725348 | `6FE70009EF582903716AB4F301F23D4BA65A035B2640BC921780F14020638C91` |

审计实际jimage模块索引，确认新增Redis budget/display产品类型存在。先将全部测试源Windows路径正规化为`/`，并强制type inventory恰为 **408**；root在首次audit前指出原脚本空集合风险，已修且未将空集合旧结果计为通过。classLeaks/fileLeaks为空、optionLeaks=false，实际cfg只含产品默认选项，没有JUnit/mock/probe、合成profile/home、headless或临时选项泄漏。

G10LinkedRedisProbe在镜像外用当前JDK的javac `--system` 对实际linked runtime编译，随后实际 `runtime/bin/java.exe -Xmx64m -Xss256k`运行，并断言RespClient来自named `com.datacube` 模块。只绑定127.0.0.1的脚本server：实际trace为`SELECT 2 → SELECT 7 → GET k → SELECT 7 → GET k → HGET k f → PING`，第一次GET断开后新连接恢复确认DB7；顶层错误精确保留`WRONGTYPE synthetic`，同socket后续PING成功；client关闭由peer EOF确认，两个socket和peer线程实际结束。巨大array声明仅13次read即typed拒绝；循环列表有界格式化、TEXT完整/HEX截断模式、超限cursor0候选保留旧next17、二进制raw/index/hex preflight均在实际linked类上断言。

旧MigrationRuntimeDriverProbe只复用已归档源码，`probe-provenance.json`保留origin哈希，当前重新编译/运行：Oracle/PG driver能发现，connectCalls=0，不等于真实连接通过。四个外置子命令每项保存argv/实际exe哈希/exit/stdout/stderr、processExited和30s控制超时分支；超时需kill+WaitForExit后才失败，本次没有触发。验收probe源码与class归档，运行后再核对输入。

### 12.3 最小原件选择、完整身份与停写

按root本轮冻结裁决，保留全部863输入的长度/哈希绑定，以及三轮实际classes的完整产物清单和UUID目录；源码原件只复制本G10的 **14产品+9测试**，实际launcher/init和probe已各自归档。class副本只选Redis、两pane、新/修改测试及小型buildSrc工具/测试类；主程序jar、exe/cfg保留实物。未再复制现有driver jar、整棵未改src/test或所有重复class。未复制的入库内容绑定本Git基线`66edd24f`和完整input binding，实际classes/完整image绑定具名UUID产物路径与全manifest；无删减运行/断言，无历史原件删除、重写或迁移。

[final-freeze/binding.json](evidence/g10-redis-20261009-p2-worker/final-freeze/binding.json)绑定当前branch/HEAD、所有新统计、完整输入/产物身份、P1a284及P1b212原件复核、旧G9分支身份。[raw-manifest.json](evidence/g10-redis-20261009-p2-worker/raw-manifest.json)对本阶段所有新原日志/XML、失败launcher、选择性源/class/主jar、完整哈希清单、报告快照给出长度/SHA-256；不含manifest自身，生成后逐项重核。diff --check仅既有行尾提示，无错误；无stage/commit。

最终process-settlement在CIM服务端按本轮UUID/8.3路径筛选，只取PID/父PID与对应owned范围，不读取无关用户Java命令行；返回本轮匹配进程0，另核对已记录probe PID的实际WaitForExit与socket/thread收尾、Gradle wrapper真实exit。覆盖具名子进程和owned范围，不据空查询声称所有本机后台进程已退出。早先在沙箱内CIM读取失败不当零进程证据，最终具名提权核对单独记录。未用future取消/空map/逻辑timeout替代物理结算。产品、测试、Gradle现在全部停写停测，唯一执行权交root。

P2工程通过不是G10最终交付；P3独立审核、main集成、精确SHA CI均未执行，开发未fetch/push/tag/PR/发布/安装，未创建其他会话/代理/自动化，`.testagent`始终禁读写枚举且Git显式排除。原生桌面端到端、真实Redis/DB/pg_dump、多会话最大合法RSS、DNS/阻塞write/native close/GC硬中断等既有未验/局限保留。维护成本整理仍等待G10交付后。
