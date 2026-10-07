# 查找表/视图：独立审查与 main 复验

客户端日期2026-10-07；原始命令的UTC时间保留，不改写为客户端日期。计划见[本轮范围](../plans/2026-10-07-schema-object-compact.md)。

## S0 基线

本地main与远端main均为b4484c6cedd5ee508234a3036d340b8c14066ace，授权工作区干净；旧Verify37455294214 success只作为历史基线。正式tag v3.2.9仍指向0f6ba02。复用独立worktree的新codex/schema-object-compact-20261007分支，GPT-6.1-sol开发，root独立审核；Gradle单一所有者。

目标是既有名称查找的小窗口/动态反馈/键盘可达性，不改变远端名称快照、纯本地筛选、明确候选确认或资源准入。复制只注入fake动作；不访问真实剪贴板、数据库或用户原profile。无新tag/PR/安装/外部联系，.testagent禁读改枚举。

## S1 真实红灯与首轮源码审核

root已阅读完整SchemaObjectSearchDialog、原测试和生命周期、上轮字段框布局，核对新增回归与red-visible原始日志。此前旧窄窗测试仅pane.resize480×610，不等于真实Stage480×480。

red-visible共8组合：480×480的普通/长合成连接名×light/dark四组失败，选中行y147.33..172而列表y146..168，底部至少4px被裁切；640×480四组没有实际裁切。高度阈值初稿red-stage不作为产品缺陷依据，不把仅30px结果区直接声称裁切，也不冒称提示/复制/字段工具已在原状态越界。

root审查候选实现：仅本类增加computed-height滚动容器、结果最小高度、内容后代焦点揭示、viewport尺寸变化与content高度变化后的揭示，Ctrl+F显式返回查询；close移除scene listener。原请求/返回/筛选/候选确认/取消的业务逻辑不改，没有跨类重构。

具体审查要求：动态复制反馈通过fake CopyResult真实触发，确认反馈显示/清除后焦点可见；手动滚动和外部确认/取消跨FX pulse仍不强拉；既有焦点Ctrl+F、缩小、Tab/Down/Enter契约保留。测试不能setDisable(false)绕过状态，不能把程序化事件算原生桌面。新的Runner只归档实际执行任务XML，不复制binary或image下旧测试报告。

当前失败/未验：最终布局绿灯及上述动态用例待完成，未跑本轮full/buildSrc/image；没有本轮原生输入、OS缩放/多屏、真库或安装升级/签名通过。下一步独立审查最终测试与红绿，再允许分支完整验证。

## S1 修正与扩大验证

root独立复读最终源码和测试差异：动态复制走注入callback，清除反馈/缩小/手动滚动/外部确认取消/已有焦点Ctrl+F均跨三个真实FX pulse，Tab/Down/Enter返回精确TableRef且快照只读一次。首次五组113项中3个Lifecycle失败由pre-show ScrollPane skin未附着导致lookup为空；仅helper改为访问实际content，原关闭/取消/资源和时序断言未动。重跑五组113/113、0skip已读取原始命令、exit0及报告。

首轮full仍在进行，已观察Copy与KindFilter旧窄窗四项失败：未show时直接pane.resize(480,548)，确认按钮y533..560超过固定548常数。暂不称产品缺陷或测试误报；已要求保存实际pane/scene尺寸诊断，再将这两旧用例改为真实shown Stage约束并跨pulse验证，保留复制失败反馈、目标/预览行高、201对象cap warning完整折行和明确动作/离线契约。不得通过放宽常数、删除断言或生产applyCss来消除红灯。定向扩大为七组，随后必须新full/buildSrc/image；旧final.patch/SHA保留为首full身份，后续另存。

主线程一次非提权Git读取C盘worktree返回not a work tree；以明确授权路径require_escalated后读取正常，这是工具沙箱工作树可见性问题，不是源码或测试失败。main仍未合并，未取得本轮原生/安装升级/签名证据。

## S1 七组定向审查通过

首full实际3957总、4失败、0error、3live skip，exit1，不能作通过。独立读取red-unshown-dimensions两XML的四例system-out：pane480×570、Scene0×0、showing=false；confirm533..560在真实pane内，仅超原常数548。原因已证实为未显示夹具的尺寸假设，生产未再修改。两旧测试四参数改为shown Stage480×548，跨FX pulse后按真实viewport逐项验证可达及Scene外部confirm/cancel可见，保留目标预览行高、复制失败提示、201对象上限提示完整折行与明确动作契约。

root独立审核最终五文件diff及诊断原件；green-targeted-seven七XML、真实:test执行/exit0，共160通过、0失败/error/skip：Routing44、Metadata31、Copy25、Kind22、Object25、Lifecycle8、Service5。最终产品仍仅原49行局部diff，生命周期及所有请求/筛选/返回语义未动；四份测试变化包含十个新增参数案例与旧布局/节点定位适配。first final.patch/SHA保持绑定首次full；final-reviewed.patch及五文件SHA绑定后续完整验证。

