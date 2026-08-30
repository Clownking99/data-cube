# Query XLSX Readability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 改善查询结果 XLSX 的列宽、表头和滚动阅读体验，不改变导出内容或整表导出行为。

**Architecture:** 在后台查询导出路径中对已投影的原始快照做有限取样，生成不可变列宽。共享 XLSX writer 通过显式新入口加入样式，旧入口仍生成原有简单工作表。取样与写入均处于已有安全文件发布事务内。

**Tech Stack:** Java 25、JUnit Jupiter 5.11.3、Gradle wrapper、JDK ZIP/XML；沿用 JavaFX 后台任务及现有导出协调器，无新增依赖。

## Global Constraints

- 仅增强 `QueryResultFileWriter` 的 XLSX 分支。
- `XlsxWriter.write(File, List<String>, RowFeed)` 保持原有无样式输出语义；`TableExporter` 的调用及分页流式读取保持不变。
- CSV、SQL、HTML、XML、复制 INSERT、导出对话框和默认文件名均不变。
- 不增加数据库查询、第三方依赖、用户设置、筛选按钮、合并单元格或日期序列值转换。
- 每列考虑表头和最多前 100 行；空值贡献宽度 0。
- 最终宽度为 `min(60, max(12, 最大估计内容宽度 + 2))`。
- 字符串最多扫描前 256 个 Unicode 码点；ASCII 可见字符计 1，非 ASCII 可见字符计 2。按码点遍历，不把代理对拆成两个字符。
- 表头：加粗、浅蓝灰底色 `E8EEF7`、深色文字、允许换行。
- 首行冻结：垂直冻结 1 行，滚动区域从 `A2` 开始；不冻结列。
- 不强制固定行高，避免主动裁切多行内容；实际自动行高表现需要查看器验收，不能仅凭样式声明承诺长文本始终全部可见。
- 时间继续作为原有文本写入，保留纳秒，不转成可能损失精度的 Excel 日期序列。
- 不改变特殊值警告或确认要求；已确认导出的是现有显示表示，不把预览值描述成完整大字段内容。
- 工作表名保持 `Sheet1`。仅改变样式元数据和单元格样式引用，不重排、不删减、不重新取数。

---

## 执行约定与上下文

批准设计：`docs/superpowers/specs/2026-08-30-query-xlsx-readability-design.md`。计划基于 `24ba0f7`，实施基线为 `c73224c`。三项实现及任务审查完成；下面保留原计划步骤，真实结果见 `../verification/2026-08-30-query-xlsx-readability.md`。Step 6 按查看器受限分支记录，不能据此声称真实 Excel 交互通过。

所有相对路径均以 `D:/Projects/朝花夕拾` 为根。执行时先使用 using-git-worktrees 技能检查当前 checkout；不得丢失现有 `codex/safe-result-export` 上的工作。`.testagent/` 属于用户，不读取、不修改、不暂存。只按文件名暂存本任务文件，不运行 `git add .`。本任务不含推送、合并或 tag。

代码定位先使用已有 CodeGraph。不要改 `SafeResultFilePublisher`、`ResultExportOperation`、快照捕获或 UI：它们已经提供取消、发布及范围投影边界。本计划的失败注入只使用测试集合和现有 publisher 回调，不增加产品测试后门。

执行前读取 test-driven-development 技能。新 API 的测试第一次可能因缺少符号无法编译；记录这个结果后，用最小可编译入口使断言明确失败，再完成实现，不把编译失败冒充行为回归证据。每个任务结束运行指定测试、核对差异并提交；任务 3 后请求代码审查。

### 文件职责

| 文件 | 职责 |
| --- | --- |
| 新建 `src/com/datacube/export/XlsxLayout.java` | 不可变、已校验的每列宽度；不包含数据或 UI |
| 新建 `src/com/datacube/export/QueryXlsxLayoutEstimator.java` | 原始行视图的有限取样及取消检查 |
| 修改 `src/com/datacube/export/XlsxWriter.java` | 可选样式入口及 OOXML 输出，保留旧入口 |
| 修改 `src/com/datacube/export/QueryResultFileWriter.java` | XLSX 分支接入布局；其他格式不改 |
| 新建 `test/com/datacube/export/QueryXlsxLayoutEstimatorTest.java` | 宽度、成本、不可变性及取消边界 |
| 新建 `test/com/datacube/export/XlsxTestDocuments.java` | 测试专用安全 XML 解析与 XPath，不进入产品代码 |
| 新建 `test/com/datacube/export/XlsxWriterLayoutTest.java` | 样式关系、内容类型、旧入口与单次消费契约 |
| 新建 `test/com/datacube/export/QueryXlsxExportTest.java` | 查询接入、范围、特殊值及文件保护回归 |
| 新建 `docs/superpowers/verification/2026-08-30-query-xlsx-readability.md` | 执行时记录真实测试及桌面验收证据 |

任务顺序为 1 → 2 → 3；不同时改 writer 与其接入。每个任务均包含完整红绿循环。

## Task 1: 有限取样布局

**Files:** 创建 `src/com/datacube/export/XlsxLayout.java`、`src/com/datacube/export/QueryXlsxLayoutEstimator.java`、`test/com/datacube/export/QueryXlsxLayoutEstimatorTest.java`。

**Interfaces:**

- Consumes: `List<String> columns`、`List<List<Object>> rows` 和 `Runnable check`；行已按导出范围及列投影完成，尚未调用显示格式化。
- Produces: `public record XlsxLayout(List<Integer> widths)`。
- Produces: `public static XlsxLayout QueryXlsxLayoutEstimator.estimate(List<String> columns, List<List<Object>> rows, Runnable check)`。
- `widths` 不可变，每项为 12–60 的整数；空列布局可构造，查询层仍拒绝无列导出。

