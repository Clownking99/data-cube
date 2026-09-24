# G6 / M6 本地验证账本

当前状态：已获明确授权，G6/M6 本地工程完成，已本地提交并合并 main，main 独立复验通过。不进入 M7。以下不是发布验收。

## 范围与基线

- main 起点：`c3481d1118a51b3855b486628e2356ac2e542126`；分支 `codex/datacube-g6-discovery-library`。
- worktree：`C:\Users\hetia\.codex\worktrees\datacube-g6-discovery-library\朝花夕拾`。
- 新证据目录：`C:\Users\hetia\AppData\Local\Temp\datacube-g6-1ec13ec5fd364937afd7b481d1372e3e`，保存逐次日志、XML、JSON 及核验后的合成截图。
- JDK 25.0.1+8 / Gradle 9.2.0，离线。每次 Test 子进程设置独立临时 user.home、`java.awt.headless=false` 并移除 live 集成环境变量；镜像构建不使用测试 init/profile 参数。
- mock JDBC、合成连接、临时文件；不使用真实配置/连接、凭据、SQL 历史或业务文件。未读取或修改 `.testagent/`，无 push/tag/PR/发布/安装更新/外部联系。

## 实现与边界

| 能力 | 实际行为 | 明确边界 |
| --- | --- | --- |
| 字段与注释查找 | 现有 Schema 对象名查找内新增入口；字段名、对象注释、字段注释分模式；参数化固定 Schema 与搜索词，200 条结果、512 字符预览、10 秒查询/UI 期限 | 只列当前账户可见的表/视图，不搜其他 Schema；`%`/`_` 当普通字符；没有匹配不等于不存在 |
| 读取生命周期 | 独立读取连接；取消标记立即发布，驱动 cancel 后台执行；旧读取或 cancel 物理结束前拒绝堆积新请求；输入变化、关闭、目标快照变化使旧结果失效 | 真驱动 connect/cancel/close 及时性未验；失败/权限不足没有“空库”结论 |
| 对象动作 | 生成未执行 SELECT、只读查看数据、查看 DDL 三个明确动作；再次校验来源节点、配置、Schema；表/视图及引用名称保留 | 输入搜索词及 Enter 查找均不表示授权业务 SQL；只有明确点击对应动作才进入已有服务 |
| SQL 收藏 | UUID、名称、可选分组、正文、修改时间；本地查找名称/分组、修改与显式删除；可收藏当前完整脚本 | 收藏独立于历史、草稿和文件；不保存连接配置、密码字段或结果；SQL 本身可能含敏感值，界面说明明文存储 |
| 持久化 | 独占目录锁，严格 UTF-8/版本/长度/校验和；原子替换，写前比对旧值，保留上一个有效版本；损坏/未知版本不覆盖，可显式复制有效备份为新 UUID | 不加密、不云同步；100 项、每项 SQL 256 KiB、总额 16 MiB 含备份；超限拒绝，不驱逐。完全无有效副本无法恢复；不可读文件不能证明额度时拒绝保存 |
| 离线打开 | 复用管理标签、文件保存/另存、草稿与关闭生命周期；新标签使用空会话，用户另行选择连接 | 不按同名连接绑定、不连接或执行；打开两次互不覆盖，编辑不写回收藏 |
| 入口整理 | 全局连接/SQL 文件/SQL 资料/工具分组；原执行、当前语句、保存/另存/重载、查找、取消和事务保留；缩进/反缩进/注释/复制行/移动行进入文本菜单 | 原动作守卫及可改绑快捷键保留；菜单显示当前快捷键，不注册跨编辑器全局加速键；品牌/主题未换皮 |

