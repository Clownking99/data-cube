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
