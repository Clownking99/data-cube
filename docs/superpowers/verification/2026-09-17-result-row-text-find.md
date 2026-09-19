# 行详情正文查找验收

基线 `10167a6`，分支 `codex/result-row-text-find`；[设计](../specs/2026-09-17-result-row-text-find.md)。测试只使用合成结果、独立临时路径和连接探针；`.testagent/` 不读取、不修改、不暂存。

## 行为证据

| 行为 | 直接测试 |
| --- | --- |
| 独立正文查找、不筛字段、不自动改变选区、显式定位 | `ResultRowTextFindTest.bodySearchDoesNotFilterFieldsOrSelectUntilAsked` |
| 切换同名字段保留条件、更新偏移且不自动跳转 | `ResultRowTextFindTest.changingDuplicateFieldsRetainsQueryButRebuildsOffsetsWithoutNavigating` |
| 字段筛选保留原匹配或清空正文，不代选 | `ResultRowTextFindTest.fieldFilteringKeepsCurrentMatchOrClearsItWithoutChoosingAnotherField` |
| NULL / 空字符串 / 字面 NULL | `ResultRowTextFindTest.placeholdersAreNotSearchableButLiteralNullIs` |
| 不搜索元数据、其他字段、隐藏值、4,096 之后的尾部 | `ResultRowTextFindTest.bodySearchNeverSearchesMetadataOtherFieldsHiddenValuesOrTruncatedTail` |
| 1,023 / 1,024 / 1,025 查找词边界及恢复 | `ResultRowTextFindTest.overlongQueryRejectsWholeInputAndRecoversWithoutMovingSelection` |
| 字面符号、空格与 Unicode 偏移 | `ResultRowTextFindTest.literalSpacesAndUnicodeUseTheDisplayedTextOffsets` |
| 字段/正文快捷键区分、双向循环、修饰键和 Enter 不误关闭 | `ResultRowTextFindTest.fieldAndBodyShortcutsStayDistinctAndNavigationDoesNotCloseTheDialog` |
| 页脚关闭按钮焦点下仍可查找与定位，额外修饰键不抢焦点 | `ResultRowTextFindTest.footerFocusStillAllowsBodySearchFieldSearchAndNavigation` |
| 新控件 Esc 关闭，拥有者回调与清理共存，旧按钮不定位 | `ResultRowTextFindTest.escapeClosesFromBodyControlsAndOwnerCallbackCannotReplaceCleanup` |
| 明暗 480 / 720 宽，长提示、完整按钮、焦点提示及失焦选区高亮 | 扩展 `ResultRowDialogTest.boundedWarningsAndControlsRemainReadableWithPathologicalMetadata` |
| 实际 SQL Pane 快照来源正确，排序/筛选/列/源选区/文件/连接不变，关闭释放 | `SqlResultCellIntegrationTest.rowBodySearchUsesFrozenProjectionWithoutChangingResultSqlOrFile` |
| 原单元格查找语义兼容 | 既有 `ResultCellFindTest` / `ResultCellDialogTest` 全类回归 |

## 执行记录

