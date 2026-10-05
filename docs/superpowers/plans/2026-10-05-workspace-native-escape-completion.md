# DataCube 2026-10-05：工作区原生退出限定矩阵收尾

维护者回复“已按 Esc”后，根核实实际证据并完成限定矩阵。基线main与独立worktree均f776f6ed979dced95530774d9b1a29a898931cf8，授权范围干净；分支codex/workspace-native-escape-20261005。只完成当前原生验收，不启动后续产品范围/线程，不访问真库或原业务内容。

## E0：实际 Esc 与取消

- 当前目标：验证真实键事件、取消、旧字节和资源恢复；文字回复不作通过依据。
- 改动/验证：归档专用profile8544af7a-8377-4bb1-95f8-4e7047e2c826在Esc后的原始事件、编号summary、实际workspace/draft。2026-10-05T03:25:58.839829200Z记录ESCAPE，随后取消退出/CANCELLED和global runner pulse；file admission恢复，dispatcher0、provider0。原mandatory guards已移除旧tabs，不宣称保留tabs。
- 实际字节：取消后真实72字节文件与前轮start-snapshot完全相同，SHA c1277981…；不是从summary推断，也不是重建旧字节。故障未释放。
- 未验/下一步：原生最后一行已取得证据，继续同profile正常Retry关闭以结算资源；旧文件不清理。

## E1：同 profile 正常关闭

- 当前目标：退出取消后重复关闭保持冻结布局，成功只释放一次资源并释放存储锁。
- 实际操作：重新选取自有窗口，原生Alt+F4再次触发生产Alert，CREATE_NEW仅释放专用故障marker，再点击生产Retry；marker不冒充原生决策。
- 实际验证：三次关闭相关发布SHA同为48f762dd…；独立解码最终workspace的两真实draft UUID、选中首项、anchor/caret1/7与2/8，capturedAt沿用首次冻结值。实际COMPLETED、dispatcher1、runtime/registry关闭、global runner拒绝准入、store实际重开锁释放、provider0，无FAIL/fallback，PID5808自然消失，本轮未用Stop-Process。
- 失败/纠正：首个capture的目标元数据正确但画面为非目标前台内容；未据其输入，重新枚举/激活后恢复。无关截图不纳入项目证据，只保留工具问题/恢复说明。无自动审批拒绝。
- 下一步：独立原件/源/镜像审计与本地集成。

## E2：审计、失败与范围

- 当前目标：7项历史已验加本轮Esc形成8/8的限定证据链，历史不当新运行。
- 实际验证：历史1038冻结原件长度/SHA不变，新4份native状态记录/5份目标截图、新49冻结原件；真实before/after字节、取消/恢复、冻结Retry和完成资源/锁逐项核对，当前main三镜像SHA及源绑定一致。产品/测试/构建无改动，本轮无新javac/Gradle/full/buildSrc/jpackageImage运行声明。
- 失败保留：独立审计前两次顺序比较失败；ConvertFrom-Json把9位小数UTC保留为String、6位转成DateTime，混合比较/重新Parse导致时区与精度问题。失败脚本和诊断保留，统一-DateKind String后用Invariant DateTimeOffset比较，实际Esc至CANCELLED为19.7238ms，审计通过。首轮仅归因字符串比较的解释不完整，以第二份诊断为准，未改原产品事件。
- 下一步：精确白名单/字节提交、main本地合并和独立复验。完成后交付本限定目标，不自动下一轮；datacube实读为PAUSED并保持，get_goal为null，不创建目标或预算。

正式DataCubeFx启动器、完整在途恢复、字段全链原生键盘/多结果/失效、小窗/OS多屏、其他真库、安装升级/签名/CI/用户任务/发布仍待验，M8不标完成。.testagent禁读改；不读原凭据/配置/SQL历史/业务文件；无真库、push/fetch/tag/PR/发布/更新/外部联系。

## E3：交付检查点

限定8/8完成；分支41ea991已合并main641f046，新main原件/实际字节/事件/资源/源与三镜像SHA审计通过。专用进程自然退出，原失败说明保留，产品/测试/构建未改，无新工程测试声明。具体提交与复验见[完成账本](../verification/2026-10-05-workspace-native-escape-completion.md)。下一步为交付后停止，datacube保持PAUSED；完整M8/发布仍未验，不自动扩展。
