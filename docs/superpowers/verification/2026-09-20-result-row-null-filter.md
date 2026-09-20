# 行详情隐藏 NULL 字段验收

## 范围与实现

基线 main `7c7efe4`，独立分支 `codex/result-row-null-filter`。生产修改仅在 `ResultRowDialog`：增加默认关闭的 NULL 复选框，与已有名称条件取交集；清除按钮同时重置两项条件，正文查找词不清除。按 `ResultCellPreview.nullValue()` 判断，不将空字符串、空白或文字 NULL 混为空值。保持快照原列号、顺序、200 字段上限和选择身份，不补入隐藏/未捕获字段，不修改原结果及导出范围。

采用 code-testing-agent 的聚焦回归方式，范围为这一生产类及其既有集成入口；未扩展测试工程或引入新依赖。复用已有离线 fixture 和 JavaFX 支持，不读取用户数据库、SQL、历史、配置或剪贴板，不访问 `.testagent/`。本轮自审，无新子代理或独立外部审查。

## 需求与证据

以下前六项位于新增 `ResultRowNullFilterTest`，合计 15 次测试执行；既有布局断言也纳入新复选框。

| 需求 | 证据 |
| --- | --- |
| 默认显示全部；仅实际 NULL 隐藏；清除与重开默认值 | `hidesOnlyDatabaseNullAndClearRestoresWithoutImplicitSelection`：null、空字符串、NULL、null、空白、0 六组参数 |
| 名称取交集、同名字段原始身份、正文选区与查找词保留 | `combinesNameAndNullFiltersWithoutLosingDuplicateIdentityBodySelectionOrSearch` |
| 隐藏选择清空详情；恢复不代选；Enter 明确选取 | `excludingNullClearsDetailAndRestoringNeverSelectsItsDuplicate` |
| 零匹配、257 单元名称拒绝、开关不能绕过上限 | `zeroMatchesAndOverlongNamesCannotBeBypassedByNullToggle` |
| 前 200 字段为固定快照，不补入隐藏或遗漏的非 NULL | `filteringBoundedSnapshotDoesNotPullInHiddenOrOmittedNonNullFields` |
| 真实面板入口不改变源行、列、选区、导出列/行、SQL 或撤销状态；DB 调用为零 | `actualPaneDialogFilterDoesNotChangeResultRowsColumnsSelectionExportOrSql` |
| 480/720 宽度 × 明/暗四组布局、长拒绝提示、Esc | `filtersAndValidationRemainReadableInNarrowThemedDialogs` |

失败先行：缺少入口时，首轮新增 11 项全部在明确的复选框存在断言处失败，25 秒、exit 1。实现后首轮四类测试 19 秒通过；加入四组宽窄/主题回归后，最终四类定向测试 18 秒通过：`ResultRowNullFilterTest` 15、`ResultRowDialogTest` 27、`ResultRowTextFindTest` 17、`SqlResultCellIntegrationTest` 35，共 94 项，零失败/错误/跳过。

## 全量与打包

PowerShell 设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后执行：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

结果：2 分 53 秒，exit 0 / BUILD SUCCESSFUL。XML 汇总应用测试 3,194 项：3,191 通过、3 项既有 live 跳过、0 失败/错误；buildSrc 8 项通过。开发镜像 `build/jpackage/DataCube` 成功，未升级版本或生成正式发布。

保留既有构建说明：`SqlEditorResultFilterContractTest` 未检查操作警告；jlink 辅助调用输出 `javac/java failed: Picked up JAVA_TOOL_OPTIONS...`；JEP 493 工具链模块提示。最终以 Gradle exit 0、测试 XML 和可启动镜像判定，不把这些输出称为已消除。

## 原生桌面验收

使用 computer-use 的原生鼠标与键盘，启动本轮镜像的隔离副本。复用未修改的 `ResultRowTextFindDesktopFixture`，只加载固定合成快照：id、两个同名 payload、数据库 NULL、空字符串、文字 NULL，以及快照外的隐藏字段。没有连接管理器或真实查询。

临时目录：`build/result-row-null-filter-desktop-20260920`；只在副本启动配置中加入测试入口/patch-module 与 `row-text-find-profile`，不改变生产入口。原镜像与副本 runtime/lib/modules 的 SHA-256 相同：`5AB628E22FCA3E66A62D8F86E24E3BB5CD9F015A372728C7EBC26F0292DED4DC`。

- 暗色宽窗初始 6/6，勾选后 5/6，原 payload 正文与所选字段保留；NULL 消失，empty 与 literal 保留。
- Ctrl+F 聚焦名称，输入 nullable 与隐藏条件组合为 0/6，明确提示已隐藏 NULL；取消隐藏后恢复 1/6，详情保持未选择。
- 鼠标选择 NULL 后再次隐藏，正文和类型摘要清空；“清除筛选”同时清空名称和关闭隐藏，恢复 6/6，不自动恢复旧选择。
- 暗/亮两主题、720/480 两宽度观察，新增开关、清除按钮、计数、只读正文及关闭入口未裁切。Tab 从名称输入跳过禁用的清除按钮到复选框，空格切换为 5/6。
- 隐藏开启时分别选择 literal 与 empty，正文/摘要正确显示文字 NULL 与空字符串，不误判。
- Esc 正常关闭，随后无本 fixture 窗口、无 DataCube 进程；隔离 profile 仅产生主题设置与 JavaFX 本地缓存。

JavaFX 可访问性子节点偶有前一帧内容，本轮按每次输入后的实际截图判断状态，未使用旧索引反复操作。原生验收仅代表本机合成窗口，不等同于真实数据库、跨平台或真实用户效率验证；正文查找选区/清除兼容、超限与截断边界由自动测试覆盖。

## 集成

实现提交 `888e569`，main 从 `7c7efe4` 快进合并。合并前确认 main 无跟踪/暂存改动；保留原有未跟踪 `.testagent/`，没有进入该目录。

合并后的 main 重新运行同样四类定向测试并打包开发镜像：

```powershell
.\gradlew.bat test --tests com.datacube.fx.ResultRowNullFilterTest --tests com.datacube.fx.ResultRowDialogTest --tests com.datacube.fx.ResultRowTextFindTest --tests com.datacube.fx.SqlResultCellIntegrationTest jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

1 分 1 秒，exit 0 / BUILD SUCCESSFUL；XML 确认 94 项全部通过，无跳过/失败/错误。将工作树与 main 镜像中的 `com.datacube` 模块分别提取，按相对路径逐文件比较 SHA-256：均 827 个文件、812 个 class，0 差异、0 Fixture。生产启动配置仍为原入口，SHA-256 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。因此主分支生产模块与已进行原生验收的实现一致，测试入口未混入生产镜像。

本记录随后以文档提交合入本地 main；生产代码未再改变。`git diff --check` 通过，不推送、不打 tag。
