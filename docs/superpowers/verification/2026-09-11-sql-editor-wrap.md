# SQL 编辑器显示换行验收

基线 main `beb1c55`，独立 worktree / 分支 `codex/sql-editor-wrap`；[设计与实施顺序](../specs/2026-09-11-sql-editor-wrap.md)。
使用 code-testing-agent 技能按本次显示开关的小范围补齐行为矩阵，沿用 JUnit 5、FxUiTestSupport、独占临时目录和 DraftConnectionProbe；未扫描或改写 `.testagent/`。

## 要求与证据

| 要求 | 精确测试 |
| --- | --- |
| 默认不折行；显式开关只改变视图，保留长行/Unicode、反向选区、逻辑行列及执行范围 | `SqlEditorScopeBarTest.wrappingChangesOnlyTheViewAndKeepsLogicalPositionAndExecutionSelection` |
| 空文档、单行、多行/尾部空行、Tab/Unicode、无空格文本，正向选区及既有撤销/重做不变 | `wrappingPreservesForwardSelectionAndExistingUndoRedoHistory` |
| 每个编辑器独立；新编辑器仍默认关闭；不写快捷键设置 | `wrappingIsPerEditorAndNewEditorsKeepTheDefault` |
| 守卫拒绝、禁用、再次允许和关闭后旧动作安全；复选状态不能假报切换 | `blockedDisabledAndClosedControlsDoNotChangeTheViewAndCanReopenBeforeDisposal` |
| 真实 SQL 文件标签保持混合物理换行、路径、干净/脏状态、目标连接和执行范围，不创建会话；四类数据库副作用为 0 | `SqlEditorWrapIntegrationTest.wrappingKeepsPhysicalFileTextDirtyStateConnectionAndExecutionRangeUnchanged` |
| 折行时“跳转行”仍定位逻辑行；不修改文件 | `goToLineWhileWrappedUsesLogicalLinesAndDoesNotEditTheFile` |
| 真实编辑器连接准入关闭、资源关闭和任务作用域关闭后，旧开关不能改变视图 | `closingEditorRejectsOldWrapAction` |
| 明暗主题 880 / 640 / 480px，开关完整可见、可键盘聚焦，查找/替换与底栏不遮挡正文 | `SqlEditorUsabilityTest.openFindBarWrapsWithoutClippingControlsOrCoveringTheEditor` |
| 既有跳转行与替换不退化 | `SqlGoToLineBarTest` / `SqlEditorReplaceIntegrationTest` |

## 自动化

首次单例运行被 AWT 判定为 headless，JUnit XML 明确为 1 项跳过，不能算通过。只对本轮构建进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后强制重跑，实际失败于“缺少可见开关”的断言（exit 1）；随后实现新开关。

定向命令 exit 0，21s；JUnit XML 为 81 项全部通过、0 跳过/失败/错误：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.fx.SqlEditorScopeBarTest --tests com.datacube.fx.SqlEditorWrapIntegrationTest --tests com.datacube.fx.SqlEditorUsabilityTest --tests com.datacube.fx.SqlGoToLineBarTest --tests com.datacube.fx.SqlEditorReplaceIntegrationTest --no-daemon --console=plain
```

全量与开发镜像命令：

```powershell
# JAVA_TOOL_OPTIONS 另加入本轮独占临时目录的 Windows ASCII 短路径 java.io.tmpdir。
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- exit 0，1m 55s；JUnit XML 汇总 1,990 项：1,987 通过、0 失败/错误，3 项既有 live 测试跳过（Redis 1、Schema Diff 2）。所有本次相关测试实际执行，无 headless 跳过。
- `jlink` / `jpackageImage` 实际执行。保留原有 unchecked 测试编译提示和 `JAVA_TOOL_OPTIONS` 导致的工具探测/JEP 493 提示，未隐藏输出。
- `jimage list` 确认正常镜像包含 `SqlEditorScopeBar`，未包含 `DesktopFixture` / `DraftConnectionProbe`；正式 cfg 仍为 DataCubeFx 入口。
- 桌面验收使用 `build/desktop-fixture-image` 副本，patch-module 复用既有 `TableSelectSqlDesktopFixture`，设置独占 `build/select-desktop-profile`，不修改正式镜像、fixture 源码或真实 profile。
- 上轮 Computer Use 启动副本返回 `GetCursorPos failed: 拒绝访问。 (0x80070005)`，当时按技能要求停止桌面输入并请求解锁。后续“继续推进产品”时成功启动，以下实际窗口验收已完成；未因桌面结果改动生产代码或测试。
- 差异自审未发现本轮范围内的阻断代码问题，`git diff --check` 通过。源码与上述通过的全量构建一致，本轮完成桌面记录后本地提交并快进 main。

## 实际桌面补验

- Computer Use 仅操作本 worktree 的隔离镜像，窗口为“DataCube - SELECT 合成验收”，合成 PostgreSQL / Oracle 节点已加载。右键 PostgreSQL 表生成被动 SELECT 标签，默认不换行；未点击执行、执行计划、事务或真实连接。
- 在可见 SQL 光标处追加一段带中文的长合成注释，不替换原 SELECT。未折行时需要水平滚动；Ctrl+Shift+Home 建立 306 个 UTF-16 单元的反向选区，底栏为“行 1 · 列 1 · 选中 306”，按钮为“执行选中 (F5)”。
- 点击“自动换行”后第二个逻辑行折成多条显示行，行号仍只有 1、2；选区长度、光标行列、执行范围、待绑定 Demo PostgreSQL 目标和未保存标记均保持不变。
- Space 可以关闭折行；Shift+Tab 回到“跳转行”，Tab 再回到复选框，Space 重新开启。每步截图核对焦点边框与开关状态，没有把键盘输入送进 SQL。RichTextFX 的辅助功能焦点仍可能报告左栏查找框，本轮以可见编辑光标、行列变化及实际控件反馈确认目标。
- 右键 Oracle 视图生成第二个标签，其“自动换行”默认关闭；切回 PostgreSQL 标签，原开关、长文本、306 字符选区与目标连接均保留。新标签未继承其他标签的显示偏好。
- 将主分隔条右移，SQL 区缩至约 500px；暗色与亮色主题下长行随宽度折行，开关文字完整可见，底栏没有覆盖正文。超出编辑区高度的显示行由垂直滚动处理；未将视觉折行误报为新增文本行。
- 聚焦合成 SQL 后一次 Ctrl+Z 撤回追加的长注释，恢复原两行 SELECT、未保存星号消失，“自动换行”仍勾选。这是实际撤销路径验证，不仅是属性断言；重做与物理文件换行保留由自动测试覆盖。
- 使用正常窗口关闭流程退出，后续窗口列表确认隔离实例已关闭。未保存截图文件，证据来自本轮工具返回的窗口截图；没有读取真实历史、业务文件、原剪贴板或连接凭据。这不是数据库执行、安装升级或远端 CI 验收。

## 审查边界

- 生产改动仅底栏 `CodeArea.setWrapText` 及视口跟随光标请求、关闭守卫接入。未调用文本替换、光标/选区设置、连接/provider、文件或剪贴板 API。
- 底栏由原有关闭流程清理；不新增计时器、线程、外部监听或持久化字段。不扩大到 DDL、结果区和全局偏好。
- 本轮只本地提交并快进 main；不推送、打 tag 或发布。未读取真实连接、SQL 历史、业务文件或原剪贴板。
