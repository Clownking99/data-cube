# DataCube 2026-10-05 工作区键盘续验

当前限定矩阵**7/8通过**：人工Enter已核实，main新profile原生Retry已复验；剩余Esc实际按键。未完成整个原生目标，更不代表M8发布验收。

[检查点计划](../plans/2026-10-05-workspace-native-key-acceptance.md)、[本轮矩阵](evidence/workspace-native-20261005/native-matrix.json)、[独立审计](evidence/workspace-native-20261005/audit-branch.json)、[原件清单](evidence/workspace-native-20261005/raw-manifest.json)、[实际限制和纠正](evidence/workspace-native-20261005/limitations.json)。旧目录保持不可变。

## 实际新证据

- 人工keyboard原始DIALOG_KEY ENTER→取消退出→CANCELLED，取消时旧SHA和资源保留断言通过，global runner恢复。之后fixture释放故障，下一次正常退出COMPLETED不算Esc或原生Retry。本轮独立解码最终workspace并按fixture种子格式重建旧哈希，结果与取消时summary一致；不冒充取消瞬间文件的新读取。
- main6cf6788上新独占profile：新javac exit0，原夹具/launcher和实际镜像3SHA绑定；原生Alt+F4/生产Alert/原生Retry。专用marker释放明确是fixture操作。实际冻结最后两attempt及最终文件SHA一致，两个真实draft/选中首项/1-7与2-8位置正确，资源关闭1、store锁重开、provider0、无fallback，进程自然退出。
- 历史951原件哈希不变；新9份状态记录与11份存档截图、87份冻结原件。只有外置证据和文档，产品/测试/构建未改；没有本轮Gradle/full/buildSrc/image测试声明，既有工程测试保留为历史。

## 失败、未验与下一步

初次沙箱进程只有READY而无可枚举桌面窗口，精确清理后用既有交互桌面权限启动成功；清理不等于产品退出。旧profile首读路径漏.datacube，未产出哈希，核对源码后修正；原始问题记录保留。没有自动审核拒绝或新产品缺陷。

仅待人工Esc：新窗口DataCube WORKSPACE NATIVE 5ac9286bb578449abaaa10585abacbcc/PID5808，真实“工作区记录未保存”Alert已准备，故障未释放，根已停止输入；维护者只需聚焦小弹窗标题栏并按Esc。已备真实旧workspace快照，后继必须检查DIALOG_KEY ESCAPE、CANCELLED、旧SHA/资源/任务恢复，不能仅凭文字回复通过。当前[检查点](evidence/workspace-native-20261005/checkpoint.json)仍0实际key/0cancel。

main新profileRetry已完成，不再列为待验。正式DataCubeFx launcher、完整在途恢复、字段检索键盘/多结果/失效、小窗/OS多屏、其他真库、安装升级/签名/CI/用户任务/发布仍未验。沿用原边界，datacube不恢复、无新goal/预算/线程。后继Esc原件另存新phase，禁止改写历史冻结快照。

Computer Use技能[SKILL.md](C:/Users/hetia/.codex/plugins/cache/openai-bundled/computer-use/26.930.31730/skills/computer-use/SKILL.md)的guidance要求“Never reconstruct an app or window from guessed fields.” 前轮modal没有独立枚举句柄，按键调用未投递；不伪造modal句柄、不用其他UI输入绕过。人工按一次Esc用于补真实事件，并非技能要求重新审批。

## 本地集成

分支/main提交与实际复验在完成后追加；本轮受验产品保持6cf6788相同字节。
