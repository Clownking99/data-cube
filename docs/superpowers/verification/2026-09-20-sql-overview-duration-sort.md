# 执行概览耗时排序验收

基线 main `9d9d43c`，分支 `codex/sql-overview-duration-sort`；[设计](../specs/2026-09-20-sql-overview-duration-sort.md)。仅概览显示值变更，不访问真实连接/数据库/SQL/历史/剪贴板，不操作 `.testagent/`。

## 回归证据

新增 `SqlOverviewDurationSortTest`（8 项）复用现有真实 JavaFX 编辑器/表格及离线探针：

| 需求 | 证据 |
| --- | --- |
| 按 long 毫秒数升降序，显示和默认顺序不变 | `sortsByNumericMillisWithoutChangingLabelsSnapshotsOrDefaultOrder`：0/9/10/80/1000、超过 int 上限及 Long.MAX_VALUE 相邻值；明确断言所有行顺序、显示、报告/下拉框、编辑器/选区/撤销/源文件、零网络探针 |
| 同耗时稳定，支持次级列排序 | `equalDurationsAllowSecondaryNumericIndexSort`：相同 9ms 保持原始顺序，再按编号升/降序作为次级排序 |
| 排序和异常筛选仍指向原始结果/详情 | `filterAndSortKeepSelectedOutcomeAndOriginalDetails`：两次切换异常筛选、选中身份、查看 timeout 原结果/Enter 原 SQL、1000ms 详情 |
| 普通查询字段不受影响，新批次恢复默认顺序 | `switchingToQueryDoesNotChangeUserDataOrItsComparator`：同名“耗时”文本字段仍按其原规则排序，查询可导出；新概览回到执行顺序后可再排序 |
| 原有毫秒文本仍能由默认单元格呈现 | `defaultCellsStillRenderMillisTextInBothThemes`：dark/light 640 宽布局，检查实际 TableCell 文本与列标题；不是原生桌面验收 |

## 复现与修复

- 原实现使用字符串 `entry.elapsedMillis() + "ms"`，真实 TableView 升序将 1000ms 排在 10ms/80ms/9ms 前。初次 RED 8 项中 6 项失败（23 秒）；其中一项查询兼容测试最初选到了不可排序的行号列，改为真实数据列后重新 RED，6 项均因概览排序错误失败，2 项原有文本呈现通过（10 秒 exit 1）。
- 最小修复：`SqlScriptDetails` 内私有 `ElapsedMillis` 值以 `Long.compare` 比较、`toString` 保持原 `ms` 标签，只替换概览第三个行值。不增加自定义全局比较器，不修改统计、查询列、排序选择保护或 UI 布局。
- 修复后五类定向 106 项全部通过，17 秒 exit 0；`git diff --check` 通过。

## 最终验证与集成

PowerShell 设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后运行：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- 2 分 38 秒 exit 0：主测试 3,073 项，其中 3,070 通过、3 项既有 live 跳过、0 failures/errors；buildSrc 8 项通过，无失败、错误或跳过。新增类 8 项纳入该全量结果。
- 保留既有 unchecked 编译、`JAVA_TOOL_OPTIONS` 和 JEP 493 相关打包辅助提示，最终构建成功。自审后无生产修改，`git diff --check` 通过。
- 本轮不重试受限的桌面通道；[上一轮 SQL 摘要待验清单](2026-09-20-sql-overview-preview.md)继续保留，恢复桌面后可一起补验耗时表头原生排序。不宣称原生交互、live 数据库或发布已验收。
- 待提交并本地快进 main 后补记主分支复验。不推送、不打 tag；`0.0.0` 仅为本地开发镜像版本。
