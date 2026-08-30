# Safe Result Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 查询结果导出默认忠实于当前筛选、排序及可见列，明确预览值限制，并在失败或取消时保护用户已有文件。

**Architecture:** 在既有格式 writer 外组合不可变投影、值能力策略、确认 UI 和临时文件原子发布器。FX 线程只捕获与确认输入，后台只消费固定请求；独立操作令牌负责取消/发布竞态，编辑器任务作用域负责线程和回调所有权。

**Tech Stack:** 现有 Java 25、JavaFX、Gradle Wrapper、JUnit Jupiter 5.11.3、Java NIO；不增加依赖，不改变共享 writer 的其他调用方。

**实施状态（2026-08-30）：** Tasks 1–7 及 Task 8 的代码、故障回归、说明文档和最终代码审查已完成。主代理在 `e74b29c` 后独立运行完整 non-headless `clean test`：1196 项，1193 通过，0 失败/错误，3 项明确的 live 环境跳过。桌面续验已完成筛选、列重排和升降序 CSV 的实际保存、零行提示、CLOB 确认、顶部 INSERT/SQL 文件特殊值阻止及深浅主题核心路径；范围切换、键盘及完整交互清单仍待补验，右键 INSERT 被工具安全检查拒绝，最后验收步骤保留未勾选。本轮只更新验收记录，未修改生产/测试代码，未重跑完整测试。详见 `docs/superpowers/verification/2026-08-30-safe-result-export.md`。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-08-30-safe-result-export-design.md`，用户已认可书面设计；代码已实施，桌面待补验，完成情况以验证记录和任务检查项为准。
- 实施基线：`c543140b1602d5d41b3c6d9c0b68f71b32c24c48`；分支 `codex/safe-result-export`。
- “当前筛选结果（默认）”：当前 TableView 中全部可见行，遵循当前排序；不是仅选中的行。
- “全部已加载行（加载顺序）”：本次活动结果快照的全部保留行，使用加载顺序，忽略本地条件和搜索。
- 两种范围使用同一份当前可见数据列及顺序，排除 `#` 序号列，不补回隐藏列。
- “全部已加载”只指活动结果，不指数据库全量，也不自动切回之前缓存的原始结果。
- 每次打开默认选择“当前筛选结果”，不记忆上次的扩大范围选择。
- 当前筛选为零行时显示“当前筛选结果为 0 行”，禁用该范围的继续按钮；绝不自动回退导出全部行。
- 行截断提示：“仅包含已加载结果，数据库中可能还有更多行”。
- 非 SQL 特殊值提示：“将导出当前展示，不代表完整原值”，须明确确认；提示不复述单元格内容。
- SQL 文件和复制 INSERT：特殊展示、未知对象、非有限数字整次阻止，不静默丢行或替换为 NULL。
- INSERT 是供用户审阅的文本，不自动执行，不承诺字段别名映射、约束或跨库往返兼容性。
- 取消任意确认或保存对话框不提交后台工作、不触碰文件或剪贴板。
- 写入开始前、循环写行期间及最终发布前检查取消/中断。
- 原子发布不支持时失败，不降级为非原子覆盖；只清理本次唯一临时文件，不删除目标文件。
- 取消与发布共用终态门禁；已发布的文件不撤回。关闭后不更新该编辑器 UI。
- 不展示 SQL、单元格值、连接属性或原始异常。路径仅用于本地保存/临时文件处置反馈。
- 不改变 TSV 选区复制；不涉及整表导出、pg_dump、迁移、Redis、自动重查、CSV 公式策略或导出任务中心。
- 不触碰 `.testagent/`，不自动推送、合并、打 tag，不使用公司数据库凭据。

---

## 文件职责与实施顺序

2026-08-30 执行补充（用户已认可）：Task 2 测试发现共享 CLOB 冻结会将截断预览退化成普通字符串。将 `ImmutableResultValue` 的 CLOB 完整性标记与相关回归纳入本轮前置修复；复用有界内存的文本表示和资源清理，在首次读取阶段保留标记。导出仍不补取 JDBC 大字段，不修改共享格式 writer 或 TSV 选区复制规则。

所有路径相对于仓库根目录 `D:\Projects\朝花夕拾`。实施前用 `git status --short --branch` 核对分支与用户改动；有 `.codegraph/` 时先用 `codegraph explore` 定位符号，再精读必要片段。

| 任务 | 文件 | 单一职责 |
| --- | --- | --- |
| 1 | `src/com/datacube/sqleditor/result/ResultExportScope.java`、`ResultExportSnapshot.java` | 冻结行列位置、SQL 来源及两种范围 |
| 2 | `src/com/datacube/sqleditor/result/ResultExportValuePolicy.java` | 完整标量与展示值的结构化区分 |
| 3 | `src/com/datacube/export/ResultExportOperation.java`、`ResultExportSession.java`、`SafeResultFilePublisher.java` | 取消/发布门禁、每编辑器单任务、唯一临时文件和目标版本保护 |
| 4 | `src/com/datacube/export/QueryResultFileWriter.java` | 可取消的格式适配，复用既有 writer |
| 5 | `src/com/datacube/fx/ResultExportOptionsDialog.java` | 计数、范围、截断、特殊值确认 |
| 6 | `src/com/datacube/fx/SqlResultToolbar.java`、`SqlEditorPane.java` | 防抖提交、展示保持、位置身份映射 |
| 7 | `src/com/datacube/fx/SqlResultExportCoordinator.java`、`SqlEditorPane.java` | 轻量 UI 接线、文件/剪贴板共用规则、关闭及迟到状态隔离 |
| 8 | 新增测试、`README.md`、`docs/superpowers/verification/2026-08-30-safe-result-export.md` | 故障注入、完整回归和真实验收记录 |

依赖顺序：1 → 2；3 独立；4 消费 1–3；5 消费 1–2；6 消费 1；7 消费 1–6；8 汇总。不要并发修改 `SqlEditorPane.java`。每个任务通过后单独审查与提交，先规格审查，再代码质量审查。

### 统一测试命令约定

在同一个 PowerShell 会话设置：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
```

下文每个 `./gradlew test` 命令都在此环境执行。缺失类/方法的首次 RED 可以是 `compileTestJava` 失败，但后续行为回归必须看到具体断言失败，再加入修复；不能把环境失败算 RED。UI 用 `FxUiTestSupport.call`，并发用 latch/显式调度，不使用固定 sleep。每一步的代码块是待实施内容，并未在编写计划时写入生产文件。

## Task 1: 不可变范围投影

**Files**

- Create: `src/com/datacube/sqleditor/result/ResultExportScope.java`
- Create: `src/com/datacube/sqleditor/result/ResultExportSnapshot.java`
- Test: `test/com/datacube/sqleditor/result/ResultExportSnapshotTest.java`

**Interfaces**

- Consumes: `QueryResult.kind`、`columns`、`rows`、`truncated`，值已经冻结，不再次调用 `ImmutableResultValue.freeze`。
- Produces: `ResultExportSnapshot.capture(QueryResult, String, List<Integer>, List<Column>)`；`columns()`；`rows(ResultExportScope)`；`originalSql()`；`truncated()`；`Column(int index, String label)`。
- `visibleRows` 由 FX 侧传入活动结果中的位置，不用 `List.indexOf` 按内容查找。

- [x] **Step 1: 新建精确矩阵测试**

```java
package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultExportSnapshotTest {
    @Test void projectsOrderDuplicatesAndRaggedRowsWithoutMutatingInput() {
        QueryResult active = QueryResult.query(List.of("id", "name", "hidden"),
                List.of(List.of(2, "乙", "secret"), List.of(1, "甲"),
                        List.of(1, "甲")), 1);
        List<Integer> visible = new ArrayList<>(List.of(2, 1));
        var snapshot = ResultExportSnapshot.capture(active, "select * from t", visible,
                List.of(new ResultExportSnapshot.Column(1, "name"),
                        new ResultExportSnapshot.Column(0, "id")));
        visible.clear();
        assertEquals(List.of("name", "id"), snapshot.columns());
        assertEquals(List.of(List.of("甲", 1), List.of("甲", 1)),
                snapshot.rows(ResultExportScope.CURRENT_FILTERED));
        assertEquals(List.of(List.of("乙", 2), List.of("甲", 1), List.of("甲", 1)),
                snapshot.rows(ResultExportScope.ALL_LOADED));
        var shortRow = ResultExportSnapshot.capture(active, "select * from t", List.of(1),
                List.of(new ResultExportSnapshot.Column(2, "hidden")));
        assertEquals(Collections.singletonList(null),
                shortRow.rows(ResultExportScope.CURRENT_FILTERED).get(0));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.rows(ResultExportScope.ALL_LOADED).get(0).set(0, "edit"));
        assertFalse(snapshot.toString().contains("secret"));
    }

    @Test void zeroVisibleRowsNeverFallBackAndInvalidPositionsAreRejected() {
        QueryResult active = QueryResult.query(List.of("id"), List.of(List.of(1)), 1);
        var columns = List.of(new ResultExportSnapshot.Column(0, "id"));
        var snapshot = ResultExportSnapshot.capture(active, "select id from t", List.of(), columns);
        assertEquals(0, snapshot.rows(ResultExportScope.CURRENT_FILTERED).size());
        assertEquals(1, snapshot.rows(ResultExportScope.ALL_LOADED).size());
        assertEquals("select id from t", snapshot.originalSql());
        assertThrows(IllegalArgumentException.class,
                () -> ResultExportSnapshot.capture(active, "", List.of(1), columns));
        assertThrows(IllegalArgumentException.class,
                () -> ResultExportSnapshot.capture(QueryResult.update(1, 1), "", List.of(), columns));
    }
}
```

- [x] **Step 2: 跑 RED**

Run: `./gradlew test --tests com.datacube.sqleditor.result.ResultExportSnapshotTest --no-daemon --console=plain`。Expected: 缺少 `ResultExportSnapshot` / `ResultExportScope`。

- [x] **Step 3: 新建范围枚举和投影类**

`ResultExportScope.java`：

```java
package com.datacube.sqleditor.result;

public enum ResultExportScope {
    CURRENT_FILTERED, ALL_LOADED
}
```

`ResultExportSnapshot.java`：

```java
package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import java.util.*;

public final class ResultExportSnapshot {
    public record Column(int index, String label) {
        public Column {
            if (index < 0) throw new IllegalArgumentException("Invalid export column");
            label = Objects.requireNonNullElse(label, "");
        }
    }
    private final QueryResult active;
    private final String originalSql;
    private final List<Integer> visibleRows;
    private final List<Column> projection;

    private ResultExportSnapshot(QueryResult active, String sql,
            List<Integer> visibleRows, List<Column> projection) {
        this.active = Objects.requireNonNull(active);
        if (active.kind != QueryResult.Kind.QUERY)
            throw new IllegalArgumentException("Export requires a query result");
        this.originalSql = Objects.requireNonNullElse(sql, "");
        this.visibleRows = List.copyOf(visibleRows);
        this.projection = List.copyOf(projection);
        if (this.visibleRows.stream().anyMatch(i -> i < 0 || i >= active.rows.size())
                || this.projection.stream().anyMatch(c -> c.index() >= active.columns.size()))
            throw new IllegalArgumentException("Invalid export position");
    }

    public static ResultExportSnapshot capture(QueryResult active, String sql,
            List<Integer> visibleRows, List<Column> projection) {
        return new ResultExportSnapshot(active, sql, visibleRows, projection);
    }
    public String originalSql() { return originalSql; }
    public boolean truncated() { return active.truncated; }
    public List<String> columns() { return projection.stream().map(Column::label).toList(); }

