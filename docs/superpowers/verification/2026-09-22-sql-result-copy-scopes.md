# 查询结果按范围快速复制验收记录

## 变更

在结果工具栏“复制”菜单增加：

- 当前筛选行 / 当前筛选行（含表头）
- 全部已加载行 / 全部已加载行（含表头）

当前筛选范围使用表格显示顺序；全部已加载范围使用活动结果的原始加载顺序。两者均按当前可见数据列投影，不包含序号列或隐藏列。

## 自动化验收

定向命令：

```text
gradlew.bat test --tests com.datacube.fx.SqlEditorResultFilterContractTest --no-daemon --console=plain
```

结果：通过（本轮新增结果复制契约与既有结果筛选契约均通过）。

完整回归命令：

```text
gradlew.bat clean test --no-daemon --console=plain
```

结果：exit 0，262 suites / 3290 tests / 3287 passed / 0 failures / 0 errors / 3 既有 live skips。输出仍保留项目原有的 unchecked 编译提示；本轮未把它描述为零警告构建。

打包命令：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

结果：exit 0，`build/image` 免安装镜像生成成功。

覆盖：

- `filteredAndLoadedRowCopyUseDistinctScopesVisibleColumnsAndPreserveSelection`：验证筛选行/全部已加载行、含/不含表头、隐藏列、列顺序、筛选后排序、原始加载顺序及选区保持。
- `oversizedAllLoadedCopyIsRejectedWithoutTouchingClipboard`：验证超过 8 MiB 的复制不调用剪贴板写入器且显示固定失败提示。
- `SqlResultToolbarTest.buttonsMenusAndConditionChipsDispatchOnlyTheirExplicitCallbacks`：验证八个复制菜单动作各自派发准确的 `CopyMode`。
- `SqlResultToolbarLayoutTest.actionsAndColumnMenuStillWorkAfterNarrowingAndWidening`：验证新增入口不改变窄宽布局下的既有动作顺序或列菜单行为。

## 未宣称事项

本记录只覆盖合成结果与内存剪贴板写入边界；未连接真实数据库，未覆盖系统剪贴板实现差异，也未将本轮自动化通过扩大为远端 CI、安装包或正式发布验收。
