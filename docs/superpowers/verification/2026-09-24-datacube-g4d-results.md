# G4/M4d 固定结果实施与验收账本

状态：**M4d 本地工程完成，已合并 main 并复验**。用户在 G4/M4a–c 交付后要求继续推进，本轮完成同一 G4 的 M4d，不进入 M5–M8。设计与资源边界见 [G4d 计划](../plans/2026-09-24-g4d-pinned-results.md)。

## 检查点 0：基线与隔离

- main `eaea5c3a17a79fafe3f9938c9e85b99b4c0ccdbc`，工作区无任务改动；独立 worktree `C:\Users\hetia\.codex\worktrees\datacube-g4-pinned-results\朝花夕拾`，分支 `codex/datacube-g4-pinned-results`。
- JDK `D:\jvms_v2.1.6_amd64\store\jdk-25.0.1+8`，Gradle wrapper 9.2，全部命令离线。
- 独占证据目录 `C:\Users\hetia\AppData\Local\Temp\datacube-g4d-e62360edb47e418593f11c0ac69055a3`；复用经过检查的运行脚本方式，日志/XML/摘要为本轮新生成，不复用 G4a–c 旧测试结果。
- Test 使用临时 user.home 和 `java.awt.headless=false`，仅从测试子进程移除 Redis/Schema Diff live 环境入口。镜像构建不带测试 init script。未读取 `.testagent/`、真实配置/历史/业务文件/凭据，无真库访问、推送、tag、PR、安装或发布。
- 下一步：新增跨批次保留的行为回归，再实现预算模型和只读入口。

## 检查点 1：保留模型与界面

- 目标：固定少量有界结果，保持现有执行与事务路径。
- `g4d-red`：1 test / 1 failed，确认缺少固定入口；首次失败日志/XML保留。实现后 `g4d-first` 1/1 passed。
- `PinnedResultStore` 核查数量/行/列/单元格/文本和可确认大小的值类型，全部核查成功才加入；超限不驱逐旧项，移除归还额度。同 SQL/等值结果有独立身份，仅保存不可变 QueryResult 和不含凭据的来源信息。
- 新入口固定当前查询全部已加载行，固定窗口与当前结果视图独立；只读显示原始 SQL、固定目标、Schema、固定时间、原有截断/预览说明。数据库筛选后的结果明确标注原始 SQL 与未保留参数。窗口搜索/文本排序不改变原表或 SQL，不提供数据库筛选或执行。
- `g4d-contracts-first`：4 suites / 36 tests / 36 passed / 0 failed/errors/skipped；包含新模型/UI与既有批次状态、增量/取消/关闭路径。
- 失败/未验：保留 red 失败；尚未完成全量/buildSrc/镜像和 main 集成，不标记完成。原生手动桌面、真库与发布验收未验。
- 下一步：补查执行中固定、取消、数据库筛选来源；审查长来源信息布局，完成最终回归与集成。

## 行为证据映射

| 要求 | 测试 |
| --- | --- |
| 新执行、清空、计划保留固定结果；固定 SQL 不受后续编辑影响；无重查询 | `SqlPinnedResultsTest.pinnedQuerySurvivesNewBatchAndClearingCurrentResultsWithoutRequery` |
| 重复列、NULL/空串、多行值、原 Schema、部分结果提示与独立搜索 | `SqlPinnedResultsTest.viewerKeepsDuplicateColumnsNullsPartialNoticeAndOriginWithoutChangingCurrentView` |
| 三份上限、清除/关闭释放、迟到菜单无效 | `SqlPinnedResultsTest.repeatedPinningReachesExplicitLimitAndClearInvalidatesOldMenusAndReleasesViewer` / `lifecycleGateRejectsStalePinAndViewActions` |
| 数据库筛选结果清楚标明原始 SQL 与未保留参数 | `SqlPinnedResultsTest.databaseFilteredSnapshotLabelsOriginalSqlWithoutPretendingToBeAnExecutableRequest` |
| 重复身份、移除归还预算、不可变数据、关闭禁止再次固定 | `PinnedResultStoreTest.occurrencesAreIndependentAndCapacityReclaimsOnlyExplicitlyRemovedEntries` |
| 共享总预算/字段边界、拒绝大数值与不明复合值，不部分加入 | `PinnedResultStoreTest.aggregateTextBudgetIncludesSourceMetadataCommentsNumbersAndPreviewPrefixes` / `overBudgetOrUnknownValuesRejectAtomicallyWithoutEvicting` / `sharedShapeBudgetAcceptsExactLimitAndRejectsNextUnit` |
| 第二句阻塞时固定第一句，取消保留、关闭释放，新执行不会改固定数据 | `SqlIncrementalResultsTest.firstResultIsBrowsableWhileSecondIsBlockedAndLateResultsRespectLifecycle` |
| 长来源信息有界布局，关闭后排队搜索不恢复数据 | `SqlPinnedResultsTest.longProvenanceHasBoundedLayoutAndLateSearchCannotRepopulateClosedViewer`（dark/light） |