    public List<List<Object>> rows(ResultExportScope scope) {
        Objects.requireNonNull(scope);
        return Collections.unmodifiableList(new AbstractList<>() {
            @Override public int size() {
                return scope == ResultExportScope.CURRENT_FILTERED
                        ? visibleRows.size() : active.rows.size();
            }
            @Override public List<Object> get(int index) {
                Objects.checkIndex(index, size());
                List<Object> source = active.rows.get(scope == ResultExportScope.CURRENT_FILTERED
                        ? visibleRows.get(index) : index);
                return Collections.unmodifiableList(new AbstractList<>() {
                    @Override public int size() { return projection.size(); }
                    @Override public Object get(int column) {
                        int position = projection.get(column).index();
                        return position < source.size() ? source.get(position) : null;
                    }
                });
            }
        });
    }
    @Override public String toString() {
        return "ResultExportSnapshot[loaded=" + active.rows.size()
                + ", visible=" + visibleRows.size() + ", columns=" + projection.size() + "]";
    }
}
```

嵌套只读列表是位置视图，不复制已有值。空列在模型可表达，由确认/请求层禁止提交；不引入表头-only 模式。

- [x] **Step 4: 跑 GREEN 并提交**

Run: `./gradlew test --tests com.datacube.sqleditor.result.ResultExportSnapshotTest --no-daemon --console=plain`。Expected: 两项通过。

```powershell
git add src/com/datacube/sqleditor/result/ResultExportScope.java src/com/datacube/sqleditor/result/ResultExportSnapshot.java test/com/datacube/sqleditor/result/ResultExportSnapshotTest.java
git commit -m "feat(export): capture immutable result scope projections"
```

## Task 2: 值能力策略，不从显示字符串推断完整性

**Files**

- Create: `src/com/datacube/sqleditor/result/ResultExportValuePolicy.java`
- Test: `test/com/datacube/sqleditor/result/ResultExportValuePolicyTest.java`

**Interfaces**

- Consumes: `ResultExportSnapshot.rows(ResultExportScope)`、`ImmutableResultValue`、`ResultValueFormatter.format(Object)`。
- Produces: `isCompleteScalar(Object)`、`displayValue(Object)`、`assess(List<List<Object>>)` → `Assessment(long displayOnlyCells)`、`Assessment.sqlAllowed()`。
- SQL allowlist 是当前生成器可明确表达的值类型；`Enum`、任意 `Number` 子类、任意 `TemporalAccessor` 不因接口匹配自动放行。

- [x] **Step 1: 写类型边界测试**

```java
package com.datacube.sqleditor.result;

import com.datacube.spi.model.ImmutableResultValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultExportValuePolicyTest {
    @Test void acceptsCompleteScalarsIncludingLiteralEllipsis() {
        for (Object value : Arrays.asList(null, "...", "值…（预览）", 1, 2L,
                new BigDecimal("3.50"), true, LocalDate.of(2026, 8, 30))) {
            assertTrue(ResultExportValuePolicy.isCompleteScalar(value));
            assertSame(value, ResultExportValuePolicy.displayValue(value));
        }
    }
    @Test void rejectsSpecialUnknownAndNonFiniteValuesWithoutInspectingText() {
        List<Object> special = List.of(ImmutableResultValue.freeze(new byte[]{1, 2}),
                ImmutableResultValue.freeze(new Object[]{new byte[]{3}}),
                Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                new Object() { @Override public String toString() { return "ordinary"; } });
        var assessment = ResultExportValuePolicy.assess(List.of(special));
        assertEquals(6, assessment.displayOnlyCells());
        assertFalse(assessment.sqlAllowed());
        for (Object value : special) {
            assertFalse(ResultExportValuePolicy.isCompleteScalar(value));
            assertInstanceOf(String.class, ResultExportValuePolicy.displayValue(value));
        }
    }
    @Test void boundedClobPreviewIsDisplayOnlyEvenWhenItsTextLooksOrdinary() throws Exception {
        Object value = ImmutableResultValue.freeze(
                new javax.sql.rowset.serial.SerialClob("x".repeat(700).toCharArray()));
        assertInstanceOf(ImmutableResultValue.class, value);
        assertFalse(ResultExportValuePolicy.isCompleteScalar(value));
        assertEquals(1, ResultExportValuePolicy.assess(List.of(List.of(value))).displayOnlyCells());
        assertEquals(((ImmutableResultValue) value).displayText(),
                ResultExportValuePolicy.displayValue(value));
    }
}
```

- [x] **Step 2: 跑 RED**

Run: `./gradlew test --tests com.datacube.sqleditor.result.ResultExportValuePolicyTest --no-daemon --console=plain`。Expected: 缺少策略类。

- [x] **Step 3: 加入保守 allowlist**

```java
package com.datacube.sqleditor.result;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.*;
import java.util.*;

public final class ResultExportValuePolicy {
    private ResultExportValuePolicy() {}
    private static final Set<Class<?>> SCALARS = Set.of(
            String.class, Character.class, Boolean.class,
            Byte.class, Short.class, Integer.class, Long.class,
            BigInteger.class, BigDecimal.class, UUID.class, URI.class,
            LocalDate.class, LocalTime.class, LocalDateTime.class,
            OffsetTime.class, OffsetDateTime.class, Instant.class);

    public record Assessment(long displayOnlyCells) {
        public boolean sqlAllowed() { return displayOnlyCells == 0; }
    }
    public static boolean isCompleteScalar(Object value) {
        if (value == null) return true;
        if (value instanceof Double d) return Double.isFinite(d);
        if (value instanceof Float f) return Float.isFinite(f);
        return SCALARS.contains(value.getClass());
    }
    public static Object displayValue(Object value) {
        return isCompleteScalar(value) ? value : ResultValueFormatter.format(value);
    }
    public static Assessment assess(List<List<Object>> rows) {
        long special = 0;
        for (List<Object> row : rows)
            for (Object value : row)
                if (!isCompleteScalar(value)) special++;
        return new Assessment(special);
    }
}
```

非有限数转展示字符串后才给 XLSX，不能产生 `<v>NaN</v>`。普通数字/布尔保留对象类型。日期保持既有 writer 的 `toString`，不用只保留秒的 UI formatter 改写普通时间精度。未知对象只能经确认作展示；来自 QueryResult 的未知值已经冻结。

- [x] **Step 4: 跑 GREEN 并提交**

Run: `./gradlew test --tests com.datacube.sqleditor.result.ResultExportValuePolicyTest --no-daemon --console=plain`。Expected: 三项通过。

```powershell
git add src/com/datacube/sqleditor/result/ResultExportValuePolicy.java test/com/datacube/sqleditor/result/ResultExportValuePolicyTest.java
git commit -m "feat(export): distinguish scalar values from display-only cells"
```

## Task 3: 文件所有权、目标版本与取消/发布终态

**Files**

- Create: `src/com/datacube/export/ResultExportOperation.java`
- Create: `src/com/datacube/export/ResultExportSession.java`
- Create: `src/com/datacube/export/SafeResultFilePublisher.java`
- Test: `test/com/datacube/export/SafeResultFilePublisherTest.java`
- Test: `test/com/datacube/export/ResultExportSessionTest.java`

**Interfaces**

- Produces: `ResultExportOperation.check()`、`cancel()` → boolean、`publish(Action)`、`published()`。
- Produces: `ResultExportSession.begin()` → nullable operation、`finish(ResultExportOperation)`、`close()`、`isClosed()`、`isBusy()`。
- Produces: `SafeResultFilePublisher.capture(Path)` → `Target`；`Target.path()`、`Target.existed()`。
- Produces: `publish(Target, ResultExportOperation, TempWriter)` → `Path`；`TempWriter.write(Path, ResultExportOperation) throws Exception`。
- 测试 seam：包内构造器 `SafeResultFilePublisher(AtomicMover, TempCleaner, Consumer<Path>)`；`AtomicMover.move(Path, Path) throws IOException`、`TempCleaner.delete(Path) throws IOException`。
- `Failure.stage()`、`Failure.temporaryPath()`；阶段 `PREPARE / TARGET_CHANGED / TARGET_BUSY / WRITE / PUBLISH / CLEANUP`。不携带原始异常 cause。

- [x] **Step 1: 写终态和 session 测试**

```java
package com.datacube.export;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultExportSessionTest {
    @Test void cancellationWinsBeforePublicationAndCloseSealsAdmission() throws Exception {
        var session = new ResultExportSession();
        var operation = session.begin();
        assertNotNull(operation);
        assertNull(session.begin());
        session.close();
        var moves = new AtomicInteger();
        assertThrows(CancellationException.class, () -> operation.publish(moves::incrementAndGet));
        assertEquals(0, moves.get());
        assertNull(session.begin());
        assertTrue(session.isClosed());
    }
    @Test void publicationWinsAndLateFinishCannotClearNewOwner() throws Exception {
        var session = new ResultExportSession();
        var first = session.begin();
        first.publish(() -> {});
        assertTrue(first.published());
        assertFalse(first.cancel());
        session.finish(first);
        var second = session.begin();
        session.finish(first);
        assertTrue(session.isBusy());
        assertNotSame(first, second);
        session.finish(second);
        assertFalse(session.isBusy());
    }
}
```

- [x] **Step 2: 跑 RED**

Run: `./gradlew test --tests com.datacube.export.ResultExportSessionTest --no-daemon --console=plain`。Expected: 缺少操作/session 类。

- [x] **Step 3: 实现两个小型所有权对象**

`ResultExportOperation.java`：

```java
package com.datacube.export;

import java.util.concurrent.CancellationException;

public final class ResultExportOperation {
    @FunctionalInterface public interface Action { void run() throws Exception; }
    private enum State { ACTIVE, CANCELLED, PUBLISHED }
    private State state = State.ACTIVE;
    public synchronized void check() {
        if (state != State.ACTIVE || Thread.currentThread().isInterrupted())
            throw new CancellationException("Export cancelled");
    }
    public synchronized boolean cancel() {
        if (state != State.ACTIVE) return false;
        state = State.CANCELLED;
        return true;
    }
    public synchronized void publish(Action action) throws Exception {
        check();
        action.run();
        state = State.PUBLISHED;
    }
    public synchronized boolean published() { return state == State.PUBLISHED; }
}
```

`ResultExportSession.java`：

```java
package com.datacube.export;

