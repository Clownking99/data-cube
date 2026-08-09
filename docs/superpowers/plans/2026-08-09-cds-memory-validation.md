# CDS Memory Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Windows jlink 镜像提供可重复的 CDS 开关与多样本内存测量，并记录 JDK 25 静态 CDS 未达到默认启用门槛的实验结论。

**Architecture:** 默认启动器保持 G1 256MB 和 JVM 默认 `-Xshare:auto`，不随包生成无收益的 CDS 归档。`measure-memory.ps1` 增加显式 `auto/on/off` 与样本次数参数，每个样本仍只关闭自身启动的进程；验证文档记录归档可复现性、启动时间和内存对照结果。

**Tech Stack:** JDK 25, JavaFX 25, Gradle/jlink, Windows PowerShell.

## Global Constraints

- 直接在 `main` 分支推进。
- Windows 为主但保留跨平台运行。
- 保留 G1 256MB 平衡模式。
- CDS 只有在 jlink/jpackage、GUI/CLI、构建可复现性和性能收益均通过时才默认启用。
- 不修改或提交用户持有的 `.testagent/`。

---

### Task 1: 可重复 CDS/内存采样工具

**Files:**
- Modify: `tools/measure-memory.ps1`
- Modify: `README.md`
- Create: `docs/performance/2026-08-09-jdk25-cds-validation.md`

**Interfaces:**
- Consumes: `build/image/bin/javaw.exe`、`-Xshare:auto|on|off`、现有 G1 256MB JVM 参数。
- Produces: `measure-memory.ps1 -CdsMode <auto|on|off> -Samples <1..20>`，输出每次样本的 PID、CDS 模式、JVM 参数、工作集、私有内存和线程数。

- [ ] **Step 1: 验证新参数 RED**

Run:

```powershell
.\tools\measure-memory.ps1 -CdsMode off -Samples 2 -WarmupSeconds 1 -ExpectedMaxWorkingSetMB 1000
```

Expected: FAIL，提示找不到 `CdsMode` 或 `Samples` 参数。

- [ ] **Step 2: 添加参数并逐样本测量**

在参数块增加：

```powershell
[ValidateRange(1, 20)]
[int]$Samples = 1,
[ValidateSet('auto', 'on', 'off')]
[string]$CdsMode = 'auto'
```

将 `-Xshare:$CdsMode` 放在 `$vmArgs` 首项；`on` 模式在启动前检查 JDK 25 镜像的
`bin/server/classes.jsa`，缺失时提示先执行 `-Xshare:dump`。循环 `1..$Samples`，每次生成包含以下字段的对象并输出：

```text
Sample
Pid
ElapsedSeconds
CdsMode
VmArgs
WorkingSetMB
PrivateMB
Threads
```

每次启动都保留现有 `try/finally`，只按当前 PID 关闭进程。CDS 模式在传给 JVM 前规范化为
小写；全部样本完成后，以未舍入的最大工作集字节数执行现有阈值检查，MB 舍入值只用于展示。

- [ ] **Step 3: 验证参数 GREEN 与进程清理**

Run:

```powershell
.\tools\measure-memory.ps1 -CdsMode off -Samples 2 -WarmupSeconds 1 -ExpectedMaxWorkingSetMB 1000
```

Expected: exit 0；输出两个样本、`CdsMode = off`、`VmArgs` 含 `-Xshare:off`；两个输出 PID 均已退出。

- [ ] **Step 4: 验证参数约束与阈值失败仍清理进程**

Run:

```powershell
.\tools\measure-memory.ps1 -CdsMode invalid -WarmupSeconds 1
.\tools\measure-memory.ps1 -CdsMode off -WarmupSeconds 1 -ExpectedMaxWorkingSetMB 1
```

Expected: 两条命令均失败；第一条在启动 JVM 前被参数校验拒绝，第二条报告超出 1MB 且输出 PID 已退出。

- [ ] **Step 5: 记录 CDS 实验与使用方式**

`README.md` 增加多样本与 CDS 对照示例。验证文档记录：静态归档 14,680,064 bytes；连续生成 SHA-256 一致；`-Xshare:on` 可在 G1/256MB 下启动；六次热启动均值 on 1054ms、off 1068ms；两次 12 秒空闲均值 on 163.0MB/off 162.4MB，未达到默认启用门槛。

- [ ] **Step 6: 完整回归与提交**

Run:

```powershell
.\gradlew.bat clean test jlink --warning-mode fail --rerun-tasks
codegraph sync
git diff --check
```

Expected: Gradle 成功、全部测试通过、jlink 镜像成功、CodeGraph 已同步、差异检查通过；新生成镜像不包含 `classes.jsa`。

```powershell
git add -- tools/measure-memory.ps1 README.md docs/performance/2026-08-09-jdk25-cds-validation.md docs/superpowers/plans/2026-08-09-cds-memory-validation.md
git commit -m "perf: 完善 CDS 内存测量"
```
