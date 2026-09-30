# 字段检索关闭后的资源准入：本地验收账本

日期：2026-09-30。维护者“继续推进产品”的限定本地增量。基线 main `97b04f82891f4e5e95dfc95c7c598d76ccccf7a9`，独立分支 `codex/metadata-search-disposal`。分支定向/全量/buildSrc/镜像检查已完成，本地提交/合并及 main 复验仍待执行；下表只列实际记录。M8 外部验收仍待授权，不称发布验收。

## 改动和行为证据

关闭字段窗口后，读取与 JDBC 取消可能仍在执行。旧实现立即清空连接树的窗口引用，新的明确点击可以建立第二条读取连接。新增实际 AppShell 的 mock JDBC 回归，在 PostgreSQL/Oracle、读取或取消先完成的四种组合，全部复现连接峰值 2。

现在窗口及时关闭，连接树继续保留该检索的占用，直到读取、取消及 FX 关闭清理全部结束。Schema 的名称和字段检索菜单暂不可用，字段入口显示“等待读取结束”；资源释放只恢复入口，用户必须再次明确查询。Dialog 的完成通知只在 FX 线程完成，树的菜单属性也只在 FX 修改，没有等待/阻塞 FX。空闲关闭立即释放；取消异常、任务拒绝和读取 Error 都结算生命周期，Error 仍传播。

复用既有服务、固定 SQL、独占只读连接、目标/请求绑定和 SELECT/只读数据/DDL 路由，没有改写门禁、事务、配置失效订阅或全局线程池。README 更新等待与明确重试规则。

| 路径 | 新证据 | 关键断言 |
| --- | --- | --- |
| PG/Oracle 关闭后重开 × 两种任务结束顺序 | 实际 AppShell 合成 FX/JDBC，4 项 | 旧实现峰值 2；修复后峰值 1、菜单提示/禁用、任一任务未结束都拒绝再开、两项结束自动恢复但不读取；再次明确查找成功生成未执行 SELECT，开关平衡、写/执行 0 |
| 关闭时取消异常 × 两种完成顺序 | Dialog 程序化 FX，4 项 | 窗口先隐藏，单任务结束仍保留占用；两项结束后在 FX 完成，不发布旧结果或自动重试 |
| 空闲重复关闭、拒绝取消、致命读取错误、后台空闲关闭 | Dialog 程序化 FX，4 项 | 空闲零读取；拒绝取消仍等待读返回；Error 传播但可释放关闭窗口；FX 清理未完成时不提前完成 |
| 配置变化/改回、脱离/换根/关闭、只读与结果三动作、超时/条件变化 | 既有回归本轮重新运行 | 原身份与请求绑定、失效取消、迟到结果丢弃、原动作和后台边界保留 |
| 原生关闭/等待提示/再开与完整检索下游 | **本轮未验** | 不把程序化 fire、反射注入或旧截图算作本轮新原生证据 |

测试只用独占新 profile、@TempDir、mock provider/Statement 和固定合成对象。user.home 在创建 AppShell 存储前替换；live 环境从测试进程剔除。不读取/修改 .testagent，不访问真实连接、凭据、历史或业务文件；无 push/fetch/PR/tag/发布/安装更新/外部联系。

## 运行记录与失败历史

实际命令、任务是否运行、失败/跳过明细、日志/XML hash、源文件 Git blob 与产物摘要见 [实际结果](2026-09-30-metadata-search-disposal-results.json) 和 [证据清单](evidence/metadata-search-disposal/manifest.json)。分支测试 HEAD 是基线，包含工作区改动；initial-source-snapshot 只对应初版，source-snapshot 对应审查后的最终产品/测试源码。

| 记录 | 实际结果 | 说明 |
| --- | --- | --- |
| disposal-red | compileTestJava failed | MenuItem API 用错，没有运行 test，不能计通过或运行时复现 |
| disposal-red-runtime | 4 failed / 0 passed | 修正测试编译后，四种组合均 expected peak 1 / actual 2；保存该测试源码与完整失败 XML |
| disposal-green-routing | 4/4，0 skipped | 修复同四个实际连接重叠场景 |
| disposal-lifecycle | 94/94，0 skipped | 增加关闭/取消异常/拒绝/空闲状态检查 |
| branch-directed-final | 95/95，0 skipped | 加入 fatal Error 回归后的初版 |
| branch-full | 309 suites / 3890 tests：3887 passed、3 live skipped | 初版实际 cleanTest/test；不能替代之后新增 closeCleaned 的最终验证 |
| branch-directed-reviewed | 96/96，0 skipped | 新增后台空闲关闭和 FX 清理完成条件；最终源码 |
| branch-full-reviewed | 309 suites / 3891 tests：3888 passed、3 live skipped | 审查后最终源码，新 profile、实际 cleanTest/test，0 failure/error |
| branch-buildsrc | 8/8，0 skipped | --rerun-tasks，实际 buildSrc:test |
| branch-image / branch-image-audit | 实际 jpackageImage 成功，类/配置泄漏 0，connectCalls=0 | 打包运行时发现 PG/Oracle 驱动，无凭据或用户 profile |
| branch-staged-evidence-first | 审计失败 | *.log 被仓库 ignore，原始日志未进 index；提交前拦截，随后仅强制暂存本轮专用证据目录 |
| branch-staged-evidence-audit | 全部匹配 | raw archive/hash、日志/XML manifest 与 canonical source/staged blob 逐项一致 |

初版与审查后的两次定向日志 SHA 恰好相同（Gradle 文本输出一致），XML manifest 和测试数分别独立记录，不能按日志 SHA 合并两次运行。全部实际失败保留，不修改为成功；历史偶发 SchemaDiffServiceTest 原因不明仍未宣称已解决。

## 集成与待验

分支最终验证已通过，正在校核原始证据 hash、源码快照与 staged blob；随后本地提交/合并 main，使用新 profile 重新定向/全量/buildSrc/镜像。集成完成后在此追加实际 SHA 与结果，不用旧通过填充待执行记录。三项 live 跳过是 Redis 缺显式环境及 PG/Oracle Schema Diff 缺完整环境/写门禁；环境已在测试进程剔除。日志中的 JavaFX unnamed-module/native-access、unchecked、jlink JEP 493 警告保留。

本轮没有新增原生桌面证据。原生关闭后等待提示/重开、完整字段请求 → SELECT/只读数据/DDL、Oracle 桌面，真 PostgreSQL/Oracle/Redis 权限/事务/取消，OS 缩放/多屏/全键盘，正式启动器/安装升级/生产签名/远端 CI/真实用户任务/发布仍待验。M8 不称完成，本地增量交付不自动扩大范围。