public final class ResultExportSession implements AutoCloseable {
    private boolean closed;
    private ResultExportOperation current;
    public synchronized ResultExportOperation begin() {
        if (closed || current != null) return null;
        current = new ResultExportOperation();
        return current;
    }
    public synchronized void finish(ResultExportOperation operation) {
        if (current == operation) current = null;
    }
    public synchronized boolean isBusy() { return current != null; }
    public synchronized boolean isClosed() { return closed; }
    @Override public synchronized void close() {
        closed = true;
        if (current != null) current.cancel();
    }
}
```

- [x] **Step 4: 跑 session GREEN**

Run: `./gradlew test --tests com.datacube.export.ResultExportSessionTest --no-daemon --console=plain`。Expected: 两项通过。

- [x] **Step 5: 写发布失败与覆盖竞态测试**

```java
package com.datacube.export;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SafeResultFilePublisherTest {
    @TempDir Path directory;
    private SafeResultFilePublisher publisher() {
        return new SafeResultFilePublisher((source, target) ->
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING),
                path -> Files.deleteIfExists(path), path -> fail("Unexpected cleanup failure"));
    }
    @Test void failingWriterTouchesOnlyItsOwnTempAndKeepsOldBytes() throws Exception {
        Path target = directory.resolve("result.csv");
        Files.writeString(target, "old");
        Path unrelated = Files.writeString(directory.resolve("unrelated.tmp"), "keep");
        var temp = new AtomicReference<Path>();
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () ->
                publisher().publish(SafeResultFilePublisher.capture(target),
                        new ResultExportOperation(), (path, operation) -> {
                            temp.set(path);
                            assertNotEquals(target, path);
                            Files.writeString(path, "partial");
                            throw new IOException("secret must not surface");
                        }));
        assertEquals(SafeResultFilePublisher.Stage.WRITE, failure.stage());
        assertNull(failure.getCause());
        assertEquals("old", Files.readString(target));
        assertEquals("keep", Files.readString(unrelated));
        assertFalse(Files.exists(temp.get()));
    }
    @Test void changedTargetAndUnsupportedAtomicMoveNeverReplaceOldBytes() throws Exception {
        Path target = Files.writeString(directory.resolve("result.csv"), "old");
        var confirmed = SafeResultFilePublisher.capture(target);
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () ->
                publisher().publish(confirmed, new ResultExportOperation(), (path, operation) -> {
                    Files.writeString(path, "new");
                    Files.writeString(target, "external change");
                }));
        assertEquals(SafeResultFilePublisher.Stage.TARGET_CHANGED, failure.stage());
        assertEquals("external change", Files.readString(target));
        var unsupported = new SafeResultFilePublisher((source, destination) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test");
        }, path -> Files.deleteIfExists(path), path -> fail("cleanup"));
        assertThrows(SafeResultFilePublisher.Failure.class, () -> unsupported.publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(),
                (path, operation) -> Files.writeString(path, "new")));
        assertEquals("external change", Files.readString(target));
    }
    @Test void missingTargetThatAppearsDuringWriteIsNotOverwritten() throws Exception {
        Path target = directory.resolve("new.csv");
        assertThrows(SafeResultFilePublisher.Failure.class, () -> publisher().publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(),
                (path, operation) -> {
                    Files.writeString(path, "ours");
                    Files.writeString(target, "other");
                }));
        assertEquals("other", Files.readString(target));
    }
    @Test void successfulPublishAndCancellationHaveDifferentTerminalEffects() throws Exception {
        Path target = directory.resolve("result.csv");
        var operation = new ResultExportOperation();
        Path published = publisher().publish(SafeResultFilePublisher.capture(target), operation,
                (path, token) -> Files.writeString(path, "new"));
        assertEquals(target, published);
        assertTrue(operation.published());
        assertFalse(operation.cancel());
        assertEquals("new", Files.readString(target));
        var cancelled = new ResultExportOperation();
        assertThrows(java.util.concurrent.CancellationException.class, () -> publisher().publish(
                SafeResultFilePublisher.capture(target), cancelled, (path, token) -> {
                    Files.writeString(path, "partial");
                    cancelled.cancel();
                }));
        assertEquals("new", Files.readString(target));
    }
}
```

- [x] **Step 6: 跑 publisher RED**

Run: `./gradlew test --tests com.datacube.export.SafeResultFilePublisherTest --no-daemon --console=plain`。Expected: 缺少发布器。

- [x] **Step 7: 实现唯一临时文件和原子发布器**

```java
package com.datacube.export;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class SafeResultFilePublisher {
    public enum Stage { PREPARE, TARGET_CHANGED, TARGET_BUSY, WRITE, PUBLISH, CLEANUP }
    public static final class Failure extends IOException {
        private final Stage stage;
        private final Path temporaryPath;
        public Failure(Stage stage, Path temporaryPath) {
            super("Result export failed at " + stage);
            this.stage = stage;
            this.temporaryPath = temporaryPath;
        }
        public Stage stage() { return stage; }
        public Path temporaryPath() { return temporaryPath; }
    }
    private record Stamp(boolean exists, Object key, long size,
                         FileTime modified, FileTime created) {}
    public static final class Target {
        private final Path path;
        private final Stamp stamp;
        private Target(Path path, Stamp stamp) { this.path = path; this.stamp = stamp; }
        public Path path() { return path; }
        public boolean existed() { return stamp.exists(); }
    }
    @FunctionalInterface public interface TempWriter {
        void write(Path path, ResultExportOperation operation) throws Exception;
    }
    @FunctionalInterface interface AtomicMover { void move(Path source, Path target) throws IOException; }
    @FunctionalInterface interface TempCleaner { void delete(Path path) throws IOException; }
    private static final Set<Path> BUSY = ConcurrentHashMap.newKeySet();
    private static final Logger LOG = Logger.getLogger(SafeResultFilePublisher.class.getName());
    private final AtomicMover mover;
    private final TempCleaner cleaner;
    private final Consumer<Path> cleanupDiagnostic;

    public SafeResultFilePublisher() {
        this((source, target) -> Files.move(source, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                path -> Files.deleteIfExists(path),
                path -> LOG.warning("Result export CLEANUP: " + path));
    }
    SafeResultFilePublisher(AtomicMover mover, TempCleaner cleaner, Consumer<Path> diagnostic) {
        this.mover = Objects.requireNonNull(mover);
        this.cleaner = Objects.requireNonNull(cleaner);
        this.cleanupDiagnostic = Objects.requireNonNull(diagnostic);
    }
    public static Target capture(Path chosen) throws Failure {
        try {
            Path absolute = chosen.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent == null || absolute.getFileName() == null)
                throw new IOException("Unsupported export path");
            Path path = parent.toRealPath().resolve(absolute.getFileName());
            return new Target(path, stamp(path));
        } catch (IOException | RuntimeException failure) {
            throw new Failure(Stage.PREPARE, null);
        }
    }
    private static Stamp stamp(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Unsupported export target");
            return new Stamp(true, attributes.fileKey(), attributes.size(),
                    attributes.lastModifiedTime(), attributes.creationTime());
        } catch (NoSuchFileException absent) {
            return new Stamp(false, null, 0, null, null);
        }
    }
    private static void verify(Target target) throws Failure {
        try {
            Path parent = target.path.getParent();
            if (!parent.toRealPath().equals(parent) || !stamp(target.path).equals(target.stamp))
                throw new IOException("Export target changed");
        } catch (IOException | RuntimeException changed) {
            throw new Failure(Stage.TARGET_CHANGED, null);
        }
    }
    public Path publish(Target target, ResultExportOperation operation, TempWriter writer)
            throws Exception {
        Objects.requireNonNull(target);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(writer);
        if (!BUSY.add(target.path)) throw new Failure(Stage.TARGET_BUSY, null);
        Path temporary = null;
        Stage stage = Stage.PREPARE;
        try {
            operation.check();
            verify(target);
            temporary = Files.createTempFile(target.path.getParent(), ".datacube-export-", ".tmp");
            stage = Stage.WRITE;
            operation.check();
            writer.write(temporary, operation);
            stage = Stage.PUBLISH;
            Path ready = temporary;
            operation.publish(() -> {
                verify(target);
                mover.move(ready, target.path);
            });
            return target.path;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Failure safe) {
            throw safe;
        } catch (Exception failure) {
            throw new Failure(stage, null);
        } finally {
            try {
                if (temporary != null && !operation.published()) {
                    try {
                        cleaner.delete(temporary);
                    } catch (IOException | RuntimeException cleanupFailure) {
                        try { cleanupDiagnostic.accept(temporary); }
                        catch (RuntimeException ignored) { /* Diagnostics cannot hide cleanup ownership. */ }
                        throw new Failure(Stage.CLEANUP, temporary);
                    }
                }
            } finally {
                BUSY.remove(target.path);
            }
        }
    }
}
```

`Files.move` 仅在同一门禁内使用 `ATOMIC_MOVE`，原子覆盖不被支持就失败；不用 `ConnectionStore` 的非原子 fallback。测试 seam 的 mover 必须“抛出前不移动”，对应受支持 NIO 原子移动契约；无法承诺恶意外进程 TOCTOU 与断电持久性。锁 key 使用真实父目录 + 文件名，在 Windows Path 等价规则下互斥；不承诺检测不同硬链接名称指向同一 inode。

- [x] **Step 8: 跑 GREEN 并提交**

Run: `./gradlew test --tests com.datacube.export.ResultExportSessionTest --tests com.datacube.export.SafeResultFilePublisherTest --no-daemon --console=plain`。Expected: 全部通过；unsupported 原子注入测试不得被 skip。

```powershell
git add src/com/datacube/export/ResultExportOperation.java src/com/datacube/export/ResultExportSession.java src/com/datacube/export/SafeResultFilePublisher.java test/com/datacube/export/ResultExportSessionTest.java test/com/datacube/export/SafeResultFilePublisherTest.java
git commit -m "feat(export): publish result files atomically with cancellation ownership"
```

## Task 4: 格式适配与逐行取消，不改变共享 writer

**Files**

- Create: `src/com/datacube/export/QueryResultFileWriter.java`
- Test: `test/com/datacube/export/QueryResultFileWriterTest.java`
- Read-only: `src/com/datacube/export/ResultExporter.java`、`XlsxWriter.java`、`src/com/datacube/sqleditor/InsertSqlGenerator.java`

**Interfaces**

- Consumes: Task 1 `ResultExportSnapshot` / `ResultExportScope`、Task 2 策略、Task 3 `ResultExportOperation.check()`。
- Produces: `QueryResultFileWriter.Format`（五种既有格式与菜单元数据）；`write(Path, Format, ResultExportSnapshot, ResultExportScope, boolean, String, ResultExportOperation) throws Exception`。
- Produces: `insert(ResultExportSnapshot, ResultExportScope, String)` → String。
- boolean 参数名 `displayConfirmed`；SQL 无论此值如何都拒绝特殊值，不能借确认绕过。

- [x] **Step 1: 写内容和拒绝测试**

```java
package com.datacube.export;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QueryResultFileWriterTest {
    @TempDir Path directory;
    private ResultExportSnapshot snapshot(List<Object> row) {
        var result = QueryResult.query(List.of("n", "flag", "text"), List.of(row), 1);
        return ResultExportSnapshot.capture(result, "select * from t", List.of(0),
                List.of(new ResultExportSnapshot.Column(0, "n"),
                        new ResultExportSnapshot.Column(1, "flag"),
                        new ResultExportSnapshot.Column(2, "text")));
    }
    @Test void csvAndInsertKeepScalarEscapingAndNullPadding() throws Exception {
        var snapshot = snapshot(Arrays.asList(7, true, "甲,'\n乙"));
        Path target = directory.resolve("result.csv");
        QueryResultFileWriter.write(target, QueryResultFileWriter.Format.CSV, snapshot,
                ResultExportScope.CURRENT_FILTERED, false, null, new ResultExportOperation());
        assertEquals("\uFEFFn,flag,text\r\n7,true,\"甲,'\n乙\"\r\n", Files.readString(target));
        assertEquals("INSERT INTO t (n, flag, text) VALUES (7, TRUE, '甲,''\n乙');\n",
                QueryResultFileWriter.insert(snapshot, ResultExportScope.CURRENT_FILTERED, "t"));
        assertTrue(QueryResultFileWriter.insert(snapshot(List.of(7)),
                ResultExportScope.CURRENT_FILTERED, "t").contains("(7, NULL, NULL)"));
    }
    @Test void xlsxKeepsNumbersAndBooleansTyped() throws Exception {
        Path target = directory.resolve("result.xlsx");
        QueryResultFileWriter.write(target, QueryResultFileWriter.Format.XLSX,
                snapshot(List.of(7, true, "甲")), ResultExportScope.CURRENT_FILTERED,
                false, null, new ResultExportOperation());
        try (ZipFile zip = new ZipFile(target.toFile())) {
            String sheet = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
                    .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(sheet.contains("<v>7</v>"));
            assertTrue(sheet.contains("t=\"b\""));
            assertTrue(sheet.contains("甲"));
        }
    }
    @Test void specialValuesRequireDisplayConsentAndNeverBecomeInsert() throws Exception {
        var snapshot = snapshot(List.of(Double.NaN, true, "..."));
        Path target = directory.resolve("result.csv");
        assertThrows(IllegalArgumentException.class, () -> QueryResultFileWriter.write(target,
                QueryResultFileWriter.Format.CSV, snapshot, ResultExportScope.CURRENT_FILTERED,
                false, null, new ResultExportOperation()));
        assertFalse(Files.exists(target));
        QueryResultFileWriter.write(target, QueryResultFileWriter.Format.CSV, snapshot,
                ResultExportScope.CURRENT_FILTERED, true, null, new ResultExportOperation());
        assertTrue(Files.readString(target).contains("NaN"));
        assertThrows(IllegalArgumentException.class, () -> QueryResultFileWriter.insert(
                snapshot, ResultExportScope.CURRENT_FILTERED, "t"));
    }
    @Test void cancelledOperationCannotCreateAFile() {
        var operation = new ResultExportOperation();
        operation.cancel();
        Path target = directory.resolve("result.csv");
        assertThrows(CancellationException.class, () -> QueryResultFileWriter.write(target,
                QueryResultFileWriter.Format.CSV, snapshot(List.of(7)), ResultExportScope.ALL_LOADED,
                false, null, operation));
        assertFalse(Files.exists(target));
    }
}
```

- [x] **Step 2: 跑 RED**

Run: `./gradlew test --tests com.datacube.export.QueryResultFileWriterTest --no-daemon --console=plain`。Expected: 缺少格式适配类。

- [x] **Step 3: 实现格式适配**

```java
package com.datacube.export;

