# G8 本地验收账本（非发布验收）

维护者于 2026-09-25 明确授权 G8 本地桌面验收及本地集成。基线 main 为 e03d493243e6da60fedec63d5e0f924383992c75，独立分支 codex/datacube-g8-local-acceptance。本轮使用 Windows、Java 25.0.1+8、JavaFX 25、Gradle 9.2.0，所有构建离线。授权的本地范围已交付：实现 b1135d9，main 产品代码合并 4885c40，证据修正集成 e65edb3；分支与 main 均有新验证。总体 M8 仍待剩余原生及外部验收。

## 产品变更与回归

| 实际问题 | 最小修复 | 新回归与红绿证据 |
| --- | --- | --- |
| 150% 进程比例、760 宽表格的查询/翻页按钮缩成省略号 | 查询工具栏按可用宽度换行，长对象/连接标签有上限和完整 tooltip | DataGridExplicitSaveTest.pagingActionsRemainLegibleWithinNarrowAndWidePanels；480/dark、760/dark、760/light 修复前失败，1200/light 原本通过；修复后 DataGrid 三类 16/16 |
| 暗色收藏/字段检索空输入提示为近黑色 | 仅五个输入框使用主题次级文字色 | SqlFavoritesDialogTest.emptyLibraryInputsKeepTheirGuidanceVisibleInBothThemesAndFocusStates、SchemaMetadataSearchDialogTest.emptyQueryGuidanceRemainsVisibleInBothThemesAndFocusStatesWithoutReading；修复前两失败，修复后两完整类 16/16 |

布局测试验证按钮完整、在面板内、不覆盖数据、保留表格空间且不写入；提示测试验证实际渲染文字颜色和可见性，明暗及 focused/unfocused 样式均覆盖，检索不触发元数据读取。既有服务门禁、事务、生产确认和关闭语义未改。

## 本轮原生桌面矩阵

截图及观察树在 [evidence/g8/screenshots](evidence/g8/screenshots)，文件摘要见 [desktop-manifest.json](evidence/g8/desktop-manifest.json)。编号是文件前缀。截图来自真实窗口原生输入；测试夹具只注入合成数据/可控后台操作。不是完整端到端真库验收。

| 范围 | 本轮操作与结果 | 证据 | 限制 |
| --- | --- | --- | --- |
| 迁移预检查/生产确认 | 100% 下只读预检查后执行启用；确认显示目标/范围，取消不导入；明确确认后显示提交未知并禁止无条件重试 | 01–07；migration-baseline-100 stdout 为 NETWORK=0 / SYNTHETIC_IMPORTS=1 | COMMIT_UNKNOWN 为夹具返回，不代表数据库连接中断实测 |
| 批次结果 | 150% 下 A 搜索 alpha → B → A 保留筛选；固定查看器保留全部两行和来源 SQL | 08–12 | 结果由夹具初始化注入，无 SQL 执行 |
| 表格保存 | 原生改格后换行，mock writes/commits 仍 0；保存确认显示生产目标；Escape 取消保留修改；再次明确确认后 writes=1 / commits=1 | 13–18、22 | mock 回读返回初始行，不能当真库持久化证明 |
| 只读表格 | Oracle mock 的写操作禁用，查询入口可用；切明暗与缩窄窗口 | 19–22 | 真库只读权限/驱动行为待验 |
| 迁移取消/在途关闭 | 150% 下阻塞合成预检查，经取消恢复控件；第二次阻塞经 Alt+F4 关闭并释放资源 | 23–25；migration-cancel-150 stdout 为 NETWORK=0 / SYNTHETIC_IMPORTS=0 / CLEANUPS=2 | 不代表网络阻塞或已提交数据回滚 |
| 收藏 | 输入“订单”、选择项目、离线打开；provider/session/metadata/network 均 0 | 26–30、33 | 首次 type_text 未进入模态，28 的 set_value 才成功 |
| 字段/注释检索 | 打开限制为 Exact Schema 的窗口后取消；未读取元数据 | 32–33 | 查询输入索引连续两次工具失败后停止，搜索/结果动作仍未验；31 未生成 |
| 修复后窄窗与主题 | 150%、760 宽查询/翻页/行操作不省略；收藏空提示明暗均可读 | 34–37 | 仅这些组合；不等于 OS 缩放切换或完整可访问性 |
| 镜像内空白 AppShell | image 自带 runtime + product module，独占空 profile；原生菜单新建离线脚本、输入合成 SQL、Ctrl+F、Escape、Alt+F4；执行与事务禁用，合成草稿保存 | 38–42；image-shell-candidate launch/stdout/stderr | 探针直接构建 AppShell，不运行 DataCube.exe 的 splash/更新自检；关闭后窗口列表及 PID 确认退出 |

原生动作没有通过反射或 .fire 代替；G8WorkflowDesktopFixture 中反射仅用于启动时注入批次结果。100%/150% 是 -Dglass.win.uiScale 的进程输出比例，stdout 实际为 1.0/1.5；未改 OS 设置。合成工作流/迁移用 mock JDBC 或拒绝网络工厂，空壳只使用新建空 profile，未创建连接或点击更新入口。

## 实际自动化证据

本轮全部命令、时间、exit code、实际测试任务是否执行、XML 计数、跳过原因、日志及 XML 清单 SHA-256 保存在 [结果 JSON](2026-09-25-datacube-g8-results.json)。原始日志/XML 位于独占临时目录 C:/Users/hetia/AppData/Local/Temp/datacube-g8-df85f6c35cf0457d9255e8714dd114a4；日志副本归档在 evidence/g8/checks。旧阶段通过不充当本轮证据。

