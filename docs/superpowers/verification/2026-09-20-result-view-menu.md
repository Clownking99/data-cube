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

## 原生验收待补

基线复现时桌面可用。新版独立窗口成功启动，但捕获为全黑；刷新窗口列表后尝试激活失败，依 computer-use 技能停止进一步输入，已请求解锁。尚未验证新版 Tab/Down/Enter/Esc、分裂按钮点击和明暗宽窄窗，不能以自动测试替代原生结论。独立验收窗口仍在运行，不强制终止其他窗口。此记录不代表 live 数据库、真实用户或正式发布验收。

独立 profile 仅包含 JavaFX 缓存和合成 SQL 文件，后者 SHA-256 为 `3E0C0DFB331C462DC9B0325DA390A8B8381B5C7722F8260C455F3210DA07BDF4`，与基线合成文件一致。`jimage list` 检查生产模块包含新工具栏且未包含新增测试类或桌面 fixture。

## 交付边界

改动仅提交到功能分支，等待原生补验后再合回 main；未推送、未打 tag、未发布。根仓库 main 保持基线，用户 `.testagent/` 未跟踪目录未读取、修改或暂存。`git diff --check` 通过。