import com.datacube.sqleditor.InsertSqlGenerator;
import com.datacube.sqleditor.result.*;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class QueryResultFileWriter {
    private QueryResultFileWriter() {}
    public enum Format {
        XLSX("Excel (.xlsx)", "Excel 文件", "*.xlsx", "query_result.xlsx"),
        CSV("CSV (.csv)", "CSV 文件", "*.csv", "query_result.csv"),
        SQL("SQL 插入脚本 (.sql)", "SQL 脚本", "*.sql", "query_result.sql"),
        HTML("HTML (.html)", "HTML 文件", "*.html", "query_result.html"),
        XML("XML (.xml)", "XML 文件", "*.xml", "query_result.xml");
        public final String label, filterDesc, filterExt, defaultName;
        Format(String label, String filterDesc, String filterExt, String defaultName) {
            this.label = label;
            this.filterDesc = filterDesc;
            this.filterExt = filterExt;
            this.defaultName = defaultName;
        }
    }
    private static List<List<Object>> guardedRows(ResultExportSnapshot snapshot,
            ResultExportScope scope, ResultExportOperation operation, boolean display) {
        List<List<Object>> source = snapshot.rows(scope);
        return new AbstractList<>() {
            @Override public int size() { return source.size(); }
            @Override public List<Object> get(int index) {
                operation.check();
                List<Object> row = source.get(index);
                if (!display) return row;
                return new AbstractList<>() {
                    @Override public int size() { return row.size(); }
                    @Override public Object get(int column) {
                        operation.check();
                        return ResultExportValuePolicy.displayValue(row.get(column));
                    }
                };
            }
        };
    }
    private static void validate(ResultExportSnapshot snapshot, List<List<Object>> rows,
            boolean sql, boolean displayConfirmed, String table) {
        if (snapshot.columns().isEmpty() || rows.isEmpty())
            throw new IllegalArgumentException("No exportable result");
        if (sql && (table == null || table.isBlank()))
            throw new IllegalArgumentException("Missing INSERT target");
        var assessment = ResultExportValuePolicy.assess(rows);
        if (!assessment.sqlAllowed() && (sql || !displayConfirmed))
            throw new IllegalArgumentException("Export value policy rejected");
    }
    public static String insert(ResultExportSnapshot snapshot, ResultExportScope scope, String table) {
        var operation = new ResultExportOperation();
        var rows = guardedRows(snapshot, scope, operation, false);
        validate(snapshot, rows, true, false, table);
        return InsertSqlGenerator.generate(table, snapshot.columns(), rows);
    }
    public static void write(Path temporary, Format format, ResultExportSnapshot snapshot,
            ResultExportScope scope, boolean displayConfirmed, String table,
            ResultExportOperation operation) throws Exception {
        operation.check();
        var originalRows = guardedRows(snapshot, scope, operation, false);
        validate(snapshot, originalRows, format == Format.SQL, displayConfirmed, table);
        var rows = guardedRows(snapshot, scope, operation, format != Format.SQL);
        List<String> columns = snapshot.columns();
        switch (format) {
            case XLSX -> XlsxWriter.write(temporary.toFile(), columns, sink -> {
                for (List<Object> row : rows) {
                    operation.check();
                    sink.row(row);
                }
            });
            case SQL -> Files.writeString(temporary,
                    InsertSqlGenerator.generate(table, columns, rows), StandardCharsets.UTF_8);
            default -> {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    switch (format) {
                        case CSV -> ResultExporter.writeCsv(writer, columns, rows);
                        case HTML -> ResultExporter.writeHtml(writer, "查询结果", columns, rows);
                        case XML -> ResultExporter.writeXml(writer, columns, rows);
                        default -> throw new IllegalArgumentException("Unsupported format");
                    }
                }
            }
        }
        operation.check();
    }
}
```

共享文本 writer 在 for-each 取下一行时触发取消检查；不用大范围改成新接口。writer 成功返回代表流已关闭；关闭流异常会由外层发布器归类为 WRITE 并清理临时文件。所有格式只接收 publisher 提供的临时路径，单元测试允许直接给独占测试路径。

- [x] **Step 4: 跑 GREEN 与既有格式回归并提交**

Run: `./gradlew test --tests com.datacube.export.QueryResultFileWriterTest --tests com.datacube.export.ResultExporterTest --tests com.datacube.sqleditor.InsertSqlGeneratorTest --no-daemon --console=plain`。Expected: 新旧测试均通过。

```powershell
git add src/com/datacube/export/QueryResultFileWriter.java test/com/datacube/export/QueryResultFileWriterTest.java
git commit -m "feat(export): adapt existing formats to scoped cancellable snapshots"
```

## Task 5: 轻量范围确认

**Files**

- Create: `src/com/datacube/fx/ResultExportOptionsDialog.java`
- Test: `test/com/datacube/fx/ResultExportOptionsDialogTest.java`

**Interfaces**

- Consumes: Task 1 快照及范围、Task 2 `Assessment`。
- Produces: 包内 `create(Window, ResultExportSnapshot, boolean sql)` → `Dialog<Selection>`，`Selection(ResultExportScope scope, boolean displayConfirmed)`。
- 每次 create 都建立新默认范围；调用方使用 `showAndWait()`，取消返回 empty，不创建 worker。

- [x] **Step 1: 写零行及特殊值确认测试**

```java
package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.util.List;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultExportOptionsDialogTest {
    private ResultExportSnapshot snapshot(List<Integer> visible, Object value) {
        var result = QueryResult.query(List.of("value"), List.of(List.of(value)), 1);
        return ResultExportSnapshot.capture(result, "select value from t", visible,
                List.of(new ResultExportSnapshot.Column(0, "value")));
    }
    @Test void zeroVisibleDisablesDefaultButExplicitAllLoadedIsAllowed() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = ResultExportOptionsDialog.create(null, snapshot(List.of(), 1), false);
            DialogPane pane = dialog.getDialogPane();
            assertTrue(pane.lookup("#result-export-continue").isDisabled());
            @SuppressWarnings("unchecked")
            ComboBox<ResultExportScope> scope =
                    (ComboBox<ResultExportScope>) ((javafx.scene.Parent)
                            ((ScrollPane) pane.getContent()).getContent()).lookup("#result-export-scope");
            assertEquals(ResultExportScope.CURRENT_FILTERED, scope.getValue());
            scope.setValue(ResultExportScope.ALL_LOADED);
            assertFalse(pane.lookup("#result-export-continue").isDisabled());
            assertTrue(((Label) ((javafx.scene.Parent) ((ScrollPane) pane.getContent()).getContent())
                    .lookup("#result-export-summary")).getText().contains("1 行"));
            return null;
        });
    }
    @Test void specialValuesNeedConsentAndSqlCannotOverrideTheBlock() throws Exception {
        FxUiTestSupport.call(() -> {
            var snapshot = snapshot(List.of(0), new byte[]{1});
            var dialog = ResultExportOptionsDialog.create(null, snapshot, false);
            DialogPane pane = dialog.getDialogPane();
            assertTrue(pane.lookup("#result-export-continue").isDisabled());
            ((CheckBox) ((javafx.scene.Parent) ((ScrollPane) pane.getContent()).getContent())
                    .lookup("#result-export-display-consent")).setSelected(true);
            assertFalse(pane.lookup("#result-export-continue").isDisabled());
            var sql = ResultExportOptionsDialog.create(null, snapshot, true);
            assertTrue(sql.getDialogPane().lookup("#result-export-continue").isDisabled());
            assertFalse(((javafx.scene.Parent) ((ScrollPane) sql.getDialogPane().getContent()).getContent())
                    .lookup("#result-export-display-consent").isVisible());
            return null;
        });
    }
}
```

- [x] **Step 2: 跑 RED**

Run: `./gradlew test --tests com.datacube.fx.ResultExportOptionsDialogTest --no-daemon --console=plain`。Expected: 缺少 dialog。

- [x] **Step 3: 实现确认组件**

```java
package com.datacube.fx;

import com.datacube.sqleditor.result.*;
import java.util.EnumMap;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

