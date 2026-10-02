# 字段查找原生键盘：独立审核与本地集成

本轮按[限定计划](../plans/2026-10-02-metadata-native-keyboard.md)，由已授权 GPT-6.1-sol 代理实施 N1，协调线程审核、必要修正、提交/合并和新复验。基线 main `7afbe280d92b68b0780333f3a7942157d4197234`，独立分支 `codex/metadata-native-keyboard`；两边授权范围干净。客户端日期 2026-10-02，日志保留实际 UTC。datacube heartbeat 保持 PAUSED。

## N0 / R0：基线及源码独立初审

- 当前目标：补齐实际 AppShell 的字段查找原生空框输入、键盘、多结果和实际小窗口证据；不扩大功能或外部目标。
- 改动：限定计划、独立分支、协调证据脚本和新 UUID 验证根；N1 已下发给现有 GPT-6.1-sol 代理。
- 验证：main/工作区都在固定基线并干净；独立完整阅读 SchemaMetadataSearchDialog 和相关焦点/条件/取消回归。query.onAction 明确查找、onShown 聚焦、Ctrl+F 严格修饰键；published 对模式/strip 词/schema/当前列表 hit 绑定。未发现可以直接认定的输入缺陷。布局为 VBox 660×560，preview/status 最小高度按内容，真实缩小需结合实际几何测量。
- 失败/未验：本轮暂未取得新桌面或工程通过。第一次 rg 使用不存在的 src/.../theme-base.css 路径返回错误，随后用 rg --files 找到 resources 下实际 CSS 并完成读取；只是定位错误，不是产品失败或通过。历史无效截图、预填文本和无效拖动不升级证据。
- 下一步：等待 N1 实际原始材料，独立核对输入/焦点/结果/窗口变化和资源计数；若需返工，下发具体修正后再集成。

## 新复验方法

N1 进行中独立审查外置 MetadataNativeKeyboardProbe.java：真实 AppShell、ConnectionStore/ConnectionManager 仅注入合成 mock 目标；无 DriverManager/no seed/setText/focus/fire 输入路径，窗口标题带唯一 UUID，新增被动键/焦点/条件/结果/几何日志与三条合成结果。预备脚本的探针筛选名称已按实际文件纠正为 MetadataNativeKeyboardProbe。协调首次读取曾猜错该文件名，随后按 rg 返回路径读取；记录为文件定位错误，不是验证失败。

[协调证据目录](evidence/metadata-native-keyboard-coordination/)中的 baseline.json 固定本轮 roots/JDK/UUID 临时根。Verify.ps1 强制固定分支/提交、源码工作区干净，每轮不同且从未存在的隔离 profile，剔除 live 与 JVM/Gradle 注入；使用既有 isolation.init.gradle，--offline --no-daemon --console=plain --rerun-tasks。顺序执行定向五 suites、全量、单独 buildSrc clean test、jpackageImage。每次覆盖报告前复制实际 TEST-*.xml，Summarize.ps1 独立重算通过/失败/skip 与实际执行 tasks。Audit-Image.ps1 检查测试/外置探针/profile/选项泄漏、冻结源码、两边产物 SHA，并以镜像运行零连接 driverFor 发现。Audit-Staged.ps1 只允许本轮精确路径，比较 actual Git blob 与原始证据字节；源码 Java 的 LF/CRLF 转换须逐份严格证明。

## N1 / R1：原始证据独立审核与具体澄清

