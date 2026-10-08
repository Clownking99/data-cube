# XML 导出忠实性 worker 验证

S0：基线2b2e004b；只用合成文本和独占TempDir。初始关闭锁猜想撤回，真实资源关闭在后台且标签先禁用。XML诊断8场景：NUL丢值；FFFE/FFFF及U+00AA列名发布成功但解析失败；CR及属性空白回读改变；合法补充字符保留；孤立代理项原UTF8层已WRITE拒绝并保留旧文件。诊断原件见initial-jdk-diagnosis。

S1：新增两个真实writer/publisher/标准解析器与coordinator回归类，尚未运行；生产及旧契约不改。下一步001首红，保留原源/命令/log/XML。

S1首红：001-xml-fidelity-red实际:test，38项，30失败，0error/skip，exit1。源/新旧test原字节已存before-001-source与001/source-at-failure。NUL/control/FFFE/FFFF错误成功发布、XML名解析/CR回读失败及UI假成功；孤立代理项原实现已安全WRITE失败，红灯仅固定XML原因分类缺失，不当旧目标已被修改。首次JDK诊断仅tool stdout，未落盘原log；归档Java源码/合成XML不补造stdout。PowerShell复制nul.xml遇保留设备名称，仅这一文件未复制，原独占temp保留；不归档class。下一步最小XML修复并更新历史静默删除契约。

S2：仅XML helper按code point验证合法字符/代理对，CR及属性空白数字引用，合法名范围净化并name回读保留；typed固定异常→XML_CHARACTER安全Stage→coordinator固定原因。历史静默删除契约明确改为拒绝。新增合法7F/85边界与全部29个非法控制字符（值/列名）；root时序复审将first.isDone改为first.get有界结算，不改变首红期望。nul.xml原字节现已ReadAllBytes归档为case-nul.xml，SHA256 3A769913F000ED8AA3001C8314BE73A4C82958AF817C713C32C739BF02EAF581；此前复制失败不丢证据。

S2定向002：140项138通过2失败0error/skip，exit1，原XML/log/源码在002-xml-fidelity-targeted。新增literal emoji标签不兼容标准解析器且改变旧标签映射（root已要求保留原净化策略），另一项retry成功提示测试忽略8.3→规范parent转换，属夹具路径别名错误。未称绿灯，先保留原件再按审查修正。

S3最终定向：003-final-targeted，实际:test，BUILD SUCCESSFUL 11s，exit0。13套140项140通过、0失败/错误/跳过。session94446已退出，唯一Gradle许可交回root，不启动full/buildSrc/image。

选择：test --tests com.datacube.export.* --tests com.datacube.sqleditor.result.ResultExportSnapshotTest --tests com.datacube.sqleditor.result.ResultExportValuePolicyTest --tests com.datacube.fx.SqlResultExportCoordinatorTest --tests com.datacube.fx.ResultExportOptionsDialogTest --tests com.datacube.fx.SqlResultXmlExportFailureTest。offline/新UUID合成profile/实际8.3 tmp/no live环境。UP-TO-DATE工具任务不计测试通过。

| Suite | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| com.datacube.export.QueryResultFileWriterTest | 7 | 0 | 0 | 0 |
| com.datacube.export.QueryXlsxExportTest | 5 | 0 | 0 | 0 |
| com.datacube.export.QueryXlsxLayoutEstimatorTest | 6 | 0 | 0 | 0 |
| com.datacube.export.ResultExporterTest | 9 | 0 | 0 | 0 |
| com.datacube.export.ResultExportSessionTest | 3 | 0 | 0 | 0 |
| com.datacube.export.SafeResultFilePublisherTest | 9 | 0 | 0 | 0 |
| com.datacube.export.XlsxWriterLayoutTest | 4 | 0 | 0 | 0 |
| com.datacube.export.XmlResultExportFidelityTest | 81 | 0 | 0 | 0 |
| com.datacube.fx.ResultExportOptionsDialogTest | 3 | 0 | 0 | 0 |
| com.datacube.fx.SqlResultExportCoordinatorTest | 6 | 0 | 0 | 0 |
| com.datacube.fx.SqlResultXmlExportFailureTest | 1 | 0 | 0 | 0 |
| com.datacube.sqleditor.result.ResultExportSnapshotTest | 3 | 0 | 0 | 0 |
| com.datacube.sqleditor.result.ResultExportValuePolicyTest | 3 | 0 | 0 | 0 |

新81项writer/publisher回归覆盖全部非法控制字符及FFFE/FFFF/4类孤立代理项（值/列名）、7F/85/D7FF/E000/FFFD/10000/10FFFF、CR/CRLF和属性空白、兼容标签及NULL；UI1项证明固定XML原因、无成功/原值泄漏、旧目标及owned-temp保护、随后成功重试。旧安全发布取消/目标变化/并发/失败清理和其它格式实际同轮通过。

Root复审修正：旧char级净化与XML合法Name范围交集，emoji→__、combining/middot→a__b保持旧标签；U+00AA→_且name精确。003前仅EOF规范化；修正UI规范路径比较，原002不覆盖。

最终六src/test及runner原字节冻结见final-freeze-003/sha256.json和complete-source.patch；与before-003-source一致。Operation/Session/Capture与public timeout未改。首红001、002失败、003通过的command/exit/log/XML和各阶段源原件全部保留。初诊nul.xml归档别名case-nul.xml原字节SHA已记录；首次stdout仅tool输出不冒充raw日志。

