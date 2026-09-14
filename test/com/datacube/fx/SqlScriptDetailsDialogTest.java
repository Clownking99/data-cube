package com.datacube.fx;

import com.datacube.sqleditor.SqlScriptExecutionReport;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.model.ResultColumn;
import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptDetailsDialogTest {
    @Test void truncatedFieldsAreExplicitlyLabelledAndOnlyCloseIsOffered() throws Exception {
        FxUiTestSupport.call(() -> {
            String large = "x".repeat(SqlScriptExecutionReport.MAX_FIELD_UNITS + 1);
            var entry = SqlScriptExecutionReport.capture(List.of(new ScriptOutcome(17, large, QueryResult.error(large, 9))), 9).entries().getFirst();
            var pane = new SqlScriptDetailsDialog(null, entry).getDialogPane();
            assertTrue(((Label) pane.lookup("#sql-script-detail-sql-label")).getText().contains("仅保留前 16384"));
            assertTrue(((Label) pane.lookup("#sql-script-detail-error-label")).getText().contains("达到显示上限"));
            assertEquals(16384, ((TextArea) pane.lookup("#sql-script-detail-sql")).getText().length());
            assertEquals(16384, ((TextArea) pane.lookup("#sql-script-detail-error")).getText().length());
            assertEquals(List.of("关闭"), pane.getButtonTypes().stream().map(javafx.scene.control.ButtonType::getText).toList());
            return null;
        });
    }

    @Test void queryDetailExplainsLoadedNotTotalRowsAndDoesNotExposeCellData() throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.queryWithMetadata(List.of(ResultColumn.unknown(0, "n")), List.of(List.of("private cell")), 12, true);
            var entry = SqlScriptExecutionReport.capture(List.of(new ScriptOutcome(4, "select n", result)), 12).entries().getFirst();
            var pane = new SqlScriptDetailsDialog(null, entry).getDialogPane();
            assertTrue(((Label) pane.lookup("#sql-script-detail-identity")).getText().contains("已加载 1 行（结果已截断）"));
            assertTrue(((Label) pane.lookup("#sql-script-detail-boundary")).getText().contains("不保留查询数据"));
            assertNull(pane.lookup("#sql-script-detail-error"));
            assertEquals("select n", ((TextArea) pane.lookup("#sql-script-detail-sql")).getText());
            return null;
        });
    }
}
