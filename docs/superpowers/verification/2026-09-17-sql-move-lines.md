# SQL 上移行 / 下移行验收

基线 `4f82a22`，分支 `codex/sql-move-lines`；[设计](../specs/2026-09-17-sql-move-lines.md)。本轮开始时已通过 GitHub API 确认 `v3.2.7` 发布工作流成功，绿色版与安装版齐全；不将该发布状态套用到本轮增量。

测试技能用于有界动作的直接回归，遵照项目约定不增加通用流水线工件或子代理。`.testagent/` 不读取、不修改、不暂存；使用合成临时文件、独立设置及内存连接探针，不触碰真实业务数据。

## 行为与证据

| 行为 | 直接测试 |
| --- | --- |
| 上下旋转行正文、部分/反向选区、行首/行尾、末尾空行、Unicode | `SqlMoveLinesTest.rotatesOnlyBodiesAndMapsExactSelection`（11 种） |
| 空文件、单行、首尾及无末尾空行时全选无操作 | `SqlMoveLinesTest.boundaryIsNotAMutation`（8 种） |
| 相同正文交换只移动选区 | `SqlMoveLinesTest.identicalBodiesMoveSelectionWithoutInventingTextEdits` |
| 9,999 / 10,000 / 10,001 所选行，交换邻行不计入所选上限 | `SqlMoveLinesTest.selectedLineLimitDoesNotCountExchangedNeighbor` |
| 8 Mi 前 / 恰好 / 超限及非法输入 | `SqlMoveLinesTest.textLengthLimitRejectsWholePlanOnlyAboveBoundary`、`rejectsInvalidPositionsUnnormalizedInputAndMissingDirection` |
| 打字之间单独一步撤销/重做，反向选区和补全抑制范围 | `SqlMoveLinesActionsTest.moveIsOneUndoStepBetweenTypingAndPreservesReverseRange` |
| 两方向键位可改绑、持久化、精确修饰键、只作用编辑器 | `SqlMoveLinesActionsTest.bothShortcutsAreEditorScopedExactAndCanBeRebound` |
| 禁用/只读/关闭，守卫或回调变化，超限不改 SQL 或撤销 | `SqlMoveLinesActionsTest.unavailableActionRejectsBothButtonsAndKeys`、`preparedPlanCannotApplyAfterGuardOrSnapshotChanges`、`excessiveRangeIsRejectedWithoutChangingSelectionOrUndo` |
| 首尾/相同内容不新增撤销 | `SqlMoveLinesActionsTest.boundaryAndIdenticalBodiesDoNotCreateUndo` |
| 实际工具栏上下入口 | `SqlEditorMoveLinesIntegrationTest.toolbarOffersBothDirectionsWithoutConnecting` |
| 混合物理换行、反向选区、执行范围、文件身份、脏状态、被动只读目标和撤销/重做 | `SqlEditorMoveLinesIntegrationTest.physicalSeparatorsFileIdentityReverseRangeAndPassiveTargetSurviveUndoRedo`（上下） |
| 含限定符文本移动不请求补全、不改变执行选中范围 | `SqlEditorMoveLinesIntegrationTest.movingQualifiedTextDoesNotRequestCompletionAndPreservesExplicitExecutionRange` |
| Pane 关闭/忙碌/禁用守卫及草稿冻结 | `SqlEditorMoveLinesIntegrationTest.paneGuardsRejectBothDirectionsAndOldKeyboard`、`movementEntersDraftCheckpointButFreezeStopsLaterEdits` |
| 600 / 880 宽明暗主题，独立操作组与完整标签 | `SqlEditorMoveLinesIntegrationTest.bothDirectionButtonsFitWrappedToolbarWithReadableLabels` |
| 480 / 640 / 880 宽明暗工具栏全部 18 个操作完整可见 | `SqlEditorUsabilityTest.primaryActionsRemainReadableAndInsideTheEditor` |
| 撤销/重做不触发候选或成员请求，旧队列失效，显式补全仍可用 | `SqlAutoCompleteFocusTest.movingLinesAndUndoRedoDoNotRequestAutomaticCompletion`、`undoCancelsAnOlderQueuedCompletion` |

