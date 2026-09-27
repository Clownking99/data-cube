# 字段 / 注释检索：原生验收补证

维护者要求继续推进产品。本轮在基线 a4dc88717ee6617e8d0e00e2721729e360483a24 补齐此前受桌面工具失败影响的局部原生证据，不修改产品源码/测试/依赖。独立分支 codex/discovery-native-acceptance；检查点见 [实施记录](../plans/2026-09-27-discovery-native-acceptance.md)。总体 M8 仍待外部验收。

## 实际桌面证据

使用全新合成 profile、既有 MetadataCancellationDesktopProbe、mock Statement 和合成元数据；全部 UI 输入通过 Computer Use node_repl / sky。三个进程均正常退出，无强制终止。原始截图、观察树和关闭确认在 [desktop](evidence/discovery-native-acceptance/desktop/)，清单按原始字节 SHA-256 归档。数字为文件名前缀；文件名描述操作意图，结论以此表和实际截图/日志为准。

| 操作范围 | 实际观察 | 证据 | 边界 |
| --- | --- | --- | --- |
| 输入与明确查找 | type_text 成功；输入文字不立即读取；点击查找才进入在途 | 01–04 | 单独检索对话框，合成 Loader |
| 超时、读取先完成 | 首次取消点击前已超时；读取结束后仍等待取消任务，两者结束后恢复查找；旧结果不发布 | 05–08；desktop.stdout.log | 不算明确取消用例；不证明真实驱动及时响应 |
| 明确重试和结果预览 | 浅色下点击查找得到新结果；未选择时动作禁用；选择后预览 demo / retry_table / customer_id / customer 并开放动作 | 09–12 | 无真实元数据/数据查询 |
| 键盘和条件失效 | Ctrl+F 选中查询词，输入 order_note 清空旧列表/预览并禁用动作 | 13–14 | 未验完整键盘遍历 |
| 字段注释及 Enter | 原生菜单选择字段注释，Enter 明确查找，结果预览匹配新来源和检索词；点击生成 SELECT 选择动作后关闭 | 15–23 | 对话框返回选择；未接入 AppShell 生成 SQL / 数据 / DDL 下游流程 |
| 150% 明确取消、取消先完成 | 在超时前点取消；取消任务先返回仍不开放查找，读取返回后提示恢复且旧结果为空 | 24–32；cancel-150 stdout | 进程输出比例 1.5，非 OS 缩放切换 |
| 明暗和 Escape | 1.0 / 1.5 下明暗状态可读；第二轮 Escape 正常退出 | 01、09、24、32–34 | 没有缩窄窗口或多屏测试 |
| 在途关闭 | 第三个 profile 读取在途时 Alt+F4；原 close handler 运行，窗口消失、PID 退出，日志显示任务返回 | 35–39；close-inflight stdout | 夹具 onHidden 主动释放 mock 门闩，不代表真实阻塞 JDBC 的关闭保证 |

第一进程 stdout 为 reads=3 / cancels=1，第二、三进程均 reads=1 / cancels=1；三个均 realConnections=0。没有反射或 .fire 触发桌面动作，也未打开现有用户 profile。

## 本轮新验证

JDK 25.0.1+8、JavaFX 25、Gradle 9.2.0，offline。测试均设置全新 user.home、headless=false，并移除 DATACUBE_REDIS_* / DATACUBE_SCHEMA_DIFF_* 真库环境。完整参数、时间、实际 Test 任务、XML 计数/清单和产物 SHA 见 [结果 JSON](2026-09-27-discovery-native-acceptance-results.json)。

| 执行 | 结果 |
| --- | --- |
| desktop-build / javac | classpath 任务及既有合成探针编译成功；不是测试通过证据 |
| branch-full | clean test：307 suites / 3835 tests / 3832 passed / 0 failures/errors / 3 live skipped，4m9s |
| branch-buildSrc | --rerun-tasks：8/8 passed，0 skipped，9s |
| branch-image / runtime | jpackageImage 成功，44s；cfg / modules 无已知验收参数或夹具泄漏，Oracle/PG 驱动可发现、connectCalls=0 |
| main 定向与集成复验 | 合并后补记；不能提前算通过 |

三个 live skips 分别为 Redis、Oracle Schema Diff、PostgreSQL Schema Diff；缺少显式授权环境及写入门禁而跳过，不计为通过。全部 XML 保留在独占临时目录 C:/Users/hetia/AppData/Local/Temp/datacube-discovery-acceptance-d3484e336ea74a8c889da1e47137ac8b；仓库保存定向相关 XML、完整 XML 摘要清单与原始日志。

## 限制、审查和下一步

历史两次 GetCursorPos / CreateForMonitor 失败没有删除；本轮工具正常，补证仅覆盖上述局部范围。即时可访问性树偶有滞后，因此保留后续稳定截图/树；菜单 End 未选中末项，随后截图点击成功；在途关闭后的首次窗口列表尚有瞬时旧项，随后空列表和 PID 退出共同确认关闭。JavaFX unnamed-module 警告、编译 unchecked 警告和 jlink JEP 493 信息均保留，不称零警告。

此轮没有复現需改产品代码的问题，因此交付证据与待验项更新。审查着重实际动作与结论一致、三个 profile 独立、未执行连接、原始证据完整、跳过不算通过、100%/150% 不等同 OS 设置。下一步仍是授权边界内的完整 AppShell 检索流程及 SQL 极限小窗口原生滚动/键盘；OS 多屏、真库权限/事务/取消、正式启动器、签名/安装升级与回退、远端同 SHA CI、真实用户任务及发布仍待授权或人工验收。

本地集成状态将在 main 复验后补记。本轮不推送、不建 PR、不改 tag、不安装更新、不联系外部人员；不称发布验收完成。
