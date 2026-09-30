# 字段检索关闭后的资源准入：检查点

沿用维护者“继续推进产品”的限定本地授权：独立 codex/ worktree，mock、合成 profile、独占临时目录；不读改 .testagent，不访问真实连接/凭据/历史/业务文件，不 push/fetch/PR/tag/发布/更新安装或外部联系。当前没有活跃 goal，不设置预算或自行改变目标状态。

## C0 — 基线与缺口

- 当前目标：窗口关闭后，在旧读取与 JDBC 取消均实际结束前，不能重开检索取得新的读取资源；等待原因可见，结束后只允许用户明确重试。
- 基线：main 97b04f82891f4e5e95dfc95c7c598d76ccccf7a9，授权范围干净；复用干净 worktree，新建 codex/metadata-search-disposal。新独占 scratch datacube-metadata-search-disposal-b2c647a2773f4185abff99681e335f85。
- 改动：先新增关闭/重开缺失行为回归；复用现有检索和真实 AppShell mock，不造服务、改事务或 SQL 执行，不做新功能扩张。
- 验证：源码显示 showAndWait 的 finally 立即清空 metadataSearch，Dialog.close 只发取消且读取/取消可能尚在途；FxTaskRunner 为每任务虚拟线程，没有该读取的跨窗口准入。当前是代码发现，下一步以实际 JDBC 打开数证明。
- 失败/未验：旧 3876 passed 不是本轮新证据；原生完整模态请求/结果链与真库/发布仍待验。现有模态工具输入失败不反复重试，也不以程序化 FX 升级为原生。
- 下一步：运行关闭后重开的红灯；保持窗口及时关闭和后台取消，资源全部释放前禁用两个入口并明确等待，恢复不自动读取；覆盖两任务相反完成顺序、取消异常及无任务关闭，再定向/全量/buildSrc/镜像、审查、本地提交/合并/main 复验和归档交付。

## C1 — 复现与修复

- 当前目标：关闭窗口与释放读取资源分开，直到读任务及取消调用均返回才释放连接树检索入口。
- 改动：Dialog 增加只读完成通知（FX 完成），树保留旧检索引用直到通知完成；两个 Schema 检索菜单绑定占用状态，字段入口显示“等待读取结束”。读取 finally 也发结束回调（Error 仍传播），取消任务被拒绝也结算状态；未改服务、写门禁、事务、结果路由或线程池。
- 验证：初次 disposal-red 是测试编译失败（MenuItem API 错误），没有运行测试；修正后 disposal-red-runtime 实际 4/4 失败，PG/Oracle × 读/取消先结束均出现连接峰值 2。修复后同四项 disposal-green-routing 4/4 通过，峰值 1、双任务结束前禁止重开、结束后不自动读取、明确重试成功。disposal-lifecycle 新 94/94 通过（5 类实际 FX/服务测试，无跳过），覆盖关闭、取消异常、拒绝取消、空闲关闭、配置 ABA/脱离/路由等原行为。
- 失败/未验：编译失败与运行时红灯均保留原日志/XML，不改为成功；没有新原生桌面或真库证据。新增 fatal Error 清理回归和最终源快照正在定向验证，之前 94 项不冒充最终源码证据。
- 下一步：最终定向通过后，在新合成 profile 执行全量、强制 buildSrc 和 jpackageImage/零连接审计，逐项检查释放时序与线程访问，然后本地提交、合并 main 并重新执行检查。

## C2 — 线程时序审查与最终源码验证

- 当前目标：不仅等待读取和取消，还须在关闭清理完成后才允许释放占用。
- 改动：本地审查发现后台 close 的 closed 标志可能先于取消发布与 FX 隐藏；完成条件增加 FX closeCleaned。新增后台空闲关闭回归：FX 队列被阻塞时 close 仍返回，但完成通知保持未完成；FX 隐藏后才完成且无读取。
- 验证：初版 branch-directed-final 95/95、branch-full 309 suites / 3890 tests（3887 passed、3 live skipped）通过后才进行此次变更。保留 initial-source-snapshot；最终 source-snapshot 另存。审查后 branch-directed-reviewed 96/96 通过，0 skipped，四项关闭/重开峰值仍为 1。两次定向日志恰好同 SHA（Gradle 文本相同）；测试数和 XML manifest 不同，均是真实独立 test 任务。
- 失败/未验：无新增运行失败；初版全量不能替代新增 closeCleaned 的最终全量。原生关闭后等待提示/重开、完整检索下游、真库和外部发布仍待验。
- 下一步：最终 branch-full-reviewed 正在新 profile 运行；之后强制 buildSrc、打包/审计、原始证据 hash 校核与限定本地集成。无自动继续其他目标或外部操作。

