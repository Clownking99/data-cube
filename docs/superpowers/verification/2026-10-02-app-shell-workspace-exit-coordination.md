# DataCube完整AppShell工作区退出决策：独立审核与集成

客户端日期：2026-10-02；起点main242946facc6c4c648cf1550d995aa9237b6544d4。依据[限定计划](../plans/2026-10-02-app-shell-workspace-exit.md)，复用现有GPT-6.1-sol与独立codex/app-shell-workspace-exit。没有新线程、原生或真库操作；既有datacube保持PAUSED。

## N0：基线、范围与独立源码审查

- 当前目标：真实AppShell工作区最终发布失败后的实际生产Alert取消/重试/忽略及取消后继续工作，不重复已有功能或下层测试。
- 改动：限定计划、独立分支和6个新的验证/原件审计脚本；独占新UUID验证namespace，脚本语法检查通过。新测试尚由代理实施，没有产品修改。
- 验证：起点main/worktree同HEAD且授权范围干净；独立阅读SqlWorkspaceUi freeze/finish/showDecision、SqlDraftUi、SqlDraftDirectory原子publish、SqlDraftCoordinator workspaceOperation与AppShell.shutdownAsync恢复。实际finish在标签guard结算/移除后才保存，CANCEL不能声称全部原tabs仍在；原恢复点/冻结布局与全局入口恢复需分别验证。默认取消、真实owner/Alert及原store I/O边界不能由假guard/supplier替代。
- 失败/未验：DataCubeFx初次旧fx路径猜测经rg纠正，仅检索错误；AGENTS文件无命中，按用户提供本机约定；记忆快速查无项目命中。新workspace/shell证据全部待运行，不借前轮通过背书；原生/正式launcher/真库/完整M8发布依旧待验。
- 下一步：GPT-6.1-sol独占Gradle实施并交还；根独立审查原始首失败、XML/源码/存储字节，必要具体返工，然后新分支/main定向/全量/buildSrc/image、原件和暂存字节审计、本地提交合并与交接。

## N1–N2：真实生产对话框与独立审查通过

- 当前目标：补齐真实workspace保存失败决策/完整shell恢复证据，确认没有假guard或decision返回值。
- 改动：新增AppShellWorkspaceShutdownTest，原AppShell/SqlDraftUi/SqlWorkspaceUi/SqlDraftStore/Directory及真实文件task委托；只对自己的workspace.bin原子mover注入失败，其他原mover委托，草稿成功移动后才计数。没有产品源码改动。
- 实际验证：首轮新4例2失败，修正后4/4，最终四suites44/44（4+9+17+14），零错误/失败/跳过；三独占profile、新test task/XML和最终七源SHA独立核对。原production Alert实际5条，owner/文案/三按钮/default cancel校验；取消/重复取消旧字节不变且旧tabs如实已移除，global runner可继续、真实新文件/脏文本去重/save和明确新layout发布通过；retry两次相同冻结bytes，ignore故障仍启用但旧字节保留。
- 审查返工：构造失败独立关自有Stage/全局资源、保留主失败/suppressed及home恢复；失败fallback有界且只作fixture安全清理；每场景要求真实草稿成功atomic move>0、provider请求0。完成dispatcherClose1/runtime和registryCLOSED/global runner拒绝、真实store重新打开证明锁释放；新accepted四例没有使用fallback。[独立XML审计](evidence/app-shell-workspace-exit-coordination/n2-worker-review.json)、[源码与manifest审查](evidence/app-shell-workspace-exit-coordination/n2-worker-manifest-review.json)。
- 失败/未验：首红仅新计数器误按.bin，实际UUID.draft，原2failure/raw/参数/hash保留，不算产品红。worker口头Alert6次重复计取消首次，原XML实际5、账本已校正。根初次把AppShell嵌套SqlFileEntry猜成独立文件，经实际代码确认，仅只读检索错误。根将worker进程审计收窄为3自有profile的WMI服务端过滤并核验0，不覆盖旧manifest/三轮原件、不重跑旧审计制造新记录。原生/正式launcher/真库/终态产品恢复/完整M8发布未验，不因此提高等级。
- 下一步：代理正式交还唯一Gradle执行权；根提交精确test/worker原件，然后修正分支及main各执行新定向/全量/buildSrc/image与独立镜像/源码/字节审查，本地合并及更新交接待验。

## N2a：提交前字节审计拒绝与精确修正

