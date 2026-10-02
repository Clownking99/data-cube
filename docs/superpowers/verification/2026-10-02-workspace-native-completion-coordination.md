# DataCube 工作区退出：授权后原生复验记录

当前原生目标未完成。维护者已明确授权，剩余依赖是Windows桌面工具访问错误，不再写成“待维护者授权”。限定过程和每检查点见[本轮计划](../plans/2026-10-02-workspace-native-completion.md)，原件见[evidence/results.json](evidence/workspace-native-completion-coordination/results.json)和[工具两次原始错误](evidence/workspace-native-completion-coordination/native-tool-attempts.json)。

## C0：新运行与独立审核

- 当前目标：按已有矩阵进行真正原生输入，旧合成FX/工程通过不算本轮原生证据。
- 改动：基线main/worktree c4807bc，分支codex/workspace-native-completion，新独占证据目录。复制probe/launcher原字节，产品src/test/build未改，旧746文件保持。
- 验证：本轮javac成功（原有unchecked提示保留），编译绑定当前main镜像三产物SHA；新UUID可见Stage及两次成功草稿原子写入、专用workspace故障事件。provider解析未被调用。初始化仅程序化证据。
- 失败/未验：0原生输入、0截图、0行为通过；没有新Gradle运行，不引用旧工程通过当作本轮新验证。
- 下一步：工具取得状态后才输入；获取失败按技能刷新一次再停止。

## C1：实际技术阻碍及安全收尾

- 当前目标：读取自有窗口状态并执行真实production Alert。
- 实际结果：枚举自有窗口成功，第一次访问GetCursorPos failed: 拒绝访问。 (0x80070005)；重新枚举/绑定后同样失败。已明确获得人类授权，没有app approval timed out，也没有automatic approval review拒绝。
- 失败/未验：不能断言锁屏；已询问当前桌面是否解锁且可交互。未调用press_key/click/type_text，原生矩阵八项全部UNVERIFIED；不把READY/存储初始化或安全停止当作产品关闭/资源锁验收。
- 收尾：默认沙箱的精确CIM查询拒绝访问原件保留；本地升级仅核实本次PID7068的路径/profile/launcher，随后停止、复查0剩余；profile不删。自有事件与三个合成SQL文件/workspace初始字节另存，首错误保留。
- 下一步：独立字节/原件/源码审查后本地集成记录；交互会话恢复后继续原矩阵，不再请求已经授予的权限，不扩展范围或改产品规避工具。

## C2：审核与本地记录集成

只读初审已核验旧746文件SHA/长度未变、产品/测试/构建未改、当前main三镜像SHA匹配、本次READY与初始workspace字节相符，原生输入/结果事件0。后续暂存字节与main集成后记录见新audit JSON；原生目标仍未完成，M8和正式launcher/完整在途恢复/安装升级/签名/CI/用户任务/其他真库及发布仍待验。datacube未恢复，既有安全边界全部沿用。

## C2a：本地集成后的实际复核

- 当前目标：本次技术阻碍记录可追溯且main保持原产品代码；不宣称原生目标完成。
- 改动/集成：独立分支本地提交e8d854a5fcf071fd59bf5ed232fad4d15d0f333d，main no-ff合并79ad40e9da775ebfba615da4db124e04f0689fbb；仅docs外置夹具副本/新原件/待验更新。精确32份暂存文件逐一hash-object与index字节匹配；ignored原始logs显式纳入，不漏原件。
- 实际复验：main上的postmerge-audit.json核验旧746原件和本次26冻结原件SHA/长度未变，产品/测试/构建对c4807无差异，当前main镜像三项SHA与新compile匹配，实际READY的旧workspaceSHA与保存的72字节原件匹配，日志原生输入/生产决策/关闭结算事件0。
- 失败/未验：本轮javac新通过；没有新Gradle/full/buildSrc/jpackageImage运行，旧工程通过只作历史。GetCursorPos拒绝访问两次和首CIM沙箱失败保留；人类授权已满足、无自动审核拒绝，桌面锁定状态未确立。八项原生矩阵UNVERIFIED，安全停止不算COMPLETED。
- 下一步：技术会话可交互后按同一矩阵新profile重新绑定。Computer Use技能引用的guidance要求窗口激活/状态失败刷新选择重试一次，失败则报告；本轮已执行，未换工具绕过。技能路径C:/Users/hetia/.codex/plugins/cache/openai-bundled/computer-use/26.930.21537/skills/computer-use/SKILL.md，相关原文“Refresh the app/window selection and retry once; report the exact error if recovery fails.” 位于../../docs/guidance.md。

本段及main审计为合并后证据记录，后继文档提交不改变受验产品源码。自有进程已停止、profile保留、datacube未恢复，本目标未完成，M8未完成，不自动下一轮。