| 执行 | 结果 |
| --- | --- |
| baseline-full | 307 suites / 3812 tests / 3809 passed / 0 failures/errors / 3 live skipped |
| fixture-compile | 编译通过；不是测试 |
| grid-layout-red | 4 tests / 1 passed / 3 failed |
| grid-layout-green | 16 passed / 0 skipped |
| discovery-prompt-red | 2 tests / 2 failed |
| discovery-prompt-green | 16 passed / 0 skipped |
| branch-image-candidate | jpackageImage 成功；仅候选镜像，用于上述原生壳验证 |
| branch-final-full | clean test；307 suites / 3818 tests / 3815 passed / 0 failures/errors / 3 live skipped，3m36s |
| branch-final-buildSrc | --rerun-tasks；8/8 passed，0 skipped，8s |
| branch-final-image / runtime | jpackageImage 成功；正式 cfg 无测试参数、镜像无夹具类；PG/Oracle 驱动发现成功，connectCalls=0 |
| main-final-full | 合并 4885c40 的新 profile clean test；307 suites / 3818 tests / 3815 passed / 0 failures/errors / 3 live skipped，3m33s |
| main-final-buildSrc | e65edb3 的 --rerun-tasks；8/8 passed，0 skipped，9s |
| main-final-image / runtime | e65edb3 的 jpackageImage 成功，32s；镜像无测试类/参数，PG/Oracle 驱动发现成功，connectCalls=0 |

三项 live skips 为 Redis、Oracle Schema Diff、PostgreSQL Schema Diff：未提供相应环境及允许写入开关，本轮故意移除 DATACUBE_REDIS_* / DATACUBE_SCHEMA_DIFF_*，没有访问真实实例。跳过不是通过。

## 失败、工具限制与待验

- 一次点击无截图几何信息、一次窗口 resize 未稳定而 bounds changed；各在重新观察后一次重试成功。
- 收藏 type_text 未输入，以实际成功的 UIA set_value 和后续截图为准。字段检索输入连续两次索引不可用后停止，保留待验。
- 部分即时观察树仍为操作前状态；以稳定后续截图、文本和 mock 计数交叉判断。关闭镜像壳后取图无目标是关闭后的工具结果，之后确认进程退出，非截图成功。
- 镜像探针有 javafx.graphics native-access 警告，未改正式 cfg。Ctrl+F 展开时当前窗口底部草稿说明部分裁切，Escape 后恢复；极限布局和完整键盘遍历待补。未宣称所有拥挤或可访问性问题已消除。
- 真库权限、事务/取消/超时、跨库迁移对账；系统缩放切换、多屏、完整原生字段检索/重启恢复；正式启动器、签名/安装/便携升级和失败回退；远端同 SHA CI；3–5 位真实用户任务，均待授权或人工验收。
- G7 曾记录的 SchemaDiffServiceTest 偶发失败根因未定。本轮若无复现只记录未复现，不称已修复。

## 本地变更说明草案

用户可见变化只有分页工具栏窄窗换行和收藏/检索空提示可读性。源代码基线为 e03d493；最终实现/合并 SHA 见交付检查点和结果 JSON。没有格式/数据库迁移、版本号变更或新依赖；正式镜像不携带 G8 测试夹具、探针或临时 profile/headless 参数。回退可在审查后 revert 本阶段实现提交，不删除 profile 或用户数据。本文是本地变更说明，未签名、未安装、未推送、未发布，不等于最终 M8 验收。

## 审查与交付检查点

分支提交前审查（历史检查点）：产品只改 DataGridPane 布局与五个 CSS 提示选择器，动作处理器、门禁、请求/目标绑定、事务、取消与关闭实现未改。回归先红后绿，最终 clean test 和强制 buildSrc 均实际运行；三个 live skips 单列。镜像 jimage 列表无 DesktopFixture/探针/测试 provider，正式 cfg 无 profile/headless/G8 参数，镜像零连接驱动探针通过。781 个源码/测试/构建文件记录原始与 LF 归一化摘要，最终验证后复核未变；后续 main 将再次对比。全部可见验收窗口已退出。git diff --check 无问题；主目录授权范围干净且 main 仍 e03d493。准备本地提交与合并，尚不记录为 main 通过。

交付复核：main 产品代码合并 4885c40 完成全量复验；随后仅合并证据换行修正和检查点为 e65edb3，源码/测试/构建与 4885c40 的 Git 内容完全相同，因此没有把未重跑的测试称作另一套新执行。e65edb3 完成强制 buildSrc、镜像及运行时探针。分支/main 的 DataCube.exe、cfg、runtime modules 三项 SHA-256 完全相同（摘要见结果 JSON）。781 项源文件无实质差异，566 项字节相同、215 项仅 CRLF。112 份桌面捕获、13 份构建日志及两个清单均按原始 SHA-256 核对通过。

证据归档首次出现的行尾空白报告及 main 的 21 项字节不符均保留在实施检查点和结果 JSON。原因是先暂存再设置 -text，旧 index 已归一化；分支 6c4079f 重新暂存原始字节后合并，未修改捕获内容或测试结论。局部属性只对原始捕获排除尾空白/末尾空行检查，代码与文档常规检查通过。后续最终提交只更新交付文档/日志，没有产品变动，不额外重复已通过测试。没有执行 push、tag、PR、安装更新、真实连接或发布。
