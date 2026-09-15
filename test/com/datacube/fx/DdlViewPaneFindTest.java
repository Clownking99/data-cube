package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.layout.Region;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.fxmisc.richtext.CodeArea;

import static org.junit.jupiter.api.Assertions.*;

class DdlViewPaneFindTest {
    @Test void toolbarAndShortcutOpenReadOnlyFindWithoutReplacementControls() throws Exception {
        try (var runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                try (var pane = new DdlViewPane("synthetic_table", () -> "CREATE TABLE demo (id INT);", runner)) {
                    Parent root = (Parent) pane.getNode();
                    new Scene(root, 480, 400);
                    root.applyCss(); root.layout();
                    Button find = (Button) root.lookup("#ddl-find");
                    assertNotNull(find, "DDL viewer needs a discoverable find entry");
                    assertFalse(root.lookup("#sql-find-bar").isVisible());
                    find.fire();
                    assertTrue(root.lookup("#sql-find-bar").isVisible());
                    assertNull(root.lookup("#sql-find-replace-toggle"));
                    assertNull(root.lookup("#sql-replace-pane"));
                    root.fireEvent(key(KeyCode.ESCAPE, false, false));
                    assertFalse(root.lookup("#sql-find-bar").isVisible());
                    root.fireEvent(key(KeyCode.F, false, true));
                    assertTrue(root.lookup("#sql-find-bar").isVisible());
                    root.fireEvent(key(KeyCode.H, false, true));
                    assertNull(root.lookup("#sql-replace-pane"));
                    assertFalse(((CodeArea) root.lookup("#ddl-text")).isEditable());
                    pane.finalizeCloseOnFx();
                }
                return null;
            });
        }
    }

    @Test void actualViewerSearchCountsNavigatesAndKeepsDdlUnchangedWithoutRefetch() throws Exception {
        String ddl = "CREATE TABLE demo (id INT, user_id INT);\n-- ID id · 中😀 .*";
        AtomicInteger fetches = new AtomicInteger();
        try (var f = new Fixture(() -> { fetches.incrementAndGet(); return ddl; })) {
            f.awaitStatus("ddl-status", "就绪", () -> {});
            FxUiTestSupport.call(() -> {
                assertEquals(0, f.editor().getCaretPosition(), "loaded DDL should start at the definition, not its end");
                return null;
            });
            f.search("id", "共 4 处");
            f.awaitStatus("sql-find-status", "共 3 处", () -> f.check("whole-word").setSelected(true));
            f.awaitStatus("sql-find-status", "共 2 处", () -> f.check("match-case").setSelected(true));
            FxUiTestSupport.call(() -> {
                assertEquals(0, f.editor().getSelection().getLength(), "counting must not select text");
                f.editor().moveTo(0);
                f.query().fireEvent(key(KeyCode.ENTER, false, false));
                assertEquals(ddl.indexOf("id"), f.editor().getSelection().getStart());
                assertEquals("id", f.editor().getSelectedText());
                f.query().fireEvent(key(KeyCode.ENTER, true, false));
                assertEquals(ddl.lastIndexOf("id"), f.editor().getSelection().getStart());
                assertEquals("2 / 2 · 已回到末尾", f.label("sql-find-status").getText());
                f.query().fireEvent(key(KeyCode.ESCAPE, false, false));
                assertFalse(f.root.lookup("#sql-find-bar").isManaged());
                assertEquals("id", f.editor().getSelectedText());
                assertEquals(f.editor(), f.root.getScene().getFocusOwner());
                f.editor().fireEvent(key(KeyCode.F, false, true));
                assertEquals("id", f.query().getText(), "selected single-line DDL prepopulates search");
                return null;
            });
            f.awaitStatus("sql-find-status", "共 1 处", () -> {
                f.check("whole-word").setSelected(false);
                f.query().setText("中😀 .*");
                f.query().fireEvent(key(KeyCode.ENTER, false, false));
            });
            FxUiTestSupport.call(() -> {
                assertEquals(ddl, f.editor().getText());
                assertFalse(f.editor().isEditable());
                assertEquals(1, fetches.get(), "find must not fetch metadata again");
                return null;
            });
        }
    }

    @Test void loadingDdlRefreshesAnAlreadyOpenSearchWithoutAutoSelecting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var f = new Fixture(() -> { assertTrue(release.await(5, TimeUnit.SECONDS)); return "id id"; })) {
            f.search("id", "无匹配");
            f.awaitStatus("sql-find-status", "共 2 处", release::countDown);
            FxUiTestSupport.call(() -> {
                assertEquals("id id", f.editor().getText());
                assertEquals(0, f.editor().getCaretPosition());
                assertEquals(0, f.editor().getSelectedText().length());
                assertEquals("就绪", f.label("ddl-status").getText());
                return null;
            });
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void emptyAndFailedLoadsRemainReadOnlyAndSearchOnlyDisplayedText(boolean fail) throws Exception {
        try (var f = new Fixture(() -> { if (fail) throw new IllegalStateException("synthetic failure"); return null; })) {
            f.awaitStatus("ddl-status", fail ? "错误" : "就绪", () -> {});
            f.search("synthetic", fail ? "共 1 处" : "无匹配");
            FxUiTestSupport.call(() -> {
                assertEquals(fail ? "-- 获取 DDL 失败: synthetic failure" : "", f.editor().getText());
                assertFalse(f.editor().isEditable());
                assertEquals(0, f.editor().getCaretPosition());
                assertEquals(!fail, f.button("sql-find-next").isDisabled());
                return null;
            });
        }
    }

    @Test void backgroundCloseCancelsLoadThenFxFinalizerPreventsFurtherSearchWithoutClosingRunner() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var f = new Fixture(() -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException cancelled) { interrupted.countDown(); }
            return "late DDL must not publish";
        })) {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            f.search("late", "无匹配");
            f.pane.close(); // Test thread deliberately exercises the non-FX resource phase.
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                f.pane.finalizeCloseOnFx();
                f.pane.finalizeCloseOnFx();
                assertTrue(f.root.isDisabled());
                assertFalse(f.root.lookup("#sql-find-bar").isVisible());
                f.button("ddl-find").fire();
                f.root.fireEvent(key(KeyCode.F, false, true));
                assertFalse(f.root.lookup("#sql-find-bar").isVisible());
                assertEquals("", f.editor().getText());
                assertEquals("加载中...", f.label("ddl-status").getText());
                return null;
            });
            CountDownLatch sharedRunnerAlive = new CountDownLatch(1);
            f.runner.submit(sharedRunnerAlive::countDown);
            assertTrue(sharedRunnerAlive.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void narrowThemesKeepFindControlsAndFocusedPromptVisible(String theme) throws Exception {
        try (var f = new Fixture(() -> "CREATE TABLE demo (id INT);")) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().getStylesheets().addAll(
                        getClass().getResource("theme-base.css").toExternalForm(),
                        getClass().getResource("theme-" + theme + ".css").toExternalForm());
                f.button("ddl-find").fire();
                f.query().requestFocus();
                ((Region) f.root).resize(480, 400);
                f.root.applyCss(); f.root.layout();
                for (String id : new String[] {"ddl-find", "sql-find-query", "sql-find-match-case",
                        "sql-find-whole-word", "sql-find-previous", "sql-find-next", "sql-find-close"}) {
                    Node node = f.root.lookup("#" + id);
                    Bounds bounds = node.localToScene(node.getBoundsInLocal());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= 480,
                            id + " must fit horizontally: " + bounds + "; root=" + f.root.getLayoutBounds());
                    assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() < 400, id + " must stay visible");
                }
                Text prompt = (Text) f.query().lookup(".text");
                assertNotNull(prompt);
                assertEquals("查找当前 DDL…", prompt.getText());
                assertTrue(((Color) prompt.getFill()).getOpacity() > 0.9, "focused prompt must not be transparent");
                assertNull(f.root.lookup("#sql-find-replace-toggle"));
                return null;
            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FxTaskRunner runner = new FxTaskRunner();
        final DdlViewPane pane;
        final Parent root;
        Fixture(Callable<String> fetch) throws Exception {
            pane = FxUiTestSupport.call(() -> new DdlViewPane("DDL: synthetic_" + "long_name_".repeat(12), fetch, runner));
            root = (Parent) pane.getNode();
            FxUiTestSupport.call(() -> {
                new Scene(root, 480, 400);
                root.applyCss(); root.layout();
                return null;
            });
        }
        CodeArea editor() { return (CodeArea) root.lookup("#ddl-text"); }
        TextField query() { return (TextField) root.lookup("#sql-find-query"); }
        Button button(String id) { return (Button) root.lookup("#" + id); }
        Label label(String id) { return (Label) root.lookup("#" + id); }
        CheckBox check(String name) { return (CheckBox) root.lookup("#sql-find-" + name); }
        void search(String query, String expected) throws Exception {
            awaitStatus("sql-find-status", expected, () -> {
                button("ddl-find").fire();
                query().setText(query);
                query().fireEvent(key(KeyCode.ENTER, false, false));
            });
        }
        void awaitStatus(String id, String expected, Runnable action) throws Exception {
            CountDownLatch reached = new CountDownLatch(1);
            ChangeListener<String> listener = (obs, before, after) -> { if (expected.equals(after)) reached.countDown(); };
            try {
                FxUiTestSupport.call(() -> {
                    label(id).textProperty().addListener(listener);
                    action.run();
                    if (expected.equals(label(id).getText())) reached.countDown();
                    return null;
                });
                assertTrue(reached.await(5, TimeUnit.SECONDS), "expected " + id + ": " + expected);
            } finally { FxUiTestSupport.call(() -> { label(id).textProperty().removeListener(listener); return null; }); }
        }
        @Override public void close() throws Exception {
            pane.close();
            FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            runner.close();
        }
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false);
    }
}
