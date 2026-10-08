package com.datacube.fx;

import com.datacube.export.*;
import com.datacube.fx.task.*;
import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultXlsxExportFailureTest {
    @TempDir Path directory;

    @Test void unpairedUtf16ReportsSafeWriteFailureWithoutSuccessAndCanRetry() throws Exception {
        Path target = Files.writeString(directory.resolve("case-result.xlsx"), "OLD");
        var events = new ArrayList<String>();
        var errors = new ArrayList<Boolean>();
        var settled = new LinkedBlockingQueue<String>();
        var active = new java.util.concurrent.atomic.AtomicReference<>(snapshot("SYNTHETIC_SECRET\uD800END"));
        var ui = new SqlResultExportCoordinator.Ui() {
            public Optional<ResultExportOptionsDialog.Selection> chooseScope(ResultExportSnapshot snapshot,
                    boolean sql) { return Optional.of(new ResultExportOptionsDialog.Selection(
                            ResultExportScope.CURRENT_FILTERED, false)); }
            public Path chooseFile(QueryResultFileWriter.Format format) { return target; }
            public String chooseTable(String sql) { fail("XLSX needs no table prompt"); return null; }
            public boolean confirmOverwrite(Path path) { return true; }
        };
        try (var runner = new FxTaskRunner()) {
            var tasks = runner.scope();
            var coordinator = new SqlResultExportCoordinator(tasks, active::get, () -> 0L,
                    (text, error) -> { events.add(text); errors.add(error);
                        if (!text.equals("导出中...")) settled.add(text); },
                    text -> { fail("No clipboard access"); return false; }, ui,
                    (request, operation) -> new SafeResultFilePublisher().publish(request.target(), operation,
                            (path, token) -> QueryResultFileWriter.write(path, request.format(),
                                    request.snapshot(), request.selection().scope(),
                                    request.selection().displayConfirmed(), request.table(), token)));
            try {
                Future<?> first = FxUiTestSupport.call(() -> coordinator.export(QueryResultFileWriter.Format.XLSX));
                String message = settled.poll(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(() -> {
                    assertEquals("导出写入失败，原目标文件未修改", message);
                    assertEquals(List.of("导出中...", message), events);
                    assertEquals(List.of(false, true), errors);
                    assertFalse(events.stream().anyMatch(text -> text.contains("SYNTHETIC_SECRET")
                            || text.startsWith("已导出:")));
                    return null;
                });
                first.get(5, TimeUnit.SECONDS);
                assertEquals("OLD", Files.readString(target));
                try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
                active.set(snapshot("RETRY"));
                Future<?> retry = FxUiTestSupport.call(() -> coordinator.export(QueryResultFileWriter.Format.XLSX));
                retry.get(5, TimeUnit.SECONDS);
                assertEquals("已导出: " + target.getParent().toRealPath().resolve(target.getFileName()),
                        settled.poll(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { assertEquals(false, errors.getLast()); return null; });
                var sheet = readSheet(target);
                assertEquals("RETRY", sheet.getElementsByTagNameNS(
                        "http://schemas.openxmlformats.org/spreadsheetml/2006/main", "t").item(1).getTextContent());
            } finally { coordinator.close(); tasks.close(); }
        }
    }

    private org.w3c.dom.Document readSheet(Path path) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var zip = new java.util.zip.ZipFile(path.toFile());
             var input = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private ResultExportSnapshot snapshot(String value) {
        return ResultExportSnapshot.capture(QueryResult.query(List.of("C"), List.of(List.of(value)), 1),
                "select synthetic_value", List.of(0), List.of(new ResultExportSnapshot.Column(0, "C")));
    }
}
