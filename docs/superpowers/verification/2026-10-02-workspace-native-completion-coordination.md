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
