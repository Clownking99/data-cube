package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorConnectionGuidanceTest {
    @TempDir Path directory;

    @Test void missingAndRedisPagesBlockButtonsAndShortcutWithoutOpeningSessions() throws Exception {
        try (var fixture = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var root = fixture.pane.getNode();
                // Locate by existing text so the first red fails on behavior, not a missing new ID.
                var execute = root.lookupAll(".button").stream().filter(Button.class::isInstance)
                        .map(Button.class::cast).filter(b -> b.getText().startsWith("执行 (")).findFirst().orElseThrow();
                assertTrue(execute.isDisabled(), "unbound SQL must not offer execution");
                int windows = Window.getWindows().size();
                fixture.pane.setSqlText("SELECT 1");
                for (ConnConfig candidate : new ConnConfig[]{null, config("Redis", DbType.REDIS), null}) {
                    fixture.context.setActiveConnection(candidate);
                    assertTrue(button(root, "execute").isDisabled());
                    assertTrue(button(root, "explain").isDisabled());
                    assertFalse(button(root, "format").isDisabled());
                    assertFalse(root.lookup("#sql-environment").isVisible());
                    assertFalse(root.lookup("#sql-environment").isManaged());
                    assertTrue(label(root, "connection-guidance").getText()
                            .contains(candidate == null ? "左侧" : "Redis"));
                    CodeArea editor = (CodeArea) root.lookup("#sql-editor");
                    Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5,
                            false, false, false, false));
                    // Exercise handlers even if invoked programmatically, not only disabled Button.fire().
                    button(root, "execute").getOnAction().handle(new ActionEvent());
                    button(root, "explain").getOnAction().handle(new ActionEvent());
                    assertNull(((SqlEditorConnectionAdmission) field(fixture.pane, "admission")).pinned());
                    assertNull(field(fixture.pane, "jdbcSession"));
                    assertEquals(windows, Window.getWindows().size(), "guidance must not open an alert");
                }
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(value = DbType.class, names = {"POSTGRESQL", "ORACLE"})
    void candidateSelectionShowsPendingAndCannotOverrideBusyOrClosing(DbType type) throws Exception {
        try (var fixture = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var root = fixture.pane.getNode();
                fixture.selectKnownCandidate(config("A", type));
                assertFalse(button(root, "execute").isDisabled());
                assertFalse(button(root, "explain").isDisabled());
                assertEquals("🔗 待绑定: A", label(root, "connection").getText());
                assertTrue(root.lookup("#sql-environment").isVisible());
                assertTrue(label(root, "connection-guidance").getText().contains("首次执行或会话操作"));
                assertNull(((SqlEditorConnectionAdmission) field(fixture.pane, "admission")).pinned());
                assertNull(field(fixture.pane, "jdbcSession"));
                setField(fixture.pane, "running", true);
                fixture.selectKnownCandidate(config("B", type));
                assertTrue(button(root, "execute").isDisabled());
                assertTrue(button(root, "explain").isDisabled());
                assertTrue(button(root, "format").isDisabled());
                setField(fixture.pane, "running", false);
                return null;
            });
            var closed = FxUiTestSupport.call(fixture.pane::requestClose);
            assertEquals(CloseGuardOutcome.APPROVED, closed.toCompletableFuture().get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                fixture.selectKnownCandidate(config("C", type));
                assertTrue(button(fixture.pane.getNode(), "execute").isDisabled());
                assertTrue(button(fixture.pane.getNode(), "explain").isDisabled());
                return null;
            });
        }
    }

    @Test void pinnedDisplayDoesNotFollowRedisOrAnotherCandidate() throws Exception {
        try (var fixture = new Fixture()) {
            FxUiTestSupport.call(() -> {
                fixture.selectKnownCandidate(config("A", DbType.POSTGRESQL));
                var admission = (SqlEditorConnectionAdmission) field(fixture.pane, "admission");
                var admit = SqlEditorPane.class.getDeclaredMethod("admitCurrentConnection");
                admit.setAccessible(true);
                admit.invoke(fixture.pane);
                assertEquals("🔗 A · 未连接", label(fixture.pane.getNode(), "connection").getText(),
                        "admission must update the fixed target before any session/network operation");
                for (ConnConfig candidate : new ConnConfig[]{config("B", DbType.ORACLE),
                        config("Redis", DbType.REDIS), null}) {
                    fixture.selectKnownCandidate(candidate);
                    assertEquals("A", admission.pinned().name());
                    assertFalse(label(fixture.pane.getNode(), "connection-guidance").isVisible());
                    assertFalse(label(fixture.pane.getNode(), "connection-guidance").isManaged());
                    assertTrue(label(fixture.pane.getNode(), "connection").getText().contains("A"));
                    assertFalse(button(fixture.pane.getNode(), "execute").isDisabled());
                }
                return null;
            });
        }
    }

    @Test void candidateChangeRespectsPendingSessionOperationEvenWhenSqlIsNotRunning() throws Exception {
        try (var fixture = new Fixture()) {
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var operations = (SerialSessionOperationQueue) field(fixture.pane, "sessionOperations");
            try {
                var completion = operations.submit(SerialSessionOperationQueue.OperationKind.SET_MODE, () -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return null;
                }, ignored -> {}, failure -> fail(failure));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    assertEquals(false, field(fixture.pane, "running"));
                    fixture.selectKnownCandidate(config("A", DbType.POSTGRESQL));
                    assertTrue(button(fixture.pane.getNode(), "execute").isDisabled());
                    assertTrue(button(fixture.pane.getNode(), "explain").isDisabled());
                    assertTrue(button(fixture.pane.getNode(), "format").isDisabled());
                    return null;
                });
                release.countDown();
                completion.get(5, TimeUnit.SECONDS);
                operations.idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(() -> {
                    fixture.selectKnownCandidate(config("B", DbType.ORACLE));
                    assertFalse(button(fixture.pane.getNode(), "execute").isDisabled());
                    assertFalse(button(fixture.pane.getNode(), "explain").isDisabled());
                    assertFalse(button(fixture.pane.getNode(), "format").isDisabled());
                    return null;
                });
            } finally { release.countDown(); }
        }
    }

    private static ConnConfig config(String name, DbType type) {
        return new ConnConfig(name, name, type, "example.invalid", type.defaultPort(),
                type == DbType.REDIS ? "0" : "db", "user", "", Map.of());
    }

    private static Button button(Node root, String id) { return (Button) root.lookup("#sql-" + id); }
    private static Label label(Node root, String id) { return (Label) root.lookup("#sql-" + id); }
    private static Object field(SqlEditorPane pane, String name) throws Exception {
        Field field = SqlEditorPane.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(pane);
    }
    private static void setField(SqlEditorPane pane, String name, Object value) throws Exception {
        Field field = SqlEditorPane.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(pane, value);
    }

    private final class Fixture implements AutoCloseable {
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final SqlEditorPane pane;
        Fixture() throws Exception {
            try {
                pane = FxUiTestSupport.call(() -> {
                    var created = new SqlEditorPane(context, null, null,
                            new AppSettings(directory.resolve("settings.properties")),
                            (id, table) -> fail("must not open a designer"), null, null,
                            new SqlHistoryStore(directory.resolve("history.txt")),
                            new ShortcutSettings(directory.resolve("shortcuts.properties")), runner);
                    new Scene((Parent) created.getNode(), 1200, 800);
                    created.getNode().applyCss();
                    return created;
                });
            } catch (Throwable failure) { runner.close(); throw failure; }
        }
        @SuppressWarnings("unchecked")
        void selectKnownCandidate(ConnConfig config) throws Exception {
            // Model a previously warmed candidate. This UI test must never do metadata/network I/O.
            if (config != null) ((Set<String>) field(pane, "prewarmed")).add(config.id());
            context.setActiveConnection(config);
        }
        @Override public void close() throws Exception {
            try {
                var closed = FxUiTestSupport.call(pane::requestClose);
                assertEquals(CloseGuardOutcome.APPROVED, closed.toCompletableFuture().get(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            } finally { runner.close(); }
        }
    }
}
