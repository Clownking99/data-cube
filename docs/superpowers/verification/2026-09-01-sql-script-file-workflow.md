# SQL 脚本文件工作流验证记录

## 范围与实现

本增量提供顶部“SQL 文件”惰性菜单、打开与最近路径、清空最近路径，以及可改绑的
`Ctrl+O`、`Ctrl+S`、`Ctrl+Shift+S` 默认动作。打开路径由应用级虚拟线程任务作用域读取；
只有 `SqlScriptFileStore.load` 成功后才在 FX 线程创建无连接的 `SqlEditorPane`，并在真实
受管 `Tab` 创建后安装已有的文件控制器。最近路径在标签成功创建后提交到同一应用任务运行器。

打开不设置活动连接、不请求 provider/元数据/JDBC 会话，也不执行 SQL。文件标题仍由每个编辑器
自己的 `SqlScriptFileController` 管理；全局保存快捷键只查找当前选中的文件 SQL 标签中的既有保存按钮，因此
仍服从该编辑器的 busy、保存和关闭生命周期。

## TDD 证据

先新增 `test/com/datacube/fx/SqlScriptFileEntryTest.java`，未改生产代码时运行：

```powershell
.\gradlew.bat test --tests '*SqlScriptFileEntryTest' --tests '*SqlAutoCompleteFocusTest' --tests '*SqlEditorConnectionGuidanceTest' --no-daemon --console=plain
```

结果为预期 RED：`compileTestJava` 失败，`ShortcutAction.SQL_OPEN_FILE`、
`SQL_SAVE_FILE` 和 `SQL_SAVE_AS` 均不存在。随后为始终存在、空列表时禁用的“清空最近文件”
动作加入断言；该单类命令确定性 RED，`SqlScriptFileEntryTest.appShellBuildsLazySqlFileMenuWithStableActionIds`
在第 36 行失败。

完成最小实现后，重新运行第一条聚焦命令，16 项通过；并运行：

```powershell
.\gradlew.bat test --tests '*AppShellTest' --tests '*SqlScriptFileEntryTest' --no-daemon --console=plain
```

结果 `BUILD SUCCESSFUL`，6 项通过。完整回归前发现既有 `AppShellTest` 依赖私有
`openSqlTab` 的 `void` 源码契约；将 AppShell 内部 post-open helper 分离为
`openSqlTabAfterOpen` 后，以上回归恢复通过，未改该既有测试文件。

## 完整回归与打包

