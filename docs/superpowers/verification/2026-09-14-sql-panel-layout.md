# SQL 面板布局验收

## 范围

独立 `codex/sql-panel-layout`，基线 main `9fd028c`。设计见[面板布局](../specs/2026-09-14-sql-panel-layout.md)。
生产变更为独立 SqlPanelLayout、SqlEditorPane 菜单/生命周期/显式编辑入口接入，以及 SqlFindBar 的无焦点隐藏重载。
不新增依赖、网络、持久化字段、查询或源文件保存。.testagent/ 未读取、修改或暂存。

## 需求与证据

| 需求 | 测试证据 |
| --- | --- |
| 三模式真实入口 | `SqlPanelLayoutIntegrationTest.layoutMenuOffersThreeModesOnRealSqlFileTab` |
| 保留节点、连续切换与分隔位置 | `SqlPanelLayoutTest.repeatedSinglePanelSwitchesRetainNodesAndLastDraggedDivider` |
| 文件物理换行/脏状态/撤销/选区，结果列重排/隐藏/排序/焦点选择不变 | `SqlPanelLayoutIntegrationTest.switchingPreservesFileUndoSelectionAndResultProjectionWithoutDatabaseAccess` |
| 查找、替换、跳转行、工具栏查找显式恢复编辑区 | `SqlPanelLayoutIntegrationTest.explicitEditingEntryRevealsEditorAndRetainsText`（4 个入口） |
| 隐藏操作条、保留查询词，新结果不抢布局 | `SqlPanelLayoutIntegrationTest.hidingEditorClosesBarsButKeepsQueryAndIncomingResultsDoNotStealLayout` |
| 隐藏取消过期替换，即使成功/失败回调已排队 | `SqlReplaceBarTest.staleCandidatesNeverOverwriteNewEditorState` 新增 `hideWithoutFocus` 场景 |
| 关闭/任务域/文件忙/禁用/准入拒绝迟到动作 | `SqlPanelLayoutIntegrationTest.staleActionsAndEditorShortcutsCannotReopenBlockedPane`（6 种状态） |
| 草稿冻结保持布局与文件 | `SqlPanelLayoutIntegrationTest.frozenDraftRejectsLayoutWithoutDirtyingFile` |
| 临时守卫恢复、菜单选择修正、幂等关闭 | `SqlPanelLayoutTest.staleMenuActionsRespectGuardAndCanRecoverWithoutChangingSelection`、`disabledParentAndIdempotentCloseRejectLateActions` |
| 查询运行期间允许纯显示切换 | `SqlPanelLayoutIntegrationTest.displayOnlySwitchIsAllowedWhileQueryIsRunning` |
| 执行计划内容与选区不丢失 | `SqlPanelLayoutIntegrationTest.executionPlanContentIsNotReplacedByLayoutChanges` |
| 单区获得空间、菜单可达、分隔位置恢复 | `SqlPanelLayoutIntegrationTest.singlePanelUsesFreedSpaceAndMenuRemainsReachable`（480/640/880 × 明暗） |
| 工具栏不裁切 | `SqlEditorUsabilityTest.primaryActionsRemainReadableAndInsideTheEditor` 扩展为包含布局的 15 项动作 |
| 实际窗口跨 pulse 恢复，之后仍可手动调整 | `SqlPanelLayoutIntegrationTest.displayedWindowRestoresDividerAfterNativeLayoutPulses`（菜单/F/H/G），实际 Stage 等待 3 次布局 pulse，不使用固定睡眠 |
| 快速切换、迟到 pulse 与关闭 | `SqlPanelLayoutTest.queuedPulseCannotOverwriteNewerModeRestoreOrClosedLayout` |

测试技能用于有针对性的状态与边界检查；按项目约定不增加通用流程工件。数据库四项探针断言为零，源 SQL 文件逐字不变。

## 自动验证

命令环境 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，Gradle 参数 `--no-daemon --console=plain`。

