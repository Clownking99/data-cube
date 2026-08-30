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
    @Test void nonTruncatedSnapshotDoesNotReserveTruncationSpace() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = ResultExportOptionsDialog.create(null, snapshot(List.of(0), 1), false);
            var content = (javafx.scene.Parent) ((ScrollPane) dialog.getDialogPane().getContent())
                    .getContent();
            Label truncated = (Label) content.lookup("#result-export-truncated");
            assertFalse(truncated.isVisible());
            assertFalse(truncated.isManaged());
            return null;
        });
    }
}
