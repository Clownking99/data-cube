# 字段 / 注释检索原生验收检查点

## CP0：范围和现场

目标：推进此前工具失败而未完成的字段检索原生流程，沿用本地提交合并和合成桌面授权。main 与可复用独立工作树均为 a4dc88717ee6617e8d0e00e2721729e360483a24，授权范围干净。复用 metadata-search-cancellation 工作树，在 codex/discovery-native-acceptance 分支工作，不清理其他工作树。

已核对交接、成熟度路线图和 9 月 26 日证据；G1–G7 不重复建设。本轮不修改产品源码、测试或依赖，以真实原生输入补足已有功能的证据。未创建新目标或预算。禁止目录、真实连接、凭据、SQL 历史和业务文件未访问；无外部操作。

验证：使用当前 Computer Use 技能的 node_repl / sky，重新发现唯一合成窗口。新建独占临时目录 datacube-discovery-acceptance-d3484e336ea74a8c889da1e47137ac8b。沿用已审查的 MetadataCancellationDesktopProbe，Loader 只返回合成元数据，Statement 为 mock，没有创建连接的路径。未改真实超时值。

失败/未验：历史 GetCursorPos / CreateForMonitor 失败保留，不当作本轮失败。当前第一次取屏成功，可以继续原生流程。

下一步：完成超时或取消后的资源释放、明确重试、结果选择及键盘/主题/关闭，按实际发生的路径记证据。

## CP1：100% 超时恢复与检索工作流

实际：原生点击输入框并 type_text 输入 customer；点击查找。首次点击取消时 10 秒期限已到，故仅记为超时分支。先释放读取，查找仍禁用；再释放取消任务，显示读取结束与超时结果未采用；列表/预览为空、结果动作禁用。切浅色后明确重试，只出现 retry_table，选择后预览目标 Schema、对象、字段和匹配词，三种结果动作启用。

Ctrl+F 选中查询词，输入 order_note 后旧结果与动作失效；选择字段注释，Enter 仅读取新结果；再次选中后点击“生成 SELECT（不执行）”关闭窗口。此夹具仅返回选择，不连接 AppShell，因此不称已验证下游编辑器生成 SQL。stdout：3 次读取、1 次取消、0 个真实连接，正常退出。

限制：即时可访问性树有时落后于截图；保留原始捕获，以后续稳定观察及 stdout 交叉核对。菜单 End 没有选中末项；随后根据最新截图点击字段注释成功。没有把该键计为成功。

下一步：独立 profile 验明确取消及相反任务顺序。

## CP2：150% 明确取消与关闭

实际：新 profile、输出比例 1.5；输入 cancel_case，Enter 后在超时前点击取消读取，明确显示“已请求取消”。先释放取消任务而读取仍在途，查找继续禁用；读取返回后显示已取消结果不采用、可重新查找，且没有自动重试。明暗提示均可读，Escape 正常退出。stdout：1 次读取、1 次取消、0 个真实连接。

第三个新 profile 输入 close_case，Enter 后读取在途时 Alt+F4；原有 close handler 运行，合成夹具在隐藏时释放两个门闩，stdout 确认读取/取消返回和正常退出。关闭后的第一次窗口列表仍见瞬时旧项，下一次列表为空，PID 已退出。此证据不证明真实 JDBC 阻塞可以被打断。

没有用反射、fire 或执行任意表达式代替原生动作。没有 OS 缩放设置变更；100%/150% 仅指进程输出比例。

验证：branch-full 新 profile clean test，307 suites / 3835 tests / 3832 passed / 0 failures/errors / 3 live skipped；buildSrc 强制执行 8/8；jpackageImage 和 cfg/模块检查及零连接驱动探针通过。所有命令 offline。

失败/未验：Redis、Oracle Schema Diff、PG Schema Diff 缺少已授权真库环境而跳过，不算通过；JavaFX unnamed-module 和 JEP 493 信息保留。完整 AppShell 检索动作、SQL 极限小窗口原生滚动、OS 多屏、真库、安装/更新/签名/CI/用户任务仍未验。

下一步：归档原始捕获与日志并核对摘要，审查 docs-only 差异、本地提交和合并 main 后重新执行定向验证。

## CP3：归档与合并前审查

114 份归档证据中有 76 份桌面文件（37 张原始截图和 39 份状态/关闭记录），逐项原始 SHA 与暂存 blob 相同。源码、测试、资源、构建配置与基线 Git 内容相同。main 仍为 a4dc887 且授权范围干净，三个自有合成进程已退出。

首次暂存差异检查将保留 CRLF 的原始 helper/manifest 行尾报告为尾空白；为该证据目录设置 cr-at-eol 后检查通过，没有重写日志、截图或测试结论。原始内容仍以清单摘要核对。没有放宽产品源码的空白检查。

下一步：本地提交 docs-only 证据并合并 main；在 main 新 profile 执行定向、全量、强制 buildSrc 与镜像审计，补记实际结果。

## CP4：main 复验与本地交付

证据提交 d9c5179，main 验收合并 79923f00bf899dccb7dd1de74606264c26e36631。新 profile 定向 3 suites / 33 passed / 0 skipped；clean test 307 suites / 3835 tests / 3832 passed / 0 failures/errors / 3 live skipped；强制 buildSrc 8/8；jpackageImage 与运行时审计通过。DataCube.exe / cfg / runtime modules 的分支与 main 摘要相同。

main 全量耗时 6m50s；一次只读测试 JVM 线程采样时 worker 在 JUnit 临时目录收尾，随后自然通过。未把这一采样当性能根因或修复证据，没有中断或删减用例。3 项 live skips 继续单列，不算通过。

最终归档 9 个执行记录、138 份证据文件，原始摘要及暂存 blob 检查通过。最终跟进提交只补 main 结果与交付记录，产品代码与已测 79923f0 完全一致。本轮局部原生补证本地交付；下一步是完整 AppShell 检索下游动作、SQL 极限小窗口原生滚动/键盘，其余真库/OS 多屏/签名/安装升级/CI/真实用户任务仍待验，不扩展外部权限，不称完成 M8 发布验收。
