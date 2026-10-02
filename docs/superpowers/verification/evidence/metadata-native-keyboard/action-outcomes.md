# N1 实际原生动作与结果

所有 `sky` 动作由 node_repl 的 `@oai/sky` 执行。目标只取工具返回的唯一合成 shell：Window id=5441012，title=`DataCube N1 d43af922a7614bcba6e4919940f2da51`，app 为该 worktree runtime/bin/javaw.exe。每次单独观察后，下一 cell 一个动作+立即刷新；图片只从实际返回的 state.screenshots.url 保存，没有其他窗口或旧全局截图。

`native-actions.jsonl` 保存实际每个观察状态、时间、当前 Window、焦点/tree、Screenshot ID/尺寸/origin/zIndex；同一 seq 前缀 JPG 是该状态真实返回的图片（工具实际返回 JPEG，无重编码）。弹出菜单和字段对话框属于该合成 shell，保存对应返回的 transient 截图。以下 action 数字指当次观察的 element_index，不跨状态套用。

| 保存状态标签 | 实际动作或观察 | 结果 |
| --- | --- | --- |
| shell-initial | list_apps 精确过滤→get_window→get_window_state | 唯一合成窗口；1103×751 screenshot；无业务窗口截图 |
| expand-connection | click element_index=34, click_count=2 | 展开合成连接，demo 出现；mock acquire 一连接 |
| schema-context-menu | click element_index=47, mouse_button=right | 合成 demo Schema 菜单出现 |
| open-field-dialog-owner | click screenshot-0 x=190,y=289 | 打开真实字段对话框，原始空框 |
| 未生成 seq 的选择错误 | list_windows 筛选完整字段对话框标题 | Expected unique dialog；该 API 只返回 shell，不提供 owned dialog 独立 Window |
| dialog-empty-reobserve-owner | 对唯一 returned owner 重新 get_window_state | 返回 owned-dialog 截图；tree/focused_element 显示 TextField；实测无独立 dialog Window |
| 未生成 seq 的索引错误 | click element_index=74 | `element 74 is not available in cached app state for javaw.exe`；没有宣称点击成功 |
| recover-after-index-unavailable | 重新 get_window_state | 保留当次截图与焦点，不复用旧 index |
| native-coordinate-focus-empty | click 当次 screenshot-1 x=200,y=98 | 图片显示模态输入框焦点；文本仍空 |
| native-type-customer-no-submit | type_text text=customer | API 未抛错，但 owner 被激活、模态失焦，文本仍空；没有 QUERY_CHANGE/DIALOG_TYPED；**输入失败** |
| type-not-visible-reobserve | list_apps 核对→get_window_state | 仍只返回 shell Window，模态文本仍空 |
| type-recovery-refocus | click 当次 screenshot-1 x=180,y=98 | 一次有限恢复，图片显示模态输入框焦点 |
| native-type-retry-once | type_text text=customer | 第二次仍空、模态失焦；**停止 type_text 重试** |
| before-native-resize | get_window_state | dialog screenshot 663×639 |
| native-resize-attempt-one | drag 当次 screenshot-1 from=(660,636),to=(510,460) | 没有尺寸变化，仍663×639；没有width/height被动事件；**无效果拖动，不算缩小通过** |
| before-tab-probe | get_window_state | tree报告输入框焦点；截图用于按键前观察 |
| native-tab-single-probe | press_key Tab | 模态失焦，未出现 DIALOG_KEY；**未到达，不算 Tab 导航通过** |
| open-mode-menu | click 当次 screenshot-1 x=128,y=133 | 模式菜单出现，字段名/对象注释/字段注释可见 |
| native-mode-column-comment | click 当次 screenshot-1 x=141,y=188 | 操作激活owner后弹出菜单先消失，实际焦点为结果ListView，mode仍字段名；**切换未成功** |
| before-esc-probe | get_window_state | 保留图像与tree；tree焦点与被动ListView实际焦点不一致，不能单凭accessibility认定有效焦点 |
| native-esc-single-probe | press_key Escape | 模态失焦，未出现DIALOG_KEY，dialog仍存在；**Esc未到达** |
| native-click-dialog-cancel | click 当次 screenshot-1 x=614,y=614 | 局部 dialog 正常关闭；有关闭动画的即时截图保留 |
| shell-after-dialog-cancel | get_window_state | 已仅剩真实AppShell；这不是完整shell退出证据 |
| close-refresh-outcome.json | click 新观察 element_index=6→立即get_window_state | native shell关闭动作完成，刷新报 `window is not a usable app window`（已销毁）；随后独立PID退出+SHUTDOWN_COMPLETED+资源平衡确认正常完整退出 |

不把API无错误返回认定成功；最终以实际截图和被动运行日志交叉核对。没做任何 set_value/setText/seed/paste，没有字母按键降级输入（Tab已证实未到达），没有SQL或写操作，没有修改产品来迎合helper。
