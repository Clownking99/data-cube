package com.datacube.fx;

import com.datacube.service.DraftConnectionProbe;
import com.datacube.spi.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaObjectCopyTest {
    @Test void offersExplicitCopyWithoutDefaultSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var picker = new SchemaObjectSearchDialog("synthetic", "s", null, List::of,
                    (work, ok, fail) -> { throw new AssertionError("no loading on construction"); }, () -> {}, () -> true)) {
                var pane = picker.dialog().getDialogPane(); pane.resize(480, 610); pane.applyCss(); pane.layout();
                Button copy = (Button) pane.lookup("#schema-object-copy");
                assertNotNull(copy, "Object finder needs a copy action for existing SQL");
                assertTrue(copy.isDisabled()); assertFalse(copy.isDefaultButton());
                assertNull(picker.dialog().getResult());
            }
            return null;
        });
    }

    @ParameterizedTest @EnumSource(value = DbType.class, names = {"POSTGRESQL", "ORACLE"})
    void copyKeepsShownDialogAndExactSelectionThenSelectStillConfirms(DbType type) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(type)) {
                f.picker.dialog().show(); f.publish();
                f.query().setText(" ORDER "); f.list().getSelectionModel().selectFirst();
                TableInfo chosen = f.list().getSelectionModel().getSelectedItem();
                String preview = f.preview().getText();
                assertTrue(f.writes.isEmpty(), "loading, typing and choosing must not copy");
                f.copy().fire();
                String expected = "\" Sales\"\" \".\" Order\"\"" + ".中😀".repeat(50) + " \"";
                assertEquals(List.of(expected), f.writes, "copy must use full identity, not the truncated cell label");
                assertEquals("已复制限定名称；粘贴前请确认目标连接。", f.feedback().getText());
                assertTrue(f.picker.dialog().isShowing()); assertNull(f.picker.dialog().getResult());
                assertSame(chosen, f.list().getSelectionModel().getSelectedItem());
                assertEquals(" ORDER ", f.query().getText()); assertEquals(preview, f.preview().getText());
                assertFalse(f.clipboard.getNode().isManaged(), "feedback belongs in the dialog, not the tree");
                assertEquals(1, f.loads); assertTrue(f.copy().getTooltip().getText().contains("其他应用"));
                f.copy().requestFocus(); f.copy().fireEvent(key(KeyCode.ENTER));
                assertEquals(1, f.writes.size()); assertNull(f.picker.dialog().getResult());
                f.query().fireEvent(key(KeyCode.ENTER));
                assertEquals(chosen.ref(), f.picker.dialog().getResult()); assertFalse(f.picker.dialog().isShowing());
                ConnectionTreeClipboardTest.assertOffline(f.probe);
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"false", "exception"})
    void failedCopyReplacesSuccessAndRetriesWithoutReloadingOrLeakingDetails(String mode) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(DbType.POSTGRESQL)) {
                f.loadAndSelect(); f.copy().fire(); f.mode = mode; f.copy().fire();
                assertEquals("复制失败：无法写入系统剪贴板，请重试。", f.feedback().getText());
                assertTrue(f.feedback().getStyle().contains("-status-error"));
                assertFalse(f.copy().isDisabled()); assertNull(f.picker.dialog().getResult());
                f.mode = "success"; f.copy().fire();
                assertEquals(3, f.writes.size()); assertEquals(f.writes.getFirst(), f.writes.getLast());
                assertTrue(f.feedback().getText().startsWith("已复制"));
                assertFalse(f.feedback().getStyle().contains("-status-error")); assertEquals(1, f.loads);
                ConnectionTreeClipboardTest.assertOffline(f.probe);
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"initial", "loading", "failure", "empty", "unselected", "no-match", "reload", "source", "closed", "disabled"})
    void unusableCandidatesNeverInvokeCopy(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(DbType.POSTGRESQL)) {
                if (!state.equals("initial")) {
                    if (state.equals("empty")) f.names = List.of();
                    f.picker.reload();
                    if (state.equals("failure")) f.failure.accept(new java.sql.SQLException("private"));
                    else if (!state.equals("loading")) f.publish();
                }
                if (List.of("reload", "source", "closed", "disabled", "no-match").contains(state)) {
                    f.list().getSelectionModel().selectFirst();
                    switch (state) {
                        case "reload" -> f.picker.reload();
                        case "source" -> f.allowed.set(false); // Button still enabled: action must recheck.
                        case "closed" -> f.picker.close();
                        case "disabled" -> f.picker.dialog().getDialogPane().setDisable(true);
                        default -> f.query().setText("absent");
                    }
                }
                f.copy().fire();
                assertEquals(0, f.copyCalls); assertTrue(f.writes.isEmpty()); assertNull(f.picker.dialog().getResult());
                ConnectionTreeClipboardTest.assertOffline(f.probe);
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"selection", "filter", "reload", "source"})
    void candidateChangesClearOldFeedbackWithoutWritingAgain(String change) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(DbType.ORACLE)) {
                f.loadAndSelect(); f.copy().fire(); assertTrue(f.feedback().isManaged());
                switch (change) {
                    case "selection" -> f.list().getSelectionModel().selectLast();
                    case "filter" -> f.query().setText("ORDER");
                    case "reload" -> f.picker.reload();
                    default -> { f.allowed.set(false); f.picker.sourceChanged(); }
                }
                assertEquals("", f.feedback().getText()); assertFalse(f.feedback().isManaged());
                assertFalse(f.feedback().isVisible()); assertEquals(1, f.writes.size());
                assertNull(f.picker.dialog().getResult());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"removed", "changed", "invalid-name"})
    void clipboardTargetAndNameAreValidatedBeforeWriting(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(DbType.POSTGRESQL)) {
                if (state.equals("invalid-name")) f.names = List.of(new TableInfo(Fixture.SCHEMA, "bad\nname", TableInfo.Kind.TABLE, null));
                f.loadAndSelect();
                if (state.equals("removed")) f.probe.manager.unregister(f.connection.id());
                if (state.equals("changed")) f.probe.manager.register(new ConnConfig("source", "synthetic", DbType.ORACLE,
                        "other.invalid", 1, "synthetic", "", "", Map.of()));
                f.copy().fire(); assertTrue(f.writes.isEmpty());
                assertTrue(f.feedback().getText().startsWith("无法复制："));
                assertTrue(f.feedback().getStyle().contains("-status-error"));
                assertNull(f.picker.dialog().getResult()); ConnectionTreeClipboardTest.assertOffline(f.probe);
            }
            return null;
        });
    }

    @Test void cancelAfterCopyDoesNotConfirmOrUndoTheExplicitWrite() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(DbType.POSTGRESQL)) {
                f.picker.dialog().show(); f.publish(); f.list().getSelectionModel().selectFirst(); f.copy().fire();
                f.query().fireEvent(key(KeyCode.ESCAPE));
                assertFalse(f.picker.dialog().isShowing()); assertNull(f.picker.dialog().getResult());
                f.copy().fire(); assertEquals(1, f.writes.size());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void narrowThemesKeepCopyFeedbackAndConfirmationWithinViewport(String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(DbType.POSTGRESQL)) {
                var pane = f.picker.dialog().getDialogPane();
                pane.getStylesheets().addAll(getClass().getResource("theme-base.css").toExternalForm(),
                        getClass().getResource("theme-" + theme + ".css").toExternalForm());
                f.loadAndSelect(); f.mode = "false"; f.copy().fire();
                pane.resize(480, 548); pane.applyCss(); pane.layout();
                for (String id : List.of("copy", "copy-status", "reload", "confirm", "preview", "query")) {
                    var node = pane.lookup("#schema-object-" + id); var bounds = node.localToScene(node.getBoundsInLocal());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= 480, id + ": " + bounds);
                    assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= 548, id + ": " + bounds);
                }
                for (String id : List.of("target", "preview")) {
                    TextArea area = (TextArea) pane.lookup("#schema-object-" + id);
                    assertTrue(area.getHeight() >= area.prefHeight(area.getWidth()) - 1,
                            id + " must retain its configured visible rows when copy feedback appears");
                }
                assertTrue(f.feedback().isVisible()); assertFalse(f.copy().isDisabled());
                assertFalse(f.copy().isDefaultButton()); assertTrue(f.feedback().getText().contains("请重试"));
            }
            return null;
        });
    }

    private static KeyEvent key(KeyCode key) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false); }
    private static final class Fixture implements AutoCloseable {
        static final String SCHEMA = " Sales\" ";
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final List<String> writes = new ArrayList<>();
        final ConnConfig connection;
        final ConnectionTreeClipboard clipboard;
        final SchemaObjectSearchDialog picker;
        List<TableInfo> names;
        Callable<List<TableInfo>> work;
        Consumer<List<TableInfo>> success;
        Consumer<Throwable> failure;
        int loads, copyCalls;
        String mode = "success";
        Fixture(DbType type) {
            connection = new ConnConfig("source", "synthetic", type, "example.invalid", 1, "synthetic", "", "", Map.of());
            probe.manager.register(connection);
            clipboard = new ConnectionTreeClipboard(probe.manager, text -> {
                writes.add(text);
                if (mode.equals("exception")) throw new IllegalStateException("PRIVATE backend diagnostic");
                return !mode.equals("false");
            });
            names = List.of(new TableInfo(SCHEMA, " Order\"" + ".中😀".repeat(50) + " ",
                    type == DbType.POSTGRESQL ? TableInfo.Kind.TABLE : TableInfo.Kind.VIEW, null),
                    new TableInfo(SCHEMA, "other", TableInfo.Kind.TABLE, null));
            picker = new SchemaObjectSearchDialog(connection.name(), SCHEMA, null, () -> { loads++; return names; },
                    (task, ok, fail) -> { work = task; success = ok; failure = fail; return new CompletableFuture<>(); },
                    () -> {}, allowed::get);
            picker.installCopyAction(ref -> { copyCalls++; return clipboard.copyResult(connection, ref); });
            var pane = picker.dialog().getDialogPane(); pane.resize(640, 610); pane.applyCss(); pane.layout();
        }
        void publish() throws Exception { success.accept(work.call()); }
        void loadAndSelect() throws Exception { picker.reload(); publish(); list().getSelectionModel().selectFirst(); }
        Button copy() { return (Button) picker.dialog().getDialogPane().lookup("#schema-object-copy"); }
        Label feedback() { return (Label) picker.dialog().getDialogPane().lookup("#schema-object-copy-status"); }
        TextField query() { return (TextField) picker.dialog().getDialogPane().lookup("#schema-object-query"); }
        TextArea preview() { return (TextArea) picker.dialog().getDialogPane().lookup("#schema-object-preview"); }
        @SuppressWarnings("unchecked") ListView<TableInfo> list() { return (ListView<TableInfo>) picker.dialog().getDialogPane().lookup("#schema-object-list"); }
        @Override public void close() { picker.dialog().close(); picker.close(); }
    }
}
