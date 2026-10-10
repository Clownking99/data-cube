# Redis 二进制键导致整页加载失败：修正计划

维护者于2026-10-10提供实际界面截图：打开Redis数据库后报“键页包含无法按 UTF-8 往返的键；未完整加载，保留原结果”。当前main为a39ffc478795801195f9ee41087f2e0af02a149f，范围内干净，v3.2.10指向此提交。截图只作为问题证据，不读取或访问截图中的连接、业务数据或配置。

## 问题与设计

root源码核对：RedisSession.scan保留byte[]，RedisKeySnapshot.candidate却强制将每个键解码为UTF-8 String；一个不合法UTF-8键会使整页候选失败。随后tree、pane Binding和Session键参数均使用String，不能仅去掉异常或用替换字符解码，否则后续命令可能指向错误键。

修正将原始字节身份和显示分开：键身份不可变，按字节内容相等/哈希，按原字节预算；可打印文本保留前缀分组，二进制/控制字符用有界明确表示，空键可辨认。显示前缀或截断碰撞不能合并不同键或改变操作目标。所有读取、五类型编辑、TTL、重命名和删除均绑定原始键及源会话，复制功能明确文本或十六进制语义。

保持SCAN整页候选、按原字节去重和累计预算；预算/树深/节点超限仍拒绝整个候选并保留旧树和cursor。DB切换、刷新优先级、旧回调和旧编辑拒绝、关闭结算保持。范围仅本缺陷，不做控制台/架构/工具栏的额外改造，不跳过二进制键来伪称扫描完整。

## 执行与验收

1. 沿用既有6.1-sol开发会话及aed5工作区，从main创建codex/redis-binary-key-20261010。开发先交源码及类型/显示/冲突方案；root独立审查，不新建线程或代理。
2. mock回归覆盖混合页、非法UTF-8/控制字符/空键、内容去重与显示碰撞、防御复制、分页预算；原生FX验证选择二进制键后命令精确字节及旧Binding失效。保留原先文本、五类型、预算和生命周期回归；不能只删除旧拒绝断言。
3. 验证工具沿用已验收共享内核。因当前入口限制G11目录，须审查本次有名修复范围的最小准入变更，禁止冒充旧G11轮次或更改旧封存证据。工具范围变化需覆盖允许本次路径、拒绝越界/旧危险路径及原有失败语义，不为产品修复扩展通用平台。
4. 开发独占Gradle完成本轮新环境定向、clean全量、强制buildSrc、jpackageImage及必要linked验证，root独立读源码、实际退出、XML和原件哈希后再集成main和复验。失败留原件，跳过不算通过，旧通过不作为新证据。
5. 本轮只提交、集成并验证已授权缺陷修正；旧tag保持不动，新的tag/发布不沿用上次一次性打tag请求。待完成后交付实际证据及未验项。

## 边界

`.testagent`禁读禁改禁枚举，Git状态/检索/暂存显式排除。旧.g10-verify-blobs.ps1不读不动不暂存；不读取原有连接、配置、凭据、SQL历史或业务文件。只用mock、合成profile、独占UUID临时目录和受控本机helper；不访问真实Redis/数据库/pg_dump，不fetch/tag/PR/发布/安装更新/外部联系。root/开发不得同时运行Gradle。截图环境复测、真实数据和完整发布验收仍单列未验。

## C0：基线与任务下发

目标：消除二进制键导致整页加载失败，同时保证命令身份与预算正确。已核对main a39ffc47与干净工作区；已定位上述源码链。当前仅静态发现，未执行新测试、未修改产品。原6.1-sol会话处于idle后已收到任务，第一阶段仅编辑/静态检查，不运行Gradle或提交。下一步独立审核方案与实现，再移交唯一Gradle执行权。截图真实连接未访问。

### C0.1：并行静态审查与纠正

开发分支已从a39ffc47创建。root已独立阅读RedisKey、Snapshot/树、Session重载、RESP预检及pane Binding的初稿：原字节不可变身份贯穿命令；文本路径与带标记的binary/空键显示分离。发现String树兼容入口先全批转换的预算回归，已下发并核对惰性逐项转换修正；String Session门面保留原整条命令预检后编码，新增RedisKey参数按原字节长度预检后复制。

新回归已形成原字节防御复制、混合页/显示冲突/预算及27条键命令断言。root要求FX五类型用例明确断言payload，并分别在刷新、DB切换、关闭前捕获有效action，再验证失效；延迟二进制值不能覆盖另一原始键选择另列。剪贴板只验纯投影，不读取/覆盖系统剪贴板。上述仍是源码审查，未执行新测试。

root在新的redis-binary-p3-review-20261010中准备独立审核器，仅替换本次阶段前缀并将旧408类型常数改为当前完整类型映射检查；旧冻结审核脚本不动。这不是通过证据。开发仍未获Gradle/进程控制执行权，下一步审查最终范围准入diff及负例方案后移交验证。

### C0.2：静态准入与控制执行移交