- [x] **Step 1: 添加以下失败测试。**

```java
package com.datacube.export;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryXlsxLayoutEstimatorTest {
    private int width(String header, Object value) {
        return QueryXlsxLayoutEstimator.estimate(List.of(header),
                List.of(Collections.singletonList(value)), () -> {}).widths().getFirst();
    }

    @Test void measuresHeaderScalarsUnicodeAndLimits() {
        assertEquals(22, width("abcdefghijklmnopqrst", null));
        assertEquals(12, width("n", null));
        assertEquals(14, width("n", "中文列宽测试"));
        assertEquals(31, width("n", LocalDateTime.of(2026, 8, 30, 10, 20, 30, 123456789)));
        assertEquals(22, width("n", Long.MIN_VALUE));
        assertEquals(12, width("n", true));
        assertEquals(34, width("n", Double.NaN));
        assertEquals(34, width("n", Float.POSITIVE_INFINITY));
        assertEquals(12, width("n", '甲'));
        assertEquals(38, width("n", UUID.fromString("00000000-0000-0000-0000-000000000000")));
        assertEquals(60, width("n", "x".repeat(5000)));
        assertEquals(60, width("中".repeat(50), null));
        assertEquals(14, width("n", "abcd\r\nabcdefghijkl"));
        assertEquals(14, width("n", "abcd\t中文"));
        assertEquals(12, width("n", "\u0001".repeat(200)));
        assertEquals(12, width("n", "\n".repeat(256) + "中".repeat(100)));
        assertEquals(14, width("n", "\n".repeat(250) + "😀".repeat(7)));
    }

    @Test void limitsRowsWithoutReadingThe101st() {
        AtomicInteger reads = new AtomicInteger();
        List<List<Object>> rows = new AbstractList<>() {
            public int size() { return 101; }
            public List<Object> get(int index) {
                assertTrue(index < 100, "layout read beyond its sample");
                reads.incrementAndGet();
                return List.of("short");
            }
        };
        assertEquals(List.of(12), QueryXlsxLayoutEstimator.estimate(
                List.of("n"), rows, () -> {}).widths());
        assertEquals(100, reads.get());
    }

    @Test void expensiveValuesAreNotFormattedForLayout() {
        Object poison = new Object() {
            public String toString() { throw new AssertionError("unexpected formatting"); }
        };
        BigDecimal decimal = new BigDecimal("123.45") {
            public String toString() { throw new AssertionError("decimal formatted"); }
        };
        BigInteger integer = new BigInteger("123") {
            public String toString() { throw new AssertionError("integer formatted"); }
        };
        for (Object value : List.of(poison, decimal, integer, URI.create("https://example.invalid/"))) {
            assertEquals(34, width("n", value));
        }
    }

    @Test void supportsHeadersOnlyAndRaggedRowsWithoutMutatingInput() {
        assertEquals(List.of(22), QueryXlsxLayoutEstimator.estimate(
                List.of("abcdefghijklmnopqrst"), List.of(), () -> {}).widths());
        assertEquals(List.of(12, 12), QueryXlsxLayoutEstimator.estimate(
                List.of("a", "b"), List.of(List.of(1)), () -> {}).widths());
        var mutable = new ArrayList<>(List.of(12));
        var layout = new XlsxLayout(mutable);
        mutable.set(0, 60);
        assertEquals(List.of(12), layout.widths());
        assertThrows(UnsupportedOperationException.class, () -> layout.widths().add(20));
        assertThrows(IllegalArgumentException.class, () -> new XlsxLayout(List.of(11)));
        assertThrows(IllegalArgumentException.class, () -> new XlsxLayout(List.of(61)));
        assertThrows(NullPointerException.class, () -> new XlsxLayout(Arrays.asList((Integer) null)));
    }

    @Test void cancellationBetweenColumnsPreventsFurtherValueAccess() {
        var operation = new ResultExportOperation();
        AtomicInteger reads = new AtomicInteger();
        List<Object> row = new AbstractList<>() {
            public int size() { return 2; }
            public Object get(int column) {
                assertEquals(0, column);
                reads.incrementAndGet();
                operation.cancel();
                return "first";
            }
        };
        assertThrows(CancellationException.class, () -> QueryXlsxLayoutEstimator.estimate(
                List.of("a", "b"), List.of(row), operation::check));
        assertEquals(1, reads.get());
        assertThrows(CancellationException.class, () -> QueryXlsxLayoutEstimator.estimate(
                List.of(), List.of(), operation::check));
    }
}
```

- [x] **Step 2: 运行红灯；若缺少 API，添加仅返回固定宽度的可编译入口，再次运行，确认中文、表头或取样断言失败。**

```powershell
./gradlew test --tests com.datacube.export.QueryXlsxLayoutEstimatorTest --no-daemon --console=plain
```

先使用 Step 3 的 `XlsxLayout`，估计器文件暂时使用下面完整代码，使测试进入断言阶段。这只是本任务红灯检查中的短暂状态，不提交。

```java
package com.datacube.export;

import java.util.Collections;
import java.util.List;

public final class QueryXlsxLayoutEstimator {
    private QueryXlsxLayoutEstimator() {}
    public static XlsxLayout estimate(List<String> columns, List<List<Object>> rows,
                                      Runnable check) {
        return new XlsxLayout(Collections.nCopies(columns.size(), 12));
    }
}
```