## 检查点 2：审查与分支最终验证

- 当前目标：完成分支本地门槛，尚未合并 main。
- `g4d-targeted-review`：93 suites / 1571 tests / 全部通过，0 skipped，覆盖 SQL 编辑器、筛选、会话、写入安全与执行预算。补充的执行中固定、完成/取消/关闭、新批次保留均通过，未改变执行器或事务实现。
- 审查修正：长来源/省略说明放入固定高度可滚动区域，避免挤掉结果；关闭窗口清除表格、来源 SQL、单元格正文、搜索计时器和移除回调。`g4d-final-targeted`：3 suites / 37 tests / 全部通过，0 skipped，含明暗主题下合成长文本布局与关闭后的迟到搜索。
- 保留资源说明：每标签的固定预算独立于当前执行预算；预览标记等固定开销、基本标量、索引与 JavaFX 控件不等于 UTF-16 文本额度，整个堆和驱动缓冲不是此预算保证。固定数据只在标签内存；没有持久化格式改动。
- `g4d-full-final`：`gradlew.bat clean test --offline --no-daemon --console=plain --init-script <scratch>/isolated-tests.gradle`，2m33s，285 suites / 3693 tests / **3690 passed** / 0 failures / 0 errors / **3 existing live skips**。
- `g4d-buildSrc-final`：`:buildSrc:test --rerun-tasks`，7s，4 tasks 实际执行，**8/8 passed**，0 skipped；`g4d-image-final`：`jpackageImage --offline --no-daemon --console=plain`，34s，exit 0。DataCube.cfg 不含测试 profile/headless/合成入口，产物摘要归档，未安装或执行更新。
- 源码/测试差异审查及 `git diff --cached --check` 通过。分支各轮发生在未提交改动上，日志 HEAD 为基线，随后提交保持源码不变；机器结果区分这一事实与 main 的已提交 SHA 复验。
- 失败/未验：仅 red 回归按预期失败；最终分支无失败。3 skips 为 Redis standalone、Oracle/PG Schema Diff live，因缺少真实环境授权/前提而跳过，不算通过。unchecked 编译和 JEP 493 提示仍在。自动 FX 布局测试不代替原生手动主题/缩放验收。
- 下一步：提交并合并 main，在新的 `profile-main` 重跑全量、fresh buildSrc 和镜像，更新实际 SHA。

## 检查点 3：main 集成与复验

- 当前目标：完成 M4d 本地交付。实现提交 `d40504e2169e8efaa036c2d0557562ccf153eb60`，main 合并 `435df7635ed7d1bbe83e50767fc3aec7c6b2d2dc`。
- 合并前 main 仍为 `eaea5c3` 且工作区无任务修改；合并无冲突。源码/测试/构建与实现提交一致，main 全量使用新的 `profile-main`，未复用分支报告。
- `g4d-main-full`：`clean test` 加相同离线/隔离参数，2m33s，285 suites / 3693 tests / **3690 passed** / 0 failures / 0 errors / **3 existing live skips**。
- `g4d-main-buildSrc`：`:buildSrc:test --rerun-tasks`，8s，4 tasks 实际执行，**8/8 passed**，0 skipped。
- `g4d-main-image`：`jpackageImage --offline --no-daemon --console=plain`，29s，exit 0。启动配置无测试 profile/headless/合成入口；`D:\Projects\朝花夕拾\build\jpackage\DataCube\DataCube.exe`、cfg 和 runtime/lib/modules 的 SHA-256 在 `g4d-main-image-manifest.json` 与[机器可读结果](2026-09-24-datacube-g4d-results.json)。没有启动安装或更新。
- 11 轮原始命令、首次失败、通过/失败/跳过、日志和 XML 摘要均归档；main 证据绑定上述合并 SHA。之后的路线图/账本文档收尾不冒充代码测试时的 SHA。
- 失败/未验：main 无新失败。3 live skips 仍不算通过；原生手动桌面、真库、安装/签名/外部发布仍待验。历史 SchemaDiff 偶发问题本轮未复现，根因仍未明。
- 下一步：交付 G4/M4 的本地工程结果，M5–M8 不自动启动。仅更新文档和本地提交，无持久化格式迁移、真实数据改动、推送或发布。必要时审查后 revert 实现或合并提交即可回退功能。

## 仍待验

原生手动桌面的键盘/主题/缩放；真实 Oracle/PG 的抓取、取消、LOB、事务；安装/升级/签名/远端 CI/发布。自动 FX 和 mock 证据不替代这些验收。历史 SchemaDiff 偶发失败根因仍未明确，未复现不能称为修复。M5–M8 未启动。