- `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，Gradle 均附 `--no-daemon --console=plain`。
- 入口测试 RED：20 秒 exit 1，缺少 `result-row-find-query` 断言失败。
- 接入共享查找条后，新入口加既有行/单元格窗口 14 秒 exit 0；补充正文行为后同四类 14 秒 exit 0。
- 再加入真实 Pane、窄窗/焦点与选区高亮检查，五类 18 秒 exit 0。
- 2026-09-17 首次全量：`clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`，2 分 33 秒 exit 0；2,859 通过、3 既有 live 跳过、0 失败/错误，buildSrc 8 项实际执行通过。此时原生快捷键未验收，不作为最终交付结论。
- 2026-09-19 桌面对照：原 Ctrl+F 可聚焦字段名，而 Ctrl+Shift+F 不聚焦正文。调整为 Ctrl+Alt+F，并将键盘过滤提升到 DialogPane，覆盖页脚焦点；未证实具体系统/输入法拦截原因。
- 更新快捷键与页脚回归先 RED：17 项中 2 项焦点断言失败，38 秒 exit 1；修复后五类定向命令 20 秒 exit 0：`test --tests com.datacube.fx.ResultRowTextFindTest --tests com.datacube.fx.ResultRowDialogTest --tests com.datacube.fx.ResultCellFindTest --tests com.datacube.fx.ResultCellDialogTest --tests com.datacube.fx.SqlResultCellIntegrationTest`。

- 2026-09-19 最终全量：`clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`，3 分 16 秒 exit 0；XML 2,863 总项、2,860 通过、0 失败/错误、3 既有 live 跳过。相对基线新增 18 项回归；buildSrc 8 项已有成功报告，本次任务 UP-TO-DATE。原有测试 unchecked 提示与打包器 JAVA_TOOL_OPTIONS / JEP 493 探测提示仍在，最终打包成功。

## 隔离桌面验收（2026-09-19）

使用 computer-use 技能操作最终 `build/jpackage/DataCube` 的副本 `build/result-row-text-find-desktop-image`。仅副本配置补丁加载 `ResultRowTextFindDesktopFixture`，profile 指向新建 `build/row-text-find-profile`；固定合成结果，无连接管理器、真实 SQL、用户文件或系统剪贴板。本项是真实 ResultRowDialog 验收，不冒充完整 AppShell 或真实数据库场景。

- 暗色宽窗：Ctrl+Alt+F 聚焦正文查找，输入 `alpha` 显示 3 处而不选中；Enter 选择首处，Shift+F3 循环到长正文下方第 3 处并滚动可见，F3 返回首处。查找框保持焦点，正文雾紫色选区清楚可见。无障碍焦点字段出现滞后，因此焦点以实际边框、输入落点和匹配高亮共同确认，不单靠 UIA 文本。
- 鼠标勾选区分大小写，计数 3 → 2；选中另一个同名 payload，查找词与大小写保留，计数 1，正文无自动选区。
- Ctrl+F 仍聚焦字段名；输入 `nullable` 后旧字段被排除，正文清空、查找无匹配，并等待选择。Enter 才选择 NULL 字段，正文为空。清空字段筛选、重新选择长正文后，条件仍保留。
- F7 切至 480 宽暗色窗口，F6 切为亮色窄窗：边界/截断提示完整换行，查找、双向按钮、大小写、正文与关闭按钮均在窗口内。亮色 Ctrl+Alt+F 全选现有查找词，Shift+Enter 反向定位末处并滚动，鼠标上一处/下一处均正确，失焦选区可见。
- Esc 正常关闭；精确路径对应剩余进程 0。profile 仅产生 `settings.properties` 与 `.openjfx` 缓存。
- 未补丁生产镜像应用模块包含 820 项资源、805 个 class、0 Fixture 类；启动入口仍为 `com.datacube.DataCubeFx`，无隔离 profile 或补丁参数。

本轮不执行 SQL、不访问真实数据库、不改写用户文件；没有真实用户效率数据，未推送、打 tag 或验证远端发布。

## main 集成（2026-09-19）

- 实现提交 `4aec2a6`（11 文件），本地 main 从 `10167a6` 快进合并；未推送或创建 tag。
- 合并后七类定向回归：`ResultRowTextFindTest`、`ResultRowDialogTest`、`ResultCellFindTest`、`ResultCellDialogTest`、`SqlResultCellIntegrationTest`、`SqlTextSearchTest`、`ResultRowPreviewTest`，加 `jpackageImage -PappVersion=0.0.0`；1 分 7 秒 exit 0，136 项通过、0 失败/错误/跳过。
- 提取 main 与最终验收 worktree 的应用模块：均为 820 项资源、805 个 class、0 Fixture 类。819 项逐项 SHA-256 相同，唯一差异为 `theme-base.css` 的 Windows Git 换行转换，CRLF 归一化后文本完全一致；不声称整个 runtime 字节相同。main 启动配置仍为生产 `DataCubeFx`，无补丁或隔离 profile 参数。
- main 已跟踪工作区干净；原有未跟踪 `.testagent/` 保持不变，不读取、不修改、不暂存。
