package com.datacube.fx;

import com.datacube.spi.model.TableInfo;
import com.datacube.spi.model.TableRef;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaObjectKindFilterTest {
    @Test void offersAllTablesAndViewsWithoutStartingWork() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var picker = new SchemaObjectSearchDialog("synthetic", "s", null, List::of,
                    (work, ok, fail) -> { throw new AssertionError("construction must stay offline"); },
                    () -> {}, () -> true)) {
                var pane = picker.dialog().getDialogPane(); pane.applyCss(); pane.layout();
                ChoiceBox<?> kind = (ChoiceBox<?>) pane.lookup("#schema-object-kind");
                assertNotNull(kind, "Object search needs a type filter");
                assertEquals(List.of("全部", "仅表", "仅视图"), kind.getItems().stream().map(Object::toString).toList());
                assertEquals("全部", kind.getValue().toString()); assertNull(picker.dialog().getResult());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void nameAndTypeIntersectWithoutLoadingCopyingOrConfirming(int type) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.query().setText(" SAME "); f.kind().getSelectionModel().select(type);
                List<TableInfo> expected = type == 0 ? seed().subList(0, 2) : List.of(seed().get(type - 1));
                assertEquals(expected, List.copyOf(f.list().getItems()));
                assertEquals("匹配 " + expected.size() + " / 4 个对象", f.status());
                assertNull(f.list().getSelectionModel().getSelectedItem());
                assertTrue(f.button("confirm").isDisabled()); assertTrue(f.button("copy").isDisabled());
                assertEquals(1, f.loads); assertEquals(1, f.jobs.size()); assertTrue(f.copies.isEmpty());
                assertNull(f.picker.dialog().getResult());
                f.query().setText(".* 中😀"); f.kind().getSelectionModel().select(1);
                assertEquals(List.of(seed().getLast()), List.copyOf(f.list().getItems()), "name matching stays literal");
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void preservesExactCandidateOrClearsItWithoutReplacingSameNamedOtherType(int selectedType) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.list().getSelectionModel().select(selectedType - 1);
                TableInfo chosen = f.list().getSelectionModel().getSelectedItem();
                f.button("copy").fire(); assertTrue(f.feedback().isVisible());
                String preview = f.preview().getText();
                f.kind().getSelectionModel().select(selectedType);
                assertSame(chosen, f.list().getSelectionModel().getSelectedItem()); assertEquals(preview, f.preview().getText());
                assertFalse(f.feedback().isVisible()); assertEquals("", f.feedback().getText());
                assertFalse(f.button("copy").isDisabled()); assertEquals(1, f.copies.size());
                f.kind().getSelectionModel().select(3 - selectedType);
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertEquals("", f.preview().getText());
                assertTrue(f.button("copy").isDisabled()); assertTrue(f.button("confirm").isDisabled());
                f.button("copy").fire(); f.button("confirm").fire();
                assertEquals(1, f.copies.size()); assertNull(f.picker.dialog().getResult());
                f.kind().getSelectionModel().select(0); assertNull(f.list().getSelectionModel().getSelectedItem());
                assertEquals(1, f.loads);
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"name", "type", "both"})
    void clearResetsBothFiltersWithoutLoadingOrSelecting(String mode) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); assertTrue(f.button("clear").isDisabled());
                if (!mode.equals("type")) f.query().setText("absent");
                if (!mode.equals("name")) f.kind().getSelectionModel().select(2);
                assertFalse(f.button("clear").isDisabled()); f.button("clear").fire();
                assertEquals("", f.query().getText()); assertEquals("全部", f.kind().getValue().toString());
                assertEquals(seed(), List.copyOf(f.list().getItems())); assertEquals("匹配 4 / 4 个对象", f.status());
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertTrue(f.button("clear").isDisabled());
                assertSame(f.query(), f.picker.dialog().getDialogPane().getScene().getFocusOwner());
                assertEquals(1, f.jobs.size()); assertEquals(1, f.loads); assertTrue(f.copies.isEmpty());
            }
            return null;
        });
    }

    @Test void pendingLoadAndRetryUseLatestFiltersButNeverOldSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.picker.reload(); f.query().setText("SAME"); f.kind().getSelectionModel().select(2);
                assertFalse(f.kind().isDisabled()); assertEquals(1, f.jobs.size()); assertEquals(0, f.loads);
                f.jobs.getLast().publish(); assertEquals(List.of(seed().get(1)), List.copyOf(f.list().getItems()));
                f.list().getSelectionModel().selectFirst(); f.button("copy").fire();
                f.button("reload").fire(); Job old = f.jobs.getLast();
                assertTrue(f.list().getItems().isEmpty()); assertFalse(f.feedback().isVisible());
                old.failure.accept(new IllegalStateException("PRIVATE"));
                assertTrue(f.status().contains("无法读取"));
                f.kind().getSelectionModel().select(1); assertTrue(f.status().contains("无法读取"));
                f.button("reload").fire(); f.jobs.getLast().publish();
                old.success.accept(List.of(item("late", TableInfo.Kind.VIEW)));
                assertEquals(List.of(seed().getFirst()), List.copyOf(f.list().getItems()));
                assertEquals("SAME", f.query().getText()); assertEquals("仅表", f.kind().getValue().toString());
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertTrue(f.button("copy").isDisabled());
                assertEquals(3, f.jobs.size()); assertEquals(2, f.loads); assertEquals(1, f.copies.size());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {199, 200, 201})
    void typeFilterAppliesBeforeDisplayCapAndKeepsFullSnapshotCount(int viewCount) throws Exception {
        FxUiTestSupport.call(() -> {
            List<TableInfo> names = new ArrayList<>();
            for (int i = 0; i < 250; i++) names.add(item("table_" + i, TableInfo.Kind.TABLE));
            for (int i = 0; i < viewCount; i++) names.add(item("view_" + i, TableInfo.Kind.VIEW));
            try (var f = new Fixture(names)) {
                f.load(); f.kind().getSelectionModel().select(2);
                assertEquals(names.subList(250, 250 + Math.min(200, viewCount)), List.copyOf(f.list().getItems()));
                assertEquals("匹配 " + viewCount + " / " + names.size() + " 个对象"
                        + (viewCount > 200 ? " · 仅显示前 200 个，请继续缩小范围" : ""), f.status());
                f.query().setText("view_" + (viewCount - 1));
                assertEquals(List.of(names.getLast()), List.copyOf(f.list().getItems()));
                assertEquals("匹配 1 / " + names.size() + " 个对象", f.status()); assertEquals(1, f.loads);
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void emptySchemaAndTypeWithoutMatchesRemainDistinct(boolean emptySchema) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(emptySchema ? List.of() : List.of(seed().getFirst()))) {
                f.load(); f.kind().getSelectionModel().select(2);
                assertEquals("匹配 0 / " + (emptySchema ? 0 : 1) + " 个对象", f.status());
                String placeholder = ((Label) f.list().getPlaceholder()).getText();
                assertTrue(placeholder.contains(emptySchema ? "没有可见" : "没有匹配"));
                assertTrue(f.button("confirm").isDisabled()); assertTrue(f.button("copy").isDisabled());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"source", "closed", "disabled"})
    void staleScopeCannotCopyOrConfirmAfterTypeChanges(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.list().getSelectionModel().selectFirst();
                if (state.equals("source")) { f.allowed.set(false); f.picker.sourceChanged(); assertTrue(f.kind().isDisabled()); }
                else if (state.equals("closed")) f.picker.close();
                else f.picker.dialog().getDialogPane().setDisable(true);
                f.kind().getSelectionModel().select(2); f.button("copy").fire(); f.button("confirm").fire();
                assertTrue(f.copies.isEmpty()); assertNull(f.picker.dialog().getResult()); assertEquals(1, f.jobs.size());
            }
            return null;
        });
    }

    @Test void missingTypeCannotBroadenResultsAndClearCanRecover() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.list().getSelectionModel().selectFirst(); f.kind().setValue(null);
                assertTrue(f.list().getItems().isEmpty()); assertTrue(f.button("confirm").isDisabled());
                assertTrue(f.button("copy").isDisabled()); assertFalse(f.button("clear").isDisabled());
                f.button("clear").fire(); assertEquals(seed(), List.copyOf(f.list().getItems()));
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertEquals(1, f.loads);
            }
            return null;
        });
    }

    @Test void typePopupEscapeOnlyClosesPopupAndExplicitCandidateEnterStillConfirms() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.picker.dialog().show(); f.jobs.getLast().publish();
                f.kind().getSelectionModel().select(2); f.list().getSelectionModel().selectFirst();
                f.kind().requestFocus(); f.kind().fireEvent(key(KeyCode.ENTER));
                assertNull(f.picker.dialog().getResult()); assertTrue(f.copies.isEmpty());
                f.kind().show(); assertTrue(f.kind().isShowing());
                f.kind().fireEvent(key(KeyCode.ESCAPE));
                assertFalse(f.kind().isShowing()); assertTrue(f.picker.dialog().isShowing());
                f.query().fireEvent(key(KeyCode.ENTER));
                assertEquals(seed().get(1).ref(), f.picker.dialog().getResult());
                assertFalse(f.picker.dialog().isShowing()); assertTrue(f.copies.isEmpty());
            }
            try (var fresh = new Fixture(seed())) {
                assertEquals("全部", fresh.kind().getValue().toString());
                fresh.picker.dialog().show(); fresh.jobs.getLast().publish();
                fresh.kind().fireEvent(key(KeyCode.ESCAPE));
                assertFalse(fresh.picker.dialog().isShowing()); assertNull(fresh.picker.dialog().getResult());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void narrowThemesKeepTypeCountAndIdentityVisibleWithCopyFeedback(String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var names = java.util.stream.IntStream.range(0, 201).mapToObj(i -> item("view_" + i, TableInfo.Kind.VIEW)).toList();
            try (var f = new Fixture(names)) {
                var pane = f.picker.dialog().getDialogPane();
                pane.getStylesheets().addAll(getClass().getResource("theme-base.css").toExternalForm(),
                        getClass().getResource("theme-" + theme + ".css").toExternalForm());
                f.load(); f.kind().getSelectionModel().select(2); f.list().getSelectionModel().selectFirst(); f.button("copy").fire();
                pane.resize(480, 548); pane.applyCss(); pane.layout();
                for (String id : List.of("kind", "status", "list", "target", "preview", "copy-status", "confirm")) {
                    var node = pane.lookup("#schema-object-" + id); var bounds = node.localToScene(node.getBoundsInLocal());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= 480, id + ": " + bounds);
                    assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= 548, id + ": " + bounds);
                    assertTrue(bounds.getHeight() > 0, id + " must remain visible");
                }
                for (String id : List.of("target", "preview")) {
                    TextArea area = (TextArea) pane.lookup("#schema-object-" + id);
                    assertTrue(area.getHeight() >= area.prefHeight(area.getWidth()) - 1);
                }
                Label status = (Label) pane.lookup("#schema-object-status");
                assertTrue(status.getHeight() >= status.prefHeight(status.getWidth()) - 1, "cap warning must wrap without clipping");
                assertTrue(f.feedback().isVisible()); assertEquals("匹配 201 / 201 个对象 · 仅显示前 200 个，请继续缩小范围", f.status());
            }
            return null;
        });
    }

    private static TableInfo item(String name, TableInfo.Kind type) { return new TableInfo("s", name, type, null); }
    private static List<TableInfo> seed() { return List.of(item("same", TableInfo.Kind.TABLE), item("same", TableInfo.Kind.VIEW),
            item("report", TableInfo.Kind.VIEW), item(".* 中😀", TableInfo.Kind.TABLE)); }
    private static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false); }
    private record Job(Callable<List<TableInfo>> work, Consumer<List<TableInfo>> success, Consumer<Throwable> failure) {
        void publish() throws Exception { success.accept(work.call()); }
    }
    private static final class Fixture implements AutoCloseable {
        final List<Job> jobs = new ArrayList<>();
        final List<TableRef> copies = new ArrayList<>();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final SchemaObjectSearchDialog picker;
        int loads;
        Fixture(List<TableInfo> names) {
            picker = new SchemaObjectSearchDialog("synthetic", "s", null, () -> { loads++; return names; },
                    (work, ok, fail) -> { jobs.add(new Job(work, ok, fail)); return new CompletableFuture<>(); }, () -> {}, allowed::get);
            picker.installCopyAction(ref -> { copies.add(ref); return new ConnectionTreeClipboard.CopyResult("已复制限定名称；粘贴前请确认目标连接。", true); });
            var pane = picker.dialog().getDialogPane(); pane.resize(640, 610); pane.applyCss(); pane.layout();
        }
        void load() throws Exception { picker.reload(); jobs.getLast().publish(); }
        ChoiceBox<?> kind() { return (ChoiceBox<?>) picker.dialog().getDialogPane().lookup("#schema-object-kind"); }
        TextField query() { return (TextField) picker.dialog().getDialogPane().lookup("#schema-object-query"); }
        @SuppressWarnings("unchecked") ListView<TableInfo> list() { return (ListView<TableInfo>) picker.dialog().getDialogPane().lookup("#schema-object-list"); }
        TextArea preview() { return (TextArea) picker.dialog().getDialogPane().lookup("#schema-object-preview"); }
        Button button(String id) { return (Button) picker.dialog().getDialogPane().lookup("#schema-object-" + id); }
        Label feedback() { return (Label) picker.dialog().getDialogPane().lookup("#schema-object-copy-status"); }
        String status() { return ((Label) picker.dialog().getDialogPane().lookup("#schema-object-status")).getText(); }
        @Override public void close() { kind().hide(); picker.dialog().close(); picker.close(); }
    }
}
