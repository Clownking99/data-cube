# 查询结果恢复列布局验收

基线 main `2ada458`，独立分支 `codex/result-column-reset`；[设计与边界](../specs/2026-09-20-result-column-reset.md)。生产修改仅限 `SqlResultColumnMenu`，未更改查询、文件保存、事务或数据库代码。

## 自动化证据

按 code-testing-agent 聚焦流程新增 `SqlResultColumnResetTest`，复用已有离线 SQL fixture；不读取真实 SQL、连接、历史、剪贴板或 `.testagent/`。无子代理或中间状态文件。

| 需求 | 证据 |
| --- | --- |
| 重排、隐藏与宽度组合恢复；同名列按身份处理；保留筛选、排序、两格选区及焦点；两种导出行范围不扩大 | `restoresColumnsWithoutLosingFilteredSortedRowsSelectionOrExportScope`：明暗两组，实际 TableView resize，断言列对象顺序、建议/实际宽度、行列表及行身份、排序键、焦点与具体导出值，零数据库调用和源文件/编辑器不变 |
| 单独列宽/顺序/可见性均能恢复，反复刷新不改变原始基线，初始布局禁用且不重复重置 | `eachLayoutChangeCanBeRestoredAndMenuRefreshDoesNotRebase`：三组 |
| 忙碌、禁用、关闭、不可用、删除列、新查询、清空结果拒绝旧恢复动作 | `staleOrUnavailableResetCannotChangeCurrentColumns`：九组，直接调用捕获的旧 handler，比较当前列/宽度/行列表和旧列；忙碌恢复、新查询的新入口仍可操作 |
| 单列、零行仍可恢复宽度，不新增数据 | `singleColumnAndEmptyRowsStillHaveRestorableWidths`：两组 |
| 原“显示全部列”保持只恢复可见性的语义 | `showingAllColumnsDoesNotResetOrderOrWidth` |
| 空选区不代选；切换批次不让旧动作影响新结果的初始宽度和 SQL 来源 | `resetWithNoSelectionDoesNotChooseARowAndBatchSwitchGetsNewDefaults` |
| JavaFX 默认宽度自动测量不算用户修改；仅实际宽度变化仍可恢复；跨 pulse 后不再启用恢复 | `restoredLayoutStaysDisabledAfterNativeLayoutPulses`：已显示/未显示/未挂载 Scene 三组；先验证初始不可用，宽度单独修改及宽度+重排+隐藏分别恢复，断言实际宽度、顺序、可见性和入口禁用 |
| 后台结果的迟到首次布局不能覆盖或取消新基线 | `obsoleteFirstLayoutCannotReplaceOrCancelCurrentBaseline`：新结果/重复挂载/清空/关闭四组；旧回调不能取消当前回调，当前回调完成后仍可正常拖宽与恢复 |

先运行新增类，17 项全部因缺失菜单入口失败，22 秒 exit 1。首次实现后三类 85 项中两项失败，15 秒 exit 1：JavaFX 在列顺序/可见性变动中清理了既有选区。修复为按列身份保留并恢复选区和焦点，新增空选区/新批次测试；一次编译因 JavaFX 原始类型选择 API 的 unchecked 警告被 `-Werror` 拒绝，随后将转换限于当前类型化基线中存在的列身份，未放宽编译规则。

首轮四类定向命令 15 秒、93 项通过。原生验收发现列宽边界后修复并扩充到 100 项（新类 25 项，另外三类 42/26/7 项），最终定向 21 秒 exit 0，`git diff --check` 通过。PowerShell 设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlResultColumnResetTest --tests com.datacube.fx.SqlEditorResultFilterContractTest --tests com.datacube.fx.ResultColumnFindDialogTest --tests com.datacube.fx.SqlResultToolbarLayoutTest --no-daemon --console=plain
```

## 全量与原生验证

首轮全量 2 分 39 秒，3,169 通过/3 既有 live 跳过、buildSrc 8 项通过，开发镜像成功。隔离原生桌面完成拖动排序/拖宽/隐藏/选择后恢复，数据排序、选区和列布局正确，但重新打开菜单发现恢复项仍启用，故这次全量不作为最终修复后的证据。

加入跨真实布局 pulse 回归，11 秒复现失败：`status` 列建议宽度为 80，但实际宽度为 73.70654296875。检查本地 JavaFX 25 类实现证实默认 80px 表头会自动测量且不更新 prefWidth；手动拖宽也只更新实际宽度，不能简单删掉实际宽度判断。修复为先完成首次表头布局，分别保留建议与实际宽度。随后补后台未挂载 Scene 的结果，再次先复现一项失败（11 秒）后增加首次布局回调及过期保护；不使用私有 JavaFX API，不放宽宽度容差。

修复后的最终命令：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

4 分 34 秒 exit 0：3,179 项中 3,176 通过、3 项既有 live 跳过、0 failures/errors；buildSrc 8 项已有成功结果（本次 UP-TO-DATE），开发镜像完成。既有 unchecked 测试提示、JAVA_TOOL_OPTIONS 被打包辅助探测写入的 `javac/java failed` 文本和 JEP 493 提示仍出现，不影响最终 exit 0；未改构建配置掩盖警告。

### 修复后 Windows 原生交互

沿用已有 `SqlScriptDetailsDesktopFixture`，从最终镜像复制到忽略的 `build/result-column-reset-fixed-desktop-20260920/DataCube`，仅副本配置入口、补丁模块和独立 user.home。原始与副本 runtime/modules SHA-256 同为 `748ED34285DA22789AABA165D66F7670C275111B2051211E9F267748DCFAD888`。fixture 只提供合成已返回结果，没有注册连接或执行 SQL。

- 暗色宽窗切换至语句 #3：初始“恢复列布局”禁用；真实拖宽 message 后启用，鼠标恢复后回到原宽，再次打开仍禁用。
- 亮色窄窗（Stage 640）：菜单完整可读；真实拖动 message 到 status 前并拖宽，按 message 升序排序，隐藏 status，选择首行 `another row`。
- 菜单中原生 Down/Down/Enter 触发恢复：顺序回到 `# / status / message`、列宽恢复、status 可见；WAITING/READY 的排序以及 message 首格选择保留。再次打开恢复项禁用，显示全部列也禁用。
- 窄窗首次拖动遇到一次过期 screenshotId，重新观察后重试一次成功；没有借用其他桌面控制通道。
- 正常 Alt+F4 关闭，验收进程为 0；合成 SQL（含混合换行）逐字符未变，profile 仅增加主题设置与 JavaFX 缓存，没有历史文件。两份生产/合成运行时一致，原生产配置仍为 `com.datacube.DataCubeFx`，生产模块不含 DesktopFixture。

这是合成结果的局部原生验收，非真实数据库、完整 AppShell 或用户效率实验。未推送、未打 tag，开发镜像版本 `0.0.0` 不是发布版本。

## 本地集成

待本地快进 main 后追加实际提交与复验结果；不触碰用户自有 `.testagent/`。