final class ResultExportOptionsDialog {
    record Selection(ResultExportScope scope, boolean displayConfirmed) {}
    private ResultExportOptionsDialog() {}
    static Dialog<Selection> create(Window owner, ResultExportSnapshot snapshot, boolean sql) {
        Dialog<Selection> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null)
                dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        dialog.setTitle(sql ? "确认 INSERT 范围" : "确认导出范围");
        dialog.setResizable(true);
        ComboBox<ResultExportScope> scope =
                new ComboBox<>(FXCollections.observableArrayList(ResultExportScope.values()));
        scope.setId("result-export-scope");
        scope.setAccessibleText("导出行范围");
        scope.setMaxWidth(Double.MAX_VALUE);
        scope.setConverter(new StringConverter<>() {
            @Override public String toString(ResultExportScope value) {
                if (value == null) return "";
                return value == ResultExportScope.CURRENT_FILTERED
                        ? "当前筛选结果（当前排序）" : "全部已加载行（加载顺序）";
            }
            @Override public ResultExportScope fromString(String value) { return null; }
        });
        scope.setValue(ResultExportScope.CURRENT_FILTERED);
        Label summary = new Label();
        summary.setId("result-export-summary");
        Label boundaries = new Label("仅影响行范围；两种范围都使用当前可见列及其顺序。"
                + "\n全部已加载不代表数据库全量，不会重新查询。");
        Label truncated = new Label(snapshot.truncated()
                ? "仅包含已加载结果，数据库中可能还有更多行" : "");
        Label values = new Label();
        values.setId("result-export-values");
        CheckBox consent = new CheckBox("我理解并同意导出当前展示");
        consent.setId("result-export-display-consent");
        consent.setWrapText(true);
        for (Label label : new Label[]{summary, boundaries, truncated, values}) {
            label.setWrapText(true);
            label.setMaxWidth(Double.MAX_VALUE);
        }
        VBox content = new VBox(10, scope, summary, boundaries, truncated, values, consent);
        content.setPrefWidth(460);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(270);
        dialog.getDialogPane().setContent(scroll);
        ButtonType next = new ButtonType("继续", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(next, ButtonType.CANCEL);
        var continueButton = dialog.getDialogPane().lookupButton(next);
        continueButton.setId("result-export-continue");
        var assessments = new EnumMap<ResultExportScope, ResultExportValuePolicy.Assessment>(
                ResultExportScope.class);
        for (var candidate : ResultExportScope.values())
            assessments.put(candidate, ResultExportValuePolicy.assess(snapshot.rows(candidate)));
        Runnable refresh = () -> {
            var selected = scope.getValue();
            int rowCount = snapshot.rows(selected).size();
            int columnCount = snapshot.columns().size();
            long special = assessments.get(selected).displayOnlyCells();
            summary.setText((selected == ResultExportScope.CURRENT_FILTERED && rowCount == 0
                    ? "当前筛选结果为 0 行" : rowCount + " 行") + " · " + columnCount + " 列");
            boolean needsConsent = !sql && special > 0;
            consent.setVisible(needsConsent);
            consent.setManaged(needsConsent);
            values.setText(special == 0 ? (sql ? "仅生成待审阅文本，不保证跨库兼容。" : "")
                    : special + " 个特殊值单元格。" + (sql
                    ? " 无法无损生成 INSERT，请调整结果或选择展示格式。"
                    : " 将导出当前展示，不代表完整原值"));
            continueButton.setDisable(rowCount == 0 || columnCount == 0
                    || (sql && special > 0) || (needsConsent && !consent.isSelected()));
        };
        scope.valueProperty().addListener((observable, before, after) -> {
            consent.setSelected(false);
            refresh.run();
        });
        consent.selectedProperty().addListener((observable, before, after) -> refresh.run());
        refresh.run();
        dialog.setResultConverter(button -> button == next && !continueButton.isDisabled()
                ? new Selection(scope.getValue(), consent.isSelected()) : null);
        return dialog;
    }
}
```

- [x] **Step 4: 跑 GREEN 并提交**

Run: `./gradlew test --tests com.datacube.fx.ResultExportOptionsDialogTest --no-daemon --console=plain`。Expected: 两项通过且未因 headless 跳过。

```powershell
git add src/com/datacube/fx/ResultExportOptionsDialog.java test/com/datacube/fx/ResultExportOptionsDialogTest.java
git commit -m "feat(export): confirm result scope and display-only values"
```

## Task 6: 在 FX 线程提交搜索并捕获正确展示顺序

**Files**

- Modify: `src/com/datacube/fx/SqlResultToolbar.java`，`commitSearchInput` 旁增加显式 flush
- Modify: `src/com/datacube/fx/SqlEditorPane.java`，结果字段、`renderResultFilterSnapshot`、新增捕获方法
- Test: `test/com/datacube/fx/SqlEditorResultFilterContractTest.java`（复用现有 `PaneFixture` / `showQuery` / `resultTable` / `field`）
- Test: `test/com/datacube/fx/SqlResultToolbarTest.java`

**Interfaces**

- Produces: `SqlResultToolbar.flushPendingSearch()` → boolean，返回是否确实提交了新搜索；重复调用不重播。
- Produces: 包内 `SqlEditorPane.captureResultExportSnapshot()` → nullable `ResultExportSnapshot`，仅 FX 线程。
- 映射：`IdentityHashMap<ObservableList<Object>, Integer> resultRowIndexes`，每个 TableView 行对象对应活动结果位置。

- [x] **Step 1: 在现有 Pane 契约测试类加入展示测试**

新增 import：

```java
import com.datacube.sqleditor.result.ResultExportScope;
import com.datacube.sqleditor.result.ResultExportSnapshot;
```

新增方法，复用该测试类已有 fixture，不另建真实连接：

```java
@Test
void exportFlushKeepsSortColumnOrderAndUsesFrozenVisibleRows() throws Exception {
    try (PaneFixture fixture = new PaneFixture(null, null)) {
        ResultExportSnapshot captured = FxUiTestSupport.call(() -> {
            QueryResult active = QueryResult.query(List.of("name", "score", "hidden"),
                    List.of(List.of("Ada", 1, "private"), List.of("Ada", 3, "private"),
                            List.of("Bob", 9, "private")), 1);
            showQuery(fixture.pane, active, "select name, score, hidden from export_source");
            TableView<ObservableList<Object>> table = resultTable(fixture.pane);
            var sequence = table.getColumns().get(0);
            var name = table.getColumns().get(1);
            var score = table.getColumns().get(2);
            var hidden = table.getColumns().get(3);
            hidden.setVisible(false);
            table.getColumns().setAll(sequence, score, name, hidden);
            score.setSortType(TableColumn.SortType.DESCENDING);
            table.getSortOrder().setAll(score);
            table.sort();
            ((TextField) fixture.pane.getNode().lookup("#sql-result-search")).setText("Ada");
            ResultExportSnapshot snapshot = fixture.pane.captureResultExportSnapshot();
            assertEquals(List.of(score, name), table.getVisibleLeafColumns().stream()
                    .filter(column -> column.getUserData() instanceof Integer).toList());
            assertEquals(List.of(score), table.getSortOrder());
            assertEquals(TableColumn.SortType.DESCENDING, score.getSortType());
            assertEquals(List.of(List.of(3, "Ada"), List.of(1, "Ada")),
                    snapshot.rows(ResultExportScope.CURRENT_FILTERED));
            showQuery(fixture.pane, QueryResult.query(List.of("other"), List.of(List.of(99)), 1),
                    "select other from newer_source");
            return snapshot;
        });
        assertEquals("select name, score, hidden from export_source", captured.originalSql());
        assertEquals(List.of(List.of(1, "Ada"), List.of(3, "Ada"), List.of(9, "Bob")),
                captured.rows(ResultExportScope.ALL_LOADED));
    }
}
```

- [x] **Step 2: 在 Toolbar 测试类加入防抖次数测试**

```java
@Test
void explicitExportFlushCommitsOnceAndCancelsPendingDebounce() throws Exception {
    AtomicInteger searches = new AtomicInteger();
    AtomicReference<SqlResultToolbar> reference = new AtomicReference<>();
    FxUiTestSupport.call(() -> {
        SqlResultToolbar toolbar = toolbar(searches, new AtomicInteger());
        reference.set(toolbar);
        ((TextField) toolbar.getNode().lookup("#sql-result-search")).setText("pending");
        assertTrue(toolbar.flushPendingSearch());
        assertFalse(toolbar.flushPendingSearch());
        assertEquals(1, searches.get());
        return null;
    });
    CountDownLatch elapsed = new CountDownLatch(1);
    FxUiTestSupport.call(() -> {
        PauseTransition delay = new PauseTransition(Duration.millis(250));
        delay.setOnFinished(event -> elapsed.countDown());
        delay.play();
        return null;
    });
    assertTrue(elapsed.await(5, TimeUnit.SECONDS));
    FxUiTestSupport.call(() -> {
        assertEquals(1, searches.get());
        assertFalse(reference.get().flushPendingSearch());
        return null;
    });
}
```

这是对防抖时间本身的 FX 定时测试，不使用线程 sleep；平台等待有 5 秒上限。该类已导入所需 AtomicReference/PauseTransition/Duration/CountDownLatch。

- [x] **Step 3: 跑 RED**

Run: `./gradlew test --tests "*SqlEditorResultFilterContractTest.exportFlushKeepsSortColumnOrderAndUsesFrozenVisibleRows" --tests "*SqlResultToolbarTest.explicitExportFlushCommitsOnceAndCancelsPendingDebounce" --no-daemon --console=plain`。Expected: 缺少两个公开/包内方法；补齐方法后必须实际执行矩阵断言。

- [x] **Step 4: 加入 flush 和位置映射**

在 `SqlResultToolbar` 的 `commitSearchInput` 前加入：

```java
public boolean flushPendingSearch() {
    boolean changed = !Objects.equals(committedSearch, search.getText());
    commitSearchInput();
    return changed;
}
```

在 `SqlEditorPane` 增加 import 和字段：

```java
import com.datacube.sqleditor.result.ResultExportSnapshot;
import java.util.IdentityHashMap;
```

```java
private final Map<ObservableList<Object>, Integer> resultRowIndexes = new IdentityHashMap<>();
```

在 `renderResultFilterSnapshot(ResultFilterState.Snapshot snapshot)` 开头加入 `resultRowIndexes.clear();`；用下列完整循环替换构建 `data` 的原循环：

```java
ObservableList<ObservableList<Object>> data = FXCollections.observableArrayList();
for (int rowIndex : snapshot.visibleRowIndexes()) {
    if (rowIndex < 0 || rowIndex >= active.rows.size()) continue;
    ObservableList<Object> row = FXCollections.observableArrayList(active.rows.get(rowIndex));
    resultRowIndexes.put(row, rowIndex);
    data.add(row);
}
```

在 `clearResultFilterState()` 开头，以及 `finalizeCloseOnFx` 的 compareAndSet 成功后，各加入以下语句，以免保留旧行引用：

```java
resultRowIndexes.clear();
```

- [x] **Step 5: 加入捕获方法，原列对象和排序定义都保留**

```java
ResultExportSnapshot captureResultExportSnapshot() {
    if (!Platform.isFxApplicationThread())
        throw new IllegalStateException("Export capture requires FX thread");
    var before = resultFilterState.snapshot();
    QueryResult active = before.activeResult();
    if (active == null || active.kind != QueryResult.Kind.QUERY) return null;
    List<TableColumn<ObservableList<Object>, ?>> columns =
            new ArrayList<>(resultTable.getColumns());
    List<TableColumn<ObservableList<Object>, ?>> sorting =
            new ArrayList<>(resultTable.getSortOrder());
    Map<TableColumn<ObservableList<Object>, ?>, TableColumn.SortType> sortTypes =
            new IdentityHashMap<>();
    for (var column : sorting) sortTypes.put(column, column.getSortType());
    boolean flushed = resultToolbar.flushPendingSearch();
    var state = resultFilterState.snapshot();
    if (state.activeResult() != active)
        throw new IllegalStateException("Result changed during export capture");
    if (flushed) {
        resultTable.getColumns().setAll(columns);
        sortTypes.forEach(TableColumn::setSortType);
        resultTable.getSortOrder().setAll(sorting);
        resultTable.sort();
    }
    List<Integer> rowPositions = new ArrayList<>();
    for (var row : resultTable.getItems()) {
        Integer position = resultRowIndexes.get(row);
        if (position == null) throw new IllegalStateException("Missing result row identity");
        rowPositions.add(position);
    }
    List<ResultExportSnapshot.Column> projection = new ArrayList<>();
    for (var column : resultTable.getVisibleLeafColumns()) {
        if (column.getUserData() instanceof Integer position && position >= 0)
            projection.add(new ResultExportSnapshot.Column(position,
                    Objects.toString(column.getProperties().get("sql-result-label"), "")));
    }
    return ResultExportSnapshot.capture(active, state.originalSql(), rowPositions, projection);
}
```

只在 flush 确实刷新结果时恢复旧列对象；无待提交搜索时不动列、排序或选择。不是按显示字符串重建类型，不修改 SQL 选区复制。过滤匹配导致某行自然消失时不承诺保留该行选择。

- [x] **Step 6: 跑 GREEN 与筛选回归并提交**

Run: `./gradlew test --tests com.datacube.fx.SqlEditorResultFilterContractTest --tests com.datacube.fx.SqlResultToolbarTest --no-daemon --console=plain`。Expected: 全部通过且无新的 skip。

```powershell
git add src/com/datacube/fx/SqlResultToolbar.java src/com/datacube/fx/SqlEditorPane.java test/com/datacube/fx/SqlEditorResultFilterContractTest.java test/com/datacube/fx/SqlResultToolbarTest.java
git commit -m "fix(export): preserve presentation while flushing pending result search"
```

## Task 7: 共用导出协调器与编辑器薄接线

**Files**

- Create: `src/com/datacube/fx/SqlResultExportCoordinator.java`
- Modify: `src/com/datacube/fx/SqlEditorPane.java`，导出菜单、`exportAs`、`onCopyInsert`、`statusBar`、`closeResources`
- Test: `test/com/datacube/fx/SqlResultExportCoordinatorTest.java`
- Modify/Test: `test/com/datacube/fx/SqlEditorResultFilterContractTest.java`，已有 INSERT 复制测试适配新增范围确认

**Interfaces**

- Consumes: `FxTaskScope.submit(Callable<T>, Consumer<? super T>, Consumer<? super Throwable>)`、`isClosed()`；Task 1–6 的接口。
- Produces: `export(QueryResultFileWriter.Format)` → nullable `Future<?>`、`copyInsert()` → boolean、`close()`。
- 注入 `Supplier<ResultExportSnapshot> capture`、`LongSupplier revision`、`BiConsumer<String,Boolean> status`、`Predicate<String> clipboard`；boolean 表示错误样式。
- `Ui`：`chooseScope(ResultExportSnapshot,boolean)` → `Optional<Selection>`；`chooseFile(Format)` → nullable Path；`chooseTable(String)` → nullable String；`confirmOverwrite(Path)` → boolean。
- `FileJob.write(Request, ResultExportOperation) throws Exception` → Path；`Request` 保存固定 target/format/snapshot/selection/table，并使用不含 SQL/数据的 `toString`。
- 仅 FX 调用 export/copyInsert；close 可被后台关闭守卫调用，不碰 FX 控件。

- [x] **Step 1: 写取消、剪贴板和新结果隔离测试**

```java
package com.datacube.fx;

