package com.datacube.fx;

import com.datacube.sqleditor.SqlTextSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

class SqlFindBarTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void wholeWordsShareCountsAndNavigationWithoutMovingSelectionOnToggle() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("id user_id id2 ID t.id")) {
                f.editor.moveTo(0);
                f.bar.show();
                f.startScan("id");
                f.jobs.getLast().publish();
                assertEquals("共 5 处", f.status());
                CheckBox words = (CheckBox) f.bar.getNode().lookup("#sql-find-whole-word");
                assertNotNull(words, "find needs an explicit whole-word option");
                assertFalse(words.isSelected());
                f.editor.selectRange(10, 8);
                words.setSelected(true);
                assertEquals(10, f.editor.getAnchor());
                assertEquals(8, f.editor.getCaretPosition());
                assertTrue(f.button("next").isDisabled());
                f.startScan("id");
                f.jobs.getLast().publish();
                assertEquals("共 3 处", f.status());
                f.button("next").fire();
                assertEquals(15, f.editor.getSelection().getStart());
                assertEquals("ID", f.editor.getSelectedText());
                f.button("next").fire();
                assertEquals(20, f.editor.getSelection().getStart());
                f.button("next").fire();
                assertEquals(0, f.editor.getSelection().getStart());
                assertEquals("1 / 3 · 已回到开头", f.status());
                f.button("previous").fire();
                assertEquals(20, f.editor.getSelection().getStart());
                assertEquals("3 / 3 · 已回到末尾", f.status());
                assertEquals("id user_id id2 ID t.id", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void findingAPhraseUpdatesExecutionScopeAndClosingFindDoesNotClearTheSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("select a; select b;");
                 var scope = new SqlEditorScopeBar(f.editor,
                         new com.datacube.config.ShortcutSettings(directory.resolve("shortcuts.properties")))) {
                f.editor.moveTo(0);
                assertEquals("执行全部 (F5)", scope.executeLabelProperty().get());
                f.bar.show();
                f.startScan("select");
                f.jobs.getLast().publish();
                f.button("next").fire();
                assertEquals("select", f.editor.getSelectedText());
                assertEquals("执行选中 (F5)", scope.executeLabelProperty().get());
                assertTrue(((Label) scope.getNode().lookup("#sql-editor-position")).getText().endsWith("选中 6"));
                f.button("close").fire();
                assertEquals("执行选中 (F5)", scope.executeLabelProperty().get(),
                        "closing find retains the selection that execution will consume");
                f.editor.moveTo(f.editor.getSelection().getEnd());
                assertEquals("执行全部 (F5)", scope.executeLabelProperty().get());
                assertEquals("select a; select b;", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void typingIsDebouncedWithoutDoingWorkForEveryKeystroke() throws Exception {
        Fixture f = FxUiTestSupport.call(() -> new Fixture("abc abc"));
        try {
            FxUiTestSupport.call(() -> {
                f.bar.show();
                f.query().setText("a");
                f.query().setText("ab");
                f.query().setText("abc");
                assertTrue(f.jobs.isEmpty(), "typing must not synchronously submit every intermediate query");
                return null;
            });
            assertTrue(f.submitted.await(5, TimeUnit.SECONDS), "find debounce did not submit");
            FxUiTestSupport.call(() -> {
                assertEquals(1, f.jobs.size());
                f.jobs.getLast().publish();
                assertEquals("共 2 处", f.status());
                f.bar.detachUi();
                f.editor.replaceText("closed editor");
                f.startScan("editor");
                assertEquals(1, f.jobs.size(), "detached find must not schedule more work");
                return null;
            });
        } finally { FxUiTestSupport.call(() -> { f.close(); return null; }); }
    }

    @Test void wholeWordToggleInvalidatesLateCallbacksAndCombinesWithCaseWithoutLeakingAcrossEditors() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("id id2 ID"); var other = new Fixture("id2")) {
                f.editor.moveTo(0);
                f.bar.show(); f.startScan("id");
                Job old = f.jobs.getLast();
                CheckBox words = (CheckBox) f.bar.getNode().lookup("#sql-find-whole-word");
                words.setSelected(true);
                assertTrue(old.future.isCancelled());
                f.startScan("id"); f.jobs.getLast().publish();
                old.publish(); old.failure.accept(new IllegalStateException("private SQL"));
                assertEquals("共 2 处", f.status());
                assertEquals(0, f.editor.getCaretPosition());
                ((CheckBox) f.bar.getNode().lookup("#sql-find-match-case")).setSelected(true);
                f.startScan("id"); f.jobs.getLast().publish();
                assertEquals("共 1 处", f.status());
                f.bar.hide(); f.bar.showReplace();
                assertTrue(words.isSelected(), "reopening keeps this editor's option");
                f.startScan("id"); f.jobs.getLast().publish();
                assertEquals("共 1 处", f.status());
                assertFalse(((CheckBox) other.bar.getNode().lookup("#sql-find-whole-word")).isSelected());
                words.setSelected(false); f.startScan("id"); f.jobs.getLast().publish();
                assertEquals("共 2 处", f.status(), "turning off restores substring matches with case still on");
                assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void navigationWrapsBothWaysWithoutEditingTheScript(boolean replacementEnabled) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("select a; SELECT b; select c;", replacementEnabled)) {
                f.editor.selectRange(0, 6);
                f.bar.show();
                assertEquals("select", f.query().getText());
                f.startScan("select");
                f.jobs.getLast().publish();
                assertEquals("共 3 处", f.status());
                assertEquals(0, f.editor.getSelection().getStart(), "scanning must not move the selection");
                f.button("next").fire();
                assertEquals(10, f.editor.getSelection().getStart());
                assertEquals("SELECT", f.editor.getSelectedText());
                assertEquals("2 / 3", f.status());
                f.query().fireEvent(key(KeyCode.ENTER, false));
                assertEquals(20, f.editor.getSelection().getStart());
                f.query().fireEvent(key(KeyCode.ENTER, false));
                assertEquals(0, f.editor.getSelection().getStart());
                assertEquals("1 / 3 · 已回到开头", f.status());
                f.query().fireEvent(key(KeyCode.ENTER, true));
                assertEquals(20, f.editor.getSelection().getStart());
                assertEquals("3 / 3 · 已回到末尾", f.status());
                f.button("previous").fire();
                assertEquals(10, f.editor.getSelection().getStart());
                assertEquals("select a; SELECT b; select c;", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable(), "find must not add an edit to the undo stack");
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void changingCaseAndClearingOrOversizingQueryInvalidateActions(boolean replacementEnabled) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("select SELECT", replacementEnabled)) {
                f.bar.show();
                f.startScan("select");
                f.jobs.getLast().publish();
                assertEquals("共 2 处", f.status());
                ((CheckBox) f.bar.getNode().lookup("#sql-find-match-case")).setSelected(true);
                f.query().fireEvent(key(KeyCode.ENTER, false));
                f.jobs.getLast().publish();
                assertEquals("共 1 处", f.status());
                f.startScan("absent");
                f.jobs.getLast().publish();
                assertEquals("无匹配", f.status());
                assertTrue(f.button("next").isDisabled());
                assertTrue(f.button("previous").isDisabled());
                int submissions = f.jobs.size();
                f.startScan("");
                assertEquals("输入查找内容", f.status());
                f.startScan("x".repeat(1025));
                assertEquals("查找内容最多 1024 个字符", f.status());
                assertEquals(submissions, f.jobs.size(), "invalid queries must not be submitted or truncated");
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void editingCancelsOldScanAndLateSuccessCannotSelectStaleOffsets(boolean replacementEnabled) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("select abc", replacementEnabled)) {
                f.bar.show();
                f.startScan("abc");
                Job old = f.jobs.getLast();
                f.editor.replaceText("abc abc");
                assertTrue(old.future.isCancelled());
                f.startScan("abc");
                f.jobs.getLast().publish();
                f.editor.moveTo(0);
                old.publish(); // Simulate a callback queued just before Future.cancel.
                assertEquals("共 2 处", f.status());
                assertEquals(0, f.editor.getCaretPosition());
                f.button("next").fire();
                assertEquals(0, f.editor.getSelection().getStart());
                assertEquals("abc", f.editor.getSelectedText());
                assertEquals("abc abc", f.editor.getText());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void hideAndCloseSuppressLateCallbacksAndDoNotStartHiddenWork(boolean replacementEnabled) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("abc", replacementEnabled)) {
                f.bar.show();
                f.startScan("abc");
                Job pending = f.jobs.getLast();
                f.query().fireEvent(key(KeyCode.ESCAPE, false));
                assertFalse(f.bar.getNode().isVisible());
                assertFalse(f.bar.getNode().isManaged());
                assertTrue(pending.future.isCancelled());
                pending.publish();
                f.editor.replaceText("hidden edit");
                assertEquals(1, f.jobs.size());
                f.editor.deselect();
                f.bar.show();
                f.startScan("hidden");
                pending = f.jobs.getLast();
                f.bar.close();
                f.bar.detachUi();
                pending.publish();
                pending.failure.accept(new IllegalStateException("private content"));
                f.bar.show();
                assertFalse(f.bar.getNode().isVisible());
                assertFalse(f.status().contains("private"));
                assertEquals("hidden edit", f.editor.getText());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void truncationAndFailureFeedbackAreExplicitAndNeverExposeBackendDetails(boolean replacementEnabled) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("x ".repeat(10001), replacementEnabled)) {
                f.bar.show();
                f.startScan("x");
                f.jobs.getLast().publish();
                assertEquals("共 10000+ 处 · 仅定位前 10000 处，请缩小范围", f.status());
                f.startScan("y");
                f.jobs.getLast().failure.accept(new IllegalStateException("SECRET SQL"));
                assertEquals("查找失败，请重新输入后重试", f.status());
                assertTrue(f.button("next").isDisabled());
            }
            return null;
        });
    }

    @Test void readOnlyModeCannotExposeReplacementEvenWhenEditorBecomesEditable() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("id ID user_id", false)) {
                f.bar.showReplace();
                assertFalse(f.bar.getNode().isVisible(), "replacement cannot even open read-only find");
                f.bar.show();
                assertEquals("查找当前 DDL…", f.query().getPromptText());
                assertNull(f.bar.getNode().lookup("#sql-find-replace-toggle"));
                assertNull(f.bar.getNode().lookup("#sql-replace-pane"));
                assertTrue(f.editor.isEditable(), "presentation mode must guard independently of editor state");
                f.startScan("id"); f.jobs.getLast().publish();
                assertEquals("共 3 处", f.status());
                ((CheckBox) f.bar.getNode().lookup("#sql-find-whole-word")).setSelected(true);
                f.startScan("id"); f.jobs.getLast().publish();
                assertEquals("共 2 处", f.status());
                f.bar.showReplace();
                assertNull(f.bar.getNode().lookup("#sql-replace-text"));
                assertEquals("id ID user_id", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    private static KeyEvent key(KeyCode code, boolean shift) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false);
    }

    private static final class Job {
        final Callable<SqlTextSearch.Result> work;
        final Consumer<SqlTextSearch.Result> success;
        final Consumer<Throwable> failure;
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Job(Callable<SqlTextSearch.Result> work, Consumer<SqlTextSearch.Result> success, Consumer<Throwable> failure) {
            this.work = work; this.success = success; this.failure = failure;
        }
        void publish() throws Exception { success.accept(work.call()); }
    }

    private static final class Fixture implements AutoCloseable {
        final List<Job> jobs = new ArrayList<>();
        final CountDownLatch submitted = new CountDownLatch(1);
        final CodeArea editor = new CodeArea();
        final SqlFindBar bar;
        Fixture(String text) {
            this(text, true);
        }
        Fixture(String text, boolean replacementEnabled) {
            editor.replaceText(text);
            editor.getUndoManager().forgetHistory();
            bar = new SqlFindBar(editor, (work, success, failure) -> {
                Job job = new Job(work, success, failure);
                jobs.add(job);
                submitted.countDown();
                return job.future;
            }, (work, success, failure) -> { throw new AssertionError("find must not submit replacements"); },
                    () -> true, replacementEnabled);
            VBox host = new VBox(bar.getNode(), editor);
            new Scene(host, 480, 400);
            host.applyCss();
            host.layout();
        }
        TextField query() { return (TextField) bar.getNode().lookup("#sql-find-query"); }
        Button button(String name) { return (Button) bar.getNode().lookup("#sql-find-" + name); }
        String status() { return ((Label) bar.getNode().lookup("#sql-find-status")).getText(); }
        void startScan(String query) {
            query().setText(query);
            query().fireEvent(key(KeyCode.ENTER, false));
        }
        @Override public void close() { bar.detachUi(); }
    }
}