- [x] **Step 3: 实现不可变布局和有限估计。**

`src/com/datacube/export/XlsxLayout.java`：

```java
package com.datacube.export;

import java.util.List;

public record XlsxLayout(List<Integer> widths) {
    public XlsxLayout {
        widths = List.copyOf(widths);
        if (widths.stream().anyMatch(width -> width < 12 || width > 60)) {
            throw new IllegalArgumentException("XLSX widths must be between 12 and 60");
        }
    }
}
```

`src/com/datacube/export/QueryXlsxLayoutEstimator.java`：

```java
package com.datacube.export;

import java.time.*;
import java.util.*;

public final class QueryXlsxLayoutEstimator {
    private static final Set<Class<?>> SHORT_SCALARS = Set.of(
            Character.class, Boolean.class, Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, UUID.class, LocalDate.class,
            LocalTime.class, LocalDateTime.class, OffsetTime.class,
            OffsetDateTime.class, Instant.class);

    private QueryXlsxLayoutEstimator() {}

    public static XlsxLayout estimate(List<String> columns, List<List<Object>> rows,
                                      Runnable check) {
        Objects.requireNonNull(columns);
        Objects.requireNonNull(rows);
        Objects.requireNonNull(check).run();
        int[] widest = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            check.run();
            widest[c] = measure(columns.get(c));
        }
        int count = Math.min(100, rows.size());
        for (int r = 0; r < count; r++) {
            check.run();
            List<Object> row = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                check.run();
                Object value = c < row.size() ? row.get(c) : null;
                widest[c] = Math.max(widest[c], measure(value));
            }
        }
        var widths = new ArrayList<Integer>(columns.size());
        for (int value : widest) widths.add(Math.min(60, Math.max(12, value + 2)));
        check.run();
        return new XlsxLayout(widths);
    }

    private static int measure(Object value) {
        if (value == null) return 0;
        if (value instanceof Double d && !Double.isFinite(d)) return 32;
        if (value instanceof Float f && !Float.isFinite(f)) return 32;
        String text;
        if (value instanceof String string) text = string;
        else if (SHORT_SCALARS.contains(value.getClass())) text = value.toString();
        else return 32;
        int line = 0, widest = 0, scanned = 0;
        for (int offset = 0; offset < text.length() && scanned < 256; scanned++) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\r' || cp == '\n') {
                widest = Math.max(widest, line);
                line = 0;
            } else if (cp == '\t') {
                line += 4;
            } else if (cp >= 0x20 && cp != 0xFFFE && cp != 0xFFFF) {
                line += cp < 0x80 ? 1 : 2;
            }
            if (line >= 58) return 58;
        }
        return Math.max(widest, line);
    }
}
```

CRLF 连续重置行宽与一次换行得到相同最大值；扫描预算仍按实际码点计算，不绕过 256 上限。未知值返回常数，不调用 formatter 或 JDBC。

- [x] **Step 4: 重跑 Step 2 命令，预期全部通过；检查测试确实执行且不是 UP-TO-DATE 旧报告。**
- [x] **Step 5: 检查差异并提交本任务。**

```powershell
git diff --check
git add -- src/com/datacube/export/XlsxLayout.java src/com/datacube/export/QueryXlsxLayoutEstimator.java test/com/datacube/export/QueryXlsxLayoutEstimatorTest.java
git commit -m "feat(export): estimate bounded query XLSX column widths"
```

## Task 2: 显式 XLSX 样式入口与旧接口保护

**Files:** 修改 `src/com/datacube/export/XlsxWriter.java`；创建 `test/com/datacube/export/XlsxTestDocuments.java` 和 `test/com/datacube/export/XlsxWriterLayoutTest.java`。

**Interfaces:**

- Consumes: Task 1 的 `XlsxLayout.widths()`，以及既有 `RowFeed.forEach(RowSink)`。
- Produces: `public static void XlsxWriter.write(File out, List<String> columns, RowFeed feed, XlsxLayout layout) throws Exception`。
- 原三参数 `write` 不要求布局；新入口拒绝 null 布局和列数不匹配，校验发生在打开输出文件前。
- Test support: `static Document XlsxTestDocuments.read(Path path, String entry)`、`static String value(Document document, String expression)`、`static int count(Document document, String expression)`。

- [x] **Step 1: 添加 XML 测试助手与样式/旧接口断言。**

`test/com/datacube/export/XlsxTestDocuments.java`：

```java
package com.datacube.export;

import java.nio.file.Path;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import org.w3c.dom.Document;

final class XlsxTestDocuments {
    private XlsxTestDocuments() {}
    static Document read(Path path, String entry) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var zip = new ZipFile(path.toFile())) {
            var part = zip.getEntry(entry);
            if (part == null) throw new AssertionError("Missing XLSX part: " + entry);
            try (var input = zip.getInputStream(part)) {
                return factory.newDocumentBuilder().parse(input);
            }
        }
    }
    static String value(Document document, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, document);
    }
    static int count(Document document, String expression) throws Exception {
        return ((Double) XPathFactory.newInstance().newXPath().evaluate(
                "count(" + expression + ")", document, XPathConstants.NUMBER)).intValue();
    }
}
```

`test/com/datacube/export/XlsxWriterLayoutTest.java`：