## 自动验证

Gradle 均使用 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`、`--no-daemon --console=plain`。

- 初次入口测试因 `Node` 传给 `Scene` 缺少 `Parent` 转换而编译失败（34 秒），已修正夹具；该次不作为行为 RED。
- 修正后单个 `SqlEditorMoveLinesIntegrationTest` 16 秒 exit 1，明确因工具栏缺少上移入口断言失败。
- 实现后三个新增测试类定向运行 13 秒 exit 0；补充真实 Pane 回归后，三类加既有 `SqlEditorDuplicateIntegrationTest` 12 秒 exit 0。
- 首次全量 1 分 58 秒 exit 1：2,842 项中 6 项失败、3 项既有真实连接测试跳过。6 项均为 `SqlEditorUsabilityTest` 的工具栏总数仍断言 16，新入口使其成为 18；同步总数并增加两个明确入口断言，保留 480 / 640 / 880 宽明暗主题下的全部按钮边界与标签检查。
- 同步后全量 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0` 2 分 25 秒 exit 0：2,839 通过、3 既有 live 跳过，buildSrc 8 通过（本次 UP-TO-DATE）。三个新增类共 57 项（27 / 14 / 16）。打包器仍输出已有 `Picked up JAVA_TOOL_OPTIONS` 探测及 JEP 493 提示，但最终镜像任务成功。
- 首轮合成桌面已验证上下快捷键、反向选区、单步撤销/重做和明暗窄窗；在亮色窄窗撤销单行时发现候选弹窗。新增 `SqlAutoCompleteFocusTest.movingLinesAndUndoRedoDoNotRequestAutomaticCompletion`（普通/限定符）及 `undoCancelsAnOlderQueuedCompletion`，10 秒 RED，3 项均因额外候选请求失败。共享补全监听增加撤销/重做回放保护，失效旧请求，不改变显式补全。
- 修复后完整 `SqlAutoCompleteFocusTest` 加动作/真实 Pane 行移动回归 14 秒 exit 0，包含正常输入、显式补全和弹窗接受的既有正向用例。
- 最终再次执行 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`，2 分 48 秒 exit 0：2,845 总项，2,842 通过、0 失败/错误、3 既有 live 跳过；buildSrc 8 项已有成功结果、任务 UP-TO-DATE。相对基线新增 60 项回归。

## 隔离桌面验收

通过 computer-use 在 `build/sql-move-lines-desktop-image` 生产镜像副本内补丁加载既有 `SqlDuplicateDesktopFixture`（窗口沿用旧标题“重复行合成验收”）。只在副本修改启动配置，`user.home` 指向新建 `build/duplicate-desktop-profile`；仅包含固定合成 SQL，无注册连接、原文件绑定或草稿绑定。它是真实 `SqlEditorPane`，不是完整 AppShell/真实数据库验收；真实文件换行、草稿保存和忙碌守卫由前述集成测试覆盖。

- 最终镜像暗色宽窗：Alt+↓ 后首两行整体下移，反向选区 `anchor=27/caret=2` → `35/10`，Alt+↑ 回到 `27/2`，选中 25 单元保持；鼠标入口与首行边界在首轮镜像也已验证。
- 暗色、亮色窄窗：使用夹具 640 宽入口；上移/下移组自动换行，标签完整、编辑区可用，未绑定连接的执行始终禁用。
- 最终亮色窄窗：末行列 7 上移至上一行列 7；原生 Ctrl+Z 一次恢复原行序，Ctrl+Y 一次重做。曾出现补全的同一路径复验及后续稳定截图均无候选弹窗；正常/显式补全的正向行为由 Stage 自动测试覆盖。
- 无 SQL 执行、网络连接、系统剪贴板或真实配置访问。验收结束以 Alt+F4 正常退出。
- 未补丁的生产 runtime 提取 `com.datacube` 后含 820 项资源、805 个 class，包含 6 个行移动 class，无 Fixture 类；补丁夹具不进入分发包。

本轮只改变文本编辑；未执行 SQL、访问真实数据库、改写用户文件或使用系统剪贴板。未宣称真实用户效率、完整业务连接兼容性或本轮远端发布完成。
