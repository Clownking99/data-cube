# Redis 二进制键修复：开发检查点

2026-10-10，分支`codex/redis-binary-key-20261010`，基线`a39ffc478795801195f9ee41087f2e0af02a149f`。工作区起始范围内干净。采用已指定code-testing-agent focused流程；无代理、无中间状态目录，不触碰.testagent或旧.g10脚本。第一阶段仅源码与静态审查，无Gradle/控制执行、无提交/tag/网络/真实服务访问，未操作系统剪贴板。

## C1：身份与命令贯通

目标：混合SCAN页正常列出；字节内容才是身份，所有命令命中原键。新增RedisKey，输入/输出byte[]防御复制，内容equals/hashCode；UTF-8文本解析严格，不使用替换字符。公开工厂在默认请求payload预算前拒绝再编码/复制；Snapshot应用更小的单键、累计原始字节/数量预算，RespCodec在整条命令payload/frame预算通过后才导出命令byte[]。

改动：RedisKeySnapshot的Map变为RedisKey身份，防御复制外部Map；KeyTreeBuilder.Node携带RedisKey，旧fullKey仅为文本兼容投影，pane不再使用它。Binding/TreeModel/TreeEntry/desired/displayed key统一RedisKey。RedisSession新增27个typed key重载，原String门面保留原call/preflight行为，避免先编码再拒绝。RespCodec显式识别RedisKey，长度预检取size，实际命令取原bytes。贯通TYPE/TTL/EXPIRE/PERSIST/DEL/RENAME/EXISTS、GET/STRLEN/GETRANGE/SET、hash/list/set/zset全部调用；成员/field/value原始身份逻辑不改。

分组/显示决定：可打印合法UTF-8仍按原分隔符和原文本排序、保留键同时为folder prefix的行为。非法UTF-8、ISO控制、format/bidi及U+2028/U+2029整体作为平面raw叶，不把其中冒号当分隔符；空键作为独立`⟦empty⟧`叶。raw叶为有界`⟦hex#序号 字节数B⟧ 十六进制`，序号取去重后的稳定插入位置；文本segment以`⟦`开头则显示`⟦text⟧`前缀，避免与hex/empty标记混淆。长标签允许截断，极小cap可能同标签；树节点Map使用未截断文本segment或独立raw叶，节点携带完整RedisKey，显示碰撞不会合并、选择或写错键。全键header也有明确hex/text/empty标记，均不用于命令。文本复制是完整原文，raw复制是完整`hex:`内容（超复制cap拒绝，不复制截断预览）；菜单明确“复制键（十六进制）”。测试只检查clipboardText纯投影，不读写维护者系统剪贴板。

预算/生命周期：旧文本树入口惰性逐项转换，超keys/keyBytes立即拒绝，不先构造整批字节身份。whole-page candidate仍在安装成功后一次提交；超数量、原始字节、单键、节点、深度或安装失败保留旧snapshot/tree/cursor。session/source/db/generation/valueEpoch门禁原样，只把key比较换成内容身份；刷新、DB切换、关闭、旧回调和旧编辑仍受原绑定约束。未做工具栏或架构扩展。

实际验证：逐调用链静态审查；当前git diff --check通过（仅换行提示）。首失败/未验：本阶段未编译Java、未运行JUnit/原生FX或工程阶段，因此没有运行通过声明。下一步root源码准入后按新实际XML复核，不能硬编码旧291/4721基线。

## C2：focused回归实现（尚未运行）

