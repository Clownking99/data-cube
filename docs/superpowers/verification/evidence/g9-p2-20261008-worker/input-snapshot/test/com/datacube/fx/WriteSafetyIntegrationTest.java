package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.*;
import com.datacube.spi.model.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WriteSafetyIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = DbType.class, names = {"ORACLE", "POSTGRESQL"})
    void readonlyPanesDisableWritesAfterLoadingButKeepReadAndPreviewControls(DbType type) throws Exception {
        try (var probe = new WriteSafetyFxProbe(type, true, "PRODUCTION"); var runner = new FxTaskRunner()) {
            Object[] panes = FxUiTestSupport.call(() -> {
                var manager = probe.manager();
                var ddl = new DdlService(manager);
                var target = ddl.target("target");
                return new Object[]{
                        new DataGridPane(new DataBrowseService(manager), new DataEditService(manager), "target", "synthetic",
                                new TableRef("synthetic", "items"), new AppSettings(directory.resolve("settings")), false, runner),
                        new TableDesignerPane(new TableDesignService(manager), "target", "synthetic", null, "synthetic", type, runner),
                        new SequenceDesignerPane(ddl, "target", "synthetic", "synthetic", "seq", type, runner),
                        new ObjectEditorPane("synthetic view", () -> "CREATE VIEW v AS SELECT 1",
                                sql -> ddl.prepareExecute(target, sql), target, runner)};
            });
            runner.close(); // joins all synthetic loads; queued FX callbacks are drained by call below
            FxUiTestSupport.call(() -> {
                assertTrue(button(panes[0], "addBtn").isDisabled());
                assertTrue(button(panes[0], "deleteBtn").isDisabled());
                assertFalse(button(panes[0], "reloadBtn").isDisabled());
                assertFalse(((TableView<?>) field(panes[0], "grid")).isEditable());
                assertTrue(((Label) field(panes[0], "hintLabel")).getText().contains("只读"));
                assertTrue(button(panes[1], "applyBtn").isDisabled());
                assertTrue(button(panes[2], "applyBtn").isDisabled());
                assertFalse(button(panes[2], "previewBtn").isDisabled());
                assertTrue(button(panes[3], "executeBtn").isDisabled());
                assertFalse(button(panes[3], "reloadBtn").isDisabled());
                assertTrue(button(panes[3], "executeBtn").getTooltip().getText().contains("只读"));
                ((DataGridPane) panes[0]).closeResources(); ((DataGridPane) panes[0]).finalizeCloseOnFx();
                ((TableDesignerPane) panes[1]).close(); ((SequenceDesignerPane) panes[2]).close();
                ((ObjectEditorPane) panes[3]).close();
                return null;
            });
            assertEquals(0, probe.writes());
        }
    }

    @Test
    void configurationChangeDisablesAlreadyOpenDesignerAndCloseRemovesListener() throws Exception {
        try (var probe = new WriteSafetyFxProbe(DbType.POSTGRESQL, false, "TEST"); var runner = new FxTaskRunner()) {
            var pane = FxUiTestSupport.call(() -> new TableDesignerPane(new TableDesignService(probe.manager()),
                    "target", "synthetic", null, "synthetic", DbType.POSTGRESQL, runner));
            assertFalse(FxUiTestSupport.call(() -> button(pane, "applyBtn").isDisabled()));
            probe.manager().unregister("target");
            FxUiTestSupport.call(() -> {
                assertTrue(button(pane, "applyBtn").isDisabled());
                assertTrue(button(pane, "applyBtn").getTooltip().getText().contains("变化或删除"));
                pane.close(); return null;
            });
            assertEquals(0, probe.writes());
            assertEquals(0, probe.opens());
            var listeners = field(probe.manager(), "configListeners");
            assertTrue(((java.util.Map<?, ?>) listeners).isEmpty());
        }
    }

    @Test
    void productionDialogShowsExactTargetAndDdlCancellationDoesNotWriteAndApprovalRunsOnce() throws Exception {
        try (var probe = new WriteSafetyFxProbe(DbType.POSTGRESQL, false, "PRODUCTION")) {
            DdlService service = new DdlService(probe.manager());
            var request = service.prepareExecute(service.target("target"), "ALTER SEQUENCE synthetic.seq INCREMENT BY 2");
            AtomicReference<String> text = new AtomicReference<>();
            var cancelled = FxUiTestSupport.call(() -> {
                Platform.runLater(() -> answerDialog(false, text));
                return WriteSafetyDialog.confirm(request, false);
            });
            assertNull(cancelled);
            assertEquals(0, probe.opens());
            assertEquals(0, probe.writes());
            assertTrue(text.get().contains("生产"));
            assertTrue(text.get().contains("synthetic.invalid:1"));
            assertTrue(text.get().contains("[target]"));
            assertTrue(text.get().contains("ALTER SEQUENCE synthetic.seq INCREMENT BY 2"));
            var approved = FxUiTestSupport.call(() -> {
                Platform.runLater(() -> answerDialog(true, text));
                return WriteSafetyDialog.confirm(request, false);
            });
            assertNotNull(approved);
            request.execute(approved);
            assertEquals(1, probe.writes());
            assertThrows(IllegalStateException.class, () -> request.execute(approved));
        }
    }

    @Test
    void lateApprovalAfterConfigurationDeletionShowsReasonAndAcquiresNoWriteResource() throws Exception {
        try (var probe = new WriteSafetyFxProbe(DbType.POSTGRESQL, false, "PRODUCTION")) {
            var service = new DdlService(probe.manager());
            var request = service.prepareExecute(service.target("target"), "CREATE TABLE synthetic.t(id int)");
            AtomicReference<String> reason = new AtomicReference<>();
            var confirmation = FxUiTestSupport.call(() -> {
                Platform.runLater(() -> {
                    probe.manager().unregister("target");
                    answerDialog(true, new AtomicReference<>());
                    Platform.runLater(() -> {
                        DialogPane pane = Window.getWindows().stream().filter(Window::isShowing)
                                .map(w -> w.getScene().getRoot()).filter(DialogPane.class::isInstance)
                                .map(DialogPane.class::cast).findFirst().orElseThrow();
                        reason.set(pane.getContentText());
                        ((Button) pane.lookupButton(ButtonType.OK)).fire();
                    });
                });
                return WriteSafetyDialog.confirm(request, false);
            });
            assertNull(confirmation);
            assertTrue(reason.get().contains("变化或删除"));
            assertEquals(0, probe.opens());
            assertEquals(0, probe.writes());
        }
    }

    private static void answerDialog(boolean approve, AtomicReference<String> text) {
        DialogPane pane = Window.getWindows().stream().filter(Window::isShowing)
                .map(w -> w.getScene().getRoot()).filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast).findFirst().orElseThrow();
        text.set(((TextArea) pane.getContent()).getText());
        ButtonType button = approve ? pane.getButtonTypes().getFirst() : ButtonType.CANCEL;
        ((Button) pane.lookupButton(button)).fire();
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Button button(Object pane, String name) throws Exception { return (Button) field(pane, name); }
}
