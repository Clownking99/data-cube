package com.datacube.fx;

import com.datacube.sqleditor.SqlTextReplacement;
import com.datacube.sqleditor.SqlTextSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlReplaceBarTest {
    @Test void wholeWordReplacementChangesOnlyCountedMatchesAndUndoRestoresAllText() throws Exception {
        FxUiTestSupport.call(() -> {
            String text = "id user_id id2 ID t.id";
            try (var f = new Fixture(text)) {
                f.bar.showReplace();
                ((CheckBox) f.bar.getNode().lookup("#sql-find-whole-word")).setSelected(true);
                f.scan("id"); f.replacement().setText("key");
                assertEquals("共 3 处", ((Label) f.bar.getNode().lookup("#sql-find-status")).getText());
                f.editor.selectRange(8, 10);
                assertTrue(f.button("current").isDisabled(), "substring excluded by whole-word mode cannot be replaced");
                f.button("current").getOnAction().handle(new javafx.event.ActionEvent());
                assertTrue(f.edits.isEmpty());
                f.editor.selectRange(15, 17); f.button("current").fire(); f.edits.getLast().publish();
                assertEquals("id user_id id2 key t.id", f.editor.getText());
                assertEquals("key", f.editor.getSelectedText());
                f.editor.undo(); assertEquals(text, f.editor.getText());
                f.scan("id"); f.button("all").fire(); f.edits.getLast().publish();
                assertEquals("key user_id id2 key t.key", f.editor.getText());
                assertTrue(f.feedback().startsWith("已替换 3 处"));
                f.editor.undo(); assertEquals(text, f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void switchingToWholeWordsRejectsOldReplacementAndZeroMatchesCannotEdit() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("user_id id2")) {
                f.bar.showReplace(); f.scan("id"); f.replacement().setText("key");
                f.button("all").fire(); var old = f.edits.getLast();
                ((CheckBox) f.bar.getNode().lookup("#sql-find-whole-word")).setSelected(true);
                assertTrue(old.future.isCancelled());
                assertTrue(f.button("all").isDisabled());
                f.button("all").getOnAction().handle(new javafx.event.ActionEvent());
                assertEquals(1, f.edits.size(), "no replacement may use the stale substring matches");
                f.scan("id"); old.publish();
                assertEquals("无匹配", ((Label) f.bar.getNode().lookup("#sql-find-status")).getText());
                assertTrue(f.button("all").isDisabled()); assertTrue(f.button("current").isDisabled());
                f.button("all").getOnAction().handle(new javafx.event.ActionEvent());
                assertEquals(1, f.edits.size());
                assertEquals("user_id id2", f.editor.getText()); assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void replaceCurrentRequiresAnExactMatchAndIsAnIndependentUndoUnit() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("one ONE one")) {
                f.bar.show();
                assertFalse(f.bar.getNode().lookup("#sql-replace-pane").isManaged());
                f.bar.showReplace();
                f.scan("one");
                f.replacement().setText("$1\\中");
                assertTrue(f.button("current").isDisabled());
                f.editor.selectRange(1, 3);
                assertTrue(f.button("current").isDisabled(), "arbitrary selections must not be replaced");
                f.editor.selectRange(4, 7);
                f.button("current").fire();
                assertEquals("one ONE one", f.editor.getText(), "worker must not edit synchronously");
                assertTrue(f.button("all").isDisabled());
                f.edits.getLast().publish();
                assertEquals("one $1\\中 one", f.editor.getText());
                assertEquals("$1\\中", f.editor.getSelectedText());
                assertTrue(f.feedback().startsWith("已替换 1 处"));
                f.editor.undo();
                assertEquals("one ONE one", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
                f.editor.redo();
                assertEquals("one $1\\中 one", f.editor.getText());
            }
            return null;
        });
    }

    @Test void replaceAllIsOneUndoSeparatedFromTypingBeforeAndAfter() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("a aa a")) {
                f.editor.appendText("!");
                f.bar.showReplace();
                f.scan("a");
                f.replacement().setText("long");
                f.button("all").fire();
                f.edits.getLast().publish();
                assertEquals("long longlong long!", f.editor.getText());
                assertTrue(f.feedback().startsWith("已替换 4 处"));
                f.editor.appendText("?");
                f.editor.undo();
                assertEquals("long longlong long!", f.editor.getText());
                f.editor.undo();
                assertEquals("a aa a!", f.editor.getText());
                f.editor.undo();
                assertEquals("a aa a", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
                f.editor.redo();
                f.editor.redo();
                assertEquals("long longlong long!", f.editor.getText());
            }
            return null;
        });
    }

    @Test void emptyReplacementDeletesAndIdenticalReplacementAddsNoUndo() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("中😀中")) {
                f.bar.showReplace();
                f.scan("中");
                f.replacement().setText("中");
                f.button("all").fire();
                f.edits.getLast().publish();
                assertFalse(f.editor.isUndoAvailable());
                assertTrue(f.feedback().contains("内容未变化"));
                f.replacement().clear();
                f.button("all").fire();
                f.edits.getLast().publish();
                assertEquals("😀", f.editor.getText());
                f.editor.undo();
                assertEquals("中😀中", f.editor.getText());
            }
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "query", "case", "words", "replacement", "selection", "hide", "hideWithoutFocus", "collapse", "close", "freeze", "disabled", "thaw", "focus", "scene"})
    void staleCandidatesNeverOverwriteNewEditorState(String change) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("a a")) {
                f.bar.showReplace();
                f.scan("a");
                f.replacement().setText("b");
                f.button("all").fire();
                var pending = f.edits.getLast();
                switch (change) {
                    case "edit" -> f.editor.replaceText("new SQL");
                    case "query" -> f.query().setText("other");
                    case "case" -> ((CheckBox) f.bar.getNode().lookup("#sql-find-match-case")).setSelected(true);
                    case "words" -> ((CheckBox) f.bar.getNode().lookup("#sql-find-whole-word")).setSelected(true);
                    case "replacement" -> f.replacement().setText("new value");
                    case "selection" -> f.editor.selectRange(0, 1);
                    case "hide" -> f.bar.hide();
                    case "hideWithoutFocus" -> f.bar.hide(false);
                    case "collapse" -> ((Button) f.bar.getNode().lookup("#sql-find-replace-toggle")).fire();
                    case "close" -> f.bar.close();
                    case "freeze" -> f.editable = false;
                    case "disabled" -> f.editor.setEditable(false);
                    case "thaw" -> { f.editor.setEditable(false); f.editor.setEditable(true); }
                    case "focus" -> { f.outside.requestFocus(); f.query().requestFocus(); }
                    case "scene" -> f.host.getChildren().remove(f.editor);
                    default -> fail(change);
                }
                if (!change.equals("freeze") && !change.equals("disabled")) assertTrue(pending.future.isCancelled(), change);
                pending.publish(); // A callback already queued before cancellation must also be harmless.
                pending.failure.accept(new IllegalStateException("private SQL"));
                assertEquals(change.equals("edit") ? "new SQL" : "a a", f.editor.getText());
                assertFalse(f.feedback().contains("private SQL"));
                if (!change.equals("edit")) assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void incompleteMatchesAndOversizedInputCannotSchedulePartialReplacement() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("x ".repeat(10001))) {
                f.bar.showReplace();
                f.scan("x");
                assertTrue(f.button("all").isDisabled());
                f.button("all").fire();
                assertTrue(f.edits.isEmpty());
                f.editor.selectRange(0, 1);
                assertFalse(f.button("current").isDisabled(), "known selected match can still be edited");
                f.replacement().setText("y".repeat(4097));
                assertTrue(f.button("current").isDisabled());
                assertTrue(f.feedback().contains("4096"));
                f.button("current").fire();
                assertTrue(f.edits.isEmpty());
                assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void noMatchesAndFailedOrRejectedWorkLeaveSqlIntactAndPermitRetry() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("a a")) {
                f.bar.showReplace();
                f.scan("absent");
                assertTrue(f.button("all").isDisabled());
                f.scan("a");
                f.replacement().setText("b");
                f.button("all").fire();
                f.edits.getLast().failure.accept(new IllegalStateException("private SQL"));
                assertEquals("a a", f.editor.getText());
                assertEquals("替换失败，未修改 SQL，请重试。", f.feedback());
                assertFalse(f.button("all").isDisabled());
                f.reject = true;
                f.button("all").fire();
                assertEquals("替换暂不可用，未修改 SQL。", f.feedback());
                f.reject = false;
                f.button("all").fire();
                f.edits.getLast().publish();
                assertEquals("b b", f.editor.getText());
            }
            return null;
        });
    }

    private static final class Job<T> {
        final Callable<T> work;
        final Consumer<T> success;
        final Consumer<Throwable> failure;
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Job(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
            this.work = work; this.success = success; this.failure = failure;
        }
        void publish() throws Exception { success.accept(work.call()); }
    }

    private static final class Fixture implements AutoCloseable {
        final CodeArea editor = new CodeArea();
        final List<Job<SqlTextSearch.Result>> scans = new ArrayList<>();
        final List<Job<SqlTextReplacement.Edit>> edits = new ArrayList<>();
        final SqlFindBar bar;
        final Button outside = new Button("another tab");
        final VBox host;
        boolean editable = true, reject;
        Fixture(String sql) {
            editor.replaceText(sql);
            editor.getUndoManager().forgetHistory();
            bar = new SqlFindBar(editor, (work, success, failure) -> {
                var job = new Job<>(work, success, failure); scans.add(job); return job.future;
            }, (work, success, failure) -> {
                if (reject) throw new IllegalStateException("private backend detail");
                var job = new Job<>(work, success, failure); edits.add(job); return job.future;
            }, () -> editable);
            host = new VBox(bar.getNode(), editor, outside);
            new Scene(host, 480, 600);
            host.applyCss(); host.layout();
        }
        TextField query() { return (TextField) bar.getNode().lookup("#sql-find-query"); }
        TextField replacement() { return (TextField) bar.getNode().lookup("#sql-replace-text"); }
        Button button(String name) { return (Button) bar.getNode().lookup("#sql-replace-" + name); }
        String feedback() { return ((Label) bar.getNode().lookup("#sql-replace-status")).getText(); }
        void scan(String query) throws Exception {
            query().setText(query);
            query().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            scans.getLast().publish();
        }
        @Override public void close() { bar.detachUi(); }
    }
}