```java
package com.datacube.export;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.export.XlsxTestDocuments.*;

class XlsxWriterLayoutTest {
    @TempDir Path directory;
    private static final String SHEET = "xl/worksheets/sheet1.xml";
    private static final String CELL = "//*[local-name()='c']";

    @Test void styledPackageHasWidthsFreezeAndValidStyleReferences() throws Exception {
        Path path = directory.resolve("styled.xlsx");
        AtomicInteger feeds = new AtomicInteger();
        XlsxWriter.write(path.toFile(), List.of("姓名", "n"), sink -> {
            feeds.incrementAndGet();
            sink.row(List.of("甲", 7));
        }, new XlsxLayout(List.of(14, 12)));
        assertEquals(1, feeds.get());
        var sheet = read(path, SHEET);
        assertEquals("14", value(sheet, "//*[local-name()='col'][1]/@width"));
        assertEquals("12", value(sheet, "//*[local-name()='col'][2]/@width"));
        assertEquals("1", value(sheet, "//*[local-name()='col'][1]/@customWidth"));
        assertEquals("1", value(sheet, "//*[local-name()='col'][1]/@min"));
        assertEquals("2", value(sheet, "//*[local-name()='col'][2]/@max"));
        assertEquals("frozen", value(sheet, "//*[local-name()='pane']/@state"));
        assertEquals("1", value(sheet, "//*[local-name()='pane']/@ySplit"));
        assertEquals("A2", value(sheet, "//*[local-name()='pane']/@topLeftCell"));
        assertEquals("", value(sheet, "//*[local-name()='pane']/@xSplit"));
        assertEquals(1, count(read(path, "xl/workbook.xml"),
                "//*[local-name()='bookViews']/*[local-name()='workbookView']"));
        assertEquals("1", value(sheet, CELL + "[@r='A1']/@s"));
        assertEquals("2", value(sheet, CELL + "[@r='A2']/@s"));
        assertEquals("", value(sheet, CELL + "[@r='B2']/@s"));
        assertEquals(0, count(sheet, "//*[local-name()='row']/@ht"));
        assertEquals("cols", value(sheet,
                "local-name(//*[local-name()='sheetData']/preceding-sibling::*[1])"));
        var styles = read(path, "xl/styles.xml");
        assertEquals(3, count(styles, "//*[local-name()='cellXfs']/*"));
        assertEquals("1", value(styles,
                "//*[local-name()='cellXfs']/*[3]/*[local-name()='alignment']/@wrapText"));
        assertEquals("top", value(styles,
                "//*[local-name()='cellXfs']/*[3]/*[local-name()='alignment']/@vertical"));
        assertEquals("", value(styles,
                "//*[local-name()='cellXfs']/*[3]/*[local-name()='alignment']/@horizontal"));
        assertEquals("FFE8EEF7", value(styles, "//*[local-name()='fgColor']/@rgb"));
        assertEquals(1, count(styles, "//*[local-name()='fonts']/*[2]/*[local-name()='b']"));
        var types = read(path, "[Content_Types].xml");
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml",
                value(types, "//*[local-name()='Override'][@PartName='/xl/styles.xml']/@ContentType"));
        var rels = read(path, "xl/_rels/workbook.xml.rels");
        assertEquals("styles.xml", value(rels,
                "//*[local-name()='Relationship'][@Id='rId2']/@Target"));
        assertEquals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles",
                value(rels, "//*[local-name()='Relationship'][@Id='rId2']/@Type"));
    }

    @Test void layoutDoesNotChangeCellValuesTypesOrOrder() throws Exception {
        var columns = List.of("n", "flag", "text", "time", "empty", "formula", "long");
        var time = LocalDateTime.of(2026, 8, 30, 10, 20, 30, 123456789);
        List<Object> row = Arrays.asList(7, true, "甲<&\"\n乙", time, null, "=1+1", "中".repeat(500));
        Path plain = directory.resolve("plain.xlsx"), styled = directory.resolve("styled.xlsx");
        AtomicInteger feeds = new AtomicInteger();
        RowFeed feed = sink -> { feeds.incrementAndGet(); sink.row(row); };
        XlsxWriter.write(plain.toFile(), columns, feed);
        XlsxWriter.write(styled.toFile(), columns, feed, new XlsxLayout(Collections.nCopies(7, 32)));
        assertEquals(2, feeds.get());
        Document before = read(plain, SHEET), after = read(styled, SHEET);
        assertEquals(count(before, CELL), count(after, CELL));
        for (int i = 1; i <= count(before, CELL); i++) {
            String node = "(" + CELL + ")[" + i + "]";
            for (String suffix : List.of("/@r", "/@t", "/text()", "/*[local-name()='v']/text()",
                    "/*[local-name()='is']/*[local-name()='t']/text()")) {
                assertEquals(value(before, node + suffix), value(after, node + suffix));
            }
        }
        assertEquals("7", value(after, CELL + "[@r='A2']/*[local-name()='v']"));
        assertEquals("b", value(after, CELL + "[@r='B2']/@t"));
        assertEquals("1", value(after, CELL + "[@r='B2']/*[local-name()='v']"));
        assertEquals(0, count(after, CELL + "[@r='E2']"));
        assertEquals("inlineStr", value(after, CELL + "[@r='F2']/@t"));
        assertEquals(0, count(after, "//*[local-name()='f']"));
        assertEquals(time.toString(), value(after, CELL + "[@r='D2']"));
        assertEquals("中".repeat(500), value(after, CELL + "[@r='G2']"));
    }

    @Test void legacyEntryKeepsItsOriginalPartsAndPlainSheet() throws Exception {
        Path path = directory.resolve("legacy.xlsx");
        XlsxWriter.write(path.toFile(), List.of("name"), sink -> sink.row(List.of("Ada")));
        try (var zip = new ZipFile(path.toFile())) {
            assertEquals(Set.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                    "xl/_rels/workbook.xml.rels", SHEET),
                    new HashSet<>(zip.stream().map(entry -> entry.getName()).toList()));
            String sheet = new String(zip.getInputStream(zip.getEntry(SHEET)).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                    + "name</t></is></c></row><row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is>"
                    + "<t xml:space=\"preserve\">Ada</t></is></c></row></sheetData></worksheet>", sheet);
        }
        assertEquals(1, count(read(path, "xl/_rels/workbook.xml.rels"), "//*[local-name()='Relationship']"));
        assertEquals(0, count(read(path, "[Content_Types].xml"),
                "//*[local-name()='Override'][@PartName='/xl/styles.xml']"));
        assertEquals(0, count(read(path, "xl/workbook.xml"), "//*[local-name()='bookViews']"));
    }

    @Test void invalidLayoutDoesNotOpenOutputOrConsumeRows() throws Exception {
        Path target = directory.resolve("existing.xlsx");
        Files.writeString(target, "keep");
        RowFeed feed = sink -> fail("invalid layout consumed feed");
        assertThrows(IllegalArgumentException.class, () -> XlsxWriter.write(
                target.toFile(), List.of("a", "b"), feed, new XlsxLayout(List.of(12))));
        assertThrows(NullPointerException.class, () -> XlsxWriter.write(
                target.toFile(), List.of("a"), feed, null));
        assertEquals("keep", Files.readString(target));
    }
}
```

