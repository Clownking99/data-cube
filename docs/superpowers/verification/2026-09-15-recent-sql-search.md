# 最近 SQL 文件检索验收

基线 main `1cbfd4d`，独立 `codex/recent-sql-search`。设计见[最近文件检索](../specs/2026-09-15-recent-sql-search.md)。不修改 RecentSqlFiles 持久化或 SqlFileEntry 读取管线，不访问业务文件或 `.testagent/`，不推送、不打 tag。

## 需求与证据

测试技能用于检索、候选身份、确认/取消和失效状态的直接断言；使用现有 JavaFX/JUnit 夹具，不引入通用测试流水线工件。

| 需求 | 证据 |
| --- | --- |
| 固定菜单入口，不平铺路径 | `SqlScriptFileEntryTest.recentPathsHaveOneSearchableEntryInsteadOfUnboundedMenuLabels` |
| 快照/同名路径/不探测缺失文件 | `RecentSqlFilesDialogTest.snapshotShowsExactReadOnlyPathWithoutProbingMissingFiles` |
| 字面、大小写、Unicode/路径筛选 | `literalPathSearchPreservesOrderWithoutImplicitOpening`（4 种） |
| 过滤保留路径身份，排除后不代选 | `selectionFollowsPathNeverReusedIndexAndClearDoesNotSelectReplacement` |
| 空列表与无匹配、确认禁用、清除恢复 | `emptyAndNoMatchAreDistinctAndCannotConfirm`（2 种） |
| 按钮/输入框/列表显式确认精确路径 | `explicitConfirmationReturnsExactFilteredPath`（3 种） |
| 取消、Esc 不返回路径 | `cancelFromEverySurfaceReturnsNothing`（4 种） |
| 完整路径预览 Enter 不打开、Ctrl+F/↓ 聚焦 | `pathPreviewEnterCannotOpenAndCtrlFReturnsToQuery`、显式确认测试 |
| 关闭/禁用/准入变化后拒绝旧候选 | `staleActionsCannotReturnCandidate`（3 种） |
| 255/256/257 查询边界整体拒绝 | `queryLimitIsInclusiveAndOverLimitEditRejectedWithoutChangingState` |
| 10 条原顺序、完整路径尾部、土耳其 Locale | `localeIndependentSearchCoversFullCapacityAndUntruncatedPath` |
| 480 窄窗明暗主题主要控件可见 | `narrowDialogKeepsSearchPathAndActionsVisibleInBothThemes`（2 种） |
| 返回后快照/最新索引/可用性复核，不写索引 | `SqlScriptFileEntryTest.recentPickerRevalidatesSnapshotAndCurrentIndexBeforeOpening`（6 种） |
| 新建、选择文件及清空最近后的回调仍有效 | 既有 `newScriptCallbackSurvivesRecentMenuRebuildWithoutOpeningFiles` 更新菜单契约 |

## 自动验证

均设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，使用 Gradle wrapper `--no-daemon --console=plain`。

1. 菜单契约 RED：21 秒 exit 1；旧菜单平铺 2 条路径，缺少统一检索入口。
2. 首轮菜单/对话框 40 项中 5 项失败：未显示的 Dialog 尚无可供 CSS lookup 查找的按钮节点。测试改用 DialogPane 的 `lookupButton` 获取真实按钮，不改变生产行为或降低断言。
3. 对话框、文件入口和既有 RecentSqlFiles 存储用例联跑：12 秒 exit 0。

4. 首轮全量 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`：2 分 41 秒 exit 0，2,642 项中 2,639 通过、3 项既有 live 跳过，buildSrc 8 项通过。
5. 打包桌面发现聚焦时查询提示透明、长路径列表横向溢出。增加两个局部 CSS 选择器，列表改为文件名及可前缀省略的目录标签；完整路径仍只读保留。将聚焦提示实际颜色、实际渲染目录尾部和行宽断言加入明暗窄窗用例，定向 40 项再跑：12 秒 exit 0。

桌面操作只使用隔离 profile 和合成路径；首轮从真实菜单打开、取消后最近索引 SHA-256 未变，正常关闭后精确 exe 路径进程数为 0。最终修订的全量/镜像及桌面证据另记，不把第一轮镜像当作最终版本。

6. 最终修订再次执行同一全量 clean/test/jpackage 命令：2 分 26 秒 exit 0，2,642 项中 2,639 通过、3 项既有 live 跳过，无失败/错误；buildSrc 8 项成功报告不变。本轮净新增 30 项回归。现有 unchecked、JAVA_TOOL_OPTIONS 探测和 JEP 493 提示仍存在，不宣称无警告。

## 最终镜像桌面验收

computer-use 操作从最终生产镜像复制出的独立程序，仅副本 `--patch-module` 测试夹具；profile 为 `build/recent-sql-desktop-profile`，只建立 3 条合成路径索引，不创建 SQL 文件，不实例化数据库服务，不启动 AppShell 的更新/恢复流程。

- 暗色 480 窄窗：搜索提示可见，文件名和省略前缀的目录尾部可辨认；输入 `archive` 后 1/3 匹配，旧候选与路径预览清空、打开禁用。原生 ↓ 选中后，Enter 返回准确的 `archive/same.sql`。
- 亮色 480 窄窗：原生 Ctrl+F 聚焦，输入无匹配词显示 0/3 和占位提示；点击「清除筛选」恢复 3/3，仍无候选。原生 Esc 返回取消。
- 最终生产菜单路径（有 owner）：固定四项 → 最近文件 → 选择 `reporting/same.sql` → 点击打开，菜单回调收到准确路径。初次修订前也验证过实际菜单取消。
- 为让桌面工具直接寻址，原生键盘验收使用无 owner 的同一生产 Dialog；生产菜单的有 owner 模式实测鼠标操作。自动测试覆盖确认/取消事件与失效守卫，不将这些路径全部描述为人工测试。
- 正常关闭后精确 exe 路径进程数为 0，隔离 profile 内不存在 SQL 文件；最近索引 SHA-256 仍为 `439A4CB37DB2714D844A53D16843ACBCEB3898FE63AB034C11E5185F0369FE4F`，与启动后初始值相同。

生产模块包含 `RecentSqlFilesDialog`，不含 `DesktopFixture` 或 `DraftConnectionProbe`；入口仍为 `com.datacube/com.datacube.DataCubeFx`，无 fixture patch/profile 参数。最终 modules SHA-256：`96865E16D0336C0533583B617579B9ADECE4129AB8AB19ACE6AE45427543EB43`。本地版本 0.0.0，不代表正式发布、真实数据库、安装升级或远端 CI 验收。
