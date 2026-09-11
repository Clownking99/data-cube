# 表/视图限定名称复制验收

基线 main `025ca85`，独立分支 `codex/object-qualified-copy`；[设计与实现顺序](../specs/2026-09-11-object-qualified-copy.md)。
使用 code-testing-agent 技能对本次小增量补齐边界回归，沿用 JUnit 5、FxUiTestSupport、DraftConnectionProbe 与注入写入器；不访问系统剪贴板进行单元测试。

## 要求与证据

| 要求 | 精确测试 |
| --- | --- |
| PG/Oracle 仅生成两个完整限定的标识符，保留空格、点号、大小写、双引号、Unicode，SQL 片段不逃逸引用 | `SqlObjectNamesTest.preservesExactMetadataAsTwoQuotedIdentifiersWithoutAddingSql` |
| 无效名称拒绝，不清洗为其他对象 | `rejectsUnrepresentableNamesInsteadOfCleaningThem` |
| 1,023 / 1,024 / 1,025 输入长度边界；转义后可增长但不截断 | `boundsEachInputIdentifierBeforeQuoteExpansion` |
| 缺失对象、未知方言与 Redis 拒绝 | `rejectsMissingObjectOrUnsupportedDialect` |
| 构造不复制；写入 true 才报成功，仅复制名称，无连接字段；四类数据库副作用为 0 | `ConnectionTreeClipboardTest.copiesOnlyQualifiedNameAndReportsConfirmedSuccessWithoutDatabaseAccess` |
| 写入 false / 异常覆盖旧成功提示，不泄露异常；再次显式点击可成功 | `failedWriteReplacesPreviousSuccessAndCanBeRetriedWithoutLeakingErrors` |
| 缺失/变更连接、无效名称在写入前拒绝，不影响已有剪贴板 | `invalidOrChangedTargetIsRejectedBeforeWriting` |
| 真实 TreeCell 四种表/视图菜单写入原目标，另一选中连接不受影响，不调用其他对象动作；刷新清理反馈 | `ConnectionTreeQualifiedCopyTest.actualCellMenuCopiesItsOriginalTargetWithoutChangingSelectionOrCallingOtherActions` |
| 脱离树、换值、换所属连接、刷新、关闭、状态行、错误连接、Redis、空节点和注册配置变化不写剪贴板 | `staleOrIneligibleMenuCannotWriteClipboard` |
| 原 SELECT 文本/被动标签、实际生成菜单与树查找不退化 | `TableSelectSqlTest` / `TableSelectSqlTabsTest` / `ConnectionTreeSelectSqlTest` / `ConnectionTreePaneFindTest` |

定向 84 项全部通过，首次 20s；收尾删除测试中的未使用 import 后重跑 10s，均为 exit 0：

```powershell
.\gradlew.bat test --tests com.datacube.sqleditor.SqlObjectNamesTest --tests com.datacube.sqleditor.TableSelectSqlTest --tests com.datacube.fx.ConnectionTreeClipboardTest --tests com.datacube.fx.ConnectionTreeQualifiedCopyTest --tests com.datacube.fx.ConnectionTreeSelectSqlTest --tests com.datacube.fx.TableSelectSqlTabsTest --tests com.datacube.fx.ConnectionTreePaneFindTest --no-daemon --console=plain
```

## 审查边界

- `SqlObjectNames` 从上轮 SELECT 中提取原样规则，`TableSelectSql.generate` 的输出与异常语义不变。
- SELECT 与复制共用捕获节点/连接的菜单守卫，不依赖当前选区或可复用单元格；剪贴板写入只在所有校验后发生，不读取旧内容。
- 复制反馈无后台任务或计时器，不触发数据库 provider、会话、元数据或 SQL；异常详情不显示。系统层面的剪贴板拒绝不承诺旧内容绝对不变，只保证不假报成功。
- 不修改执行、文件保存、草稿格式、工作区恢复、连接持久化、CI 或版本；`.testagent/` 未读取/修改/暂存。仅合并本地 main，不推送/tag/发布。

## 全量与桌面

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- 构建使用独占临时目录的 ASCII 短路径作为 `java.io.tmpdir`。exit 0，1m 58s；原有 unchecked 编译提示与 jlink 探测/JEP 493 提示未隐藏。
- JUnit XML 汇总 1,977 项：1,974 通过、0 失败、0 错误、3 项既有 live 测试跳过。未使用真实数据库或操作系统剪贴板运行单元测试。
- `jimage list` 检出 `SqlObjectNames` / `ConnectionTreeClipboard`，未检出 `DesktopFixture` / `DraftConnectionProbe`；正常 `build/jpackage/DataCube/app/DataCube.cfg` 指向正式 DataCubeFx 入口。
- 复制开发镜像至 `build/desktop-fixture-image`，只在副本通过 patch-module 复用既有 `TableSelectSqlDesktopFixture`，指定本 worktree 的 `build/select-desktop-profile`；未改正式打包配置，也未读取上轮 profile 或真实连接。
- 首次 Computer Use 启动副本时返回 `GetCursorPos failed: 拒绝访问。 (0x80070005)`，当时停止操作并请求解锁。用户确认桌面解锁后，以下实际窗口验收已完成，未因此修改生产代码。

### 解锁后的桌面补验

- 使用 Computer Use 技能操作隔离镜像的真实 AppShell，窗口标题为“DataCube - SELECT 合成验收”。只使用合成 PostgreSQL / Oracle 节点；不读取真实连接或原剪贴板内容。
- 在尚无 SQL 标签时右键 PostgreSQL 表 `Order"Line`，点击“复制限定名称”：左栏显示成功及目标连接提醒，中央仍是开始页，没有自动创建标签。
- 另行点击已有“生成 SELECT 到新 SQL（不执行）”动作作为粘贴目标。定位 SQL 编辑区并通过可见光标、行列位置确认焦点，换行后按 Ctrl+V，实际文本为 `" Sales "."Order""Line"`：保留 Schema 前后空格和内部双引号，不附加 SELECT 或分号。编辑后显示未保存标记，仍提示待绑定 Demo PostgreSQL、尚未创建专用会话，结果区为空。
- 右键 Oracle 视图 `Monthly View` 并复制时，已有 SQL 文本与标签数量不变；手动换行、Ctrl+V 后得到 `"REPORTING"."Monthly View"`。当前脚本仍待绑定 Demo PostgreSQL，未切换为 Oracle。粘贴只是合成文本验证，未执行这些行。
- 明暗主题分别实测；左栏缩至约 185px 时成功提示自动换行且完整可见，未挤占或遮挡 SQL 编辑区。
- 点击顶部“刷新”，树恢复为两个折叠的合成连接，旧复制提示清除；现有 SQL 文本、标签和待绑定连接不变。没有再次展开连接或执行查询。
- 正常关闭本轮隔离窗口，随后窗口列表确认其已退出。只向系统剪贴板写入合成对象名，未读取或恢复原内容；未保存截图文件，证据为本轮工具返回的窗口截图。
- 实际系统剪贴板成功写入及手动粘贴已验证；操作系统拒绝/异常路径由注入写入器的自动测试覆盖，未进行系统故障注入。以上不能代表真实数据库连接或 SQL 执行验收。