Catalog 依据：[Oracle ALL_TAB_COMMENTS](https://docs.oracle.com/en/database/oracle/oracle-database/21/refrn/ALL_TAB_COMMENTS.html)、[ALL_COL_COMMENTS](https://docs.oracle.com/en/database/oracle/oracle-database/21/refrn/ALL_COL_COMMENTS.html) 与 [PostgreSQL catalog comment functions](https://www.postgresql.org/docs/16/functions-info.html)。仅查询公开文档；不能替代真实授权/权限矩阵验证。

## 行为与测试对应

| 断言 | 本轮回归 |
| --- | --- |
| 3 模式、2 方言、精确 Schema/字面搜索参数、200/201 条、资源关闭/超时/迟到连接取消 | SchemaMetadataSearchTest |
| 输入不触发读取、明确动作身份、条件/目标变化、超时等待物理完成、后台关闭立即取消、晚到结果拒绝 | SchemaMetadataSearchDialogTest |
| 树节点/配置漂移、错误 Schema 不调动作；表/视图及只读打开实参 | SchemaObjectFindEntryTest 与既有 SchemaObject 系列 |
| UTF-8/总量/计数、独占、旧值冲突、原子移动失败、损坏主文件/未知备份保护、恢复新 UUID、显式删除 | SqlFavoriteStoreTest 与 SqlDraftDirectoryTest |
| 实际后台 I/O，创建/改分组/查找/离线返回、删除取消、放弃种子、失败保留编辑、晚到回调、超大脚本未带入提示 | SqlFavoritesDialogTest |
| 独立空会话、同名配置不隐式连接、保存与草稿装配、两标签互不覆盖、关闭后不能收藏 | SqlFavoriteTabsTest；共用生命周期由 SqlHistoryTabs/SqlDraft/SqlScriptFile 系列回归 |
| 菜单动作撤销、物理换行/选区、冻结/关闭/只读编辑守卫、旧快捷键改绑；480/640/880 宽度明暗布局 | SqlEditorIndent/LineComment/Duplicate/MoveLinesIntegrationTest、SqlEditorUsabilityTest |

## 本轮已执行记录

机器可读记录见同目录 [实际结果](2026-09-24-datacube-g6-results.json)。编译失败没有采集/引用旧 XML。通过数字不包括 skipped。

| 运行 | 实际结果 | 说明 |
| --- | --- | --- |
| baseline-full | 3727 tests / 3724 passed / 3 live skipped | 本轮起点 fresh clean test |
| m6a-service | 5/5 passed | 新 catalog 服务 |
| m6a-ui | 109 tests / 108 passed / 1 failed | JavaFX 默认取消返回 ButtonType，已显式转 null |
| m6-storage-and-search | 134/134 passed | 包含取消修复、收藏边界及原对象检索 |
| m6-favorites-compile | 编译失败，未运行测试 | 构造参数遮蔽字段，已修复 |
| m6-favorites-compile-fixed | testClasses 成功，未运行测试 | 编译不算测试通过 |
| m6-library-ui-1 | 131 tests / 72 passed / 59 failed | 菜单迁移的旧节点断言修改脚本未生效；已改实际菜单断言；新标题星号期望修正 |
| m6-library-ui-2 | 131/131 passed | 原有撤销/改绑/安全守卫断言保留 |
| m6-final-targeted | 编译失败，未运行测试 | 结果类型预览缺 TableInfo import，已补 |
| m6-final-targeted-2 | 652/652 passed / 0 skipped | 字段/收藏/草稿/文件/历史/编辑器/安全扩大回归；之后新增两项 deadline/后台关闭测试随全量验证 |

## 桌面证据与限制

夹具 `G6DiscoveryDesktopFixture` 使用真实编辑器/收藏/元数据对话框，全部数据合成；provider/session/network 路径被 probe 拦截。没有启动真实用户 profile 的应用。

首次未激活窗口时截图图像与选定窗口不一致，已停止使用并删除误采文件，不作证据；按工具恢复流程重新选择唯一夹具并激活后恢复。后续始终先核对目标及画面。已记录正确的整理前 100% 合成宽/窄窗口。整理后的主题/缩放/焦点证据及结论见下方 CP2；待验项保持单列。

## 外部待验

真实 Oracle/PostgreSQL 权限和 catalog 表/视图覆盖、真实 JDBC connect/cancel/close、安装包运行、真实操作系统缩放与多显示器切换、签名/安装升级、远端 CI 及发布均未验。此阶段最多交付本地工程与合成桌面证据，不声称消除了全部拥挤/可访问性问题或通过发布验收。

## CP2 补充证据与审查

- `branch-full`：297 suites / 3753 tests / **3750 passed** / 0 failed/errors / **3 live skipped**，4m13s；日志 SHA256 `A25ACF6BDDA5D602AD3385B281D06B08405ADBFDAD3B94B9C139307AF71AD980`。
- `branch-buildSrc`：`--rerun-tasks`，8/8 passed，0 skipped。另行重新编译合成夹具并导出测试运行类路径，只供桌面验收。
- `branch-image`：jpackageImage 成功，46s。镜像 cfg 不含 user.home/headless/acceptance/测试 profile 参数。之后发现首次 profile 问题，最终镜像需重新构建。
- `m6-focus`：13/13 passed；新增 JavaFX Ctrl+F/Tab 两项行为回归。源码全量将随首次 profile 修复再跑。
- `m6-fresh-profile-red`：1 test / 1 failed，证明全新 profile 的 .datacube 父目录缺失导致收藏无法初始化；修复只在后台创建收藏父目录，保持独占/校验/原子发布边界。

已逐项审查 catalog 参数化/范围、取消和关闭的物理完成、目标身份与动作分派、收藏的文件故障/容量/隐私/旧值冲突，以及离线文件/草稿装配。未引入依赖、自动连接或 SQL 格式化新功能。

### 本轮合成截图

夹具标准输出确认 JavaFX outputScale 分别为 `1.0x1.0` 和 `1.5x1.5`，通过 JavaFX 启动参数设置；**没有修改操作系统缩放**。截图及 SHA256 见汇总 JSON。

| 场景 | 核验范围与证据 |
| --- | --- |
| 100% 整理前后窄窗 | [整理前](evidence/g6/desktop-before-100-narrow.jpg)、[暗色整理后](evidence/g6/after100-dark-narrow.jpg)、[亮色整理后](evidence/g6/after100-light-narrow.jpg)；编辑主工具栏 4 行→3 行 |
| 150% 窄窗 | [暗色](evidence/g6/after150-dark-narrow.jpg)、[亮色](evidence/g6/after150-light-narrow.jpg)；执行/当前语句/保存及事务仍直接可见 |
| 文本菜单 | [100% 菜单](evidence/g6/after100-text-menu.jpg)；6 个动作及原快捷键提示 |
| 收藏 / catalog 查找 | [收藏 100% 亮色](evidence/g6/after100-favorites-light.jpg)、[字段与注释 150% 亮色](evidence/g6/after150-metadata-light.jpg)；实际对话框、合成内容、容量/隐私/目标提示（主图见独立 owned 截图） |
| Tab / 离线 | [Schema 焦点](evidence/g6/after150-tab-schema.jpg)、[保存焦点](evidence/g6/after150-tab-save.jpg)、[离线计数](evidence/g6/after150-offline-counts.jpg)：provider/session/metadata/network 全为 0 |

桌面工具 `focused_element` 未同步反映 JavaFX 按钮焦点，最初 100% 的仅 UIA 观测不记为通过；150% 后续以实际焦点描边截图确认 Schema→保存。模态窗口只作为 owned 截图出现，未返回可单独定位的 Window；点击报“point is over SQL 收藏, not target DataCube G6 合成验收”，故未继续模态输入。收藏/检索原生键盘路径、完整 AppShell 全局入口和真实驱动仍待人工或可正确定位模态窗口的环境验证。合成界面的成功不等于完整产品/发布验收。

最终源码/测试/资源/构建文件清单共 752 项，内容摘要记录于独占证据目录 `final-source-manifest.json`，文件 SHA256 `4D2D5C71FFD7F5BC8E73F68A8D7A14BD46AEC5CB4037ECA344AA882B09B53128`。合并后逐项比较：516 项字节相同、236 项仅 checkout 的 CRLF/LF 不同，无其他差异；Git 内容比较为空。行尾转换不称为字节完全相同，main 仍独立运行全量和构建。明文 SQL 收藏的隐私提示不保证 SQL 本文不含敏感值；用户须自行决定保存内容。

## 最终分支验证

- 首次 profile 修复后 `m6-fresh-profile-green`：9/9 passed。
- `branch-full-final`：297 suites / 3756 tests / **3753 passed** / 0 failures/errors / **3 live skipped**，3m40s。新增焦点、deadline、后台关闭、空 profile 用例均包含在本次全量；日志 SHA256 `BF9B61FA404719C08D0E0C584246CFF960774C05E7DCA5DA16A0C0D89EC74886`，XML 清单 SHA256 `A3982F06195EA177C91ABC358CCE9D8091FDD350AA272A5F4BF2908497ED2A17`。
- 3 项跳过分别为 Redis live、Oracle SchemaDiff live、PostgreSQL SchemaDiff live；均因没有明确真库环境/写授权，不计入 passed。
- `git diff --check` 通过；最终 752 项清单逐项匹配。旧全量/镜像均保留作为过程证据，最终镜像及 main 另行记录。
- `branch-image-final`：最终产品代码 jpackageImage 成功，41s；日志 SHA256 `20B12CB7B8E80EA970BF40E80E8BBC5C5259C4BB7C0B15EC396718A221D15B26`。重新核对 cfg，无测试启动参数；exe/cfg/runtime modules 摘要在汇总 JSON。

## 本地合并与 main 复验

- 实现提交：`d4b02debdf59e7af1c81a0c54a7ed0f3c2db9c1d`；main 合并：`c182128a64c970f05469129bdc1adfbd2bd1a452`。合并前 main 仍为起点 `c3481d1`，授权范围工作区干净；没有覆盖其他任务的变更。
- `main-full`：使用运行前确认不存在的 `profile-main`，`clean test --offline --no-daemon --console=plain` 加隔离 Test init；**297 suites / 3756 tests / 3753 passed / 0 failures/errors / 3 live skipped**，3m35s。日志 SHA256 `C66A5837953C573668407B3A8B1CC748CB3B3338A4BAA3505A47804168F387A9`；XML 清单 SHA256 `8DDF4E908995D90FB5627CFFD0EC83DCE6D2FF333215BA4E448DB4F5ED3EA4FF`。
- `main-buildSrc`：`:buildSrc:test --rerun-tasks`，**8/8 passed / 0 skipped**，8s；实际任务执行，非 UP-TO-DATE。日志 SHA256 `69A28754A437E4B48A48F3AC09B6B4091A32D58084DB461E73CAE58FD21B9171`。
- `main-image`：`jpackageImage --offline --no-daemon --console=plain`，成功，33s；日志 SHA256 `E47878C969391F32518B0989490E3AD398B54342F6F6AB673F85EF20BA119747`。本轮仍有既有 unchecked 编译提示及 JEP 493 jlink 提示，不称为零警告。
- 镜像 cfg 没有 user.home、headless、验收入口或测试 profile 参数；`jimage list` 确认包含收藏/检索产品类，不包含 `G6DiscoveryDesktopFixture`。exe、cfg、runtime modules 三项 SHA256 与最终分支镜像相同；这三项比对不代表对整个安装包作可复现构建承诺。精确摘要见 [实际结果](2026-09-24-datacube-g6-results.json)。
- main 复验后仅更新计划、交接和验证记录，产品/测试/构建文件保持已验证内容。完整失败记录与 3 项真实环境 skip 保留，跳过不算通过。
- 本地工程交付到此结束。原生模态输入、完整 AppShell、真实 catalog/驱动、OS 缩放/多屏、安装运行与升级、签名、远端 CI 和发布仍待验；不进入 M7，不推送或发布。
