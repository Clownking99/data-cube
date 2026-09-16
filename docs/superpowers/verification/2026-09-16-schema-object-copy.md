# 对象查找内复制限定名称验收

基线 main `5b180f8`，分支 `codex/schema-object-copy`。设计见[对象查找复制](../specs/2026-09-16-schema-object-copy.md)。测试技能用于行为边界回归；按项目约定不增加测试流水线工件。不读取、修改或暂存 `.testagent/`，不触碰真实连接、业务 SQL 或用户系统剪贴板，不推送、不打 tag。

## 需求与证据

| 需求 | 直接回归 |
| --- | --- |
| 独立复制入口，初始不默认选择或写剪贴板 | `SchemaObjectCopyTest.offersExplicitCopyWithoutDefaultSelection` |
| PG/Oracle 长名称、空格、内嵌引号与 Unicode 完整引用；复制保留已显示窗口、选择、筛选和预览，生成 SELECT 仍独立确认 | `copyKeepsShownDialogAndExactSelectionThenSelectStillConfirms`（2 种，含复制按钮 Enter 不误确认、数据库探针为零和不改变树反馈） |
| 返回 false/异常替换旧成功提示，安全失败可重试，不追加元数据请求 | `failedCopyReplacesSuccessAndRetriesWithoutReloadingOrLeakingDetails`（2 种） |
| 初始、加载、失败、空列表、未选择、无匹配、重读、来源失效、关闭、禁用不调用复制回调 | `unusableCandidatesNeverInvokeCopy`（10 种） |
| 选择/筛选/重新读取/来源变化清除旧复制反馈，不再次写入 | `candidateChangesClearOldFeedbackWithoutWritingAgain`（4 种） |
| 已移除或变更的连接、非法名称在写入前拒绝 | `clipboardTargetAndNameAreValidatedBeforeWriting`（3 种） |
| 复制后 Esc 取消无 Dialog 结果，已显式写入不撤销，关闭后不能再写 | `cancelAfterCopyDoesNotConfirmOrUndoTheExplicitWrite` |
| 480×548 窄窗明暗主题下复制、反馈、重读、预览和确认不溢出；目标/预览保留各自 2/3 行高度 | `narrowThemesKeepCopyFeedbackAndConfirmationWithinViewport`（2 种） |
| 既有树复制、名称边界、查找入口与异步生命周期不回退 | `ConnectionTreeClipboardTest`、`SqlObjectNamesTest`、`SchemaObjectSearchDialogTest`、`SchemaObjectSearchLifecycleTest`、`SchemaObjectFindEntryTest` 联跑 |

## 自动验证

设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，使用 Gradle wrapper 并加 `--no-daemon --console=plain`。

1. 入口 RED：41 秒 exit 1，查找窗口缺少 `schema-object-copy`；实现后与既有查找/复制联跑 15 秒 exit 0。
2. 完整定向命令 13 秒 exit 0：

```text
test --tests com.datacube.fx.SchemaObjectCopyTest
     --tests com.datacube.fx.SchemaObjectSearchDialogTest
     --tests com.datacube.fx.SchemaObjectSearchLifecycleTest
     --tests com.datacube.fx.ConnectionTreeClipboardTest
     --tests com.datacube.sqleditor.SqlObjectNamesTest
     --tests com.datacube.fx.SchemaObjectFindEntryTest
     --no-daemon --console=plain
```

3. 首轮全量 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`：3 分 1 秒 exit 0，2,735 项中 2,732 通过、3 项既有 live 跳过；buildSrc 8 项通过。
4. 首轮隔离镜像实测复制成功，但 480 窄窗显示反馈时目标和预览被压缩，类型一行需要滚动。新增实际高度断言 RED：12 秒 exit 1，明暗两种均失败。生产代码保留 TextArea 的首选行高，改由候选列表缩小；上述 91 项定向联跑 15 秒 exit 0。首轮实例正常关闭，精确 exe 路径进程数为 0。
5. 修订后首个全量重跑被会话中断，没有完整报告或镜像，不计成功。确认无残留构建进程后，使用 `.gradle/` 下的本轮后台验证脚本保存输出及退出码，再执行相同全量命令；这些临时验证文件已确认被 Git 忽略，不入库。

6. 最终相同全量命令 2 分 32 秒成功，日志结尾 `SCHEMA_COPY_VERIFICATION_EXIT=0`。重新汇总 XML：2,735 项，2,732 通过、3 项既有 live 跳过，0 failures/errors；buildSrc 8 项、0 failures/errors/skipped。保留既有 unchecked/toolchain/JEP 493 提示，不宣称构建无警告。

## 最终开发镜像与桌面

- 正式开发镜像入口仍为 `com.datacube/com.datacube.DataCubeFx`，版本 `0.0.0`，没有 profile/patch 参数。`runtime/lib/modules` SHA-256：`DB204108B01BE410BAB60DBD065B1B9D40F0FC8F5ACA6D69EF5D19E96079B61E`。模块含 `ConnectionTreeClipboard$CopyResult`，不含桌面夹具或 `DraftConnectionProbe`。
- 仅在 `build/schema-copy-final-desktop-image/` 副本注入测试夹具，使用本轮 `schema-object-copy-desktop-profile`，真实 Dialog、任务执行器和复制适配器搭配合成名称/内存写入器；没有访问真实保存连接或系统剪贴板。使用 computer-use 技能的原生窗口输入和截图复验，不把 UIA 的迟到文本当作实际焦点/显示结果。
- 暗色宽窗选择 `Order "Quoted" 中😀` 后点击复制，标题记录精确 `"Exact Schema"."Order ""Quoted"" 中😀"`，写入计数 1；选择、预览、查询及窗口保留，无 SELECT 结果。
- F7 缩至 480 窄窗，复制反馈显示时目标两行、预览三行及类型仍完整可见；F6 浅色主题同样通过。列表缩小而非压缩身份信息，确认/取消与复制按钮均可见。
- 复制按钮保留焦点时，原生 Enter 再次复制、计数增至 2，没有生成 SELECT 或关闭。单元测试中的单次合成 `KEY_PRESSED` 不包含平台完整按键序列，因此不把其“未再次写入”断言扩展为原生 Enter 不触发复制的承诺。
- F8 注入写入异常，点击复制显示固定失败文案，成功计数仍为 2，选择保留，未展示异常明细。关闭注入后以原生空格重试成功，计数增至 3，成功提示替换失败提示。
- Ctrl+F 返回筛选框，输入 `REPORT` 后显示 1/3，旧复制提示及旧选择清除，复制/SELECT 禁用；↓ 选择视图后复制，精确 `"Exact Schema"."order_report"`、计数 4，仍保留筛选和窗口。
- Esc 正常退出；随后精确夹具 exe 路径的进程数为 0。隔离 profile 顶层只有 `settings.properties` 与 `.openjfx/`。

本地 main 集成结果待补充。上述证据不代表真实数据库、系统剪贴板服务、完整 AppShell 联调、用户效率实验、远端 CI 或发布验收。
