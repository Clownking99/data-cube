# Windows CI 短路径修复：独立审核与 main 复验

## R0：当前目标与失败基线

目标是修复维护者粘贴的 Windows CI 失败，独立审核 GPT-6.1-sol 的实现和原始证据，合并 main 后重新验证，再读取同一推送提交的 CI。不是新功能或发布验收。沿用授权的隔离 worktree、合成数据和本地提交/合并/main 推送范围；既有 tag 不移动，datacube 跟进保持 PAUSED。

- main/origin/main 起点：`c3a41806bf17763f91578dfa65c913cdb14c2c15`。工作区在本轮自己的审核文件之外无改动；所有 Git 枚举排除 `.testagent`。
- 用户日志对应 [Verify 37307877176](https://github.com/Clownking99/data-cube/actions/runs/37307877176)，head `c4b68c549e923098fb095a1e3dd7bdc1ee548098`。main 起点与该提交的产品源码、测试、buildSrc、构建和 Verify workflow 无差异，见 [基线绑定](evidence/windows-ci-paths-20261005-coordination/baseline-binding.json)。
- 又只读核验 [main 的 Verify 37315921765](https://github.com/Clownking99/data-cube/actions/runs/37315921765)：Windows 3938 tests / 28 failed / 3 skipped，Windows linked image 未执行；Linux、Redis 专用 CI、wrapper validation 成功。两轮的 28 个失败名称一致，见 [本轮 CI 失败清单](evidence/windows-ci-paths-20261005-coordination/current-failure-summary.json)。不能将其他 job 的成功或跳过计作 Windows 通过。
- 28 项包括更新流程 22 项和 AppShell 文件/工作区夹具 6 项。完整远端 job log 仅保留在本任务独占临时目录，归档采用明确提取的测试清单及 job 元数据，不抄录无关 runner 环境。

验证命令的首版日志解析将 Gradle `> Task :test FAILED` 也算成一项，29 项校验失败后已限定为测试类行，得到 28 项。这个工具解析错误保留在基线摘要，未归为产品测试失败。

## R1：源码审核与开发修正

独立阅读 UpdatePaths / UpdateApplier / UpdateStartup、发货 PowerShell helper、SqlFileTabRegistry / SqlScriptFileStore 及两组 FX 夹具。旧 `toRealPath().equals(input)` 将 Windows 普通 8.3 别名和链接混淆；FX 夹具则把输入路径拼写当成 store 返回的规范身份。

本地 JDK 25.0.1 `lib/src.zip` 中 WindowsFileAttributes 明确将非符号链接的 REPARSE_POINT / DEVICE 映射为 `isOther()`。逐祖先 `readAttributes(..., NOFOLLOW_LINKS)` 并拒绝 symbolicLink/isOther 可以检查实际文件属性，不能先解析路径而藏掉链接祖先。只有 NoSuchFileException 可按尚未建立的 staging 子路径继续，其他 IO 错误必须传播。

开发者实际短路径红灯复现 7 项（原有 6 项 FX 失败及新增 image 短路径用例）。第一版修复后定向测试又暴露 10 项 helper NON_CANONICAL_PATH，已要求保留失败原件并继续修正 Java 交接路径，而不是放松 helper 所有权、重解析和规范输入检查。原始短名路径须先通过无链接检查，随后对存在的 target/workspace 规范化；startup acknowledgment 也必须使用同一规范身份。

夹具只规范化自建根目录；生产 SQL registry 仍在 FX 线程上消费文件 store 已确定的身份，不增加磁盘 IO。junction 回归必须证明目标和缺失子目录祖先都拒绝、归档提取无副作用。需要 Windows 真实 8.3 别名的测试不得在能力不足时悄悄跳过。

当前状态：实现与新回归由开发者修正中；root 尚未批准合并，也尚未运行本轮 main Gradle。开发者独占 Gradle，避免共享缓存/输出竞争。下一步审核最终差异及红绿原件、全量/buildSrc/image，再本地合并并产生 main 新证据。

## R2：分支工程审核

代码提交 `d5dd127b09342582b5c7448adf5b9aced018ebff` 审查无阻断项：3 个生产 Java 文件及 4 个测试文件；helper、SQL 生产 registry、buildSrc 与 workflow 未修改。`canonicalExisting` 按原输入祖先检查 → NOFOLLOW real path → 规范路径祖先复查执行；两种更新模式写 owner/plan 前统一 target/workspace 身份，startup 回执使用同一 target。新增实际短名、junction 及两种模式交接/精确回执测试，没有放宽安全断言。

root 独立读取开发者原始 XML/退出码，而非仅采纳口头结果：

| 运行 | 实际结果 | 说明 |
| --- | --- | --- |
| 003-red | 15 total / 7 failed / 0 skipped，exit 1 | 新短名 image 回归及原有 6 项 FX 失败 |
| 004-green（历史目录名） | 179 total / 10 failed / 0 skipped，exit 1 | 首版修复暴露 helper NON_CANONICAL_PATH；该目录不计通过 |
| 005-green | 180 passed / 0 failed / 0 skipped，exit 0 | 全更新包、两 FX 类及 SQL 文件契约 |
| 006-full | 3941 total / 3938 passed / 3 skipped / 0 failed | 所有 XML 生成时间在本次运行开始之后 |
| 007-buildsrc | 8 passed / 0 skipped / 0 failed | 强制执行日志与 XML 新鲜度均核实，其他运行所复制的陈旧 buildSrc XML 不计新验证 |
| 008-image | jpackageImage exit 0 | 独立构建环境，无测试 JAVA_TOOL_OPTIONS/headless；JEP 493 等警告仍保留 |
| 010-image-audit | 通过 | 三项实际产物 SHA、无测试类/夹具/配置参数泄漏、仅驱动发现；不访问数据库 |

3 个跳过为 Redis live、Oracle SchemaDiff live、PostgreSQL SchemaDiff live，未启用且不算通过。新增 Windows 条件测试在本机全部实际运行；Linux 不执行 Windows 专属用例，须分平台解读。

009 镜像审计误把原生 `api-ms-win-core-profile-l1-1-0.dll` 的名字判为 profile 泄漏；失败原件保留，010 只修正路径段匹配，产品未变。root 旧证据首版 PowerShell 审计也发生 schema/非终止错误漏检，输出已明确标 INVALID；严格 Node 校验字段、路径范围、字节数和哈希后，旧 formal 根 91 / worker 26 原件全部匹配，见 [纠正记录](evidence/windows-ci-paths-20261005-coordination/previous-formal-audit-correction.json) 和 [实际复核](evidence/windows-ci-paths-20261005-coordination/previous-formal-evidence-preserved.json)。不把审计工具误报或无效输出当成产品测试结果。

分支新 modules SHA 为 `0860780EE572AFA93BF959235646FF45022904C4C27115A2F9BC4CACDE135ADE`，exe/cfg 与旧镜像相同；这仍是新 runtime，旧原生证据不会变成新镜像验收。下一步冻结分支证据、合并 main，在新隔离目录重新执行定向/全量/buildSrc/image，并读取推送 SHA 的 CI。

## R3：main 集成与托管 CI

分支最终 `86d6612c53a971defb8318eb0c979a2dc750b548` 的 738 份 raw 原件由 root 逐字段/字节数/SHA 重新校验，全部一致；Git 产品树与受验源提交未变，分支工作区干净且已停止 Gradle。已 no-ff 合并到 main `14875d749ec07ce54bbbeb7b490674ab4784d72f`，无冲突，合并后产品/测试/build/workflow 树与审核分支完全一致。

main 首轮新目录定向 180/180（0 failed、0 skipped）已通过，实际 head 和命令见 [011 summary](evidence/windows-ci-paths-20261005-coordination/011-main-targeted/summary.json)。随后启动另一个合成 home 和真实短名 tmp 的 clean 全量复验。按维护者已有 main 推送授权及此前直连失败后的代理回退，经精确核对 origin/main 仍为 c3a4180 后，使用命令级 7897 代理将 main 快进推送；没有改全局代理或移动 tag。

修复提交的 [Verify 37319792169](https://github.com/Clownking99/data-cube/actions/runs/37319792169) 已启动；此检查点尚未将运行中 CI 计为通过。后续 main 全量/buildSrc/image 和同提交 CI 的完成记录追加如下。

main 本轮全部本地检查完成，均绑定 `14875d7`：

- [012 全量](evidence/windows-ci-paths-20261005-coordination/012-main-full/summary.json)：314 suites / 3941 total / **3938 passed / 3 live skipped / 0 failures/errors**。全部 XML 新生成，跳过仍是上述 Redis/Oracle/PG 真库项。
- [013 buildSrc](evidence/windows-ci-paths-20261005-coordination/013-main-buildsrc/summary.json)：独立强制执行 **8/8**。
- [014 jpackageImage](evidence/windows-ci-paths-20261005-coordination/014-main-image/exit.json)：exit 0，构建仅 Gradle 使用新 home，无测试 JVM 注入。警告原样保留。
- [015 镜像审计](evidence/windows-ci-paths-20261005-coordination/015-main-image-audit/audit.json)：新镜像与开发分支 exe/cfg/modules 三 SHA 一致；测试类、夹具文件、profile 和启动参数泄漏均未发现。现场 javac 后仅调用 driverFor 做 Oracle/PG 驱动发现，不调用 connect、不加载连接配置，不是原生启动或真库验收。

本地必要工程门槛已满足。CI 尚在运行的检查点保留，只有后续取得的真实完成结果才能关闭托管环境缺口。

## R4：故障关闭与最终记录

实际 [Verify 37319792169](https://github.com/Clownking99/data-cube/actions/runs/37319792169) 已 **success**，head 精确为 main 修复代码 `14875d749ec07ce54bbbeb7b490674ab4784d72f`：Windows 单元测试及 linked image、Linux、Redis 专用 CI、wrapper validation 全部成功，完整 job/step 原始回读见 [fixed-code-ci.json](evidence/windows-ci-paths-20261005-coordination/fixed-code-ci.json)。未重跑至偶然成功，也未修改 workflow/跳过失败用例。远端未上传详细 JUnit XML，因此 CI 不虚报各平台通过数量；上面的精确数量来自本轮本地原始 XML。

本次 Windows CI 故障已关闭。实现、本地 main 复验、新镜像和同代码提交托管 CI 形成完整证据；后续交接/审核证据提交仅修改 docs，不改变受验产品/测试/build/workflow 树。最终 main 同步及其 CI 回读以交付消息列出的 SHA/run 为准，避免为写入最后一次 CI 状态而无穷制造新文档提交。

维护者授权边界保持：仅 main 推送，既有 `acceptance/workspace-exit-20261005` 仍指向原 c4b68c5；没有发布、真实升级/安装、真库操作或新原生桌面验收。本地 live 跳过、签名/安装升级及完整 M8 发布验收继续待验；本轮结束，不自动扩大开发范围或恢复已 PAUSED 的跟进。

## 未验范围

本轮不访问真库、原配置、凭据、SQL 历史或业务文件，不安装/更新软件，不执行真实升级。既有正式启动器原生桌面证据绑定旧 image；新 image 构建后不能复用它宣称新镜像桌面通过。完整桌面、无外置 Gate 的默认运行、安装/升级/回滚、签名及发布验收仍需各自证据。
