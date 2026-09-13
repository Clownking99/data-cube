# SQL 美化范围验收

## 范围与基线

- 基线 main `0798b8e`，独立分支 `codex/sql-format-scope`。
- 开始时只读核对 [v3.2.6 发布](https://github.com/Clownking99/data-cube/actions/runs/34761879321) 已 success；这不是本轮增量的远端 CI。
- 沿用现有词法美化器，新增显式选区/全文范围、Ctrl+Alt+L、单步撤销和编辑守卫；不增加按钮数量。
- `.testagent/`、真实连接/凭据/SQL 历史/业务文件不在验收范围；不推送、不打 tag、不更新安装器。

## 自动测试映射

| 要求 | Evidence |
| --- | --- |
| 仅替换选中正文、保留边界空白/外部语句与正反方向 | `SqlFormatScopeTest.selectedRangePreservesOuterTextBoundaryWhitespaceAndDirection` |
| 不自行扩大到词或语句 | `selectionDoesNotExpandToStatementOrWordBoundaries` |
| 空白选区/空文档不调用排版器、不回退全文 | `blankRangeNeverInvokesFormatterOrFallsBackToWholeText` |
| 无选区全文排版，光标夹紧，无变化不编辑 | `wholeTextKeepsBoundaryWhitespaceAndClampsCollapsedCaret` / `alreadyFormattedRangeKeepsCaretAndDoesNotPlanAnEdit` |
| 输入、范围、结果限额前/等/后；小选区可用于长文档 | `scopeLimitIsCheckedBeforeFormatter` / `wholeInputLimitStillAllowsSmallSelectionAtBoundary` / `resultLimitAcceptsBoundaryAndRejectsOversizeBeforeApplying` |
| 无效位置拒绝 | `invalidPositionsAreRejected` |
| 动态按钮、一次撤销/重做与前后输入隔离 | `SqlFormatActionTest.formatIsOneUndoBetweenTypingAndPreservesReverseSelectionAndDynamicLabel` |
| 快捷键只在编辑器、精确修饰键、改绑持久化及查找不变 | `shortcutIsEditorScopedExactAndRebindableWithoutChangingFind` |
| 准入/忙碌/不可编辑/禁用/关闭拒绝旧按钮与键盘，解除监听 | `guardsBlockBothOldButtonAndKeyWithoutEditing` |
| beforeEdit 改文本/选区/准入使旧计划失效 | `beforeEditCannotApplyStaleSnapshot` |
| 计划失败无编辑、不显示异常正文 | `planningFailureKeepsEditorAndDoesNotExposeExceptionText` |
| 无变化/空白不改撤销、位置或触发编辑回调 | `unchangedOrBlankDoesNotTouchUndoSelectionOrCallbacks` |
| 实际 Pane 保留选区外混合物理换行、文件身份、高亮/脏状态，单步撤销 | `SqlEditorFormatIntegrationTest.selectedSqlLeavesOtherStatementsAndPhysicalSeparatorsUntouched` |
| 实际 Pane 空白选区不改全文 | `whitespaceSelectionNeverFallsBackToFormattingWholeFile` |
| 实际 Pane 关闭/任务/文件忙碌/执行/禁用守卫 | `paneGuardsRejectBothOldButtonAndShortcut` |
| 进入草稿检查点，冻结拒绝美化，源文件不写入 | `formattedTextEntersDraftButFreezeStopsFurtherChangesWithoutSavingSource` |
| 600/880 宽明暗按钮显示，快捷键与执行选区保持一致 | `dynamicFormatLabelFitsToolbarInBothThemesAndShortcutKeepsSelection` |
| 美化取消排队补全、不请求成员候选，后续正常输入仍补全 | `SqlAutoCompleteFocusTest.explicitFormatCancelsQueuedCompletionAndNeverRequestsMembers` |
| 隐藏已显示候选、嵌套/失败后恢复补全 | `explicitTransformHidesVisiblePopupAndRestoresSuppressionAfterFailure` |

Pane 测试使用 @TempDir 合成文件和 DraftConnectionProbe，provider/session/metadata/network 均断言为 0。
单元测试不是用户耗时实验或数据库方言全覆盖；没有采集 SQL 或行为遥测。

## 运行记录

- RED：先新增两项实际 Pane 测试，22 秒 exit 1；两项分别证实选区被扩大和纯空白选区触发全文重写。
- 初版修复与相邻 Formatter / ShortcutSettings / Usability / DraftIntegration 测试：23 秒 exit 0。
- 纯计划、动作及入口定向：12 秒 exit 0；追加实际 Pane 守卫、草稿、高亮和明暗布局后，15 秒 exit 0。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*SqlFormatScopeTest' --tests '*SqlFormatActionTest' --tests '*SqlEditorFormatIntegrationTest' --no-daemon --console=plain
```

首次全量 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`：
2 分 31 秒 exit 0；应用 2494 项 / 2491 通过 / 3 既有跳过 / 0 failures/errors，构建期 8 项通过。
jlink / jpackageImage 实际执行，保留既有 unchecked、JAVA_TOOL_OPTIONS 工具探测及 JEP 493 提示，不称为无警告。
夹具启动前改用字段访问尚未安装 skin 的 CodeArea，52 项定向复验 25 秒 exit 0。

## 桌面发现与修正

- 首轮在生产镜像副本 patch 单个合成夹具，强制独立 `format-desktop-profile`。
  按钮选区美化和 Ctrl+Z 已实测；选区从 45→22 变为 55→22，外部 `select untouched;` 未改变，文件脏标记随撤销清除。
- 初案 Ctrl+Shift+F 无反应。夹具 Scene 捕获到 `UNDEFINED / ctrl=true / shift=true`；
  对照已有 Ctrl+Shift+D 收到 D 并正常重复，Ctrl+Z 收到 Z，改绑 Ctrl+Alt+L 收到 L 并成功美化。
  只确认当前输入路径的表现，不把输入法归因当作已证明事实；未修改系统或用户输入法设置。
- 采用 DataGrip 同类默认 Ctrl+Alt+L，仍可改绑。此时发现程序化美化会排队唤起补全；
  添加局部变换抑制和旧候选代际失效，避免文本排版导致成员候选请求。

## 最终验证（2026-09-14）

- 键位/补全修正后定向格式化、真实焦点补全、快捷键测试：38 秒 exit 0。
- 最终 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`：
  2 分 25 秒 exit 0；XML 2496 项 / 2493 通过 / 3 既有 live 跳过 / 0 failures/errors；构建期 8 项已通过，本次 up-to-date。
  新增 54 项：范围 21、动作 16、Pane 15、补全焦点 2。没有删减既有断言或增加跳过。
- 生产运行时 `jimage list` 含 SqlFormatScope、Plan、Action、SqlAutoComplete，不含 SqlFormatDesktopFixture / DraftConnectionProbe；
  cfg 入口仍为 `com.datacube/com.datacube.DataCubeFx`。运行时 modules SHA-256：
  `2BC35530AC851919EB5C6526FF028E6E4D490152446F6ADFD397056E903D7B08`。
- 最终桌面使用最新镜像的新副本 `build/format-final-image/DataCube` 和全新
  `build/format-final/format-desktop-profile`；没有快捷键覆盖文件，以出厂 Ctrl+Alt+L 验证。
  副本只 patch 单个夹具类；版本 0.0.0 仅供本地验收，不是发布版本。
- 真实 Ctrl+Alt+L 收到 `L / ctrl=true / shift=false / alt=true`，仅美化反向选中的查询，
  45→22 变为 55→22，未选中的 `select untouched;` 保持不变；Ctrl+Z 一次恢复文本和干净标记。
- 选区 46→48 仅含两个空格，默认快捷键显示明确拒绝，正文/位置/干净标记均未改变。
- 暗色宽窗、暗色与亮色 640 窄窗完成按钮/范围/反馈检查；亮色窄窗点击实际“美化选中”并一次撤销通过。
- 亮色窄窗将光标移至关键词内（23→23）再按默认快捷键美化全文，两个语句均排版、无新选区，
  补全窗口未出现；Ctrl+Z 恢复。最后正常关闭，精确两条夹具 exe 路径的存活进程数为 0。
- 合成源文件仍与初始混合换行原文完全相等，未保存或执行；未连接真实数据库、修改输入法、安装/升级或推送。
- 一次主题切换因截图 ID 失效未执行，重新观察后续验成功；没有复用失效截图执行操作。

## 审查与本地集成

复查范围及索引边界、单次替换/撤销、前后快照准入、设置监听与构造失败/最终关闭清理、
程序化编辑对补全/成员候选的隔离及异常恢复、生产模块与夹具分离。源码与文档差异检查通过。
待提交并本地快进 main 后追加集成结果；不推送、不打 tag。

## 边界

- 此增量控制文本编辑范围，不把现有词法美化器升级为 PostgreSQL/Oracle 完整语法解析器或语义安全检查。
- 仅范围内的排版会改变空白；未涉及的物理换行保留。范围内新增/重建换行沿用现有文件控制器的首个分隔符规则；
  撤销恢复编辑器文本，不承诺混合换行在被重排区域逐字节回滚。不自动保存源文件。
- 字符限额限制操作规模，但不承诺任意复杂输入的固定耗时；结果上限在现有美化器返回后检查。
- 全文美化保留的是夹紧后的字符偏移，不承诺光标仍对应同一个 SQL token。
