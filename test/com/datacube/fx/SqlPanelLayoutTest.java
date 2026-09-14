package com.datacube.fx;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import static com.datacube.fx.SqlPanelLayout.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlPanelLayoutTest {
    @Test void repeatedSinglePanelSwitchesRetainNodesAndLastDraggedDivider() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new VBox(new Label("SQL")); var results = new VBox(new Label("Rows"));
            var hides = new AtomicInteger();
            try (var layout = new SqlPanelLayout(editor, results, () -> true, hides::incrementAndGet)) {
                var root = new VBox(layout.menu(), layout.node()); new Scene(root, 880, 850);
                root.applyCss(); root.layout();
                assertEquals(List.of(editor, results), layout.node().getItems());
                layout.node().setDividerPositions(0.63);
                double divider = layout.node().getDividerPositions()[0];
                for (int i = 0; i < 3; i++) {
                    assertTrue(layout.select(EDITOR)); assertEquals(List.of(editor), layout.node().getItems());
                    assertTrue(layout.select(RESULTS)); assertEquals(List.of(results), layout.node().getItems());
                    assertTrue(layout.select(RESULTS), "same mode is a no-op");
                    assertTrue(layout.select(EDITOR));
                    assertTrue(layout.select(SPLIT)); assertEquals(List.of(editor, results), layout.node().getItems());
                    assertEquals(divider, layout.node().getDividerPositions()[0], 0.001);
                    assertTrue(((RadioMenuItem) layout.menu().getItems().get(0)).isSelected());
                }
                assertEquals(3, hides.get(), "only transitions into results-only close editor popups");
                assertEquals("布局：分屏", layout.menu().getText());
            }
            return null;
        });
    }

    @Test void staleMenuActionsRespectGuardAndCanRecoverWithoutChangingSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            var allowed = new AtomicBoolean(true); var hides = new AtomicInteger();
            try (var layout = new SqlPanelLayout(new VBox(), new VBox(), allowed::get, hides::incrementAndGet)) {
                layout.select(EDITOR);
                var stale = (RadioMenuItem) layout.menu().getItems().get(2);
                allowed.set(false); stale.setSelected(true); stale.fire();
                assertEquals(EDITOR, layout.mode()); assertFalse(stale.isSelected());
                assertTrue(((RadioMenuItem) layout.menu().getItems().get(1)).isSelected());
                assertEquals(0, hides.get()); assertFalse(layout.revealEditor());
                allowed.set(true); assertTrue(layout.select(RESULTS));
                assertTrue(layout.revealEditor()); assertEquals(SPLIT, layout.mode());
                assertFalse(stale.isDisable());
                layout.select(EDITOR); assertTrue(layout.revealEditor()); assertEquals(EDITOR, layout.mode());
            }
            return null;
        });
    }

    @Test void disabledParentAndIdempotentCloseRejectLateActions() throws Exception {
        FxUiTestSupport.call(() -> {
            var hides = new AtomicInteger();
            var layout = new SqlPanelLayout(new VBox(), new VBox(), () -> true, hides::incrementAndGet);
            var root = new VBox(layout.menu(), layout.node()); root.setDisable(true);
            layout.menu().getItems().get(2).fire(); assertEquals(SPLIT, layout.mode());
            root.setDisable(false); layout.select(EDITOR); layout.close(); layout.close();
            layout.menu().getItems().get(2).fire();
            assertEquals(EDITOR, layout.mode()); assertFalse(layout.revealEditor());
            assertTrue(layout.menu().isDisabled()); assertEquals(0, hides.get());
            return null;
        });
    }

    @Test void queuedPulseCannotOverwriteNewerModeRestoreOrClosedLayout() throws Exception {
        FxUiTestSupport.call(() -> {
            var layout = new SqlPanelLayout(new VBox(), new VBox(), () -> true, () -> { });
            new Scene(new VBox(layout.menu(), layout.node()), 880, 850);
            layout.node().setDividerPositions(0.63);
            layout.select(RESULTS); layout.select(SPLIT);
            Runnable stale = pending(layout);
            assertNotNull(stale);
            layout.select(EDITOR); layout.select(SPLIT);
            Runnable latest = pending(layout);
            stale.run(); assertSame(latest, pending(layout), "stale callback must not cancel a newer restore");
            assertEquals(0.63, layout.node().getDividerPositions()[0], 0.001, "rapid switching keeps intended divider");
            latest.run(); assertNull(pending(layout));
            layout.select(RESULTS); layout.select(SPLIT); Runnable closing = pending(layout);
            layout.close(); layout.node().setDividerPositions(0.47); closing.run();
            assertEquals(0.47, layout.node().getDividerPositions()[0], 0.001); assertNull(pending(layout));
            return null;
        });
    }

    private static Runnable pending(SqlPanelLayout layout) throws Exception {
        var field = SqlPanelLayout.class.getDeclaredField("pendingRestore"); field.setAccessible(true);
        return (Runnable) field.get(layout);
    }
}
