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

class SqlResultXmlExportFailureTest {
    @TempDir Path directory;

    @Test void unrepresentableXmlReportsFixedReasonWithoutSuccessAndCanRetry() throws Exception {
        Path target = Files.writeString(directory.resolve("result.xml"), "OLD");
        var events = new ArrayList<String>();
        var errors = new ArrayList<Boolean>();
        var settled = new LinkedBlockingQueue<String>();
        var active = new java.util.concurrent.atomic.AtomicReference<>(snapshot("SYNTHETIC_SECRET\u0000END"));
        var ui = new SqlResultExportCoordinator.Ui() {
            public Optional<ResultExportOptionsDialog.Selection> chooseScope(ResultExportSnapshot snapshot,
                    boolean sql) { return Optional.of(new ResultExportOptionsDialog.Selection(
                            ResultExportScope.CURRENT_FILTERED, false)); }
            public Path chooseFile(QueryResultFileWriter.Format format) { return target; }
            public String chooseTable(String sql) { fail("XML needs no table prompt"); return null; }
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
                Future<?> first = FxUiTestSupport.call(() -> coordinator.export(QueryResultFileWriter.Format.XML));
                String message = settled.poll(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(() -> {
                    assertEquals("XML 无法表示部分字符，原目标文件未修改", message);
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
                Future<?> retry = FxUiTestSupport.call(() -> coordinator.export(QueryResultFileWriter.Format.XML));
                retry.get(5, TimeUnit.SECONDS);
                assertEquals("已导出: " + target, settled.poll(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { assertEquals(false, errors.getLast()); return null; });
                assertTrue(Files.readString(target).contains("RETRY"));
            } finally { coordinator.close(); tasks.close(); }
        }
    }

    private ResultExportSnapshot snapshot(String value) {
        return ResultExportSnapshot.capture(QueryResult.query(List.of("C"), List.of(List.of(value)), 1),
                "select synthetic_value", List.of(0), List.of(new ResultExportSnapshot.Column(0, "C")));
    }
}
