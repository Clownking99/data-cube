S0 本轮目标：XLSX 表头与文本单元格的 OOXML 文本保真。HEAD 365dba304a5e198e72ae7633d88a1b5258d058fa，分支 codex/xlsx-text-fidelity-20261008。未改产品/现有测试，未提交、未执行 Gradle/全量/native/真库。所有诊断仅用合成 snapshot、SafeResultFilePublisher 与独占 UUID profile/temp，实际 8.3 alias 记录在 command.json。没有读取凭据、连接配置、SQL 历史或业务文件。

S1 失败复现：002-baseline-source-diagnosis。Run-Diagnosis.ps1 将所需源码包复制到本轮独占临时 source mirror（不带 module-info.java），javac 从这些真实源码编译 QueryResultFileWriter 与 SafeResultFilePublisher 的依赖，不使用旧 build class。compileExit=0；真实查询 XLSX 导出调用安全 publisher，10 个合成样本全都 published=true；安全 XML reader 读取 ZIP worksheet 后按单次 ST_Xstring 映射回读，9 个失败，diagnosis runExit=1。

- nul / c0：表头 A1 与文本 A2 的 NUL、0001、000B、001F 被删除；XML 可解析，原值不等。
- cr：原 CR 与 CRLF 被标准 XML reader 归一化为 LF；原值不等。
- fffe / ffff：文件已发布，但 XML 1.0 reader 拒绝 worksheet。
- literal：_x0000_、_x000D_、_x005F_x0000_、小写 hex 的原字面量被单次 Xstring 解码改变。共享下划线与相邻片段包含在源样本中。
- near-literal：不匹配片段保持原样，但末尾真实 _x005F_ 解码改变，仍失败。
- high-surrogate / low-surrogate：UTF-8 OutputStreamWriter 将孤立 D800 / DC00 替换为 003F，publisher 正常发布。
- ordinary：中文、实体、tab、LF、C1、D7FF/E000/FFFD、合法代理项对 U+10000/emoji/U+10FFFF 精确回读，原始 t 文本映射也匹配。

机器清单为 evidence/xlsx-text-fidelity-20261008-worker/002-baseline-source-diagnosis/cases.json；每项包含 id、inputUtf16Hex、expectedRawUtf16Hex、cells A1/A2、styled=true、相对 XLSX 路径、expectReject 与 published。每项 XLSX 原 ZIP、sheet XML 字节、诊断 stdout、command/exit、源码与 SHA256 均保留。002 的 source-at-diagnosis 固定本次产品/诊断源。000/001 两次诊断工具编译失败（直接 sourcepath 引入 module-info；第一次修脚本仍未替换到 sourcepath）的原日志、command/exit、源与 SHA 保留；不能当作产品失败或通过。没有覆盖原失败文件。

S2 建议契约，待 root 审核后实施：合法 UTF-16 文本按一次 OOXML Xstring 解码与原 Java String 相等。C0 中除 tab/LF 外的所有字符（含 CR）、FFFE/FFFF 编码为 _xHHHH_；CR 必须编码；tab/LF 作为 t 元素内容保留原字符。XML 实体照常逃逸。原始文本每个下划线位置前瞻精确 _x[0-9A-Fa-f]{4}_，仅把这个首下划线写为 _x005F_，继续逐原字符前进，以覆盖共享下划线与相邻 escape，不扫描生成结果、不使用不重叠的整串替换。合法代理项对保留原字符。

孤立高/低 UTF-16 代理项明确拒绝是本轮建议的产品契约，不能宣称 ST_Xstring 规范明确要求拒绝。Unicode UTF-16 中这些 code unit 只有配对后才表示补充字符；现在写器静默用问号替代已获实证。建议 XlsxWriter 增加无值/列名/路径/cause 的固定 typed IOException 子类，直接抛出；RowSink.row 已支持 throws Exception，去掉行回调对 IOException 的 RuntimeException 包装是足够的最小传播修正。安全 publisher 可沿用现有 WRITE 固定消息与清理/未发布保证；无需新增 stage 或 UI 文案，避免扩大三文件状态/界面契约。若 root 要求 XLSX 专属提示，需明确审核后再增加 enum/UI 路径。

