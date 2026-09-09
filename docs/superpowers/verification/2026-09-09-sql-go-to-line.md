# SQL 跳转到行验证

基线 main `9a1e4a1`；独立 `codex/sql-go-to-line`。只补本地导航，不执行 SQL、不访问真实数据库或配置。
设计见 [跳转到行](../specs/2026-09-09-sql-go-to-line.md)。

## 要求与证据

| 要求 | 精确测试 |
| --- | --- |
| 有效一基行号、前导零、首尾空格、int 上界；非法/空/溢出不猜测目标 | `SqlGoToLineBarTest.parsesOnlyAnExistingOneBasedLine` / `rejectsInvalidNumbersWithoutOverflowOrClamping` |
| 首行/中间空行/Unicode 行/末尾空行，定位到行首并折叠选区，不新增编辑 | `SqlGoToLineBarTest.navigationCollapsesSelectionAtTheRequestedLineStartWithoutAnEdit` |
| 空 SQL 仍可定位第 1 行 | `SqlGoToLineBarTest.emptyDocumentStillHasANavigableFirstLine` |
| 越界 Enter 不定位，取消按钮/Esc 保留反向选区，重新打开重置为当前行 | `SqlGoToLineBarTest.cancellingInvalidInputPreservesTheReverseSelection` |
| 文本缩短/增长后按最新段落数重新校验 | `SqlGoToLineBarTest.documentChangesRevalidateAgainstTheLatestLineCount` |
| 只读可导航，忙/禁用/关闭后不能生效，关闭后监听不再更新 | `SqlGoToLineBarTest.readOnlyNavigationIsAllowedButBusyDisabledAndClosedActionsAreRejected` |
| 既有撤销和重做记录没有被定位动作清除或插入其他编辑 | `SqlGoToLineBarTest.navigationLeavesExistingUndoAndRedoHistoryIntact` |
| Ctrl+G/改绑/底栏入口、范围反馈、查找替换互斥、文件 dirty/字节不变、另一编辑器不受影响 | `SqlEditorUsabilityTest.goToLineRebindingAndFindSwitchingPreserveFileAndEditorIsolation` |
| 打开定位先隐藏实际补全弹层，不接受候选 | `SqlEditorUsabilityTest.findAndReplaceDismissCompletionBeforeTakingFocus` 新增 goto-button/goto-key 分区 |
| 明暗 480/640/880px 布局不遮挡正文或底栏、不裁切输入和按钮 | `SqlEditorUsabilityTest.goToLineWrapsWithoutClippingOrCoveringSql` |
| 旧 Ctrl+G 查找覆盖仍保持优先级；执行范围与查找/替换原行为保留 | 原 `SqlEditorUsabilityTest.findEntryAndReboundShortcutStayInsideTheUnboundEditor`，ScopeBar 及其他 Usability 回归 |

最终定向命令，65 项全部通过，11s，exit 0：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlGoToLineBarTest --tests com.datacube.fx.SqlEditorUsabilityTest --tests com.datacube.fx.SqlEditorScopeBarTest --no-daemon --console=plain
```

## 开发与审查记录

- 首次主模块编译失败：`getParagraphs()` 的声明类型为 ReactFX LiveList，直接调用其方法跨越现有模块边界。改为使用 JavaFX 的 `ObservableList<?>` 公共接口，不增加依赖或放开模块读取范围。
- 首次 UI 回归失败：新布局测试在 CSS/skin 建立前查找底栏按钮，修正测试初始化顺序；文件加载本身已有撤销状态，改为校验定位前后保持一致，并新增真实 undo/redo 行为测试，没有更改文件加载语义。
- 新定位组件同步运行，无后台待发布候选；提交瞬间再次检查关闭/文件忙/禁用和最新行数。构造失败与 FX 最终关闭均释放段落监听，不在资源关闭线程访问 FX。
- 状态底栏只接入可选导航节点；主工具栏按钮数量和执行准入、事务、文件保存规则不变。
- 根目录用户 `.testagent/` 未读取、修改或暂存。本轮不自动推送、tag、发布，不声明远端 CI 或真实用户指标。

## 全量与打包

独占临时目录的 Windows 短路径通过 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=...` 传给构建：

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

1m59s，exit 0；182 个测试类 XML 汇总 1,871 项，1,868 通过、3 原有 live 跳过、0 failures/errors。
`jlink` 和 `jpackageImage` 实际执行。保留既有 unchecked 测试提示；打包工具对 `JAVA_TOOL_OPTIONS`
stderr 的噪声提示不代表失败，最终镜像实际构建成功。版本 `0.0.0` 仅为隔离开发镜像，不是发布号；
现有 `AppVersion.isDev()` 使该镜像跳过自动更新检查。

## Windows 实际窗口

Computer Use 技能控制本轮镜像 `build/jpackage/DataCube/DataCube.exe`；只对忽略目录内的 cfg 添加
`-Duser.home=.../build/desktop-profile`，没有改动真实用户配置。
通过原生文件选择器打开本轮合成 `build/desktop-fixtures/跳转行 验收.sql`，包含 80 行合成语句与第 81 个末尾空行。

1. 文件打开后没有未保存星号；离线连接提示和禁用执行状态保留。底栏“跳转行…”默认选中当前第 81 行，范围说明为 1–81。
2. 输入 1、Enter 后定位栏关闭，编辑器滚动到首行，光标显示行 1 / 列 1。
3. Shift+End 选中首行 40 个单元；Ctrl+G 默认选中当前行号 1，SQL 选区与“执行选中”保留。
4. 输入越界的 82，“定位”禁用，Enter 不移动原选区、不关闭输入；Escape 取消后仍为行 1 / 列 41 / 选中 40。
5. 再次 Ctrl+G 重置输入为 1；输入 75 并点击“定位”，视口滚动至可见第 75 行，光标显示行 75 / 列 1，选区折叠，按钮恢复“执行全部”。
6. 右侧编辑区缩窄至约 480px；定位栏在明暗主题下均可读，输入、按钮、说明、底栏与正文没有重叠。
7. 点击查找会收起定位栏；从查找输入按 Ctrl+G 切回定位，默认行号为 75，不修改 SQL。取消按钮关闭定位并保留行 75 / 列 1。
8. Alt+F4 正常退出；再次列举目标镜像窗口为空。全过程未选择连接、执行 SQL、保存脚本或打开真实历史。

合成 SQL 文件验收前后 SHA-256 一致：
`D919BB31C792022B183B94730BF8F507589B5C1AA6F93902FE530DEE545D2413`。
快捷键改绑、空文件、正文变化和关闭后动作由自动测试覆盖；实际桌面仅声明以上已操作项目。
没有验证真实数据库执行、安装器升级或远端 CI。

## 本地交付边界

交付前核对根目录仍为 main `9a1e4a1`，仅有原有未跟踪 `.testagent/`。
本轮源码、测试和文档使用显式路径提交，再快进合并本地 main；保留 worktree 与其他分支。
推送、tag、Release 不包含在本轮交付动作中。