## C3 — 分支验证与集成前审查

- 当前目标：固定最终源码、记录新验证，并准备本地提交/合并。
- 改动：产品/测试自 C2 后未变；README 明确等待/恢复规则，新增本轮账本和 raw archive/校核脚本。
- 验证：branch-directed-reviewed 96/96；branch-full-reviewed 309 suites / 3891 tests：3888 passed、3 live skipped、0 failure/error；branch-buildsrc 强制重跑 8/8；branch-image 实际 jpackageImage 成功；branch-image-audit 类/配置泄漏 0、PG/Oracle 驱动发现且 connectCalls=0。最终源码四项 raw SHA 与 canonical Git blob 固定于 source-snapshot.json。本地审查见 branch-review：占用/双任务/后台关闭/FX 线程、拒绝/Error、原配置/只读/路由均检查。
- 失败/未验：仅 C1 已保留的编译错误与实际红灯；3 live 环境及显式写门禁未配置，已剔除，不算通过。没有新原生或真库证据；历史 SchemaDiffServiceTest 偶发原因未证实关闭。
- 下一步：hash/暂存审计后限定提交；main 仍为基线且授权范围干净，再合并、逐项新 profile 复验和更新实际 SHA。只记录本轮本地工程交付，不宣称 M8 或发布验收。

集成前补记：首轮 staged 校核发现 *.log 被仓库 .gitignore 排除，未提交并记为审计失败；限定 -f 暂存本轮 evidence/metadata-search-disposal 后，75 份原始文件、14 份记录和四个源/暂存 blob 全部一致。随后将此次失败/修正记录与审计结果一并归档，重新校核，未放宽 hash 验收。

## C4 — 本地合并与 main 复验

- 当前目标：以实际 main 代码取得新证据，不能沿用分支通过作为 main 验收。
- 改动：实现提交 219d3c2d66c7fd29708f6acd2a181ee7bb7389ef，本地 main --no-ff 合并代码 2f27e2ba1d73cd8c6a2a41b26040cf05fad2cd6e；合并前 main 仍为基线、授权范围干净，未 push/fetch/PR/tag。合并后的本检查点为文档更新，不改产品/测试。
- 验证：最终分支暂存校核 76 份文件 / 15 份记录通过；main 合并内容与分支整体一致，postmerge-review 源文件 canonical blob 和 raw archive hash 均通过（main 原始源文件换行可能不同，另记 raw SHA）。main 定向正在新 profile 实际执行。
- 失败/未验：main 全量、强制 buildSrc、jpackageImage/镜像对比尚未执行，不填写旧通过；原生/真库/外部发布仍待验。
- 下一步：定向完成后 main cleanTest/test、强制 buildSrc、镜像/零连接审计与三项 SHA 对比；归档新记录并更新交接/路线图；限定文档证据提交及干净检查后交付。

## C5 — main 新证据与本轮交付

- 当前目标：完成本轮关闭后资源准入修复的限定本地交付。
- 改动：实现与 main 合并同 C4；产品/测试自分支最终快照未变。更新实际结果、原始 archive、账本、交接与路线图，后续仅文档证据提交。
- 验证：main-directed 96/96；main-full 309 suites / 3891 tests：3888 passed、3 live skipped、0 failure/error；main-buildsrc 强制重跑 8/8；main-image 实际 jpackageImage 成功；main-image-audit 类/配置泄漏 0、connectCalls=0。DataCube.exe、DataCube.cfg、runtime/lib/modules 三项 SHA 与分支逐一一致；main canonical 源/测试与最终快照一致。
- 失败/未验：保留测试编译错误、四项实际红灯及 ignore 导致的暂存审计失败。3 live skipped 不算通过；没有本轮新原生关闭/等待/重开或完整下游证据。真库、OS 缩放/多屏/全键盘、正式启动器/安装升级/签名/远端 CI/用户任务/发布仍待验，M8 不称完成。
- 下一步：归档与限定文档暂存复核、文档提交及授权范围干净检查后交付。此前通过不再重复运行，除非产品再变或出现新失败；本轮结束，不自动扩展目标或外部操作。
