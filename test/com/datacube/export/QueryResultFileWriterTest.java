package com.datacube.export;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QueryResultFileWriterTest {
    @TempDir Path directory;

    private enum SerializationMarker {
        FIRST("first"), CANCEL("cancel"), LATER("later");

        private static final AtomicInteger firstAccesses = new AtomicInteger();
        private static final AtomicInteger cancelAccesses = new AtomicInteger();
        private static final AtomicInteger laterAccesses = new AtomicInteger();
        private static ResultExportOperation operation;
        private final String text;

        SerializationMarker(String text) {
            this.text = text;
        }

        static void cancelDuringSerialization(ResultExportOperation value) {
            operation = value;
        }

        static void reset() {
            operation = null;
            firstAccesses.set(0);
            cancelAccesses.set(0);
            laterAccesses.set(0);
        }

        @Override public String toString() {
            switch (this) {
                case FIRST -> firstAccesses.incrementAndGet();
                case CANCEL -> {
                    cancelAccesses.incrementAndGet();
                    operation.cancel();
                }
                case LATER -> laterAccesses.incrementAndGet();
            }
            return text;
        }
    }

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

    @Test void cancellationDuringCsvSerializationStopsBeforeLaterRows() throws Exception {
        var result = QueryResult.query(List.of("value"), List.of(
                List.of(SerializationMarker.FIRST),
                List.of(SerializationMarker.CANCEL),
                List.of(SerializationMarker.LATER)), 1);
        var snapshot = ResultExportSnapshot.capture(result, "select value from t", List.of(0, 1, 2),
                List.of(new ResultExportSnapshot.Column(0, "value")));
        var operation = new ResultExportOperation();
        Path target = directory.resolve("cancelled.csv");

        SerializationMarker.cancelDuringSerialization(operation);
        try {
            assertThrows(CancellationException.class, () -> QueryResultFileWriter.write(target,
                    QueryResultFileWriter.Format.CSV, snapshot, ResultExportScope.ALL_LOADED,
                    true, null, operation));
            assertEquals(1, SerializationMarker.firstAccesses.get());
            assertEquals(1, SerializationMarker.cancelAccesses.get());
            assertEquals(0, SerializationMarker.laterAccesses.get());
            assertEquals("\uFEFFvalue\r\nfirst\r\ncancel\r\n", Files.readString(target));
        } finally {
            SerializationMarker.reset();
        }
    }

    @Test void csvScopeUsesVisibleOrderOrAllLoadedOrderWithTheSameProjection() throws Exception {
        var result = QueryResult.query(List.of("hidden", "name", "rank"), List.of(
                List.of("load-1", "one", 10),
                List.of("load-2", "two", 20),
                List.of("load-3", "three", 30)), 1);
        var snapshot = ResultExportSnapshot.capture(result, "select hidden, name, rank from t", List.of(2, 0),
                List.of(new ResultExportSnapshot.Column(2, "rank"),
                        new ResultExportSnapshot.Column(1, "name")));
        Path current = directory.resolve("current.csv");
        Path all = directory.resolve("all.csv");

        QueryResultFileWriter.write(current, QueryResultFileWriter.Format.CSV, snapshot,
                ResultExportScope.CURRENT_FILTERED, false, null, new ResultExportOperation());
        QueryResultFileWriter.write(all, QueryResultFileWriter.Format.CSV, snapshot,
                ResultExportScope.ALL_LOADED, false, null, new ResultExportOperation());

        assertEquals("\uFEFFrank,name\r\n30,three\r\n10,one\r\n", Files.readString(current));
        assertEquals("\uFEFFrank,name\r\n10,one\r\n20,two\r\n30,three\r\n", Files.readString(all));
    }

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
}