| Requirement | Evidence（已实现测试名，未运行） |
|---|---|
| 输入和返回byte[]不改变身份/哈希 | RedisBinaryKeyTest.contentIdentityDefensivelyCopiesInputsAndReturnedArrays |
| 同页文本/非法UTF8/控制字符/空键/重复bytes/文本标记lookalike，保留前缀树 | RedisBinaryKeyTest.mixedPageKeepsTextBinaryControlsEmptyAndDistinctTextLookalikes |
| 同截断显示不同原字节，不合并树或文本namespace | RedisBinaryKeyTest.sameBoundedPreviewDoesNotMergeRawKeysOrTextNamespace |
| 逐页bytes去重、raw计费、exact/+1数量与节点/单键拒绝、旧cursor保留 | RedisBinaryKeyTest.pagesDeduplicateByBytesChargeRawBudgetAndRejectWholeCandidate；现有RedisDisplayBudgetTest.scanCandidatesPreserveCursorOnRejectedFinalPageDuplicatesAndEmptyPages和countIsOnlyAHintAndKeyByteTreeDepthAndNodeBoundariesAreIndependent继续保留 |
| keys/总bytes拒绝后不访问后续受控List元素 | RedisBinaryKeyTest.legacyTreeConversionStopsAtFirstCountOrByteBudgetRefusal |
| 复制完整文本/显式hex，超预算/非法Unicode/超工厂预算拒绝 | RedisBinaryKeyTest.clipboardIsCompleteLiteralTextOrExplicitHexAndFactoriesRejectBeforeEncoding |
| 27 typed键命令含RENAME双方、空键原始bulk bytes；executor改参不污染key | RedisBinaryKeySessionTest.everyTypedKeyCommandUsesOriginalBulkBytesIncludingRenameAndEmptyKey |
| 请求预算使用raw长度而非标签；String旧门面继续整条预检 | RedisBinaryKeySessionTest.wholeCommandPreflightChargesRawKeyBytesAndRetainsStringFacadeBudget |
| 原生FX选择binary键后五编辑器真实key/value/field/index/score字节；分别有效绑定在刷新/DB/关闭后拒绝 | RedisPaneBudgetTest.binaryKeySelectionAndFiveEditorsSendOriginalKeyBytesAndInvalidateOldBinding |
| binary页超数量及安装失败保留tree/cursor，恢复不重复计费 | RedisPaneBudgetTest.binaryScanOverflowAndInstallFailureKeepOriginalTreeAndCursor |
| delayed binary GET不能覆盖另一raw选择 | RedisPaneBudgetTest.delayedBinaryValueCannotOverwriteDifferentRawSelection |
| 原文本/五类型/生命周期不退化 | 现有RedisPaneBudgetTest及RedisDisplayBudgetTest：只调整typed身份断言；旧非法UTF8拒绝改为明确成功包含原bytes的断言，旧预算拒绝断言保留 |

实际验证：测试源码自审，未调用测试。首失败/未验：无运行结果，不将测试数当完成度。下一步root接收唯一Gradle执行权后先最窄Redis/原生FX，再按授权定向/clean全量/buildSrc/image/linked；准确native类为RedisPaneBudgetTest，新增case按实际XML统计。

## C3：本次有名验证范围最小补丁

目标：本次证据使用`redis-binary-p2-<32hexUUID>[-suffix]`和p3对应范围，不伪装G11；内部datacube-g11-UUID/runtime/G11_BUILD保留。四共享工具改动：Core新增一个纯词法Assert-VerificationEvidencePath，并用于New-OwnedScope、Invoke-OwnedProcess、Bind-OwnedImage；run-stage ScopePath先词法再NoReparse；run-owned同一命名regex覆盖out/stage、直属package/tools、imageSource的直属root/ownedUUID/scope.json；check-core原21/18真实synthetic根随本次matrix目录选择p2/p3命名，否则旧G11调用原样。闭包仍12，不改任何预算、skip、任务、进程/退出/Job/EOF/镜像门禁或旧证据。

新增有名路径只允许repo/docs/superpowers/verification/evidence的直属run root，owned子目录32位小写UUID；tools严格run/tools，image source严格run/UUID/scope.json。保留旧G11准入行为与禁止路径/反链接规则，不新增泛化任务namespace。

拟准入控制（尚未执行）：

1. 纯词法正例：p2/p3根、fixture/targeted/full/buildsrc/image/linked后缀、owner/package/tools、ownedUUID/scope.json；原G11调用仍接受。Python admitted_run_name与PowerShell helper对相同命名表一致。
2. 零文件访问负例：未知维护范围、缺/短/大写UUID、空suffix/超长suffix、嵌套run root、foreign repo/evidence sibling、非UUID owned、scope文件名错、tools多层或不叫tools、source-image层级错；既有禁止组件/.testagent/device/traversal均在任何metadata前拒绝。使用合成字符串和受控访问计数，不创建或检查禁止路径。
3. 新有名根下真实normal fixture命令/argv/实际退出0/强root proof/Jobempty/完整双流，collision二次启动拒绝；foreign/链接root或tools/source零不当访问。再完整重跑共享98（21+21+18+31+7），实际39 synthetic根须带本次p2/p3前缀并由saved spec归档。
4. 获准后冻结新12工具与实际controller/inputs，再运行产品工程。存档仅消费本轮actual spec/scope，不扩展旧G11 roots公式。运输时另加精确`redis-binary-p[23]-*/** -text`及本报告-text规则，原属性行不改；当前未修改属性。

