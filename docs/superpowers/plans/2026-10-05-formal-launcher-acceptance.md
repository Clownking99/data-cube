# 2026-10-05：正式启动器的隔离离线验收

当前目标：补齐正式 DataCube.exe 的启动、原生离线编辑、正常关闭和同 profile 显式恢复；不扩展功能，不安装升级，不宣称完整 M8 发布通过。维护者“继续推进”延续已授权 GPT-6.1-sol 开发、根审查与 main 集成。基线 main c4b68c549e923098fb095a1e3dd7bdc1ee548098，独立分支 codex/formal-launcher-acceptance-20261005。

范围与边界：原 exe/cfg/runtime modules 不改，真正入口为 DataCubeFx；独占 UUID 临时 profile、两份合成中文 SQL、不连接或执行；.testagent 禁读禁改，原 profile/凭据/历史/业务文件禁读。默认更新检查用外置 JVM Gate 的本机拒绝代理隔离，限所测 HttpClient 路径，不冒充 OS 网络沙箱。此次不启动新功能、真库、安装升级、签名、发布、外部联系。既有推送 main 授权仍有效；不新建/移动 tag 或改其他远端引用。

| 检查点 | 当前目标与改动 | 实际验证 | 失败/未验 | 下一步 |
| --- | --- | --- | --- | --- |
| F0 | 核对 main、剩余验收与独立 worktree；委派现有 GPT-6.1-sol 子代理准备外置工具 | 授权工作区干净、三镜像 SHA 固定、旧1087原件核对 | 正式launcher此前未验；无可解析的原用户线程 id，使用已授权开发子代理，不建更多用户线程 | 审查启动前profile和更新隔离 |
| F1 | Gate在应用主类前断言home，默认ProxySelector仅回环拒绝；父委派且不替换main/FX/guard | worker正确home探针exit0、错误home初始化exit1；根新profile独立重做；根重新javac对照三个实际class字节一致 | 无java.instrument；4种准备失败原件保留：参数拼接、boot类依赖、loader初始化重入、classpath覆盖。未启动产品的失败不算产品缺陷 | 根原生正式启动 |
| F2 | 两次原DataCube.exe原生启动；创建A/B离线中文草稿，切A，Alt+F4；同profile显式恢复两页，再切回A退出 | 15状态/18目标截图；恢复2/0/0/0；两份117字节草稿不变，72字节workspace顺序A/B、selected0、两项anchor/caret55；父launcher两次exit0，JVM自然shutdown且4PID均消失 | 首次UI授权超时重选成功；CIM初次拒绝后取第一父子绑定；UIA短暂滞后；初版decoder错误要求schema null，源码证实空字符串有效，保留原失败后修正。1100ms闪屏未截图；未独立读子JVM退出码/第二父子CIM | 归档、独立复核 |
| F3 | worker独立读根原件审核；根审查源码/重编译字节/原始日志/镜像SHA | worker review提交bb601b9；根综合audit-branch核对旧1087、worker26、根91冻结原件，三实际镜像SHA不变，限定6项通过 | 第一次Node调用git被EPERM拒绝，限定提权后同脚本通过；CRLF/原始末尾空行与JavaFX警告保留。无产品改动，不把历史Gradle结果算新测试 | 精确白名单提交、合并main、复验 |

集成和最终待验见[本轮账本](../verification/2026-10-05-formal-launcher-acceptance.md)。本轮限定交付后停止，不自行扩展下一项；既有 datacube 跟进保持 PAUSED，无新 goal 或预算。
