# SQL 历史找回验收

基线 `3e75c77`，分支 `codex/sql-history-retrieval`。[设计](../specs/2026-09-12-sql-history-retrieval.md)。范围限于既有 SqlHistoryDialog；AppShell 离线空会话载入路径和 SqlHistoryStore 文件格式不变。

## 行为证据

按本机协作约定，测试技能用于单个对话框的行为矩阵、Java/JUnit 约定和精确断言；没有新增依赖、覆盖率工具、通用流程文件或多代理步骤。下列方法均在 `SqlHistoryDialogTest`。

| 要求 | 自动测试 |
| --- | --- |
| 空历史计数、占位、预览和打开/清除禁用 | `initialCountDistinguishesEmptyHistory` |
| 独立快照、最新在前、完整 Unicode 多行只读预览 | `initialSnapshotSelectsLatestAndKeepsFullReadOnlyPreview` |
| 连接/schema/完整 SQL 大小写子串、空白、中文和正则字符按字面匹配 | `literalSearchMatchesAllThreeFieldsAndFullSql`（7 组） |
| 土耳其默认 Locale 不改变查找结果 | `searchIsIndependentOfTurkishDefaultLocale` |
| 200 条容量下找回末条、清除恢复完整原顺序及选择 | `fullHistoryCapacityCanFindOldestEntryAndRestoresOriginalOrder` |
| 索引移动仍保留原条目；被移除时首条回退；清除和全空白恢复 | `filtersRetainEntryNotIndexAndFallbackOnlyWhenRemoved` |
| 无匹配清空预览、禁用确认；Enter 不关窗，清除后恢复 | `noMatchClearsPreviewBlocksConfirmationAndClearRecovers` |
| 筛选框/列表 Enter 和按钮返回准确候选；此前没有结果 | `confirmationReturnsExactFilteredEntry`（3 组） |
| 筛选框/列表/预览 Esc 或取消按钮返回空 | `cancelFromAnySurfaceReturnsNoEntry`（4 组） |
| 初始焦点、↓ 进入列表保留候选、预览 Enter 不开、Ctrl+F 全选查询 | `keyboardFocusAndPreviewEnterAreSafe` |
| 列表空白、空单元格、右键双击和单击不打开旧选择 | `nonActivationClicksNeverReturnOldSelection`（4 组） |
| 主键双击返回被点击的条目，不返回之前选中项 | `primaryDoubleClickReturnsClickedCellNotPreviousSelection` |
| 浏览和确认不改历史文件字节或内存顺序/时间戳 | `browsingAndOpeningDoNotRewriteHistory`（临时文件） |
| 明暗 600/760 宽说明完整显示、控件宽度与主题；聚焦空筛选/空预览提示颜色可见 | `narrowAndWideLayoutsKeepInstructionsAndControlsVisible`（4 组），检查真实 Text 节点的 opacity 和亮度 |

## 运行记录

- 红灯：缺少计数状态，1 项失败，19s，exit 1。
- 扩展测试首次 31 项中 11 项失败，原因是未 show 的 SplitPane 项尚未进入 CSS lookup；调整测试夹具访问其 items。第二次剩 2 项为未显示对话框的 ButtonBar lookup，同样改为 lookupButton。没有改弱生产行为断言。
- 定向最终 32 项全通过（31 新用例 + 1 既有存储失败原子性回归），17s，exit 0：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*SqlHistoryDialogTest' --tests '*SqlHistoryStoreStrictTest' --no-daemon --console=plain
```

## 全量、镜像与桌面

- 首轮 `clean test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain` 通过，2m35s、exit 0。XML 共 2,377 项、2,374 通过、0 失败/错误、3 既有 live 跳过；此时尚未修复下面的桌面提示颜色问题。
- Computer Use 首轮在仅含合成条目的隔离镜像副本上确认：初始 3/3；输入不存在的词得到 0/3，候选与预览清空、打开禁用，Enter 保持弹窗。发现聚焦搜索框提示透明、空预览提示在暗色主题偏暗。
- 追加实际 Text 填充断言后 4 个明暗/宽度场景全部红灯（14s、exit 1，opacity 为 0）；将两个历史控件 ID 加入已有提示颜色规则，不改变其他控件。32 项定向再次通过（15s、exit 0）。
- 重建时一次 clean 因仍运行的合成验收启动页占用 DLL 失败（9s、exit 1，未进入测试）。通过标题栏正常关闭该隔离实例并确认无 DataCube 进程后重跑；没有结束其他应用或删除用户配置。

- 最终同命令重建成功，2m42s、exit 0：2,377 项，2,374 通过、3 既有 live 跳过（Redis 1、Schema Diff 2），0 失败/错误；jlink 和 jpackageImage 均执行。沿用 Windows ShortPath java.io.tmpdir 与非 headless 设置，不改构建配置；保留既有 unchecked、JAVA_TOOL_OPTIONS 探测和 JEP 493 提示，不称为无警告构建。
- `jimage list` 确认生产镜像包含 SqlHistoryDialog 及 EntryCell，不含 DesktopFixture / DraftConnectionProbe；生产 cfg 仍为 `com.datacube/com.datacube.DataCubeFx`。
- 桌面仅用新建 `build/history-fixture-image/DataCube` 开发镜像副本，patch 单个 SqlHistoryDesktopFixture 类并改副本入口；强制 `build/history-desktop-profile`，没有读取真实历史、注册连接或创建 SqlEditorPane/自动更新入口。夹具调用真实对话框并显示返回候选，不将此称为完整 AppShell/数据库工作流验收。
- 最终暗色宽窗：聚焦空筛选提示可见；双击列表空白仍停留原候选。Ctrl+F 返回筛选，输入不存在的词得到 0/3、禁用打开，空预览提示可读；清除恢复 3/3。输入 `orders` 命中 SQL 第二行并显示完整三行，↓ 进入列表、Enter 关闭并返回准确 Oracle 合成条目。
- 最终亮色 600 宽窗：筛选、按钮、计数和底部两行提示可读，长列表行可横向滚动；点击只读预览后 Enter 不关闭弹窗。左键双击第三条返回该条 `select '[.*]' as literal;`，不是原候选。
- 空快照实测 0/0、暂无 SQL 历史、打开/清除禁用，Esc 返回取消且没有候选。最后通过标题栏关闭夹具启动页，进程检查确认已退出。
- Computer Use 不支持右键双击（工具返回 unsupported），未绕过工具；该行为及空单元格/单击由自动 JavaFX 鼠标事件测试覆盖。Locale、容量、文件不改写和选择保留同样为自动测试证据，不混称为手工验证。

## 自审与交付边界

只增加对话框局部监听，不订阅持久化源或全局快捷键。过滤保存条目而非旧索引；打开禁用、预览默认 Enter 和单元格双击分别处理。使用 Locale.ROOT，保持历史原顺序和文件字节。测试没有连接服务依赖，桌面夹具不读取真实历史；不将合成验收声称为真实用户效率实验。

完成后本地提交并快进 main、合并后定向回归；本轮不推送、不打 tag，用户 `.testagent/` 不读取或修改。