- [x] **Step 2: 运行红灯。**

```powershell
./gradlew test --tests com.datacube.export.XlsxWriterLayoutTest --no-daemon --console=plain
```

为观察行为红灯，先在现有 `XlsxWriter` 中添加以下临时方法。预期样式测试失败、旧接口测试通过。记录后用 Step 3 实现替换临时入口。

```java
public static void write(File out, List<String> columns, RowFeed feed,
                         XlsxLayout layout) throws Exception {
    write(out, columns, feed);
}
```

- [x] **Step 3: 实现可选样式，保持旧生成逻辑。**

在 `XlsxWriter.java` 增加 `import java.util.Objects;`。用以下方法替换原 `write`、`writeSheet`、`writeCell`、`writeInlineString`；保留原 `cellRef`、`xml`、`putEntry`、`contentTypes`、`rootRels`、`workbook` 和 `workbookRels` 不动。

```java
public static void write(File out, List<String> columns, RowFeed feed) throws Exception {
    writePackage(out, columns, feed, null);
}

public static void write(File out, List<String> columns, RowFeed feed,
                         XlsxLayout layout) throws Exception {
    Objects.requireNonNull(layout);
    if (layout.widths().size() != columns.size()) {
        throw new IllegalArgumentException("XLSX layout column count mismatch");
    }
    writePackage(out, columns, feed, layout);
}

private static void writePackage(File out, List<String> columns, RowFeed feed,
                                 XlsxLayout layout) throws Exception {
    boolean styled = layout != null;
    try (ZipOutputStream zip = new ZipOutputStream(
            new BufferedOutputStream(new FileOutputStream(out)))) {
        String types = contentTypes();
        String relationships = workbookRels();
        String workbookXml = workbook();
        if (styled) {
            workbookXml = workbookXml.replace("<sheets>",
                    "<bookViews><workbookView/></bookViews><sheets>");
            types = types.replace("</Types>",
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
            relationships = relationships.replace("</Relationships>",
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        }
        putEntry(zip, "[Content_Types].xml", types);
        putEntry(zip, "_rels/.rels", rootRels());
        putEntry(zip, "xl/workbook.xml", workbookXml);
        putEntry(zip, "xl/_rels/workbook.xml.rels", relationships);
        if (styled) putEntry(zip, "xl/styles.xml", styles());
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
        Writer writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        writeSheet(writer, columns, feed, layout);
        writer.flush();
        zip.closeEntry();
    }
}

private static void writeSheet(Writer writer, List<String> columns, RowFeed feed,
                               XlsxLayout layout) throws Exception {
    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    writer.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
    if (layout != null) {
        writer.write("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                + "<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews>");
        if (!layout.widths().isEmpty()) {
            writer.write("<cols>");
            for (int c = 0; c < layout.widths().size(); c++) {
                int column = c + 1;
                writer.write("<col min=\"" + column + "\" max=\"" + column
                        + "\" width=\"" + layout.widths().get(c) + "\" customWidth=\"1\"/>");
            }
            writer.write("</cols>");
        }
    }
    writer.write("<sheetData><row r=\"1\">");
    for (int c = 0; c < columns.size(); c++) {
        writeInlineString(writer, cellRef(c, 1), columns.get(c), layout == null ? "" : " s=\"1\"");
    }
    writer.write("</row>");
    int[] rowCounter = {1};
    feed.forEach(values -> {
        int row = ++rowCounter[0];
        try {
            writer.write("<row r=\"" + row + "\">");
            for (int c = 0; c < values.size(); c++) {
                writeCell(writer, cellRef(c, row), values.get(c), layout != null);
            }
            writer.write("</row>");
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    });
    writer.write("</sheetData></worksheet>");
}

private static void writeCell(Writer writer, String ref, Object value,
                              boolean styled) throws IOException {
    if (value == null) return;
    if (value instanceof Number) {
        writer.write("<c r=\"" + ref + "\"><v>" + value + "</v></c>");
    } else if (value instanceof Boolean flag) {
        writer.write("<c r=\"" + ref + "\" t=\"b\"><v>" + (flag ? 1 : 0) + "</v></c>");
    } else {
        writeInlineString(writer, ref, value.toString(), styled ? " s=\"2\"" : "");
    }
}

private static void writeInlineString(Writer writer, String ref, String text,
                                      String style) throws IOException {
    writer.write("<c r=\"" + ref + "\"" + style + " t=\"inlineStr\"><is><t xml:space=\"preserve\">");
    writer.write(xml(text == null ? "" : text));
    writer.write("</t></is></c>");
}

private static String styles() {
    return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <fonts count="2">
                <font><sz val="11"/><name val="Calibri"/></font>
                <font><b/><sz val="11"/><color rgb="FF1F2937"/><name val="Calibri"/></font>
              </fonts>
              <fills count="3">
                <fill><patternFill patternType="none"/></fill>
                <fill><patternFill patternType="gray125"/></fill>
                <fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF7"/><bgColor indexed="64"/></patternFill></fill>
              </fills>
              <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
              <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
              <cellXfs count="3">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
              </cellXfs>
              <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
            """;
}
```

