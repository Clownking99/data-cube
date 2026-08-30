# Windows CI Path Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正安全导出测试对路径拼写的错误假设，使 Windows 短路径及普通规范路径均能验证真实文件发布契约。

**Architecture:** 保持 `SafeResultFilePublisher` 的父目录规范化、目标校验、原子发布和取消行为不变。仅把成功用例的输入变为明确的别名路径，并验证规范返回路径与文件身份；现有成功/取消效果断言全部保留。

**Tech Stack:** Java 25、JUnit Jupiter 5.11.3、Gradle wrapper、Windows 8.3 路径；无新依赖。

## Global Constraints

- 基线 `151a64a03ef66da44a88d72845484b89e46731d0`；父路线图为 `2026-08-30-product-continuity-roadmap.md`。
- `.testagent/` 不读取、不修改、不暂存；只操作本次明确文件和生成的临时数据。
- 不修改生产发布器、不放宽文件保护、不增加跳过、不删除成功/取消断言。
- 用户已授权独立 worktree 开发并在完成后本地合并回 `main`；不推送、tag 或触发 Release。
- `JAVA_TOOL_OPTIONS` 仅在子进程测试范围追加并恢复；不输出用户其他环境设置，不写全局配置。
- 8.3 短路径实验是 Windows 补充验证；普通测试中的 `.` 路径必须跨平台运行，不依赖符号链接权限或特定用户名。

---

## 已核实的根因证据

