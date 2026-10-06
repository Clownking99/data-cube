# 字段查找小窗口：独立审核与 main 复验

## C0 当前目标与基线

日期2026-10-06，main/worktree起点0f6ba02656fcf3752b180514c72e79151a3320df，限定范围干净；开发分支codex/metadata-search-compact-20261006。目标为现有字段框小空间可达性及基本键盘行为，不扩展查询范围或自动执行。GPT-6.1-sol开发，root独立审核/合并/复验；本轮计划见[范围与边界](../plans/2026-10-06-metadata-search-compact.md)。

已只读重新核实远端main仍为起点；v3.2.9注释tag对象cfe83d64d313ad343fe504e8136299d8a7181fe8解引用起点，release非draft，ZIP 41776676 bytes/EXE 35390464 bytes均uploaded。发布链接https://github.com/Clownking99/data-cube/releases/tag/v3.2.9；这属于先前发布的当前读回，不是本轮新打包/安装通过。

## C1 首轮审核与缺陷证据

- 当前目标：证明用户可见问题并获得有效回归。
- 改动：开发新增回归，root建立本轮专用验证脚本；无其他功能扩展。
- 实际审核：root读取SchemaMetadataSearchDialog及既有测试、首稿新回归。要求去掉人为setDisable(false)，通过正常mock读取与选择启用动作；不用要求某种ScrollPane结构作为失败依据，改为真实受约束窗口的几何；补普通目标、双主题、水平范围、Cancel可见性和焦点可达性。
- 已独立读取red-scene命令/退出码/log：Stage640×480、Scene624×441，SELECT y431..455超出Scene下界441；test实际执行并exit1。早先结构断言和NPE失败仅为测试设计问题，不能冒称额外产品缺陷。red-scene仍含首稿人为启用，物理越界是真实观察，最终动作状态需由正常工作流回归覆盖。
- 失败/未验：最终实现/回归尚待；无本轮原生输入、OS缩放、真库、安装升级、签名证据。主线程初次读取managed worktree被sandbox拒绝，改为精确授权路径的require_escalated后成功；不是产品失败或自动审批拒绝。部分探索读取不存在测试/账本路径报错，仅文件发现错误。
- 下一步：审核最小布局与焦点修复、正常状态回归及原始红绿，再运行分支工程验证；不据旧测试宣称本轮通过。

## C1 最终代码审核

root独立读取最终diff和红绿原件，产品仅SchemaMetadataSearchDialog增加滚动容器、结果可用高度、焦点揭示及close清理；查询/目标绑定、请求/取消/关闭结算未改。测试仅扩充原SchemaMetadataSearchDialogTest。

red-real-states以正常mock查找和选择启用动作，四参数均红：普通light/dark结果区不足120；长文本light/dark SELECT y511..538超出Scene441。red-same-focus进一步证明查询已有焦点、手动滚动后Ctrl+F不能回到可见位置，四参数红。上述原件保留，不以首稿人为启用替代。

具体返工已落实：computed preferred height允许折行文本扩展；focus listener只处理自身内容后代并close注销；仅viewport宽高改变触发异步焦点揭示，手动滚动不强拉回；Ctrl+F显式揭示已有焦点的查询；resize、手动滚动和外部Cancel跨实际FX pulse断言，避免同回调立即断言漏掉runLater。初期尺寸/pulse相关失败均保留。

最终五组定向green-reviewed-pulses103通过、0失败/错误/跳过；root核对正常启用、实际Stage约束、水平/垂直边界和代码差异，批准进入full/buildSrc/image。产品raw SHA D42FFC8870ADB6B8E500D6C109FFFE6E20C7DC1DFB0BB4EC3290F730733428FF，测试raw SHA 9E6824F043071D69D8CD63C752F60F623B16AE50A740A95DE26005F0FC51EEF0；保存final.patch与SHA清单。全量在执行时不得将旧XML算新结果。

下一步：分支全量/buildSrc/image实际结果及镜像审计，提交、合并main、新profile复验。原生/OS缩放/真库/安装升级/签名保持待验；没有新tag。

## C2 分支交付批准

产品提交58278f767d998cb5001407ac69bd71c823fb3ba9，仅一份生产类和对应测试。root已独立重算全部新XML和任务：定向5 suites103 passed/0 skip，全量314 suites3947 total=3944 passed+3 live skipped、0失败/错误；强制buildSrc8/8。三项skip为Redis、Oracle与PostgreSQL显式live门禁，未启用真库。

新jpackageImage成功；root另执行镜像模块/文件/配置检查及外置driverFor发现，test/fixture/profile/注入泄漏0，connectCalls=0。exe/cfg保持原SHA，新modules SHA562E5C36C9738155DEE9C273162FBCFF1BB32F48AACFD123FEDF4B32C4EA3A36；这是新镜像，旧原生桌面证据不升级为新镜像通过。

原始失败、最终patch及源码快照保留。worker本地初始manifest含1123文件/64.5MB，其中Gradle binary报告缓存既不是测试结果XML也不是必要复现来源；root不删除原件，但从Git归档排除binary目录和仅反映本地全集的manifest.json，精确冻结1040份可审计日志/XML/命令/源码等（31946420 bytes），以raw-manifest.json为提交字节依据。两种集合边界明确，不宣称二进制缓存已入Git。root独立验证脚本/结果单独归档。

下一步：完成精确暂存字节审计，合并main，在新的隔离目录产生main定向/全量/buildSrc/image证据；当时main未复验，不据分支通过替代。
