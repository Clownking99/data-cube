# G9 P3：精确SHA CI阻断与测试兼容修正

## C8：首次Linux CI失败，已下发最小返工

目标：完成G9 P3最终交付；本地Windows工程验收通过不替代Linux CI。最终文档SHA `f634f7f4b5ec95d91ce9d8ed544c2b8920963b85` 已直接推送main成功，远端API核对相同，未使用代理。精确[Verify 37822449579](https://github.com/Clownking99/data-cube/actions/runs/37822449579)首次attempt中，wrapper与CI临时Redis成功，Linux任务113466665854失败，Windows暂仍执行。G9此时未最终完成，datacube-g9保持ACTIVE，旧datacube/v3.2.9不动。

根会话独立读取完整Linux job日志并归档[原始失败](evidence/g9-ci-20261009-coordination/initial-linux-failure/manifest.json)：任务实际4566 tests completed、14 failed、1751 skipped，BUILD FAILED/exit1。Linux无显示环境会跳过大量既有FX用例；不能将跳过记为通过或与Windows4666直接混同。以下两项由原失败日志与当前源码共同确认：

1. `PgDumpRunnerBaselineRedTest.helper`和`PgDumpProcessHelper`子孙启动使用当前JDK目录的`bin/java.exe`。Ubuntu实际报`/usr/lib/jvm/temurin-25-jdk-amd64/bin/java.exe`不存在，继而出现START、未ready、非零/排空原因不符等13项失败。修正必须同时覆盖主helper及子孙，保留进程族、deadline、物理资源和邻居断言，不跳过Linux测试，不加长timeout。
2. `AppShellTableExportJdbcShutdownTest`的ui-error参数把`FxUiTestSupport.call`包在`assertThrows(ExecutionException)`中；无display时前置Assumption抛TestAbortedException，被断言当作错误类型而失败。须在资源创建和异常断言前执行既有FX显示门禁，有display时仍验证真正UI Error、回调、listener和资源释放；不得全局改变FX支持或宣称headless执行了UI。

已通过正式消息工具向原线程01a11b86-2026-7ed3-86c5-1ec232640653下发上述任务。开发恢复同一aed5/codex分支，根会话交回唯一Gradle执行权并停止Gradle；不新建线程/代理，不合main、不fetch/push。范围限测试兼容，产品/构建树须保持e7950123；若需扩大先报告依据。

要求新证据根保存首次CI失败、最小测试改动、新受影响Windows回归、明确headless=true的同类skip验证，再新完整G9定向和clean全量。无Linux本机证据时依赖修正后精确SHA CI真实helper验证，不把Windows路径断言当Linux执行。测试-only改动无须开发重做未变产品的buildSrc/image；原P2/P3构建字节和产物身份保留，后续根会话复验范围据实际diff决定。完成后冻结、独立分支本地提交并停写交权，再由根会话审核集成及新CI。

失败/未验：首次Linux失败保留，未直接重跑旧SHA掩盖确定缺陷；Windows任务尚未结算，真实DB/pg_dump、原生/慢盘/安装签名等仍未验。下一步检查开发修正与原件、同时观察同SHA Windows是否另有问题；没有依据则不改生产逻辑或扩大G9范围。

源码预审：开发目前仅修改3个测试文件，主helper与子孙共用当前JDK的平台路径（Windows用java.exe、其他用java），新增两分支/当前真实可执行文件检查；UI参数化测试在合成资源前先执行FxUiTestSupport空动作门禁，后续Error/listener/目标字节断言不变。本会话已直接读完整diff并允许继续验证；预计新增1例，尚未以此宣布GREEN。开发turn为01a11cbb-c091-7f40-91fa-ff17e5880545，当前仍独占Gradle。

首次CI现已完整结束：Windows实际4666 tests、1 failed、3 skipped，linked image skipped；Linux失败维持，wrapper/CI Redis成功。已保存[完整失败原件](evidence/g9-ci-20261009-coordination/initial-complete-failure/manifest.json)。Windows唯一失败为既有AppShellSqlCancelIdentityTest PostgreSQL参数，栈在Fixture构造第91行的FxUiTestSupport.call/FutureTask.get，发生于FX初始化、尚未进入被验取消行为。本会话已读源码，根因未知，不能据此推定取消产品回归或放宽5秒门禁。已要求开发在新受影响定向加入该类一次，既定clean全量自然覆盖；复现再诊断，未复现也不抹去首次失败。旧SHA不重跑，修正后的新SHA必须重新通过精确CI。

## C8.1：修正后已完成定向的独立核验

2026-10-08 18:25 UTC跟进，main为78a3af7f且范围内干净，开发仍active且唯一持有Gradle。根会话读取两个新launcher并执行[verify-ci-progress.py](evidence/g9-ci-20261009-coordination/verify-ci-progress.py)，[progress-001回执](evidence/g9-ci-20261009-coordination/progress-001/root-progress-verification.json)独立确认846输入当前哈希与已完成三轮前后绑定相符，旧src/test中仅预审的3份测试改变。

原command/exit/stdout及suite/testcase双重统计：001受影响6 suites/81 passed/0 skip，002强制headless 1 suite/16 skipped/0 passed，003完整G9定向85 suites/1182 passed/0 skip；三轮均0 failure/error、实际test、exit0、新独占UUID。002 stderr明确headless=true，包含ui-error的全部16项均为available display Assumption，明确skip而不是UI实测通过。新平台路径检查已执行；Linux实际helper仍需新精确CI证明。

本检查点只接受这三轮证据；Windows首次FX初始化超时类的一次复验、clean全量、最终源码/原件冻结和本地提交尚待开发完成，不预报通过，不合main或接管Gradle。旧P2/P3证据身份不改；下一步继续核对完整交付，再决定准入。

## C9：测试修正冻结审核通过，接管main复验

2026-10-08 18:40 UTC收到同一worker停写交付6b8ceb93c7413b3882137d322d88b9a1cb14ef5c，范围内干净。根会话重新独立读取最终报告/提交diff并执行[verify-ci-final.py](evidence/g9-ci-20261009-coordination/verify-ci-final.py)，[final-001回执](evidence/g9-ci-20261009-coordination/final-001/root-ci-final-verification.json)确认526份raw/Git原件全部相同，旧P2 3516份冻结原件未变，846普通/847身份单跑输入当前与每次前后清单匹配；实际仅3测试改变，src/构建/README与e7950123相同。

五轮独立XML：受影响81通过；headless16全部跳过/0通过；完整G9 1182通过；cancel identity完整类单次4通过；clean全量4667项、4664通过/3live跳过，全部0 failure/error、exit0、新UUID隔离、实际test执行。全量345份当前XML与归档同字节，物理摘要43/0/135/20/157行逐项存在于对应XML原system-out。首次WindowsFX初始化超时在单类和全量该类4项均未复现，仍标根因未知，不据此声称修复。Linux真实helper仍待新CI证明。

裁决：本地测试修正通过审核，根会话接回唯一Gradle执行权。下一步合main，新受影响测试（包括cancel identity类）、强制headless与clean全量；产品/构建树未变，既有buildSrc/image身份仍有效，但clean会移除main当前镜像，根会话将重建镜像并重新执行审计，同时强制buildSrc作完整工程收尾。保留所有旧CI失败，不在旧SHA重跑；main复验通过后再推新SHA核对四任务。G9仍待最终CI，不暂停跟进、不扩展目标。
