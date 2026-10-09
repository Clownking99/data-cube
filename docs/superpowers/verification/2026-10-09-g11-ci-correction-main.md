# G11 PID修正后main完整复验

本报告保留main `e368a1b16bdee226925b363f06b4dc4f003c1f0f`的被拒绝P3，接续[首次本地P3](2026-10-09-g11-main-verification.md)。本页结果和1904份REJECTED原件不改成通过；退出观察修正合入后的最新版本与交付判定见[新main报告](2026-10-09-g11-root-exit-main.md)。

## 修正与输入

首轮推送cfd9d4ed的[Verify37893270726](https://github.com/Clownking99/data-cube/actions/runs/37893270726)整体失败：Ubuntu的PgDump测试在PID文件创建但内容未写完时解析空串；其余三个任务成功。原日志和终态API见[首失败记录](evidence/g11-ci-pid-readiness-20261009/first-ci-final.json)。未对旧提交盲目重跑CI。

修正提交52374f7112eca999ffb2a28551e5ef6b8f2b2946只涉及三个测试文件：测试helper完整写入并关闭PID文件后才创建.ready标记；消费者等待标记并验证正long。新增10个确定性回归，覆盖未发布的空/部分/完整内容、child/grandchild发布和已发布非法内容；原5秒时限、进程收尾、邻居存活、过滤器和skip策略未放宽。证据提交ca055cc597fa92ce0d13b52ea892d2cd4bea72ef由root审查后合入main。产品、共享12工具、构建及CI未改，未开展夹具迁移。

开发最窄验证实际33通过/0跳过，其[103文件manifest](evidence/g11-p2-ci-pid-4983fab79aa14a5da1626836b240525c-frozen/manifest.json)SHA256为`04ee175ba44263ac75a6e528e19c0d6f1cafcaea1e72c46e599657cb79f72379`。首次相对spec在进程启动前拒绝exit1，原件保留；绝对参数执行实际exit0。root独立核对109个提交路径及Git原字节，见[运输复核](evidence/g11-ci-pid-readiness-20261009/root-review/transport-after.json)。两份Java在main检出为CRLF，正文一致；新P3使用main实际磁盘SHA。

## 新P3结果

root独占Gradle，使用全新[g11-p3-581b459dad08-package](evidence/g11-p3-581b459dad08-package/preparation.json)，每阶段新的UUID home/temp/build。875输入、408测试类型、12工具；编排器SHA256为`844df3e07a93ecdb727fcf53608ef05e5b2700f2fe357a6c75597f988f39c48f`，仅改变新前缀/受验main绑定并保留显式require。

完整序列实际exit0，80项原控制、五工程阶段自然结束。独立读取原始XML：定向291通过/1精确live跳过、全量4721通过/3live跳过（PID33例全通过）、buildSrc8通过/0跳过；image与linked阶段退出0。跳过不计通过。

**本轮P3被独立审核拒绝。** full/java-version的process-receipt.json报告passed/complete，同时rootExited=false、rootExitCode=null。受控host及捕获的真实handle均记录PID21608实际退出0，但root-exit.json缺席。审核器实际exit1，未放宽要求。PowerShell7.6.5只读实测：默认ConvertFrom-Json把startTimeUtc变成DateTime，与ISO字符串比较false；按String读取后比较true。源码同时存在host循环尾刚退出时漏发事件，以及最终passed只检查host汇总而未要求顶层根退出一致的问题。

这是runner四契约内的必要修正，已下发同一开发会话：统一实际handle退出观察/原子事件、强身份关联与晚读、成功门禁，以及缺失/损坏/矛盾和循环尾退出的确定性组合控制。保留首因、预算、真实Job/流收尾，不用无条件host汇总fallback或反复重跑掩盖缺口。root已停止Gradle，待源码准入后才移交验证权。

[独立拒绝裁决](evidence/g11-p3-581b459dad08-root-review/round-verdict.json)、[审核拒绝原始stderr](evidence/g11-p3-581b459dad08-root-review/full-review-refusal-stderr.raw)、[相同PowerShell身份诊断](evidence/g11-p3-581b459dad08-root-review/identity-coercion-stdout.raw)。本轮1904文件/33165510字节按[REJECTED manifest](evidence/g11-p3-581b459dad08-rejected-frozen/manifest.json)封存，SHA256 `40fed9cfd3b881fe62bd6e237d27eed090dd275316f75e08df9b11f060754b97`。序列自报成功与独立验收拒绝同时保留；旧冻结根未改。

## 交付与边界

本轮未获交付准入，没有推送或执行[预备交付入口](evidence/g11-p3-581b459dad08-package/delivery-intent.json)。该入口对应已拒绝轮次，不能当作成功或未来修正版的交付入口。共享工具修正后须新冻结开发验证、合main、新完整P3，再建立新交付定位，推main并核对相同SHA四任务CI与远端身份。首轮CI失败回执和首次P3的1900份原件保持不变。

不将三个live前置跳过计作通过，不把本机helper/CI临时Redis等同真实业务环境。完整桌面、真实Redis/关系库、安装升级回退和生产签名仍未验；G10多会话RSS、DNS/阻塞写/native close/GC及同步OS永久阻塞的局限继续保留。private Job结算只覆盖实际成员和持有handle，不外推委托服务，故障注入不等同实际不可杀进程。

不fetch/tag/PR/发布，v3.2.9保持不动。不自动启动受控JVM夹具迁移、Redis/Shell拆分或下一轮自动跟进。
