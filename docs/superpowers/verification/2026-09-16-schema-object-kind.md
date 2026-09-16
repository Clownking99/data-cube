# Schema 对象类型筛选验收

基线 `105a45f`，分支 `codex/schema-object-kind`；[设计](../specs/2026-09-16-schema-object-kind.md)。测试技能用于单个 Dialog 类的直接行为回归，依项目约定不增加通用流水线工件或子代理。没有读取、修改或暂存 `.testagent/`，不使用真实连接、业务 SQL 或用户剪贴板。

## 需求与证据

以下新增回归均位于 `SchemaObjectKindFilterTest`，使用合成名称、可控提交器和内存复制记录。

| 行为 | 直接测试 |
| --- | --- |
| 三个类型选项、默认全部，构造不请求 | `offersAllTablesAndViewsWithoutStartingWork` |
| 名称与类型 AND，大小写/空白及字面规则，不自动复制/选择/确认/请求 | `nameAndTypeIntersectWithoutLoadingCopyingOrConfirming`（3 类） |
| 同名不同类型保留完整身份，排除则清空而非替换，旧复制提示清除 | `preservesExactCandidateOrClearsItWithoutReplacingSameNamedOtherType`（表/视图） |
| 只有名称、只有类型、两者同时筛选均能一键清除，不追加请求 | `clearResetsBothFiltersWithoutLoadingOrSelecting`（3 种） |
| 读取中用最新条件，重读/失败重试保留条件，旧结果不覆盖 | `pendingLoadAndRetryUseLatestFiltersButNeverOldSelection` |
| 在展示上限之前过滤，199/200/201 边界及越过首屏的目标可检索，分母不变化 | `typeFilterAppliesBeforeDisplayCapAndKeepsFullSnapshotCount`（3 个边界） |
| 真空 Schema 与当前类型无匹配明确区分 | `emptySchemaAndTypeWithoutMatchesRemainDistinct`（2 种） |
| 来源变化、关闭、禁用后改类型不能复制/生成，失效类型入口禁用 | `staleScopeCannotCopyOrConfirmAfterTypeChanges`（3 种） |
| 不存在的类型不放宽结果，清除可恢复 | `missingTypeCannotBroadenResultsAndClearCanRecover` |
| 类型 Enter 不误确认，Esc 先收起下拉，明确候选 Enter 确认，新窗口不记类型 | `typePopupEscapeOnlyClosesPopupAndExplicitCandidateEnterStillConfirms` |
| 480×548 明暗窄窗，类型、计数、200 上限提示、复制反馈、身份与确认不挤出 | `narrowThemesKeepTypeCountAndIdentityVisibleWithCopyFeedback`（2 主题） |

## 自动验证

所有 Gradle 命令使用 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 及 `--no-daemon --console=plain`。

1. 入口 RED：`test --tests com.datacube.fx.SchemaObjectKindFilterTest`，1 分 1 秒 exit 1，原因是缺少 `schema-object-kind`，不是编译错误。
2. 实现后 `test --tests com.datacube.fx.SchemaObjectKindFilterTest --tests com.datacube.fx.SchemaObjectSearchDialogTest --tests com.datacube.fx.SchemaObjectCopyTest --tests com.datacube.fx.SchemaObjectSearchLifecycleTest`，22 秒 exit 0。
3. 进一步将窄窗回归加强为实际 201 个匹配的长计数提示，随后全量 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`；使用 Git 忽略的 `.gradle/schema-kind-verification.ps1` 后台记录日志与明确退出码，避免会话中断丢失验证状态。

4. 最终全量与镜像命令 4 分 7 秒 exit 0，日志结尾 `SCHEMA_KIND_VERIFICATION_EXIT=0`。XML 汇总 2,757 项：2,754 通过、3 既有 live 跳过，0 failures/errors；新增类型筛选 22 项全部通过。buildSrc 8 项、0 failures/errors/skipped。既有 unchecked、JAVA_TOOL_OPTIONS/toolchain、JEP 493 提示仍在，不宣称构建无警告。

## 镜像与合成桌面

- 最终 `build/jpackage/DataCube` 仍为 DataCubeFx 入口、版本 `0.0.0`、原 JVM 参数，无 profile/patch 注入；运行时含新增 `KindFilter`，不含 `SchemaObjectCopyDesktopFixture` 或 `DraftConnectionProbe`。`runtime/lib/modules` SHA-256：`E5DA569C0A7DC78112F6584AAB1FB7CA8FF34210003095895156A41FAEE7272E`。
- 复用既有 `SchemaObjectCopyDesktopFixture`，不新增桌面夹具源码；仅在本轮 `build/schema-kind-desktop-image` 副本注入其 4 个测试 class。`user.home` 指向本 worktree 新建的 `build/schema-object-copy-desktop-profile`，使用合成名称与内存复制适配器、零数据库探针；不使用系统剪贴板或真实保存连接。
- computer-use 原生窗口实测：初始「全部」为 3/3，没有默认选择；选择合成表并复制后，内存计数 1、显示完整引用。切为「仅视图」后为 1/3，原表选择/预览/复制反馈清除，复制及 SELECT 禁用，窗口保持打开。
- 明暗 480 窄窗均可见类型、计数、固定连接和完整身份。仅视图下 Ctrl+F 输入表名 `customers` 得到 0/3；点击「清除筛选」一次恢复空名称、全部类型及 3/3，不自动选中。
- Tab 可从空查询进入类型，空格展开下拉；Esc 只收起下拉且窗口保持。再次空格、↓、Enter 选择「仅表」，得到 2/3；已选中表时重新打开类型菜单并按 Enter 仅确认菜单项，再按 Enter 仍不生成 SELECT 或关闭。
- 仅表下显式复制使内存计数增至 2，保持类型与对象；明暗窄窗的复制反馈出现后目标两行、对象三行和全部底部按钮可见。最后 Esc 正常退出，窗口列表为空，精确预览 exe 路径的进程数为 0；隔离 profile 顶层仅 `.openjfx/` 与 `settings.properties`。
- 单元测试另证明加载中的条件、重读/失败/迟到回调、空 Schema、同名异类型、199/200/201 边界及最终 TableRef；桌面仅验证上述三对象夹具交互，不把该层验证扩大到全量真实元数据或实际 AppShell 打开 SQL。

## 本地 main 集成

- 功能提交 `2dfab3c`，6 个文件；差异检查通过。合并前再次核对 main 为 `105a45f`、已跟踪文件干净，以 `git merge --ff-only codex/schema-object-kind` 快进；用户 `.testagent/` 保留，不入库。
- main 执行 `test`，指定 `SchemaObjectKindFilterTest`、`SchemaObjectCopyTest`、`SchemaObjectSearchDialogTest`、`SchemaObjectSearchLifecycleTest`、`ConnectionTreeClipboardTest`、`SqlObjectNamesTest`、`SchemaObjectFindEntryTest`，并构建 `jpackageImage -PappVersion=0.0.0`；1 分 32 秒 exit 0。XML 汇总 113 项全部通过、0 failures/errors/skipped。
- main 镜像保持 DataCubeFx 正式入口、原 JVM 参数及无夹具注入；从 main/已验收 worktree 的运行时分别提取 `com.datacube`，813 个文件逐一比较 SHA-256，无缺失、增加或内容差异。整个 `runtime/lib/modules` SHA-256 同为上文 `E5DA569C…EE7272E`。
- 仅本地提交与合并，未推送、打 tag 或发布；版本 `0.0.0` 仅作开发镜像验证。

未宣称真实数据库兼容性、系统剪贴板服务、完整 AppShell 联调、用户效率实验、远端 CI 或发布完成。
