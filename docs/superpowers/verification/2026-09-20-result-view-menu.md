# 查询结果工具栏浏览菜单验收

基线 `e3b6c03`，分支 `codex/sql-result-context-keyboard`。只改查询结果工具栏入口及接线，使用原有查看单元格、查看整行、定位行和恢复行序实现。不改全局快捷键、JavaFX 版本、数据库请求或正式版本号。

## 基线与取舍

在上一轮独立合成镜像上重新验证：选中 id=1，Shift+F10 打开结果菜单，下一次 Down 唤起 Windows 系统菜单，菜单焦点并未下移。该现象不是本轮代码造成。退出两个菜单并正常关闭基线验收窗口；窗口列表和进程路径检查确认退出。

阅读 [JavaFX 25 Windows GlassWindow.cpp](https://raw.githubusercontent.com/openjdk/jfx/jfx25/modules/javafx.graphics/src/main/native-glass/win/GlassWindow.cpp) 485–501 行及 [Microsoft WM_KEYDOWN remarks](https://learn.microsoft.com/en-us/windows/win32/inputdev/wm-keydown)：原生分支仅排除无修饰键 F10 的默认处理，Windows 默认 F10 处理会激活系统菜单。此为与复现相符的原因线索，未证明所有键盘/输入通道均相同，不声称 Java 层消费事件能修复原生分支。

因此保留右键菜单，提供标准分裂按钮作为可发现的替代键盘路径，不引入原生钩子、模拟按键或全局拦截。Shift+F10 本身未修复。

## 自动测试

使用 code-testing-agent 技能的定向行为验证思路；沿用 JUnit、FX 线程测试和离线合成 fixture，不增加第三方测试依赖，不读取用户 `.testagent/`。测试不使用真实数据库、SQL 文件、历史、凭据或剪贴板。

| Requirement | Evidence |
| --- | --- |
| 主按钮/菜单单元格、可见整行、定位行；排序后读对行、隐藏列不泄露；不改变源 SQL、撤销、筛选或非目标选区 | `browseEntryUsesFocusedVisibleResultWithoutChangingSqlOrSelection`（4 组） |
| 无选择、空结果、零匹配、无查询时可用状态正确；恢复保留筛选和身份选区，不补回隐藏行 | `availabilityTracksSelectionEmptyResultsAndResetPreservesFilteredRows` |
| 替换结果、筛选、计划、概览、忙碌与各关闭状态拒绝迟到菜单；主按钮忙碌保护 | `lateMenuActionsRejectChangedOrBlockedView`（12 组） |
| 菜单打开后重绘或禁用关闭弹层 | `openMenuClosesWhenResultChangesOrControlsAreDisabled`（3 组） |
| 480/640/880 宽度两主题、控件完整文字和无重叠 | 原有 `SqlResultToolbarLayoutTest.resultActionsKeepFullLabelsAndWrapWithoutDispatching`，改后重跑 |

实现后首轮定向测试成功，43 秒、exit 0：新类 20、单元格集成 35、工具栏 22、布局 7、恢复行序 23，共 107 项全通过，0 失败/错误/跳过。没有把这一轮称为先失败后实现的 TDD。

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.fx.SqlResultViewMenuTest --tests com.datacube.fx.SqlResultCellIntegrationTest --tests com.datacube.fx.SqlResultToolbarTest --tests com.datacube.fx.SqlResultToolbarLayoutTest --tests com.datacube.fx.SqlResultOrderResetTest --no-daemon --console=plain
```

自审：动作仅捕获修订号，不保留旧查询或行列表；新增项在打开时计算禁用状态，执行时再核对视图/忙碌/生命周期；查看快照和行序/定位的既有行为由集成断言验证。未进行独立外部审查。

## 全量与开发镜像

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

3 分 5 秒、exit 0 / BUILD SUCCESSFUL。应用测试 XML 合计 3,260 项：3,257 通过、3 项既有 live 跳过，0 失败/错误；buildSrc 8 项通过。保留既有 unchecked、jlink 辅助调用 `javac/java failed: Picked up JAVA_TOOL_OPTIONS...` 及 JEP 493 提示，不把这些警告称为本轮已修复。

复制最终镜像到根仓库忽略目录 `build/result-view-menu-desktop-20260920/DataCube`。仅在副本配置中使用未修改的 `SqlScriptDetailsDesktopFixture` 单类 patch-module 和独立 `script-details-desktop-profile`，无连接注册、无 SQL 执行。原/副本 runtime/lib/modules SHA-256 均为 `0E6855834D62CD8DA60CA4EEEBB05AE5232BBA0507DCBE689A29E947CA22BE9E`；生产配置仍为 `com.datacube.DataCubeFx`，SHA-256 为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。

## 原生验收首次受阻

基线复现时桌面可用。新版独立窗口成功启动，但捕获为全黑；刷新窗口列表后尝试激活失败，依 computer-use 技能停止进一步输入并请求解锁。当时未验证新版 Tab/Down/Enter/Esc、分裂按钮点击和明暗宽窄窗，未以自动测试替代原生结论，保留独立验收窗口以供后续补验。

独立 profile 仅包含 JavaFX 缓存和合成 SQL 文件，后者 SHA-256 为 `3E0C0DFB331C462DC9B0325DA390A8B8381B5C7722F8260C455F3210DA07BDF4`，与基线合成文件一致。`jimage list` 检查生产模块包含新工具栏且未包含新增测试类或桌面 fixture。

## 交付边界

实现提交 `8e0f8a2` 最初仅保留在功能分支，待下述原生补验完成后再合回 main。未推送、未打 tag、未发布；用户 `.testagent/` 未跟踪目录未读取、修改或暂存。

## 原生补验完成

后续“继续推进产品”轮次，重新读取 computer-use 技能并选择已存在的独立验收窗口。首次报告最小化，按恢复指引刷新选择、激活并重新捕获后，桌面正常。未重新启动真实应用或连接配置；生产代码保持 `8e0f8a2`，复核验收副本与工作树模块哈希仍为上文 `0E685583...`。

| 检查 | 原生观察 |
| --- | --- |
| 主区域一键查看 | 语句 #3 选中 `second query result`，点击“查看单元格”主区域，显示正确正文、message 列和第一行位置；关闭后原选择保留 |
| Tab / Shift+Tab | 焦点在查看分裂按钮与复制按钮之间切换，聚焦轮廓可见，没有执行复制 |
| Down → Down → Enter | 聚焦查看按钮后 Down 打开菜单、再次 Down 选择整行、Enter 打开对应行快照，status=READY、message 正确；没有出现 Windows 系统菜单 |
| 鼠标下拉及 Esc | 点击箭头打开四项菜单；Esc 关闭，排序和选择不变 |
| 恢复原始行序 | 按 message 排序为 WAITING / READY，经下拉恢复为 READY / WAITING，排序箭头清除，原 READY 选择随记录回到第一行；再次打开恢复项禁用 |
| 定位行入口 | 暗色窄窗下点击“定位到行”，显示当前 2 行、范围 1–2；取消后原选择保留。跨行确认仍由自动测试验证，本次不扩大为原生通过 |
| 主题与宽度 | fixture 640 / 980 宽度、明暗主题均查看工具栏；窄窗操作自然换行且文字完整，两主题窄窗菜单四项完整显示在弹层中 |
| 正常关闭 | Alt+F4 触发正常退出；随后窗口列表为空、进程检查无该验收程序。未强制终止 |

补验中的输入通道限制单列：初次 resize 后一次 `unknown screenshotId screenshot-0`，刷新截图后重试成功；单元格模态窗口通过宿主发送 Esc 未关闭，改用观察到的“关闭”按钮；行定位的可访问元素值设置报告 cached element 不可用，未改动输入，刷新后取消。未绕过工具输入路径，也未将这些尝试记为成功或推断为产品缺陷。工具栏菜单的 Esc、方向键和 Enter 则已直接观察通过。

正常退出后合成 SQL SHA-256 仍为 `3E0C0DFB...`，无真实数据或剪贴板读写。Shift+F10 本身的旧限制保持不变，本轮验收的是工具栏替代路径；不代表 live 数据库、真实用户效率或正式发布验收。

## main 本地集成复验

补验文档提交 `4440868`；核对 main 仍为 `e3b6c03`、无跟踪文件或暂存修改后，`git merge --ff-only codex/sql-result-context-keyboard` 成功。原有 `.testagent/` 未跟踪目录保持原样。

在 main 上设置相同 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，执行上文五类定向测试并追加 `jpackageImage '-PappVersion=0.0.0'`。59 秒、exit 0 / BUILD SUCCESSFUL；XML 汇总 107 项全部通过，0 失败/错误/跳过，开发镜像构建成功。本轮未重跑全量，前文 3,257 通过 / 3 live 跳过来自此前 worktree clean run。

使用 `jimage extract --include 'glob:/com.datacube/**'` 提取 main 与已验收 worktree 的应用模块，按相对路径逐文件比对 SHA-256：各 828 文件、813 class，0 差异、0 Fixture。main 生产配置哈希仍为 `AD4F0A4A...`，未引入测试入口或独立 profile。随后只补路线图与本记录，生产代码不变；`git diff --check` 通过。未推送、未打 tag、未正式发布。
