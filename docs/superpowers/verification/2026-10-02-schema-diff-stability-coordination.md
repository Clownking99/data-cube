# SchemaDiff 稳定性：独立审查与 main 复验

客户端日期 2026-10-02。本轮按 [计划](../plans/2026-10-02-schema-diff-stability.md) 在独立分支执行；main 起点 055bda4a9d0f9d561cb9242971dbabb8e8f6c3e7。开发代理使用维护者指定的 GPT-6.1-sol，当前线程负责独立审查和本地集成。侧栏开发线程正式 id 仍不可取得，没有创建额外侧栏线程或伪报向它发消息；已实际通过代理工具下发限定开发与有界等待修正。

## R0：基线、原始失败与诊断审查

- 当前目标：定位前轮间歇失败的可证明原因；不能用后续通过补成历史根因。
- 改动：新计划、独立审查账本与外置复验/镜像审计脚本；产品未改。复用干净 worktree，在 codex/schema-diff-stability 分支开发，main 授权范围干净。
- 证据：完整读取旧 full-first SchemaDiffServiceTest XML、服务并行读取/取消/连接所有权、ConnectionManager 专属打开、DPAPI 每次独立 Arena/AES 每次新 Cipher、已有并发测试。新的夹具诊断直接执行 32 ×256 个唯一配置的 mock open/close，不访问真实库。
- 首轮红灯原 SchemaDiffServiceTest XML：4 tests /2 failures；两类定向合计 9 tests /2 failures。8192 次 physicalClose 对应仅 6177 条 opened，0 worker exception，确认旧 ArrayList 记录在并发下丢失。另一个失败是新增快照断言暴露 mock 把 schema.original 的 SQL 引用形式重复当作 catalog 标识规范化；这两个现象是本轮已证实的夹具缺陷，尚不是旧 Schema snapshot failed 的底层异常。
- 审查动作：要求 barrier/future 明确有界等待，保留完整计数、唯一配置、双侧快照/schema/host 和每连接实际 close 断言；拒绝自动重试或修改产品错误脱敏。读取两次未经核实的候选文件名失败，随后通过真实文件路径完成相关源码读取，没有测试或产品结果因此改变。
- 失败/未验：旧 XML 丢失底层 cause，历史那次根因继续未知。本轮无新原生/真库、安装/升级/签名/CI/发布验收。尚未审核最终开发交付或执行协调线程新工程复验。
- 下一步：审核最小夹具修复及真实红绿证据，精确提交最终快照；单 Gradle、新 profile 执行分支和 main 定向/全量/buildSrc/jpackageImage，归档原始字节并更新待验。

复验脚本在 [协调证据目录](evidence/schema-diff-stability-coordination/)；独占 UUID 临时根写入 baseline.json。脚本为每 phase/step 创建新 profile，拒绝覆盖已执行结果；剔除 live/JVM 注入环境，离线强制执行并复制全部 XML。源文件原字节按 phase 独立冻结，不将跨 worktree 换行差异当业务变化。镜像精确检查测试类型、外置探针/profile/选项泄漏并执行已审阅的零连接 driverFor 发现；不把驱动发现提升为真库或启动器验收。

## R1：开发交付独立审核

- 当前目标：审核已证明的最小夹具修复，再交付分支/main 新工程证据。
- 改动审查：只改变 SchemaDiffServiceTest 的同步记录容器、实际 close 计数、双侧 snapshot/schema/配置断言，并纠正 catalog mock 构造。并发回归直接使用该真实 mock 工厂，32×256 唯一 ID，barrier 5s/future 10s；无产品修改、重试、sleep 或超时扩展。
- 原始证据：独立读取红绿 XML、日志与参数；新定向 9/9、0 skips，8192 条记录与8192 次 close 完整。外置 TwoAddProbe 的 start/finish barrier 为每 pair 初始化新空容器，两个 worker 各 add 一次，固定200000 pairs。unsafe 实际 raw ArrayIndexOutOfBoundsException 指向 ArrayList.add，记录399940/400000、60 invalidPairs；safe 同参数400000/400000、0 invalidPairs/exception。既有服务会把 read runtime failure 摘要为 Schema snapshot failed，机制一致，但历史具体那一次仍未确定。
- 字节审查：开发18项 raw SHA/长度全部匹配。四份原始 log 在忽略列表且默认 Git 过滤会改写换行，协调线程仅在本轮开发证据目录加 * -text，精确强制暂存，保留原始字节和开发清单的旧过滤前后对照；不改变仓库通用属性。测试源码的 raw/过滤后 OID 不同仅是 Git LF/CRLF，提交前单独证明正常过滤后的内容一致；不改写开发清单。
- 归档首次检查失败：加入目录限定 -text 后，Git 将原始 JSON/exit 文件物理 CRLF 的 CR 识别为行尾空白，暂存检查拒绝。原输出/exit 在 staged-whitespace-first-failure.log/json 保留；仅给这两个本轮证据目录声明 whitespace=cr-at-eol，保留物理换行并继续检查真正的行尾空格，不改原始文件或产品。
- 失败/未验：红灯和 unsafe exit=1 均保留，未算通过；历史 cause 缺失和完整 M8/发布待验不撤除。读取外置诊断时一次未核实候选文件名失败，已用 rg --files 找到真实 TwoAddProbe.java，完成源码审阅，不影响诊断。
- 下一步：精确提交已审核开发快照，新 profile 在分支跑定向/全量/buildSrc/image，审核后本地合并 main 并复验；不再交给开发代理并发启动 Gradle。

## R2：分支最终快照新复验

- 当前目标：验证已审核开发提交，完成本地 main 集成条件。
- 改动：开发精确提交 75ed0c6be586cd6ff84e34ce64e2fba5692a8610；仅一个测试文件及开发账本/原始证据。协调验证/审计和本检查点准备一并提交。
- 新验证：提交后独占 UUID 临时根、分支每步骤新 profile，定向9/9，310 suites 全量3920总数（3917 passed /3 live skipped /0 fail/error），buildSrc8/8，新 jpackageImage通过，8/8/4/14任务均实际执行。183文件镜像无测试/已知外置探针/profile/选项泄漏；源文件774项原字节不变，源码工作区干净。新 driverFor 发现 Oracle/PG，connectCalls=0，未加载实际用户配置。原始依据在协调 branch/，不沿用前轮通过。
- 原始字节：开发20个实际暂存对象已审核；全部证据磁盘字节与 Git blob相同，仅已有测试源码的默认CRLF→LF提交规范化单独严格证明，保持 raw SHA 和 staged SHA 两套记录。开发18项原清单继续保持，不改写。
- 首次镜像审计失败：外置审计初版未限定 Reproducer 词，误命中java.base的四个sun/security/ssl TLS内部类型。首版脚本、首次JSON和tool exit元数据保留；所有源/文件/选项检查已通过。改为精确测试类型、已知探针简单类名和测试框架包前缀，最终审计和零连接发现通过。只修外置审计，不改产品或重跑已通过工程检查。
- 失败/未验：原始新红灯、unsafe exit=1、归档CR误报及镜像误报均保留；3 live skip不算通过。历史那一次具体cause未知；完整桌面/真库/安装升级/签名/CI/发布仍未验。
- 下一步：审计并提交协调记录和原始分支证据，本地合并精确提交到main；新main profile定向/全量/buildSrc/image及产物对照，更新交接与待验后交付。
