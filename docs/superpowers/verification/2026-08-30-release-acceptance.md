# P0.2 Windows 发布前验收（进行中）

日期：2026-08-30。构建/测试源码基线：`edce8a2`。工作区：`D:/Projects/朝花夕拾/.worktrees/release-acceptance`，分支 `codex/release-acceptance`。

**结论：本地完整单测、免安装包构建、隔离首次启动和合成旧配置重启保留已通过；范围切换/弹窗键盘等桌面验收仍有工具限制，发布门槛尚未全部满足。** 下文记录早期 `edce8a2` 源码基线的构建/桌面验收，当时没有修改生产、测试或正式构建配置；随后 `719685b` 已补齐发现的列隐藏入口，最新代码与桌面证据见[列控制验收](2026-08-30-result-column-visibility.md)。旧包不代表新源码已经完成打包验收；不推送、不打 tag、不安装/卸载软件。

## 1. 本次实际构建与测试

首次执行：

```powershell
.\gradlew.bat clean test jpackageImage --no-daemon --console=plain
```

- 退出码 0，54 秒；17 actionable tasks，16 executed、1 up-to-date。`jlink` 和 `jpackageImage` 均实际执行成功。
- 新建 worktree 中重新编译，不以旧安装目录存在作为构建成功依据。
- 首次 JUnit XML：138 suites、1211 tests、1118 passed、0 failures/errors、93 skipped。默认环境跳过了部分桌面相关测试，不能作为完整验收结果。

随后临时追加非 headless 设置并强制重跑：

```powershell
$p02PreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$p02PreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $p02TestExit = $LASTEXITCODE
} finally {
    $env:JAVA_TOOL_OPTIONS = $p02PreviousJavaOptions
}
exit $p02TestExit
```

- 退出码 0，27 秒；8 actionable tasks 全部执行，非 UP-TO-DATE 复用。环境变量运行后恢复。
- 主代理读取本次全部 XML：**138 suites、1211 tests、1208 passed、0 failures/errors、3 skipped**。
- 三项跳过分别是 Redis live、Oracle Schema Diff live、PostgreSQL Schema Diff live，未启用真实数据库环境，不计入通过。
- 保留既有 `SqlEditorResultFilterContractTest` 未检查操作编译提示，以及 jlink 的 JEP 493 工具链位置提示；这两条提示不导致本次任务失败，也未在本轮修复。