import com.datacube.export.*;
import com.datacube.fx.task.*;
import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultExportCoordinatorTest {
    @TempDir Path directory;
    private ResultExportSnapshot snapshot() {
        return ResultExportSnapshot.capture(
                QueryResult.query(List.of("id"), List.of(List.of(1), List.of(2)), 1),
                "select id from captured_table", List.of(1),
                List.of(new ResultExportSnapshot.Column(0, "id")));
    }
    private final class Ui implements SqlResultExportCoordinator.Ui {
        boolean cancelScope, cancelFile, overwrite = true;
        String capturedSql;
        @Override public Optional<ResultExportOptionsDialog.Selection> chooseScope(
                ResultExportSnapshot snapshot, boolean sql) {
            return cancelScope ? Optional.empty() : Optional.of(
                    new ResultExportOptionsDialog.Selection(ResultExportScope.CURRENT_FILTERED, false));
        }
        @Override public Path chooseFile(QueryResultFileWriter.Format format) {
            return cancelFile ? null : directory.resolve("result.csv");
        }
        @Override public String chooseTable(String sql) {
            capturedSql = sql;
            return "captured_table";
        }
        @Override public boolean confirmOverwrite(Path path) { return overwrite; }
    }
    @Test void cancellingEitherDialogDoesNotSubmitAndInsertUsesCapturedScope() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxTaskScope tasks = runner.scope();
            Ui ui = new Ui();
            AtomicInteger writes = new AtomicInteger();
            AtomicReference<String> clipboard = new AtomicReference<>();
            AtomicReference<String> status = new AtomicReference<>();
            var coordinator = new SqlResultExportCoordinator(tasks, this::snapshot, () -> 0L,
                    (text, error) -> status.set(text), text -> { clipboard.set(text); return true; },
                    ui, (request, operation) -> { writes.incrementAndGet(); return request.target().path(); });
            FxUiTestSupport.call(() -> {
                ui.cancelScope = true;
                assertNull(coordinator.export(QueryResultFileWriter.Format.CSV));
                assertFalse(coordinator.copyInsert());
                ui.cancelScope = false;
                ui.cancelFile = true;
                assertNull(coordinator.export(QueryResultFileWriter.Format.CSV));
                assertEquals(0, writes.get());
                assertNull(clipboard.get());
                assertTrue(coordinator.copyInsert());
                assertEquals("select id from captured_table", ui.capturedSql);
                assertEquals("INSERT INTO captured_table (id) VALUES (2);\n", clipboard.get());
                return null;
            });
            coordinator.close();
            tasks.close();
        }
    }
    @Test void lateExportDoesNotOverwriteNewerStatusAndDuplicateIsNotSubmitted() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxTaskScope tasks = runner.scope();
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicLong revision = new AtomicLong();
            AtomicReference<String> status = new AtomicReference<>("ready");
            AtomicInteger jobs = new AtomicInteger();
            var coordinator = new SqlResultExportCoordinator(tasks, this::snapshot, revision::get,
                    (text, error) -> { status.set(text); revision.incrementAndGet(); },
                    text -> true, new Ui(), (request, operation) -> {
                        assertFalse(Platform.isFxApplicationThread());
                        jobs.incrementAndGet();
                        started.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        assertEquals(List.of(List.of(2)), request.snapshot().rows(request.selection().scope()));
                        return request.target().path();
                    });
            try {
                Future<?> future = FxUiTestSupport.call(
                        () -> coordinator.export(QueryResultFileWriter.Format.CSV));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    assertNull(coordinator.export(QueryResultFileWriter.Format.CSV));
                    status.set("new query");
                    revision.incrementAndGet();
                    return null;
                });
                release.countDown();
                future.get(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(() -> {
                    assertEquals("new query", status.get());
                    assertEquals(1, jobs.get());
                    return null;
                });
            } finally {
                release.countDown();
                coordinator.close();
                tasks.close();
            }
        }
    }
}
```

- [x] **Step 2: 跑 RED**

Run: `./gradlew test --tests com.datacube.fx.SqlResultExportCoordinatorTest --no-daemon --console=plain`。Expected: 缺少协调器。

- [x] **Step 3: 实现协调器及固定提示**

```java
package com.datacube.fx;

import com.datacube.export.*;
import com.datacube.export.QueryResultFileWriter.Format;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.sqleditor.InsertSqlGenerator;
import com.datacube.sqleditor.result.*;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class SqlResultExportCoordinator implements AutoCloseable {
    interface Ui {
        Optional<ResultExportOptionsDialog.Selection> chooseScope(ResultExportSnapshot snapshot, boolean sql);
        Path chooseFile(Format format);
        String chooseTable(String originalSql);
        boolean confirmOverwrite(Path path);
    }
    record Request(SafeResultFilePublisher.Target target, Format format, ResultExportSnapshot snapshot,
                   ResultExportOptionsDialog.Selection selection, String table) {
        @Override public String toString() { return "ResultExportRequest[" + format + "]"; }
    }
    @FunctionalInterface interface FileJob {
        Path write(Request request, ResultExportOperation operation) throws Exception;
    }
    private final FxTaskScope tasks;
    private final Supplier<ResultExportSnapshot> capture;
    private final LongSupplier revision;
    private final BiConsumer<String, Boolean> status;
    private final Predicate<String> clipboard;
    private final Ui ui;
    private final FileJob job;
    private final ResultExportSession session = new ResultExportSession();
    private ResultExportOperation latest;

    SqlResultExportCoordinator(FxTaskScope tasks, Supplier<ResultExportSnapshot> capture,
            LongSupplier revision, BiConsumer<String, Boolean> status, Predicate<String> clipboard,
            Supplier<Window> owner) {
        this(tasks, capture, revision, status, clipboard, new Dialogs(owner),
                (request, operation) -> new SafeResultFilePublisher().publish(request.target(), operation,
                        (temporary, token) -> QueryResultFileWriter.write(temporary, request.format(),
                                request.snapshot(), request.selection().scope(),
                                request.selection().displayConfirmed(), request.table(), token)));
    }
    SqlResultExportCoordinator(FxTaskScope tasks, Supplier<ResultExportSnapshot> capture,
            LongSupplier revision, BiConsumer<String, Boolean> status, Predicate<String> clipboard,
            Ui ui, FileJob job) {
        this.tasks = Objects.requireNonNull(tasks);
        this.capture = Objects.requireNonNull(capture);
        this.revision = Objects.requireNonNull(revision);
        this.status = Objects.requireNonNull(status);
        this.clipboard = Objects.requireNonNull(clipboard);
        this.ui = Objects.requireNonNull(ui);
        this.job = Objects.requireNonNull(job);
    }
    private boolean open() { return !session.isClosed() && !tasks.isClosed(); }
    private boolean ownsStatus(ResultExportOperation operation, long stamp) {
        return open() && latest == operation && revision.getAsLong() == stamp;
    }
    private static boolean permitted(ResultExportSnapshot snapshot,
            ResultExportOptionsDialog.Selection selection, boolean sql) {
        var rows = snapshot.rows(selection.scope());
        if (rows.isEmpty() || snapshot.columns().isEmpty()) return false;
        boolean scalar = ResultExportValuePolicy.assess(rows).sqlAllowed();
        return scalar || (!sql && selection.displayConfirmed());
    }
    Future<?> export(Format format) {
        if (!open()) return null;
        ResultExportOperation operation = session.begin();
        if (operation == null) return null;
        latest = operation;
        boolean submitted = false;
        long ownerRevision = revision.getAsLong();
        try {
            ResultExportSnapshot snapshot = capture.get();
            ownerRevision = revision.getAsLong();
            if (snapshot == null) {
                status.accept("没有可导出的查询结果", true);
                return null;
            }
            var selection = ui.chooseScope(snapshot, format == Format.SQL);
            if (selection.isEmpty() || !open()) return null;
            if (!permitted(snapshot, selection.get(), format == Format.SQL)) {
                if (ownsStatus(operation, ownerRevision)) status.accept("当前范围或值类型不能导出", true);
                return null;
            }
            String table = format == Format.SQL ? ui.chooseTable(snapshot.originalSql()) : null;
            if ((format == Format.SQL && table == null) || !open()) return null;
            Path chosen = ui.chooseFile(format);
            if (chosen == null || !open()) return null;
            var target = SafeResultFilePublisher.capture(chosen);
            if (target.existed() && !ui.confirmOverwrite(target.path())) return null;
            if (!open()) return null;
            operation.check();
            var request = new Request(target, format, snapshot, selection.get(), table);
            boolean statusOwned = ownsStatus(operation, ownerRevision);
            if (statusOwned) status.accept("导出中...", false);
            final long completionRevision = statusOwned ? revision.getAsLong() : Long.MIN_VALUE;
            Future<?> future = tasks.submit(() -> {
                try { return job.write(request, operation); }
                finally { session.finish(operation); }
            }, published -> {
                if (ownsStatus(operation, completionRevision))
                    status.accept("已导出: " + published, false);
            }, failure -> {
                if (ownsStatus(operation, completionRevision))
                    status.accept(failureMessage(failure), true);
            });
            submitted = true;
            return future;
        } catch (RejectedExecutionException rejected) {
            if (open() && latest == operation) status.accept("导出任务未能启动，请重试", true);
            return null;
        } catch (CancellationException cancelled) {
            return null;
        } catch (Exception failure) {
            if (ownsStatus(operation, ownerRevision)) status.accept(failureMessage(failure), true);
            return null;
        } finally {
            if (!submitted) {
                operation.cancel();
                session.finish(operation);
            }
        }
    }
    boolean copyInsert() {
        if (!open()) return false;
        long ownerRevision = revision.getAsLong();
        try {
            var snapshot = capture.get();
            ownerRevision = revision.getAsLong();
            if (snapshot == null) return false;
            var selection = ui.chooseScope(snapshot, true);
            if (selection.isEmpty() || !open()) return false;
            if (!permitted(snapshot, selection.get(), true)) {
                if (revision.getAsLong() == ownerRevision) status.accept("当前范围或值类型不能生成 INSERT", true);
                return false;
            }
            String table = ui.chooseTable(snapshot.originalSql());
            if (table == null || !open()) return false;
            String script = QueryResultFileWriter.insert(snapshot, selection.get().scope(), table);
            if (!open()) return false;
            boolean written = clipboard.test(script);
            if (open() && revision.getAsLong() == ownerRevision)
                status.accept(written ? "已复制 " + snapshot.rows(selection.get().scope()).size()
                        + " 条 INSERT 语句" : "复制失败：无法写入系统剪贴板", !written);
            return written;
        } catch (RuntimeException failure) {
            if (open() && revision.getAsLong() == ownerRevision)
                status.accept("复制失败：无法生成或写入 INSERT", true);
            return false;
        }
    }
    private static String failureMessage(Throwable failure) {
        if (failure instanceof SafeResultFilePublisher.Failure safe) {
            return switch (safe.stage()) {
                case PREPARE -> "无法安全保存：请选择本地普通文件";
                case TARGET_CHANGED -> "目标文件已改变，请重新选择并确认";
                case TARGET_BUSY -> "目标文件正在导出，请稍后重试";
                case WRITE -> "导出写入失败，原目标文件未修改";
                case PUBLISH -> "无法原子发布导出文件，原目标文件未修改";
                case CLEANUP -> "导出未完成，临时文件清理失败，请手动处理: " + safe.temporaryPath();
            };
        }
        return "导出失败，未发布结果文件";
    }
    @Override public void close() { session.close(); }

    private static final class Dialogs implements Ui {
        private final Supplier<Window> owner;
        private Dialogs(Supplier<Window> owner) { this.owner = owner; }
        @Override public Optional<ResultExportOptionsDialog.Selection> chooseScope(
                ResultExportSnapshot snapshot, boolean sql) {
            return ResultExportOptionsDialog.create(owner.get(), snapshot, sql).showAndWait();
        }
        @Override public Path chooseFile(Format format) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("导出结果 - " + format.label);
            File directory = FxFiles.defaultSaveDir();
            if (directory != null) chooser.setInitialDirectory(directory);
            chooser.setInitialFileName(format.defaultName);
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(format.filterDesc, format.filterExt));
            File chosen = chooser.showSaveDialog(owner.get());
            return chosen == null ? null : chosen.toPath();
        }
        @Override public String chooseTable(String originalSql) {
            String table = InsertSqlGenerator.singleTableName(originalSql);
            if (table != null) return table;
            TextInputDialog dialog = new TextInputDialog();
            if (owner.get() != null) dialog.initOwner(owner.get());
            dialog.setTitle("指定目标表");
            dialog.setHeaderText("无法确定单一来源表，请输入 INSERT 目标表名（可带 schema 前缀）");
            dialog.setContentText("表名:");
            return dialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
        }
        @Override public boolean confirmOverwrite(Path path) {
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                    "导出成功后替换此文件；失败时保留原文件。\n" + path, ButtonType.OK, ButtonType.CANCEL);
            if (owner.get() != null) dialog.initOwner(owner.get());
            dialog.setTitle("确认替换文件");
            dialog.setHeaderText("目标文件已存在");
            return dialog.showAndWait().filter(ButtonType.OK::equals).isPresent();
        }
    }
}
```

注意：保存对话框的原生覆盖提示不能代替本次明确确认；版本 stamp 在自定义确认之前捕获，不能在后台重新 capture 后把已变化的文件视为被用户确认。

- [x] **Step 4: 跑协调器 GREEN**

Run: `./gradlew test --tests com.datacube.fx.SqlResultExportCoordinatorTest --no-daemon --console=plain`。Expected: 两项通过。返回 Future 只为可控等待/测试，事件调用方不在 FX 线程阻塞等待。

- [x] **Step 5: 用协调器替换 Pane 内巨型事件处理器**

新增 import：

```java
import com.datacube.export.QueryResultFileWriter.Format;
```

新增字段：

```java
private SqlResultExportCoordinator resultExports;
private long resultStatusRevision;
```

在构造器 `build();` 后加入：

```java
resultExports = new SqlResultExportCoordinator(tasks, this::captureResultExportSnapshot,
        () -> resultStatusRevision, (text, error) -> {
            statusLabel.setText(text);
            statusLabel.setStyle(error
                    ? "-fx-text-fill: -status-error; -fx-font-size: 12px;"
                    : "-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        }, this::writeClipboard,
        () -> root.getScene() == null ? null : root.getScene().getWindow());
construction.own(resultExports::close);
```

在 `statusBar` 创建 `statusLabel` 后加入：

```java
statusLabel.textProperty().addListener((observable, before, after) -> resultStatusRevision++);
```

在 `renderResultFilterSnapshot(ResultFilterState.Snapshot snapshot)` 开头加入：

```java
resultStatusRevision++;
```

在 `clearResultFilterState()` 开头也加入上述递增，覆盖清空及非查询结果切换。每次结果更新都会失效旧导出状态，即使新状态字符串刚好相同；普通状态文本变化也失效旧导出。

在 `closeResources` 的 `if (resourcesClosed.get()) return;` 后加入：

```java
if (resultExports != null) resultExports.close();
```

这是已接受关闭后的资源阶段，不放进可被用户取消的初始关闭询问。先封住导出/取消发布，再关闭 task scope。

删除旧私有 `ExportFormat` 枚举；菜单循环类型替换为：

```java
for (Format fmt : Format.values()) {
    MenuItem item = new MenuItem(fmt.label);
    item.setOnAction(event -> exportAs(fmt));
    exportResultBtn.getItems().add(item);
}
```

用下列方法替换旧 `exportAs` 和 `onCopyInsert`，删除旧 `writeExportFile` / `resolveInsertTable`：

```java
private void exportAs(Format format) {
    if (resultExports != null) resultExports.export(format);
}

private void onCopyInsert() {
    if (resultExports != null) resultExports.copyInsert();
}
```

顶部及右键继续调用同一个 `onCopyInsert`；`writeClipboard` / `setClipboardWriterForTesting` / TSV 复制路径保持不变。删除这次替换后未使用的旧格式、I/O、FileChooser imports，先用下列命令确认其在本文件已无其他用途：

```powershell
rg -n "ResultExporter|XlsxWriter|InsertSqlGenerator|FileChooser|FileOutputStream|OutputStreamWriter|StandardCharsets|\bWriter\b|\bFile\b" src/com/datacube/fx/SqlEditorPane.java
```

这是机械 import 清理，不修改其他引用或共享格式实现。

- [x] **Step 6: 跑集成 GREEN 并提交**

在运行前，替换旧测试 `clipboardFailureNeverClaimsSuccessAndInsertCopyUsesTheSameSeam`，让其明确确认新增弹窗，并实际覆盖顶部/右键两个入口；不能让原来的反射调用在未处理的模态对话框中超时。加入 helper：

```java
private static void confirmNextResultExport(AtomicInteger confirmations) {
    javafx.application.Platform.runLater(() -> {
        for (javafx.stage.Window window : List.copyOf(javafx.stage.Window.getWindows())) {
            if (window.getScene() == null) continue;
            javafx.scene.Node node = window.getScene().getRoot().lookup("#result-export-continue");
            if (node instanceof Button button && !button.isDisabled()) {
                confirmations.incrementAndGet();
                button.fire();
                return;
            }
        }
        throw new AssertionError("Expected enabled result export confirmation");
    });
}

@Test
void clipboardFailureNeverClaimsSuccessAndInsertCopyUsesTheSameSeam() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>("unchanged");
    AtomicInteger confirmations = new AtomicInteger();
    try (PaneFixture fixture = new PaneFixture(null, null)) {
        FxUiTestSupport.call(() -> {
            showQuery(fixture.pane, result(false, row("Ada", 7, "2026-08-29 10:11:12")),
                    "select * from people");
            TableView<ObservableList<Object>> table = resultTable(fixture.pane);
            table.getSelectionModel().clearAndSelect(0, table.getColumns().get(1));
            fixture.pane.setClipboardWriterForTesting(ignored -> false);
            ((MenuButton) fixture.pane.getNode().lookup("#sql-result-copy")).getItems().get(0).fire();
            assertEquals("unchanged", captured.get());
            assertEquals("复制失败：无法写入系统剪贴板", labelText(fixture.pane, "statusLabel"));
            confirmNextResultExport(confirmations);
            ((Button) field(fixture.pane, "copyInsertBtn")).fire();
            assertEquals(1, confirmations.get());
            assertEquals("unchanged", captured.get());
            assertEquals("复制失败：无法写入系统剪贴板", labelText(fixture.pane, "statusLabel"));
            fixture.pane.setClipboardWriterForTesting(text -> { captured.set(text); return true; });
            confirmNextResultExport(confirmations);
            table.getContextMenu().getItems().get(1).fire();
            assertEquals(2, confirmations.get());
            assertTrue(captured.get().startsWith("INSERT INTO people"));
            assertTrue(labelText(fixture.pane, "statusLabel").startsWith("已复制 1 条 INSERT"));
            return null;
        });
    }
}
```

Run: `./gradlew test --tests com.datacube.fx.SqlResultExportCoordinatorTest --tests com.datacube.fx.SqlEditorResultFilterContractTest --tests com.datacube.fx.SqlResultToolbarTest --tests com.datacube.fx.ResultExportOptionsDialogTest --no-daemon --console=plain`。Expected: 所有测试通过，原有 TSV/数据库筛选契约不退化。

```powershell
git add src/com/datacube/fx/SqlResultExportCoordinator.java src/com/datacube/fx/SqlEditorPane.java test/com/datacube/fx/SqlResultExportCoordinatorTest.java test/com/datacube/fx/SqlEditorResultFilterContractTest.java
git commit -m "feat(sql-editor): share safe export flow across files and INSERT copy"
```

## Task 8: 故障注入、完整回归与产品说明

**Files**

- Modify: `test/com/datacube/export/SafeResultFilePublisherTest.java`
- Modify: `test/com/datacube/export/ResultExportSessionTest.java`
- Modify: `test/com/datacube/export/QueryResultFileWriterTest.java`
- Modify: `test/com/datacube/fx/SqlResultExportCoordinatorTest.java`
- Modify: `test/com/datacube/sqleditor/result/ResultExportSnapshotTest.java`
- Modify: `README.md`
- Create: `docs/superpowers/verification/2026-08-30-safe-result-export.md`

**Interfaces**

- 消费前述所有已定义接口；不增加生产接口。
- 产出：实际的精确文件字节、终态、回调次数、格式内容和测试统计证据。下列是加在已定义测试类中的完整测试方法，不另行复制 fixture。

- [x] **Step 1: 发布器增加关闭流失败、锁冲突和清理失败用例**

加入 `SafeResultFilePublisherTest`：

```java
@Test void streamCloseFailurePreservesTarget() throws Exception {
    Path target = Files.writeString(directory.resolve("result.csv"), "old");
    var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> publisher().publish(
            SafeResultFilePublisher.capture(target), new ResultExportOperation(), (path, operation) -> {
                try (var writer = new java.io.FilterWriter(Files.newBufferedWriter(path)) {
                    @Override public void close() throws IOException {
                        super.close();
                        throw new IOException("close sentinel");
                    }
                }) { writer.write("new"); }
            }));
    assertEquals(SafeResultFilePublisher.Stage.WRITE, failure.stage());
    assertEquals("old", Files.readString(target));
}

