# P0.2 Windows 发布前验收（进行中）

日期：2026-08-30。构建/测试源码基线：`edce8a2`。工作区：`D:/Projects/朝花夕拾/.worktrees/release-acceptance`，分支 `codex/release-acceptance`。

**结论：本地完整单测与免安装包构建通过；桌面工具拒绝访问，尚未完成打包启动和剩余交互验收，不能发布。** 本轮不修改生产代码、测试代码或正式构建配置；不推送、不打 tag、不安装/卸载软件。

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

## 2. 产物与隔离方式

- 产物：本工作区 `build/jpackage/DataCube/DataCube.exe`，496640 字节。
- 对捆绑运行时执行 `runtime/bin/java.exe --list-modules` 成功，含 `com.datacube`、`java.sql@25.0.1`、`javafx.controls@25`。这只验证运行时命令，不代表 GUI 已启动。
- `DataCube.exe` SHA-256：`BC48477461F3494B08E25F5996B5F50F668F7FD672258C992F119F8056827F01`。这是启动器哈希，不是整包完整性校验。
- 本地构建使用默认版本 `3.0.0`，**仅用于本地验收，不是下一发布号**。正式版本须经维护者确认后重新构建。
- 创建独占空目录 `C:/Users/hetia/AppData/Local/Temp/datacube-p02-home-170b059b15a840b3b2c1bbd7ba2937ee`。
- 为避免读取真实连接、凭据和历史，仅修改生成产物 `build/jpackage/DataCube/app/DataCube.cfg`：在 `[JavaOptions]` 下增加 `java-options=-Duser.home=C:/Users/hetia/AppData/Local/Temp/datacube-p02-home-170b059b15a840b3b2c1bbd7ba2937ee`。没有修改受版本控制的 `build.gradle`。
- 当前隔离配置 SHA-256：`C74EFDC11360F1F8D49273C1B850F106C03612EE8BC3284FF01C9732AEBB33EE`。该含本机绝对路径的产物不能直接作为 Release 分发。
- 源码显示 GUI 启动会在显示主窗口后触发公共更新检查。因此隔离 `user.home` 不等于禁止网络；本轮没有宣称离线运行已验证。

## 3. 桌面尝试与停止原因

使用 computer-use 技能，通过受支持的 Windows 控制接口尝试启动上述确切 exe。启动前窗口列表只有 Codex，没有 DataCube 或 Excel。

1. 首次 `launch_app` 返回 `GetCursorPos failed: 拒绝访问。 (0x80070005)`。
2. 重新读取窗口列表，仍没有 DataCube；按技能允许的恢复流程重试一次，得到相同错误。
3. 停止 UI 路径，没有改用 PowerShell UIAutomation、COM、键盘宏或其他接口绕过；已提示维护者确认桌面是否解锁且当前会话可交互。
4. 隔离目录核对仍为空。没有观察到应用启动、执行点击、读取真实配置或保存导出文件；不能将测试工具访问错误归为产品启动失败。

此前 Excel 文件对话框因可能暴露无关私人文件元数据被工具安全检查拒绝的路径亦不重试、不绕过。本轮没有启动 Excel，不能据 OOXML、独立渲染器或单测改写真实 Excel 的验收状态。

## 4. 验收矩阵与后续恢复点

| 项目 | 本轮状态 | 后续所需证据 |
| --- | --- | --- |
| 本地完整单测 | 通过，3 项 live 明确跳过 | 改动后按风险重跑 |
| jlink / jpackageImage | 通过 | 正式版本号确认后重新构建 |
| 捆绑运行时模块列表 | 通过 | 不替代 GUI 启动 |
| 隔离空配置首次启动 | 未验收，桌面访问受限 | 实际主窗口与空连接引导 |
| 合成旧连接/设置重启保留 | 未验收 | 只用合成连接，不展开连接/执行 SQL；核对连接 ID、名称与设置 |
| 实际安装器升级/卸载 | 未执行 | 另行授权；免安装启动不等于安装升级 |
| 全部已加载范围切换并保存 | 未验收 | 对比当前筛选行数与加载顺序，实际文件内容 |
| 隐藏列输出 | 未验收 | 隐藏列不进入两种范围文件，可见列顺序正确 |
| 弹窗 Tab / Enter / Esc | 未验收 | 观察独立弹窗焦点、确认及取消后无写入 |
| 筛选输入后防抖到期前立即导出 | 未验收 | 可靠观测时间边界，不将手工慢操作冒充边界验证 |
| 真实 Excel 冻结/换行/自动行高 | 未验收 | 允许访问的纯合成工作簿中的直接观察 |
| 同 SHA 远端 Verify | 未执行 | 获授权推送后的 jobs 结果 |
| tag / Release | 未执行 | 版本、发布授权及发布 gate 全部满足 |

既有五格式实际保存、筛选 CSV、列重排/排序、预览许可和 SQL 拒绝证据继续有效，但不扩写为上述未验收组合。本轮没有重新执行这些已有通过项，详见[安全导出验收](2026-08-30-safe-result-export.md)和[XLSX 可读性验收](2026-08-30-query-xlsx-readability.md)。

恢复工作时先核对工作树、构建基线及隔离 cfg，不要直接启动用户已安装实例。若重新构建覆盖了生成 cfg，必须在 GUI 启动前重新设置独占 `user.home`。窗口可交互后补完矩阵；期间使用合成数据，不访问 `.testagent/`、公司数据库、真实历史或凭据。当前分支和本地产物暂保留供续验，P0.2 未标记完成。

## 5. 文档审查与当前交付状态

- 候选说明 `bfc980c` 完成独立任务审查，符合要求且质量 Approved，无待修发现；主代理核对了历史五格式及后续 XLSX 文件/渲染证据，不将其等同于本轮真实 Excel 验收。
- 整个文档增量 `edce8a2..419ddb9` 完成独立只读审查：0 Critical、0 Important、0 Minor，允许保留文档增量，不代表 P0.2 完成。
- 主代理再次核对本轮相对 Markdown 链接及 `git diff --check`，均通过；`src`、`test`、`build.gradle`、工作流相对此基线无变更。
- 由于阶段验收尚未完成，独立分支及隔离产物暂保留，尚未合并回 `main`。桌面恢复后继续本表，再按用户授权本地合并；不自动扩大为推送、安装升级或发布。
