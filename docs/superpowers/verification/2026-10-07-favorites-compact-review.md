# SQL 收藏小窗口：独立审查与main复验

## S0 当前目标/基线

2026-10-07，main f5b2b7dff4fd2000a234a1b12fbee863f84efd34，授权范围干净；前轮SQL收藏写后刷新修复及CI37585497605为历史，不当本轮证据。独立分支codex/favorites-compact-20261007复用既有worktree。Memory检索无相关命中；文件发现误用MetadataSearchDialog后以rg找到实际SchemaMetadataSearchDialog，无产品判断依赖不存在文件。

root已核对当前SqlFavoritesDialog布局/事件和既有收藏回归，以及两个搜索框布局实现线索。新候选为固定内容在紧凑窗口的裁切/焦点可达性，未复现前只列风险。开发限定范围见本轮计划；写后完成/重读状态、真正失败、存储与关闭语义保留。只用合成FX/mock/临时库，不读取真实内容或.testagent，不使用真库/原生输入。本轮完整M8不称完成。

下一步GPT-6.1-sol建立几何红灯与最小修复，root审核再进入最终完整验证。不得用旧测试或仅isVisible证明本轮可用性。

## S1 实际几何首红与审查

root独立读取002-red-geometry命令、实际:test日志、exit1及XML，light/dark×480/640共4例失败、0skip。真实Stage480×480时scene464×441，pane最小高516，Cancel纵向477.6–506.4完全越出scene；说明402–456部分被裁切。640×480时scene624×441，pane高466，Cancel427.6–456.4部分越界。屏幕与scene坐标均归档；这是可复现布局缺陷，不是仅isVisible判断或强行resize单节点。

001-red是新夹具参数错误引起compileTestJava失败，不计产品红灯。最小方向为外层垂直滚动、横向宽度约束、焦点与布局变化显露，保持取消在外层按钮栏和原try(view)+showAndWait关闭所有权。root同时发现几何测试收集延迟assertAll期间的手动滚到底时序需要修正：应在焦点轮询完成后再实际滚到底测status/privacy，不删边界断言。原红灯原件保留。

当前仅首红确认，修复/定向与full/main尚待。SQL正文/焦点、存储确认/新pending语义不扩大改动；所有测试只用合成数据，原生键盘与完整M8仍未验。

## S1 首轮几何绿灯与生产差异

root已读取004-green-geometry的实际exit0/XML/日志，两主题×两窗口4项全部通过，0skip。480×480实际scene464×441，scroll viewport纵向11–382；重读按钮纵向355–382、滚动到底的说明316–370位于viewport内，取消仍由外层DialogPane按钮栏承载。640对应实际scene624×441同样通过。003为布局代码块替换遗漏导致scroll未定义的编译失败，不算运行测试/产品回归；原件保留。

当前生产差异限于SqlFavoritesDialog：ScrollPane适配宽高，content自然首选最小高，列表/SQL/命令行宽度约束，焦点及布局变化reveal、同焦点Ctrl+F显露、close移除焦点监听。TextArea/ListView内部焦点映射到控件边界，不改内部滚动/caret。旧写状态、Repository、确认、try-with-resources所有权均未改。root接受这一最小方向，要求补完整操作计数、已完成/失败/恢复状态、手动滚动、长SQL选择/内部滚动、宽窗口/resize与关闭后排队布局回调。最终定向与全量/main尚未验。

## S1 扩展定向夹具诊断

005-expanded-targeted正常结束、exit1，65项中10失败/0skip，未中断。root独立读取新增测试完整diff与实际失败：6项640宽窗口单行提示被错误要求height>20；4项把ScrollEvent直接送给TextArea外控制节点导致内部未滚动。前者是错误假设，后者需转实际skin内容节点并跨pulse验证真实滚动增量；保留外层vvalue、selection/正文不变断言，不能用setScrollTop替代被测事件效果。待新证据前不宣称这些行为已验。

005过滤误写Tabs类名/SqlDraftDirectory包名，未覆盖最终要求六类；即使其中旧29收藏测试通过，也不替代最终完整定向。原失败/首红未覆写。生产diff本检查点未再变更；最终六类定向、宽窗口和关闭布局回调补验进行中，full/main尚待。

## S2 最终定向与独立审核通过

