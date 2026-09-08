# SQL 编辑器易用性（2026-09-08）

## 本轮范围

- “SQL 另存为”预填当前已绑定文件的目录和文件名，支持中文与空格。
- 新建或未绑定文件默认名称为 `query.sql`。原父目录不存在或被普通文件替换时，
  不设置无效初始目录，也不自动创建目录，保留文件名供用户重新选择。
- 另存为成功后，下次建议使用新路径；取消覆盖不会改变当前文件绑定。
- 主工具栏按 Schema、文件、执行、编辑、结果分组，空间不足时整组换行，
  事务控制行也可换行。按钮、快捷键、连接准入和保存事务逻辑保持原样。

## 自动化验证

新增 `SqlEditorUsabilityTest` 使用真实 JavaFX 布局与隔离临时文件：

- `saveChooserStartsAtTheCurrentFileIncludingUnicodeAndSpaces`
- `unboundSqlGetsADefaultNameWithoutChoosingADirectory`
- `unavailableParentFallsBackWithoutRecreatingIt`：目录消失、目录被文件替换两种情况。
- `primaryActionsRemainReadableAndInsideTheEditor`：880 / 640 / 480 像素编辑区，深浅两种主题。
  验证所有 9 个主操作保留、按钮宽度足够显示完整标签、位于工具栏范围内，
  以及未绑定连接仍禁止执行。

现有 `SqlScriptFileControllerTest` 的首次保存、另存为及取消覆盖测试增加当前路径断言。
定向命令完成 `BUILD SUCCESSFUL`：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorUsabilityTest --tests com.datacube.fx.SqlScriptFileControllerTest --no-daemon --console=plain
```

实现前文件选择默认值测试按预期失败；布局验证先修正 JavaFX 不支持的组合选择器，
再独立检查每类实际控件。没有用测试选择器错误作为布局缺陷的证据。

## 完整回归与打包

仅为测试进程指定真实 Windows 8.3 短路径形式的 `java.io.tmpdir`，运行：

```powershell
.\gradlew.bat clean test jpackageImage "-PappVersion=0.0.0" --no-daemon --console=plain
```

结果 `BUILD SUCCESSFUL in 1m 52s`。fresh XML 汇总：1,734 tests、0 failures、0 errors、3 skipped。
`0.0.0` 仅供独立 profile 的桌面验收，利用已有开发版本逻辑跳过启动更新，不修改默认发布版本。

## 桌面验收限制

computer-use 启动验收包时返回 `GetCursorPos failed: 拒绝访问。 (0x80070005)`，
随后检测到 LockApp 进程；已停止桌面输入，没有绕过锁屏或修改系统权限。
本轮真实原生文件对话框和拖动窗口验收尚未完成，不能用自动化布局测试代替这一结论。
