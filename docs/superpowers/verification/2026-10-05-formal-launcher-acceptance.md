# 2026-10-05：正式 DataCube.exe 离线启动与恢复验收

正式启动器的限定离线流程 **6/6通过**：启动到可用主窗口、两个中文 SQL 页的原生编辑、正常退出、同 profile 显式恢复和再次正常退出。使用原 exe/cfg/modules 与外置 JVM 隔离 Gate；不是原默认环境的无观测运行，也不是完整 M8/发布验收。闪屏外观仍未验。

[检查点计划](../plans/2026-10-05-formal-launcher-acceptance.md) · [限定矩阵](evidence/formal-launcher-20261005-coordination/native-matrix.json) · [根综合审计](evidence/formal-launcher-20261005-coordination/audit-branch.json) · [独立复核](evidence/formal-launcher-20261005-worker/review.md) · [根91原件清单](evidence/formal-launcher-20261005-coordination/raw-manifest.json)。worker工具/准备原件26份，旧1087份原件均未改。

## 实际结果

- 基线 c4b68c5。原启动器仍以 com.datacube/com.datacube.DataCubeFx 启动。三产物 SHA：exe `6C32DDB8…074F`、cfg `E53F0D48…6153D`、modules `423331A0…A6A`；完整值和启动前后记录均在证据中，根已重新读取实际镜像核对。
- 外置 Gate 在应用初始化前核实唯一临时 home/marker，父委派、不定义/变换产品类。更新 HttpClient 的实际 api.github.com CONNECT 在每次启动中仅进入 127.0.0.1 拒绝代理并收到403。正探针实收CONNECT403；自有错误home负探针在main前exit1。此证据不代表任意原始socket的OS隔离。根独立javac重编译三类与实际jar内class逐字节SHA相同；原产品/构建不变。
- 原生工具点击“新建SQL”，真实输入两段带中文的合成SQL，无连接、执行按钮禁用，未执行SQL。A/B真实草稿UUID分别45bc7258-ffb7-4e9c-b26a-fe19be50dc9b、d017dbfa-38e6-4254-b552-e5c85ae3d07a，各117字节；[首轮独立解码](evidence/formal-launcher-20261005-coordination/decoded-first.json)与[二轮解码](evidence/formal-launcher-20261005-coordination/decoded-second.json)确认内容/UUID/hash不变。
- 第二次启动通过欢迎页“恢复SQL工作区”→“恢复工作区”，真实界面报告已打开2、已定位0、缺失0、失败0；标签顺序A/B、首项A、两页行2列26一致。磁盘两次72字节workspace独立解码均selectedIndex0、anchor/caret55；捕获时间随再次关闭更新，不能要求整份workspace hash相同。恢复保持未绑定，不自动执行。
- 两次原生Alt+F4走原关闭代码，观察器直接取得父launcher12344/6232 exit0；JVM34388/8468记录JVM_SHUTDOWN，随后4PID均不在。未强停。子JVM退出码没有独立读取；只有第一对有CIM父子绑定原件，不编造第二对绑定。

## 失败、限制与未验

[工具限制原件](evidence/formal-launcher-20261005-coordination/limitations.json)保留初次computer-use应用授权超时、CIM权限失败、UIA滞后、初版decoder误报与恢复。decoder原来要求schema为null，但实际源码保存空白字段的空字符串；修正仅接受前三项连接身份null及空白schema，未改产品/原始文件。worker的四类准备失败和根首次Node启动git的EPERM均保留；后者限定提权用同脚本通过。截图只归档实际目标画面。原始CRLF和末尾空行导致普通diff --check提示，原件保留；JavaFX原生访问警告也保留，不报告零警告。

未取得1100ms闪屏视觉证据，未验无Gate/默认网络运行、完整在途关闭/字段原生键盘及其他完整桌面/OS缩放多屏、其他真库、真实安装升级/回滚/签名/CI结果/用户任务和发布。没有把上述跳过算通过，M8仍未完成。没有产品/测试/buildSrc/打包改动，本轮未新跑Gradle定向/全量/buildSrc/jpackageImage；旧工程通过不充当新证据。

## 集成检查点

F3：worker准备提交f9e62c5、独立复核bb601b9；根91冻结原件、15原生状态/18截图及26份worker原件审核通过。接下来将精确白名单提交并合并main，重新运行综合审计与草稿解码，记录实际提交和推送结果。已有 datacube 跟进保持PAUSED，不创建新目标/预算/后续任务；既有tag不移动。

文档更新首次匹配到两条M8行，校验中止而未修改路线图；随后限定当前推进账本的精确旧状态行完成更新，未改其它M8条目。

暂存复核首次将普通Markdown按原件字节比较，遇仓库既有CRLF归一化而中止；调整为原件精确字节、普通Markdown遵守既有Git文本规则后98文件通过。两份diff检查真实exit2保留于integration目录；除冻结末尾空行，javac原始输出的空JAVA_OPTIONS提示也带尾空格，不修改原输出压掉告警。