root独立重算开发C5的14文件长度/SHA，清单SHA为95a3cf729639b165aac4eb98d0ea871a52233462988d647afbffe92b7a87f073。较初稿仅Core/run-stage变化：错误scope文件名及外来EvidenceRoot现于目标metadata读取前拒绝。产品与测试字节未变；[独立准入回执](../verification/evidence/redis-binary-p3-review-20261010/source-control-admission.json)仅准入执行，不代表运行通过。

已将有名范围控制及完整98共享控制的唯一执行权交开发线程，Gradle仍待控制原件审查。root补充发现check-p2的哈希拒绝夹具把矩阵根当owned根，新规则下会提前失败，已下发以独占UUID子目录修正夹具，保留EXECUTABLE_IDENTITY_CHANGED断言和31项范围，不放宽产品/准入门禁。该第五个共享源码改动需独立复核，12工具闭包不变。下一步核对实际控制脚本、命令/退出、原始日志和哈希，再准入工程验证；当前尚无Java/FX运行证据。

### C1：控制原件接受，开始工程验证

首次边界控制因PowerShell负例数组表达式结合顺序，错误包含了合法UUID，实际Python退出0、PowerShell退出1，98项未开始。原件完整保留；只修测试表达式并在新UUID环境重跑，未改产品或门禁。随后70边界控制、98共享控制、成功normal之后实际同根RUN_COLLISION拒绝均通过。

root独立审计成功封存fad4963106f5ae58174a056ae697377ff3a26d103a7da01c22fe5a4edb1e6772：91根、1384文件、17025013字节。逐案核对实际spec/argv/退出/邻居/Job/EOF，重算232日志与18份强退出证明；23入口、12工具和878当前工程输入相符。[控制审查](../verification/evidence/redis-binary-p2-root-review-20261010/controls-review.json)与[边界审查](../verification/evidence/redis-binary-p2-root-review-20261010/boundary-review.json)均接受。边界计数是动态命令调用观测加源码顺序检查，不是全系统I/O跟踪；两个错误工具位置负例仅模拟__file__。

已授权开发独占新环境targeted、clean全量、强制buildSrc、jpackageImage及linked；root不并发Gradle，同一工具无需重复98。当前Java/FX和工程结论仍未验，下一步按实际新XML、过程原件与镜像逐项审查；不凑历史测试数，不操作tag或真实连接。

### C2：首轮工程失败与确定性夹具修正

首轮targeted实际303项，1失败、1 live跳过；失败为既有AppShellTableExportShutdownTest的`[1] missing`，在Fixture.seed直接保存工作区时与自动保存竞争而收到BUSY。root独立读取堆栈与当前XML，两组binary suite的6+2项、RedisPaneBudgetTest的14项全部实际通过，但整体明确不接受，详见[首失败审查](../verification/evidence/redis-binary-p2-root-review-20261010/first-engineering-failure.json)。full及后续未启动。

开发在纠正指令前启动同字节诊断重跑；root要求不得以跑绿代替修复。该轮targeted实际302通过/1 live跳过后，在入口身份检查边界停止，未进入full；原件保留，只作为诊断。下一步只修必要测试夹具：通过既有工作区协调器等待实际在途及seed保存Future，并恢复活动状态，用latch阻塞真实原子发布证明等待顺序；不吞BUSY、不加sleep/超时、不改产品门禁。新测试输入与控制阶段旧878输入需精确记录差异，12工具不变，修正交审后再从新targeted开始完整工程。

### C3：开发工程接受，转入 main 集成

确定性seed夹具及真实在途发布回归已独立审查准入；878输入只有该测试文件改变，其余877和12工具不变。最终新环境五阶段全部实际退出0：定向303通过/1 live跳过，clean全量4733通过/3 live跳过，强制buildSrc 8通过/0跳过，jpackageImage与linked通过。root核对当前源码与XML精确方法集6+2+14+5，包含14项Redis原生FX和5项工作区关闭测试；所有原生测试实际执行，无原生跳过。

root逐项核对878输入、410测试类型、12工具、全部实际根退出及日志。183文件镜像包含RedisKey.class，26090类中无测试类泄漏，四条外置linked命令通过；合成探针connectCalls=0、realServices=0。完整工程封存ba41a8c537203f70db80150a17e77cd76da637cbcd6a5eecf4514694204b6bdd的11根、1188文件、21496307字节均独立重算一致：[工程审核](../verification/evidence/redis-binary-p2-root-review-20261010/engineering-review.json)、[用例审核](../verification/evidence/redis-binary-p2-root-review-20261010/case-review.json)、[原件封存审核](../verification/evidence/redis-binary-p2-root-review-20261010/engineering-freeze-review.json)。

已授权开发按16源码与证据分别本地提交，采用精确路径清单运输；唯一Gradle执行权交回root。下一步核对Git运输字节、合main、新UUID隔离环境完整五阶段复验。共享98只绑定已独立接受的同12工具证据，不假称在main重跑。截图真实环境、真实Redis和完整发布验收仍未验，旧tag不变。

### C4：运输、集成与 main 新环境复验