007-final-targeted实际六组84项（Dialog52=旧29+新23，Tabs1、Store8、FxTaskScope5、DraftDirectory17、TabFileLifecycle1），0失败/错误/跳过；root独立读取command/exit/全部XML，1m24正常结束。006四个正确skin靶点滚轮案例通过，单个宽窗口错误要求vvalue=0的断言失败；该值在fit-height时可保留历史normalized值，最终改用内容高度≤viewport和全部控件真实边界。006原件保留，不当最终通过。

root读完整生产diff与新增测试：实际操作及确认次数、选中行在列表内、首次busy/保护状态/真写失败/完成后重读流程、同焦点Ctrl+F/禁用过滤框、真实skin ScrollEvent导致内部scrollTop增加且外层/文本/selection不变、resize保留caret、宽窗口及实际height变化后关闭的排队布局回调均覆盖。只改SqlFavoritesDialog布局与对应测试，原write/load/确认/try(view)关闭所有权保持；代码审核通过，无待修产品发现。

最终源SHA72987E021CEF9E0E5FAEAA81A7F84F4D7FC50C3B9F687DA37781B5F2DFD2072B，测试SHA02FD27C6186ED9ECE644A1EFD300372932A281C3EBC4CE58AD4B49F1F9A2122A。root首次哈希工具误按旧Hash字段读取新sha256字段导致drift报错，已独立实算两文件/patch/runner均一致，非源码变化；本轮审计脚本兼容字段并验证长度/路径/摘要，不改原始hash文件。

已授权开发者冻结代码后串行full/buildSrc/image；Gradle仍由开发者独占。完整工程/main/CI尚待；“mock Repository注入写失败”与原生桌面严格区分，原生输入/OS缩放/M8未验。

## S3 分支工程通过与源码提交

root的Audit-Branch-Tests独立核对实际Task行、command/exit、新鲜XML时间戳与冻结source/test/patch/runner摘要：007六组84/84；008 clean test314套/4000总数，3997通过、0失败/错误、3live跳过；009实际root :buildSrc:test强制执行8/8；010实际jpackageImage强制构建成功。跳过是Redis standalone和Oracle/PostgreSQL SchemaDiff，未开启外部授权环境，不计通过。

源码提交125f954bd81c06b9806581d7355cbee8f2d70666（仅两文件）。branch-image-audit检查实际模块类/镜像文件/cfg，无测试类、探针、profile/fixture或JVM测试选项泄漏；外置driverFor探针只发现Oracle/PG驱动，connectCalls=0。runtime modules SHA ACD69E25AFA105B3898CCCFFAEADD02568EC9486F21E0C339D72089C05D33B02，字节102398287；完整三个产物身份见audit.json。此为工程/合成FX与镜像隔离，旧原生记录不自动绑定新runtime。

当前源审核与分支验证完成，原始失败和诊断均保留。下一步精确冻结、Git字节审计、证据提交与main合并，再新跑完整矩阵；尚无本轮main/CI完成声明。

## S3 main集成与定向复验

证据提交06fbab9a7f2bed83f6af8b0bec578fa47e0e5bf4，冻结370份worker原件/18份协调原件并校验全部Git实际blob字节。main基线f5b2b7d及授权范围干净复核后，no-ff合并为f5bc602354db94cdd5dc845f4ccf61175e853360。合并后370+18原件字节复核、受验源码树与125f954一致。

main-directed强制新执行六组84/84、0失败/错误/跳过，未沿用分支报告；原命令/日志/新XML/summary保存。接下来新main-full、buildSrc、image及镜像审计、最终推送/精确SHA CI，当前不提前宣称这些待办完成。原生桌面/OS缩放与完整M8继续待验。

## S4 main完整复验与本地交付

main f5bc602354db94cdd5dc845f4ccf61175e853360新执行main-directed84/84、main-full4000总数/3997通过/3明确live跳过、main-buildsrc实际root :buildSrc:test8/8、main-image实际jpackageImage，全部exit0。各自独占合成profile、实际日志和本次XML/summary保存；未借用分支或UP-TO-DATE作为新执行。Redis standalone及Oracle/PG SchemaDiff live仍未验。

main-image-audit检查实际183个镜像文件、模块类和cfg，无测试类/探针/profile/fixture/测试JVM选项泄漏，外置Oracle/PG驱动发现connectCalls=0。实际exe、cfg、runtime/modules三项SHA/字节与分支完全相同，runtime SHA ACD69E25AFA105B3898CCCFFAEADD02568EC9486F21E0C339D72089C05D33B02。main产品树与125f954一致，370份worker原件的原字节和Git再次核对通过。完整结果见results.json。

