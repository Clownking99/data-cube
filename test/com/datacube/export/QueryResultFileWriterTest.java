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
