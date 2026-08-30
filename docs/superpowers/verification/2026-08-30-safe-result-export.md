# 查询结果导出验收记录

日期：2026-08-30

状态：自动化实施验收通过；桌面人工验收未验证。本文件不把自动化测试替代人工观察结论。

范围：SQL 编辑器 XLSX/CSV/SQL/HTML/XML 和复制 INSERT；不含整表、迁移、Redis、pg_dump。

## 自动化证据

- 完整 non-headless：`$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'; ./gradlew clean test --no-daemon --console=plain`，退出码 0，`BUILD SUCCESSFUL`。
- JUnit XML（`build/test-results/test/TEST-*.xml`）统计：135 个 suite、1196 个测试、1193 通过、0 failures、0 errors、3 skipped。该统计由 XML 属性实际求和，不是 Gradle `UP-TO-DATE` 推断。
- 主代理在任务 8 提交 `e74b29c` 后独立重跑上述 `clean test`，退出码 0，用时 25 秒；再次读取 XML 得到相同统计。不是仅转述子代理的测试报告。
- skipped 均为明确禁用的 live 集成测试，未使用公司连接或凭据：Redis 五类型 smoke 缺少 `DATACUBE_REDIS_HOST` 和 `DATACUBE_REDIS_PASSWORD`；Oracle 与 PostgreSQL Schema Diff smoke 缺少显式写入 gate 和完整 provider 环境。
- 本机符号链接拒绝回归实际运行通过；未因符号链接能力限制或 headless 跳过测试。上述三项 live 测试未计入通过数。
- 文件失败注入覆盖旧文件字节不变、仅本次临时文件清理、关闭流失败、目标锁冲突、清理失败、目录/链接目标拒绝和不支持原子移动。

- `git diff --check` 在本轮自动化证据后通过；编译器仍报告既有 `SqlEditorResultFilterContractTest` 未检查操作提示（非失败）。

## 桌面验收

- 默认范围、全量选项、零匹配、隐藏/重排列、升降序、刚输入搜索就导出。
- 同样的数据分别从顶部和右键复制 INSERT，取消不写剪贴板。
- 行截断与预览值分别显示，SQL 拒绝特殊值，非 SQL 需显式确认。
- 深色/浅色、最小可用窗口、键盘 Tab/Enter/Esc 和长提示不遮挡按钮。
- 仅使用合成数据、临时目录，不打开公司数据库或用户已有导出文件。

状态：交互及视觉验收未验证。2026-08-30 已尝试运行独立合成窗口，具体记录如下：

- 通过本地临时 harness 的 `runExportSmoke` 启动 SQL 编辑器，不配置数据库连接；无公司连接、历史 SQL 或已有导出文件访问。
- 桌面无障碍树确认窗口“DataCube 导出验收 · 合成数据”存在，表内有 3 行、3 列合成数据，“导出结果”和“复制INSERT”入口可见。
- 工具截图为黑屏；重新绑定窗口后再次尝试激活，仍返回 `GetCursorPos failed: 拒绝访问。 (0x80070005)`。没有执行任何点击、键盘、剪贴板或保存操作，不能据无障碍树判定主题、排版或交互通过。
- 已核对进程命令确为本次 `ExportSmokeLauncher` 后，仅停止该合成 Java 进程，未停止其他 Java 进程。此主动停止导致 `runExportSmoke` 返回退出码 1（子进程 -1），不计为成功验收，也不是完整测试套件的失败。
- harness 启动有 JavaFX unnamed-module 警告；它使用单独的 classpath 启动方式，该次运行不证明正式打包启动已验证。
- 仍需在可交互的 Windows 桌面补验上述范围切换、实际文件输出、主题、小窗口及键盘操作。

## 交付限制

- 最终全分支代码审查范围 `c811802..e74b29c`：无未解决 Critical、Important 或 Minor 发现。此前原子移动测试断言、空截断提示占位和任务拒绝覆盖新状态的问题均已修复并复审；代码审查通过不代替桌面验收。

- 未验证项必须保留说明，不用自动化测试替代人工观察结论。
- 不保证跨库 SQL 恢复、不改变 CSV 公式策略、不承诺恶意外进程竞态或断电持久化。
- 初始 CLOB 捕获会流式读取并对完整内容做指纹，仅保留有界预览；导出不再 JDBC 重读。大字段的 live 数据库延迟未验证。
- 本阶段不自动推送、合并或打 tag。