实际验证：Core/run-stage PowerShell Parser各0错误，run-owned/check-core Python ast.parse通过；只是语法检查，没有执行函数、路径控制或helper。首失败/未验：所有新增准入正/负/进程语义仍待root准入；本阶段未调用共享控制或Gradle。下一步root审实际diff和本表后再执行控制及工程，首次异常留原件并停。

## C4：第一阶段交审

目标：交付可按实际字节复核的实现及最小准入diff。当前14文件：6产品（RedisKey新增，Snapshot/TreeBuilder/Session/RespCodec/Browser修改）、4测试（2新增binary focused suites及2现有budget suites）、4共享验证工具（Core/run-stage/run-owned/check-core）。README、buildSrc、Gradle构建/CI、原probe、policy/budget/skip均未修改。

源码检查点`evidence/redis-binary-p2-0f92392793de47f39dc2091e75aa5e33-source`保存含新增文件的完整source.diff、14文件长度/SHA、实际静态检查事实及Git换行提示。source.diff SHA **2bdfd4a774a8389ca63f10e71fe8e7c7e55e401539b5548c51e18ebcfda14b51**；source-identities.json SHA **4684f8d5dc8cd3c8a5ca4ae36b204a92fcc5040768ffafbda0dd14de98888ce8**。检查点完成后不再写入该根；后续若有审查修正用新检查点。

实际验证：限定路径git diff --check实际exit0；2 PowerShell Parser零错误、2 Python AST通过，未执行这些控制函数；基线/分支已核对。首失败/未验：Java编译、全部新增/原有JUnit与原生FX、准入边界控制、共享98、targeted/full/buildSrc/image/linked均未运行；没有任何新增通过数声明，也未访问截图中的环境。下一步root独立审阅该14文件身份及C1-C3矩阵，准入后才接唯一Gradle/控制执行权。当前无提交/暂存/合并/推送/tag操作，系统剪贴板未读写。

## C5：准入前词法检查顺序修正

目标：落实C3对被拒目标的metadata前拒绝。root源码审核指出两处顺序缺口，属于静态审核发现，不是运行失败。仅修正两行位置/条件：run-stage在Assert-NoReparse/Get-Content之前明确basename严格等于scope.json；New-OwnedScope在任何paths的Assert-NoReparse之前调用EvidenceRoot纯词法准入。其余产品、回归、预算、退出/Job/EOF与镜像语义保持。

零访问控制口径：合法spec和受验工具是必要读取，不能声称整个调用零文件访问；应只断言被拒EvidenceRoot/ScopePath/tools/imageSource及其祖先没有metadata/read/write探测。有效路径正例仍执行真实后续检查；负例访问计数器只观察目标，不mock吞掉后续检查，也不访问禁止组件。

实际验证与未验：两PowerShell文件再次Parser检查及限定diff --check；尚未执行任何新增边界控制、98控制、Java编译/测试或Gradle。旧C4 source根保持不变，新检查点保存当前完整14文件diff和身份。下一步root准入新身份后，才执行有名边界控制与共享98。

新检查点为`evidence/redis-binary-p2-e9d00600b31d4858a3eaac1a8c1f3162-source`；完整diff SHA **2a12a3b11620758fe744cfd0eec3d8466fe1cb0a3b2fa3e57fe9be5e91d9eec5**，14文件身份清单SHA **95a3cf729639b165aac4eb98d0ea871a52233462988d647afbffe92b7a87f073**。逐身份对比C4，仅Core/run-stage两文件变化，产品和测试字节不变。该新检查点完成后停写。

## C6：控制夹具与新增边界入口准备

root已准入C5并移交唯一控制执行权，仍不准Gradle或提交。执行前root静态发现check-p2的owned-exe-hash-change夹具把矩阵根当owned，新的严格UUID门禁会提前拒绝而不能验证执行文件哈希。按明确授权只修正夹具：创建Out/独占UUID child，fakeScope.owned、fakeJdk、fake HostScript、Cache/RuntimeParent都绑定该child；保留原EXECUTABLE_IDENTITY_CHANGED精确断言与全部31项，不放宽owned准入。闭包仍12；本次共享源码实际修改扩到5文件，产品/测试不变。check-p2 Python AST通过；尚未运行受影响31或任何其他控制。

