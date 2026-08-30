# 查询结果导出验收记录

日期：2026-08-30

状态：自动化实施验收通过；桌面人工验收未验证。本文件不把自动化测试替代人工观察结论。

范围：SQL 编辑器 XLSX/CSV/SQL/HTML/XML 和复制 INSERT；不含整表、迁移、Redis、pg_dump。

## 自动化证据

- 完整 non-headless：`$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'; ./gradlew clean test --no-daemon --console=plain`，退出码 0，`BUILD SUCCESSFUL`。
- JUnit XML（`build/test-results/test/TEST-*.xml`）统计：135 个 suite、1196 个测试、1193 通过、0 failures、0 errors、3 skipped。该统计由 XML 属性实际求和，不是 Gradle `UP-TO-DATE` 推断。
- skipped 均为明确禁用的 live 集成测试，未使用公司连接或凭据：Redis 五类型 smoke 缺少 `DATACUBE_REDIS_HOST` 和 `DATACUBE_REDIS_PASSWORD`；Oracle 与 PostgreSQL Schema Diff smoke 缺少显式写入 gate 和完整 provider 环境。
- 链接能力及 live 测试的每一项跳过都需说明原因；headless 导致的 UI 跳过不能记为通过。
- 文件失败注入覆盖旧文件字节不变、仅本次临时文件清理、关闭流失败、目标锁冲突、清理失败、目录/链接目标拒绝和不支持原子移动。

- `git diff --check` 在本轮自动化证据后通过；编译器仍报告既有 `SqlEditorResultFilterContractTest` 未检查操作提示（非失败）。

## 桌面验收

- 默认范围、全量选项、零匹配、隐藏/重排列、升降序、刚输入搜索就导出。
- 同样的数据分别从顶部和右键复制 INSERT，取消不写剪贴板。
- 行截断与预览值分别显示，SQL 拒绝特殊值，非 SQL 需显式确认。
- 深色/浅色、最小可用窗口、键盘 Tab/Enter/Esc 和长提示不遮挡按钮。
- 仅使用合成数据、临时目录，不打开公司数据库或用户已有导出文件。

状态：未验证；由后续 synthetic desktop 验收更新。

## 交付限制

- 未验证项必须保留说明，不用自动化测试替代人工观察结论。
- 不保证跨库 SQL 恢复、不改变 CSV 公式策略、不承诺恶意外进程竞态或断电持久化。
- 初始 CLOB 捕获会流式读取并对完整内容做指纹，仅保留有界预览；导出不再 JDBC 重读。大字段的 live 数据库延迟未验证。
- 本阶段不自动推送、合并或打 tag。