桌面续验后，以相同的临时非 headless 设置和 `finally` 恢复方式强制重跑三个相关测试类：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorResultFilterContractTest --tests com.datacube.fx.SqlResultToolbarTest --tests com.datacube.fx.ResultExportOptionsDialogTest --rerun-tasks --no-daemon --console=plain
```

- 退出码 0，23 秒、8 tasks 全执行；主代理读取本次全部 XML 为 **3 suites、54 tests、54 passed、0 failures/errors/skips**（29 + 22 + 3）。当前 `build/test-results/test` 是此次定向运行结果；上方完整套件统计对应较早的完整运行，不混为本次重跑。
- `exportFlushKeepsSortColumnOrderAndUsesFrozenVisibleRows` 在同一 FX 调用中输入搜索并捕获，断言筛选/排序、可见列投影和全部已加载顺序；其中隐藏列由测试代码设置。
- `explicitExportFlushCommitsOnceAndCancelsPendingDebounce` 验证立即 flush 仅提交一次，等待后不重放；弹窗测试用 `ComboBox.setValue` 验证全部已加载选择及摘要。这些是自动化契约证据，不替代物理输入、用户隐藏列入口、实际文件保存或弹窗键盘验收。

## 2. 产物与隔离方式

- 产物：本工作区 `build/jpackage/DataCube/DataCube.exe`，496640 字节。
- 对捆绑运行时执行 `runtime/bin/java.exe --list-modules` 成功，含 `com.datacube`、`java.sql@25.0.1`、`javafx.controls@25`。这只验证运行时命令，不代表 GUI 已启动。
- `DataCube.exe` SHA-256：`BC48477461F3494B08E25F5996B5F50F668F7FD672258C992F119F8056827F01`。这是启动器哈希，不是整包完整性校验。
- 本地构建使用默认版本 `3.0.0`，**仅用于本地验收，不是下一发布号**。正式版本须经维护者确认后重新构建。
- 创建独占空目录 `C:/Users/hetia/AppData/Local/Temp/datacube-p02-home-170b059b15a840b3b2c1bbd7ba2937ee`。
- 为避免读取真实连接、凭据和历史，仅修改生成产物 `build/jpackage/DataCube/app/DataCube.cfg`：在 `[JavaOptions]` 下增加 `java-options=-Duser.home=C:/Users/hetia/AppData/Local/Temp/datacube-p02-home-170b059b15a840b3b2c1bbd7ba2937ee`。没有修改受版本控制的 `build.gradle`。
- 当前隔离配置 SHA-256：`C74EFDC11360F1F8D49273C1B850F106C03612EE8BC3284FF01C9732AEBB33EE`。该含本机绝对路径的产物不能直接作为 Release 分发。
- 源码显示 GUI 启动会在显示主窗口后触发公共更新检查。因此隔离 `user.home` 不等于禁止网络；本轮没有宣称离线运行已验证。

## 3. 桌面尝试与续验

### 3.1 首次尝试：访问拒绝（历史记录）

使用 computer-use 技能，通过受支持的 Windows 控制接口尝试启动上述确切 exe。启动前窗口列表只有 Codex，没有 DataCube 或 Excel。

1. 首次 `launch_app` 返回 `GetCursorPos failed: 拒绝访问。 (0x80070005)`。
2. 重新读取窗口列表，仍没有 DataCube；按技能允许的恢复流程重试一次，得到相同错误。
3. 停止 UI 路径，没有改用 PowerShell UIAutomation、COM、键盘宏或其他接口绕过；已提示维护者确认桌面是否解锁且当前会话可交互。
4. 隔离目录核对仍为空。没有观察到应用启动、执行点击、读取真实配置或保存导出文件；不能将测试工具访问错误归为产品启动失败。

此前 Excel 文件对话框因可能暴露无关私人文件元数据被工具安全检查拒绝的路径亦不重试、不绕过。本轮没有启动 Excel，不能据 OOXML、独立渲染器或单测改写真实 Excel 的验收状态。

### 3.2 新版控制会话恢复：打包启动与配置保留

同日发现 computer-use 插件版本已更新至 `26.825.51511`。重新读取技能及安全指引，重置 JavaScript 会话并通过受支持接口重新初始化后，上述隔离 exe 成功启动；没有改用其他 Windows 自动化接口或改变安全设置。旧访问错误不再是首次启动的当前阻塞。

- 启动前核对独占目录为空；实际观察到深色空连接引导页及“开始使用 DataCube”。启动时出现公共更新提示，选择“稍后”，没有下载、运行更新或安装软件。
- 通过 UI 切换亮色并正常关闭，独占 `.datacube/settings.properties` 写入 `ui.theme=LIGHT`，随后窗口列表确认应用已退出。
- 在已退出的隔离目录创建两条无密码的旧格式合成连接，ID 为 `p02-legacy-pg` / `p02-legacy-oracle`，名称为“验收-旧配置-PG”/“验收-旧配置-Oracle”。仅含 ID、名称、类型、主机、端口、库名、用户名与空加密密码，不含新环境/只读/超时字段；主机均为 `127.0.0.1`、端口 `1`。设置文件预置 `sql.result.maxRows=321`、`sql.result.commentMode=INLINE`，保留 UI 写入的亮色设置。
- 重启同一隔离 exe，主窗口显示两条折叠连接；再次选择更新“稍后”，设置窗口显示亮色、固定注释模式、321 行上限、256 MB。点击设置“取消”并正常关闭主窗口，没有展开连接、执行 SQL 或改动真实配置。
- 正常退出后再次读取合成配置并断言连接 ID 不变、两文件 SHA-256 与启动前一致；下表为最终复核值。此次证明旧格式加载兼容与重启保留，**不证明已安装旧版本的安装器升级、迁移或卸载**。

| 独占 `.datacube` 文件 | 启动前与退出后相同的 SHA-256 |
| --- | --- |
| connections.json | `276DD19953E5E0B48122B3F85327A1D694F1ABC7CB44BCC7E11324E278C16438` |
| settings.properties | `5CA8D1BBCAB221B7DECCF6257800586C37E6CFD36489395FA8D793F3E69A8470` |

### 3.3 合成结果续验：入口缺口与工具边界

使用既有临时 `ExportSmokeLauncher` 在本 worktree 启动真实查询结果 UI，未绑定数据库；独占目录为 `C:/Users/hetia/AppData/Local/Temp/datacube-export-smoke-12887823699863499662`。这是 classpath 验收夹具，不替代上节的实际打包启动。

- 三行合成数据中输入 `Ada`，显示 2 / 3 行；“分数”升序显示 8、12。CSV 确认默认“当前筛选结果（当前排序）”，摘要为 2 行、3 列；能够看到“全部已加载行（加载顺序）”选项，但选择尝试没有改变范围，不记为通过。
- `list_windows` 仍只返回主窗口，没有可独立定位的确认弹窗；Tab 操作后焦点落到主窗口导出按钮，而非弹窗。无障碍焦点与视觉焦点亦不一致。范围切换及弹窗 Tab/Enter/Esc 保持未验收，不据工具输入结果判断产品有缺陷，也不通过猜测窗口句柄绕过。
- 右键数据列标题只出现复制相关菜单；CodeGraph 及定向源码检查确认 `SqlEditorPane` 没有启用列选择菜单，也没有其他显示/隐藏列入口。因此原计划把“隐藏列”当作现有 UI 功能并不成立：这是待设计的产品缺口，不是再点一次就能补验的条目。既有测试直接调用 `TableColumn.setVisible(false)` 只能证明内部导出投影规则，不能证明用户可以隐藏列。本次没有用夹具隐藏列冒充 UI 验收。
- 点击确认框“取消”后恢复结果页，筛选和排序仍为 Ada、8/12；没有进入文件保存器。正常关闭夹具，`runExportSmoke` 退出码 0（7 分 47 秒，包含人工交互等待），再次列出窗口确认不存在 DataCube 验收窗口。独占目录仅有关闭时生成的合成 `history.txt`，没有导出文件；没有读取或覆盖剪贴板。
- 两次工具调用之间的截图与分析延迟不能证明输入后小于防抖时间的立即导出，因此不把本轮手工操作算作该时间边界通过。真实 Excel 仍未启动，先前被安全检查拒绝的路径不重试。

## 4. 验收矩阵与后续恢复点

| 项目 | 本轮状态 | 后续所需证据 |
| --- | --- | --- |
| 本地完整单测 | 通过，3 项 live 明确跳过 | 改动后按风险重跑 |
| jlink / jpackageImage | 通过 | 正式版本号确认后重新构建 |
| 捆绑运行时模块列表 | 通过 | 不替代 GUI 启动 |
| 隔离空配置首次启动 | 通过 | 实际空连接引导；更新选择“稍后” |
| 合成旧连接/设置重启保留 | 通过 | 两条旧格式合成连接、亮色/321 行/固定注释可见，退出后文件哈希不变 |
| 实际安装器升级/卸载 | 未执行 | 另行授权；免安装启动不等于安装升级 |
| 全部已加载范围切换并保存 | 未验收 | 对比当前筛选行数与加载顺序，实际文件内容 |
| 隐藏列输出 | 后续列控制增量已补齐入口；真实菜单/摘要通过，两种 CSV 文件自动化通过 | 隐藏后的桌面实际保存仍待补验，见列控制验收；不混淆文件测试与桌面保存 |
| 弹窗 Tab / Enter / Esc | 未验收 | 观察独立弹窗焦点、确认及取消后无写入 |
| 筛选输入后防抖到期前立即导出 | 自动化契约通过，桌面未验收 | 可靠观测时间边界，不将手工慢操作冒充边界验证 |
| 真实 Excel 冻结/换行/自动行高 | 未验收 | 允许访问的纯合成工作簿中的直接观察 |
| 同 SHA 远端 Verify | 未执行 | 获授权推送后的 jobs 结果 |
| tag / Release | 未执行 | 版本、发布授权及发布 gate 全部满足 |

既有五格式实际保存、筛选 CSV、列重排/排序、预览许可和 SQL 拒绝证据继续有效，但不扩写为上述未验收组合。续验只重复了筛选/排序与确认摘要观察，没有重新保存文件；详见[安全导出验收](2026-08-30-safe-result-export.md)和[XLSX 可读性验收](2026-08-30-query-xlsx-readability.md)。

恢复工作时先核对工作树、构建基线及隔离 cfg，不要直接启动用户已安装实例。若重新构建覆盖了生成 cfg，必须在 GUI 启动前重新设置独占 `user.home`。独立弹窗可可靠定位后再补验相应条目；列控制范围与入口验证已另行完成。期间使用合成数据，不访问 `.testagent/`、公司数据库、真实历史或凭据。本地产物暂保留供续验，P0.2 未标记完成。

## 5. 文档审查与当前交付状态

- 候选说明 `bfc980c` 完成独立任务审查，符合要求且质量 Approved，无待修发现；主代理核对了历史五格式及后续 XLSX 文件/渲染证据，不将其等同于本轮真实 Excel 验收。
- 整个文档增量 `edce8a2..419ddb9` 完成独立只读审查：0 Critical、0 Important、0 Minor，允许保留文档增量，不代表 P0.2 完成。
- 以 `8fc3133` 为基线的本次五文件桌面续验记录完成独立只读审查：0 Critical、0 Important、0 Minor，结论为可保留文档增量。审查者独立核对当前 54 项 XML 及生产/测试/构建/工作流无差异；未把旧格式配置保留扩大为安装升级，也未把工具限制或隐藏列内部测试扩大为 UI 通过。
- 主代理再次核对本轮相对 Markdown 链接及 `git diff --check`，均通过；`src`、`test`、`build.gradle`、工作流相对此基线无变更。
- 上述文档阶段结束时尚未合并 `main`。随后用户已批准列控制并委托常规设计决策：经审查通过的独立增量可按已有授权本地集成，不等待发布动作授权；最新集成状态见列控制验收。P1 草稿恢复仍需独立设计与实现，但不再等待逐项人工设计确认；发布门槛保持不变。