1. 真实菜单 RED：`test --tests com.datacube.fx.SqlPanelLayoutIntegrationTest`，1 分 18 秒 exit 1，唯一失败为菜单不存在；旧版本缺少入口已证实。
2. 初始定向 63 项有 6 项失败：新增窄宽测试重复把同一 root 放入两个 Scene。修正夹具为先解除旧 Scene root，不修改生产布局语义；重跑 63 项通过，18 秒 exit 0。
3. 加入草稿冻结、计划内容和旧替换保护：`test --tests com.datacube.fx.SqlPanelLayout*Test --tests com.datacube.fx.SqlEditorUsabilityTest --tests com.datacube.fx.SqlFindBarTest --tests com.datacube.fx.SqlReplaceBarTest`，18 秒 exit 0；XML 87 项通过，无失败/跳过。
4. 第一轮全量构建在会话中断后没有完成记录；恢复时无存活 Java 进程、无测试 XML 或完成镜像，不算通过，重新运行完整命令。
5. 重跑 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`，2 分 17 秒 exit 0；XML 2548 项 / 2545 通过 / 3 既有 live 跳过 / 0 failures/errors，buildSrc 8 项通过。保留既有 unchecked、JAVA_TOOL_OPTIONS 工具探测和 JEP 493 提示，不称无警告。
6. 首轮桌面发现 divider 在下一次 pulse 回退。新增真实 Stage 回归 RED：10 秒 exit 1，期望 0.6096，实际 0.3722。
   查阅 [OpenJFX 25 SplitPaneSkin 源码](https://raw.githubusercontent.com/openjdk/jfx/jfx25/modules/javafx.controls/src/main/java/javafx/scene/control/skin/SplitPaneSkin.java)，确认重挂内容会重建 divider 并在布局中修正位置。
   新逻辑在下一次 post-layout pulse 后恢复一次；切换/关闭/场景分离取消，代际检查拒绝旧回调，不持续绑定分隔条。
7. 修复后单个真实窗口回归及旧定向用例通过，15 秒 exit 0；扩展菜单/F/H/G 和迟到 pulse 后，最终定向 92 项通过，17 秒 exit 0。
8. 最终重新 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`：2 分 27 秒 exit 0。
   XML 2553 项 / 2550 通过 / 3 既有 live 跳过 / 0 failures/errors；buildSrc 8 项通过（本次 up-to-date）。
   jlink/jpackageImage 实际执行。生产入口仍为 DataCubeFx，runtime 含 SqlPanelLayout，不含 DesktopFixture/DraftConnectionProbe。
   最终 modules SHA-256：`9402440A9404D65A1A6FA3118A18E74D46631815580D235A5C43103BC5DDCB07`。

## 桌面与集成

首轮使用新镜像的独立副本，仅 patch 单个桌面夹具类，user.home 为 build/layout-desktop-profile。无注册连接、无主应用更新服务。
原生鼠标验证仅看 SQL/结果扩大区域；合成编辑产生脏标记，结果区选择 Sample 3 后 Ctrl+F 恢复编辑；聚焦正文单次 Ctrl+Z 恢复原文与干净状态。
首轮分隔条恢复未通过并已据此修复，不能将首轮当作整体桌面通过。关闭后夹具 exe 存活数 0，源 SQL 混合换行文本逐字不变。

2026-09-15 完成修复后复验（本轮工作跨午夜）：

- 新生产镜像副本 `build/layout-fixture-v2/DataCube`，单独 fixture jar 与临时 profile；生产镜像未 patch。
- 原生拖动分隔条从约 y456 到 y596，再切换仅看结果。Ctrl+F 返回分屏后，查找条显示、分隔条恢复 y596；后续重新观察仍保持，不再回退。
- 隐藏编辑区时查找/替换条收起；从仅看结果分别按 Ctrl+H / Ctrl+G，可见替换/跳转行，分隔位置均保留。
- 暗色宽窗和 640 窄窗、亮色窄窗菜单可读；亮色连续“仅看结果 → 仅看 SQL → 上下分屏”，单区获得原另一半空间，返回恢复窄窗分隔位置。正文和 30 行结果保留。
- 工具一次返回截图缓存失效，重新观察后使用新控件索引切换主题；窗口被遮挡时只激活本夹具，未操作遮挡窗口。
- 正常关闭后精确夹具 exe 路径进程数 0；源 SQL 与初始混合 CRLF/LF 文本逐字一致，无自动保存/SQL 执行。

## 审查和集成

按测试技能逐项复查状态和边界断言，重点审查节点身份、单步撤销、迟到回调代际、关闭/场景分离清理及只恢复一次。
真实 Stage 跨 pulse 测试弥补了单 FX 回合 layout 检查的缺口；桌面技能帮助发现并复验分隔条回退，未弱化测试期待或删除失败场景。
实现提交 `f6e6fc6`（`feat: add SQL editor and result panel layouts`）已快进合并到本地 main。
合并时 main 与功能分支 Git tree 均为 `1149a3db8400fe544b663df9b1a897be19e022c4`；功能 worktree 干净。
main 合并后运行 `:buildSrc:test test`（与上方相同的五组定向过滤）及 `jpackageImage -PappVersion=0.0.0`，52 秒 exit 0。
XML 92 项全部通过，无失败/错误/跳过；buildSrc 8 项原已通过、本次 up-to-date；jlink/jpackageImage 实际执行。
main 镜像 modules SHA-256 与上方最终验收镜像完全一致，入口仍为 DataCubeFx，本地预览镜像已更新。
根目录仅保留既有未跟踪 .testagent/，未读取、修改或暂存；本轮不推送、不打 tag。

## 边界

布局不持久化，仅当前标签；返回分屏的比例仍受控件最小尺寸约束。隐藏区域保留在内存，不是关闭或取消查询。
没有增加水平分屏、浮动停靠、新快捷键或发布版本。合成数据验收不代表真实用户效率、真实数据库、安装升级或远端 CI 已通过。