权威依据：Microsoft [MS-OE376] ST_Xstring 规定 CR 转义与字面 _xHHHH_ 首下划线保护，并限制 _x005f_ 仅用于该保护：https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/bd0aa042-434a-4ca7-b25f-4e1fd25a954d 。较新 [MS-OI29500] 同段给出一致规则，且元素内容不能把 tab/LF 转为 Xstring：https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/d34ae755-c53f-4a44-a363-c6dd3ee018a4 。Unicode 关于 UTF-16 成对语义：https://www.unicode.org/faq/utf_bom.html 。这些为本轮实时读取的官方参考；没有将上一轮 XML 拒绝 C0/FFFE/FFFF 的策略搬到 XLSX。

S3 调用影响：QueryResultFileWriter 为查询导出估宽后调用带 layout 的 XlsxWriter；TableExporter 调用不带 layout 的 XlsxWriter。两者共享 writeInlineString→当前 xml 方法，因此只在这个共同序列化入口修复文本。布局、采样、数值、布尔、null、sheet 名、分页和 row order 不需产品改动。TableExporter 当前直接写目标，不通过安全 publisher，本轮不能承诺其失败保留原目标，也不重建其事务。

S4 建议首红与定向测试：新增 XlsxTextFidelityTest，用安全 XML 解析 ZIP 内真实 t 文本并独立一次 Xstring decoder；已知编码片段硬编码 expectedRaw，不让 decoder 掩盖错误映射。覆盖所有 32 个 C0（tab/LF 特例）、FFFE/FFFF、CR/CRLF、XML 实体/空白、全部有效 UTF16边界、前后普通字符、相邻/shared underscore、大小写hex与近似序列；同一个样本在 styled/plain 两入口的 header/cell 精确回读。孤立项覆盖头/行、始中末、高高/低高/低低，直接typed拒绝及查询publisher已有/新目标清理、published=false、固定安全消息。真实 QueryResultFileWriter+publisher验证成功发布；TableExporter 用 mock ConnectionManager/provider/DataAccessor 分页证明无 layout 路径的共享文本修复、offset/500分页、列/行顺序与数字/布尔/null无变化。原布局/查询/估宽/安全publisher/export/coordinator tests 定向回归，不改上轮 CI 助手测试、不放宽限时。

S5 当前验证边界：只有真实源码合成诊断，没有新 JUnit 首红、产品修复、无样式合成导出、Gradle 定向/全量、Excel/LibreOffice native 回读或镜像。root 将对002 ZIP原件独立用 Python zipfile、安全XML与 openpyxl.utils.escape.unescape 审计；worker 等root范围审核才进入首红与最小实现。数字精度、最大长度、行列上限等不在本轮。唯一Gradle许可仍由worker持有，但没有使用。

S6 root 已对002原 ZIP 独立 Python XML + openpyxl.utils.escape.unescape 审计：10个样本1通过/9失败与worker一致。root审核授权首红→单产品XlsxWriter修复→定向；接受S2契约、固定typed IOException与SafePublisher现有WRITE提示。root将S4 TableExporter计划收窄为直接plain writer文本回归与源码稳定检查，不引入完整Table大mock；未实现/不声称其事务保护。

S7 首红003-xlsx-text-red：clean test，仅新XlsxTextFidelityTest、SqlResultXlsxExportFailureTest；真实:test，exit1，BUILD FAILED 22s，2套142项：9通过/133failure/0error/0skip。XLSX141项132失败，coordinator1项失败（实际返回“已导出”，而非失败提示）。所有红日志/command/exit/XML、source-at-failure及SHA保留，产品首红前仍是原XlsxWriter。

S8 修复仅XlsxWriter一产品文件：当前diff47新增/16删除；编码所有XML禁止的C0和CR/FFFE/FFFF、原始下划线逐位置ASCII前瞻、pair原字符保留、孤立项无值typed IOException拒绝。行回调直接传播IOException。没有修改SafePublisher、QueryResultFileWriter、TableExporter、XlsxTestDocuments、XML exporter、operation/session、coordinator、layout、采样或分页产品。数值与长度范围没有扩大。

S9 阶段绿004-xlsx-text-targeted：真实:test，exit0，BUILD SUCCESSFUL 11s，原XML准确12套277/277，0failure/error/skip。之前即时消息误报13套，此处按原XML纠正；004为阶段绿，不冒充最终完整选择。

