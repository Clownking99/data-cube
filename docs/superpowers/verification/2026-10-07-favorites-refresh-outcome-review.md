# SQL 收藏写入/刷新结果：独立审核和main复验

## S0 目标与基线

2026-10-07，main a9d255aa39cf1712660e509495f8e9ce4d87ad4f，授权工作区干净；复用已附加worktree的新codex/favorites-refresh-outcome-20261007分支。上一轮Verify37563805978四任务success为历史基线，本轮需要新测试。

root已读完整SqlFavoritesDialog、SqlFavoritesDialogTest、SqlFavoriteTabs、FxTaskScope及SqlFavoriteStore写入/快照方法。确认当前写操作与后续load的异常被合并处理；潜在新建重复UUID须实测，不能仅因代码形态声称已复现。真正save异常仍可能具有存储层保留/备份语义，不能把所有异常都说成零字节写入。

范围与决策见本轮计划。只修保存/删除/恢复成功后的刷新失效，保留真正写失败的编辑/确认与关闭保护；不扩展SQL或布局。禁止.testagent/原配置凭据历史业务内容，无真库和系统剪贴板；只用mock及专用临时库。原生/安装签名与完整M8仍待验。

本轮路径发现曾尝试不存在的SqlLibraryDialog/Pane文件，rg后直接转SqlFavoritesDialog；无产品或验证结论。Memory轻量检索无相关命中，不复用历史记忆断言。

下一步：先读取原始红灯与最小设计，再审批代码进入完整工程验证；不将旧CI或上轮测试算本轮通过。

## S1 首红与状态方案

root独立读取red-new-real-store的源码/patch、真实:test日志、command/exit及失败信息。初始正常UI载入后向专用TempDir真实SqlFavoriteStore保存，首次实际落盘1项并核实正文；仅后续读取被注入IOException。再通过原按钮正常fire保存，真实库变为2项（expected1 actual2），首红exit1。不是只检验文案或禁用样式，未访问用户原收藏。

root接受最小方案：只在Repository写方法（含资源close）正常返回时创建已完成结果，save/delete/recover分别明确提示。后续读取失败时保留必要内容/精确身份，清除过时列表、冻结编辑及写入/离线打开，只允许明确重读或关闭。dirty仅在已确认完成待重读阶段为false，不能误提示已提交正文仍未保存。重读失败或任务拒绝仍保留已完成结果；重读成功只从真实新snapshot选择精确ID，不伪造消失或变化的项。处理器独立守卫该状态，close清除引用、scope拒绝迟到回调；Error不能降格为可恢复读失败。

真正写方法throw仍沿用编辑保留/明确丢弃/重试与存储CAS、备份、额度守卫，不将未知结果说成绝无文件变化。至少新建真实临时库及其他保存/删除/恢复、重复操作、重读两次失败、重读恢复、取消/owner关闭和迟到结果均需定向行为证据。当前实现与回归在进行，尚无本轮绿灯/full/buildSrc/image；主线尚未合并。原生和完整M8继续待验。

## S1 首轮绿灯和代码审查

root读取完整当前生产diff和新测试差异，变化限于SqlFavoritesDialog及其既有测试。生产引入已完成操作及待重读状态，写方法正常返回后才确认；普通save/delete/recover抛错仍走原失败通道。成功后读取失败清除旧列表、保留正文与精确身份、冻结编辑/其他动作，处理器也守卫；重读被拒仍保留已完成提示。重读成功使用实际新snapshot，允许外部新版本或消失结果，不伪造旧对象。

已读取green-new-real-store及green-outcome-matrix原始:test日志exit0。首版10项（旧9+真实临时库回归）、当前20项覆盖四种完成状态、直接调用被禁用动作处理器仍无重复写、反复读取失败只增加read数、fresh外改与missingID、任务拒绝及四种已完成关闭不误弹丢弃。最早红灯原件未改。

独立审查要求补全：真正写失败四操作的重试/编辑保留、损坏副本通过真实临时store恢复且原字节不变、post-write读取在途时普通关闭仍拒绝、owner强制close及迟到结果不复活UI。write后读取捕获InterruptedException时恢复线程interrupt，不吞取消信号；Error仍走原fatal规则。完整定向/full/buildSrc/image尚待，不提前交付。

