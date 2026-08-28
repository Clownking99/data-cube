package com.datacube.fx;

import com.datacube.config.CredentialCipher;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionDialogTest {
    private static ConnConfig config(DbType type) {
        return new ConnConfig("test", "test", type, "example.invalid", type.defaultPort(),
                type == DbType.REDIS ? "0" : "db", "user", "fake-existing-cipher", Map.of());
    }

    @Test void pendingDisablesSaveAndFormButRetainsCancelAndFailureInput() throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                Dialog<ConnConfig> dialog = ConnectionDialog.create(config(DbType.POSTGRESQL),
                        new CredentialCipher(), controller);
                try {
                    dialog.show();
                    var pane = dialog.getDialogPane();
                    Button test = button(pane, "test");
                    Button save = button(pane, "save");
                    TextField host = (TextField) pane.lookup("#connection-host");
                    test.fire();
                    test.fire();
                    assertEquals(1, pending.calls);
                    assertTrue(test.isDisabled());
                    assertTrue(save.isDisabled());
                    assertTrue(host.isDisabled());
                    assertFalse(pane.lookupButton(ButtonType.CANCEL).isDisabled());
                    assertEquals("正在测试连接…", status(pane));
                    assertTrue(pane.lookup("#connection-test-progress").isVisible());
                    pending.success.accept("private sentinel-secret jdbc:private");
                    assertTrue(dialog.isShowing());
                    assertFalse(test.isDisabled());
                    assertFalse(save.isDisabled());
                    assertFalse(host.isDisabled());
                    assertFalse(pane.lookup("#connection-test-progress").isManaged());
                    assertEquals("example.invalid", host.getText());
                    assertTrue(status(pane).contains("主机和端口"));
                    assertFalse(status(pane).contains("sentinel-secret"));
                    assertFalse(status(pane).contains("jdbc:"));
                    save.fire();
                    assertFalse(dialog.isShowing());
                    assertNotNull(dialog.getResult(), "a failed test must not prevent saving valid config");
                    assertEquals("example.invalid", dialog.getResult().host());
                    assertEquals(1, pending.calls, "save must not test again");
                } finally { dialog.close(); }
            }
            return null;
        });
    }

    @ParameterizedTest @EnumSource(DbType.class)
    void saveIsIndependentAndEditPreservesCipherForEveryProvider(DbType type) throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            AtomicInteger stops = new AtomicInteger();
            try (var controller = new ConnectionTestController(pending, stops::incrementAndGet, cfg -> null)) {
                var original = config(type);
                var dialog = ConnectionDialog.create(original, new CredentialCipher(), controller);
                try {
                    dialog.show();
                    button(dialog.getDialogPane(), "save").fire();
                    ConnConfig result = dialog.getResult();
                    assertFalse(dialog.isShowing());
                    assertNotNull(result);
                    assertEquals(original.id(), result.id());
                    assertEquals(type, result.type());
                    assertEquals(original.host(), result.host());
                    assertEquals(original.port(), result.port());
                    assertEquals(original.database(), result.database());
                    assertEquals("fake-existing-cipher", result.encryptedPassword());
                    assertEquals(0, pending.calls);
                    assertEquals(1, stops.get());
                } finally { dialog.close(); }
            }
            return null;
        });
    }

    @Test void newDialogHasNoRequestAndDynamicFieldsHaveNames() throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                var dialog = ConnectionDialog.create(null, new CredentialCipher(), controller);
                var pane = dialog.getDialogPane();
                assertEquals(0, pending.calls);
                assertEquals("尚未测试当前配置", status(pane));
                for (String id : List.of("type", "name", "host", "port", "database", "user",
                        "password", "environment", "read-only", "timeout")) {
                    Node field = pane.lookup("#connection-" + id);
                    assertNotNull(field, id);
                    assertNotNull(field.getAccessibleText(), id);
                    assertFalse(field.getAccessibleText().isBlank(), id);
                }
                @SuppressWarnings("unchecked")
                ComboBox<DbType> type = (ComboBox<DbType>) pane.lookup("#connection-type");
                type.setValue(DbType.ORACLE);
                assertEquals("服务名:", pane.lookup("#connection-database").getAccessibleText());
                assertEquals("1521", ((TextField) pane.lookup("#connection-port")).getText());
                type.setValue(DbType.REDIS);
                assertEquals("DB 索引:", pane.lookup("#connection-database").getAccessibleText());
                assertFalse(pane.lookup("#connection-environment").isManaged());
                assertFalse(pane.lookup("#connection-read-only").isVisible());
                assertFalse(pane.lookup("#connection-timeout").isVisible());
                type.setValue(DbType.POSTGRESQL);
                assertEquals("数据库:", pane.lookup("#connection-database").getAccessibleText());
                assertTrue(pane.lookup("#connection-environment").isManaged());
                assertTrue(pane.lookup("#connection-timeout").isVisible());
                assertEquals(0, pending.calls);
            }
            return null;
        });
    }

    @Test void everyEditedFieldInvalidatesPreviousResult() throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                var pane = ConnectionDialog.create(config(DbType.POSTGRESQL),
                        new CredentialCipher(), controller).getDialogPane();
                for (String id : List.of("name", "host", "port", "database", "user", "password", "timeout")) {
                    controller.start(config(DbType.POSTGRESQL));
                    pending.success.accept(null);
                    assertEquals("连接成功，可保存配置", status(pane));
                    TextField field = (TextField) pane.lookup("#connection-" + id);
                    field.setText(field.getText() + "x");
                    assertEquals("尚未测试当前配置", status(pane), id);
                }
                for (String id : List.of("type", "environment")) {
                    controller.start(config(DbType.POSTGRESQL));
                    pending.success.accept("private");
                    ComboBox<?> field = (ComboBox<?>) pane.lookup("#connection-" + id);
                    field.getSelectionModel().selectLast();
                    assertEquals(ConnectionTestController.Phase.IDLE, controller.phase(), id);
                }
                controller.start(config(DbType.POSTGRESQL));
                pending.success.accept(null);
                ((CheckBox) pane.lookup("#connection-read-only")).setSelected(true);
                assertEquals(ConnectionTestController.Phase.IDLE, controller.phase());
            }
            return null;
        });
    }

    @ParameterizedTest @EnumSource(DbType.class)
    void tabTraversalFollowsVisibleFieldsAndReachesActions(DbType type) throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                var dialog = ConnectionDialog.create(config(type), new CredentialCipher(), controller);
                try {
                    dialog.show();
                    var pane = dialog.getDialogPane();
                    pane.applyCss();
                    pane.layout();
                    pane.lookup("#connection-name").requestFocus();
                    var fields = new java.util.ArrayList<>(List.of("host", "port", "database", "user", "password"));
                    if (type != DbType.REDIS) fields.addAll(List.of("environment", "read-only", "timeout"));
                    fields.addAll(List.of("test", "save"));
                    for (String id : fields) {
                        Event.fireEvent(pane.getScene().getFocusOwner(), new KeyEvent(KeyEvent.KEY_PRESSED,
                                "", "", KeyCode.TAB, false, false, false, false));
                        assertSame(pane.lookup("#connection-" + id), pane.getScene().getFocusOwner(), id);
                    }
                    Event.fireEvent(pane.getScene().getFocusOwner(), new KeyEvent(KeyEvent.KEY_PRESSED,
                            "", "", KeyCode.TAB, false, false, false, false));
                    assertSame(pane.lookupButton(ButtonType.CANCEL), pane.getScene().getFocusOwner());
                    assertEquals(0, pending.calls);
                } finally { dialog.close(); }
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"theme-dark.css", "theme-light.css"})
    void failureTextFitsAfterIdleToFailedTransition(String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                var dialog = ConnectionDialog.create(config(DbType.POSTGRESQL), new CredentialCipher(), controller);
                try {
                    var pane = dialog.getDialogPane();
                    pane.getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                            ThemeManager.class.getResource(theme).toExternalForm());
                    dialog.show();
                    pane.applyCss();
                    pane.layout();
                    button(pane, "test").fire();
                    pending.success.accept("private");
                    pane.applyCss();
                    pane.layout();
                    Label status = (Label) pane.lookup("#connection-test-status");
                    assertTrue(status.getHeight() + 0.5 >= status.prefHeight(status.getWidth()),
                            "failure guidance must have enough height for every wrapped line");
                    assertTrue(status.localToScene(status.getBoundsInLocal()).getMaxY()
                            <= button(pane, "save").localToScene(button(pane, "save").getBoundsInLocal()).getMinY(),
                            "guidance must not overlap the action buttons");
                    for (Node action : List.of(button(pane, "test"), button(pane, "save"),
                            pane.lookupButton(ButtonType.CANCEL))) {
                        var bounds = action.localToScene(action.getBoundsInLocal());
                        assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= pane.getScene().getHeight(),
                                "all action buttons must remain fully inside the window: " + bounds);
                    }
                } finally { dialog.close(); }
            }
            return null;
        });
    }

    @Test void submissionRejectionRestoresControlsAndKeepsDialogOpen() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var controller = new ConnectionTestController((work, ok, failed) -> {
                throw new java.util.concurrent.RejectedExecutionException("sentinel-secret");
            }, () -> {}, cfg -> null)) {
                var dialog = ConnectionDialog.create(config(DbType.POSTGRESQL), new CredentialCipher(), controller);
                try {
                    dialog.show();
                    var pane = dialog.getDialogPane();
                    button(pane, "test").fire();
                    assertTrue(dialog.isShowing());
                    assertFalse(button(pane, "test").isDisabled());
                    assertFalse(button(pane, "save").isDisabled());
                    assertFalse(pane.lookup("#connection-host").isDisabled());
                    assertEquals("无法开始连接测试，请稍后重试", status(pane));
                } finally { dialog.close(); }
            }
            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({"POSTGRESQL,name,EMPTY", "POSTGRESQL,port,not-a-port",
            "POSTGRESQL,timeout,-1", "REDIS,database,16"})
    void invalidTestKeepsDialogAndFocusWithoutRequest(DbType type, String fieldId, String invalid) throws Exception {
        invalidActionKeepsDialog(type, fieldId, invalid, "test");
    }

    @Test void invalidSaveKeepsDialogAndFocusWithoutRequest() throws Exception {
        invalidActionKeepsDialog(DbType.POSTGRESQL, "name", "EMPTY", "save");
    }

    private static void invalidActionKeepsDialog(DbType type, String fieldId, String invalid, String action)
            throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                var dialog = ConnectionDialog.create(config(type), new CredentialCipher(), controller);
                try {
                    dialog.show();
                    var pane = dialog.getDialogPane();
                    var field = (TextField) pane.lookup("#connection-" + fieldId);
                    field.setText(invalid.equals("EMPTY") ? "" : invalid);
                    AtomicBoolean dismissed = new AtomicBoolean();
                    // warn() uses a nested FX event loop: dismiss only the validation alert.
                    Platform.runLater(() -> {
                        for (Window window : List.copyOf(Window.getWindows())) {
                            if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane warning
                                    && warning != pane && warning.getButtonTypes().equals(List.of(ButtonType.OK))) {
                                dismissed.set(true);
                                ((Button) warning.lookupButton(ButtonType.OK)).fire();
                            }
                        }
                    });
                    button(pane, action).fire();
                    assertTrue(dismissed.get(), "validation must show the warning");
                    assertTrue(dialog.isShowing(), "invalid form must remain open");
                    assertSame(field, pane.getScene().getFocusOwner());
                    assertEquals(0, pending.calls);
                    assertNull(dialog.getResult());
                } finally { dialog.close(); }
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void slowOperationLeavesFxResponsiveAndCloseSuppressesResult(boolean cancelButton) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean operationOnFx = new AtomicBoolean(true);
        AtomicInteger changes = new AtomicInteger();
        try (var runner = new FxTaskRunner()) {
            FxTaskScope scope = runner.scope();
            var fixture = FxUiTestSupport.call(() -> {
                var controller = new ConnectionTestController(scope, cfg -> {
                    operationOnFx.set(Platform.isFxApplicationThread());
                    started.countDown();
                    try {
                        while (true) {
                            try { release.await(); break; }
                            catch (InterruptedException ignored) { interrupted.countDown(); }
                        }
                        return null;
                    } finally { finished.countDown(); }
                });
                controller.phaseProperty().addListener((o, a, b) -> changes.incrementAndGet());
                var dialog = ConnectionDialog.create(config(DbType.POSTGRESQL), new CredentialCipher(), controller);
                dialog.show();
                return new Fixture(dialog, controller);
            });
            try {
                // Schedule separately so even an accidental synchronous implementation can be released in finally.
                Platform.runLater(() -> button(fixture.dialog().getDialogPane(), "test").fire());
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertFalse(operationOnFx.get());
                assertEquals("heartbeat", FxUiTestSupport.call(() -> "heartbeat"));
                int beforeClose = changes.get();
                FxUiTestSupport.call(() -> {
                    if (cancelButton) ((Button) fixture.dialog().getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
                    else fixture.dialog().close();
                    assertFalse(fixture.dialog().isShowing());
                    assertNull(fixture.dialog().getResult());
                    return null;
                });
                assertTrue(scope.isClosed());
                assertTrue(interrupted.await(5, TimeUnit.SECONDS), "closing must request interruption");
                release.countDown();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> null);
                assertEquals(beforeClose, changes.get());
                runner.submit(() -> {}).get(5, TimeUnit.SECONDS); // Dialog must not own the application runner.
            } finally {
                release.countDown();
                FxUiTestSupport.call(() -> { fixture.dialog().close(); fixture.controller().close(); return null; });
            }
        }
    }

    private static Button button(DialogPane pane, String name) {
        return (Button) pane.lookup("#connection-" + name);
    }

    private static String status(DialogPane pane) {
        return ((Label) pane.lookup("#connection-test-status")).getText();
    }

    private record Fixture(Dialog<ConnConfig> dialog, ConnectionTestController controller) {}
}
