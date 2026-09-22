# Oracle 层级查询与 DDL 动作美化增强验收记录

## 自动化

定向命令：

```text
gradlew.bat test --tests com.datacube.sqleditor.SqlFormatterTest --no-daemon --console=plain
```

结果：exit 0，20 项格式化测试通过。新增：

- `oracleHierarchyClausesRemainReadable`
- `oracleDdlActionsAreUppercasedAndSpaced`

完整回归结果：262 suites / 3301 tests / 0 failures / 0 errors / 3 existing live skips。`jpackageImage` exit 0，安装镜像生成成功；jlink 仍输出项目既有的 JEP 493 模块假设提示。

## 未宣称事项

本轮未连接真实 Oracle，也未做桌面窗口交互验收；执行前仍应人工检查具体方言和约束语义。