### C7 controls frozen before invocation
- Root admitted check-p2 fixture SHA `2b6b53d3e7879763908a1f2af8c3d15da6e82ccaa749e737e9325a18b2957430`, full 98 controls, and named boundaries. No engineering run.
- Package: `redis-binary-p2-d19ccc8ab48d402985168bcd7b4e1c45-controls`.
- Entry manifest: 22 files, SHA-256 `8ec2ef9035b37340114401850a662e2dbc91dc7f84b07e582557c0e5a5fe28a2`.
- Input binding: 878 files, SHA-256 `0c66247b4844e04c7d920683c84cdc2dbeffe7f305e9cb0ceaad0912c8dd2783`.
- Tool manifest: 12 tools, SHA-256 `2eda5a3aa8b6d380bc7dbaefed7621fbb8db91db1b26e49a0198f40cdca4f457`.
- Matrix: Python 34 boundary cases, PowerShell 36 boundary cases, existing shared 98 controls, then 1 real same-root collision following successful normal. Legacy G11 p2/p3 pure lexical positives included in each language.
- Observations delegate actual NoReparse/no_links calls; stage basename combines actual command observation and source ordering. This is not system-wide filesystem I/O tracing. Two Python wrong-tool-location cases simulate only __file__ to reach the intended guard.
- All child stages receive independent private Job ownership, finite deadlines, raw stdout/stderr files, actual exits and observed empty Job settlement. First anomaly stops subsequent stages; original evidence retained.

