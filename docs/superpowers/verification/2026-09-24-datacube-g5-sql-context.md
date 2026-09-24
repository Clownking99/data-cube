# G5 / M5 本地验证账本

当前状态：**G5/M5 本地工程完成，已合并 main `0e7ca79` 并独立复验**。维护者已明确授权 G5，沿用原安全及本地操作边界，不扩展 M6–M8。此结论不包含原生桌面、真实数据库或发布验收。

## 基线与范围

- main：`fb849b29d4a2da6ad2cefb34767216e8ea905087`；分支 `codex/datacube-g5-sql-context`。
- worktree：`C:\Users\hetia\.codex\worktrees\datacube-g5-sql-context\朝花夕拾`。
- 日志、XML、JSON、基线探针：`C:\Users\hetia\AppData\Local\Temp\datacube-g5-25a8dbddae8b467291bae91664ae808d`。
- JDK：`D:\jvms_v2.1.6_amd64\store\jdk-25.0.1+8`。Gradle wrapper 9.2.0，离线构建，无依赖安装/更新。
- 本轮只使用 mock JDBC、合成配置与临时文件。未读取/修改 `.testagent/`；未使用真实数据库、保存连接/凭据、用户 SQL 历史或业务文件；无 push/tag/PR/发布/外部联系。

## 实现及边界

| 项目 | 实际行为 | 保守降级 |
| --- | --- | --- |
| M5a | 新增当前语句按钮及可改绑 Ctrl+Enter；F5 选中/全部语义保留；执行前显示行范围，复用原分句器并保留 UTF-16 偏移 | 空白/注释、未闭合词法/括号、缺少主体的 WITH、未知语句类型不退回全文；不替代数据库完整语法校验 |
| 方言边界 | PG dollar quote/E 字符串、Oracle q quote/匿名块/包；Oracle 关键字间或块前注释保留；单独 `/` 与 CR/LF/CRLF | PL/SQL 当前语句需 `/` 与 END 尾部；PG SQL 标准 BEGIN ATOMIC 函数体当前语句/作用域补全明示未支持。原选中/全部入口不被此新动作重定义 |
| 光标终止符 | 分号及紧跟分号的位置可指向刚结束的语句；若已经在紧邻下一语句首 token，则指向下一语句 | 后续空白行不回退到上一语句；不扩大范围 |
| M5b | 每次执行捕获不可变原文、选取范围、分句偏移和编辑版本；PG 原始结构化位置按 Unicode 字符映射为编辑器 UTF-16 偏移 | 只在用户 execute 阶段取位置；Schema 设置、抓取、内部位置、错误文案和 JDBC 改写的 SQL 不猜位置；编辑后即使撤销也提示过期 |
| 定位预算 | 单标签原文快照最多 8 Mi UTF-16 单元，映射前 1000 个 occurrence，清空/新执行/关闭释放；结果详情仍遵守已有摘要预算 | 超限正常执行仍可用，但明确没有定位映射；Oracle 当前未采用未确认的驱动位置 |
| M5c | 明确 schema.table、quoted identifier、CTE 显式列/可证明投影、派生查询、嵌套局部别名遮蔽；双引号候选完整替换 token，过期弹窗不插错位置 | 通配/复杂/集合查询投影、表函数、跨数据库/复合字段路径等不猜；CTE 主体和非 LATERAL 派生表不借用外层 FROM 别名；当前文本上限 2 Mi、20,000 token、32 层 |
| 元数据 | 不再打开编辑器即扫 schema；只读当前明确 Schema 至多 500 个表名、指定表至多 256 个列名；16 项缓存键含完整不可变配置，专用连接与参数化 catalog 查询 | PG Schema 空缺不假定 public；Schema 输入框沿用旧的未引用名称语义，混合大小写可在 SQL 内显式引用 Schema；配置漂移拒绝读取，不借用对象树连接 |
| 超时与取消 | 5 秒 JDBC 查询超时和 UI 等待期限；取消标记即时发布，驱动 cancel 在后台；迟到响应/关闭不刷新；旧读取或取消物理结束前不堆积新连接 | 不能证明所有驱动的 connect/cancel/close 都及时响应；真驱动行为单列待验；失败可 Ctrl+Space 显式重试 |
| 计时 | 分开记录 JDBC execute、ResultSet 获取/读取、FX 渲染准备；不更改原 elapsed 排序值 | 原计时不称全流程；工作线程累计包含交互等待，不含渲染；抓取不含可选列注释；渲染准备不含布局/绘制；未测量显示未测量 |
| 兼容 | 复用原执行准入、生产确认、事务串行队列、取消/关闭/结果预算/固定结果；格式化无扩展及新依赖 | 原生交互、真实数据库、安装升级和发布未验 |

