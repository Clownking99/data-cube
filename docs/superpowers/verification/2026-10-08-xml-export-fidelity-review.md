# XML 导出忠实性独立审核

## S0 基线与范围

2026-10-08，main 2b2e004b 范围内干净；旧开发分支干净，root 从当前 main 建立 codex/xml-export-fidelity-20261008。上一轮精确 SHA Verify 已成功，其 15 份原回执复制后逐文件 SHA256 相同；这只确立基线，不是本轮验证。

root 已独立阅读结果导出 coordinator、session、operation、安全文件发布及 XML 序列化、现有测试。正常 tab 关闭禁用内容，资源关闭在后台，暂未证实最初的 FX 关闭阻塞猜想，放弃该方向。XML 路径则明确有静默删除控制字符和原始 CR/属性空白归一化问题，且 FFFE/FFFF 检查缺失。旧 xmlStripsIllegalControlChars 测试固化了静默丢值行为；本轮须先新回归首红，再明确修正该契约。

GPT-6.1-sol 正在用独占临时目录、真实导出代码及标准 XML 解析器诊断。当前没有本轮 Gradle/全量/镜像通过。下一步：保留可重复失败，实施最小 XML 修复及安全失败提示，不扩大其它格式或关闭状态机。

## S1 可重复数据损坏与开发许可

开发者以真实 ResultExporter/SafeResultFilePublisher 和 JDK 解析器验证 8 个合成目标；root 独立读诊断源码，再用禁止 DTD/外部解析的 .NET XmlReader 回读这些实际输出并保存字节/SHA/解析结果。NUL 已发布但被删除；FFFE、FFFF、U+00AA 列名已经覆盖旧文件却无法解析；CR 文本变为 LF，列名 tab/CR/LF 被归一化；合法 emoji 保留。孤立高代理项原本已由 UTF-8 writer 拒绝并保留 OLD，此项不是新发现的已发布损坏。

批准范围仅 XML 序列化、格式失败安全传播与固定 UI 原因及相关测试；不改同步发布/取消门禁。开发者持唯一 Gradle 许可，先新回归首红，保留原件后修复并明确更新旧静默删除契约，再跑导出相关定向。root 完整审查最终源码及原始红绿结果后才进行源码提交和全量/buildSrc/镜像。当前尚无 Gradle 通过。

## S2 首红独立核验与兼容性审核

001 原 command/exit/新 XML 经 root 重新解析：实际 test，2 套 38 项，8 通过、30 失败、0 error/skip、exit 1。非法值/列名 26 例中 18 为未正确拒绝，8 为原有孤立代理项安全拒绝但缺少格式原因；其它失败为文本 CR、属性空白、U+00AA 标签解析和 UI 假成功。首红源及旧契约均保留，不把原有 8 项保护说成新修复。

root 完整阅读新两个测试及三生产文件 diff。typed XML 字符异常转固定安全阶段/消息，不带原始值或 cause；字符范围和 CR/属性引用合理。返工要求：新的 XML name 范围不得顺带改变原先可解析的标签净化映射（emoji 原 __、组合符原 _）；保留旧映射与合法 XML name 的交集，name 属性回读完整原名。UI 测试以 Future.get 等实际结算，避免消息回调先于 Future.done 的夹具竞态；不改变产品期望。暂未批准源码提交或全量。

两次 root 只读命令分别因沙箱下 C worktree Git 访问、拼错新测试名失败；随后以批准的精准只读 Git 和 rg 找到真实文件，不涉及产品测试。诊断 nul.xml 触发 Windows 保留名归档限制，须以 case-nul.xml 保存同字节并记录映射，不能遗失该证据。

## S3 中间失败、140 项通过与补充审查

002 定向原 XML 独立统计为 13 套 140 项，138 通过、2 失败、0 skip，exit 1。emoji 直接作标签不被当前 JDK 解析器接受，以及成功消息实际规范长路径与测试 8.3 路径不同；这两项按 S2 兼容要求和目标规范化语义修正，原件保留。003 新定向实际 140/140，0 skip/fail/error。原先数据损坏和安全错误提示已通过这组回归，但尚未全量或交付。

root 最终审核继续用实际已编译 Exporter + JDK parser 检查 XML 名边界，发现 U+037F/U+08A0 虽符合第五版 Name 范围，当前 JDK 仍不能解析其直接标签；普通中文/修复后 AA/9FE 可以。探针源码和原输出已保存。这是同一列名兼容性缺口，不能以 003 绿灯掩盖。要求新增两个解析回读首红，再以公开 JDK 名校验或等价最小兼容筛选修复；保留原可解析标签映射与完整 name 属性，不使用内部 API 或扩大其它格式。此补验完成前不提交源、不跑全量。

## S4 最终修复审核及源码提交

004 边界首红实际 83 项、2 失败；005 扩展定向 144 项、1 失败。root 重读原 XML：005 唯一失败是夹具把 U+09FE 旧净化映射误认为 literal 标签；旧 Character.isLetter 策略输出 _，name 属性回读已正确。保留 005 后仅修这一期望。006 最终新定向 13 套 144/144、0 fail/error/skip、exit 0，实际 test，9 秒。

最终实现用每次导出独占的 JDK 默认空 DOM 校验旧净化标签，仅对被拒标签逐字符最小净化；不解析外部文档、无共享 DOM/自定义 provider。所有不可表示的原值/列名仍先明确拒绝，不会借净化丢失原 name。现有 java.sql 传递依赖 java.xml，无新依赖。非法控制字符回归扩展到 XML 1.0 禁止的全部 C0，并覆盖合法边界、完整代理对、FFFE/FFFF 及孤立代理项；其它格式和发布/取消状态机不变。

