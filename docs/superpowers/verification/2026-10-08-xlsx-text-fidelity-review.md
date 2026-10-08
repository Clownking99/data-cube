# XLSX 文本导出保真独立审核

## S0 基线与诊断范围

2026-10-08，main 与远端均为 365dba304a5e198e72ae7633d88a1b5258d058fa，范围内干净；复用工作区原分支干净，root 已从 main 建立 codex/xlsx-text-fidelity-20261008。上一轮精确 SHA CI 最终成功，Windows 首次未改动助手用例 20 秒超时、一次原代码重跑通过；原因未知。原回执 29 文件与 manifest 逐项 SHA 验证后复制到 baseline-ci-365dba30，保留历史身份。

root 独立阅读 XlsxWriter、QueryResultFileWriter、SafeResultFilePublisher、显示值策略及现有布局/查询 XLSX 测试，发现共享文本编码路径仍静默删 C0、直接写 CR/FFFE/FFFF，且 UTF-8 默认 writer 可以替换孤立代理项。微软 ST_Xstring 说明允许格式转义，并要求保护字面转义外观；不能照搬上轮普通 XML 的全部拒绝策略。上述为源码发现，尚无本轮行为复现或测试通过。

开发代理仅获诊断权限及唯一 Gradle 许可，暂不改产品/提交/全量。root 将核对合成产物和固定期望后确定最小实现范围；拟限表头/文本单元格及其安全错误传播，不扩大数值精度、长度限制、其他格式或数据库路径。捆绑 openpyxl 3.1.5 可作独立 Xstring 解码审计，不等同原生 Excel 验收。下一步是保存真实 writer/publisher 的旧行为及首红证据。

## S1 可重复损坏与最小修复范围

开发者 002 通过从原源码隔离 mirror 编译真实 QueryResultFileWriter / SafePublisher 生成 10 个合成 XLSX，编译 exit 0，诊断 exit 1；root 完整审查诊断 Java/runner 后，用 Python 标准 XML（拒绝 DTD/实体声明）和捆绑 openpyxl 3.1.5 单次解码逐文件独立回读，结果 1 通过、9 失败。NUL/C0 删除、CR/CRLF 改变、FFFE/FFFF 生成不可解析 XML、字面转义序列被解码改变、孤立高/低代理项变为问号；全部原先已发布。普通 Unicode、tab/LF、C1 和合法完整代理对通过，不能称其为新修复。

同意只改 XlsxWriter：按 ST_Xstring 编码 C0（tab/LF 保持）、CR、FFFE/FFFF，逐原字符前瞻保护字面转义的首下划线，合法 pair 保持，孤立 UTF16 固定 typed IOException 明确拒绝。RowSink 本身允许 checked Exception，去除多余 IOException 包装即可；SafePublisher 沿用 WRITE 和现有安全消息，无额外 UI/stage 改动。查询结果的原目标保护按真实 publisher 验证；TableExporter 直接目标写的现状单列，不声称它也保留原文件，不扩大其状态机。

000/001 模块 sourcepath 诊断编译失败不当产品证据。nul.xlsx 是 Windows 保留名，原件保留，Java NIO 另存 case-nul.xlsx 并记录名称映射、2407 字节和相同 SHA；root 后续独立审计已核对全部 10 包与 sheet 原字节。root 初次误写诊断文件名的只读命令失败、可选 defusedxml 未安装也单列为工具诊断；实际改用标准 XML 加拒绝声明检查，不安装依赖。独立库的两组重叠字面转义固定夹具通过，但不是产品测试。

开发者获授权继续唯一 Gradle 的新 JUnit 首红、最小修复及定向，未准提交或全量。root 已准备独立 linked runtime 的固定标准样例探针，最终镜像阶段验证 styled/plain 原包文本、标量行为和孤立项拒绝；当前尚未执行，不预报通过。下一步独立解析首红/定向新 XML 并审查实际 diff。

## S2 首红核验与单文件实现审核

root 重新解析 003 原 command/exit/新 XML：实际 test，2 套 142 项，9 通过、133 failure、0 error/skip，exit 1。UI 原实现将含孤立代理项的内容发布并提示已导出；publisher 新/旧目标场景也未拒绝。已有合法字符/映射辅助用例首红通过，不称其为新修复。

实现只改 XlsxWriter：固定无数据 typed IOException、合法 pair 验证、逐原字符检查字面转义、C0/CR/FFFE/FFFF 编码，保留实体转义和原布局/标量/页读取行为。移除 RowSink 内多余 IOException 包装，没有修改 SafePublisher 或 UI。root 已完整阅读单产品 diff 与两个新测试，检查发布状态、旧/无关字节、唯一临时清理、真实 retry、固定安全消息和无假成功断言，未发现阻断问题。

004 阶段定向原 XML 为 12 套 277/277、0 skip/fail/error、exit 0；开发消息曾误报 13 套，root 已要求按原件纠正。此阶段未选全 Snapshot/ValuePolicy/Options 范围，不称最终全定向。开发者继续同源码的 005 完整选择，保留 004 身份；随后核对最终源字节并批准源码提交、分支全量/buildSrc/镜像。当前未有本轮全量或镜像通过。

## S3 完整定向、冻结与源码提交

005 原件经 root 独立核验：实际 test，15 套 286/286、0 failure/error/skip、exit 0，10 秒。root 重算三个源文件 SHA，与开发者 final-freeze-005 一致；和测试运行时快照逐文本比较，仅移除 EOF 空白，没有行为或断言修改。只读共享调用与布局/安全发布/其他格式源码未改变。

