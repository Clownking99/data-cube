package com.datacube.fx;

import com.datacube.sqleditor.*;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlExecutionContextIntegrationTest {
    @TempDir Path directory;
    @Test void errorNavigationUsesRetainedOccurrenceAndRejectsEditedOrClearedTextWithoutChangingSql() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                String source = "select '😀', broken; select '😀', broken";
                f.editor.replaceText(source); f.editor.getUndoManager().forgetHistory();
                long revision = (long) field(f.pane, "editorRevision");
                var snapshot = SqlExecutionSource.capture(source, revision, new SqlExecutionRange(0, source.length(), false), false);
                var error = QueryResult.error("synthetic", 2).withExecutionDetails(13, 2, -1);
                f.show(List.of(new ScriptOutcome(1, "select '😀', broken", error), new ScriptOutcome(2, "select '😀', broken", error)));
                setField(f.pane, "executionSource", snapshot);
                var choices = (ComboBox<?>) f.root.lookup("#sql-batch-choice"); choices.getSelectionModel().select(2);
                var locate = (Button) f.root.lookup("#sql-locate-error"); locate.fire();
                assertEquals(source.lastIndexOf("broken"), f.editor.getCaretPosition());
                assertEquals(source, f.editor.getText()); assertFalse(f.editor.isUndoAvailable());
                assertTrue(((Label) f.root.lookup("#sql-result-timings")).getText().contains("JDBC 执行 2ms"));
                f.editor.insertText(0, "-- edited\n"); f.editor.moveTo(0); locate.fire();
                assertEquals(0, f.editor.getCaretPosition());
                assertTrue(((Label) field(f.pane, "statusLabel")).getText().contains("过期"));
                f.editor.undo(); f.editor.moveTo(0); locate.fire();
                assertEquals(0, f.editor.getCaretPosition(), "undo does not revive an obsolete execution version");
                invoke(f.pane, "clearResultFilterState", new Class<?>[0]);
                assertNull(field(f.pane, "executionSource")); assertTrue(locate.isDisabled());
                assertNotNull(f.root.lookup("#sql-execute-current"));
                return null;
            });
            f.assertOffline();
        }
    }
}