源码提交c33b9ef45c680b86976919b68b81d4d61b9230c7，证据提交207782399ec41fb61f39cf30eb4416d8f28a6746。root按精确清单核对16源码与3077证据/文档的worker和Git blob原字节；main合并af3fe27369973c2d0254e712968f5984b8dc2b86后，3077证据字节保持不变，11个Java源码仅checkout换行转换。[运输审核](../verification/evidence/redis-binary-p3-review-20261010/transport-main.json)接受。审核器首次抄写source SHA漏了三位，Git立即拒绝；纠正为实际提交后审核，未改变产品或受验证据。

main准备入口首次拒绝非Java文件换行差异，未启动任何工程命令。随后独立比较全部878输入：256文件只有CRLF/LF差异，且逐个与当前HEAD规范化Git blob一致（含8个既有非Java文本文件）。新入口只准入该256路径及双方精确SHA，不放宽任意内容变化；12工具仍原字节一致。[换行诊断](../verification/evidence/redis-binary-p3-review-20261010/input-newline-diagnostic.json)和[入口纠正记录](../verification/evidence/redis-binary-p3-review-20261010/reviewer-corrections.json)保留。

新main包redis-binary-p3-703d50245ffb4b799a3616f70ce0ba39-package绑定实际main字节，18入口文件manifest为68bc476741f98a851b419971a65fc1b7f5c4f54b57962bf081afbba2bde1c898。定向已实际303通过/1 live跳过，root复核878输入、410类型、12工具、XML和实际退出；其余阶段继续执行。尚未宣称main整体或远端交付完成。下一步接受五阶段原件，封存本轮，文档提交后仅推main并检查精确SHA CI；[交付入口](../verification/evidence/redis-binary-p3-703d50245ffb4b799a3616f70ce0ba39-package/delivery-intent.json)仅规定验收条件，不是成功回执。

### C5：main 本地验收完成，进入精确 SHA 交付

五阶段实际完成且controller退出0、入口身份未变。root新main审查接受定向303通过/1 live跳过、clean全量4733通过/3 live跳过、buildSrc 8通过/0跳过、183文件镜像及四linked命令。当前精确方法集6+2+14+5在定向和全量均通过；878输入、410类型、12工具、实际根退出/host/EOF/Job全部核对。镜像包含RedisKey.class，没有测试类泄漏；合成驱动/Redis探针不连接真实服务。

13根1200原件、21986312字节封存SHA e3f7ec2cfd9bf7fe2de0df67454ffdcac28be8046d8c66e798961c7944c4438a，冻结后不再写入。[main报告](../verification/2026-10-10-redis-binary-key-main.md)汇总本轮通过与未验。下一步文档/证据提交沿用相同受验工程输入，仅推main并核对相同SHA CI；实际远端结论由交付入口指向的原始回执确认。真库、截图安装版本及完整桌面/发布验收继续单列未验，不移动旧tag、不自动发版或扩范围。

### C6：首轮 CI 拒绝及 checkout 最小补正

3af30f12ea225c872b1d65b831ab0a136ef05e18已直接推送（无需代理），但Verify 38034885345的Windows job114163146217在checkout报Filename too long，后续Java/tests均未启动。其余三任务成功，整体明确不接受；完整API/日志及失败裁决保存于redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci。旧v3.2.10注解对象da691fb5和peeled a39ffc47未变化。

仍由既有6.1-sol线程在独立codex/redis-binary-ci-longpaths-20261010分支修正：仅给Verify、Release两处Windows-capable checkout各加4行步骤级环境，Git配置仅对子进程有效，Linux矩阵COUNT=0。任务、过滤器、权限、发布触发条件及其他所有行不变；不触发发布。合成独占Git仓库实际387字符路径在false时退出128、true时退出0，同一commit/blob的68原字节一致；9个实际命令及Job空结算、41文件269574字节均经root独立复核，封存9b999249226d964e8c3bec8f5130d1cb08d911a5d68e3138034052b51dc5621b。

root已授权精确44路径本地提交。后续记录两个workflow与本地P3旧输入的精确哈希迁移，其余876工程输入和12工具不变；不把本地旧输入测试说成新workflow已执行。下一步合main并推送新的精确SHA Verify，实际结论见[新交付入口](../verification/evidence/redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci/delivery-intent.json)。原本地五阶段仍有效，真实连接/完整桌面/发布继续未验，不重写旧冻结目录。

### C7：CI 补正集成完成

补正提交c5f129497c3f2ab4c303eaf4c71352219aa4d1e5的44路径经root独立Git blob核验，42份证据在worker/Git/main保持原字节。已合入main e24c356c45d92e88fb2ace27c95d91ff55aaaed1。对照P3实际输入，只两个workflow变化，其他876输入和12工具原字节一致；[输入迁移](../verification/evidence/redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci/input-delta.json)与[运输回执](../verification/evidence/redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci/transport-main.json)记录。下一步将补正审查、首失败与当前交接提交后，仅推main，等待新精确SHA全部四个CI任务实际成功；旧失败轮次不重标通过，Release仍不触发。