## S1 关闭夹具诊断与调用链核对

28项版中26通过、2个owner-close用例失败，原件green-failure-owner-protected保留。失败时实际owner Stage关闭、Dialog.isShowing已false，但同FX回调正文未清理；暂不能据此宣称生产生命周期缺陷。允许跨pulse诊断，禁止主动view.close或放宽关键断言凑通过。

root进一步查到AppShell唯一生产入口调用SqlFavoritesDialog.show，第48行已有try-with-resources包裹showAndWait。旧新增Fixture只调用dialog.show，缺少生产入口的资源所有权包装。因此要求使用同样try(view)+showAndWait真实owner路径并等待包装返回，独立核实关闭、cancel信号和迟到回调；若生产包装正常，仅修夹具，不新增无必要owner监听器。此项诊断未完成前不把隐藏事件差异冒称产品缺陷。

## S2 最终定向审核通过

正式生产try(view)+showAndWait所有权包装下，owner隐藏后正常退出并清理，无需新增showing监听器。green-owner-hide属于弃用方案诊断，不作为最终源码验收；green-owner-production-wrapper的28项均通过。root再指出虚拟线程执行器的独立空任务不是迟到任务完成屏障，最终测试捕获mock实际后台线程，在释放后join并确认终止，再走FX队列屏障。这样断言确实覆盖迟到成功/失败回调，未手动提前close制造通过。

root独立读取最终生产diff、owner和interrupt回归及green-targeted-task-join原始command/exit/XML。六组61项通过（Dialog29、Tabs1、Store8、FxTaskScope5、DraftDirectory17、TabFileLifecycle1），0失败/错误/跳过。线程interrupt由测试专用executor.afterExecute直接观察，没有新增生产测试接缝。完成正常返回与异常未知结果的边界清楚，Error不被捕获降格；安全状态与真实磁盘字节断言充分，本轮限定源码审核通过。

生产/测试SHA256分别F571F512C5D71A0C70B2757C3B7C59BD781059CACB4B1DEC18204BC384476A27、F964A968D0E0608E662E881DDECC02F0D19983346A21516B031CD86C42995162。已要求冻结源码并执行全量、root buildSrc、image；上述61项不能替代尚未进行的完整工程/main复验或原生验收。

## S2 全量通道中断与接管

full-final在:test期间执行通道中断，开发agent已不在活动列表；没有exit.json或BUILD SUCCESSFUL，具体中断原因未知。root仅检查进程ID/启动时间确认无残留java/javaw进程，保留该目录未完成log、interrupted.json和partial-xml-not-validation，不从残留XML推断通过。

已再次核对最终两份源码SHA未变；root使用原隔离脚本、新UUID profile执行full-root-resumed。此后Gradle唯一执行者为root。开发实现与独立源审查已完成，root接管剩余工程/main验证，不创建更多线程或扩大范围。

## S3 分支工程审计与源码提交

Audit-Branch-Tests独立核查实际Task行、command/exit、314套完整XML及时间戳、冻结源码SHA。最终定向61/61；full-root-resumed共3977项，3974通过、0失败/错误、3明确live跳过；buildsrc-root-final实际root :buildSrc:test8/8；image-root-final实际jpackageImage通过。三个跳过为Redis standalone、Oracle/PostgreSQL SchemaDiff live测试，均未验，不计通过。

源码提交3b34183ac75b017b1ffc3475a1941c5c5452d799，仅SqlFavoritesDialog及其测试。branch-image-audit用当前实际镜像列出类/文件/cfg，无测试类、探针、profile、fixture或测试JVM选项泄漏；外置探针只调用已读源码的driverFor，Oracle/PG驱动发现成功，connectCalls=0。三产物及SHA/字节详见audit.json，runtime modules SHA A1FDB3C1D145A062DD6FE95503FE70D84DB1D2E0EC524394EC8A7DD7765D29C0。旧原生证据不自动绑定新runtime。

root复核源码范围、空白检查通过。接下来精确冻结/暂存原件并验证Git实际字节，合并main后再新执行同一验证矩阵；此检查点尚无main复验/推送/CI完成声明。
