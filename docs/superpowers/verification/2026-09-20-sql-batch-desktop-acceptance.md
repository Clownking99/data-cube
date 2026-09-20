# SQL 批量结果原生桌面收尾与筛选提示修复

基线 main `b2e6b26`；修复分支 `codex/sql-overview-prompt`。本轮优先补验已有的 SQL 摘要、数值耗时排序、双向异常导航与概览筛选，不增加数据库操作。未推送、未打 tag，`0.0.0` 仅为开发镜像版本。

## 隔离边界

Windows 桌面通道本轮恢复，按 computer-use 技能使用 `@oai/sky` 的实际鼠标、键盘和截图验收。复用 `SqlScriptDetailsDesktopFixture`，仅注入六条合成结果：两组查询、一组更新、失败、超时、取消；不注册连接、不执行 SQL。使用独立 `script-details-desktop-profile`，不读取真实用户配置、SQL、历史、剪贴板或 `.testagent/`。

基线镜像复制到忽略目录 `build/batch-workflow-desktop-20260920/DataCube`，仅复制品的启动配置添加 fixture 单类 patch 和临时 profile。原生产配置 SHA-256 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`、runtime/modules SHA-256 `3533292D554143A0A1C64D7029E95DB2B63661FA4F344D8EF68EE80E11A63D44` 在基线验收前后均一致。正常关闭后无 DataCube 进程；合成源文件逐字符核对（含混合换行）未改变，profile 除 JavaFX 缓存外只有该合成 SQL 文件。

## 基线原生实测

| 操作 | 观察结果 |
| --- | --- |
| 查询 #1 点击“上一异常”，再点“下一异常” | 先到 #6 取消，再环绕到 #4 失败；展示各自错误与耗时，无额外提示框，整批汇总不变 |
| “下一异常”获焦后 Shift+Tab，再 Space | 可见焦点移至“上一异常”；从 #4 环绕至 #6，正确显示取消结果 |
| 点击“耗时”表头两次 | 升序为 5、8、12、18、50、1000ms，降序反向；不是字典序 |
| 降序下勾选“仅看异常” | 仅 #5、#6、#4，3/6，保留降序 |
| 在筛选框输入 `  SLOW  ` | 与异常条件取交集，仅 #5，1/6；已输入文字可读，汇总仍为六条结果 |
| 从筛选框 Tab 到“清除关键词”，Space | 清空并把焦点还给输入框；异常条件和耗时降序保留，恢复 3/6 |
| 选择 #4 的截短 SQL 摘要，Enter | 只读详情显示原始多行 SQL、缩进、字面 `<script>`、完整尾行和 SQLState=42703；关闭按钮返回概览，未改编辑器 |

这些是合成返回结果的 Windows 原生交互证据，不代表真实数据库执行、事务状态、完整 AppShell 或发布安装包验收。

## 发现的问题与最小修复

概览筛选框的空值提示在暗色主题下近乎不可见，而输入文字可读。新控件没有接入已有提示色规则，沿用 Modena 默认计算色。生产改动仅在 `theme-base.css` 的提示色选择器中加入 `#sql-script-sql-filter`，使用现有 `-brand-fg-dim`，同时覆盖空框聚焦和清除后的状态；不修改 Java 生产逻辑、全局输入框规则或任何数据库路径。

按 code-testing-agent 聚焦流程扩展一个既有测试类，不创建中间状态目录或调用子代理。

| 需求 | 证据 |
| --- | --- |
| 提示在明暗主题、聚焦前后、清除后与动态主题切换时使用可读提示色；筛选行为不变 | `SqlOverviewSearchTest.overviewPromptUsesReadableThemeColorBeforeAndAfterFocusAndThemeSwitch`：两种切换顺序；断言解析后的实际 CSS 色值、焦点、提示文本、匹配/恢复的具体行号及离线边界 |
| 先证实测试会发现回归 | 修复前两项失败，27 秒 exit 1：暗色实际 `#101017` 而期望 `#A8A8B8`；亮色实际 `#B2B2B2` 而期望 `#555555` |
| 定向回归通过 | 修复后以下命令 64 项通过，15 秒 exit 0 |

PowerShell 设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlOverviewSearchTest --tests com.datacube.fx.SqlScriptDetailFindTest --no-daemon --console=plain
```

## 最终验证

相同 `JAVA_TOOL_OPTIONS` 下执行：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

3 分 22 秒 exit 0：主测试 3,154 项，其中 3,151 通过、3 项既有 live 跳过，0 failures/errors；buildSrc 8 项全部通过。开发镜像成功。既有 unchecked、`JAVA_TOOL_OPTIONS` 辅助进程输出及 JEP 493 提示不变。

修复镜像复制到 `build/batch-workflow-fixed-20260920/DataCube`；仅复制品加入同一 fixture 单类 jar 和新临时 profile。复制品 runtime/modules 与工作树生产镜像 SHA-256 同为 `371185DB6FE1CC3D5DDB11BBCA9B3184DCA2D312FF46ABC21B165FB54634E0F9`。

## 修复镜像原生复验

| 操作 | 观察结果 |
| --- | --- |
| 暗色宽窗查看空框、点击聚焦、输入 `slow` 再鼠标清除 | 提示清晰可读；输入显示 1/6，清除恢复 6/6、输入框焦点和可读提示 |
| 按 fixture 窄窗按钮（stage width=640），再切亮色 | 两主题下筛选框、清除按钮及计数完整显示，工具条换行；亮色聚焦后的提示仍可读 |
| 亮色窄窗在表格水平滚动 | SQL 摘要和右侧结果列均可浏览，未丢失行 |
| 输入 `STATUS`，点击 SQL 摘要表头 | 仅两条匹配；从 update/select 顺序变为 select/update，2/6 不变 |
| 选择 `select status from sample;`，鼠标点“查看结果” | 正确打开语句 #3 的两行 READY/WAITING、两列 status/message 数据；返回概览时关键词为空、恢复 6/6 |
| 替换为仅正常批次 | 两个异常导航按钮禁用；进入概览并勾“仅看异常”显示 0/2、无结果和“已保留结果中没有异常”，窄窗提示完整 |
| 正常关闭窗口并核对隔离文件 | 无 DataCube 进程；合成 SQL（含混合换行）未改变；除 JavaFX 缓存外仅合成 SQL 与主题切换产生的 settings.properties，无连接或历史文件 |

工作树原生产启动配置仍为 DataCubeFx，配置 SHA-256 与基线一致，runtime/modules 与上面修复镜像一致；未把 fixture 类打入生产镜像。没有改真实用户数据，也没有进行 live 数据库或远端发布验收。原生验证覆盖上表的实际操作，不扩大为所有主题、分辨率、键盘路径的穷举保证。

## 本地 main 集成

集成结果完成后补记。