待验：full/buildSrc/image尚待root审查授权；原生桌面/键盘/OS缩放、真实PG/Oracle、真实慢/网络磁盘、安装升级/回退/签名及完整M8未验。仅本轮XML增量，不扩同步capture/关闭功能。无commit/merge/push/tag/网络/真库/原profile/剪贴板/原生输入；.testagent未读改枚举。下一步root独立复审；worker源码冻结且Gradle已交回。

S4 root追加标准reader边界发现：003的140项不覆盖U+037F/U+08A0列名；当前字符范围合法但JDK parser不接受此literal标签。新增两label回读回归，003源与原件保留，生产不改先004首红。

S4首红004：83项81通过2失败0error/skip，exit1，实际:test，7s。U+037F/U+08A0均标准parser拒绝，保留004原字节源/XML。最小兼容修复每次writeXml建立一个JDK公开空DOM（newDefaultInstance/newDocument，无parse/网络/自定义provider），先校验完整旧tag；仅拒绝时逐char替换，保留原name属性。增加中文/U+09FE literal原tag断言，不一刀切非ASCII；原Char验证在fallback之前拒绝不可表示的name。下一步005完整144项定向。

S5定向005：13套144项143通过1失败0error/skip，exit1。唯一失败为新增9FE测试错误要求literal标签，旧Character净化策略实际为_；原name属性完整且标准回读通过，root确认仅修旧mapping期望。其余，包括037F/08A0及中文literal标签，均通过。保留005原源/XML，生产不改；006相同完整定向。

S6最新最终定向006：13套144项144通过、0failure/error/skip，exit0，实际:test，BUILD SUCCESSFUL 9s；session93006已退出。选择与003/final-targeted-tasks.json一致，新增2个reader边界及2个旧标签兼容证明；XML suite现85项。源码/六src-test与runner最终冻结以final-freeze-006/sha256.json为准，旧final-freeze-003保留。完整patch SHA256 5B610071CDFF7482356A5A6FECADFD991ED0131873C55CD583F139FACD4A4AC7。003140不是新边界的通过证据，004/005失败原件及源不覆盖。

当前生产除S2三文件改动外，仅Exporter XML名增加一次空DOM完整tag兼容校验，拒绝后最少逐char替换；原字符拒绝及name属性仍完整保留，中文literal不变，9FE为旧_映射。未新增模块/依赖/internal API/解析或外部I/O，不修改发布取消状态机。

本阶段Gradle已停止并交回root，源/test/runner字节保持冻结；full/buildSrc/image仍未授权执行。待验和安全边界与S3相同。报告尚供root审查，worker不提交或启动其它功能。

S7全量007-worker-full：审查提交c553e4c5eadb0ca2d73fc86ee5e4aa6bb49b4d6e，本次clean test实际:test，BUILD SUCCESSFUL 5m31s/exit0。新XML323套4164总项，4161通过、0failure/error、3live skip；精确原原因在007/xml-summary.json与原XML。

- RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle：未设置DATACUBE_REDIS_HOST与DATACUBE_REDIS_PASSWORD，未访问Redis。
- SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas：没有显式write gate及完整provider环境，未访问Oracle。
- SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas：同一显式write gate及完整provider环境缺失，未访问PostgreSQL。

S8 buildSrc 008-worker-buildsrc：root :buildSrc:test --rerun-tasks，实际task，新XML1套8/8、0fail/error/skip，BUILD SUCCESSFUL 8s/exit0；4任务实际执行。

S9镜像009-worker-image：jpackageImage --rerun-tasks -Image，独占新build profile无test JVM flags，BUILD SUCCESSFUL 46s/exit0；14任务全部执行，含jlink/jpackageImage。未将UP-TO-DATE当作通过。

S10 linked镜像010-worker-image-audit：复制root Audit-Main-Image.ps1与XmlRuntimeExportProbe.java原字节运行，ReviewedCommit=c553e4c5eadb0ca2d73fc86ee5e4aa6bb49b4d6e，exit0/passed=true。7种合成列名（中文/AA/037F/08A0/属性空白/emoji）、CRLF/emoji/转义精确回读，非法NUL固定类型拒绝；XML_RUNTIME_ROUND_TRIPS=7; INVALID_CHARACTER_REJECTED=true。oracle/postgres仅ServiceLoader驱动发现，connectCalls=0，无凭据或原profile读取，无class/file/option泄漏；实际镜像183文件。

三产物字节/SHA记录010/audit.json并本次再计算匹配；六源码/test+runner的7个SHA全部仍与final-freeze-006一致。本轮未改Operation/Session/capture/timeout；root已提交源码，worker未做Git写操作。

全部执行session已退出，唯一Gradle许可交回root；不再执行任务或修改任何文件。原件位置：001产品首红、002排版兼容/规范路径失败、003阶段绿、004reader边界首红、005错误9FE期望失败、006最终144绿、007全量、008buildSrc、009image、010image-audit；各command/exit/log/XML/source-at-failure或before-source/final-freeze原件均保留，详细顺序见各S阶段。最初诊断stdout仅tool结果，归档源码和合成XML（nul.xml别名case-nul.xml）；没有补造stdout或归档编译class。完整patch最终使用final-freeze-006，其SHA已记录。raw归档/报告字节冻结/Git集成由root负责，本worker不另造raw-manifest。

剩余未验：原生桌面/键盘/OS缩放、真实PG/Oracle/Redis、真实慢/网络磁盘、安装更新/回退/签名/完整M8以及main侧新验证/CI由root后续负责。自动化只使用mock/合成独占profile/temp或内存XML，标准解析禁DTD/外部实体。不会自动扩大功能；v3.2.9与PAUSED自动跟进未操作。
