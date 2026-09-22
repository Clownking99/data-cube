# SQL 美化注释与常见子句安全增强验收记录

## 自动化

定向命令：

```text
gradlew.bat test --tests com.datacube.sqleditor.SqlFormatterTest --no-daemon --console=plain
```

结果：exit 0。覆盖行注释行边界、分页/NULLS/FILTER 关键字与 DDL 列表间距，新增：

- `lineCommentsKeepFollowingSqlOnANewLine`
- `commonPagingAndFilterKeywordsAreFormatted`
- `ddlColumnListsKeepReadableSpacing`

完整回归与打包将在本轮合并前执行；本轮没有真实数据库或桌面交互验收。

## 语义边界

行注释回归同时断言注释与后续 `FROM` 之间存在换行、后续 SQL 不出现在注释正文中，并验证二次美化幂等。复杂查询仍是纯词法排版，执行前应人工检查方言特性与过程体。
