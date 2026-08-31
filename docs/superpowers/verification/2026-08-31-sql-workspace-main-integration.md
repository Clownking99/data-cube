# SQL 工作区恢复：main 整合记录

## 授权与范围

维护者在已获知桌面验收未完成及主目录重叠修改后，明确要求“合并并推送”。本轮仅整合已审查的P2分支并推送main，不新增功能、不打tag、不安装或发布。桌面鼠标/键盘、明暗主题及桌面重启仍未验收，不能把本次整合视为这些项目通过；远端CI结果也须单独确认。

此前[阶段验证记录](2026-08-31-sql-workspace-recovery.md)中的未合并/未授权描述是当时状态，以本记录的最新整合结果为准。

## 未提交文件保留

main的SqlDraftStore.java虽显示修改，但HEAD、索引、Git过滤后工作文件对象完全相同：`00b0315cd16757944cc8aaa784e8c52862104aae`。实际为混合换行，未发现代码语义差异。先复制原始文件至独占临时备份目录，核对两份SHA-256均为`C38FF179C1A0D93FF10B42EACA13D0D9675F7290F7394FF6326CFD4BD66496F5`，再仅刷新该路径索引；暂存diff为空，没有制造或丢弃源码提交。

未读取、修改、暂存或清理`.testagent/`内容。所有旧worktree、合成验收目录和日志保留。

## 合并前验证与审查

- main原HEAD：`7710ecb526d10a22e3fbff65367c50b04e44ed9d`。
- 功能分支：`codex/sql-workspace-recovery`，HEAD `3e793df2b06fbe00ff8eb8f2d358d7343bd6c70c`，包含25个main之后的提交。
- 已批准源码`e984c0c`到功能HEAD仅文档变化；生产/测试/构建差异为空。
- 本轮功能目录完整回归：`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，session78865，native exit0/1m36s。实际XML160suites、1560tests、1557passed、0failures/errors、3原有live skipped。
- 跳过仍仅Redis standalone及Oracle/PostgreSQL SchemaDiff，未启用真实数据库测试。JDK25，作用域内设置/恢复`JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`；原unchecked编译提示保留。
- 独立只读合并预检确认祖先关系、源码身份、两工作区跟踪文件干净和原文件备份，无Critical/Important/Minor整合发现；没有重复声称审查者运行了测试。

## main整合与推送检查

`git merge --ff-only codex/sql-workspace-recovery`成功，将main从7710ecb快进到3e793df，无冲突、无源码冲突修复。合并后仅原有`.testagent/`名称列为未跟踪。

main完整回归：在主目录执行`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，session55269 native exit0/1m31s，8任务全部执行。root实际汇总XML160suites、1560tests、1557passed、0failures/errors、3原有live skipped；跳过名称与合并前完全一致。JDK25与scoped非headless设置同上，旧unchecked提示保留。测试后再次核对源码/测试/构建配置相对3e793df无差异，只有本次整合文档变更；此文档提交不改变已经验证的产品代码。

远端配置为`origin=https://github.com/Clownking99/data-cube.git`，经本地7897代理刷新/核对后，远端main为151a64a，是合并前本地main的祖先（此前本地领先86提交）。按维护者要求同步既有main提交和P2提交；仅正常推送main，不强推、不推送tag。推送成功须以命令退出和远端引用核对为准，不由本地合并成功推断。

## 推送与CI后续核验

上轮正常推送native exit0，远端由151a64a更新至`c8c53aab443181b817fad0e75dac0cfa182e8694`；独立ls-remote与本地HEAD相同，ahead/behind为0/0。本轮再次核对该精确提交的[Verify运行33389841570](https://github.com/Clownking99/data-cube/actions/runs/33389841570)，event=push、status=completed、conclusion=success。

| 作业 | 结果与范围 |
| --- | --- |
| wrapper-validation，99480691011 | success，Gradle包装器校验步骤通过 |
| redis-integration，99480691195 | success，CI临时密码保护Redis的集成测试及清理步骤通过 |
| Test (ubuntu-latest)，99480691209 | success，Unit tests步骤通过；Windows镜像步骤在该平台按条件跳过 |
| Test (windows-latest)，99480691272 | success，Unit tests及Windows linked image均通过 |

查询使用只读GitHub CLI，经进程内7897代理后恢复环境；没有重跑CI、修改工作流、连接业务数据库或写入远端。没有下载XML来核对远端测试总数，因此不将本地1557通过数冒充远端精确计数。以上结论只覆盖c8c53aa，不能推及后续尚未推送的文档提交。桌面部分验收及剩余限制见[工作区验证记录](2026-08-31-sql-workspace-recovery.md)。
