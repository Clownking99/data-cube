# SQL 结果栏窄屏适配（2026-09-08）

## 范围

- 结果搜索、添加条件、数据库筛选、列选择、复制、清除筛选按可用宽度换行；
  保留 220 像素搜索输入区和完整按钮文字。
- 长条件标签在结果栏内换行，结果摘要及可恢复错误提示允许多行显示。
- 事务模式显示“自动提交 / 手动提交”，保留原来的 `AUTO_COMMIT / MANUAL` 枚举值、
  默认值、连接准入、提交/回滚与待提交事务确认流程。
- 不增加依赖，不改变 SQL、筛选状态、搜索防抖、复制内容或保存流程。

## 回归证据

使用真实 JavaFX 控件和合成结果数据，不连接数据库。

| 要求 | 测试 |
| --- | --- |
| 深浅主题下 880 / 640 / 480 像素布局、完整标签、无重叠、长条件及摘要换行 | `SqlResultToolbarLayoutTest.resultActionsKeepFullLabelsAndWrapWithoutDispatching` |
| 缩窄再放宽后操作回调、搜索先于查询、四种复制、条件删除和列显隐仍正常 | `SqlResultToolbarLayoutTest.actionsAndColumnMenuStillWorkAfterNarrowingAndWidening` |
| 中文事务选项映射、实际自动提交文字宽度、未绑定连接保持禁用 | `SqlEditorUsabilityTest.primaryActionsRemainReadableAndInsideTheEditor` |

旧实现的定向测试失败，确认了搜索宽度不足、动作标签被压缩及摘要未换行。
事务测试使用新控件 ID；实现后的首次测试误取了 ComboBox 的内部空白单元格，
已改为检查皮肤的实际显示节点，没有将这个选择器错误当作产品缺陷。

定向验证命令：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlResultToolbarLayoutTest --tests com.datacube.fx.SqlResultToolbarTest --tests com.datacube.fx.SqlEditorUsabilityTest --tests com.datacube.fx.SqlEditorResultFilterContractTest --tests com.datacube.service.JdbcEditorSessionTest --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 22s`，包括已有搜索防抖、结果状态映射及 JDBC 事务回归。

## 全量验证与桌面验收

为测试进程设置实际 Windows 8.3 短路径形式的 `java.io.tmpdir`，运行：

```powershell
.\gradlew.bat clean test jpackageImage "-PappVersion=0.0.0" --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 2m 15s`。fresh XML 汇总为 1,741 tests、0 failures、0 errors、3 skipped。
版本 `0.0.0` 仅供隔离验收，使用已有开发版本逻辑跳过更新，不变更发布版本或 tag。

通过 computer-use 操作本轮 jpackage 应用，指定独立 `user.home`：

1. `Ctrl+O` 打开合成文件 `结果栏 验收.sql`，执行及结果操作保持禁用，无数据库连接。
2. 默认约 900 像素编辑区内，结果操作单行显示，“自动提交”文字完整。
3. 拖动分隔条缩至约 480 像素，搜索与筛选按钮在第一行，列选择、复制、清除筛选换至第二行，
   未出现上轮的省略号按钮；事务文字仍完整。
4. 切换亮色主题，窄屏布局一致；恢复宽度后结果操作重新回到单行。
5. 正常退出隔离实例，未访问用户数据库、连接配置或真实 SQL 文件。

真实桌面验收覆盖未连接/无结果状态；有结果、长条件与摘要、动作回调和事务枚举映射由上述
真实 JavaFX 自动测试覆盖。本轮没有在实际数据库中切换事务或执行查询。
