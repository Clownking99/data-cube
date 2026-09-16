package com.datacube.fx;

import com.datacube.config.DraftManagementProbe;
import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.spi.model.DbType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftFilterTest {
    @Test void filterEntryExistsWithoutRestoringOrWriting() throws Exception {
        var probe = new DraftManagementProbe();
        var runtime = FxUiTestSupport.call(() -> probe.create(Platform::runLater, Platform::isFxApplicationThread));
        var pane = FxUiTestSupport.call(() -> {
            var value = new SqlDraftManagerPane(runtime, draft -> { throw new AssertionError("must not restore"); }, () -> {});
            new Scene(value.getNode()); value.getNode().applyCss(); value.getNode().layout(); return value;
        });
        try {
            FxUiTestSupport.call(() -> {
                TextField filter = (TextField) pane.getNode().lookup("#draft-manager-filter");
                assertNotNull(filter, "Draft manager needs a summary filter");
                assertTrue(filter.getPromptText().contains("SQL 摘要"));
                assertEquals(0, probe.prunes); assertEquals(0, probe.deletions); assertEquals(0, probe.clears);
                return null;
            });
        } finally {
            FxUiTestSupport.call(() -> { pane.close(); runtime.shutdown(); return null; }); probe.drain();
        }
    }

    @ParameterizedTest @CsvSource({"PG LAB,0", "tax,1", "金额.*,0", "ORACLE,1", "未绑定连接,2", "空草稿,2"})
    void searchesDisplayedFieldsLiterallyWithoutStorageWork(String term, int selected) throws Exception {
        try (var f = new Fixture(true, true)) {
            int prunes = f.probe.prunes;
            f.fx(() -> {
                assertEquals(seed(), List.copyOf(f.list().getItems()));
                f.filter().setText(" " + term + " ");
                assertEquals(List.of(seed().get(selected)), List.copyOf(f.list().getItems()));
                assertEquals("显示 1 / 3 份草稿", f.label("matches").getText());
                assertTrue(f.label("status").getText().contains("共 3 份"));
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertTrue(f.button("restore").isDisabled());
                f.button("clear-filter").fire();
                assertEquals(seed(), List.copyOf(f.list().getItems())); assertEquals("显示 3 / 3 份草稿", f.label("matches").getText());
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertTrue(f.button("clear-filter").isDisabled());
            });
            assertEquals(prunes, f.probe.prunes); assertEquals(seed(), f.probe.records); f.assertNoMutation();
        }
    }

    @ParameterizedTest @CsvSource({"sql,119,true", "sql,120,false", "name,79,true", "name,80,false", "schema,79,true", "schema,80,false"})
    void onlySummaryPrefixIsSearchable(String field, int offset, boolean matches) throws Exception {
        try (var f = new Fixture(true, true)) {
            String content = "x".repeat(offset) + "Z" + "x".repeat(500);
            SqlDraft draft = new SqlDraft(PG_ID, 100_000L, "pg-id", DbType.POSTGRESQL,
                    field.equals("name") ? content : "name", field.equals("schema") ? content : "schema",
                    field.equals("sql") ? content : "select 1");
            f.replace(List.of(draft));
            f.fx(() -> {
                f.filter().setText("Z");
                assertEquals(matches ? List.of(draft) : List.of(), List.copyOf(f.list().getItems()));
                assertTrue(f.label("filter-hint").getText().contains("SQL 前 120"));
                assertTrue(f.label("filter-hint").getText().contains("不搜索后续正文"));
                assertEquals(content, field.equals("sql") ? draft.sql() : field.equals("name") ? draft.connectionName() : draft.schema());
            });
            f.assertNoMutation();
        }
    }

    @Test void hiddenMetadataAndIdentifiersAreNotSearchableAndNoMatchDiffersFromEmpty() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> {
                for (String term : List.of(PG_ID.toString(), "pg-id", "private-offline-name", "not_present")) {
                    f.filter().setText(term); assertTrue(f.list().getItems().isEmpty());
                    assertTrue(((Label) f.list().getPlaceholder()).getText().contains("没有匹配"));
                    assertEquals("显示 0 / 3 份草稿", f.label("matches").getText());
                }
            });
            f.replace(List.of());
            f.fx(() -> { assertEquals("没有可恢复草稿", ((Label) f.list().getPlaceholder()).getText());
                assertEquals("显示 0 / 0 份草稿", f.label("matches").getText()); });
        }
    }

    @Test void retainedSelectionKeepsPreviewRangeAndExcludedSelectionCannotRestoreAnotherDraft() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().selectFirst(); SqlDraft chosen = f.list().getSelectionModel().getSelectedItem();
                f.sql().selectRange(2, 8); String text = f.sql().getText();
                f.filter().setText("金额.*");
                assertSame(chosen, f.list().getSelectionModel().getSelectedItem()); assertEquals(text, f.sql().getText());
                assertEquals(2, f.sql().getAnchor()); assertEquals(8, f.sql().getCaretPosition());
                f.filter().setText("salary");
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertEquals("", f.sql().getText());
                assertTrue(f.button("restore").isDisabled()); assertTrue(f.button("delete").isDisabled());
                f.filter().fireEvent(key(KeyCode.ENTER)); f.button("restore").fire();
                f.button("clear-filter").fire(); assertNull(f.list().getSelectionModel().getSelectedItem());
            });
            f.assertNoMutation();
        }
    }

    @Test void refreshRetainsUuidWithLatestTextButDoesNotReplaceExcludedRecordByMatchingName() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> { f.list().getSelectionModel().selectFirst(); f.filter().setText("pg lab"); });
            SqlDraft updated = new SqlDraft(PG_ID, 100_001L, "pg-id", DbType.POSTGRESQL, "PG Lab", "ledger", "new SQL\r\nexact");
            SqlDraft other = new SqlDraft(UUID.randomUUID(), 100_002L, "pg-id", DbType.POSTGRESQL, "PG Lab", "ledger", "other SQL");
            f.replace(List.of(other, updated));
            f.fx(() -> {
                assertSame(updated, f.list().getSelectionModel().getSelectedItem());
                assertEquals("new SQL\nexact", f.sql().getText()); assertEquals("pg lab", f.filter().getText());
            });
            f.replace(List.of(other));
            f.fx(() -> {
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertEquals("", f.sql().getText());
                assertTrue(f.button("restore").isDisabled()); assertEquals(List.of(other), List.copyOf(f.list().getItems()));
            });
        }
    }

    @Test void initializingAndPendingRefreshUseLatestFilterWithoutRestoreAdmission() throws Exception {
        try (var f = new Fixture(false, true)) {
            f.fx(() -> {
                f.filter().setText("PG"); assertEquals("尚未加载草稿", f.label("matches").getText());
                f.filter().fireEvent(key(KeyCode.ENTER));
            });
            f.ready();
            f.fx(() -> {
                assertEquals(List.of(seed().getFirst()), List.copyOf(f.list().getItems()));
                f.list().getSelectionModel().selectFirst(); f.runtime.refresh();
                f.filter().setText("tax"); f.filter().fireEvent(key(KeyCode.DOWN)); f.filter().fireEvent(key(KeyCode.ENTER));
                assertTrue(f.runtime.managementPending()); assertTrue(f.restored.isEmpty());
            });
            f.settle();
            f.fx(() -> { assertEquals(List.of(seed().get(1)), List.copyOf(f.list().getItems()));
                assertEquals("tax", f.filter().getText()); assertEquals(ORA_ID, f.list().getSelectionModel().getSelectedItem().id()); });
            f.assertNoMutation();
        }
    }

    @Test void unavailableStorageStillFiltersAndRestoresReadSnapshot() throws Exception {
        try (var f = new Fixture(true, false)) {
            f.fx(() -> {
                f.filter().setText("salary"); f.filter().fireEvent(key(KeyCode.DOWN));
                assertFalse(f.button("restore").isDisabled()); assertTrue(f.button("delete").isDisabled());
                assertTrue(f.button("clear").isDisabled()); assertTrue(f.button("refresh").isDisabled());
                f.filter().fireEvent(key(KeyCode.ENTER));
            });
            assertEquals(List.of(seed().get(1)), f.restored);
            assertEquals(0, f.probe.clears); assertEquals(0, f.probe.deletions);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"closed", "disabled"})
    void closedOrDisabledPaneRejectsFilterAndKeyboardActions(String state) throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> {
                f.list().getSelectionModel().selectFirst();
                if (state.equals("closed")) f.pane.close(); else f.pane.getNode().setDisable(true);
                f.filter().setText("salary"); f.filter().fireEvent(key(KeyCode.ENTER)); f.list().fireEvent(key(KeyCode.ENTER));
                f.button("restore").fire();
                assertEquals("", f.filter().getText()); assertEquals(seed(), List.copyOf(f.list().getItems()));
            });
            f.assertNoMutation();
        }
    }

    @Test void queryLimitRejectsWholeEditAndClearRestoresSnapshot() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> {
                f.filter().setText("x".repeat(256)); assertEquals(256, f.filter().getLength());
                f.filter().setText("y".repeat(257)); assertEquals("x".repeat(256), f.filter().getText());
                assertTrue(f.label("matches").getText().contains("超长输入未应用"));
                f.button("clear-filter").fire(); assertEquals("显示 3 / 3 份草稿", f.label("matches").getText());
                assertEquals(seed(), List.copyOf(f.list().getItems()));
            });
        }
    }

    @Test void keyboardRequiresExplicitSelectionAndPreviewEnterNeverRestores() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> {
                f.filter().setText("SALARY");
                f.sql().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false));
                assertSame(f.filter(), f.pane.getNode().getScene().getFocusOwner()); assertEquals("SALARY", f.filter().getSelectedText());
                f.filter().fireEvent(key(KeyCode.ENTER)); assertTrue(f.restored.isEmpty());
                f.filter().fireEvent(key(KeyCode.DOWN)); assertEquals(ORA_ID, f.list().getSelectionModel().getSelectedItem().id());
                f.sql().fireEvent(key(KeyCode.ENTER)); assertTrue(f.restored.isEmpty());
                f.list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, true, false, false, false));
                assertTrue(f.restored.isEmpty());
                f.list().fireEvent(key(KeyCode.ENTER)); assertEquals(List.of(seed().get(1)), f.restored);
                assertFalse(f.button("restore").isDefaultButton());
            });
        }
    }

    @Test void deleteWithFilterOnlyRemovesExplicitUuid() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> {
                f.filter().setText("salary"); f.list().getSelectionModel().selectFirst();
                SqlDraftManagerTest.respondToDialog(() -> f.button("delete").fire(), SqlDraftManagerTest::confirmDialog);
            });
            f.settle();
            assertEquals(List.of(seed().get(0), seed().get(2)), f.probe.records);
            assertEquals(1, f.probe.deletions); assertEquals(0, f.probe.clears);
            f.fx(() -> { assertEquals("显示 0 / 2 份草稿", f.label("matches").getText()); assertTrue(f.sql().getText().isEmpty()); });
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void clearAllDisclosesHiddenRecordsAndKeepsCancelDefault(boolean confirm) throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> {
                f.filter().setText("salary"); assertEquals("清空全部草稿", f.button("clear").getText());
                SqlDraftManagerTest.respondToDialog(() -> f.button("clear").fire(), dialog -> {
                    assertTrue(dialog.getHeaderText().contains("全部可恢复草稿"));
                    assertTrue(dialog.getHeaderText().contains("被筛选隐藏"));
                    assertTrue(((Button) dialog.lookupButton(ButtonType.CANCEL)).isDefaultButton());
                    if (confirm) SqlDraftManagerTest.confirmDialog(dialog);
                });
            });
            f.settle(); assertEquals(confirm ? List.of() : seed(), f.probe.records);
            assertEquals(confirm ? 1 : 0, f.probe.clears); assertEquals(0, f.probe.deletions);
        }
    }

    @Test void partialFailureNoticeSurvivesLocalFiltering() throws Exception {
        try (var f = new Fixture(true, true)) {
            f.probe.partialClear = true;
            f.fx(() -> SqlDraftManagerTest.respondToDialog(() -> f.button("clear").fire(), SqlDraftManagerTest::confirmDialog));
            f.settle();
            f.fx(() -> {
                String warning = f.label("notice").getText(); assertTrue(warning.contains("部分"));
                f.filter().setText("absent"); f.button("clear-filter").fire();
                assertEquals(warning, f.label("notice").getText()); assertEquals("显示 2 / 2 份草稿", f.label("matches").getText());
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void narrowThemesKeepFocusedSearchPromptAndScopeReadable(String theme) throws Exception {
        try (var f = new Fixture(true, true)) {
            f.fx(() -> {
                var scene = f.pane.getNode().getScene();
                scene.getStylesheets().addAll(getClass().getResource("theme-base.css").toExternalForm(),
                        getClass().getResource("theme-" + theme + ".css").toExternalForm());
                Region root = (Region) f.pane.getNode(); root.resize(680, 600); f.filter().requestFocus();
                // A Scene without a shown Window cannot acquire native focus; exercise its focused CSS state explicitly.
                f.filter().pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
                root.applyCss(); root.layout();
                for (String id : List.of("filter", "clear-filter", "matches", "filter-hint", "restore", "clear")) {
                    var node = root.lookup("#draft-manager-" + id); var bounds = node.localToScene(node.getBoundsInLocal());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= 680, id + ": " + bounds);
                    assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= 600, id + ": " + bounds);
                }
                Text prompt = (Text) f.filter().lookup(".text"); assertEquals(f.filter().getPromptText(), prompt.getText());
                assertTrue(((Color) prompt.getFill()).getOpacity() > 0.9, "focused query needs visible scope hint");
                var hint = f.label("filter-hint"); assertTrue(hint.getHeight() >= hint.prefHeight(hint.getWidth()) - 1);
            });
        }
    }

    private static final UUID PG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORA_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static List<SqlDraft> seed() {
        return List.of(new SqlDraft(PG_ID, 100_000L, "pg-id", DbType.POSTGRESQL, "PG Lab", "ledger", "select 金额.* from invoice;\r\n-- raw"),
                new SqlDraft(ORA_ID, 90_000L, "ora-id", DbType.ORACLE, "Payroll", "Tax", "select salary from payroll;"),
                new SqlDraft(UUID.fromString("00000000-0000-0000-0000-000000000003"), 80_000L, null, null, "private-offline-name", null, ""));
    }
    private static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false); }
    private static final class Fixture implements AutoCloseable {
        final DraftManagementProbe probe = new DraftManagementProbe();
        final List<SqlDraft> restored = new ArrayList<>();
        final SqlDraftCoordinator runtime;
        final SqlDraftManagerPane pane;
        Fixture(boolean ready, boolean writable) throws Exception {
            probe.writable = writable; probe.records.addAll(seed());
            runtime = call(() -> probe.create(Platform::runLater, Platform::isFxApplicationThread));
            pane = call(() -> {
                var value = new SqlDraftManagerPane(runtime, draft -> { restored.add(draft); return false; }, () -> {});
                new Scene(value.getNode()); value.getNode().applyCss(); value.getNode().layout(); return value;
            });
            if (ready) ready();
        }
        void ready() throws Exception { settle(); settle(); }
        void settle() throws Exception { probe.drain(); fx(pane::refreshView); fx(() -> {}); }
        void replace(List<SqlDraft> values) throws Exception { probe.records.clear(); probe.records.addAll(values); fx(() -> button("refresh").fire()); settle(); }
        TextField filter() { return (TextField) pane.getNode().lookup("#draft-manager-filter"); }
        @SuppressWarnings("unchecked") ListView<SqlDraft> list() { return (ListView<SqlDraft>) pane.getNode().lookup("#draft-manager-list"); }
        TextArea sql() { return (TextArea) pane.getNode().lookup("#draft-manager-sql"); }
        Label label(String id) { return (Label) pane.getNode().lookup("#draft-manager-" + id); }
        Button button(String id) { return (Button) pane.getNode().lookup("#draft-manager-" + id); }
        <T> T call(Callable<T> action) throws Exception { return FxUiTestSupport.call(action); }
        void fx(Runnable action) throws Exception { call(() -> { action.run(); return null; }); }
        void assertNoMutation() { assertTrue(restored.isEmpty()); assertEquals(0, probe.deletions); assertEquals(0, probe.clears); assertTrue(probe.writes.isEmpty()); }
        @Override public void close() throws Exception { fx(pane::close); var shutdown = call(runtime::shutdown); probe.drain(); shutdown.get(5, TimeUnit.SECONDS); }
    }
}
