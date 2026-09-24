# G6 / M6 实施检查点

维护者明确授权：字段与注释检索、SQL 收藏及入口整理，完成本地验证、提交并合并 main，沿用原安全边界；不进入 M7。

## CP0：基线与小设计

- 当前目标：M6a/b/c。本地 main 为 `c3481d1118a51b3855b486628e2356ac2e542126`，工作区无任务改动（始终排除 `.testagent/`）；新分支 `codex/datacube-g6-discovery-library`，独立 worktree `C:\Users\hetia\.codex\worktrees\datacube-g6-discovery-library\朝花夕拾`。
- 现有能力：SchemaObjectSearchDialog/SchemaObjectCatalog 已提供限定 Schema 的对象名筛选和未执行 SELECT；历史、草稿、文件及安全会话均已有实现，不重复建设。
- M6a：从现有 Schema 查找进入字段/对象注释检索，固定完整连接快照与 Schema。按明确模式和搜索词发起参数化 catalog 读取，限定结果、超时、取消及连接物理完成；显示匹配来源与截断/权限边界。查看数据、DDL、生成 SQL 各有明确按钮，检索与文本输入不触发业务 SQL。
- M6b：收藏独立于历史/草稿/源文件；版本化、严格 UTF-8、有界记录，只存 UUID、名称、分组、SQL、修改时间，不保存连接配置或结果。复用已有原子文件边界，保存前校验旧值，保留上一有效版本；损坏项保护并支持显式从备份复制恢复，不静默覆盖或自动绑定连接。
- M6c：依据本轮合成窗口截图整理入口；保留执行/取消/事务/保存，低频文本操作放入菜单且维持已有快捷键及状态守卫。主题与品牌不换皮。
- 验证：已启动本轮隔离基线 `clean test`，不使用 G5 旧通过作新证据。后续定向验证身份/权限/取消/迟到结果、收藏原子失败/损坏/容量/离线打开、布局/焦点/快捷键。
- 失败/未验：尚未执行 G6 新行为验证；原生桌面可用性待探测，真库/签名安装/远端 CI/发布未授权。既有 SchemaDiff 偶发问题原因未明，不宣称修复。
- 下一步：实现 M6a，再 M6b、M6c；完成定向、全量、fresh buildSrc、jpackageImage、审查后提交合并，并在 main 新 profile 复验。

工具链：Temurin 25.0.1+8 / Gradle wrapper 9.2.0，离线构建。独占证据目录 `C:\Users\hetia\AppData\Local\Temp\datacube-g6-1ec13ec5fd364937afd7b481d1372e3e`；Test 子进程隔离 user.home、移除 live 集成环境变量；镜像不携带测试 profile/headless 参数。测试只用 mock JDBC、合成配置和临时文件；不访问真实连接、凭据、SQL 历史或业务文件。

## CP1：M6a / M6b 实现及入口收敛

- 当前目标：完成 M6a/b/c 的本地工程闭环，尚未提交或合并。
- 改动：限定 Schema 的参数化字段/对象注释/字段注释查找（200 条、10 秒、专用读取连接、取消与迟到守卫）；生产树入口明确分为未执行 SELECT、只读数据及 DDL。收藏采用独立版本化 UTF-8 文件、原子发布与上一版本备份、100 项/每项 256 KiB/总额 16 MiB、旧值校验、损坏保护及显式恢复副本；打开复用离线文件标签与草稿生命周期。全局按连接/SQL 文件/SQL 资料/工具组织；6 个低频文本动作进入菜单，原快捷键及动作守卫保留。
- 验证：本轮 baseline-full 为 3724 passed / 3 live skipped；m6-storage-and-search 134/134；m6-library-ui-2 131/131。只算本轮实际执行 XML，不把编译成功当作测试通过。
- 失败：m6a-ui 109 项中 1 项失败（默认取消 result 转换）已修复；m6-favorites-compile 编译失败（构造参数遮蔽字段）已修复；m6-library-ui-1 131 项中 59 项失败，58 项为菜单迁移后的旧节点断言/未生效编辑脚本，1 项为新增测试标题星号期望错误。已改用实际菜单入口，保留所有离线、撤销、冻结/关闭、焦点、改绑断言；随后 131/131 通过。所有失败日志仍保留。
- 桌面：使用 Computer Use 技能及独立 G6 合成夹具。首次截图与目标窗口不一致，未作为证据，误采文件已删除；重新定位并激活唯一合成窗口后恢复正常。仅保留核验后的合成截图。整理前窄窗工具栏 4 行；原生后验及 100/150% 仍待执行。
- 审查补充：超大脚本收藏明确“未带入”而非静默截断；字段检索在读取结束时再查取消；结果预览显示表/视图；树动作再验配置与目标。
- 下一步：上述补充定向验证、整理后合成桌面、全量/buildSrc/jpackageImage、差异审查；然后本地提交合并与 main 新 profile 复验。
- 未验：真实 Oracle/PostgreSQL catalog 可见性、真实驱动 connect/cancel/close、真库、签名安装/升级与发布。外部操作不在授权范围；未跳过为通过。