@Test void sameCanonicalTargetIsExclusiveAndLockIsReleased() throws Exception {
    Path target = directory.resolve("result.csv");
    var selected = SafeResultFilePublisher.capture(target);
    var alias = SafeResultFilePublisher.capture(directory.resolve(".").resolve("result.csv"));
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var worker = new java.util.concurrent.FutureTask<Path>(() ->
            publisher().publish(selected, new ResultExportOperation(), (path, operation) -> {
                Files.writeString(path, "first");
                started.countDown();
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            }));
    Thread.ofVirtual().start(worker);
    try {
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () ->
                publisher().publish(alias, new ResultExportOperation(),
                        (path, operation) -> fail("Second writer must not start")));
        assertEquals(SafeResultFilePublisher.Stage.TARGET_BUSY, failure.stage());
        release.countDown();
        worker.get(5, java.util.concurrent.TimeUnit.SECONDS);
        publisher().publish(SafeResultFilePublisher.capture(target), new ResultExportOperation(),
                (path, operation) -> Files.writeString(path, "second"));
        assertEquals("second", Files.readString(target));
    } finally {
        release.countDown();
        worker.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}

@Test void cleanupFailureReportsOnlyOwnedPathAndFixedStage() throws Exception {
    Path target = Files.writeString(directory.resolve("result.csv"), "old");
    var owned = new AtomicReference<Path>();
    var diagnosed = new AtomicReference<Path>();
    var brokenCleaner = new SafeResultFilePublisher((source, destination) ->
            fail("Failed writer cannot publish"),
            path -> { throw new IOException("private cleanup text"); }, diagnosed::set);
    var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> brokenCleaner.publish(
            SafeResultFilePublisher.capture(target), new ResultExportOperation(), (path, operation) -> {
                owned.set(path);
                Files.writeString(path, "partial");
                throw new IOException("private row value");
            }));
    assertEquals(SafeResultFilePublisher.Stage.CLEANUP, failure.stage());
    assertEquals(owned.get(), diagnosed.get());
    assertEquals(owned.get(), failure.temporaryPath());
    assertTrue(Files.exists(owned.get()));
    assertEquals("old", Files.readString(target));
    assertFalse(failure.getMessage().contains("private"));
    assertNull(failure.getCause());
}

@Test void directoriesAreRejected() {
    assertThrows(SafeResultFilePublisher.Failure.class,
            () -> SafeResultFilePublisher.capture(directory));
}