- 当前目标：审查 GPT-6.1-sol 本轮原生材料，不把工具无报错当作操作有效；限定证据集成。
- 改动：新增 Audit-Worker.ps1；下发一次 Tab 可达性诊断与停止条件，以及 worker 账本“启动时 HEAD”澄清，代理已落实。没有产品/src/resources/test/build 改动。
- 验证：独立阅读 fixture、launcher、保存 recipe、动作参数/异常、worker 账本、全部状态与运行日志；直接查看初始 shell、Schema 菜单、输入聚焦/两次空框、缩小、Tab、模式菜单/无效选择、Esc 与退出后的原始图像。独立核验 54 份 worker manifest 条目、21 状态、39 JPEG 全部 SHA/长度、每张 JPEG 实际尺寸与该次 returned 截图元数据一致、唯一合成目标。原生能展开连接/Schema 菜单、打开字段框、坐标取消局部 dialog、正常退出完整空闲 shell；PID35228 已退出、SHUTDOWN_COMPLETED、mock 1/1，检索/page/DDL/写/执行均 0。
- 失败/未验：工具只返回 owner，模态 element74 缓存不可用；两次 type_text 空框、Tab/Esc 零 DIALOG_KEY、模式未变、缩小前后均 663×639且零 geometry 事件。输入/键盘/多结果/条件失效/小窗口全部仍未通过。accessibility 的 focused_element 一直报告字段框但被动焦点实际已转 ListView，不能单信树或 API 成功返回。本轮不能将限制精确归因到产品；不做无根因产品修复或 FX 红绿。worker 首次只计 PNG 造成图片计数 0 已保留失败，修成 JPEG 后与全部 returned 状态吻合。
- 下一步：保持原生未验矩阵，完成 fresh 工程与镜像复验、本地提交/合并和交接。

## N2 / R2：分支新验证（进行中）

- 当前目标：独立工程复验、归档真实 XML 和所有失败，再本地集成。
- 改动：协调计划/脚本先提交 c175f45a5bc3fe2ae7be218f7a8d6f03c1e8de82；后续修正定向筛选并加每个 requested suite 必须出现在 XML 的检查，审计路径按 worker 实际文件名修正。
- 验证：纠正后的新定向 5 suites /83 passed、0 failure/error/skip，774 冻结源码文件稳定。
- 失败/未验：首轮错误筛选 SchemaDiffDefaultsServiceTest（不存在），Gradle整体exit0但实际只有4 suites/78 passed；完整原始日志/XML/执行参数/旧baseline保留 directed-first-incomplete，不能作完整五组通过。纠正重试首先被“profile非新”门禁拒绝，Gradle未启动；该工具输出转录（明确非raw shell log）保存 directed-profile-rejection.json，未删/复用旧profile，改用新的每阶段/每步 -final 目录后实际重跑。完整 full/buildSrc/image尚待。
- 下一步：全量、buildSrc、镜像及零连接发现；原始字节/范围审查通过后提交和本地合并 main，再fresh复验。

R2 后续：分支 fresh 全量 310 suites /3920 total =3917 passed +3 live skipped、0 failure/error，8 tasks 全执行；buildSrc 8/8、0 skip，4 tasks 全执行，冻结 774 文件无漂移。三个 skip 分别为 Redis、Oracle SchemaDiff、PG SchemaDiff 的显式 live 门禁缺失，不算通过，也未为跑它们访问真库/创建或操作 schema。worker raw 四个日志将精确强制暂存；仅证据目录 .gitattributes 保留 raw 字节，不改变仓库一般换行策略。worker 原 manifest 保持不变，协调新增属性文件独立纳入最终字节审计。

R2 完成：分支 jpackageImage 成功、14 tasks 全执行；镜像 183 文件，test/probe/profile/option 泄漏 0，774 冻结源码与工作区干净；镜像内 Oracle/PG driverFor 发现成功、connectCalls=0。独立 source/test/resources/build Git diff 与起始 main 为零。仅批准外置夹具、有限失败证据、复验脚本和交接的本地集成，绝非批准原生输入/键盘/小窗口验收通过。定向首轮不完整、profile拒绝、worker报告格式计数错误均保留。下一步精确暂存/字节审计，提交后 --no-ff 本地合并 main，fresh main 复验。

## 仍待验

本轮不能代表真库、完整 Oracle/view/SELECT/配置失效原生链、完整 AppShell 在途退出/超时恢复、OS 缩放/多屏、正式启动器/安装升级/签名/CI/用户任务或发布验收。已有指定 Oracle 专用表不访问/不清理。未验证的项目保留待验，M8 不称完成。
