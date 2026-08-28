# Task 1 验证记录：空工作区开始引导

日期：2026-08-28。范围仅限连接到查询体验计划的任务 1，不代表整个阶段完成。

## 实施范围

- 基线提交：`e236674`；本地分支：`codex/connection-onboarding`。当前目录内顺序执行，未创建 worktree 或启动子代理。
- 新增 `WorkspaceStartPane`，通过 `AppShell` 组合；不加入受管标签集合，不替换原 TabPane。
- `ContentTabPane` 仅暴露只读空状态；`ConnectionTreePane.focusConnections()` 仅请求焦点。
- 开始提示包含新建连接、选择已有连接两个入口，以及 PostgreSQL / Oracle 和 Redis 的使用说明。
- 未修改数据库协议、配置格式、凭据、版本及发布流程；未读取、修改或暂存既有 `.testagent/`；未推送或打标签。

## 自动化证据

以下测试均在任务 1 实施轮实际运行；离线使用已有依赖，无新增依赖。后续桌面补验仅更新文档，未修改源码，也未重复运行这些测试。

| 阶段 | 命令 / 操作 | 结果 |
| --- | --- | --- |
| 基线重跑 | `./gradlew.bat test --rerun-tasks --offline --no-daemon --console=plain` | 111 套件，785 项，0 失败，0 错误，3 跳过 |
| TDD 红灯 | 先新增测试，再运行 `./gradlew.bat test --tests 'com.datacube.fx.WorkspaceStartPaneTest' --offline --no-daemon --console=plain` | 实现前因 4 个待实现 API 缺失出现 11 个编译错误 |
| 新增测试绿灯 | 同上 | 7 项，0 失败、错误或跳过 |
| 反向变更检查 | 暂时反转开始页 visible 绑定，仅运行 `emptyOpenAndCloseKeepTheSameTabNode` | 1 项，1 失败；随后恢复正确绑定，再运行回归 |
| 标签保护回归 | `./gradlew.bat test --tests 'com.datacube.fx.WorkspaceStartPaneTest' --tests 'com.datacube.fx.ContentTabPane*' --tests 'com.datacube.fx.Managed*' --offline --no-daemon --console=plain` | 12 套件，43 项，0 失败、错误或跳过 |
| 全量回归 | `./gradlew.bat clean test --offline --no-daemon --console=plain` | 112 套件，792 项，0 失败，0 错误，3 跳过；退出码 0 |
| 差异检查 | `git diff --check` | 通过 |

全量测试的 3 项跳过来自 `RedisLiveIntegrationTest`（1）与 `SchemaDiffLiveIntegrationTest`（2），新增 7 项均实际执行。XML 报告位于忽略目录 `build/test-results/test/`，后续 clean 会清除，不作为克隆仓库后的持久证据。

## 行为与测试映射

测试均位于 `test/com/datacube/fx/WorkspaceStartPaneTest.java`。

| Requirement | Evidence |
| --- | --- |
| 初始显示；打开内容隐藏；关闭最后一页恢复；保留同一 TabPane | `emptyOpenAndCloseKeepTheSameTabNode` |
| 不在构造时触发动作，点击只触发对应回调 | `actionsDoNothingUntilClickedAndInvokeOnlyTheirCallback` |
| 常驻页存在时不显示开始提示 | `permanentTabKeepsTheStartPaneHidden` |
| 关闭仍在等待时保留内容，仅批准后恢复开始提示 | `pendingManagedCloseKeepsContentUntilApproved` |
| 拒绝关闭时保留内容，不触发终结清理 | `rejectedManagedCloseDoesNotReplaceTheContent` |
| 空树和已保存连接树仅获焦点，不选中、不展开、不建连 | `focusConnectionsNeverSelectsExpandsOrConnects(false)`、`focusConnectionsNeverSelectsExpandsOrConnects(true)` |

相对原计划扩充了等待关闭、常驻页和两种树状态测试；测试使用当前非弃用的受管标签工厂。测试等待发生在测试线程，不阻塞 FX 线程。假配置使用临时目录、`example.invalid` 和空凭据，不使用公司连接。

## 桌面验收

### 首次尝试：访问受限

使用当前源码启动：

```powershell
./gradlew.bat run --offline --no-daemon --console=plain '-PappVersion=3.2.2'
```

- 可访问性树可读到“开始使用 DataCube”、两个新增按钮、使用说明和原有连接树。
- 截图返回全黑；重新选择目标窗口后重试激活，仍报 `GetCursorPos failed: 拒绝访问。 (0x80070005)`。停止桌面输入，不绕过该限制。
- 未点击已保存的连接，未测试真实连接、执行 SQL、创建或修改连接配置；未切换主题。
- **未验证：** 实际点击新建/取消连接、打开/关闭空 SQL、深浅主题的布局和裁切、键盘焦点的可见效果。真实控件测试不等于这些视觉验收。
- 验收实例保持打开，等待桌面可访问后继续；没有把全黑图保存为成功截图，也没有复用实施前截图。

### 同日补验：已完成

用户提供暗色开始页截图后，重新选择并激活原验收实例，桌面访问恢复。运行代码对应 `707f71a`，本次无源码变化。使用 Computer Use 实际操作，不把可访问性树或先前自动化结果代替视觉证据。

截图保存于忽略目录 `build/product-verification/2026-08-28/`；它们是本地验收产物，后续 clean 会清除，不包含在 Git 提交中。

| 验收项 | 实际操作和结果 | 截图文件 |
| --- | --- | --- |
| 暗色开始页 | 标题、说明、两个入口及两行使用提示可见；当前约 1200 × 832 窗口下无明显裁切 | `01-start-dark.jpg` |
| 新建连接入口 | 点击开始页“新建连接”，打开现有对话框；不输入数据，不测试、不保存，点击“取消”返回开始页 | `02-new-connection.jpg` |
| 键盘焦点 | 取消对话框后，“新建连接”有焦点边框；Tab 移至“选择已有连接”，边框随之移动 | `03-keyboard-focus.jpg` |
| 选择已有连接 | 在该按钮按空格，连接树出现焦点边框；两个节点保持折叠，无选择高亮。没有按树方向键或激活连接 | `04-tree-focus.jpg` |
| 内容页替代引导 | 点击顶部“新建 SQL”，出现空编辑页，开始引导隐藏；页面仍显示“未绑定连接”“尚未创建专用会话” | `05-sql-open.jpg` |
| 最后一页关闭 | 仅关闭刚打开、未编辑的空 SQL 标签，开始页恢复 | `06-start-restored.jpg` |
| 亮色主题 | 切换亮色，标题、说明和按钮清晰可见，使用提示无明显裁切 | `07-start-light.jpg` |
| 恢复用户状态 | 切回原暗色主题，无新标签或对话框遗留；验收实例保持打开 | `08-dark-restored.jpg` |

本次不代表不同 DPI、所有窗口尺寸或完整辅助技术兼容性验收。可访问性树的焦点/标签文字偶有延迟，以上焦点边框和主题结果同时依据实际截图确认。全程未打开真实数据库连接或执行 SQL，未保存或改动连接配置。

## 审查与后续

已在当前任务逐项自查生产差异和新增测试；不是独立代理审查。受管标签关闭逻辑未更改；开始提示绑定只读空状态；聚焦动作不引入存储或网络调用。未发现本次范围内需进一步改动的代码问题，任务 1 的桌面补验已完成。

任务 1 代码检查点为 `707f71a`，后续文档更新补齐桌面验收证据。任务 2（异步连接测试）和任务 3（SQL 连接提示）尚未实施，整体阶段验收清单保持未勾选。
