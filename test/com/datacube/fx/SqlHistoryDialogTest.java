package com.datacube.fx;

import com.datacube.config.SqlHistoryStore.Entry;
import com.datacube.config.SqlHistoryStore;
import com.datacube.config.AppSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlHistoryDialogTest {
    @TempDir Path directory;
    private static final Entry NEWEST = new Entry(3000, "REPORTING", "PUBLIC", "select '中😀';\n-- recent");
    private static final Entry MIDDLE = new Entry(2000, "warehouse", "sales", "select *\nfrom orders where id = 7;");
    private static final Entry OLDEST = new Entry(1000, null, null, "select '[.*]';");
    private static final List<Entry> ENTRIES = List.of(NEWEST, MIDDLE, OLDEST);

    @Test void initialCountDistinguishesEmptyHistory() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = SqlHistoryDialog.create(List.of(), null, null);
            var count = (Label) dialog.getDialogPane().lookup("#sql-history-count");
            assertNotNull(count, "history retrieval needs an explicit match count");
            assertEquals("显示 0 / 0 条历史", count.getText());
            assertEquals("暂无 SQL 历史", ((Label) list(dialog).getPlaceholder()).getText());
            assertTrue(button(dialog, "open").isDisabled());
            assertTrue(button(dialog, "clear").isDisabled());
            assertEquals("", preview(dialog).getText());
            query(dialog).setText("anything");
            assertEquals("暂无 SQL 历史", ((Label) list(dialog).getPlaceholder()).getText());
            return null;
        });
    }

    @Test void initialSnapshotSelectsLatestAndKeepsFullReadOnlyPreview() throws Exception {
        FxUiTestSupport.call(() -> {
            var source = new ArrayList<>(ENTRIES);
            var d = SqlHistoryDialog.create(source, null, null);
            source.clear();
            assertEquals(ENTRIES, list(d).getItems());
            assertSame(NEWEST, list(d).getSelectionModel().getSelectedItem());
            assertEquals(NEWEST.sql(), preview(d).getText()); assertFalse(preview(d).isEditable());
            assertEquals("显示 3 / 3 条历史", count(d));
            assertFalse(button(d, "open").isDisabled()); assertFalse(button(d, "open").isDefaultButton());
            assertTrue(d.isResizable()); assertNull(d.getResult());
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"' reporting ',0", "public,0", "中😀,0", "'FROM ORDERS',1", "sales,1", "warehouse,1", "[.*],2"})
    void literalSearchMatchesAllThreeFieldsAndFullSql(String input, int index) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(ENTRIES, null, null);
            query(d).setText(input);
            assertEquals(List.of(ENTRIES.get(index)), list(d).getItems());
            assertSame(ENTRIES.get(index), list(d).getSelectionModel().getSelectedItem());
            assertEquals(ENTRIES.get(index).sql(), preview(d).getText());
            assertEquals("显示 1 / 3 条历史", count(d)); assertNull(d.getResult());
            return null;
        });
    }

    @Test void searchIsIndependentOfTurkishDefaultLocale() throws Exception {
        FxUiTestSupport.call(() -> {
            Locale before = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                var d = SqlHistoryDialog.create(ENTRIES, null, null);
                query(d).setText("reporting"); assertEquals(List.of(NEWEST), list(d).getItems());
                query(d).setText("PUBLIC"); assertEquals(List.of(NEWEST), list(d).getItems());
            } finally { Locale.setDefault(before); }
            return null;
        });
    }

    @Test void fullHistoryCapacityCanFindOldestEntryAndRestoresOriginalOrder() throws Exception {
        FxUiTestSupport.call(() -> {
            List<Entry> full = java.util.stream.IntStream.range(0, SqlHistoryStore.MAX_ENTRIES)
                    .mapToObj(i -> new Entry(1000 - i, null, null, "select " + i + ";")).toList();
            var d = SqlHistoryDialog.create(full, null, null);
            query(d).setText("199;");
            assertEquals(List.of(full.getLast()), list(d).getItems()); assertEquals("显示 1 / 200 条历史", count(d));
            button(d, "clear").fire();
            assertEquals(full, list(d).getItems()); assertSame(full.getLast(), list(d).getSelectionModel().getSelectedItem());
            assertEquals("显示 200 / 200 条历史", count(d));
            return null;
        });
    }

    @Test void filtersRetainEntryNotIndexAndFallbackOnlyWhenRemoved() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(ENTRIES, null, null);
            list(d).getSelectionModel().select(OLDEST);
            query(d).setText("select '");
            assertEquals(List.of(NEWEST, OLDEST), list(d).getItems());
            assertSame(OLDEST, list(d).getSelectionModel().getSelectedItem());
            assertEquals(1, list(d).getSelectionModel().getSelectedIndex());
            query(d).setText("orders");
            assertSame(MIDDLE, list(d).getSelectionModel().getSelectedItem());
            button(d, "clear").fire();
            assertEquals(ENTRIES, list(d).getItems()); assertEquals("", query(d).getText());
            assertSame(MIDDLE, list(d).getSelectionModel().getSelectedItem());
            assertEquals(MIDDLE.sql(), preview(d).getText()); assertTrue(button(d, "clear").isDisabled());
            query(d).setText("\u2003 \t");
            assertEquals(ENTRIES, list(d).getItems()); assertSame(MIDDLE, list(d).getSelectionModel().getSelectedItem());
            return null;
        });
    }

    @Test void noMatchClearsPreviewBlocksConfirmationAndClearRecovers() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(ENTRIES, null, null);
            try {
                d.show(); query(d).setText("no matching SQL");
                assertEquals("显示 0 / 3 条历史", count(d));
                assertEquals("没有匹配的历史，请修改或清除筛选", ((Label) list(d).getPlaceholder()).getText());
                assertNull(list(d).getSelectionModel().getSelectedItem()); assertEquals("", preview(d).getText());
                assertTrue(button(d, "open").isDisabled());
                button(d, "open").fire(); query(d).fireEvent(key(KeyCode.ENTER)); list(d).fireEvent(key(KeyCode.ENTER));
                assertTrue(d.isShowing()); assertNull(d.getResult());
                button(d, "clear").fire();
                assertEquals(ENTRIES, list(d).getItems()); assertEquals("显示 3 / 3 条历史", count(d));
                assertSame(NEWEST, list(d).getSelectionModel().getSelectedItem());
                assertEquals(NEWEST.sql(), preview(d).getText()); assertFalse(button(d, "open").isDisabled());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"query", "list", "button"})
    void confirmationReturnsExactFilteredEntry(String control) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(ENTRIES, null, null);
            try {
                d.show(); query(d).setText("orders");
                assertTrue(d.isShowing()); assertNull(d.getResult());
                if (control.equals("button")) button(d, "open").fire();
                else (control.equals("query") ? query(d) : list(d)).fireEvent(key(KeyCode.ENTER));
                assertFalse(d.isShowing()); assertSame(MIDDLE, d.getResult());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"query", "list", "preview", "button"})
    void cancelFromAnySurfaceReturnsNoEntry(String control) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(ENTRIES, null, null);
            try {
                d.show(); query(d).setText("orders");
                if (control.equals("button")) button(d, "cancel").fire();
                else (control.equals("query") ? query(d) : control.equals("list") ? list(d) : preview(d)).fireEvent(key(KeyCode.ESCAPE));
                assertFalse(d.isShowing()); assertNull(d.getResult());
            } finally { d.close(); }
            return null;
        });
    }

    @Test void keyboardFocusAndPreviewEnterAreSafe() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(ENTRIES, null, null);
            try {
                d.show(); assertSame(query(d), d.getDialogPane().getScene().getFocusOwner());
                query(d).setText("select"); list(d).getSelectionModel().select(MIDDLE);
                query(d).fireEvent(key(KeyCode.DOWN));
                assertSame(list(d), d.getDialogPane().getScene().getFocusOwner());
                assertSame(MIDDLE, list(d).getSelectionModel().getSelectedItem());
                preview(d).requestFocus(); preview(d).fireEvent(key(KeyCode.ENTER));
                assertTrue(d.isShowing()); assertNull(d.getResult());
                preview(d).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false));
                assertSame(query(d), d.getDialogPane().getScene().getFocusOwner());
                assertEquals("select", query(d).getSelectedText());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"blank", "empty-cell", "secondary", "single"})
    void nonActivationClicksNeverReturnOldSelection(String kind) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(ENTRIES, null, null);
            try {
                d.show(); d.getDialogPane().applyCss(); d.getDialogPane().layout();
                Node target = list(d);
                if (!kind.equals("blank")) {
                    ListCell<Entry> cell = list(d).getCellFactory().call(list(d));
                    cell.updateListView(list(d)); cell.updateIndex(kind.equals("empty-cell") ? 99 : 1);
                    assertEquals(kind.equals("empty-cell") ? null : MIDDLE, cell.getItem());
                    target = cell;
                }
                target.fireEvent(mouse(kind.equals("secondary") ? MouseButton.SECONDARY : MouseButton.PRIMARY, kind.equals("single") ? 1 : 2));
                assertTrue(d.isShowing()); assertNull(d.getResult());
                assertEquals(NEWEST.sql(), preview(d).getText());
            } finally { d.close(); }
            return null;
        });
    }

    @Test void primaryDoubleClickReturnsClickedCellNotPreviousSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(ENTRIES, null, null);
            try {
                d.show();
                ListCell<Entry> cell = list(d).getCellFactory().call(list(d));
                cell.updateListView(list(d)); cell.updateIndex(1);
                assertSame(NEWEST, list(d).getSelectionModel().getSelectedItem());
                cell.fireEvent(mouse(MouseButton.PRIMARY, 2));
                assertSame(MIDDLE, d.getResult()); assertFalse(d.isShowing());
            } finally { d.close(); }
            return null;
        });
    }

    @Test void browsingAndOpeningDoNotRewriteHistory() throws Exception {
        var file = directory.resolve("sql-history.txt");
        var store = new SqlHistoryStore(file);
        store.recordStrict("local", "PUBLIC", "select 1;\nselect 2;");
        byte[] before = Files.readAllBytes(file); var snapshot = store.recent();
        FxUiTestSupport.call(() -> {
            var d = SqlHistoryDialog.create(store.recent(), null, null);
            try { d.show(); query(d).setText("select 2"); button(d, "open").fire(); assertSame(snapshot.getFirst(), d.getResult()); }
            finally { d.close(); }
            return null;
        });
        assertArrayEquals(before, Files.readAllBytes(file)); assertEquals(snapshot, store.recent());
    }

    @ParameterizedTest @CsvSource({"600,DARK", "760,DARK", "600,LIGHT", "760,LIGHT"})
    void narrowAndWideLayoutsKeepInstructionsAndControlsVisible(double width, AppSettings.Theme mode) throws Exception {
        FxUiTestSupport.call(() -> {
            var settings = new AppSettings(directory.resolve("settings")); settings.setTheme(mode);
            var d = SqlHistoryDialog.create(ENTRIES, null, new ThemeManager(settings));
            try {
                d.show(); d.setWidth(width); d.setHeight(500);
                d.getDialogPane().applyCss(); d.getDialogPane().layout();
                Label hint = (Label) d.getDialogPane().lookup("#sql-history-hint");
                String rendered = hint.lookupAll(".text").stream().filter(Text.class::isInstance)
                        .map(Text.class::cast).map(Text::getText).findFirst().orElseThrow();
                assertEquals(hint.getText(), rendered);
                assertTrue(hint.getHeight() + 1 >= hint.prefHeight(hint.getWidth()));
                assertTrue(query(d).getWidth() > 150); assertTrue(list(d).getWidth() >= 160); assertTrue(preview(d).getWidth() >= 160);
                assertTrue(button(d, "clear").getWidth() + 1 >= button(d, "clear").prefWidth(-1));
                query(d).requestFocus();
                assertPromptVisible(query(d));
                query(d).setText("no match"); preview(d).requestFocus();
                d.getDialogPane().applyCss(); d.getDialogPane().layout();
                assertPromptVisible(preview(d));
                assertTrue(d.getDialogPane().getStylesheets().stream().anyMatch(s -> s.contains(mode == AppSettings.Theme.DARK ? "theme-dark" : "theme-light")));
            } finally { d.close(); }
            return null;
        });
    }

    private static void assertPromptVisible(javafx.scene.control.TextInputControl input) {
        Text prompt = input.lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                .filter(text -> input.getPromptText().equals(text.getText())).findFirst().orElseThrow();
        javafx.scene.paint.Color fill = (javafx.scene.paint.Color) prompt.getFill();
        assertEquals(1.0, fill.getOpacity(), "focused empty controls still need discoverable guidance");
        assertTrue(fill.getBrightness() > 0.15, "dark prompt must not render nearly black");
    }

    private static TextField query(Dialog<Entry> d) { return (TextField) d.getDialogPane().lookup("#sql-history-filter"); }
    private static TextArea preview(Dialog<Entry> d) { return (TextArea) split(d).getItems().get(1); }
    private static Button button(Dialog<Entry> d, String name) {
        if (name.equals("clear")) return (Button) d.getDialogPane().lookup("#sql-history-clear");
        return d.getDialogPane().getButtonTypes().stream().map(d.getDialogPane()::lookupButton).map(Button.class::cast)
                .filter(b -> ("sql-history-" + name).equals(b.getId())).findFirst().orElseThrow();
    }
    private static String count(Dialog<Entry> d) { return ((Label) d.getDialogPane().lookup("#sql-history-count")).getText(); }
    private static SplitPane split(Dialog<Entry> d) {
        // SplitPane items only enter CSS lookup after the skin is installed.
        return ((VBox) d.getDialogPane().getContent()).getChildren().stream().filter(SplitPane.class::isInstance)
                .map(SplitPane.class::cast).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private static ListView<Entry> list(Dialog<Entry> d) { return (ListView<Entry>) split(d).getItems().getFirst(); }
    private static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false); }
    private static MouseEvent mouse(MouseButton button, int clicks) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 10, 10, 10, 10, button, clicks,
                false, false, false, false, false, false, false, false, false, true, null);
    }
}
