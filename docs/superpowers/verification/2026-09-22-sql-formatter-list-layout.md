# SQL 列表排版增强验收记录

## 自动化

定向命令：

```text
gradlew.bat test --tests com.datacube.sqleditor.SqlFormatterTest --no-daemon --console=plain
```

结果：exit 0，21 项格式化测试通过。新增 `topLevelAndWindowListsBreakWithoutSplittingFunctionArguments`，覆盖顶层分组/排序、窗口分区/排序、`RETURNING` 列表、函数参数不拆分与幂等性。

完整回归和安装镜像将在本轮合并前执行。
