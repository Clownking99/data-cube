package com.datacube.fx;

import java.util.List;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ResultColumnFindDialogTest {
    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void confirmingHiddenFarColumnActuallyScrollsWithoutChangingRowsOrSelection(String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table("id", "b", "c", "d", "e", "far");
            var row = javafx.collections.FXCollections.<Object>observableArrayList(3, "B", "C", "D", "E", "F");
            table.getItems().add(row);
            table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
            table.getSelectionModel().setCellSelectionEnabled(true);
            var id = table.getColumns().get(1); var far = table.getColumns().getLast(); far.setVisible(false);
            table.getSelectionModel().clearAndSelect(0, id); table.getFocusModel().focus(0, id);
            var order = List.copyOf(table.getColumns());
            var stage = new javafx.stage.Stage(); var root = new javafx.scene.layout.VBox(table);
            var scene = new javafx.scene.Scene(root, 400, 260); stage.setScene(scene);
            scene.getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
            var dialog = new ResultColumnFindDialog(table, stage, () -> true);
            try {
                stage.show(); root.applyCss(); root.layout(); table.layout();
                var bar = table.lookupAll(".scroll-bar").stream().filter(javafx.scene.control.ScrollBar.class::isInstance)
                        .map(node -> (javafx.scene.control.ScrollBar) node)
                        .filter(scroll -> scroll.getOrientation() == javafx.geometry.Orientation.HORIZONTAL).findFirst().orElseThrow();
                double before = bar.getValue();
                dialog.show(); query(dialog).setText("far");
                assertEquals(before, bar.getValue()); assertFalse(far.isVisible());
                confirm(dialog).fire(); root.applyCss(); root.layout(); table.layout();
                assertTrue(far.isVisible()); assertTrue(bar.getValue() > before, "confirm must actually scroll horizontally");
                assertEquals(order, table.getColumns()); assertSame(row, table.getItems().getFirst());
                assertTrue(table.getSelectionModel().isSelected(0, id));
                assertSame(id, table.getFocusModel().getFocusedCell().getTableColumn());
                assertEquals(180, far.getPrefWidth());
            } finally { dialog.close(); stage.hide(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"query-enter", "list-enter", "query-escape", "list-escape"})
    void keyboardConfirmsOrCancelsWithoutPreviewNavigation(String action) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table("one", "target"); var target = table.getColumns().get(2); target.setVisible(false);
            var dialog = new ResultColumnFindDialog(table, null, () -> true);
            try {
                dialog.show(); query(dialog).setText("target"); assertFalse(target.isVisible());
                boolean enter = action.endsWith("enter");
                javafx.scene.Node control = action.startsWith("query") ? query(dialog) : list(dialog);
                control.fireEvent(key(enter ? javafx.scene.input.KeyCode.ENTER : javafx.scene.input.KeyCode.ESCAPE));
                assertFalse(dialog.isShowing()); assertEquals(enter, target.isVisible());
                assertEquals(enter ? Boolean.TRUE : null, dialog.getResult());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"replace", "remove", "reorder", "denied", "table-disabled", "disposed"})
    void staleOrDeniedCandidatesNeverNavigateToASameNamedReplacement(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table("one", "target"); var old = table.getColumns().get(2); old.setVisible(false);
            var replacement = table("target").getColumns().get(1);
            var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
            var scrolled = new java.util.concurrent.atomic.AtomicInteger();
            table.addEventHandler(javafx.scene.control.ScrollToEvent.scrollToColumn(), event -> scrolled.incrementAndGet());
            var dialog = new ResultColumnFindDialog(table, null, allowed::get);
            try {
                dialog.show(); query(dialog).setText("target");
                switch (state) {
                    case "replace" -> table.getColumns().set(2, replacement);
                    case "remove" -> table.getColumns().remove(old);
                    case "reorder" -> java.util.Collections.swap(table.getColumns(), 1, 2);
                    case "denied" -> allowed.set(false);
                    case "table-disabled" -> table.setDisable(true);
                    case "disposed" -> dialog.close();
                    default -> throw new AssertionError(state);
                }
                // Direct event dispatch exercises the handler even after normal button disablement.
                confirm(dialog).fireEvent(new javafx.event.ActionEvent());
                assertEquals(0, scrolled.get()); assertFalse(old.isVisible());
                assertNull(dialog.getResult());
                assertEquals(!state.equals("disposed"), dialog.isShowing());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void visibilityChangeRequiresASecondExplicitConfirmation() throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table("target"); var target = table.getColumns().get(1);
            var dialog = new ResultColumnFindDialog(table, null, () -> true);
            try {
                dialog.show(); assertEquals("定位", confirm(dialog).getText());
                target.setVisible(false); confirm(dialog).fire();
                assertFalse(target.isVisible()); assertTrue(dialog.isShowing());
                assertEquals("显示并定位", confirm(dialog).getText());
                confirm(dialog).fire(); assertTrue(target.isVisible()); assertFalse(dialog.isShowing());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {255, 256, 257})
    void queryLengthBoundaryRejectsOverlongInputAndCanRecover(int length) throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = new ResultColumnFindDialog(table("x".repeat(260)), null, () -> true);
            try {
                dialog.show(); query(dialog).setText("x".repeat(length));
                assertEquals(length <= 256 ? 1 : 0, list(dialog).getItems().size());
                assertEquals(length > 256, confirm(dialog).isDisabled());
                if (length > 256) assertTrue(status(dialog).contains("过长"));
                query(dialog).setText("missing"); assertTrue(confirm(dialog).isDisabled()); assertTrue(status(dialog).contains("匹配 0 / 1"));
                query(dialog).setText(""); assertEquals(1, list(dialog).getItems().size()); assertFalse(confirm(dialog).isDisabled());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {0, 199, 200, 201})
    void candidateLimitIsExplicitAndCanFindAColumnBeyondTheInitialList(int count) throws Exception {
        FxUiTestSupport.call(() -> {
            String[] names = java.util.stream.IntStream.range(0, count).mapToObj(i -> "column-" + i).toArray(String[]::new);
            var dialog = new ResultColumnFindDialog(table(names), null, () -> true);
            try {
                dialog.show(); assertEquals(Math.min(count, 200), list(dialog).getItems().size());
                assertEquals(count > 200, status(dialog).contains("仅列出前 200"));
                assertEquals(count == 0, confirm(dialog).isDisabled());
                if (count > 200) { query(dialog).setText("column-200"); assertEquals(1, list(dialog).getItems().size()); }
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void queryArrowsChooseDuplicateIdentityAndClosedDialogReleasesColumnListener() throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table("same", "same"); var dialog = new ResultColumnFindDialog(table, null, () -> true);
            dialog.show(); query(dialog).fireEvent(key(javafx.scene.input.KeyCode.DOWN));
            assertSame(table.getColumns().get(2), list(dialog).getSelectionModel().getSelectedItem());
            query(dialog).fireEvent(key(javafx.scene.input.KeyCode.UP));
            assertSame(table.getColumns().get(1), list(dialog).getSelectionModel().getSelectedItem());
            dialog.close(); String before = status(dialog);
            table.getColumns().clear(); assertEquals(before, status(dialog)); assertTrue(list(dialog).getItems().isEmpty());
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"480,dark", "720,dark", "480,light", "720,light"})
    void boundedMetadataAndInstructionsStayReadable(double width, String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table("a\n".repeat(255) + "😀\rTAIL", "名称_下划线", "<b>literal</b>");
            table.getColumns().get(2).setVisible(false);
            var dialog = new ResultColumnFindDialog(table, null, () -> true);
            dialog.getDialogPane().getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
            try {
                dialog.show(); dialog.setWidth(width); dialog.setHeight(480);
                var root = dialog.getDialogPane(); root.applyCss(); root.layout();
                var first = list(dialog).lookupAll(".list-cell").stream().filter(javafx.scene.control.ListCell.class::isInstance)
                        .map(node -> (javafx.scene.control.ListCell<?>) node).filter(cell -> cell.getIndex() == 0).findFirst().orElseThrow();
                assertEquals("原列 1 · " + "a ".repeat(255) + "😀…", first.getText());
                for (String suffix : List.of("query", "list", "status", "hint", "locate", "cancel")) {
                    var node = (javafx.scene.layout.Region) root.lookup("#result-column-" + suffix);
                    var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= root.getWidth() + 1, suffix);
                    assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= root.getHeight() + 1, suffix);
                }
                var hint = (javafx.scene.control.Label) root.lookup("#result-column-hint");
                assertTrue(hint.getHeight() + 1 >= hint.prefHeight(hint.getWidth()));
                query(dialog).setText("TAIL"); assertEquals(1, list(dialog).getItems().size(), "search uses original untruncated name");
                query(dialog).setText("名称_"); assertSame(table.getColumns().get(2), list(dialog).getItems().getFirst());
                root.applyCss(); root.layout();
                assertEquals("显示并定位", confirm(dialog).getText());
                assertTrue(confirm(dialog).getWidth() + 1 >= confirm(dialog).prefWidth(-1), "dynamic confirmation label must not truncate");
                assertEquals("显示并定位", ((javafx.scene.text.Text) confirm(dialog).lookup(".text")).getText(), "rendered button text must retain the full action");
                query(dialog).setText("<b>"); assertSame(table.getColumns().get(3), list(dialog).getItems().getFirst());
            } finally { dialog.close(); }
            return null;
        });
    }

    static String status(ResultColumnFindDialog dialog) { return ((javafx.scene.control.Label) dialog.getDialogPane().lookup("#result-column-status")).getText(); }
    static javafx.scene.input.KeyEvent key(javafx.scene.input.KeyCode code) {
        return new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }
    @Test void filteringHiddenDuplicateColumnsOnlyNavigatesOnExplicitConfirmation() throws Exception {
        FxUiTestSupport.call(() -> {
            var table = table("same", "same", "other");
            var first = table.getColumns().get(1); var hidden = table.getColumns().get(2);
            hidden.setVisible(false);
            var dialog = new ResultColumnFindDialog(table, null, () -> true);
            try {
                dialog.show();
                query(dialog).setText("SAME");
                assertEquals(List.of(first, hidden), list(dialog).getItems());
                list(dialog).getSelectionModel().select(hidden);
                assertFalse(hidden.isVisible()); assertEquals("显示并定位", confirm(dialog).getText());
                confirm(dialog).fire();
                assertTrue(hidden.isVisible()); assertFalse(dialog.isShowing()); assertEquals(Boolean.TRUE, dialog.getResult());
            } finally { dialog.close(); }
            return null;
        });
    }

    static TableView<ObservableList<Object>> table(String... labels) {
        var table = new TableView<ObservableList<Object>>();
        table.getColumns().add(new TableColumn<>("#"));
        for (int i = 0; i < labels.length; i++) {
            var column = new TableColumn<ObservableList<Object>, Object>(labels[i]);
            column.setUserData(i); column.getProperties().put("sql-result-label", labels[i]);
            column.setPrefWidth(180); table.getColumns().add(column);
        }
        return table;
    }
    static TextField query(ResultColumnFindDialog dialog) { return (TextField) dialog.getDialogPane().lookup("#result-column-query"); }
    @SuppressWarnings("unchecked") static ListView<TableColumn<ObservableList<Object>, ?>> list(ResultColumnFindDialog dialog) {
        return (ListView<TableColumn<ObservableList<Object>, ?>>) dialog.getDialogPane().lookup("#result-column-list");
    }
    static Button confirm(ResultColumnFindDialog dialog) { return (Button) dialog.getDialogPane().lookup("#result-column-locate"); }
}
