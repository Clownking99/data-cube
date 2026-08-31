# 工作区桌面续验与自动补全缺陷

## 环境和隔离

本轮沿用已标记profile `C:/Users/hetia/AppData/Local/Temp/datacube-workspace-ui-3a97aa5b837644eaaad0636a6eeed5cf`，未播种第二次、清空或读取真实用户配置。桌面入口为忽略的SqlWorkspaceAcceptanceLauncher，真实AppShell/ThemeManager/退出协调器，省略外层更新检查；不等同安装版完整入口验证。

验收前功能worktree的src/test/build与main `109df89`差异为0；main只比已推送c8c53aa多文档。launcher SHA256为`FF6D0EF653FC9D60418D0F8A1C0C9CA662B560E052C253EB5125A7D9783C1A14`，init为`DF8342E190AFA8BA41C9CED4B9441E3C8B15EB7EB341BE62CA07405D334D83E6`。本轮不再查询或重跑已完成CI，不推送/tag/发布。

## 实际桌面结果（修复前）

使用Computer Use，仅对标题为“DataCube SQL工作区隔离验收”的当前窗口输入。每次输入后重取控件树及截图，过渡动画后再观察；控件索引点击曾没有触发目标动作，改用最新截图中可见按钮坐标后成功。没有用其他桌面自动化通道，也没有操作其他应用。

| 项目 | 观察结果 |
| --- | --- |
| 首次启动 | 暗色启动页、没有自动SQL标签、连接树为空 |
| 恢复入口 | 管理页稳定显示共2/可用2/缺失0，当前布局尚未开始，重试保存禁用 |
| 显式恢复 | 显示已打开2/已定位0/缺失0/失败0；当前布局已保存 |
| 重复恢复 | 已打开0/已定位2/缺失0/失败0；仍只有两张标签 |
| 顺序/当前草稿 | 第一张beta、第二张alpha；恢复后当前为alpha |
| 内容/选区 | 两份合成SQL及synthetic_schema可见；alpha选中偏移3至9、beta为2至8对应的可见文本。截图只证明选区范围，精确anchor/caret方向仍引用此前真实控件自动化断言 |
| 安全状态 | 两个编辑器均未绑定连接、尚未创建专用会话、执行按钮禁用；本轮从未点击执行或连接。此前四探针自动化证据与本轮可见状态分开 |
| 主题 | 暗→亮→暗成功；亮色编辑器、选区、管理页数量/状态/隐私说明可辨认 |
| 正常退出 | 点击主窗口标题栏关闭，session9444/PID20648，native exit0，BUILD SUCCESSFUL in 5m19s；没有强制结束 |
| 同profile重启 | session59636/PID26420，启动仍不自动显示SQL；显式恢复再次打开2份，alpha当前、beta-alpha顺序及可见选区保留 |
| 第二次正常退出 | 标题栏关闭，native exit0，BUILD SUCCESSFUL in 2m56s；没有强制结束 |

两次启动各只有已知classpath JavaFX配置capture；终端未出现新的异常诊断。未改变保护开关、记录偏好、清空或删除；相关操作继续使用已有合成自动化证据。

## 新发现：被动恢复弹出自动补全

在两次新JVM的首次显式恢复后，出现SELECT补全浮层，覆盖SQL草稿管理页。期间没有任何SQL文本输入。第二次点击“恢复工作区”或关闭管理页可让浮层消失，但这不构成修复。

代码定位：SqlEditorPane恢复时setSqlText调用CodeArea.replaceText，随后SqlWorkspaceRecoveryTabs恢复选区。SqlAutoComplete的textProperty监听无条件排队maybeShow，执行时读取已经恢复的caret前缀（alpha为sel，beta为se），未验证编辑器焦点，最终显示弹窗。现有refresh则在延迟回调中检查area.isFocused。修复设计和失败测试计划见[焦点设计](../specs/2026-08-31-sql-completion-focus-design.md)与[实施计划](../plans/2026-08-31-sql-completion-focus.md)。

root修复前独立定向基线：session71066，JDK25、scoped非headless、`test --tests '*SqlEditorDraftRecoveryTest' --tests '*SqlWorkspaceRecoveryTabsTest' --rerun-tasks --no-daemon --console=plain`，native exit0/26s；实际XML为7+14=21通过，0失败/错误/跳过。原SqlEditorResultFilterContractTest unchecked提示仍存在。

## 焦点修复与回归

修复RED已由root独立核实：保存的`.superpowers/sdd/completion-focus-task-1-red.xml`时间2026-08-31T13:03:55.758Z，1test/1failure/0errors；真实CodeArea未聚焦时replaceText+selectRange之后，队列屏障断言候选请求应为0、实际1。此时SqlAutoComplete生产diff为0；这是产品行为失败，不是编译失败或超时。确认后才允许实现焦点检查。

扩展RED为7项中3项失败，分别是未聚焦恢复、未聚焦替换后回调前获得焦点、聚焦替换后回调前丢失焦点；另4项正常输入/手动补全/Tab及Enter接受保持旧行为通过。root实际读取扩展XML并核对三个失败均为候选请求应0而实际1。

源码提交`a901811e5c95809eb5ea5fe86f9d501561509b6a`只修改SqlAutoComplete和新增SqlAutoCompleteFocusTest。前者仅给文本监听的排队前与回调内增加焦点检查；未改maybeShow、手动快捷键、刷新、存储或恢复逻辑。后者使用真实Stage/Scene/CodeArea、焦点监听及队列屏障，检查真实Popup与文本，不引入新依赖或生产测试接口。

实施代理完整回归session49446，native exit0，1m35s、8tasks全部执行。root独立读取实际完整XML：161suites，1567tests，1564passed，0failures/errors，3既有live skipped（Redis standalone、Oracle/PostgreSQL SchemaDiff）。原unchecked提示仍存在；详细命令见`.superpowers/sdd/completion-focus-task-1-report.md`。随后root独立重跑`test --tests '*SqlAutoCompleteFocusTest' --no-daemon --console=plain`，session70953 exit0/10s，实际XML7tests、0失败/错误/跳过，时间2026-08-31T13:11:31.136Z；不是重新执行全量的声称。

独立任务审查completion_focus_review：Spec compliant/Quality Approved，0Critical/Important/Minor；报告列出的RED顺序、进程退出及XML核验事项已由root以上证据独立补足。

修复后桌面session62756/PID22572沿用相同标记profile，helper两份hash仍不变。启动不自动恢复；显式恢复最终显示打开2/定位0/失败0，当前布局已保存，立即截图和随后稳定观察均无SELECT补全浮层。重复恢复最终打开0/定位2/失败0，仍无浮层；关闭管理页后alpha文本、选区及两张标签保留，仍未绑定连接。只用窗口输入，没有键入或执行SQL，没有操作真实配置。

## 尚未关闭的边界

修复后通过标题栏正常关闭，session62756 native exit0/3m36s，9tasks中2执行、7up-to-date；未强制结束，profile及全部合成证据保留。没有留下本轮拥有的验收进程。

- 对话框键盘Tab输入没有给出可靠焦点迁移证据，不能记作完整键盘验收通过。此前启动入口Space打开的已观察结果保留。
- 未在本轮桌面手动执行单标签关闭、拒绝退出和隐私/清空路径；先前独立JVM矩阵覆盖这些行为，不冒充鼠标键盘覆盖。
- 未启动带更新检查的正式分发入口、安装或访问真实数据库。
- 本轮补全缺陷已完成任务回归、任务审查与桌面复验；上述键盘/发行入口边界仍未完成，不据此把整个P2桌面矩阵勾选通过。P3仍未开始。