当前目标转为分支完整验证；full-reviewed正在新profile执行，buildSrc/image与main仍待完成。root验证脚本首次Parser检查因未初始化ref变量失败，随后正确初始化并真实语法检查成功；工具脚本准备错误不算产品或验收通过。下一步独立统计最终XML/源码身份，镜像审计后提交合并。

## S2 分支交付批准

产品提交74517f615ed42d1a75dcd709e5dde06810f4f75d，仅一个生产类及四份相关测试。root执行Audit-Branch-Tests独立核对实际任务行、command/exit、XML时间戳和最终五文件SHA：七组160/160、0skip；full-reviewed314 suites共3957总=3954通过+3明确live跳过、0失败/error；root :buildSrc:test --rerun-tasks实际4任务、8/8。分支新jpackageImage成功，14任务中7执行7up-to-date，打包任务确实执行。

root另独立检查实际镜像：测试类/夹具/profile/JVM测试选项泄漏0，外置driverFor仅发现Oracle/PostgreSQL驱动、不调用connect或加载配置。三产物：exe SHA6C32DDB8…、cfg E53F0D48…、modules33BD7B27D4EEEE21E8D13E8E584A21F679C4677236F4A770381E9F16CC02596A，modules102387620字节。详细结果见coordination/branch-independent-tests.json与000-branch-image-audit/audit.json。

首full与尺寸诊断原件完整保留；最终worker使用仅实际任务XML归档，无binary缓存。原件将冻结raw-manifest并按实际Git blob逐个验证，不用旧测试报告或skip冒充通过。当前main尚未合并；下一步归档提交、合并后新profile复验。原生输入/OS缩放/多屏、真实安装升级和签名仍待验，旧原生证据不移植为当前镜像证据。

归档首轮字节审计拒绝：仓库既有*.log忽略规则使git add目录未暂存原始日志，非原件变更或换行损坏。已精确check-ignore/check-attr确认text unset且日志缺失；改用raw-manifest列出的精确NUL路径强制暂存，不修改原件或仓库忽略规则，再重新执行实际Git blob审计。

## S3 main 集成与新验证

分支原件提交c41d117ea804b159d0b12fc6d0677e875ff5ee7a经719份worker/17份coordination raw与实际Git blob逐一审计。合并前main仍b4484c6且授权范围干净，--no-ff合并为53d2dc6e1300ed1d70f7cf26edf5835c07602fca。main再独立核验719份worker原件与Git字节相同，受验src/test/resources/build/workflow树与74517f6一致。

当前新profile强制定向七组已实际启动；随后clean全量、强制buildSrc及jpackageImage都需新执行，不能借分支通过替代main证据。原生/OS缩放、真库、安装升级和签名仍未取得本轮证据。下一步按实际main结果决定推送；不创建或移动tag。

## S4 main 本地交付完成

main53d2dc6在新的独占profile分别执行并通过：七组160/160、0skip（8任务实际执行）；clean全量314 suites、3957总=3954通过+3明确live skip、0失败/error；强制buildSrc8/8（4任务实际执行）；jpackageImage成功（14任务实际执行）。只保留各命令实际执行测试任务的XML，image目录没有旧测试报告副本；原始command/log/exit均保留。既有unchecked测试编译提示和JEP493说明不当失败或隐藏。

root独立main镜像检查通过：无test/fixture/profile或JVM测试选项混入；外置驱动发现不打开连接。exe/cfg/modules三项长度及SHA与分支逐项一致，modules33BD7B27D4EEEE21E8D13E8E584A21F679C4677236F4A770381E9F16CC02596A。main受验src/test/resources/build/workflow树仍等同74517f6，719份worker原件与Git字节一致；实际汇总见本轮coordination/results.json。

本轮产品目标完成：真实480×480名称候选行不再裁切，目标/筛选/列表/预览/复制/字段入口可滚动到达，焦点在窗口缩小及反馈变化后保持可见；手动滚动和外部确认/取消不强拉，已有焦点Ctrl+F可返回查询。现有只读快照/本地筛选、明确候选Enter生成未执行SELECT、取消关闭和配置失效语义保留。开发由GPT-6.1-sol实施，root独立审核源码、四次有限测试适配与全部最终证据，不重复实现其他功能。

证据等级仅mock与真实JavaFX Stage的程序化几何/事件。仍待验：名称/字段完整原生键盘及下游动作、OS缩放/多屏、完整AppShell在途/FAILED_PARTIAL恢复原生矩阵、默认无Gate启动与闪屏视觉、真实安装升级/回退、生产签名；完整M8不称完成。旧原生/Oracle证据保持原镜像身份，本轮未访问真库、原配置/历史/业务内容或系统实际剪贴板，没有安装/PR/外部联系。

下一步仅为最终文档和原件提交，按既有授权推送main并检查精确提交的Verify。托管结果以本线程独占临时回执及最终交付答复中的实际GitHub run为准；本地通过不预判托管通过。只文档/原件收尾不改变已复验源码，不重复测试。v3.2.9继续指向发布时0f6ba02，无新tag或发布；datacube保持PAUSED，不自动开启下一轮。
