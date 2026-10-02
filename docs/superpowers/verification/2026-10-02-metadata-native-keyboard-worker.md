# N1 原生字段查找开发侧账本

日期：2026-10-02。隔离 worktree `C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾`；分支 `codex/metadata-native-keyboard`，夹具启动时基线 HEAD `7afbe280d92b68b0780333f3a7942157d4197234`；协调随后提交计划/复验脚本 `c175f45...`，产品源码不变。本轮没有产品改动，也没有原生输入/键盘/小窗口通过结论。

## N1a 计划、夹具与启动

- 当前目标：真实 AppShell 上补原生空框输入、键盘、多结果预览、条件失效和实际缩小证据。
- 已完整阅读本轮计划和 Computer Use 的 SKILL.md、guidance.md、confirmations.md、api.md；审查旧 SolShellDesktopProbe.java、Launch-NativeProbe.ps1 与 SchemaMetadataSearchDialog/ConnectionTreePane。
- 改动：建立独立 MetadataNativeKeyboardProbe.java，保留真实 AppShell，仅注入外置 mock provider 与新合成只读连接；去掉旧可选 query 预填路径。合成 metadata ResultSet 支持三条不同对象/字段，三模式日志绑定 connection/schema/mode/term；但本轮未能原生提交查询，三结果尚未实际读取。
- 被动观察：owned dialog 添加焦点、KEY_PRESSED/KEY_TYPED、query/mode/result/status、窗口width/height与控件bounds日志。不调用查询 setText、不主动焦点/选择/submit。仅给窗口加唯一合成标题，便于识别。
- 验证：新 UUID `d43af922a7614bcba6e4919940f2da51`、PID35228、独占临时 `metadata-native-profile`；既有 JDK25 javac 首次exit=0。运行复用已有 jpackage runtime；已验证镜像构建源75ed0c..与本轮HEAD之间 src/build.gradle无差异，记录模块SHA与实际参数。未运行 Gradle/full/buildSrc/image。
- 失败/未验：初读误猜 MetadataSearchDialog.java 文件名导致一次 Get-Content 检索错误，经 rg 确定实际 SchemaMetadataSearchDialog.java，属于检索错误。尚未有本轮原生通过。
- 下一步：由 sky 工具返回对象选唯一合成窗口；逐步原生操作、单动作刷新、保留实际返回图像。

## N1b 工具失败与有限恢复

- 当前目标：从真正空框完成原生 type_text 后观察零自动查询。
- 新证据：原生展开合成连接、Schema右键菜单和字段查找对话框成功；原始截图与日志证明 query空、items=0、actions禁用、searches=0。
- 阻断：list_windows/list_apps均只提供shell Window id5441012，不提供 owned-dialog独立Window。owner get_window_state能返回模态截图与TextField焦点，但 element_index74 click 报 `element 74 is not available in cached app state for javaw.exe`。有限恢复改用新观察的当次模态截图坐标点击，图片显示输入框焦点。
- 失败保留：type_text(customer) API无抛错，但实际重新激活owner、模态失焦且文本仍空；被动日志无DIALOG_TYPED/QUERY_CHANGE。重新观察→原生坐标重新聚焦→type_text仅重试一次，仍失败。没有 seed/setText/set_value/paste 或程序化输入替代。
- 一次独立键探测：当前模态观察后 press_key Tab 同样失焦，日志没有DIALOG_KEY，未导航到mode。停止依赖文字的Ctrl+F/ShiftTab/Enter/多结果UpDown等验项，未以逐字键或其他底层注入继续盲试。
- 模式：菜单能原生展开，三模式可见；点击字段注释时owner重新激活，popup先关闭，实际点到ListView，mode仍字段名。因此不能宣称条件变化、旧选择失效、零自动查询链通过；只有原始未提交状态下searches=0。
- 下一步：独立原生缩小/关闭，准确区分无效果与产品缺陷，核对资源后停止桌面。

## N1c 几何、关闭与结论

- 当前目标：测实际缩小效果，正常关闭完整shell并释放mock资源。
- 几何：窗口被动geometry=674.666687×644.666687；tool dialog screenshot=663×639，OUTPUT_SCALE=1.5。对最新模态shot实际drag (660,636)→(510,460) 一次，前后截图仍663×639，无width/height日志，不能算实际縮小，更不能推断已经证明最小尺寸约束。没有程序化 resize 或截图缩放替代。小窗口可达性未验。
- Esc：press_key Escape 一次无DIALOG_KEY且dialog仍在；未到达，不算Esc取消通过。原生坐标点击Cancel成功关闭局部dialog，随后新截图确认仅剩AppShell。
- 完整正常退出：原生点击新观察shell的关闭按钮；立即刷新因窗口已销毁报 `window is not a usable app window`，保留close-refresh-outcome.json。随后独立验证PID35228退出、raw日志SHUTDOWN_COMPLETED、mock opens=closes=1、searches/pages/ddls/writes/executions全部0。该正常空闲shell退出不等于在途退出/取消/超时恢复验收。
- 证据：native-actions.jsonl为21个实际观察状态；39张实际返回JPEG以seq-label-index命名，未重编码。action-outcomes.md含每一步真实方法、参数及失败；desktop-runtime.log/launcher stdout/stderr、compile-first.log、launch/process-closed/results、Save-ReturnedState.js与Summarize-Worker.ps1保存并可重算。仅归档指定合成运行日志，不归档profile、配置或凭据文件。
- 报告脚本第一次只计PNG，结果错误写savedImages=0；实际工具返回并保存JPEG。第一次命令实际输出另存summary-first-count-failure.json，标为报告计数错误，不当验收结论。已按真实格式修复计数与返回截图总数核对，未重跑桌面或伪造图像。
- 归因边界：现有证据支持Computer Use owner/modal绑定与输入递送限制，不能认定产品输入/键盘/布局缺陷。没有修改产品，也不做无根因FX红绿。父线程继续独立证据审核和工程验证；原生字段输入、键盘、多结果、条件失效、小窗口仍待验。
- 下一步：停止桌面与Gradle，交父线程；未暂存/commit/merge，未改hand-off/roadmap/协调计划，未访问真库/业务内容/原有profile/.testagent，无外部联系。
