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
