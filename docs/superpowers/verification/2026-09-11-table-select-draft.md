# 表/视图 SELECT 草稿验收

基线 main `4ace548`；独立 `codex/table-select-draft`。设计见[SELECT 草稿](../specs/2026-09-11-table-select-draft.md)。
本轮开始时核实 [v3.2.4 发布](https://github.com/Clownking99/data-cube/actions/runs/34317059767)和
[main 验证](https://github.com/Clownking99/data-cube/actions/runs/34317059487)均为 completed / success；
这不是本轮未推送代码的 CI 结果。

## 要求与证据

使用 code-testing-agent 技能补齐单个产品增量的回归，重点检查转换结果、状态变化和副作用，不设测试数量目标。

| 要求 | 精确测试 |
| --- | --- |
| PG/Oracle 限定 SELECT，保留大小写、点号、前后空格、Unicode；引号和分号只作为标识符内容 | `TableSelectSqlTest.generatesQualifiedSelectPreservingIdentifierCaseSpacesDotsQuotesAndUnicode` |
| 拒绝 null/空名称、控制字符、Unicode 行/段分隔符与孤立代理项；不清洗成其他名称 | `refusesNamesThatCannotBePreservedWithoutCleaningOrTruncation` |
| 引用前长度 1,023 / 1,024 / 1,025，双引号转义不截断 | `identifierLimitIsCheckedBeforeEscapingWithoutTruncation` |
| 不支持的数据库和缺失表引用 | `rejectsUnsupportedDatabaseAndMissingTable` |
| 实际 TreeCell 表/视图菜单；当前选中另一个对象仍使用右键目标，保留查看数据入口、不调用浏览/执行动作 | `ConnectionTreeSelectSqlTest.actualCellMenuDispatchesOnlyTheClickedObjectRegardlessOfSelection` |
| 删除、刷新、替换节点值、关闭面板、状态行、错误所属连接、Redis 不触发新 SQL | `staleOrIneligibleMenuCannotOpenSql` |
| 完整标签事务、独立连接意图、无隐含 Schema 命令、编辑未保存标记、既有标签不被覆盖、恢复检查点正确关联连接；provider/session/metadata/network 均为 0 | `TableSelectSqlTabsTest.generatedTabKeepsExactPassiveConnectionAndQualifiedTextThroughEditingAndDraftCheckpoint` |
| 连接被删、类型或目标变更、Redis、非法对象名：构造编辑器之前拒绝 | `invalidOrStaleTargetCannotConstructAnEditorOrInstallATab` |
| 关闭中的标签注册表不再构造编辑器 | `closedTabRegistryRejectsBeforeConstructingThePassiveEditor` |
| 原文件连接选择、受管标签文件生命周期、连接树查找不退化 | `SqlFileConnectionTest` / `SqlTabFileLifecycleTest` / `ConnectionTreePaneFindTest` |

定向 50 项全部通过；首次修正后 17s，桌面 fixture 配置归一化后重跑 11s，均为 exit 0：

```powershell
.\gradlew.bat test --tests com.datacube.sqleditor.TableSelectSqlTest --tests com.datacube.fx.TableSelectSqlTabsTest --tests com.datacube.fx.ConnectionTreeSelectSqlTest --tests com.datacube.fx.SqlTabFileLifecycleTest --tests com.datacube.fx.SqlFileConnectionTest --tests com.datacube.fx.ConnectionTreePaneFindTest --no-daemon --console=plain
```

## 开发与审查记录

- 首轮定向 2 项失败：标签事务测试在 TabPane CSS/skin 建立前 lookup 编辑器，修正 fixture 的 CSS/layout 顺序后通过，未修改产品逻辑。
- 首次全量前手工桌面 fixture 编译失败：误用标签关闭枚举比较应用关闭结果。读取实际 `ShutdownOutcome` 后修正测试入口，不改变应用关闭流程。
- 桌面 fixture 使用 `ConnectionStore.loadAll()` 返回的归一化配置构造合成树，确保与真实 AppShell 加载到连接注册表的配置一致；不放松产品中的连接目标相等检查。
- 手工入口首次启动在 SplitPane skin 建立前查找连接树，导致空指针并退出；将 Scene、CSS/layout 初始化前置后正常启动。该修正仅涉及 test 入口，`compileTestJava` exit 0，6s。
- SELECT 生成器是纯文本转换，无 provider、网络或文件依赖。生成路径复用被动脚本连接，而非会创建会话对象的普通绑定编辑器；始终新开标签，不借用活动连接。
- 新模板沿用历史载入的干净基线规则，原文件/草稿格式、执行准入和事务规则不变。只在显式编辑后出现未保存标记；本地恢复检查点可保存生成的文本。
- UI 验收需要合成已加载对象：`TableSelectSqlDesktopFixture` 仅在 test 源集，通过明确命名的隔离 profile 启动真实 AppShell，再替换内存树节点；不加载真实数据库，不加入 src 或正式发布镜像。
- `.testagent/` 未读取、修改或暂存；本轮不推送、不打 tag、不发布。

## 全量与桌面

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- 为 jlink/jpackage 配置独立 ASCII 短路径 `java.io.tmpdir`；构建 exit 0，1m 59s。
- JUnit XML 汇总 1,932 项：1,929 通过、0 失败、0 错误、3 项既有 live 测试跳过；未连接真实数据库。
- 收尾再次执行 `.\gradlew.bat test --no-daemon --console=plain`，exit 0，1m 29s，XML 汇总相同；之后仅调整手工入口的 Scene 初始化顺序并重新编译。
- 原始 `build/jpackage/DataCube` 为正常开发镜像；复制到 `build/desktop-fixture-image/DataCube` 后，仅在副本中通过 `--patch-module` 加入手工 fixture，并指定 `build/select-desktop-profile`。正式源码和原始镜像不包含手工启动入口。
- `jimage list` 核实原始镜像包含 `TableSelectSql` / `TableSelectSqlTabs`，没有 `DesktopFixture` / `DraftConnectionProbe`；原始 cfg 仍为 `com.datacube/com.datacube.DataCubeFx`，无隔离 profile 或 patch-module。

## 桌面实测

Computer Use 首次启动返回 `GetCursorPos failed: 拒绝访问。 (0x80070005)`；暂停并请求解锁。
用户确认桌面解锁后恢复验收，入口布局问题修正后成功启动。所有操作只针对返回的唯一
`DataCube - SELECT 合成验收` 窗口，未加载真实保存连接、执行 SQL、切换事务或安装软件。

1. 暗色下右键 PostgreSQL 合成表 `Order"Line`，保留“查看数据”等旧菜单，并显示“生成 SELECT 到新 SQL（不执行）”。点击后新建命名标签，文本为 `SELECT *` 换行 `FROM " Sales "."Order""Line";`；Schema 输入为空，显示 Demo PostgreSQL、目标已选择但尚未连接、尚未创建专用会话、暂无结果。
2. 右键 Oracle 合成视图 `Monthly View`，同样生成新标签，文本为 `SELECT *` 换行 `FROM "REPORTING"."Monthly View";`，连接为 Demo Oracle；PostgreSQL 标签仍保留。返回第一个标签后原 SQL 和 PostgreSQL 连接标识不变。
3. 切换亮色，将 SQL 内容区域收窄至约 480px；生成 SQL、保存/另存为、执行、查找和连接切换入口可见，工具栏按行排列。
4. 在 Oracle SQL 末尾添加换行和 `-- reviewed`，行列状态更新，标题出现未保存星号；恢复检查点显示已保存，不产生结果。
5. 点击“保存 SQL”打开原生保存弹窗，默认 `query.sql`、类型 `SQL 文件 (*.sql)`；取消后保留全部文本和未保存标记，没有保存到 `.sql` 文件。取消时一次无障碍索引失效，刷新后通过可见取消按钮完成，未重复提交保存。
6. 关闭隔离窗口，复查已退出。隔离 profile 中保留两份草稿和工作区；SQL 历史仅在退出后出现，属于既有编辑器关闭保存行为，不是生成动作执行了查询（关闭前仅有连接、设置与草稿目录）。

上述为真实 AppShell 配合合成已加载节点的桌面验收，不是对真实数据库元数据或查询执行的验证。