执行：

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
```

清理后的 JUnit XML 共 167 个文件：1,620 tests、0 failures、0 errors、3 skipped（既有 live
tests）。随后再次运行 `./gradlew.bat test --no-daemon --console=plain`，输出
`BUILD SUCCESSFUL in 5s`。

执行：

```powershell
.\gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain
```

结果 `BUILD SUCCESSFUL in 41s`，14 个任务均实际执行。构建输出中的 JEP 493 工具链提示不是失败：
`java.base module not found ... assuming the used Java toolchain has enabled JEP 493`。

已检查 `build/jpackage/DataCube/`：`app/DataCube.cfg` 指向
`com.datacube/com.datacube.DataCubeFx`；运行时镜像的 `com.datacube` 模块有 706 entries、
693 class entries，包含 `AppShell`、`RecentSqlFiles`、`ShortcutAction`、
`SqlScriptFileController`、`SqlScriptFileStore` 和 `SqlScriptDocument`。按 `Test.class` 与
`testagent` 精确匹配为 0；`ConnectionTestController` 是已有生产类名，不是测试 helper。

## 未验证项与边界

未启动 installer，也未读取真实用户 profile、SQL、凭据、数据库或网络。未做实际 app-image
桌面交互：打包入口 `DataCubeFx` 在显示主窗口后调用更新检查，运行会引入本任务禁止的网络路径；
因此没有以静态检查替代桌面打开、FileChooser、Alert 或快捷键焦点交互的验收。若后续在明确离线
入口或网络隔离条件下运行，只能使用新建的合成隔离 profile，且仍不得访问数据库。

本记录不包含用户试用、完成率、耗时、下载、CI、安装器或发布证据。

## 第六轮复审修复后的最终验证

跨 chunk 编辑连接的两侧是独立逻辑片段：若左侧以裸 CR 结束、右侧以 LF 开始，它们表示两个
逻辑分隔符，不能序列化成单个 CRLF。缓冲区因此使用最小的无歧义 `CR + CRLF` 物理编码；保存后
重新加载仍保留两个编辑器行分隔符。每次 replace 从左右各抽取至多两个相邻叶，并将这个有限窗口
均匀重分块；既维持 CRLF 不跨叶，也避免非尾部编辑把 1 字符尾叶不断累积。

回归以 CodeArea 的期望逻辑文本为准，覆盖 `a\rb\nc` 删除 `b` 的 positional 与 whole replacement、
插入、所有 CR/LF/CRLF 两侧组合、4 KiB 边界、UTF-16、controller 保存/重新加载、10,000 次单字符
输入及删除的叶数/高度/最大块长断言，以及 8 MiB 增量编辑的零全文物化指标。另以固定 seed=42
在空文档随机合法位置插入 10,000 个字符，实测为 3 个叶、0 个小于 2 KiB 的叶、最大 3,334 字符、
树高 2；4096 字符初始文本前插 10,000 次实测为 5 个叶、0 个小于 2 KiB 的叶、最大 2,952 字符、树高 4。

文件标签失败路径直接调用 AppShell 的生产 opener seam：在真实 `SqlEditorPane` 的 controller 安装、
draft bind/installed 后注入异常。断言标签和 selection 未发布，真实 session、controller 与 draft
监听均已失效，后台 resource phase 完成；临时移除生产 `finalizeCloseOnFx` 时该测试在 draft
解绑断言处失败，随后已恢复实现。

运行：

```powershell
.\gradlew.bat test --tests '*SqlScriptDocumentTest' --tests '*SqlScriptFileControllerTest' --tests '*SqlScriptFileEntryTest' --tests '*ContentTabPane*Test' --tests '*ManagedTabFactorySequenceTest' --tests '*AppShellTest' --tests '*SqlEditorSessionContractTest' --no-daemon --console=plain
.\gradlew.bat clean test --no-daemon --console=plain
.\gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain
```

聚焦命令为 `BUILD SUCCESSFUL`：9 个 XML、72 tests、0 failures、0 errors、0 skipped。清理后的
完整回归为 167 个 XML、1,639 tests、0 failures、0 errors、3 skipped。镜像
`build/jpackage/DataCube` 存在且 `DataCube.exe` 可见；`jimage list` 显示 719 个
`com/datacube` 条目（707 个 class 条目），`com/datacube/.*Test.class` 和 `testagent` 精确匹配均为 0。

最终提交通过 `git commit --amend --no-edit` 生成；提交后执行 `git rev-parse --verify HEAD` 记录该次
验证对应的当前 HEAD，而不是把 amend 前 SHA 写入本文。未启动镜像，桌面交互仍未验证（避免入口
更新检查的网络路径）。

## 最终标签工作流修复（2026-09-02）

`AppShell` 现在拥有一个仅在 FX 线程访问的 `SqlFileTabRegistry`。规范路径的已提交绑定与另存为
临时声明都由不透明 owner token 管理；重复打开（含 store 解析后的别名）只选中已有 dirty 标签，
不会再创建 pane、隔离 session、draft binding、controller 或第二个 recent callback。另存为在保留
A 的同时声明 B：owner 碰撞不确认覆盖、不写磁盘；取消、capture/store 失败和关闭撤销 B；成功只在
FX settlement 原子提交 A→B。应用 shutdown 会先关闭入口和 registry，标签 finalizer 的重复 release
保持幂等。

普通、历史和恢复草稿标签都会在初始文本装入后安装 `initial=null` 的文件控制器，因此第一次
`Ctrl+S` 进入另存为；历史文本是干净基线，恢复标签继续保留原 draft handle。历史打开不再读取保存
连接或构建 JDBC session。`SqlDraft`/`SqlWorkspace` 持久化 record 没有 `Path`/path component，文件
路径仍只存在于文件 document/registry/recent index。`RecordAdmission` 在 load/save 获准时捕获，
之后成功 clear 会淘汰迟到的 recent 写回。

### 本轮 TDD 证据

按行为分组先写测试并观察预期 RED：

- `SqlFileTabRegistryTest` 首次运行在 `compileTestJava` 产生 15 个 missing-symbol 错误；最小 registry
  实现后 focused GREEN。
- duplicate-open/alias 测试先分别因缺少 registry-aware `SqlFileEntry` 构造器和
  `openLoadedSqlFile(..., registry)` overload 编译失败；实现 admission gate 与受管标签绑定后 GREEN。
- Save As/recent 测试先因缺少 controller 的 `(beforeSettlement, registry, owner)` seam 编译失败；实现
  claim/rollback/commit 和 admission token 后 controller 全类 GREEN。
- ordinary/history/recovered 测试先因缺少 `openSqlTab` 和 `SqlDraftRecoveryTabs` 文件生命周期 overload
  产生 3 个编译错误；实现后通过。注入 text-unsubscribe 异常的 finalizer 测试随后在 draft 未解绑处
  RED；逐项 `BestEffortCloseSequence` 后 GREEN。另一个 RED 证明 partial-close 首因必须保留原始反射
  异常，而不是嵌套 aggregate。
- shutdown registry 测试先在缺少 `sqlFileTabs.close()` 处 RED，加入 FX shutdown release 后 GREEN。

focused 运行覆盖 registry、entry、controller、普通/历史/恢复、draft/workspace recovery、managed-tab
lifecycle、finalizer、recent、store 和 document；扩展的 13 个最终相关 XML 为 222 tests、0 failures、
0 errors、0 skipped。

### fresh 完整回归

最终重新执行：

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
```

结果 `BUILD SUCCESSFUL in 1m 42s`。从 fresh `build/test-results/test/TEST-*.xml` 聚合得到 171 个
文件、1,723 tests、0 failures、0 errors、3 skipped。

前一次 clean run 曾出现两个失败：一个是既有 source-text 测试只接受
`pane -> binding.bind(pane::closeResources)` 的具体拼写，已改为检查同一真实 abort-binding contract；
另一个未改动的 `SchemaDiffServiceTest.providerAwareCompareReturnsOtherObjectsWhenOneRoutineRequiresManualReview`
在全量并行时一次性报告 `Schema snapshot failed`，该方法立即独立复现通过，第二次 fresh 全量也通过。
未修改无关 Schema Diff 生产代码。

本轮仍未启动应用镜像、installer、真实用户 profile、数据库或网络；FileChooser/Alert 的真实桌面点击
不在自动化证据内。
