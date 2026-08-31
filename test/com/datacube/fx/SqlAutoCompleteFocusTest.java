package com.datacube.fx;

import com.datacube.config.ShortcutSettings;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.value.ChangeListener;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class SqlAutoCompleteFocusTest {
    @TempDir Path directory;

    @Test void unfocusedRestoreDoesNotRequestCandidates() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CodeArea area = FxUiTestSupport.call(() -> {
            CodeArea value = new CodeArea();
            new SqlAutoComplete(value, () -> {
                requests.incrementAndGet();
                return List.of("SELECT");
            }, new ShortcutSettings(directory.resolve("shortcuts.properties")));
            assertFalse(value.isFocused());
            value.replaceText("select 'synthetic alpha';\n");
            value.selectRange(9, 3);
            return value;
        });
        FxUiTestSupport.call(() -> {
            assertEquals(0, requests.get(), "Passive restoration must not request completion");
            assertEquals("select 'synthetic alpha';\n", area.getText());
            assertEquals(9, area.getAnchor());
            assertEquals(3, area.getCaretPosition());
            return null;
        });
    }

    @Test void unfocusedReplacementThenFocusGainedDoesNotRequestCandidates() throws Exception {
        try (Fixture fixture = new Fixture(directory.resolve("focus-after-replacement.properties"))) {
            fixture.focus(fixture.other);
            FxUiTestSupport.call(() -> {
                fixture.area.replaceText("sel");
                fixture.area.requestFocus();
                return null;
            });
            fixture.awaitFocus(fixture.area);
            fixture.drain();
            FxUiTestSupport.call(() -> {
                assertEquals(0, fixture.requests.get(), "An unfocused replacement must not enqueue completion");
                assertFalse(fixture.popup().isShowing());
                return null;
            });
        }
    }

    @Test void focusedReplacementThenFocusLostDoesNotRequestCandidates() throws Exception {
        try (Fixture fixture = new Fixture(directory.resolve("focus-lost-before-delivery.properties"))) {
            fixture.focus(fixture.area);
            FxUiTestSupport.call(() -> {
                fixture.area.replaceText("sel");
                fixture.other.requestFocus();
                return null;
            });
            fixture.awaitFocus(fixture.other);
            fixture.drain();
            FxUiTestSupport.call(() -> {
                assertEquals(0, fixture.requests.get(), "Queued completion must not outlive editor focus");
                assertFalse(fixture.popup().isShowing());
                return null;
            });
        }
    }

    @Test void focusedEditShowsActualCandidates() throws Exception {
        try (Fixture fixture = new Fixture(directory.resolve("focused-edit.properties"))) {
            fixture.focus(fixture.area);
            FxUiTestSupport.call(() -> {
                fixture.area.replaceText("sel");
                return null;
            });
            fixture.drain();
            FxUiTestSupport.call(() -> {
                assertEquals(1, fixture.requests.get());
                assertTrue(fixture.popup().isShowing(), "A focused edit must show its actual candidates");
                return null;
            });
        }
    }

    @Test void ctrlSpaceStillShowsActualCandidates() throws Exception {
        try (Fixture fixture = new Fixture(directory.resolve("ctrl-space.properties"))) {
            fixture.focus(fixture.area);
            FxUiTestSupport.call(() -> {
                fixture.area.replaceText("sel");
                return null;
            });
            fixture.drain();
            FxUiTestSupport.call(() -> {
                fixture.completion.hide();
                Event.fireEvent(fixture.area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE,
                        false, true, false, false));
                assertTrue(fixture.popup().isShowing(), "Ctrl+Space must explicitly show actual candidates");
                return null;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode.class, names = {"TAB", "ENTER"})
    void acceptanceReplacesPrefixClosesPopupAndDoesNotReopen(KeyCode key) throws Exception {
        try (Fixture fixture = new Fixture(directory.resolve("accept-" + key.name() + ".properties"))) {
            fixture.focus(fixture.area);
            FxUiTestSupport.call(() -> {
                fixture.area.replaceText("sel");
                return null;
            });
            fixture.drain();
            FxUiTestSupport.call(() -> {
                assertTrue(fixture.popup().isShowing(), "Fixture must begin with an actual visible popup");
                Event.fireEvent(fixture.area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key,
                        false, false, false, false));
                assertEquals("SELECT", fixture.area.getText());
                assertFalse(fixture.popup().isShowing(), "Acceptance must close the popup");
                assertEquals(1, fixture.requests.get(), "Applying a candidate must not recursively reopen completion");
                return null;
            });
            fixture.drain();
            FxUiTestSupport.call(() -> {
                assertFalse(fixture.popup().isShowing(), "Acceptance must remain closed after the FX queue drains");
                assertEquals(1, fixture.requests.get());
                return null;
            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final CodeArea area;
        final Button other;
        final SqlAutoComplete completion;
        final Stage stage;

        Fixture(Path shortcuts) throws Exception {
            Fixture created = FxUiTestSupport.call(() -> {
                CodeArea value = new CodeArea();
                Button alternate = new Button("other focus");
                SqlAutoComplete autoComplete = new SqlAutoComplete(value, () -> {
                    requests.incrementAndGet();
                    return List.of("SELECT");
                }, new ShortcutSettings(shortcuts));
                Stage window = new Stage();
                window.setScene(new Scene(new VBox(value, alternate), 480, 220));
                window.show();
                window.getScene().getRoot().applyCss();
                window.getScene().getRoot().layout();
                return new Fixture(value, alternate, autoComplete, window);
            });
            area = created.area;
            other = created.other;
            completion = created.completion;
            stage = created.stage;
        }

        private Fixture(CodeArea area, Button other, SqlAutoComplete completion, Stage stage) {
            this.area = area;
            this.other = other;
            this.completion = completion;
            this.stage = stage;
        }

        void focus(javafx.scene.Node node) throws Exception {
            awaitFocus(FxUiTestSupport.call(() -> requestFocus(node)));
        }

        void awaitFocus(javafx.scene.Node node) throws Exception {
            awaitFocus(FxUiTestSupport.call(() -> focusFuture(node)));
        }

        private static CompletableFuture<Void> requestFocus(javafx.scene.Node node) {
            CompletableFuture<Void> ready = focusFuture(node);
            node.requestFocus();
            return ready;
        }

        private static CompletableFuture<Void> focusFuture(javafx.scene.Node node) {
            if (node.isFocused()) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> ready = new CompletableFuture<>();
            ChangeListener<Boolean> listener = new ChangeListener<>() {
                @Override public void changed(javafx.beans.value.ObservableValue<? extends Boolean> observable,
                                              Boolean wasFocused, Boolean isFocused) {
                    if (isFocused) {
                        node.focusedProperty().removeListener(this);
                        ready.complete(null);
                    }
                }
            };
            node.focusedProperty().addListener(listener);
            return ready;
        }

        private static void awaitFocus(CompletableFuture<Void> ready) throws Exception {
            ready.get(5, TimeUnit.SECONDS);
        }

        void drain() throws Exception {
            FxUiTestSupport.call(() -> null);
        }

        PopupWindow popup() throws Exception {
            Field field = SqlAutoComplete.class.getDeclaredField("popup");
            field.setAccessible(true);
            return (PopupWindow) field.get(completion);
        }

        @Override public void close() throws Exception {
            FxUiTestSupport.call(() -> {
                completion.hide();
                stage.hide();
                return null;
            });
        }
    }
}