### C8 retained first anomaly and entry-only retry
- First package d19ccc8ab48d402985168bcd7b4e1c45: Python boundary actual exit 0, PowerShell boundary actual exit 1; both independent Jobs observed empty. Shared 98 never started.
- Exact raw failure: `BOUNDARY_REFUSAL:invalid owned accepted`. Reproduction shows PowerShell array-plus precedence split `$owned+'/nested'` into valid UUID and `/nested` elements. Parenthesized expression restores intended three lexical negatives, all independently refused.
- No shared or product source changes. First frozen entry and all raw outputs retained.
- Fresh package `redis-binary-p2-a3fd7057b71e4152880a33c776c42984-controls`, 23 entry files; entry manifest SHA `bd89f987244c797877982b475077f16f8ec8a86b958c1653b691345af419f255`. Same 12-tool manifest and 878-input binding. Retry from boundaries, then all 98 from zero.
- Exact observed old array: ["not-a-uuid", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "/nested"]. Intended corrected array: ["not-a-uuid", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/nested"]. Actual pure lexical corrected refusals respectively: UNADMITTED_NAMED_SCOPE, UNADMITTED_NAMED_SCOPE, UNADMITTED_NAMED_EVIDENCE. Dynamic retry result records each intended leaf and actual refusal.

### C9 controls complete, originals sealed; engineering still pending
- Fresh successful package: `redis-binary-p2-a3fd7057b71e4152880a33c776c42984-controls`.
- Actual Python boundary 34/34, PowerShell boundary 36/36. Both include G11 p2/p3 lexical compatibility positives. 70 total named/legacy boundary assertions.
- Full shared controls from zero: Python 21, process 21, root-exit 18, policy-role-image 31, outer 7 = 98/98.
- Actual same-root collision after successful normal: real `New-OwnedScope` refused `RUN_COLLISION`; source `...-controls-process/normal-owner-spec.json`, actual completed root `redis-binary-p2-fbe12d3a29f74fd89602d5bc726631df-normal`.
- Operator stages boundary-python / boundary-powershell / controller / collision / archive all actual exit 0, no deadline failure; each independent private Job queried empty. All 39 process/root-exit neighbors survived, 144 original stream identities audited. Working/frozen 12-tool identities and 23 entry identities matched. 878 input binding unchanged before/after.
- Successful seal: `redis-binary-p2-a3fd7057b71e4152880a33c776c42984-controls-frozen/manifest.json`, SHA-256 `fad4963106f5ae58174a056ae697377ff3a26d103a7da01c22fe5a4edb1e6772`; 91 actual-spec-derived roots, 1384 files, 17025013 bytes. Actual saved specs and scope owner identities drive archive roots; runtime/home/build excluded.
- Retained failed first seal: `redis-binary-p2-d19ccc8ab48d402985168bcd7b4e1c45-controls-frozen/manifest.json`, SHA `56dba9c03f06502245e16603ab06ae85c3e99f25524408399ad11a99b2afa2e4`, 40 files. This contains only the entry construction anomaly and is not counted as a shared-control pass.
- Current 15-file source checkpoint: `redis-binary-p2-90901f0a22f846239ef767c3ace57a95-source`; identities SHA `a09940100104ea99774155652c7f3385300bc3e7102f2b54aabb439e9f874032`, diff SHA `489a0a83288dfd1ab8a15e814ddff806b1700dc698b208c119cc65689a86e73c`. Original C5 14 file identities rechecked unchanged, plus admitted check-p2 fixture fix.
- Observation scope remains dynamic NoReparse/no_links/Get-Content calls plus source ordering, not comprehensive OS I/O tracing. Two wrong-tools Python cases simulate __file__ only, explicitly recorded in raw case results.
- No Gradle, Java compilation, product JUnit, FX, real Redis/DB, clipboard, network, commit, merge or push. Await root's independent original-evidence review and engineering admission.

### C10 engineering admitted and entry frozen
- Root independently accepted exact controls manifest fad49631..., 878 current input identities and boundary/collision evidence; worker holds sole Gradle execution authority.
- New engineering package `redis-binary-p2-ccd7d3de18524a1a9a7268fa73a88196-engineering`, entry manifest SHA `4f75c1b355cce091952e3af4990b9c5cdc1545d1b4af3127b2a075071fd73ceb`.
- Reuses the same admitted 12 tools without re-running 98. Exact accepted manifest bound, all source byte identities checked before every stage.
- Sequence: targeted cleanTest/test; full clean/test; buildsrc :buildSrc:test --rerun-tasks; image jpackageImage; linked frozen synthetic probes. Existing offline cache, no daemon, independent runtime/home/temp/build, owned execution finite budgets.
- Source-derived exact expected regression XML sets: RedisBinaryKeyTest 6, RedisBinaryKeySessionTest 2, RedisPaneBudgetTest 14 (including three new binary-key native cases). Actual full/targeted totals and type inventories authoritative.
- Engineering input spec explicitly marks absent gradle.properties, preserving prior required optional-input contract; 878 path/length/hash rows are identical to accepted controls.

### C11 retained first engineering failure; identical-byte retry
- First targeted actual exit 1 / controller exit 1; full/buildsrc/image/linked never started. Outer Job observed empty, stdout/stderr EOF complete; input and tool identities unchanged.
- Raw XML: 58 suites, 303 tests, 301 passed, 1 approved live skip, 1 failure, 0 errors. Sole failure: `AppShellTableExportShutdownTest` `[1] missing`, `SqlDraftCoordinator$Failure: SQL draft runtime: BUSY` at existing Fixture.seed/saveWorkspace before export operation. Existing explicit workspace save can overlap background layout save; no binary-key source on failure stack.
- Both new suites passed all 6+2 tests; all RedisPaneBudgetTest 14 passed, including three new native binary-key cases. No clipboard action executed.
- Failed original seal `redis-binary-p2-ccd7d3de18524a1a9a7268fa73a88196-engineering-frozen/manifest.json`, SHA `48a4e66bddf34c8f1a5b21e95603adbfc592a0d987e10865476ede03ec99400d`, 3 actual roots / 214 files / 1791542 bytes.
- One fresh complete sequence retry with identical 878 input and same 12 tool bytes, no source/test/assertion correction. New package `redis-binary-p2-66b33fb0fb454405aec3cc9b459e0c87-engineering`, entry manifest `99d4183671ccb9ea281917da5ecade3efd76c2fb140684716738effafbcc3ce1`, failed manifest bound. If targeted repeats failure, stop for review.

### C12 diagnostic stop and deterministic fixture review checkpoint
- Root requires deterministic fixture repair; identical-byte green retry is diagnostic only. Current targeted finished actual exit 0 (303 tests:302 pass,1 approved live skip). To stop at an owned settled boundary without changing inputs or killing processes, an exclusive read handle temporarily held this package's entry-manifest.json. Post-targeted identity read refused with recorded PermissionError, controller actual exit1; full and later stages never started. Handle released; manifest before/after both 99d41836... unchanged. Raw intentional-boundary-stop.json records mechanism and UTC times. This is not accepted engineering.
- Diagnostic seal `redis-binary-p2-66b33fb0fb454405aec3cc9b459e0c87-engineering-frozen/manifest.json` SHA `fd7fccfd75fb428cfcd71beab6d695dd0bbb9221decc5fc3089a152e35e2c7fb`, 219 original files.
- Test-only deterministic repair: Fixture.seed now calls seedAsync; on FX owner activity/freeze/saveFrozen waits actual inFlight before submitting seed, awaits seed's actual publication future, resumes owner activity and clears observations after completion. No BUSY retry, sleep, timeout increase, product or shared safety change.
- New native regression `AppShellWorkspaceShutdownTest.fixtureSeedWaitsForActualInFlightWorkspacePublication`: actual owned workspace atomic mover blocked by latch, seed Future remains pending and no second publication occurs; releasing actual first write permits exactly two ordered publications, runtime snapshot equals seeded layout, owner SAVED and observations cleared after completion.
- Review package `redis-binary-p2-429230c3311a4695a5bbb1dc19f54ffb-fixture-review`: exact fixture.diff SHA `f9b6de1498e8520a53bdc87e3c84713d820b38768a56957670da538fabf5a5c3`; changed test SHA `3f32995e517dc0ec0f4f51029d5378669d47f5105efb2e705522ccd2b01bde48`, length26958; new 878-input binding SHA `9ff2250eea0b0eabe134df70e832bc45713c0f00b03d279a0641ba73c9a6d9c7`.
- input-delta.json proves only AppShellWorkspaceShutdownTest.java differs from accepted controls' 878 file rows; no source path additions, no product or tool changes. Modified tests not yet run, pending root independent precise-diff admission.

### C13 fixture final review and engineering migration entry prepared
- Final regression also submits different layout after seed completes and requires owner PENDING, proving activity admitted again after SAVED (not FROZEN). It uses no real close/cancel action for seed; only owner freeze/save/resume in the single FX seed operation.
- Superseding review package `redis-binary-p2-8ab0c9a68b274b5cb3a5521dcbca0d1d-fixture-review`. Test SHA `e8ca8af6b4a38baaa6bc3ceb5d5a3b480676139324251ef858bec2c2f175a225`, length27512. Diff SHA `5581c66f05c43ab33a4495ee1f8de59e3325089434fb3b2b08d991eea252bcfd`. New input binding SHA `ce586900760da7932e024ec318f9c9e84a7eecadfb6e0758d5a9b34012e6e542`. Exact migration JSON SHA `e89b04db27d8d4d3c1721591f4150721f8ff3e008314c9afc4d5b5719beb3bae`.
- Prepared (NOT RUN) new engineering package `redis-binary-p2-fd5eabee9eeb4eb3a4c20746509772bc-engineering`, entry manifest SHA `4187bb8788e328a3d7f19e2f900d4543f8a4acb217e5e02135e3ce8c79810b3f`.
- Controller binds exact migration SHA and hardcodes allowed test path; proves old row matches accepted control binding, new row matches reviewed SHA, all 878 path/length/hash rows equal migrated expected set, other877 unchanged; existing 12-tool identity/control-manifest checks retained. No broad acceptance of source drift.
- Source-derived regression audit additionally checks all5 AppShellWorkspaceShutdownTest methods (new controlled test included), plus prior6/2/14. Await independent fixture diff/input admission before execution.

### C14 complete admitted engineering sequence sealed for root review
- Root accepted final fixture e8ca8af6... and exact old/new migration; resumed sole worker Gradle execution. No further source changes.
- Package `redis-binary-p2-fd5eabee9eeb4eb3a4c20746509772bc-engineering`, controller/operator actual exit0, all five stages actual exit0 and strong root/host proof, complete EOF/settled Jobs.
- Actual XML: targeted58 suites/304tests/303passed/1 approved live skip/0failures/0errors; full353 suites/4736tests/4733passed/3 approved live skips/0failures/0errors; forced buildSrc1suite/8tests/8passed/0skips/0failures/0errors.
- Both targeted/full audited exact current source method sets against raw XML: RedisBinaryKeyTest6, RedisBinaryKeySessionTest2, RedisPaneBudgetTest14, AppShellWorkspaceShutdownTest5 (including controlled seed publication regression). No native skip accepted.
- 57 original owned processes audited: targeted10/full10/buildsrc10/image10/linked17, each actual root0/host0, complete original stdout/stderr/host streams and EOF. Five actual outer stages Job empty and complete streams; no process failure/termination requested in successful sequence.
- 878 per-stage input files unchanged before/after/final except the explicitly admitted old-to-new fixture migration from accepted control binding. All 12 working/frozen/scope tool bytes matched. Actual source/type mapping410 in all stages.
- Image and linked passed; image-before/image-after/source-image manifest identical. Original module-index contains `com/datacube/redis/RedisKey.class`; module-index SHA `333794fc621fd127cb0c9edd7b75cd8bab9edbd965c71e0623ab29b4fe5d7a5b`. Image audit: classLeaks/missingClasses/fileLeaks empty, optionLeaks false, testTypeCount410. Four linked commands module-index/probe-compile/driver-discovery/redis-linked passed; connectCalls=0, socketsSettled=true, realServices=0.
- Successful freeze `redis-binary-p2-fd5eabee9eeb4eb3a4c20746509772bc-engineering-frozen/manifest.json`, SHA `ba41a8c537203f70db80150a17e77cd76da637cbcd6a5eecf4514694204b6bdd`;11 actual-spec-derived roots,1188 original files,21496307 bytes. Audit script SHA included in manifest. Runtime/home/temp/build excluded; image artifacts bound by original inventory/core hashes and unchanged executable receipts.
- Both prior engineering attempts rehashed file by file: first failure214 files + diagnostic stop219 files, both remain explicitly unaccepted. No pass reuse.
- Final16-source checkpoint `redis-binary-p2-26c5f5b6b23a4107b8e9e50d228ad823-source`: identities SHA `c96a8f7b06277e844cfdfa75d8af68f6e59baaf434c31912fcde61c56d84a9ef`; full diff SHA `422756153123536f9fdee3027e3cc12721899d837ac5b3f9adf90ddb7d81bf8b`.
- Await root independent image/linked/original-freeze acceptance and explicit commit transportation instructions. No staging/commit/main merge/push/tag changes; no real connections/system clipboard, forbidden directory or old script access.

| Requirement | Evidence |
| --- | --- |
| Raw binary identity and bounded display preserve distinct keys | RedisBinaryKeyTest exact6 methods; targeted/full regressions-audit.json and original XML all passed |
| Every typed command uses original key bytes and whole-command budget | RedisBinaryKeySessionTest.everyTypedKeyCommandUsesOriginalBulkBytesIncludingRenameAndEmptyKey; wholeCommandPreflightChargesRawKeyBytesAndRetainsStringFacadeBudget; both stages passed |
| Native five editors, stale binding/generation and candidate rollback | RedisPaneBudgetTest.binaryKeySelectionAndFiveEditorsSendOriginalKeyBytesAndInvalidateOldBinding; binaryScanOverflowAndInstallFailureKeepOriginalTreeAndCursor; delayedBinaryValueCannotOverwriteDifferentRawSelection; both stages passed |
| Seed waits actual in-flight publication and resumes later activity | AppShellWorkspaceShutdownTest.fixtureSeedWaitsForActualInFlightWorkspacePublication; actual XML passed targeted/full |
| Complete clean verification | Frozen run-engineering.ps1 -Package redis-binary-p2-fd5eabee9eeb4eb3a4c20746509772bc-engineering; raw targeted/full/buildsrc/image/linked specs and owner command.json; actual exit0 each |


### C15 local transport commits authorized
- Root independently accepted all five P2 engineering stages and full seal ba41a8c5..., then authorized exactly two local commits. Gradle execution authority returned to root; no further validation runs.
- Source commit: `c33b9ef45c680b86976919b68b81d4d61b9230c7`, exactly16 reviewed source/test/tool files. Every staged Git blob compared with worker bytes; all16 identical at staging. Java retains normal repository line-ending policy (checkout may use CRLF); raw evidence/tools stay -text.
- Evidence commit will contain only two append-only .gitattributes changes, this final worker report, and strict redis-binary-p2-UUID evidence roots including all retained attempts/source/fixture/control/engineering originals. Transport payload generated after this report update; payload excludes itself and its independent SHA is reported with final commit SHA.
- No root plan/CURRENT/root-review directory, forbidden path, old script, main merge, push or tag changes.
