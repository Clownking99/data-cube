"""Update readable checkpoints only after all new main receipts pass."""
from pathlib import Path
import json,subprocess
e=Path(__file__).resolve().parent;r=e.parents[4]
receipt=json.loads((e/'main-verification.json').read_text());assert receipt['passed'] and receipt['mainWorkerArtifactsEqual']
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=r,text=True).strip();assert receipt['head']==head
intent=json.loads((e/'delivery-intent.json').read_text())
relative='evidence/g9-ci-main-20261009/'
review=r/'docs/superpowers/verification/2026-10-09-g9-ci-portability-review.md'
s=review.read_text(encoding='utf-8');assert '## C10：' not in s
s+='''
## C10：修正后main复验完成，等待最终新SHA CI

当前目标：完成测试兼容修正后的P3最终交付。main已本地合并6b8ceb93为ef2b5b1d0ff186c6ae30304384ba29bb28dd74f5，根会话为唯一Gradle执行者。合并后526份返工原件与Git/冻结全部相同，无检出换行差异；src/构建/README仍与e7950123一致，测试与6b8ceb93一致。

新独占UUID、清空环境、JDK25离线执行并由[Verify-Main.py](evidence/g9-ci-main-20261009/Verify-Main.py)独立核对[最终本地回执](evidence/g9-ci-main-20261009/main-verification.json)：受影响7 suites/85 passed（包含cancel identity完整4例）、强制headless 1 suite/16 skipped/0 passed、clean全量345 suites/4667 tests（4664 passed、3 live skipped）、强制buildSrc 1 suite/8 passed；所有运行0 failure/error、exit0、实际任务执行、844输入前后字节稳定。新headless日志明确true，ui-error在门禁跳过，不计UI通过；全量当前XML与归档相同。新整套G9 1182定向已在修正分支独立通过，main用受影响85及新clean全量覆盖修正后状态，不把旧1181充当新计数。

clean后重新jpackageImage并做外置镜像审计：183文件无测试类/profile/验收选项污染，7子命令exit0；XML7组、XLSX20包/40单元格独立回读及6非法UTF16拒绝、G9 SQL/XLSX各501行成功和中途失败原目标保护通过。exe/cfg/modules的长度与SHA全部等于已审P2和首次main镜像（modules 102622232字节，62AB89F099650151039E7122ADF86EBFB7BB19EB4B5D1565BA79E1D6FCFF60C4）。产品/构建未改，但本次确实重建，不冒用旧镜像任务。JAVA_TOOL_OPTIONS相关诊断原stderr仍保留。

Windows首次FX初始化超时在worker一次单类+全量及本次main受影响+全量均未复现；根因仍未知，未改该类、FX门禁等待、断言或生产取消逻辑。首次失败Run 37822449579的Linux14失败、Windows1失败及image skip全部保留；不会把新本地绿抹成旧CI成功。Linux真实helper仍以新精确SHA CI为最终证据。

后续文档提交不改受验产品/测试/构建树。只推最终main，读取新精确SHA四项Verify及Windows实际test/buildSrc/jlink日志；直连失败才命令级7897代理，不fetch/tag/PR/发布。新[delivery-intent.json](evidence/g9-ci-main-20261009/delivery-intent.json)指向独占CI原始回执，只有其中delivery-result.json实际passed=true才完成本轮并暂停datacube-g9。首次失败回执已冻结在本审查C8两个证据目录；旧build临时路径不再作为最终交付入口。

外部待验与局限保持：真实Oracle/PostgreSQL驱动/MVCC/undo/网络与取消、真实pg_dump/libpq16+、原生桌面/chooser/Excel与系统缩放、慢/网络磁盘、文件身份校验到move/unlink竞争窗口、驱动内部大值分配、永久阻塞资源pending和已捕获进程家族范围、保守SQL结构类型/仅列与PK及DDL-数据非整个Schema原子快照、安装升级/回退/生产签名和完整M8。CI临时Redis不等同真实业务服务。旧datacube保持PAUSED，v3.2.9不变，交付后不启动下一目标。
'''
review.write_bytes(s.encode())
oldcoord=r/'docs/superpowers/verification/2026-10-08-g9-table-export-coordination.md'
s=oldcoord.read_text(encoding='utf-8')+'\n## 后续C8-C10：CI返工与最终交付入口\n\n首次推送f634f7f4的Linux14失败及Windows1次既有FX初始化超时已保留；同一6.1-sol线程最小测试修正6b8ceb93由root独立审核后合main ef2b5b1d。修正后worker完整定向1182、全量4664通过/3live跳过；root新受影响85、headless16明确跳过、全量4664通过/3live跳过、强制buildSrc8、重建镜像与外置探针均通过，三核心产物SHA不变。详细最新检查点见[CI协调C10](2026-10-09-g9-ci-portability-review.md)。最终推送/CI/暂停以新[g9-ci-main delivery-intent](evidence/g9-ci-main-20261009/delivery-intent.json)所指实际回执为准，前述C7旧入口仅保留历史失败身份。首次Windows超时根因仍未知；外部/原生及完整发布待验不变。\n'
oldcoord.write_bytes(s.encode())
for name,prefix in [('docs/handoffs/2026-09-23-product-maturity-goal-handoff.md','../superpowers/verification/'),('docs/superpowers/plans/2026-09-23-product-maturity-roadmap.md','../verification/')]:
    p=r/name;s=p.read_text(encoding='utf-8');a=s.index('**2026-10-09 G9最终CI阻断：');b=s.index('\n\n',a)
    text='**2026-10-09 G9最新：CI测试兼容修正已集成，main新本地复验通过。** 产品e7950123、修正6b8ceb93、main集成ef2b5b1d；526份返工原件独立核验。分支新完整G9定向1182/全量4664通过+3live跳过；root新受影响85、单列headless16跳过、clean全量4664通过+3live跳过、强制buildSrc8、重建镜像与外置linked探针通过，三核心产物SHA不变。首次CI两平台失败已保留，Windows初始化超时本地未复现但根因未知。最新证据/待验见[CI协调C10]('+prefix+'2026-10-09-g9-ci-portability-review.md)，最终新SHA CI和结案以[新delivery-intent]('+prefix+relative+'delivery-intent.json)所指实际回执为准；成功后暂停datacube-g9，不启动下一目标。真实DB/pg_dump、原生/慢盘/文件系统局限、安装签名和完整M8仍待验；旧datacube/v3.2.9不变。下方为历史检查点。'
    p.write_bytes((s[:a]+text+s[b:]).encode())
p=r/'docs/superpowers/plans/2026-10-08-g9-table-export-reliability.md';s=p.read_text(encoding='utf-8');a=s.index('P3 / main本地复验通过，但首次精确SHA CI阻断：');b=s.index('\n\n为落实持续下发',a)
text='P3 / CI测试兼容返工已通过独立审查并合main ef2b5b1d：526原件与6b8ceb93一致，仅3测试修改。新分支完整G9定向1182/全量4664通过+3live跳过；root新受影响85、headless16明确跳过、clean全量4664通过+3live跳过、强制buildSrc8、重建镜像/linked审计通过；产品仍e7950123，三核心镜像SHA相同。首次CI失败和Windows超时根因未知均保留，详见[最新C10](../verification/2026-10-09-g9-ci-portability-review.md)。最终新SHA推送/Verify四项以[新delivery-intent](../verification/evidence/g9-ci-main-20261009/delivery-intent.json)所指实际回执为准，成功后暂停datacube-g9并交付，不自动启动下一目标；真库、原生、慢盘/文件系统局限及完整M8仍待验。'
p.write_bytes((s[:a]+text+s[b:]).encode())
print(json.dumps(dict(updatedDocuments=5,verificationHead=head,finalCIReceipts=intent['receipts'])))
