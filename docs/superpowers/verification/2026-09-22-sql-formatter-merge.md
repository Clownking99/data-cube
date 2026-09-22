# MERGE SQL 美化增强验收记录

## 自动化

定向命令：

```text
gradlew.bat test --tests com.datacube.sqleditor.SqlFormatterTest --no-daemon --console=plain
```

结果：exit 0，18 项格式化测试通过。新增 `mergeActionsUseVisibleClauseBoundaries`，覆盖 MERGE 目标、USING 子查询、ON 条件、匹配/未匹配动作及列和值列表，并验证二次美化幂等。

完整回归和安装镜像将在本轮合并前执行；未进行真实 Oracle 连接或桌面交互验收。