root 完整复核生产三文件、新两个测试和旧契约修正，重算六源/test 加 runner 七项冻结 SHA，全部一致，diff check 通过。精确源码提交 c553e4c5eadb0ca2d73fc86ee5e4aa6bb49b4d6e。批准独占执行 007 clean 全量、008 强制 buildSrc、009 强制镜像、010 镜像审计；审计额外运行 linked runtime 的七种合成列名/CRLF/emoji 回读及非法字符拒绝，确保新公开 XML 调用在镜像可用。上述全量/镜像尚未完成，不预报通过。

## S5 分支完整验证

root 重新解析 007/008 原 command/exit/新 XML 并检查实际任务：007 clean 全量 323 套 4164 项，4161 通过、3 明确 live 跳过、0 fail/error，5m31s；008 强制 buildSrc:test 8/8、0 skip，8 秒。三跳过是 Redis 真服务、SchemaDiff Oracle/PostgreSQL 显式写门禁及完整环境未启用，不计通过，也未读取凭据或访问真库。

009 强制 jpackageImage 实际 14 任务、exit 0、46 秒。010 镜像 183 文件隔离检查通过，未泄漏测试类/profile/JVM 测试参数；额外 linked XML 探针 7 种标签与正文精确回读、NUL 拒绝通过。root 独立重算实际 exe/cfg/modules 字节与 SHA，与原 audit 一致；modules 102432389 字节、SHA 35F6E2BE488B55791A40397C3B55F334E833897B9FAFC8856592E5D17C62FA22。驱动探针仅 Class.forName/getDriver，connectCalls=0 是已审源码声明，不是连库插桩验收。

新增测试 86 项（85 文件序列化/发布、1 合成 FX coordinator），现有其它格式、快照、目标变化、取消/关闭与重试回归同轮通过。当前分支验证完整，尚未 main 合并和新复验，待开发报告停写后归档证据并集成。

## S6 证据归档与 main 集成

开发报告停写后，root 将报告原件及 raw 共 525 文件、13821938 字节按 SHA256/长度冻结，并核对 Git blob 字节；证据提交 eda8970196bdfd0e231ea116562e7fd5eeb1044b。第一次误把日志/XML/patch 原件纳入代码空白检查，因原始 tab/CR/尾空白中止；随后仅对源码/属性使用该检查，原件没有清洗或改变，raw/index 再核完全一致。全量日志中的两个 Duplicate TableColumns stderr 对应旧定位测试主动 Collections.swap 的中间重复列通知，JUnit 无失败；原输出保留，不宣称无任何诊断日志。

main 从 2b2e004b 合并为 33e167b73cc4c4717f3459ab806c4a2ab9e81dcd，源码树与 c553 完全一致。合并后 525 raw 再次逐字节核对。六源码 main checkout 实际 SHA 独立保存，和 worker 归一化文本一致，换行字节差异单独记录。001-main-targeted 已在新独占 profile 开始；尚无 main 全量/镜像或最终 SHA CI 结论。

001-main-targeted 在 Gradle wrapper 启动阶段因沙箱不能写既定缓存 .lck 而 exit 1，未执行任何测试，不算产品失败或通过。保留原 command/log/exit。使用已授权构建权限和另一新独占 profile 启动 002-main-targeted，代码与测试不改。

002-main-targeted 实际 compileJava/compileTestJava/test，新 XML 13 套 144/144、0 fail/error/skip，10 秒。root 独立统计通过后，003-main-full 从 clean 开始，当前尚未完成。分支通过不替代 main 复验。

## S7 main 完整复验与本地交付

main 33e167b7 的 003 clean 全量新 323 套 4164 项、4161 通过/3 相同 live 跳过、0 fail/error，5m42s；004 强制 buildSrc 8/8、0 skip，9 秒；005 强制 jpackageImage 实际 14 任务、exit 0，49 秒。006 镜像 183 文件隔离审计和 linked XML 七组回读/非法字符拒绝通过。root 重算三实际产物字节/SHA，与分支完全一致；六源码 main 实际 SHA 从复验开始到结束不变，源树仍与 c553 一致。

本轮完成了 XML 导出的静默丢字符、回车/属性空白失真和部分列名不可解析修复。不可表示的内容有固定失败原因，原目标和无关文件不变，临时文件按原规则清理；可表示的正文/原列名精确回读，原可解析标签映射保留，失败后可重试。新增 86 项回归，分支与 main 均有完整新验证。README 已说明格式限制和重试路径，交接/路线/待验行同步更新。

所有首红、中间兼容性失败、夹具错误及 main 缓存权限启动失败按实际结论保留；没有把跳过或 UP-TO-DATE 当通过。仅合成数据、独占 profile/temp 与内存 XML；无真实数据库、原配置/凭据/历史/业务文件、.testagent 或原生输入访问。原生桌面/键盘/OS缩放、多屏、真实驱动、慢/网络磁盘、安装升级/回退/生产签名和完整 M8 仍未由本轮验证。旧证据保持各自身份。

本地源码审查、提交、main 集成与完整复验已完成。最终证据提交后，按 delivery-intent.json 指向的 build/owned-ci-f2220dcff8ae4e118cb6836e37c09888 保存 main 推送、远端读回及精确 SHA Verify/Windows 原日志；此段不预报 CI 成功。v3.2.9 保持，不启动下一轮或修改 PAUSED 跟进。