## CP2：审查、桌面证据与首次使用修正

- 当前目标：收口 M6，准备本地集成；未进入 M7。
- 审查：核对检索 SQL 只访问 catalog 且全部参数绑定；资源/取消/配置漂移路径；收藏旧值比较、备份发布顺序及损坏保护；离线标签经相同文件/草稿/关闭路径装配；菜单只转发原动作，快捷键不新注册为全局动作。没有格式化扩展、新依赖、堆预算提高或远端操作。
- 本地验证：branch-full 实际 3753 tests / 3750 passed / 3 live skipped；branch-buildSrc 使用 --rerun-tasks 实际 8/8；branch-image 成功。新增焦点行为测试后 m6-focus 13/13，覆盖 Ctrl+F 到各自搜索框及 Tab 到列表/模式，不能替代原生输入证据。
- 原生证据：实际 JavaFX outputScale 日志 1.0 和 1.5；宽/窄、明/暗，14 张核验后的合成截图入库 evidence/g6。100% 窄窗编辑工具栏从 4 行减为 3 行；150% 也为 3 行，执行/保存/事务均保留直接入口。150% 截图确认 Tab 从 Schema 到保存按钮。UIA focused_element 有滞后，以截图为准；模态窗口没有独立可定位的 Window，点击被工具拒绝，原生模态键盘和完整用户壳流程仍待人工验证。没有把工具失败记为通过。
- 新发现/失败：首次 profile 尚无 .datacube 时，收藏 local repository 的 parent 不存在；m6-fresh-profile-red 1/1 失败证明该缺口。现已在后台 repository 打开时创建自己的父目录，仍由原文件边界做独占和原子校验。随后 targeted green 与 branch-full-final 正在执行；此前 image 不能代替最终 image。
- 下一步：最终全量/镜像通过后提交；main 前置核对仍为 c3481d1 且干净（排除受保护目录），merge 后新 profile 全量、fresh buildSrc、镜像及源码/产物摘要复验，最终更新本账本与路线图。
- 未验：模态窗口原生输入、完整生产壳原生流程、真实数据库/驱动、OS 级缩放切换、多显示器、签名/安装升级、远端 CI、发布。不会宣称发布验收完成。

## CP3：最终分支测试通过，待本地集成

- 当前目标：合入 main 并在新 profile 复验，范围仍为 G6/M6。
- 改动：首次 profile 初始化修复完成；此后没有产品代码变化。
- 验证：m6-fresh-profile-green 9/9；branch-full-final 3753 passed、0 failed/errors、3 live skipped；branch-image-final 成功，cfg 无测试参数；buildSrc 本轮强制执行 8/8。
- 失败/未验：此前失败均有原始日志与对应复验；真实数据库跳过及原生模态输入限制保持待验，不标为通过。
- 下一步：最终镜像/参数检查、提交、main 前置检查及合并；main fresh 全量/buildSrc/image，记录精确提交及产物摘要。

## CP4：已合并 main，独立复验进行中

- 当前目标：完成 G6 的 main 复验和交付记录；不进入 M7。
- 改动：实现提交 `d4b02debdf59e7af1c81a0c54a7ed0f3c2db9c1d`，main 合并 `c182128a64c970f05469129bdc1adfbd2bd1a452`。合并前 main 仍为 `c3481d1` 且授权范围工作区干净。
- 验证：分支与合并后 Git 产品内容一致；752 项清单中 516 项字节相同、236 项仅 CRLF/LF 转换，无其他差异。`profile-main` 运行前确认不存在，随后用于 main fresh clean test。
- 失败/未验：main 测试及构建结果待产生；此前失败和外部待验保留，不借用分支结果宣称 main 已通过。
- 下一步：main 全量、强制 buildSrc、jpackageImage；更新路线图、交接及实际结果后提交文档并交付。

## CP5：main 复验通过，本地工程交付

- 当前目标：G6/M6 本地实现、审查、提交、合并及复验完成；交付截至本阶段。
- 改动：复验后仅同步路线图、交接、验证账本和实际结果；产品与测试保持 `c182128a64c970f05469129bdc1adfbd2bd1a452` 内容。
- 验证：main fresh 全量 297 suites / 3756 tests / 3753 passed / 0 failures/errors / 3 live skipped（3m35s）；main buildSrc 强制执行 8/8（8s）；main jpackageImage 成功（33s）。镜像没有测试启动参数或桌面夹具，exe/cfg/runtime modules 摘要与最终分支镜像一致。精确命令、日志/XML/产物摘要见验证账本和结果 JSON。
- 失败/未验：main 复验无失败；先前编译/测试失败保留，既有 SchemaDiff 偶发原因未明，3 个真库 skip 不算通过。原生模态输入与完整壳流程、OS 缩放/多屏、真库/驱动、安装运行/签名升级、远端 CI/发布仍未验。
- 下一步：提交最终文档并检查 main 工作区及产品内容；交付 G6。M7/M8 需另行明确启动，当前不扩展。
