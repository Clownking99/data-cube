# 名称查找物理读取准入：检查点

## C0 — 基线与范围

- 当前目标：名称查找关闭或请求取消后，在旧读取实际返回前，重开名称/字段入口不能取得新资源；窗口内重复读取也不能重叠，等待明确且不自动重试。
- 基线：main 918d7198cb7ec07d509bbce024dd442972889f6b，授权范围干净；复用干净独立 worktree，新建 codex/schema-object-admission。没有活跃 goal，不创建预算/目标。
- 改动：先补 mock 名称读取不立即响应中断的真实 AppShell 回归；复用现有服务及查询，不扩展数据库或全局线程池。
- 验证：源码显示对象窗口 finally 立即清空 objectSearch，Future.cancel/done 早于物理工作返回，reload 直接取消并创建下一任务；字段入口在名称读取中也可打开。当前为代码发现，尚无本轮实际运行证据；旧 3888 passed 不算新验证。
- 失败/未验：原生模态输入限制不重复尝试；本轮原生/真库/发布尚无新证据，M8 不称完成。
- 下一步：红灯证实重叠后，以局部读取所有权跟踪覆盖运行中/排队取消、关闭清理、异常/拒绝、重复读取及内嵌字段入口；定向/全量/buildSrc/jpackageImage/审查、本地提交/合并/main 复验后交付。

沿用用户边界：不读取/修改 .testagent，不访问真实连接、凭据、SQL 历史或业务文件；只用 mock、固定合成 profile 和独占临时目录。无 push/fetch/PR/tag/发布/安装更新/外部联系。常规决定自主记录，不自动扩大范围。

## C1 — 实际复现、修复和定向验证

- 当前目标：用实际 AppShell 资源计数证明名称窗口关闭后的占用；保留物理结束而非 Future.done 的准入。
- 改动：局部 Pending 原子状态区分排队/运行/返回，关闭取消未开始任务后保证其不会调用 loader；运行任务仅在 loader finally 结束后通知。关闭清理与完成通知都在 FX，不等待 JDBC。树保留 objectSearch 到 disposal；两个 Schema 菜单显示等待并禁用。名称窗口重复 reload 和字段按钮均有逻辑拒绝。README 更新等待/重试规则；无服务/线程池/事务/写安全改动。
- 验证：name-red-runtime 实际 4/4 失败，PG/Oracle × 重开名称/字段入口峰值 2；同四项 name-green-routing 4/4 通过，峰值 1、取消到达但 closes=0、释放后只恢复、明确重试成功且资源平衡。name-lifecycle 173/173 通过；增加提交时关闭和后台空闲清理后，branch-directed 9 suites / 180 tests 全通过、0 skipped。所有旧筛选/复制/晚回调/只读/三动作和服务限制本轮实际重验。
- 失败/未验：红灯原源码与日志/XML保留；没有本轮新原生/真库证据，不重复已知工具输入失败。旧“程序化 reload 可替换未结束请求”的测试改为重复请求被拒绝，再在旧请求完成后启动新请求并保留迟到回调拒绝；未取消 late-result 断言。
- 下一步：最终源码五项 raw SHA/canonical blob 已固定，本地审查记录 branch-review；全量正在新独占 profile 执行，完成后强制 buildSrc、jpackageImage/零连接审计和证据暂存校核，再本地提交/合并/main 新验证。

## C2 — 分支验证齐全与集成前检查

- 当前目标：以最终源码和新证据通过本地工程门槛。
- 改动：产品/测试自 C1 最终快照未变；本轮账本映射精确测试名，raw archive/校核脚本限定本轮目录；沿用原始证据 -text 属性，并提前限定 -f 暂存已被 *.log 忽略的原始日志。
- 验证：branch-directed 9 suites / 180 passed、0 skipped；branch-full 309 suites / 3901 tests：3898 passed、3 live skipped、0 failure/error；强制 branch-buildsrc 8/8；实际 branch-image jpackageImage 成功；branch-image-audit 类/配置泄漏 0，PG/Oracle 驱动发现，connectCalls=0。main 仍是基线且授权范围干净。本地审查检查排队 claim、未来 Future 赋值、实际返回/FX 清理、回调 revision、内嵌入口、原只读/事务/路由。
- 失败/未验：只有已保留的四项实际红灯；三项 live 缺显式环境/写门禁且被测试进程剔除，不计通过。没有新原生/真库/外部发布证据，历史偶发测试根因未证实关闭；警告保留日志。
- 下一步：校核 raw manifest、日志/XML清单、五项 source/index blob 后限定提交；main --no-ff 合并，重新定向/全量/buildSrc/镜像/产物对比，再更新实际 SHA 和待验项后交付。