S10 最终定向005-xlsx-text-final-targeted：沿用上轮export.*、snapshot、valuepolicy、coordinator、options、XML失败选择，另加本轮XLSX失败suite；final-targeted-tasks.json为精确Tasks。Run-Main.ps1 -Name 005-xlsx-text-final-targeted -Tasks (final-targeted-tasks.json)，命令argv/隔离profile/8.3 alias/HEAD记录005/command.json。真实:test，exit0，BUILD SUCCESSFUL 10s，原XML15套286/286，0failure/error/skip；新测试141+1项全部通过。005/source-at-test保留运行时三源；之后仅删除三个文件EOF多余空白行，没有任何行为/断言修改。最终三源字节在final-freeze-005，对应SHA：

- src/com/datacube/export/XlsxWriter.java：5BBB089F4BC2BF1A01A1D74E9BFD41ECB1207C66A6CBF65A963E9FB34C13FB00。
- test/com/datacube/export/XlsxTextFidelityTest.java：ECC6DEF79C67D0C64C687058FBEB593D4B8BD236E012243033B9FF807657CA29。
- test/com/datacube/fx/SqlResultXlsxExportFailureTest.java：D85187D4ED3F46560CD79BDBA752BB105D9DF1D55462849B4AAE5CB6BE86EB84。

final-freeze-005/shared-callers-unchanged.json独立对比002归档源SHA，四调用/reader文件均unchanged=true。git diff --check XlsxWriter clean（仅自动换行提示），status显式排除.testagent，本轮仅产品一文件、新测试两文件和限定worker证据/报告未提交。所有执行session已退出，没有全量/buildSrc强制/image/真实库/native。唯一Gradle许可等待root下一范围审核；当前不提交、不扩测、不改其它源。

| Requirement | Evidence |
| --- | --- |
| “所有禁C0、代理项/边界、header/cell、styled/plain、shared/adjacent literal” | XlsxTextFidelityTest.headersAndCellsUseKnownXstringMappingAndRoundTrip；unpairedUtf16FailsWithTypedFixedReasonInHeadersAndCells；readerUsesOnePassForProtectedSharedAndAdjacentEscapes。原t硬编码mapping与独立一次decode分别断言，header/cell同次写出。 |
| “真实publisher保留旧字节/无关文件/owned temp cleanup/重试” | XlsxTextFidelityTest.realQueryPublisherPreservesOriginalAndUnrelatedBytesCleansAndRetries，12状态组合；assertArrayEquals原/邻居字节、精确directory集合、published false、固定stage/message/cause，以及同captured目标成功重试。 |
| “实际QueryResultFileWriter+coordinator无成功提示的集成” | SqlResultXlsxExportFailureTest.unpairedUtf16ReportsSafeWriteFailureWithoutSuccessAndCanRetry，真实writer+publisher、固定UI消息、events/errors精确顺序、失败无已导出/secret、旧目标/清理和真实retry ZIP回读。 |
| “直接plain writer文本回归与源码稳定检查即可” | headersAndCellsUseKnownXstringMappingAndRoundTrip的plain参数；XlsxWriterLayoutTest.layoutDoesNotChangeCellValuesTypesOrOrder验证数字/布尔/null/字符串/顺序；shared-callers-unchanged.json中的TableExporter SHA保持原字节。未执行TableExporter mock分页，也不声称全表安全保存。 |
| “保证XML/取消/发布/布局回归同轮” | 005原XML15套286/286；XmlResultExportFidelityTest85项、SafeResultFilePublisherTest9项、ResultExportSessionTest3项、XlsxWriterLayoutTest4项、QueryXlsxExportTest5项、Coordinator6项、Options3项以及snapshot/valuepolicy各3项。最终命令/exit在005/command.json、exit.json。 |

S11 nul别名：原诊断nul.xlsx已通过Java NIO复制为case-nul.xlsx，2407bytes，两者SHA=9CADFBE36703B7D836EF3A4E4F0948DC204503A31CE0E4DE2864E91134BA1A10；002/audit-aliases.json保存原名→审计名/长度/SHA，alias log/command-exit与Java源保留。原cases没有改写。root要求未来Git冻结只纳入case-nul.xlsx+mapping，原nul.xlsx保留物理证据不进入Git；当前worker尚未归档或提交。

当前仍未验：原生Excel/LibreOffice/桌面、真实PG/Oracle/Redis、安装更新/签名、慢磁盘/网络盘、main侧新验证。root的linked runtime独立探针待后续镜像阶段使用。既有live skip不等于通过。本阶段定向不新增.skip或松限时，旧CI/helper原件不改。

