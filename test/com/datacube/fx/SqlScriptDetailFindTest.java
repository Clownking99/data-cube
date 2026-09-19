package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.SqlScriptExecutionReport;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptDetailFindTest {
    @Test void switchingScopeKeepsQueryOptionsAndSelectionsButRecomputesMatches() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("alpha ALPHA alpha", QueryResult.error("alpha only", 5));
            try {
                d.show(); sql(d).selectRange(8, 6); error(d).selectRange(8, 6);
                query(d).setText("alpha"); assertTrue(status(d).startsWith("匹配 3 处"));
                assertEquals(8, sql(d).getAnchor()); assertEquals(6, sql(d).getCaretPosition());
                next(d).fire(); assertSelection(sql(d), 12, 17); next(d).fire(); assertSelection(sql(d), 0, 5);
                previous(d).fire(); assertSelection(sql(d), 12, 17);
                matchCase(d).fire(); assertTrue(status(d).contains("2 处"));
                scope(d).getSelectionModel().select(1);
                assertEquals("alpha", query(d).getText()); assertTrue(matchCase(d).isSelected());
                assertTrue(status(d).startsWith("匹配 1 处")); assertSelection(sql(d), 12, 17);
                assertEquals(8, error(d).getAnchor()); assertEquals(6, error(d).getCaretPosition());
                next(d).fire(); assertSelection(error(d), 0, 5); assertSelection(sql(d), 12, 17);
                // Detached SQL selection/text listeners must not replace the active error count.
                sql(d).positionCaret(0); sql(d).setText("alpha alpha alpha alpha");
                assertTrue(status(d).startsWith("第 1 / 1 处"));
                scope(d).getSelectionModel().select(0); assertTrue(status(d).startsWith("匹配 4 处"));
                next(d).fire(); assertSelection(sql(d), 0, 5); assertSelection(error(d), 0, 5);
                assertFalse(sql(d).isEditable()); assertFalse(error(d).isEditable());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"query", "update", "error", "timeout", "cancel"})
    void resultKindsOfferOnlyExistingScopesAndDoNotSearchMetadataOrRows(String kind) throws Exception {
        FxUiTestSupport.call(() -> {
            QueryResult result = switch (kind) {
                case "query" -> QueryResult.query(List.of("secretColumn"), List.of(List.of("secretRow")), 3);
                case "update" -> QueryResult.update(3, 9);
                case "error" -> QueryResult.error("diagnostic", 3);
                case "timeout" -> QueryResult.timeout("diagnostic", 3);
                default -> QueryResult.cancelled("diagnostic", 3);
            };
            var d = dialog("select body", result);
            try {
                d.show(); boolean abnormal = result.kind == QueryResult.Kind.ERROR;
                assertEquals(abnormal ? 2 : 1, scope(d).getItems().size());
                assertEquals(0, scope(d).getSelectionModel().getSelectedIndex());
                assertEquals(!abnormal, scope(d).isDisabled());
                for (String text : List.of("secretColumn", "secretRow", "语句 #17", "diagnostic")) {
                    query(d).setText(text); assertTrue(next(d).isDisabled());
                }
                if (abnormal) {
                    scope(d).getSelectionModel().select(1); assertFalse(next(d).isDisabled()); next(d).fire();
                    assertEquals("diagnostic", error(d).getSelectedText()); assertEquals("", sql(d).getSelectedText());
                }
            } finally { d.close(); }
            return null;
        });
    }

    @Test void shortcutsSelectFocusedBodyButNavigationUsesVisibleScope() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("a A a", QueryResult.error("a a", 1));
            try {
                d.show(); query(d).setText("a"); error(d).requestFocus(); error(d).fireEvent(key(KeyCode.F, false, true));
                assertEquals(1, scope(d).getSelectionModel().getSelectedIndex());
                assertSame(query(d), d.getDialogPane().getScene().getFocusOwner()); assertEquals("a", query(d).getSelectedText());
                query(d).fireEvent(key(KeyCode.ENTER, false, false)); assertSelection(error(d), 0, 1);
                query(d).fireEvent(key(KeyCode.ENTER, true, false)); assertSelection(error(d), 2, 3);
                sql(d).requestFocus(); sql(d).fireEvent(key(KeyCode.F3, false, false));
                assertSelection(error(d), 0, 1); assertEquals("", sql(d).getSelectedText());
                sql(d).fireEvent(key(KeyCode.F, false, true)); assertEquals(0, scope(d).getSelectionModel().getSelectedIndex());
                query(d).fireEvent(key(KeyCode.F3, true, false)); assertSelection(sql(d), 4, 5);
                close(d).requestFocus(); close(d).fireEvent(key(KeyCode.F, false, true));
                assertSame(query(d), d.getDialogPane().getScene().getFocusOwner());
                assertEquals(0, scope(d).getSelectionModel().getSelectedIndex());
                query(d).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F3, false, false, true, false));
                assertSelection(sql(d), 4, 5); assertTrue(d.isShowing());
                assertEquals("a A a", sql(d).getText()); assertEquals("a a", error(d).getText());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"1023,true", "1024,true", "1025,false"})
    void queryLimitRejectsWholeInputAndRecoversOnEitherScope(int length, boolean allowed) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("x".repeat(1200), QueryResult.error("x".repeat(1200), 1));
            try {
                d.show(); query(d).setText("x".repeat(length));
                for (int i = 0; i < 2; i++) {
                    scope(d).getSelectionModel().select(i); assertEquals(!allowed, next(d).isDisabled());
                    assertTrue(status(d).contains(allowed ? "匹配 1 处" : "最多 1024"));
                }
                query(d).setText("xxx"); next(d).fire(); assertSelection(error(d), 0, 3);
                query(d).clear(); assertTrue(next(d).isDisabled()); assertTrue(status(d).startsWith("输入查找文本"));
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void boundedSnapshotExcludesTailAndMatchLimitIsExplicit(int target) throws Exception {
        FxUiTestSupport.call(() -> {
            String large = "x".repeat(16384) + "TAIL_ONLY";
            var d = dialog(large, QueryResult.error(large, 1));
            try {
                d.show(); scope(d).getSelectionModel().select(target); query(d).setText("TAIL_ONLY"); assertTrue(next(d).isDisabled());
                assertTrue(((Label) d.getDialogPane().lookup("#sql-script-detail-" + (target == 0 ? "sql" : "error") + "-label")).getText().contains("达到显示上限"));
                query(d).setText("x"); assertTrue(status(d).contains("仅定位前 10000 处"));
                TextArea area = target == 0 ? sql(d) : error(d); area.positionCaret(16384); previous(d).fire();
                assertSelection(area, 9999, 10000); next(d).fire(); assertSelection(area, 0, 1);
                assertEquals(16384, area.getLength());
            } finally { d.close(); }
            return null;
        });
    }

    @Test void literalWhitespaceUnicodeAndNormalizedNewlinesUseShownOffsets() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog(".* 中😀\r\n  end", QueryResult.error("other", 1));
            try {
                d.show(); query(d).setText(".*"); next(d).fire(); assertSelection(sql(d), 0, 2);
                query(d).setText("中😀"); next(d).fire(); assertSelection(sql(d), 3, 6);
                query(d).setText("  "); next(d).fire(); assertEquals("  ", sql(d).getSelectedText());
                assertEquals(sql(d).getText().indexOf("  "), sql(d).getSelection().getStart());
                scope(d).getSelectionModel().select(1); assertTrue(next(d).isDisabled()); assertEquals("", error(d).getSelectedText());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"escape", "close", "owner"})
    void hiddenCleanupSurvivesOwnerCallbackAndRejectsOldActions(String action) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("old old", QueryResult.error("old old", 1)); var hidden = new AtomicBoolean();
            d.setOnHidden(event -> hidden.set(true));
            try {
                d.show(); query(d).setText("old"); scope(d).getSelectionModel().select(1); next(d).fire();
                if (action.equals("escape")) query(d).fireEvent(key(KeyCode.ESCAPE, false, false));
                else if (action.equals("close")) close(d).fire(); else d.close();
                assertFalse(d.isShowing()); assertTrue(hidden.get()); String oldStatus = status(d);
                query(d).setText("new"); sql(d).setText("new new"); error(d).setText("new new");
                scope(d).getSelectionModel().select(0); matchCase(d).fire(); next(d).getOnAction().handle(new javafx.event.ActionEvent());
                assertTrue(next(d).isDisabled()); assertTrue(scope(d).isDisabled()); assertEquals(oldStatus, status(d));
                assertEquals("", sql(d).getSelectedText()); assertEquals("", error(d).getSelectedText());
            } finally { d.close(); }
            var fresh = dialog("old old", QueryResult.error("old old", 1));
            try { fresh.show(); assertEquals("", query(fresh).getText()); assertEquals(0, scope(fresh).getSelectionModel().getSelectedIndex()); }
            finally { fresh.close(); }
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"480,dark", "480,light", "880,dark", "880,light"})
    void controlsAndBodiesFitNarrowAndWideThemes(int width, String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("select a", QueryResult.error("diagnostic", 1));
            try {
                d.getDialogPane().getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                d.show(); d.setWidth(width); d.setHeight(700); var pane = d.getDialogPane(); pane.applyCss(); pane.layout();
                for (String id : List.of("sql-script-find-scope", "sql-script-find-query", "sql-script-find-next", "sql-script-find-previous", "sql-script-find-status", "sql-script-detail-sql", "sql-script-detail-error")) {
                    var node = (Region) pane.lookup("#" + id); assertNotNull(node, id); var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= pane.getScene().getWidth() + 1, id);
                    assertTrue(node.getHeight() + 1 >= node.minHeight(node.getWidth()), id);
                    if (node instanceof Button) assertTrue(node.getWidth() + 1 >= node.prefWidth(-1), id);
                }
                assertTrue(sql(d).getHeight() >= 70); assertTrue(error(d).getHeight() >= 70);
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void nonFocusedMatchesAndFocusedEmptyPromptRemainVisible(String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("alpha", QueryResult.error("alpha", 1));
            try {
                d.getDialogPane().getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                d.show(); query(d).requestFocus(); d.getDialogPane().applyCss();
                assertTrue(((javafx.scene.paint.Color) cssValue(query(d), "-fx-prompt-text-fill")).getOpacity() > 0);
                query(d).setText("alpha");
                for (int i = 0; i < 2; i++) {
                    scope(d).getSelectionModel().select(i); next(d).fire(); d.getDialogPane().applyCss();
                    var body = i == 0 ? sql(d) : error(d);
                    assertSame(query(d), d.getDialogPane().getScene().getFocusOwner()); assertEquals("alpha", body.getSelectedText());
                    assertEquals(javafx.scene.paint.Color.web("#6C5CE7"), cssValue(body, "-fx-highlight-fill"));
                    assertEquals(javafx.scene.paint.Color.WHITE, cssValue(body, "-fx-highlight-text-fill"));
                }
            } finally { d.close(); }
            return null;
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object cssValue(Control node, String name) {
        javafx.css.CssMetaData data = node.getCssMetaData().stream().filter(item -> item.getProperty().equals(name)).findFirst().orElseThrow();
        return data.getStyleableProperty(node).getValue();
    }

    static SqlScriptDetailsDialog dialog(String sql, QueryResult result) {
        return new SqlScriptDetailsDialog(null, SqlScriptExecutionReport.capture(List.of(new ScriptOutcome(17, sql, result)), 5).entries().getFirst());
    }
    static TextField query(SqlScriptDetailsDialog d) { var node = (TextField) d.getDialogPane().lookup("#sql-script-find-query"); assertNotNull(node, "Execution details need local search"); return node; }
    static ComboBox<?> scope(SqlScriptDetailsDialog d) { return (ComboBox<?>) d.getDialogPane().lookup("#sql-script-find-scope"); }
    static TextArea sql(SqlScriptDetailsDialog d) { return (TextArea) d.getDialogPane().lookup("#sql-script-detail-sql"); }
    static TextArea error(SqlScriptDetailsDialog d) { return (TextArea) d.getDialogPane().lookup("#sql-script-detail-error"); }
    static Button next(SqlScriptDetailsDialog d) { return (Button) d.getDialogPane().lookup("#sql-script-find-next"); }
    static Button previous(SqlScriptDetailsDialog d) { return (Button) d.getDialogPane().lookup("#sql-script-find-previous"); }
    static Button close(SqlScriptDetailsDialog d) { return (Button) d.getDialogPane().lookup("#sql-script-detail-close"); }
    static CheckBox matchCase(SqlScriptDetailsDialog d) { return (CheckBox) d.getDialogPane().lookup("#sql-script-find-case"); }
    static String status(SqlScriptDetailsDialog d) { return ((Label) d.getDialogPane().lookup("#sql-script-find-status")).getText(); }
    static void assertSelection(TextArea area, int start, int end) { assertEquals(start, area.getSelection().getStart()); assertEquals(end, area.getSelection().getEnd()); }
    private static KeyEvent key(KeyCode code, boolean shift, boolean control) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false); }
}
