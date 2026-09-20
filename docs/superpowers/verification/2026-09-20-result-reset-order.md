# 查询结果恢复原始行序验收

基线 main `9fa6ae1`，分支 `codex/sql-result-reset-order`。生产改动仅 `SqlEditorPane`：结果右键菜单增加“恢复原始行序”，复用已有行身份映射和结果视图修订号。动作不保留旧查询/列表引用，不加后台任务或持久状态，不重新执行 SQL。

恢复前按对象身份捕获所选单元格和焦点；清除表头排序后只排序当前可见列表，再按身份还原选择。不会按相等内容合并重复行，也不借筛选重绘扩大行范围。菜单打开与动作执行均复核查询类型、忙碌/关闭/准入/工具栏状态；旧菜单动作不能整理新查询或新筛选视图。

## 需求与证据

采用 code-testing-agent 聚焦方式，在一个类中增加 23 项离线回归，复用现有面板 fixture。未启动子代理、未创建测试中间状态目录、未读取或修改 `.testagent/`。

| 需求 | 精确证据（`SqlResultOrderResetTest`） |
| --- | --- |
| 原返回顺序、相等行身份、多单元格选择及焦点；同时保留文本筛选、列布局、导出范围与 SQL/撤销 | `restoresSourceIdentityAndMultiCellFocusWhilePreservingFiltersColumnsAndExport`（明暗两主题） |
| 文本与列条件取交集，数据库筛选状态不变，放宽文本后列条件仍生效 | `keepsTextAndColumnConditionIntersectionAndDatabaseState` |
| 取消最后一个排序箭头后仍能恢复，无选择不代选，无变化动作无副作用 | `cancelledSortArrowStillRestoresOrderWithoutInventingSelection` |
| 空结果、单行、零匹配可清排序；零匹配不补入隐藏行，放宽条件后仍为原行序 | `clearsSortForEmptyOrSingleRowsWithoutRestoringHiddenRows`（3 组） |
| 关闭资源/任务/UI、禁用根/表格、忙碌、准入/队列停止后拒绝迟到动作，忙碌结束恢复可用 | `lateResetCannotMutateBlockedQuery`（8 组） |
| 新查询、筛选重建、空结果、计划、概览、更新、错误及清理不被旧动作改变 | `oldMenuActionCannotChangeReplacementOrNonQueryResults`（8 组） |

初次编译修正 ContextMenu 的 WindowEvent 类型后，23 项均因缺少恢复入口而失败（28 秒、exit 1）。实现后发现忙碌入口保护缺口：`setButtonsRunning(true)` 会禁用结果工具栏，但并不单独设置 `running` 字段；增加工具栏禁用复核，不修改既有执行状态逻辑。

五类定向回归 24 秒、exit 0：新类 23、列布局 25、单元格集成 35、结果筛选契约 42、概览恢复 23，共 148 项全通过，无失败/错误/跳过。

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.fx.SqlResultOrderResetTest --tests com.datacube.fx.SqlResultColumnResetTest --tests com.datacube.fx.SqlResultCellIntegrationTest --tests com.datacube.fx.SqlEditorResultFilterContractTest --tests com.datacube.fx.SqlOverviewResetOrderTest --no-daemon --console=plain
```

所有新用例复核合成 SQL 文件内容及 provider/session/metadata/network 零调用；不使用真实连接、SQL、历史、凭据或剪贴板。自审按需求核对具体行次序、对象身份、选区/焦点与次级可观察状态，不以测试数量替代行为证据。没有独立外部代码审查。

## 全量与打包

相同 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 下执行：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

3 分 5 秒、exit 0 / BUILD SUCCESSFUL；应用 XML 合计 3,240 项：3,237 通过、3 项既有 live 跳过、0 失败/错误，buildSrc 8 项通过。开发镜像构建成功，未改变正式版本。保留既有 unchecked、jlink 辅助调用 `javac/java failed: Picked up JAVA_TOOL_OPTIONS...` 和 JEP 493 提示，不宣称这些警告已修复。

## 隔离原生桌面

复用未修改的 `SqlScriptDetailsDesktopFixture`，复制最终镜像到忽略目录 `build/result-reset-order-desktop-20260920/DataCube`，只在副本配置中追加测试入口、单类 patch-module 与独立 `script-details-desktop-profile`。不注册连接、不执行 SQL。原/副本 runtime/lib/modules SHA-256 均为 `2B6A41EA189F33AAEA9B595DD7CBF70C839BDB0672A3A0DA58411CB5BBD84E97`；工作树生产配置保持 `DataCubeFx`，SHA-256 为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。

按 computer-use 技能使用原生截图、鼠标和键盘操作实际打包窗口。选择合成语句 #3，其原顺序为 READY / WAITING，两列 status / message。

| 原生检查 | 观察结果 |
| --- | --- |
| 初始右键菜单 | “恢复原始行序”禁用，其他原有入口保留 |
| status 表头升序、降序；选中 WAITING 后 Shift+Right 选中同一行两格；鼠标恢复 | 行序恢复 READY / WAITING，无排序箭头；两格选择仍在 WAITING 行，未跳到当前第一行 |
| message 升序；选中 `another row`；菜单键、方向键、Enter | 成功触发恢复，`another row` 的选择与焦点随 WAITING 回到第二行 |
| 排序后输入 `second`，等待筛选完成；暗色窄窗右键恢复 | 筛选保持 `second`，计数仍 1 / 2，只有 READY；只清除排序，不补回 WAITING |
| 明暗主题、fixture 640 / 980 宽度 | 现有工具栏自然换行；暗色宽/窄窗和亮色窄窗实际查看菜单，恢复入口可见，恢复后禁用。亮色宽窗结果显示正常 |
| 清除筛选 | 两条记录按 READY / WAITING 原始顺序显示，无排序箭头 |
| Alt+F4 正常退出 | 指定验收进程消失，随后窗口列表无该验收窗口，无强制终止 |

键盘限制单独记录：Shift+F10 能打开上下文菜单，但后续 Down 在本次输入通道中唤出 Windows 系统菜单，未完成该路径验收；退出后重新聚焦，使用菜单键 → 方向键 → Enter 成功。尚未定位系统菜单干扰的原因，不据此修改全局键盘处理，不把菜单键通过称为 Shift+F10 已修复。自动测试仍覆盖取消最后排序箭头、组合列条件、重复行和各类迟到动作，不把这些自动测试冒充原生穷举。

正常退出后，独立 profile 顶层仅 `settings.properties` 与 `synthetic-details.sql`，另有 JavaFX 缓存；合成 SQL 的 SHA-256 为 `3E0C0DFB331C462DC9B0325DA390A8B8381B5C7722F8260C455F3210DA07BDF4`，与 fixture 原始内容一致。没有读写真实用户数据或剪贴板。以上不是 live 数据库、真实用户效率或正式发布验收。

## 本地集成

实现与验收在独立 worktree 完成，待按基线守卫快进 main 并复验。不推送、不打 tag。