精确源码提交 ce2a096a044db6a731774b13a8ae61ea44687953，含 XlsxWriter 和两个新测试，cached diff check 通过；新增 142 项（141 文件序列化/发布及辅助解码、1 合成 coordinator）。批准唯一 Gradle 执行者依次 006 clean 全量、007 强制 buildSrc:test、008 强制 jpackageImage、009 新镜像审计。新审计保留旧 XML 七组，同时增加 styled/plain 共 20 实际 XLSX 包、40 单元格跨语言解码及 6 孤立项拒绝；目前未完成，不预报通过。下一步审核分支原始 XML、实际产物和字节，再归档/集成。

006 clean 全量经 root 独立统计：325 套 4306 项，4303 通过、3 live 跳过、0 fail/error，exit 0，5m45s。三跳过仍为未设置授权真服务环境的 Redis 和 SchemaDiff Oracle/PostgreSQL，不是实际连库通过。root 复算三源码 SHA 与审核冻结一致。buildSrc/镜像仍须完成各自新证据，尚未 main 集成。

## S4 分支完整验证、归档与 main 集成

007 强制 buildSrc 新 1 套 8/8、0 skip、exit 0，8 秒；008 强制 jpackageImage 实际 14 任务、exit 0，47 秒。009 新镜像审计 183 文件无测试类/profile/选项泄漏，linked XML 七组及拒绝、新 XLSX 20 包/40 单元格独立解码和 6 孤立项拒绝均通过。root 独立重算三实际产物长度/SHA，与原 audit 一致；modules 为 102433583 字节、E0BF1CF2D49396301EA26686E751AE6B27086713077DD66EB84169AB6415CA32。驱动仅加载/发现，声明 connectCalls=0 不是连接插桩或真库验收。

009a 首次镜像审计外层将命名参数放入 array splat，绑定失败使脚本未执行；外层错误地给出 exit 0，不计通过。原命令/明确标识的工具输出转录保留，修正调用后 009 才有实际新镜像/探针证据。开发者停写、交回 Gradle 后，root 冻结 518 文件、14156649 字节，逐项核对原 SHA 和 Git blob。报告原件也保存在冻结目录，阅读版仅收尾 EOF 空白。

原 nul.xlsx 保留在 worker 物理证据目录并精确忽略，不进入 Windows 不可检出的 Git 路径；同字节 case-nul.xlsx、原 cases、别名映射/日志与 manifest externalOriginals 入库，不改写历史文件身份。证据提交 5f176b0b10af9d2de1bd338fe9b1c9518bb4d377；main 本地合并 8fb039c000f03116720859cf0df185c37ab8b405，受验源树与 ce2a096a 完全一致。合并后 518 原件/Git 字节再次核验通过。下一步 root 独占执行 main 定向、clean 全量、强制 buildSrc/镜像及新审计；分支通过不替代 main 复验。

001-main-targeted 已实际重新编译产品/测试并运行 test，新 XML 15 套 286/286、0 fail/error/skip、exit 0，11 秒。三源 main checkout 实际 SHA 独立记录，与 worker 归一化文本完全一致；换行字节差异保留各自身份。002-main-full 正从 clean 执行，当前尚未有 main 全量/镜像或最终 SHA CI 结论。

## S5 main 新全量与构建复验

002 clean 全量新 XML 经 root 独立核验：325 套 4306 项，4303 通过、3 相同 live 跳过、0 failure/error、exit 0，5m12s。003 强制 buildSrc 实际 4 任务，exit 0，7 秒；原 XML 随最终汇总核对，不用 UP-TO-DATE 代替执行。004 强制 jpackageImage 正在生成，镜像及最终产物一致性尚未结算。

## S6 main 镜像与本地交付审核完成

004 强制 jpackageImage 实际 14 任务、exit 0、36 秒。005 用独占合成 profile/temp 重新审计 main 新镜像，183 文件无测试类/配置选项泄漏，linked runtime 的 XML 七组回读及非法字符拒绝通过；新生成 20 个 XLSX 包，固定期望的 40 个表头/文本单元格由标准 XML 解析与 openpyxl 单次解码独立验证，6 个孤立 UTF16 场景拒绝。探针检查数值/布尔/NULL 保留、无公式生成。无真库或原生 Excel 行为声明。

Finalize-Local 独立解析新 XML，确认 main 定向 15 套 286/286、全量 325 套 4303 通过/3 跳过、buildSrc 1 套 8/8，均零 failure/error；跳过 ID 和原因与分支完全一致。三受验源文件从 main 开始复验至今字节未变，Git 产品/测试树与 ce2a096a 完全一致；DataCube.exe、cfg、modules 三实际文件长度/SHA 与分支相同。`main-branch-comparison.json` 保存完整核对结果。

代码独立审核无剩余阻断项。README、交接和路线按上述实际范围更新；下一步冻结 root 原件并提交文档证据，推送最终 main，再核对精确 SHA Verify 四任务及 Windows 实际 test/buildSrc/jlink 日志。最终推送和 CI 尚未执行，不将旧成功充当新证据；回执位置固定为 `build/owned-ci-6bae6012662948499f1d674966f539c3`，由已入库 delivery-intent 指向，避免提交回执自身改变受验 SHA。

待验：原生 Excel/LibreOffice 与桌面输入、OS 缩放/多屏、真库与慢/网络磁盘、整表导出原目标失败保护、安装升级/回退/生产签名、完整 M8。前轮 CI 助手超时根因仍未知。既有 v3.2.9、PAUSED 跟进和外部权限边界不变，本轮结束即交付。
