# 连接树展开加载可靠性验收

基线 main `a1ed3ec`，独立分支 `codex/connection-tree-retry`；实现 `fafd9b0` 已快进本地 main。

## 自动验证

| Requirement | Evidence |
| --- | --- |
| 失败后不会在同一展开状态重复加载，收起再展开才重试；成功后不再重复加载 | `ConnectionTreePaneLifecycleTest.failedLazyLoadCanRetryOnlyAfterCollapseAndReexpand` |
| 空子节点不渲染成空白，显示稳定的状态行且不可执行连接树操作 | `ConnectionTreePaneLifecycleTest.emptyLazyLoadShowsStableStatusInsteadOfBlankChildren` |
| 刷新/替换或节点脱离当前树后的迟到回调被拒绝 | `ConnectionTreePaneLifecycleTest.staleLazyCallbackIsRejectedAfterTreeReplacementOrDetach` |
| 既有连接树查找和刷新行为保持不访问网络、不写连接文件 | `ConnectionTreePaneFindTest.savedConnectionFindRefreshAndLegacyTypingNeverConnectExecuteOrWriteConfiguration` |

定向命令：

```powershell
.\gradlew.bat test --tests com.datacube.fx.ConnectionTreePaneLifecycleTest --tests com.datacube.fx.ConnectionTreePaneFindTest --no-daemon --console=plain
```

结果：exit 0 / BUILD SUCCESSFUL。

同一 worktree 随后执行全量测试与开发镜像构建：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djava.awt.headless=false'
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

结果：2 分 52 秒、exit 0 / BUILD SUCCESSFUL；主测试 XML 共 3,288 项，3,285 通过、3 个既有 live 跳过，无失败/错误；buildSrc 的 `IcoGeneratorTest` 8 项通过。保留既有 unchecked 编译提示、`JAVA_TOOL_OPTIONS` 和 jlink 辅助探测文本，不将其误报为失败。

## 边界与未宣称事项

`displayChildren` 对 null/空列表均显示状态行；失败提示保留已有 `message(Throwable)` 的简短文本，不把数据库诊断扩展到新日志或剪贴板。代次校验只防止迟到 UI 回调，不取消已经在数据库驱动中运行的底层读取；关闭仍由 `FxTaskScope` 取消并抑制回调。未连接真实数据库、未读取用户连接配置、未执行 SQL，也未把合成单元测试或开发镜像称为真实网络稳定性或用户效率数据。