本限定增量本地交付完成：收藏480×480/640×480双主题窗口取消/说明裁切红灯已修复，所有编辑/操作/状态可滚动访问；焦点变化/resize/同焦点Ctrl+F可显露控件，手动滚动和长SQL内部滚动/caret保持。23项新增合成FX回归包括实际操作计数和旧写后刷新状态；旧29项收藏测试本轮重验。布局变化、存储/确认与关闭所有权分开，未扩展其他产品功能。

首两次编译失误、005误过滤/错误换行及事件靶点、006无滚动范围时的normalized值断言、root哈希字段误读均保留/说明，不假归因于产品，也不以失败跳过取代通过。源代码红灯有真实几何证据；原生键盘/鼠标、OS缩放/多屏、完整在途/FAILED_PARTIAL恢复、无Gate正式启动/闪屏、安装升级回退与生产签名仍待验。旧原生/Oracle记录绑定旧镜像，完整M8不称完成。

收尾只按既有授权推送最终main，用精确最终SHA核对Verify四任务和远端refs。版本化Push-Verify-Main.ps1把实际推送/CI/远端指向回执保存在独占build/owned-ci-UUID，最终交付消息提供run链接与SHA；本提交不预报尚未发生的CI结果。v3.2.9继续保持0f6ba02656fcf3752b180514c72e79151a3320df，datacube保持PAUSED；不自动启动下一轮。

## S5 精确提交 CI 首次失败与单次复验

最终本地证据提交 bdf274cac590f27105f02e20835da1fe8bf49848 已推送 main。Verify 37593098366 attempt 1 的 wrapper、Ubuntu、Redis 成功；Windows 4000 项中 1 个旧 SQL 概览排序测试在 FutureTask.get 的 5 秒等待超时，3 live 跳过，linked image 因前步失败跳过。不能把本次远端矩阵算通过。原始失败日志/API 与后续原件独立存入 favorites-compact-ci-20261007，旧 733 份工程原件不覆写。

root 与 GPT-6.1-sol 独立核对旧用例/fixture/helper、排序调用链及收藏 close/reveal 生命周期，未找到跨 Scene 持续布局或确定产品缺陷。日志缺少 FX 线程栈，无法区分未开始任务与执行中耗时；本轮本地同用例 0.051/0.050 秒通过不证明远端根因已解决。只对同 SHA 的失败 Windows job 发起一次 attempt 2，不增加 timeout、不删断言/过滤用例。若重复则先获取线程栈/任务执行观测，不反复重跑。完整诊断见新增 evidence/diagnosis.md。

本次跟进复用原 worktree 的 codex/favorites-compact-ci-20261007，仅记录远端 CI，不改生产/测试/构建/工作流，产品树仍与源码 125f954 完全相同。无理由重复本地工程矩阵并称新代码验证；受验身份明确沿用本轮新执行数据。当前等待 attempt 2 真实结果，再更新交接、提交、合并与精确最终 SHA CI。原生/OS 缩放/真库/安装签名及完整 M8 继续未验。

## S6 同 SHA 单次重跑通过，根因未知保留

Verify 37593098366 attempt 2 在 bdf274cac590f27105f02e20835da1fe8bf49848 完成成功，Windows job 112704916891 的 Unit tests 与 Windows linked image 都实际执行成功，原始日志包含 :buildSrc:test、:test、:jlink 和两次 BUILD SUCCESSFUL；API 四任务结论均为 success。首次失败与单次请求退出码、第二次 API/日志/远端 refs 分目录归档，不覆写先前证据。远端 main 指向 bdf274c，v3.2.9 peeled 仍为 0f6ba02656fcf3752b180514c72e79151a3320df。

结论仅为“同一源码单次重跑未复现”，不是已找到并修复超时原因。未增加超时或弱化断言；精确原因缺少 FX 线程栈，仍为测试可靠性待验项，如再次发生先采集栈与执行阶段。由于本跟进仅新增文档/CI 原件，产品与测试树未变，不重复本地全量/image并冒充新实现证据。本轮原有新定向84、全量3997通过/3live跳过、buildSrc8、jpackageImage与镜像审计结果继续绑定原受验源码125f954。

下一步仅提交并合并本次证据，推送最终 main；最终提交的全新 Verify 状态及远端指向由版本化 Push-Verify-Main.ps1 写入独占 build/owned-ci-UUID 回执，最终消息提供精确 SHA/run。当前记录不预报该未来结果。原生输入、OS缩放/多屏、真库专项、安装升级回退、生产签名及完整M8仍未验；旧tag与PAUSED不变，不自动扩展下一轮。