S12 root批准精确暂存三文件，cached diff --check clean；源码提交ce2a096a044db6a731774b13a8ae61ea44687953（fix(export): preserve XLSX text through OOXML encoding），仅1产品+2新测试，296新增/16删除。证据/worker报告未纳入源码提交。root批准顺序全量→buildSrc→Image→本轮独立镜像审计。

S13 全量006-worker-full：提交ce2a096a，clean test真实:test，exit0，BUILD SUCCESSFUL 5m45s；原新XML325套4306总项，4303通过、0failure/error、3live skip。exact skip original理由含原栈保存006/skipped-original-reasons.json：RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()缺DATACUBE_REDIS_HOST/PASSWORD；SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas()与postgresqlSafeDeploymentConvergesInDisposableSchemas()缺explicit write gate及完整provider环境。没有将live skip当作通过或访问真库。

S14 buildSrc007-worker-buildsrc：实际:buildSrc:test --rerun-tasks，exit0，BUILD SUCCESSFUL8s，4任务全部实际执行；新XML1套8/8通过、0failure/error/skip。

S15 镜像008-worker-image：jpackageImage --rerun-tasks与Image隔离模式，exit0，BUILD SUCCESSFUL47s，14任务全部实际执行，含jlink/jpackageImage。JAVA_TOOL_OPTIONS为null，仅Gradle build home隔离；不是UP-TO-DATE镜像。新command/exit/log完整保存。

S16 镜像审计第一次调用错误：009a-audit-invocation-failure。外层PowerShell把named flags放进array splat导致参数绑定失败，Audit-Main-Image脚本根本没有执行；外层未设Stop又读取空LASTEXITCODE，最终工具exit0，不代表审计通过。原命令、工具输出转录（明确非脚本stdout原日志）与failure.json保留，不改根脚本。随后新增Run-Image-Audit.ps1外层，用明确named参数、Stop/catch、命令/真实exit记录。

S17 实际009-worker-image-audit：本轮root四脚本逐字节复制，SHA匹配记录runtime-probe-copy-provenance.json，未用旧镜像probe代替新增XLSX probe。Audit-Main-Image.ps1 -ReviewedCommit ce2a096a044db6a731774b13a8ae61ea44687953 -Name 009-worker-image-audit，wrapper真实exit0，audit.passed=true。linked runtime XML标准回读7/7与固定typed非法字符拒绝；XLSX20个有效styled/plain包、A1/A2共40文本单元格由独立stdlib XML（拒绝DTD/实体）+已捆绑openpyxl 3.1.5单次unescape精确回读；另6个头/行孤立UTF16样本拒绝。XLSX_RUNTIME_PACKAGES=20; INVALID_UTF16_REJECTED=6；XML_RUNTIME_ROUND_TRIPS=7; INVALID_CHARACTER_REJECTED=true。

镜像183文件，class/file/option泄漏为空/false，cfg没有profile/tmp/headless/synthetic flags。artifact SHA与本次重新读取匹配：DataCube.exe 595968bytes、app/DataCube.cfg369bytes、runtime/lib/modules102433583bytes；详情009/audit.json与final-stability-after-009.json。Oracle/Postgres检查仅ServiceLoader/driverFor发现类，runtime探针源码没有connect/open，stdout declared connectCalls=0不是实际网络连接计数，不证明真实DB可连或读写。没有原生Excel/LibreOffice验收（independent audit明确nativeExcelAcceptance=false）。6非法包是直接writer失败时的局部证据，本轮没有为TableExporter增加SafePublisher保护。

S18 最终稳定检查：三源码SHA全部仍与final-freeze-005一致；四root探针副本SHA仍与来源一致；三image artifact SHA与009审计一致，final-stability-after-009.passed=true。HEAD仍ce2a096a；tracked src/test/resources/buildSrc/build.gradle/CI相对HEAD diff clean；所有命令session退出。003首红、000/001诊断编译失败、009a调用错误及所有原件均保留，不用阶段绿替代最终绿。

本轮worker工作到此完成。暂停所有写操作并交回唯一Gradle许可，由root归档审核、main集成与main新验收；worker不自行提交证据/报告、不清理旧目录、不再运行其它测试。原nul.xlsx原物理证据保留，未来Git只纳case-nul.xlsx与映射；原cases不改。安全及未验边界沿用S11，真实连接/native桌面/安装更新/签名仍未验。