- 当前目标：确保全部原始worker证据进入Git时保持真实字节。
- 失败：首次developer暂存审计exit1，corrected-fourcases原XML与staged blob不同；worker新目录缺少自己的-text属性，Git换行归一化。未执行commit，原XML/raw与原manifest未修改，不是测试失败或自动权限审核拒绝。[首错误](evidence/app-shell-workspace-exit-coordination/n2-first-staged-byte-audit-error.json)。
- 改动/验证：仅在本轮worker目录加自有.gitattributes，-text保留原字节及日志/XML空白规则；精确重暂存/renormalize本目录后重新核对所有原manifest哈希和原始/暂存字节，不重跑测试制造证据。
- 下一步：字节审计通过后本地提交，根执行新分支门槛；原生/真库/完整发布待验不变。

## N3a：新分支门槛及本地集成准备

- 当前目标：以审查后测试提交25b770a517d6eda08559fd149ddc3b909ade2e6c新验证分支，确定能否本地合并。
- 改动：冻结778源码/test/build文件，独占新UUIDnamespace；产品源码无改动。developer暂存字节24文件通过后已本地提交，首次字节审计失败保留。
- 实际验证：新定向18 suites 235/235、全量313 suites 3938 total=3935 passed+3 live skipped，零错误/失败；buildSrc8/8。定向/全量各8 tasks、buildSrc4 tasks、jpackageImage14 tasks全executed。新workspace四例与原相关回归均实际执行，不借旧XML。
- 镜像审查：183文件，359测试类型隔离无class/file/option泄漏；Oracle/PG driverFor零连接发现connectCalls=0，无凭据/原profile。778源文件冻结稳定，三项产物SHA见branch/image-audit.json。
- 失败/未验：本次工程门槛无失败；3 live跳过仍不算通过。首轮2项新夹具后缀计数失败、口头Alert误计和首暂存换行审计失败全部保留，均非产品红/权限审核拒绝。没有原生/真库/正式launcher/M8发布验收，取消前已移除标签不能称为原标签保留。
- 下一步：提交独立分支原件，复核main基线后本地no-ff合并，再以实际main合并代码全新profile重跑全部门槛和审计，更新交接与待验。

## N3b：main新复验与本轮交付

- 当前目标：交付真实AppShell工作区最终保存失败的合成FX决策/恢复证据，本轮结束，不自动扩展功能或跟进。
- 改动：实现25b770a517d6eda08559fd149ddc3b909ade2e6c、独立分支证据e94f77a1ab6fd6f2d4aa524b5b4217e37ef38a24，本地no-ff合并main代码b57c6acd8e811854e7ace0102a4d5b7f3996e784。只有新增测试和证据，产品未改；最终文档/原件集成保持受验src/test/resources/build树不变。
- 实际验证：分支/main各新定向18 suites 235/235、全量313 suites 3935 passed/3 live skipped、buildSrc8/8，零失败/错误；定向/全量各8、buildSrc4 tasks均实际executed。jpackageImage14 tasks，183文件镜像/359测试类型隔离无泄漏，零连接driverFor发现通过；三项产物SHA与分支相同。778源文件稳定，217 Java checkout差异严格逐字确认仅LF/CRLF。[实际结果](evidence/app-shell-workspace-exit-coordination/results.json)、[源码比较](evidence/app-shell-workspace-exit-coordination/source-comparison.json)、[main复验记录](evidence/app-shell-workspace-exit-coordination/n3-main-revalidation.json)。
- 新行为：main四个新例实际5条production Alert，owner/文案/三按钮/default cancel对应原实现；原标签已守卫结算/移除。取消两次旧workspace字节不变、global task继续/真实新文件读取脏文本去重/save保持，明确活动后发布新layout；retry发布相同checkpoint ids/位置/顺序/选中项的冻结bytes；ignore故障仍启用且旧字节保持；合成Dialog关闭按cancel。成功每例fileDispatcherClose1/runtime和registryCLOSED/global runner拒绝，实际store重新打开证明锁释放，provider请求0。main新例未使用fixture fallback，不把安全fallback当成功。
- 失败/未验：首轮新夹具4项/2失败后缀误计、worker口头Alert6→实际5修正、首暂存换行审计失败及只读检索错误全部保留；均不是产品缺陷复现/自动权限审核拒绝。本次3 live跳过明确不计通过；没有原生操作/正式launcher/真库/发布证据。真实workspace CANCEL仅合成FX已验，原生workspace/完整shell退出、FAILED_PARTIAL后产品恢复、字段全键盘/输入/多结果/失效/小窗、OS多屏、其他真库、安装升级/签名/CI/用户任务/发布仍待验，M8不称完成。
- 下一步：本轮限定目标完成，最后提交交接/路线图和原始哈希/暂存字节审计并本地集成；最终HEAD为受验main的文档证据后继，源码一致以只读复核确认，不拿旧执行假称新验证。datacube实查PAUSED，自有根验证namespace的Java进程0；既有Oracle表未访问/清理，无推送/外部操作，本轮交付后不自动下轮。