- 远端 [run 33303961545](https://github.com/Clownking99/data-cube/actions/runs/33303961545)，Windows job `99237088971`：1211 tests、1 failed、3 skipped；失败于 `successfulPublishAndCancellationHaveDifferentTerminalEffects` 原第 80 行。
- `SafeResultFilePublisher.capture` 先规范输入，再用 `parent.toRealPath()` 解析真实父目录；`publish` 返回捕获的规范路径。
- 原测试用 `assertEquals(target, published)` 比较输入路径和返回路径。`Path.equals` 比较路径表示，不能把 Windows 8.3 短名自动视为长名。
- 本地未改代码时设置短路径临时目录，强制执行原用例：1 test、1 failed，退出码 1，17 秒，同为第 80 行。
- XML 明确显示预期父目录 `DATACU~1.KMP`，实际父目录 `datacube-ci-path-probe-hejdab0m.kmp`；其后 JUnit 子目录和 `result.csv` 相同。
- GitHub 原运行没有上传测试报告，日志未展示其具体路径差值。因此本地实验明确证明此断言存在同型缺陷，不声称已拿到远端 XML；最终仍须同提交 CI 复验。

原用例已走到发布返回后的断言，没有发布异常。最小修复对象是测试契约，而非修改原子写入或取消实现。

## Task 1: 修正测试路径契约

**Files:**

- Modify: `test/com/datacube/export/SafeResultFilePublisherTest.java`，仅 `successfulPublishAndCancellationHaveDifferentTerminalEffects`。
- Read only: `src/com/datacube/export/SafeResultFilePublisher.java`、`src/com/datacube/export/ResultExportOperation.java`。
- Create: `docs/superpowers/verification/2026-08-30-windows-ci-path-contract.md`，记录实际执行结果，不预填通过。

**Interfaces:**

- Consumes: `SafeResultFilePublisher.capture(Path)`、`publish(Target, ResultExportOperation, TempWriter)`；现有注入式 `publisher()`。
- Produces: 不变的生产 API；增强后的现有 JUnit 回归，不新增产品测试接口。

- [x] **Step 1: 读取失败日志并复现现有断言。**

本轮使用新建独占临时目录，COM `Scripting.FileSystemObject.GetFolder(...).ShortPath` 得到其实际短路径，再运行：

```powershell
$ciPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$ciPreviousJavaOptions -Djava.io.tmpdir=C:\Users\hetia\AppData\Local\Temp\DATACU~1.KMP -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --tests 'com.datacube.export.SafeResultFilePublisherTest.successfulPublishAndCancellationHaveDifferentTerminalEffects' --rerun-tasks --no-daemon --console=plain
    $ciTestExit = $LASTEXITCODE
} finally {
    $env:JAVA_TOOL_OPTIONS = $ciPreviousJavaOptions
}
exit $ciTestExit
```

这是本轮证据命令，不是可跨机器复用的硬编码配置。后续重跑先确认该目录仍存在且短名映射未变；否则重新创建独占临时目录并获取真实短名，不猜测 `~1` 编号。

- [x] **Step 2: 确认隔离位置后，仅强化用例的输入以获得跨平台稳定 RED。**

将该用例第一行改为：

```java
Path target = directory.resolve(".").resolve("result.csv");
```

暂时保留原 `assertEquals(target, published)`。运行：

```powershell
.\gradlew.bat test --tests 'com.datacube.export.SafeResultFilePublisherTest.successfulPublishAndCancellationHaveDifferentTerminalEffects' --rerun-tasks --no-daemon --console=plain
```

预期：同一用例因输入包含 `.` 而返回规范路径，在相等断言失败；不是编译错误，也不能出现写入异常。

- [x] **Step 3: 最小修正断言，保留其他行为覆盖。**

该用例最终完整内容：

```java
@Test void successfulPublishAndCancellationHaveDifferentTerminalEffects() throws Exception {
    // A chosen path can contain aliases; publication returns the real parent path.
    Path target = directory.resolve(".").resolve("result.csv");
    var operation = new ResultExportOperation();
    Path published = publisher().publish(SafeResultFilePublisher.capture(target), operation,
            (path, token) -> Files.writeString(path, "new"));
    assertEquals(directory.toRealPath().resolve("result.csv"), published);
    assertTrue(Files.isSameFile(target, published));
    assertTrue(operation.published());
    assertFalse(operation.cancel());
    assertEquals("new", Files.readString(target));
    var cancelled = new ResultExportOperation();
    assertThrows(java.util.concurrent.CancellationException.class, () -> publisher().publish(
            SafeResultFilePublisher.capture(target), cancelled, (path, token) -> {
                Files.writeString(path, "partial");
                cancelled.cancel();
            }));
    assertEquals("new", Files.readString(target));
}
```

期望路径由测试目录与已知文件名独立构造，不用 `capture(target).path()` 作为自我验证。规范路径断言加实际文件身份断言，原成功状态、发布后不能取消、取消后旧内容保留断言不变。

- [x] **Step 4: 定向 GREEN。**

```powershell
.\gradlew.bat test --tests 'com.datacube.export.SafeResultFilePublisherTest' --rerun-tasks --no-daemon --console=plain
```

预期：失败/错误为零；若平台无法创建符号链接，报告该既有条件跳过，不能计作通过。随后在 Step 1 的短路径环境运行同一类，验证真实 Windows 别名问题消失。

- [x] **Step 5: 导出组合验证与全量验证。**

```powershell
.\gradlew.bat test --tests 'com.datacube.export.*' --tests 'com.datacube.fx.SqlResultExportCoordinatorTest' --rerun-tasks --no-daemon --console=plain
.\gradlew.bat clean test --no-daemon --console=plain
```

两条命令分开核对退出码，第一条失败时不继续把第二条结果当作覆盖。若本机默认 headless 导致 JavaFX 不可用，只临时追加 `-Djava.awt.headless=false` 并恢复环境；写入记录，不能悄悄跳过 UI 契约测试。

从本次 XML 汇总实际 suites/tests/failures/errors/skipped：

```powershell
$ciTotals = @{ Suites=0; Tests=0; Failures=0; Errors=0; Skipped=0 }
foreach ($ciReport in (Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml')) {
    [xml]$ciXml = Get-Content -LiteralPath $ciReport.FullName -Raw
    $ciTotals.Suites++
    $ciTotals.Tests += [int]$ciXml.testsuite.tests
    $ciTotals.Failures += [int]$ciXml.testsuite.failures
    $ciTotals.Errors += [int]$ciXml.testsuite.errors
    $ciTotals.Skipped += [int]$ciXml.testsuite.skipped
}
$ciTotals.Passed = $ciTotals.Tests - $ciTotals.Failures - $ciTotals.Errors - $ciTotals.Skipped
$ciTotals
```

不把历史 1208 passed 预填为本次结果，也不把 Redis/Oracle/PostgreSQL live 环境缺失的跳过算作通过。

- [x] **Step 6: 审查与本地交付。**（修复 `da3dee9`、验收文档补全 `40d51c6`，任务复审通过；整分支审查及合并后复验单独记录。）

检查差异仅涉及该用例与计划/验收文档；确认没有对生产源、`.testagent/` 或无关文件的更改。记录完整 RED/GREEN 命令、退出码、XML 断言差值与限制。按文件明确暂存，不执行 `git add .`。

```powershell
git diff --check
git diff -- test/com/datacube/export/SafeResultFilePublisherTest.java
```

隔离及本地交付方式确认、审查和验证通过后，可单独提交：

```powershell
git add -- test/com/datacube/export/SafeResultFilePublisherTest.java docs/superpowers/verification/2026-08-30-windows-ci-path-contract.md
git commit -m "test(export): handle canonical paths in publisher contract"
```

路线图与计划文档单独明确暂存，避免混入其他文件。本地提交不等于远端 CI 已修复。

## Task 2: 本地合并与远端验收（仅本地合并已授权）

- [x] 用户授权独立 worktree 开发，完成后本地合并回 `main`。
- [ ] 独立审查与本地验证完成后，核对 `main` 未发生冲突变更，执行本地合并并重跑合并后的测试；不删除用户文件或其他分支。
- [ ] 另获推送授权后，重新 fetch 并核对双方差异，不 force、不改变其他分支或 tags。
- [ ] 推送后读取新 SHA 的 Verify run；确认 Windows/Linux 单测、Redis 集成、wrapper validation 及 Windows jlink 成功。
- [ ] 失败则记录确切测试/步骤并继续诊断，不自动重跑直至偶然通过。
- [ ] 在验收文档加入 run 链接与对应 SHA，之后才把父路线图的 CI gate 标为完成；进入 P0.2 桌面与打包验收。

## 自审及执行状态

- 本计划只修正测试中路径表示与文件身份混淆，不修改用户可见导出行为。
- Windows 短路径的环境复现、跨平台 `.` 回归与最小修复已完成，修复提交 `da3dee9`；全量结果为 138 suites、1211 tests、1208 passed、3 skipped、0 failures/errors。
- 用户已确认独立 worktree，完成后本地合并回 `main`；普通路径对照运行 1 项通过、14 秒。修复与审查结果记录到独立验收文件。
- 完成条件同时要求本地证据和经授权取得的远端证据，不能因写完计划或测试数量不变而标记完成。
