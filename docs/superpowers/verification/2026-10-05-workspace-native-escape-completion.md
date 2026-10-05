# DataCube 2026-10-05：工作区原生退出限定验收完成

限定矩阵**8/8通过**，此前7项证据保留，本轮补齐真实Esc取消，并完成同profile原生Retry正常关闭。main新profileRetry已在前轮完成；完整M8/发布验收仍待验。

[检查点计划](../plans/2026-10-05-workspace-native-escape-completion.md)、[8项矩阵](evidence/workspace-native-escape-20261005/native-matrix.json)、[独立分支审计](evidence/workspace-native-escape-20261005/audit-branch.json)、[原件清单](evidence/workspace-native-escape-20261005/raw-manifest.json)。本轮49原件冻结，旧1038原件不覆写。

## 实际结果

- 人工ESCAPE于2026-10-05T03:25:58.839829200Z进入真实production Alert，随后取消退出/CANCELLED、global runner恢复；文件入口恢复、dispatcher0、provider0。取消后实际72字节workspace与前轮原字节快照完全相同，SHA c1277981…；不是仅凭文字回复或summary判定。
- 根随后原生Alt+F4、生产Alert、单独释放自有故障marker、原生Retry。关闭尝试保持原冻结布局，最终三次相关SHA均48f762dd…；真实draft UUID、顺序、首项选择、1/7与2/8编辑位置独立解码符合。COMPLETED资源只关闭1次，真实store锁重开，provider0，无fallback；自有PID5808自然退出。
- 独立审计核对旧1038原件、4份native状态/5份目标截图、真实前后字节/事件/资源、当前main镜像3SHA和受验源。本轮只外置证据/文档，无产品/测试/构建变更，无新javac/Gradle/full/buildSrc/image运行，历史测试不充当新证据。

## 失败与局限

首个目标capture显示了非目标前台画面，未据其输入；重新枚举/激活后恢复，无关画面不进入项目，只留[说明](evidence/workspace-native-escape-20261005/native/capture-limitation.json)。独立审计两次UTC比较失败，保留[第一次](evidence/workspace-native-escape-20261005/audit-first-attempt/failure.json)和[最终诊断](evidence/workspace-native-escape-20261005/audit-second-attempt/failure.json)及对应脚本；统一JSON时间类型并Invariant解析后通过，原始事件不变，无产品失败或自动审批拒绝。

外置真实AppShell/原guard/store/production Alert不等同正式DataCubeFx启动器。完整在途恢复、字段原生键盘/多结果/失效、小窗/OS多屏、其他真库、安装升级/签名/CI/用户任务/发布仍待验；M8不标完成。原安全边界沿用，datacube已核实PAUSED并保持，无新goal/预算/线程。本限定验收交付后停止，不自动扩展。

## 本地集成

分支41ea991d339c04ca1260aeb2a3e151225d26d8fa已本地no-ff合并main641f046b752106cf41d4cfb2356be3805f75a789，main独立复验通过，详见下方E3。
## E3：本地交付与main实际复验

- 当前目标：限定8/8原生矩阵交付；目标内无剩余待验。
- 实际集成：分支41ea991d339c04ca1260aeb2a3e151225d26d8fa，main641f046b752106cf41d4cfb2356be3805f75a789。55份证据/文档精确白名单与磁盘/暂存字节检查通过，追加2份审计元数据后共57文件提交；只文档/外置证据。
- 新复验：main运行[Verify-Escape.ps1](evidence/workspace-native-escape-20261005/Verify-Escape.ps1)生成[audit-main.json](evidence/workspace-native-escape-20261005/audit-main.json)。旧1038原件、新49冻结原件、实际Esc前后字节、取消/恢复/重试/资源/锁、真实draft布局、4状态/5目标截图、源码与当前镜像3SHA均通过。产品/测试/构建对f776f6e不变，本轮无新工程构建或测试执行声明。
- 失败/未验：两次审计工具误报及非目标首帧限制均保留说明并已恢复；无产品FAIL/fallback。完整M8/正式启动器/发布及前文列出的其他场景仍待验，本次不扩大完成口径。
- 交付后动作：本限定验收完成并停止；专用PID5808已正常退出，现有datacube跟进保持PAUSED。无活动goal、未创建目标或预算；不自动启动后续范围。后继元数据提交只记录本次main复验，不改变受验产品。
