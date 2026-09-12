# 行详情字段名筛选验收

基线 `f95688e`；分支 `codex/result-row-filter`。范围见[设计与分步计划](../specs/2026-09-12-result-row-field-filter.md)。未接触真实数据库、用户配置、历史、业务文件或 `.testagent/`。

## 行为证据

复用 JUnit 5 / FxUiTestSupport；直接显示真实 ResultRowDialog，不用字符串源码匹配代替交互测试。

| 要求 | 证据 |
| --- | --- |
| 字段名大小写、两端空白、中文、字面特殊字符、控制字符显示一致；保持同名原列号和顺序；不搜索值/类型/编号 | `ResultRowDialogTest.nameFilterIsLiteralCaseInsensitiveAndKeepsSnapshotOrder`（7 组） |
| 同名字段按身份保留正文与反向正文选区；排除后清详情；无匹配提示与清空恢复 | `survivingDuplicateKeepsIdentityAndTextSelectionThenExclusionClearsDetails` |
| 255/256 接受，257 全部拒绝；无候选时 Enter 不选择或关闭，缩短可恢复 | `overlongQueryIsRejectedWithoutSearchingItsPrefix`（3 组） |
| Enter / 向下明确进入列表，Ctrl+F 聚焦并全选；保留现有候选选择 | `queryKeyboardExplicitlySelectsFirstMatchAndShortcutFocusesQuery`（2 组） |
| 隐藏字段、超过 200 项的字段、512 单元元数据之后的尾部不进入匹配，空白恢复 200 项 | `filteringNeverIncludesHiddenOmittedOrTruncatedNamesAndBlankRestoresSnapshot` |
| 输入/字段表/正文 Escape 关闭；明暗 480/720 宽度下新增控制及旧警告边界 | 扩展 `escapeFromEitherReadingAreaCloses`（3 组）、`boundedWarningsAndControlsRemainReadableWithPathologicalMetadata`（4 组） |
| 筛选并选择后原表排序/筛选/隐藏/宽度/焦点/多选、SQL 反向选区、文件与撤销不变；新结果后仍旧快照 | 扩展 `SqlResultCellIntegrationTest.rowDetailsCaptureSortedFilteredVisibleProjectionWithoutChangingSourceAndRemainFrozen` |

实际 Pane 测试使用 @TempDir 和 DraftConnectionProbe；断言 provider/session/metadata/network 为零，注入剪贴板 writer 在误调用时失败。保留既有空值、二进制、残缺行、关闭与忙碌守卫回归；不宣称覆盖率百分比。

## 命令与结果

- 红灯：新测试先运行，27 项中 19 失败，原因均为旧界面不存在 query/clear/filter-status 新控件；28s，exit 1。
- 第一次实现后窗口/模型 51 项通过（12s，exit 0）。该命令额外写了一个未匹配的 `SqlEditorResultRowPreviewTest` 过滤项，不将其记为已运行集成测试。
- 修正为实际集成类并扩展交叉断言后，定向 76 项全部通过（19s，exit 0）：Dialog 27、RowPreview 24、CellIntegration 25。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*ResultRowDialogTest' --tests '*ResultRowPreviewTest' --tests '*SqlResultCellIntegrationTest' --no-daemon --console=plain
```

首次全量 `clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain` 通过（2m23s，exit 0）：2,237 总项、2,234 通过、3 既有 live 跳过、0 失败/错误。

桌面发现提示字在暗色主题下不清晰；补充聚焦输入框的实际 Text 可见性与主题色断言，4 组先全部失败（13s，exit 1）。为 `#result-row-query` 单独加入 `-fx-prompt-text-fill: -brand-fg-dim` 后，76 项定向回归再次通过（26s，exit 0）。没有改变其他输入框。

最终 `clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain` 再次通过（2m37s，exit 0）：2,237 总项、2,234 通过、3 既有 live 跳过、0 失败/错误。两次均使用独占临时目录的 Windows ShortPath 设置 java.io.tmpdir、非 headless。构建有既有 unchecked、JAVA_TOOL_OPTIONS 探测及 JEP 493 输出，不称为无警告构建。

最终 `jimage list` 确认生产镜像有 ResultRowDialog，没有 ResultRowDesktopFixture、ResultRowFilterDesktopFixture 或 DraftConnectionProbe。生产启动类仍为 `com.datacube/com.datacube.DataCubeFx`；仅开发镜像独立副本的 cfg 加入测试类 patch-module、夹具入口与临时 user.home。

## 桌面检查过程

- 第一轮：生产镜像独立副本 + 既有 ResultRowDesktopFixture，真实未绑定 SQL 页面右键打开详情，显示原焦点 note、可见 11 列。发现提示字问题并关闭详情和应用；原表 note 选择保留。
- 该通道只返回父窗口，键盘调用激活父窗口后文字没有输入；UIA set_value 报 CacheRequest 属性错误。不把这些调用当作筛选或键盘通过证据，也不绕过控制通道操作 Windows。
- 第二轮使用真实 ResultRowDialog 的独立无 owner 合成夹具，沿用其全部生产 UI；控制台/脚本、用户业务配置和真实数据库均不参与。
- 第二轮实际键盘：输入 `SAME` 后仅列原列 3/4，匹配 2/4，旧 note 详情清空。Enter 选第一项，正文 `left`；Down 移至第二项，原列 4、正文 `right`。Ctrl+F 聚焦并全选 `SAME`，输入 `hidden_only` 后匹配 0/4，列表与详情明确无匹配，正文清空。
- 鼠标点击清空恢复 4/4 列，正文仍空，没有自动猜选。暗色聚焦输入框提示字清楚可见。F7 将窗口设为 480 宽，输入、清空、字段表、边界说明、正文和关闭按钮均可见；F6 切亮色后同样可读。
- 亮色窄窗输入 `note` 匹配 1/4，Down 进入字段表，原列 2 与正文中的 HTML 字面、换行、中文、emoji 正确。恢复 720 宽时布局和内容保留；Ctrl+F 再次全选，Escape 从输入框关闭并退出夹具。
- UIA 有时落后一帧或返回辅助名称作为 selected_text；以刷新后的截图核对实际可见内容，不把该文本当作 SQL/字段值证据。200 字段、超长查找词和元数据边界由自动化矩阵验证，未声称实际桌面逐一输入。

## 交付与限制

最终自审修正一个弱负例：类型不参与搜索应输入夹具真实的 `OTHER`，而非夹具没有的 `VARCHAR`。仅改测试输入后，76 项定向回归通过（16s，exit 0），生产源码/样式/已验收镜像未改。检查身份保留、失选清空、超限拒绝、键盘范围、无外部引用和文档边界；无剩余阻塞发现，`git diff --check` 通过。所有桌面夹具已正常退出，最后 list_apps 未返回本轮应用。

按本机约定采用测试技能的 Java 行为矩阵和断言检查，未套用通用多代理/状态文件流程。Computer Use 仅用于隔离合成窗口，实际桌面证据单列。代码差异只涉及行详情 UI 与测试/说明，不增加依赖、持久化、网络或主工具栏按钮。不推送、打 tag 或发布；本地通过不代表远端 CI、安装升级、真实用户耗时或正式发布 gate 完成。
