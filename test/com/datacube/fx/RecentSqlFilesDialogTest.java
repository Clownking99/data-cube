package com.datacube.fx;

import com.datacube.config.AppSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RecentSqlFilesDialogTest {
    @TempDir Path directory;
    List<Path> paths() { return List.of(directory.resolve("Reporting/same.sql"), directory.resolve("archive/same.sql"),
            directory.resolve("中😀/[literal].sql")); }

    @Test void snapshotShowsExactReadOnlyPathWithoutProbingMissingFiles() throws Exception {
        FxUiTestSupport.call(() -> {
            var paths = new ArrayList<>(paths()); var d = create(paths); paths.clear();
            assertEquals(paths(), list(d).getItems()); assertEquals(paths().getFirst(), selected(d));
            assertEquals(paths().getFirst().toString(), preview(d).getText()); assertFalse(preview(d).isEditable());
            assertEquals("显示 3 / 3 个最近文件", count(d)); assertNull(d.getResult());
            assertFalse(button(d, "open").isDefaultButton()); assertTrue(d.isResizable());
            list(d).getSelectionModel().select(1);
            assertEquals(paths().get(1).toString(), preview(d).getText()); return null;
        });
        assertFalse(java.nio.file.Files.exists(directory.resolve("Reporting")));
    }

    @ParameterizedTest @ValueSource(strings = {" SAME.SQL ", "ARCHIVE", "中😀", "[literal]"})
    void literalPathSearchPreservesOrderWithoutImplicitOpening(String term) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(paths()); query(d).setText(term);
            List<Path> expected = term.contains("SAME") ? paths().subList(0, 2)
                    : term.equals("ARCHIVE") ? List.of(paths().get(1)) : List.of(paths().getLast());
            assertEquals(expected, list(d).getItems()); assertNull(d.getResult());
            if (!expected.contains(paths().getFirst())) { assertNull(selected(d)); assertTrue(button(d, "open").isDisabled()); }
            return null;
        });
    }

    @Test void selectionFollowsPathNeverReusedIndexAndClearDoesNotSelectReplacement() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(paths()); list(d).getSelectionModel().select(1);
            query(d).setText("same"); assertEquals(paths().get(1), selected(d));
            query(d).setText("archive"); assertEquals(paths().get(1), selected(d));
            assertEquals(0, list(d).getSelectionModel().getSelectedIndex());
            query(d).setText("Reporting"); assertNull(selected(d)); assertEquals("", preview(d).getText());
            button(d, "clear").fire(); assertEquals(paths(), list(d).getItems()); assertNull(selected(d));
            assertTrue(button(d, "open").isDisabled()); return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void emptyAndNoMatchAreDistinctAndCannotConfirm(boolean empty) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(empty ? List.of() : paths());
            try {
                d.show(); query(d).setText("NOT_FOUND");
                assertEquals(empty ? "显示 0 / 0 个最近文件" : "显示 0 / 3 个最近文件", count(d));
                assertEquals(empty ? "暂无最近 SQL 文件" : "没有匹配的文件，请修改或清除筛选", ((Label) list(d).getPlaceholder()).getText());
                query(d).fireEvent(key(KeyCode.ENTER)); button(d, "open").fire();
                assertNull(d.getResult()); assertTrue(d.isShowing());
                assertEquals("", preview(d).getText());
                button(d, "clear").fire(); assertEquals(empty ? List.of() : paths(), list(d).getItems());
            } finally { d.close(); } return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"button", "query", "list"})
    void explicitConfirmationReturnsExactFilteredPath(String surface) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(paths());
            try {
                d.show(); query(d).setText("archive"); assertNull(selected(d));
                query(d).fireEvent(key(KeyCode.DOWN)); assertEquals(paths().get(1), selected(d));
                assertSame(list(d), d.getDialogPane().getScene().getFocusOwner());
                if (surface.equals("button")) button(d, "open").fire();
                else (surface.equals("query") ? query(d) : list(d)).fireEvent(key(KeyCode.ENTER));
                assertFalse(d.isShowing()); assertEquals(paths().get(1), d.getResult());
            } finally { d.close(); } return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"button", "query", "list", "path"})
    void cancelFromEverySurfaceReturnsNothing(String surface) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(paths());
            try {
                d.show(); list(d).getSelectionModel().select(1);
                if (surface.equals("button")) button(d, "cancel").fire();
                else (surface.equals("query") ? query(d) : surface.equals("list") ? list(d) : preview(d)).fireEvent(key(KeyCode.ESCAPE));
                assertFalse(d.isShowing()); assertNull(d.getResult());
            } finally { d.close(); } return null;
        });
    }

    @Test void pathPreviewEnterCannotOpenAndCtrlFReturnsToQuery() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(paths());
            try {
                d.show(); query(d).setText("same"); preview(d).requestFocus();
                preview(d).fireEvent(key(KeyCode.ENTER)); assertNull(d.getResult()); assertTrue(d.isShowing());
                preview(d).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false));
                assertSame(query(d), d.getDialogPane().getScene().getFocusOwner()); assertEquals("same", query(d).getSelectedText());
            } finally { d.close(); } return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"guard", "disabled", "closed"})
    void staleActionsCannotReturnCandidate(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var allowed = new AtomicBoolean(true); var d = RecentSqlFilesDialog.create(paths(), null, allowed::get);
            try {
                d.show();
                switch (state) {
                    case "guard" -> allowed.set(false);
                    case "disabled" -> d.getDialogPane().setDisable(true);
                    case "closed" -> d.close();
                }
                button(d, "open").fire();
                assertNull(d.getResult());
                var openType = d.getDialogPane().getButtonTypes().getFirst();
                assertNull(d.getResultConverter().call(openType));
            } finally { d.close(); } return null;
        });
    }

    @Test void queryLimitIsInclusiveAndOverLimitEditRejectedWithoutChangingState() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(paths()); var query = query(d);
            query.setText("x".repeat(255)); assertEquals(255, query.getLength());
            query.appendText("x"); assertEquals(256, query.getLength());
            query.appendText("y"); assertEquals("x".repeat(256), query.getText());
            query.replaceText(0, 1, "xx"); assertEquals("x".repeat(256), query.getText());
            assertNull(selected(d)); assertEquals("显示 0 / 3 个最近文件", count(d));
            button(d, "clear").fire(); assertEquals(paths(), list(d).getItems()); return null;
        });
    }

    @Test void localeIndependentSearchCoversFullCapacityAndUntruncatedPath() throws Exception {
        FxUiTestSupport.call(() -> {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                var all = new ArrayList<Path>(); for (int i = 0; i < 9; i++) all.add(directory.resolve("file-" + i + ".sql"));
                Path tail = directory.resolve("I".repeat(240)).resolve("NEEDLE.sql"); all.add(tail);
                var d = create(all); query(d).setText("needle"); assertEquals(List.of(tail), list(d).getItems());
                list(d).getSelectionModel().selectFirst(); assertEquals(tail.toString(), preview(d).getText());
                query(d).setText("i".repeat(200)); assertEquals(List.of(tail), list(d).getItems());
                button(d, "clear").fire(); assertEquals(all, list(d).getItems()); assertEquals(tail, selected(d));
                assertEquals("显示 10 / 10 个最近文件", count(d));
            } finally { Locale.setDefault(original); } return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void narrowDialogKeepsSearchPathAndActionsVisibleInBothThemes(boolean dark) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = create(paths()); var settings = new AppSettings(directory.resolve("settings"));
            settings.setTheme(dark ? AppSettings.Theme.DARK : AppSettings.Theme.LIGHT);
            new ThemeManager(settings).applyTo(d.getDialogPane());
            try {
                d.show(); d.setWidth(480); d.getDialogPane().applyCss(); d.getDialogPane().layout();
                for (Node node : List.of(query(d), button(d, "clear"), list(d), preview(d), button(d, "open"), button(d, "cancel"))) {
                    var b = node.localToScene(node.getBoundsInLocal());
                    assertTrue(b.getWidth() > 20); assertTrue(b.getMinX() >= -1);
                    assertTrue(b.getMaxX() <= d.getDialogPane().getScene().getWidth() + 1, node.getId());
                }
                query(d).requestFocus(); d.getDialogPane().applyCss();
                assertPromptVisible(query(d));
                for (var node : list(d).lookupAll(".list-cell")) {
                    if (!(node instanceof ListCell<?> cell) || cell.isEmpty()) continue;
                    var lines = (javafx.scene.layout.VBox) cell.getGraphic();
                    var folder = (Label) lines.getChildren().get(1);
                    Path original = (Path) cell.getItem();
                    assertEquals(original.getParent().toString(), folder.getText());
                    String rendered = folder.lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance)
                            .map(javafx.scene.text.Text.class::cast).map(javafx.scene.text.Text::getText).findFirst().orElseThrow();
                    assertTrue(rendered.endsWith(original.getParent().getFileName().toString()), rendered);
                    assertTrue(lines.getWidth() < list(d).getWidth());
                }
                query(d).setText("missing"); preview(d).requestFocus();
                d.getDialogPane().applyCss(); d.getDialogPane().layout(); assertPromptVisible(preview(d));
            } finally { d.close(); } return null;
        });
    }

    static Dialog<Path> create(List<Path> paths) { return RecentSqlFilesDialog.create(paths, null, () -> true); }
    @SuppressWarnings("unchecked") static ListView<Path> list(Dialog<Path> d) { return (ListView<Path>) d.getDialogPane().lookup("#recent-sql-list"); }
    static TextField query(Dialog<Path> d) { return (TextField) d.getDialogPane().lookup("#recent-sql-query"); }
    static TextArea preview(Dialog<Path> d) { return (TextArea) d.getDialogPane().lookup("#recent-sql-path"); }
    static Button button(Dialog<Path> d, String name) {
        for (var type : d.getDialogPane().getButtonTypes()) {
            var node = (Button) d.getDialogPane().lookupButton(type);
            if (("recent-sql-" + name).equals(node.getId())) return node;
        }
        return (Button) d.getDialogPane().lookup("#recent-sql-" + name);
    }
    static Path selected(Dialog<Path> d) { return list(d).getSelectionModel().getSelectedItem(); }
    static String count(Dialog<Path> d) { return ((Label) d.getDialogPane().lookup("#recent-sql-count")).getText(); }
    static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false); }
    static void assertPromptVisible(TextInputControl input) {
        var prompt = input.lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance)
                .map(javafx.scene.text.Text.class::cast).filter(text -> input.getPromptText().equals(text.getText())).findFirst().orElseThrow();
        var fill = (javafx.scene.paint.Color) prompt.getFill();
        assertEquals(1.0, fill.getOpacity()); assertTrue(fill.getBrightness() > 0.15);
    }
}