只有样式入口在原工作簿 XML 的 sheets 前增加一个 `workbookView`，让 `sheetView` 的视图 0 引用有明确目标；不改变默认 sheet 名或旧入口输出。实际查看器验收须检查冻结功能，不只检查文件可解压。样式编号 0 为默认、1 为表头、2 为正文文本；数值和布尔仍用默认样式。

- [x] **Step 4: 运行全部 export 单元测试，预期新旧入口及现有文件保护测试均通过。**

```powershell
./gradlew test --tests 'com.datacube.export.*' --no-daemon --console=plain
```

- [x] **Step 5: 确认 `TableExporter.java` 没有改动，提交本任务。**

```powershell
git diff -- src/com/datacube/export/TableExporter.java
git diff --check
git add -- src/com/datacube/export/XlsxWriter.java test/com/datacube/export/XlsxTestDocuments.java test/com/datacube/export/XlsxWriterLayoutTest.java
git commit -m "feat(export): add opt-in XLSX header and worksheet layout"
```

## Task 3: 查询接入、范围与文件保护验收

**Files:** 修改 `src/com/datacube/export/QueryResultFileWriter.java:99` 的 XLSX 分支；创建 `test/com/datacube/export/QueryXlsxExportTest.java` 和新验收记录。不要编辑旧 safe-result-export 验收记录为本轮通过。

**Interfaces:**

- Consumes: Task 1 `estimate(columns, originalRows, operation::check)` 和 Task 2 四参数 `XlsxWriter.write`；测试使用 Task 2 XML 助手。
- Consumes: 现有 `SafeResultFilePublisher.publish(Target, ResultExportOperation, TempWriter)`、`capture(Path)`；无需改签名。
- Produces: 现有 `QueryResultFileWriter.write` 的 XLSX 输出自动启用新布局；其他格式保持原行为。

- [x] **Step 1: 写查询接入和安全边界测试。**

`test/com/datacube/export/QueryXlsxExportTest.java`：

