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

## 仍待验

本轮不能代表真库、完整 Oracle/view/SELECT/配置失效原生链、完整 AppShell 在途退出/超时恢复、OS 缩放/多屏、正式启动器/安装升级/签名/CI/用户任务或发布验收。已有指定 Oracle 专用表不访问/不清理。未验证的项目保留待验，M8 不称完成。
