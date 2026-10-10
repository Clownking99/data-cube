# Redis 二进制键修复：main 验收

维护者截图中的错误来自键列表强制把原始键转换为UTF-8文本。修复已以c33b9ef45c680b86976919b68b81d4d61b9230c7提交，证据为207782399ec41fb61f39cf30eb4416d8f28a6746，合并并受验main为**af3fe27369973c2d0254e712968f5984b8dc2b86**。不可变原始字节身份贯穿分页、树节点、选择绑定和全部键命令；文本、二进制及空键显示分离，显示截断不会改变命令目标。

## 实际验证

root独立审查开发源码、原始证据和Git运输后，在main新UUID合成环境重新运行完整五阶段。每阶段878输入、410测试类型、12工具一致，实际命令退出0、根退出证明与Job/EOF结算均核对。

| 阶段 | main 本轮结果 |
| --- | --- |
| 定向 | 304项：303通过、1指定live跳过、0失败/错误 |
| clean 全量 | 4736项：4733通过、3指定live跳过、0失败/错误 |
| 强制 buildSrc | 8通过，0跳过/失败/错误 |
| jpackageImage | 实际构建通过；183文件、26090类；包含RedisKey.class，无410测试类型泄漏 |
| linked | 四条外置命令全部通过；驱动connectCalls=0，合成Redis realServices=0、socketsSettled=true，镜像前后哈希一致 |

定向和全量均核对当前源码的精确方法集：RedisBinaryKeyTest 6、RedisBinaryKeySessionTest 2、RedisPaneBudgetTest 14、AppShellWorkspaceShutdownTest 5。覆盖原字节防御复制、混合页、空/控制/非法UTF-8键、显示碰撞、完整复制投影、27个typed键命令、五类型原生编辑、旧绑定/延迟回调、候选回滚及实际在途保存顺序。系统剪贴板未读写，原生测试没有跳过。

开发阶段70项具名路径边界、98项共享控制和实际同根冲突拒绝已由root独立接受。main使用同12工具的已验收控制证据，**未重跑98项**；main上述五工程阶段全部新跑。不是复用开发XML或历史通过。

## 原件与差异

- [五阶段独立审核](evidence/redis-binary-p3-review-20261010/engineering-review.json)、[精确用例审核](evidence/redis-binary-p3-review-20261010/case-review.json)、[实际阶段定位](evidence/redis-binary-p3-703d50245ffb4b799a3616f70ce0ba39-package/progress.json)。
- [1200原件封存](evidence/redis-binary-p3-703d50245ffb4b799a3616f70ce0ba39-frozen/manifest.json)：13根、21986312字节，SHA-256 **e3f7ec2cfd9bf7fe2de0df67454ffdcac28be8046d8c66e798961c7944c4438a**。包含P2独立审核、main入口/命令/退出/XML/镜像清单和P3独立审核；不复制用户或临时运行时数据。
- 16源码与3077开发证据文件的worker/Git字节已核对；3077证据在main仍原字节一致。main与worker的256文本换行差异全部与当前Git规范内容一致，并绑定双方精确SHA后才运行main；工具字节完全相同。详见[运输审核](evidence/redis-binary-p3-review-20261010/transport-main.json)、[换行诊断](evidence/redis-binary-p3-review-20261010/input-newline-diagnostic.json)。
- 开发首轮全量未开始：定向暴露已有Fixture.seed保存竞争BUSY；同字节诊断绿跑未接受。最终以协调器等待实际在途和seed发布、恢复活动，并加latch回归后重新完整工程。首失败和诊断原件保留。[分阶段计划与裁决](../plans/2026-10-10-redis-binary-key-fix.md)、[开发报告](2026-10-10-redis-binary-key-worker.md)。审核器SHA抄写和main入口换行预期错误均另留记录，没有修改旧原件。

## 交付与待验

本地验收通过。首次文档提交3af30f12的Verify 38034885345整体失败：Windows在checkout遇到证据路径过长，Java/tests尚未开始；Linux、Redis集成、wrapper校验成功不抵消这一失败。[首失败结果与完整日志](evidence/redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci/first-run.json)保留。

后续只给Verify和Release两处Windows checkout增加步骤级`core.longpaths`配置，Linux矩阵COUNT=0，不改全局配置、产品、测试、任务、权限或发布触发条件。独占合成仓库中，同一387字符路径、同一commit和blob，关闭时退出128并报Filename too long，开启后退出0且68原字节一致；root逐项审核41原件及9个实际Git命令。[独立补正审查](evidence/redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci/review.json)。

本次补正不重跑未改变的本地Java工程；已有本地五阶段仍只对应其实际受验输入。新的878输入中精确记录两个workflow前后哈希，其余876及12工具完全不变，不能称全部878原字节未变。新的最终SHA、远端main、相同SHA Verify四任务和原始日志以[CI补正后交付入口](evidence/redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci/delivery-intent.json)定位的`delivery-result.json`为准。只有新回执`passed=true`才表示远端交付完成；未触发Release来验证发布步骤。

截图中的已安装版本未独立确认，其真实连接没有访问；真实Redis/数据库复测、完整人工桌面及发布验收仍未完成。测试使用mock、合成profile、专用UUID目录与受控本机helper。`.testagent`和原有凭据、配置、SQL历史、业务数据未读取；不fetch/tag/PR/发布/安装更新。v3.2.10和v3.2.9保持不动，不自动扩展下一轮。
