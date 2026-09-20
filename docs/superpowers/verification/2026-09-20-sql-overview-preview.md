# 执行概览 SQL 摘要验收

基线 main `d3707dc`，分支 `codex/sql-overview-preview`；[设计](../specs/2026-09-20-sql-overview-preview.md)。只使用合成 SQL/结果及可丢弃 profile，不访问用户连接、历史或剪贴板，不操作 `.testagent/`。

## 需求与证据

| 需求 | 自动回归证据 |
| --- | --- |
| 多行摘要、注释/字面量按纯文本显示，原快照不变 | `SqlScriptPreviewTest.previewFlattensLinesButLeavesCommentsLiteralsAndCapturedSqlIntact` |
| 120 单元边界、省略号、代理字符对 | `previewHasExactBoundWithExplicitTruncation`（119/120/121）、`previewDoesNotSplitSurrogatePairAtBoundary`（118/119 前缀） |
| 空/未提供、字段或总预算截断不会混淆 | `missingOrWhitespaceSqlIsNotConfusedWithBudgetOmission`、`inheritedTruncationRemainsVisibleEvenWhenFlattenedPreviewIsShort`、`aggregateBudgetOmissionDoesNotLookLikeMissingSql`、`truncatedWhitespacePrefixDoesNotClaimThatOriginalSqlWasEmpty` |
| 摘要降序 + 异常筛选后仍对应原 SQL 和结果，编辑后快照不变 | `SqlOverviewPreviewTest.previewSortAndFailureFilterKeepOriginalSqlAndResultIdentity`：重复编号、换行/字面量、查看结果/Enter 详情、编辑器/选区/撤销/源文件、零网络探针 |
| 新查询/新批次/清空/关闭不复用旧摘要 | `replacementDoesNotReuseOldPreview`；普通查询没有额外摘要列 |
| 明暗宽窄布局、长摘要不撑开面板 | `previewColumnStaysBoundedAndDoesNotExpandThePanel`（480/980 × dark/light）；保留表格横向阅读 |

## 自动验证过程

- RED：新增 UI 类 10 项均因缺少 SQL 摘要列失败，23 秒 exit 1，无编译错误。
- 实现后：两类新增 23 项，加概览筛选/查看结果、脚本详情和报告相关回归，共 118 项全部通过，19 秒 exit 0。
- 自审：原结果摘要仍保留在行值索引 3，新 SQL 摘要使用索引 4；视觉列次序为编号/类型/耗时/SQL 摘要/结果。未改任何查询执行、快照保留上限或详情文本来源。检查测试的具体输出、身份断言、无副作用和截断边界，不按测试数量代替行为证据。

## 最终验证

PowerShell 设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后运行：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- 2 分 50 秒 exit 0：主测试 3,065 项，其中 3,062 通过、3 项既有 live 跳过，0 failures/errors；buildSrc 8 项通过，0 failures/errors。两类新增 23 项已纳入全量结果。
- 生产镜像保持 `com.datacube/com.datacube.DataCubeFx` 入口；测试夹具 jar 只 patch 到 `build/overview-preview-desktop-image` 独立副本，并指定一次性 `script-details-desktop-profile`。
- 保留既有 unchecked 编译、`JAVA_TOOL_OPTIONS` 和 JEP 493 相关打包辅助提示，最终构建成功。`git diff --check` 通过。

## 隔离桌面与集成状态

首次启动隔离镜像遇到 `GetCursorPos failed: 拒绝访问。 (0x80070005)`，已停止 UI 输入并请用户解锁。后续“继续推进产品”时重新检查，隔离窗口不存在，启动仍返回相同错误；没有重复输入或尝试绕过桌面限制。

- 再次核对现有全量 XML：3,065 total / 3,062 passed / 3 skipped / 0 failures/errors；本次没有将旧结果冒充重新运行。
- 重新审查生产差异和两类回归，未修改已经测试的源代码。生产 `com.datacube` 提取目录包含 824 文件、809 class、0 Fixture 类；生产配置 SHA-256 为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。本轮隔离程序进程数为 0。
- 将实现、测试和验收边界保存为本地分支检查点，不把桌面验收标记完成；待桌面恢复后验证摘要列排序、原始多行详情、明暗窄窗横向滚动，再快进本地 main 并复验。
- main 保持 `d3707dc`，不推送、不打 tag，不修改 `.testagent/`。