@Test void symbolicLinkTargetIsRejectedWithoutTouchingDestination() throws Exception {
    Path actual = Files.writeString(directory.resolve("actual.csv"), "old");
    Path link = directory.resolve("link.csv");
    try {
        Files.createSymbolicLink(link, actual);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
        org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Symbolic link creation unavailable for this test account");
    }
    assertThrows(SafeResultFilePublisher.Failure.class, () -> SafeResultFilePublisher.capture(link));
    assertEquals("old", Files.readString(actual));
}
```

链接测试可因明确的账户/平台能力跳过，必须记录；不能因此跳过目录、目标变化或原子失败测试。JUnit 仅清理自己创建的 `@TempDir`，不扫描用户目录。

- [x] **Step 2: 验证取消和发布真正并发时的门禁**

加入 `ResultExportSessionTest`：

```java
@Test void cancellationCannotUndoAnAtomicPublicationAlreadyInsideTheGate() throws Exception {
    var operation = new ResultExportOperation();
    var publishing = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var cancelCalled = new java.util.concurrent.CountDownLatch(1);
    var committed = new AtomicInteger();
    var publication = new java.util.concurrent.FutureTask<Void>(() -> {
        operation.publish(() -> {
            publishing.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            committed.incrementAndGet();
        });
        return null;
    });
    Thread.ofVirtual().start(publication);
    try {
        assertTrue(publishing.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var cancellation = new java.util.concurrent.FutureTask<Boolean>(() -> {
            cancelCalled.countDown();
            return operation.cancel();
        });
        Thread.ofVirtual().start(cancellation);
        assertTrue(cancelCalled.await(5, java.util.concurrent.TimeUnit.SECONDS));
        release.countDown();
        publication.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(cancellation.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, committed.get());
        assertTrue(operation.published());
    } finally {
        release.countDown();
        publication.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
```

- [x] **Step 3: 新增活动结果/截断、HTML/XML 回归**

加入 `ResultExportSnapshotTest`：

```java
@Test void allLoadedMeansActiveNotCachedOriginalAndTruncationIsCaptured() {
    var columns = List.of(new com.datacube.spi.model.ResultColumn(0, "id", java.sql.Types.INTEGER, "int4"));
    var original = QueryResult.queryWithMetadata(columns,
            List.of(List.of(1), List.of(2), List.of(3)), 1, false);
    var active = QueryResult.queryWithMetadata(columns, List.of(List.of(2)), 1, true);
    var state = new ResultFilterState();
    state.showOriginal(original, "select id from t", null);
    state.appendCondition(new FilterCondition(0, FilterConnector.AND, FilterOperator.IS_NOT_NULL, null));
    var request = state.databaseRequest();
    assertTrue(state.databaseApplied(request.generation(), active));
    var current = state.snapshot();
    var captured = ResultExportSnapshot.capture(current.activeResult(), current.originalSql(),
            current.visibleRowIndexes(), List.of(new ResultExportSnapshot.Column(0, "id")));
    assertSame(original, current.originalResult());
    assertEquals(List.of(List.of(2)), captured.rows(ResultExportScope.ALL_LOADED));
    assertTrue(captured.truncated());
    state.showOriginal(original, "select id from other", null);
    assertTrue(captured.truncated());
    assertEquals(1, captured.rows(ResultExportScope.ALL_LOADED).size());
}
```

加入 `QueryResultFileWriterTest`：

```java
@Test void htmlAndXmlRetainEscapingUnicodeAndFullScalarTime() throws Exception {
    var timestamp = java.time.LocalDateTime.of(2026, 8, 30, 12, 34, 56, 123456789);
    var snapshot = snapshot(List.of(timestamp, true, "甲<&\"\n乙"));
    Path html = directory.resolve("result.html");
    Path xml = directory.resolve("result.xml");
    QueryResultFileWriter.write(html, QueryResultFileWriter.Format.HTML, snapshot,
            ResultExportScope.CURRENT_FILTERED, false, null, new ResultExportOperation());
    QueryResultFileWriter.write(xml, QueryResultFileWriter.Format.XML, snapshot,
            ResultExportScope.CURRENT_FILTERED, false, null, new ResultExportOperation());
    for (Path path : List.of(html, xml)) {
        String text = Files.readString(path);
        assertTrue(text.contains("2026-08-30T12:34:56.123456789"));
        assertTrue(text.contains("甲&lt;&amp;&quot;\n乙"));
        assertFalse(text.contains("甲<&\""));
    }
}
```

- [x] **Step 4: 协调器增加拒绝恢复、关闭取消和剪贴板失败证据**

加入 `SqlResultExportCoordinatorTest`，复用其中 Ui/snapshot/directory：

```java
@Test void rejectedSubmissionReleasesBusyStateAndUsesFixedMessage() throws Exception {
    FxTaskRunner runner = new FxTaskRunner();
    FxTaskScope tasks = runner.scope();
    runner.close();
    var messages = new ArrayList<String>();
    var coordinator = new SqlResultExportCoordinator(tasks, this::snapshot, () -> 0L,
            (text, error) -> messages.add(text), text -> true, new Ui(),
            (request, operation) -> { fail("Closed runner cannot start work"); return null; });
    try {
        FxUiTestSupport.call(() -> {
            assertNull(coordinator.export(QueryResultFileWriter.Format.CSV));
            assertNull(coordinator.export(QueryResultFileWriter.Format.CSV));
            assertEquals(2, messages.stream().filter("导出任务未能启动，请重试"::equals).count());
            return null;
        });
    } finally {
        coordinator.close();
        tasks.close();
    }
}

@Test void closeCancelsUnpublishedFileAndSuppressesCompletionUi() throws Exception {
    try (FxTaskRunner runner = new FxTaskRunner()) {
        FxTaskScope tasks = runner.scope();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        var coordinator = new SqlResultExportCoordinator(tasks, this::snapshot, () -> 0L,
                (text, error) -> callbacks.incrementAndGet(), text -> true, new Ui(),
                (request, operation) -> new SafeResultFilePublisher().publish(
                        request.target(), operation, (temporary, token) -> {
                            Files.writeString(temporary, "partial");
                            started.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            token.check();
                        }));
        try {
            Future<?> future = FxUiTestSupport.call(() -> coordinator.export(QueryResultFileWriter.Format.CSV));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            int beforeClose = callbacks.get();
            coordinator.close();
            release.countDown();
            future.get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> {
                assertEquals(beforeClose, callbacks.get());
                assertNull(coordinator.export(QueryResultFileWriter.Format.CSV));
                assertFalse(coordinator.copyInsert());
                return null;
            });
            assertFalse(Files.exists(directory.resolve("result.csv")));
            try (var paths = Files.list(directory)) {
                assertEquals(0, paths.count());
            }
        } finally {
            release.countDown();
            coordinator.close();
            tasks.close();
        }
    }
}

@Test void clipboardFailureNeverReportsSuccessAndBlockedValuesNeverReachWriter() throws Exception {
    try (FxTaskRunner runner = new FxTaskRunner()) {
        FxTaskScope tasks = runner.scope();
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<String> message = new AtomicReference<>();
        var active = new AtomicReference<>(snapshot());
        var coordinator = new SqlResultExportCoordinator(tasks, active::get, () -> 0L,
                (text, error) -> message.set(text), text -> { writes.incrementAndGet(); return false; },
                new Ui(), (request, operation) -> { fail("No file export requested"); return null; });
        try {
            FxUiTestSupport.call(() -> {
                assertFalse(coordinator.copyInsert());
                assertEquals("复制失败：无法写入系统剪贴板", message.get());
                assertEquals(1, writes.get());
                active.set(ResultExportSnapshot.capture(
                        QueryResult.query(List.of("id"), List.of(List.of(Double.NaN)), 1),
                        "select id from t", List.of(0),
                        List.of(new ResultExportSnapshot.Column(0, "id"))));
                assertFalse(coordinator.copyInsert());
                assertEquals(1, writes.get());
                assertEquals("当前范围或值类型不能生成 INSERT", message.get());
                return null;
            });
        } finally {
            coordinator.close();
            tasks.close();
        }
    }
}
```

- [x] **Step 5: 跑所有新增边界测试**

```powershell
./gradlew test --tests com.datacube.export.SafeResultFilePublisherTest --tests com.datacube.export.ResultExportSessionTest --tests com.datacube.export.QueryResultFileWriterTest --tests com.datacube.fx.SqlResultExportCoordinatorTest --tests com.datacube.sqleditor.result.ResultExportSnapshotTest --no-daemon --console=plain
```

Expected: 全部通过，链接能力 skip 单独记录。若出现行为失败，先保留失败证据、定位根因，再修实现；不能删断言或扩大超时掩盖竞态。纯回归新增测试可以直接通过，但不得称其为已观察到 RED 的 TDD 用例。

- [x] **Step 6: 更新产品说明及建立验收记录**

README “导出”原功能条目替换为以下两行：

```markdown
- **查询结果导出**：支持 XLSX / CSV / SQL / HTML / XML；默认导出当前筛选后的全部可见行，并保留排序和可见列顺序，可明确切换到当前活动结果的全部已加载行。预览/特殊值需确认，不能无损生成 INSERT 的值会阻止 SQL 输出；不自动重新查询。
- **导出文件保护**：查询结果先写同目录临时文件，成功后才原子发布；覆盖前确认目标，写入/发布失败保留旧文件，不支持原子发布时拒绝保存。整表导出、pg_dump 和迁移保持各自原有行为，不在此保护承诺范围内。
```

新增验收文档的初始内容（随后以本轮实际结果更新状态，不沿用历史测试数字）：

```markdown
# 查询结果导出验收记录

日期：2026-08-30

状态：实施验收尚未完成。本文件不证明实现已经通过测试。

范围：SQL 编辑器 XLSX/CSV/SQL/HTML/XML 和复制 INSERT；不含整表、迁移、Redis、pg_dump。

## 自动化证据

- 记录本轮运行命令、退出码、套件数、测试总数、通过/失败/跳过数。
- 链接能力及 live 测试的每一项跳过都需说明原因；headless 导致的 UI 跳过不能记为通过。
- 文件失败注入应证明旧文件字节不变、仅本次临时文件被清理。

## 桌面验收

- 默认范围、全量选项、零匹配、隐藏/重排列、升降序、刚输入搜索就导出。
- 同样的数据分别从顶部和右键复制 INSERT，取消不写剪贴板。
- 行截断与预览值分别显示，SQL 拒绝特殊值，非 SQL 需显式确认。
- 深色/浅色、最小可用窗口、键盘 Tab/Enter/Esc 和长提示不遮挡按钮。
- 仅使用合成数据、临时目录，不打开公司数据库或用户已有导出文件。

## 交付限制

- 未验证项必须保留说明，不用自动化测试替代人工观察结论。
- 不保证跨库 SQL 恢复、不改变 CSV 公式策略、不承诺恶意外进程竞态或断电持久化。
- 本阶段不自动推送、合并或打 tag。
```

- [x] **Step 7: 完整 non-headless 回归并统计 XML**

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
./gradlew clean test --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL，0 failures、0 errors。运行后用只读统计（不硬编码通过数）：

```powershell
$exportSuites = @(Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml' | ForEach-Object { ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite })
$exportStats = [pscustomobject]@{
    Suites = $exportSuites.Count
    Tests = ($exportSuites | Measure-Object -Property tests -Sum).Sum
    Failures = ($exportSuites | Measure-Object -Property failures -Sum).Sum
    Errors = ($exportSuites | Measure-Object -Property errors -Sum).Sum
    Skipped = ($exportSuites | Measure-Object -Property skipped -Sum).Sum
}
$exportStats
git diff --check
```

在验收文档记录真实输出。若 JUnit XML 属性需转换数字，转换后复核总数；不能从 Gradle “UP-TO-DATE” 推断本轮新测试已执行。

- [ ] **Step 8: 桌面检查、最终审查并提交**

执行记录：最终全分支代码审查已通过，无未解决发现；代码及文档保留为本地提交。首次桌面尝试遇到 `GetCursorPos` 拒绝访问；续验已实际保存两份 CSV，核对筛选、列重排、升降序及纳秒时间，并补验零行、预览确认、顶部 INSERT/SQL 文件特殊值阻止与浅色紧凑窗口。独立弹窗控制仍不稳定；右键 INSERT 因可能覆盖剪贴板被工具拒绝，未绕过。全部已加载切换、键盘等剩余项不记为通过，具体边界见验收记录。

使用 synthetic fixture 和临时目录验证上面的桌面清单；若使用真实桌面控制工具，先读取并遵守 `computer-use` 技能。不能为验收打开公司连接。无可用展示环境时明确记“未验证”，不要伪造截图或通过结论。

最终核对：本轮是否仍有直接写目标或失败删除目标、背景线程读取 TableView/当前 SQL、旧完成覆盖新状态、SQL preview 被转换成 NULL、非原子 fallback。只检查 SQL 编辑器新路径，不借机修改排除的整表导出。

```powershell
git diff --check
git diff --stat
git status --short --branch
git add test/com/datacube/export/SafeResultFilePublisherTest.java test/com/datacube/export/ResultExportSessionTest.java test/com/datacube/export/QueryResultFileWriterTest.java test/com/datacube/fx/SqlResultExportCoordinatorTest.java test/com/datacube/sqleditor/result/ResultExportSnapshotTest.java README.md docs/superpowers/verification/2026-08-30-safe-result-export.md
git commit -m "test(export): verify failure safety and document result export boundaries"
```

执行过程中若为通过回归修改了生产代码，应把审查后的明确文件追加到 staging，不使用 `git add .`。实施结束使用 `verification-before-completion` 和 `requesting-code-review`，汇报真实通过/未验证项及本地提交；无新增授权不推送/合并/tag。

## 规格覆盖自审

| 已批准设计 | 对应任务与断言 |
| --- | --- |
| 3.1 两范围、当前排序、列隐藏/重排、重复与短行 | 1 的矩阵/零行；5 的默认范围；6 的实际 TableView 顺序 |
| 3.1 活动结果而非缓存原结果、取消与捕获 SQL | 8 活动/原始对照；7 取消零工作和剪贴板精确 SQL |
| 3.2 行截断、特殊值、无损 SQL 限制 | 2 类型 allowlist；5 独立提示/确认；8 截断来源固定 |
| 3.2 常规格式与精度 | 4 CSV/INSERT/XLSX；8 HTML/XML/纳秒时间；既有格式回归 |
| 3.3 防抖与展示、后台不读 UI | 6 flush 计数及列/排序保持；1 快照；7 阻塞后台/新查询状态 |
| 4.1 文件所有权、原子发布与目标版本 | 3 旧字节/外部目标变更/unsupported；8 close、cleanup、同路径、目录/链接 |
| 4.2 关闭、取消和完成、任务拒绝 | 3 单任务/session；8 并发门禁、关闭抑制和 rejected 重试 |
| 5 边界 | 纯模型、发布器、格式适配、dialog、coordinator 分文件；Pane 只接线 |
| 6 验收 | 8 fresh full non-headless、XML 统计、桌面清单及未验证记录 |

自审还需确认代码块之间接口一致、旧方法全部移除、每个新增生产文件都有测试、没有把“计划里的代码”当成“已运行实现”。实施者遇到基线漂移应先列出差异再调整，不盲贴失效的上下文。