PG 位置仅使用协议大写 `P`（原始查询字符位置），不使用内部 `p/q` 或服务端源码行号；依据 [PostgreSQL 协议文档](https://www.postgresql.org/docs/18/protocol-error-fields.html)。本轮仅查询公开文档，没有连接数据库。

## 行为回归映射

| 断言 | 测试 |
| --- | --- |
| 原始偏移、重复 SQL/Unicode、方言字符串/块/注释、分隔符与不完整输入拒绝 | `SqlCurrentStatementTest`；`OracleSqlRunnerExecutionControlTest` 的匿名块传输及写分类回归 |
| 当前语句按钮、默认 Ctrl+Enter 和改绑实际仅执行选中 occurrence；原 F5 语义兼容 | `SqlEditorResultFilterContractTest`；既有执行范围/会话/安全测试 |
| 原始结构化位置、JDBC 改写拒绝、非 execute 阶段无位置、复制/预算保留字段 | `PgErrorPositionTest` |
| 选区偏移、相同 SQL 的不同 occurrence、Unicode 映射、编辑/撤销失效、清空释放 | `SqlExecutionSourceTest`、`SqlExecutionContextIntegrationTest` |
| 跨 Schema/quoted 标识符、CTE/派生投影、局部别名遮蔽、相关查询与不可相关边界、字面量不伪造列 | `SqlCompletionContextTest` |
| 双引号标识符完整替换、光标改变后弹窗无效、焦点与显式格式化抑制 | `SqlAutoCompleteFocusTest` |
| 专用资源关闭、参数化目标、行数/名称额度、超时及连接迟到后取消 | `SqlCompletionMetadataTest` |
| 旧读取物理结束前不堆积新连接、超时/关闭拒绝迟到响应、后台关闭不等待 FX 队列 | `SqlCompletionLookupTest` |
| 480px 工具栏可读且不重叠，原数值排序、详情与新计时文案 | `SqlBatchFailureNavigationTest`、`SqlEditorResultFilterContractTest`、`SqlOverviewDurationSortTest`、`SqlScriptExecutionReportTest` |

格式化、事务、取消、生产确认、只读、配置变化和关闭回归在本轮定向/全量中重新执行；未复用旧通过状态。

## 已执行证据

`run-check.ps1` 对 Test 子进程设置隔离 `user.home` 和 `java.awt.headless=false`，移除 Redis/SchemaDiff live 环境变量；不修改系统环境。以下任务均附 `--offline --no-daemon --console=plain`，测试还附隔离 init script。镜像构建不带测试 init/profile/headless 参数。

| 运行 | 任务/实际结果 | 失败或未验 |
| --- | --- | --- |
| `baseline-probe` | 从 `fb849b2` 取分句器与词法规则到独立临时目录编译；合成 Oracle 注释前缀匿名块预期 1 条、实际 3 条，探针 exit 1，证明旧缺陷 | 有意 RED，不算通过；日志 SHA256 `65EA4B237437AF66D4F69EF4FF4AD0B3D86429B5D31B3618C570FE96164D2D3D` |
| `m5a-targeted-1` | 编译失败，未运行测试 | 新按钮重复字段，已修复；首次包装脚本无 XML 时失败，原编译日志保留 |
| `m5a-targeted-2` | 51 tests / 51 passed / 0 failed/errors/skipped | 早期 M5a 增量 |
| `m5b-targeted-1` | 78 / 75 passed / 3 failed / 0 skipped | 旧计时断言、480px 结果高度；已修复 |
| `m5-context-targeted-1` | 106 / 102 passed / 4 failed / 0 skipped | 480px 高度仍不足、旧私有加载方法的两个测试调用；已修复 |
| `m5-context-targeted-2` | 45 / 44 passed / 1 failed / 0 skipped | 数字常量误当投影名的新增反例；已修复 |
| `m5-lifecycle-targeted-1` | 测试编译失败，实际未执行测试 | import 歧义；包装脚本误复制上轮 XML，JSON 已标记 INVALID，该 XML 不作为本次证据。后续只采集日志显示实际 test 任务执行的 XML |
| `m5-lifecycle-targeted-2` | 45 / 44 passed / 1 failed / 0 skipped | CTE 主体/非 LATERAL 派生查询误见外层别名；已修复 |
| `m5-lifecycle-targeted-3` | 45 / 45 passed / 0 failed/errors/skipped | 不代替后续版本验证 |
| `m5-final-targeted-1` | 测试编译失败，实际未执行测试，未采集旧 XML | 缺 Label import；已修复 |
| `m5-final-targeted-2` | 349 / 349 passed / 0 failed/errors/skipped | 覆盖执行动作/改绑、格式化、结果筛选、定位、补全和生命周期 |
| `branch-full-1` | clean test：3721 / 3703 passed / 15 failed / 3 live skipped | 新动作数量、原计时列/详情断言、结果栏 HBox 假设；已保留原数值并调整新布局/断言 |
| `branch-full-2` | clean test：3721 / 3716 passed / 2 failed / 3 live skipped | 480px 上下异常/详情被分到不同行；将三个动作保留同组后重验 |
| `m5-review-targeted` | 290 / 290 passed / 0 failed/errors/skipped | 审查后覆盖新终止符位置、Oracle 注释 header/匿名块传输、安全分类，以及前轮全部界面失败类；日志 SHA256 `C08E0D7F36C2CCDF7E6B0FEE6719175054414320C334A6CCCB04746ADB750A1A` |
| `branch-full-3` | clean test：3724 / 3721 passed / 0 failed/errors / 3 live skipped | 通过；之后审查发现后台关闭的取消标记不应等待 FX 队列，补充即时取消和可控阻塞回归，最终源码另行全量验证 |
| `branch-full-final` | clean test：3725 / 3722 passed / 0 failed/errors / 3 live skipped | 包含后台关闭即时取消回归；之后补上字面量投影反例，不能代替最终版本验证 |
| `m5-literals-red` | 8 / 7 passed / 1 failed / 0 skipped | 新反例证实 NULL 被误当作 CTE 输出列；有意 RED，已修复 |
| `m5-literals-green` | 8 / 8 passed / 0 failed/errors/skipped | NULL/TRUE/FALSE 无显式别名时不猜列；显式别名与引用列名保留 |
| `branch-release-full` | clean test：292 suites / 3726 tests / 3723 passed / 0 failed/errors / 3 live skipped；2m54s | 含字面量修正。Oracle 等价的零位置表达式随后简化，单类另行验证；main 将对提交源码全量复验 |
| `branch-oracle-final` | 15 / 15 passed / 0 failed/errors/skipped | Oracle 零位置表达式简化后实际重新编译运行 |
| `branch-buildSrc` | :buildSrc:test --rerun-tasks：8/8，0 failed/errors/skipped；4 tasks 实际执行 | 无跳过或 up-to-date 冒充测试 |
| `branch-image` | jpackageImage：exit 0；39s | DataCube.cfg 无测试 profile/headless/合成入口；未安装或启动更新 |
| `main-full` | main `28723b3` clean test：3726 tests / 3723 passed / 0 failed/errors / 3 live skipped；2m37s | 首次合并复验；不包含后来发现的未闭合引用来源修正 |
| `m5-unclosed-source-red` | 9 / 8 passed / 1 failed / 0 skipped | 新反例证实未闭合的引用来源会丢掉末字符而误认其他表；有意 RED |
| `m5-unclosed-source-green` | 5 suites / 36 tests / 全部通过，0 skipped | 来源名称须引用闭合，合法引用名称和编辑中的前缀保持可用；含补全生命周期/元数据/焦点/当前语句回归 |
| `main-full-final` | main `0e7ca79` clean test：292 suites / 3727 tests / **3724 passed** / 0 failed/errors / **3 live skipped**；2m47s | 最终提交代码，使用新 `profile-main-final`，不是旧通过证据 |
| `main-buildSrc` | :buildSrc:test --rerun-tasks：**8/8 passed**，0 skipped；8s，4 tasks 实际执行 | 本次 XML 独立归档 |
| `main-image` | jpackageImage：exit 0；30s | cfg 无测试参数，exe/cfg/runtime modules 摘要归档；未安装或启动更新 |

所有 counts 以归档 XML 实际统计为准。三个 live 跳过是 Redis standalone 与 PG/Oracle SchemaDiff 写入集成；缺少明确授权及环境，均不算通过。历史 SchemaDiff 偶发 snapshot 失败根因仍未明确，不能称已修复。保留 unchecked 编译提示及可能的 JEP 493 jlink 提示，不宣称零警告。

## 分支交付审查

- 对新增/修改的源码与回归逐项检查：新动作只改变范围选取，继续同一 admission、安全确认和串行会话路径；错误位置不从文案猜测；结果额度拷贝保留来源字段；补全不借用树连接；关闭先同步取消，再异步清理界面。
- 完整 diff 与 `git diff --check` 通过；没有新增依赖、持久化格式或格式化规则。最终本地检查没有未处理的已确认缺陷；文档列明保守降级与外部待验，不承诺完整 SQL 解析。
- 分支检查基于未提交工作内容，因此日志 head 仍为基线 `fb849b2`，不能解释为对纯基线的通过。提交后 main 复验将绑定合并 SHA。
- 最新全量日志 SHA256 `BDA068C891EBCAA72E1714CE82947BE68CFF86CBB76A0DC5C09EEADC95616AE7`，XML 清单摘要 `DE657FFB7C75E8328C148E0FE3968771E571B4351322B711582CFB0572C58DDD`。每轮命令、结果、日志/XML hashes 和镜像文件摘要见 [机器结果](2026-09-24-datacube-g5-results.json)。

## main 集成

- 实现 `76dd22104cb62e2890eeb45f79f4d6c23691d6cd`；首次 main 合并 `28723b3391a76f268744b1f743ac75b232e22117`。合并前 main 与基线一致，工作区干净（始终排除受保护目录）。
- 最后审查反例及修正 `c62e86cb71aa30f95b94490b90cd338c30faeb4d`；main 修正合并 `0e7ca7972cfb107bfb54b2192d990c547d47031e`。代码/测试/构建与修正分支相同，无冲突。
- 首次 main 使用事前不存在的 `profile-main`；最终 main 另用事前不存在的 `profile-main-final`。不共享真实配置，不复用分支或首次 main 的 XML 充当最终通过证据。
- 最终 main 全量日志 SHA256 `DFC6513F8002024834601C4B5FFFC2957547C20A9F74ED44850030BD3554010D`，XML 清单摘要 `FBFB2FAD20063FE8EF4F06E6FA0BEDE9500BF3056AC6BDB95A211BB4158A4DFA`；镜像日志 `9F33DDA66EFC1F03FBA9E8234C74F72B8464F68DEB826A20EF15AB6D7570A96E`。
- main 镜像 `D:\Projects\朝花夕拾\build\jpackage\DataCube`：exe SHA256 `6C32DDB83447C5754B5484B7D0C0F501CF48AD515993F96143388D2B4A32074F`；cfg `E53F0D480A7462920E5D0B6DF5E12BB24BBAA011298317A090CA174FBCC6153D`；模块镜像 `742263805848DE909BCBD97528BAD81D6A49BE51F4FAE72104E4455F5044519D`。模块化应用代码位于 runtime/lib/modules，不能只比 exe 推断应用代码一致。
- 机器结果保留 26 轮结构化运行、最初编译失败与基线 RED、独立测试 XML 摘要、包装脚本 hashes 和两份镜像摘要。最终源码树 `b7913a5fbfb04b4f47a8842288cd0d7ede5add65`、测试树 `2a2beda99905d9cd2b8e461c221ab45514e39913` 与分支相同。
- main 最终无失败；三项 live 跳过和历史 SchemaDiff 偶发问题仍按前文记录。后续提交仅同步文档，不把文档提交 SHA 冒称代码测试时的 SHA。

## 交付与下一步

G5 本地工程交付；M6–M8 未启动。实现与修正均为本地可审查提交，没有 push/tag/PR/发布。后续仅在明确授权的目标范围继续；真实数据库、签名凭据等外部操作仍须单独授权。

## 待外部验收

- 原生桌面按键、焦点、明暗主题和 100%/150% 缩放；本轮 JavaFX 自动测试不代替肉眼/原生交互。
- 授权的一次性 PG/Oracle：真实驱动错误位置/Unicode、跨 Schema/权限、抓取/超时/取消/关闭及连接迟到。
- 签名、安装/便携升级/回退、远端 CI 和发布；本轮无相关操作与通过证据。