```java
package com.datacube.export;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.export.XlsxTestDocuments.*;

class QueryXlsxExportTest {
    @TempDir Path directory;
    private static final String SHEET = "xl/worksheets/sheet1.xml";
    private static final String CELL = "//*[local-name()='c']";

    private ResultExportSnapshot snapshot(List<List<Object>> rows, List<Integer> visible) {
        return ResultExportSnapshot.capture(
                QueryResult.query(List.of("hidden", "name", "rank"), rows, 1),
                "select hidden, name, rank from synthetic", visible,
                List.of(new ResultExportSnapshot.Column(2, "rank"),
                        new ResultExportSnapshot.Column(1, "name")));
    }
    private void write(Path path, ResultExportSnapshot snapshot, ResultExportScope scope,
                       boolean consent, ResultExportOperation operation) throws Exception {
        QueryResultFileWriter.write(path, QueryResultFileWriter.Format.XLSX,
                snapshot, scope, consent, null, operation);
    }

    @Test void scopesUseTheirOwnSampleAndOrderingWithTheSameProjection() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            rows.add(List.of("hidden-" + i, i == 100 ? "中".repeat(40) : "Ada", i));
        }
        var snapshot = snapshot(rows, List.of(100, 0));
        Path current = directory.resolve("current.xlsx"), all = directory.resolve("all.xlsx");
        write(current, snapshot, ResultExportScope.CURRENT_FILTERED, false, new ResultExportOperation());
        write(all, snapshot, ResultExportScope.ALL_LOADED, false, new ResultExportOperation());
        var currentSheet = read(current, SHEET);
        var allSheet = read(all, SHEET);
        assertEquals("60", value(currentSheet, "//*[local-name()='col'][2]/@width"));
        assertEquals("12", value(allSheet, "//*[local-name()='col'][2]/@width"));
        assertEquals("100", value(currentSheet, CELL + "[@r='A2']"));
        assertEquals("0", value(currentSheet, CELL + "[@r='A3']"));
        assertEquals("0", value(allSheet, CELL + "[@r='A2']"));
        assertEquals("100", value(allSheet, CELL + "[@r='A102']"));
        assertEquals("中".repeat(40), value(allSheet, CELL + "[@r='B102']"));
        assertEquals(3, count(currentSheet, "//*[local-name()='row']"));
        assertEquals(102, count(allSheet, "//*[local-name()='row']"));
        for (var sheet : List.of(currentSheet, allSheet)) {
            assertEquals("rank", value(sheet, CELL + "[@r='A1']"));
            assertEquals("name", value(sheet, CELL + "[@r='B1']"));
            assertEquals(2, count(sheet, "//*[local-name()='col']"));
            assertEquals(0, count(sheet, CELL + "[starts-with(@r,'C')]"));
            assertFalse(sheet.getDocumentElement().getTextContent().contains("hidden-"));
        }
    }

    @Test void specialValuesStillNeedConsentAndNeverBecomeSql() throws Exception {
        var snapshot = snapshot(List.of(List.of("hidden", Double.NaN, 1)), List.of(0));
        Path path = directory.resolve("special.xlsx");
        assertThrows(IllegalArgumentException.class, () -> write(path, snapshot,
                ResultExportScope.CURRENT_FILTERED, false, new ResultExportOperation()));
        assertFalse(Files.exists(path));
        write(path, snapshot, ResultExportScope.CURRENT_FILTERED, true, new ResultExportOperation());
        assertEquals("NaN", value(read(path, SHEET), CELL + "[@r='B2']"));
        assertEquals("inlineStr", value(read(path, SHEET), CELL + "[@r='B2']/@t"));
        assertThrows(IllegalArgumentException.class, () -> QueryResultFileWriter.insert(
                snapshot, ResultExportScope.CURRENT_FILTERED, "synthetic"));
    }

    @Test void samplingFailureAndCancellationPreserveOldFileAndCleanTemporary() throws Exception {
        // Component composition: inject failures through estimator inputs, not a production seam.
        for (boolean cancel : List.of(false, true)) {
            Path path = directory.resolve(cancel ? "cancel.xlsx" : "failure.xlsx");
            Files.writeString(path, "original");
            var target = SafeResultFilePublisher.capture(path);
            var operation = new ResultExportOperation();
            AtomicBoolean writerReached = new AtomicBoolean();
            List<List<Object>> rows = new AbstractList<>() {
                public int size() { return 1; }
                public List<Object> get(int index) {
                    if (!cancel) throw new IllegalArgumentException("synthetic sampling failure");
                    operation.cancel();
                    return List.of("value");
                }
            };
            var error = assertThrows(Exception.class, () -> new SafeResultFilePublisher().publish(
                    target, operation, (temporary, token) -> {
                        var layout = QueryXlsxLayoutEstimator.estimate(List.of("n"), rows, token::check);
                        writerReached.set(true);
                        XlsxWriter.write(temporary.toFile(), List.of("n"),
                                sink -> sink.row(List.of("value")), layout);
                    }));
            if (cancel) assertInstanceOf(CancellationException.class, error);
            else assertEquals(SafeResultFilePublisher.Stage.WRITE,
                    assertInstanceOf(SafeResultFilePublisher.Failure.class, error).stage());
            assertFalse(writerReached.get());
            assertFalse(operation.published());
            assertEquals("original", Files.readString(path));
            try (var entries = Files.list(directory)) {
                assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".datacube-export-")));
            }
        }
    }

    private enum Marker {
        FIRST, CANCEL, LATER;
        static ResultExportOperation operation;
        static final int[] accesses = new int[3];
        public String toString() {
            accesses[ordinal()]++;
            if (this == CANCEL && operation != null) operation.cancel();
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Test void cancellationDuringActualXlsxSerializationStopsLaterRowsAndPublication() throws Exception {
        var snapshot = snapshot(List.of(List.of("h", Marker.FIRST, 1),
                List.of("h", Marker.CANCEL, 2), List.of("h", Marker.LATER, 3)),
                IntStream.range(0, 3).boxed().toList());
        Path path = directory.resolve("serializing.xlsx");
        Files.writeString(path, "original");
        var operation = new ResultExportOperation();
        Arrays.fill(Marker.accesses, 0);
        Marker.operation = operation;
        try {
            assertThrows(CancellationException.class, () -> new SafeResultFilePublisher().publish(
                    SafeResultFilePublisher.capture(path), operation,
                    (temporary, token) -> write(temporary, snapshot,
                            ResultExportScope.ALL_LOADED, true, token)));
            assertArrayEquals(new int[]{1, 1, 0}, Marker.accesses);
            assertEquals("original", Files.readString(path));
            assertFalse(operation.published());
            try (var entries = Files.list(directory)) {
                assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".datacube-export-")));
            }
        } finally {
            Marker.operation = null;
            Arrays.fill(Marker.accesses, 0);
        }
    }
}
```

- [x] **Step 2: 运行接入测试，预期范围测试因缺少列宽失败；已有特殊值和发布保护测试不应出现无关失败。**

```powershell
./gradlew test --tests com.datacube.export.QueryXlsxExportTest --no-daemon --console=plain
```

- [x] **Step 3: 仅替换 XLSX case，使用原始值估计、现有显示视图写入。**

`QueryResultFileWriter.write` 中的 `originalRows` 已存在且已完成 `validate`；保留其余方法和 switch 分支原样：

```java
case XLSX -> {
    XlsxLayout layout = QueryXlsxLayoutEstimator.estimate(columns, originalRows, operation::check);
    operation.check();
    XlsxWriter.write(temporary.toFile(), columns, sink -> {
        for (List<Object> row : rows) {
            operation.check();
            sink.row(row);
        }
    }, layout);
}
```

- [x] **Step 4: 重跑接入和导出定向测试，预期通过。**

```powershell
./gradlew test --tests 'com.datacube.export.*' --tests com.datacube.fx.SqlResultExportCoordinatorTest --no-daemon --console=plain
```

- [x] **Step 5: 运行完整测试并汇总本次 XML，不复用旧计数。**

在 Windows 本地保持 JavaFX 可用；命令只临时追加 headless 属性，结束恢复进程环境变量：

```powershell
$xlsxPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$xlsxPreviousJavaOptions -Djava.awt.headless=false".Trim()
    ./gradlew clean test --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Full test suite failed' }
} finally {
    $env:JAVA_TOOL_OPTIONS = $xlsxPreviousJavaOptions
}
$xlsxReports = Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml'
$xlsxTotals = @{ Suites = 0; Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
foreach ($xlsxReport in $xlsxReports) {
    [xml]$xlsxXml = Get-Content -LiteralPath $xlsxReport.FullName -Raw
    $xlsxTotals.Suites++
    $xlsxTotals.Tests += [int]$xlsxXml.testsuite.tests
    $xlsxTotals.Failures += [int]$xlsxXml.testsuite.failures
    $xlsxTotals.Errors += [int]$xlsxXml.testsuite.errors
    $xlsxTotals.Skipped += [int]$xlsxXml.testsuite.skipped
}
$xlsxTotals.Passed = $xlsxTotals.Tests - $xlsxTotals.Failures - $xlsxTotals.Errors - $xlsxTotals.Skipped
$xlsxTotals
```

预期 Gradle 退出 0、failures/errors 为 0；跳过项目单独列明，不计入通过。若遇到失败，使用 systematic-debugging 技能定位，不扩大修改范围以掩盖问题。

- [x] **Step 6: 生成本轮合成文件并检查真实输出。**（文件/渲染通过；真实 Excel 交互受限，按下述降级记录要求保留未验收项。）

以下使用 JDK 25 `jshell`，不连接数据库、不写用户文件。它创建唯一临时目录，打印路径，并生成新旧入口文件用于比较；没有自动删除行为：

```powershell
@'
import com.datacube.export.*;
import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.nio.file.*;
import java.util.*;
import java.time.*;
var directory = Files.createTempDirectory("datacube-xlsx-readability-");
var columns = List.of("姓名", "分数", "创建时间", "说明");
List<List<Object>> rows = List.of(Arrays.asList("Ada", 12, LocalDateTime.of(2026,8,30,10,20,30,123456789), "短说明"), Arrays.asList("张三 & <演示>", 25, null, "这是一段用于检查长文本换行的合成说明。".repeat(12)), Arrays.asList("Ada", 8, LocalDateTime.of(2026,8,29,9,1,2), "第一行\n第二行"));
var snapshot = ResultExportSnapshot.capture(QueryResult.query(columns, rows, 1), "select synthetic", List.of(0,1,2), List.of(new ResultExportSnapshot.Column(0,columns.get(0)),new ResultExportSnapshot.Column(1,columns.get(1)),new ResultExportSnapshot.Column(2,columns.get(2)),new ResultExportSnapshot.Column(3,columns.get(3))));
QueryResultFileWriter.write(directory.resolve("query-styled.xlsx"), QueryResultFileWriter.Format.XLSX, snapshot, ResultExportScope.CURRENT_FILTERED, false, null, new ResultExportOperation());
XlsxWriter.write(directory.resolve("legacy-plain.xlsx").toFile(), columns, sink -> { for (var row : rows) sink.row(row); });
System.out.println("XLSX_ARTIFACT_DIRECTORY=" + directory);
/exit
'@ | jshell --class-path build/classes/java/main
```

核对控制台无 Java 异常、两个文件均存在。使用 spreadsheets 技能检查新文件内容并生成预览；若操作真实表格应用，先读取对应 computer-use 或 excel-live-control 技能。只能打开本步骤生成的合成文件，不能访问用户数据库、凭证、剪贴板或已有业务文档。核对：中文/时间列可读、浅色加粗表头、长说明换行；在可用查看器中向下滚动确认首行冻结。无法使用交互查看器时，在验收记录明确写“冻结/自动行高仅验证 OOXML，未验证真实 Excel 交互”。静态预览不能替代这项结论。

- [x] **Step 7: 保存真实验收记录、审查并提交。**（独立最终审查无阻断项；两项 Minor 与真实 Excel 未验收明确留存。）

在 `docs/superpowers/verification/2026-08-30-query-xlsx-readability.md` 记录代码提交基线、红绿命令与结果、全套通过/失败/跳过数、合成文件绝对路径与 SHA-256、使用的查看器/渲染器及未验收项。逐项对应批准设计第 7 节，不将任何未做检查写成通过。不要写入真实凭证或业务数据。

按 requesting-code-review 技能审查全部三任务差异，处理有证据的缺陷；有代码更改后重跑受影响测试，必要时重跑全套。完成验证后：

```powershell
git diff --check
git add -- src/com/datacube/export/QueryResultFileWriter.java test/com/datacube/export/QueryXlsxExportTest.java docs/superpowers/verification/2026-08-30-query-xlsx-readability.md
git commit -m "feat(export): enable readable query XLSX exports"
git status --short
```

## 计划自审与交接

覆盖映射：设计 1–3 → 本计划目标及全局约束；4–5 → Task 1、Task 3；6 → Task 2；7.1 → Task 1 及 Task 3 范围测试；7.2 → Task 2、Task 3 发布保护及全套测试；7.3 → Task 3 Step 6–7；8 → 用户批准后子代理实施，本地保留分支，不推送、合并或打 tag。

本计划采用少量明确接口，未引入新的任务生命周期、数据源或布局框架。代码块是实施内容，不代表已经进入源码或测试通过。执行中发现与实际代码不符时先记录并纠正计划，不能跳过取消、兼容或内容断言。
