package com.datacube.fx;

import com.datacube.config.RecentSqlFiles;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns one SQL editor's file binding, saved baseline, and close decision. */
public final class SqlScriptFileController implements AutoCloseable {
    private static final String CHANGED_FEEDBACK = "文件已被外部修改，未覆盖磁盘内容。";
    private static final String TOO_LARGE_FEEDBACK = "SQL 文件超过 8 MiB，未保存。";
    private static final String INVALID_TARGET_FEEDBACK = "保存位置无效，请重新选择。";
    private static final String BUSY_FEEDBACK = "文件保存正在进行中，请稍后重试。";
    private static final String TARGET_BUSY_FEEDBACK = "目标文件正在被其他保存操作使用，请稍后重试。";
    private static final String GENERIC_FEEDBACK = "SQL 文件保存失败，磁盘内容未被替换。";

    public enum CloseDecision { SAVE, DISCARD, CANCEL }

    /** Small deterministic worker seam; production delegates to the editor's shared task scope. */
    interface Submitter {
        <T> void submit(Callable<T> operation, Consumer<? super T> success,
                Consumer<? super Throwable> failure);
    }

    private final CodeArea editor;
    private final SqlScriptFileStore store;
    private final RecentSqlFiles recent;
    private final Supplier<Window> owner;
    private final Consumer<String> titleConsumer;
    private final String fallbackTitle;
    private final Function<Window, Path> savePathChooser;
    private final BiPredicate<Window, Path> overwriteConfirmer;
    private final Function<Window, CloseDecision> closeDecisionProvider;
    private final Consumer<String> feedback;
    private final Submitter submitter;
    private final ReadOnlyBooleanWrapper busyProperty = new ReadOnlyBooleanWrapper();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final ChangeListener<String> textListener = (observable, oldText, newText) -> refreshTitle();

    private SqlScriptDocument document;
    private boolean installed;
    private boolean listenerAttached;
    private volatile boolean busy;
    private volatile CompletableFuture<Boolean> pending;

    SqlScriptFileController(CodeArea editor, SqlScriptFileStore store, RecentSqlFiles recent,
            FxTaskScope tasks, Supplier<Window> owner, Consumer<String> titleConsumer,
            String fallbackTitle, Function<Window, Path> savePathChooser,
            BiPredicate<Window, Path> overwriteConfirmer,
            Function<Window, CloseDecision> closeDecisionProvider, Consumer<String> feedback) {
        this(editor, store, recent, tasks, owner, titleConsumer, fallbackTitle, savePathChooser,
                overwriteConfirmer, closeDecisionProvider, feedback, new ScopeSubmitter(tasks));
    }

    SqlScriptFileController(CodeArea editor, SqlScriptFileStore store, RecentSqlFiles recent,
            FxTaskScope tasks, Supplier<Window> owner, Consumer<String> titleConsumer,
            String fallbackTitle, Function<Window, Path> savePathChooser,
            BiPredicate<Window, Path> overwriteConfirmer,
            Function<Window, CloseDecision> closeDecisionProvider, Consumer<String> feedback,
            Submitter submitter) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.store = Objects.requireNonNull(store, "store");
        this.recent = Objects.requireNonNull(recent, "recent");
        Objects.requireNonNull(tasks, "tasks");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.titleConsumer = Objects.requireNonNull(titleConsumer, "titleConsumer");
        this.fallbackTitle = Objects.requireNonNull(fallbackTitle, "fallbackTitle");
        this.savePathChooser = Objects.requireNonNull(savePathChooser, "savePathChooser");
        this.overwriteConfirmer = Objects.requireNonNull(overwriteConfirmer, "overwriteConfirmer");
        this.closeDecisionProvider = Objects.requireNonNull(closeDecisionProvider,
                "closeDecisionProvider");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    public void install(SqlScriptFileStore.Loaded initial) {
        requireFx("install");
        if (closed.get()) throw new IllegalStateException("SQL file controller is closed");
        if (installed) throw new IllegalStateException("SQL file controller is already installed");
        if (initial == null) {
            document = new SqlScriptDocument(editor.getText());
        } else {
            editor.replaceText(initial.text());
            document = new SqlScriptDocument();
            document.attach(initial);
        }
        editor.textProperty().addListener(textListener);
        listenerAttached = true;
        installed = true;
        refreshTitle();
    }

    public CompletionStage<Boolean> save() {
        requireFx("save");
        if (!readyForRequest()) return CompletableFuture.completedFuture(false);
        if (document.target() == null) return startSaveAs();
        CompletableFuture<Boolean> result = beginOperation();
        if (result.isDone()) return result;
        submitSave(document.target(), editor.getText(), generation.get(), result);
        return result;
    }

    public CompletionStage<Boolean> saveAs() {
        requireFx("saveAs");
        if (!readyForRequest()) return CompletableFuture.completedFuture(false);
        return startSaveAs();
    }

    private CompletionStage<Boolean> startSaveAs() {
        final Path chosen;
        try {
            chosen = savePathChooser.apply(owner.get());
        } catch (RuntimeException failure) {
            report(INVALID_TARGET_FEEDBACK);
            return CompletableFuture.completedFuture(false);
        }
        if (chosen == null) return CompletableFuture.completedFuture(false);

        CompletableFuture<Boolean> result = beginOperation();
        if (result.isDone()) return result;
        long token = generation.get();
        String snapshot = editor.getText();
        submit(token, result, () -> store.capture(chosen), target -> {
            if (document.path() != null && document.path().equals(target.path())) {
                submitSave(document.target(), snapshot, token, result);
                return;
            }
            if (target.exists()) {
                final boolean confirmed;
                try {
                    confirmed = overwriteConfirmer.test(owner.get(), target.path());
                } catch (RuntimeException failure) {
                    fail(token, result, GENERIC_FEEDBACK);
                    return;
                }
                if (!confirmed) {
                    finish(token, result, false);
                    return;
                }
            }
            submitSave(target, snapshot, token, result);
        });
        return result;
    }

    private void submitSave(SqlScriptFileStore.Target target, String snapshot, long token,
            CompletableFuture<Boolean> result) {
        submit(token, result, () -> {
            SqlScriptFileStore.Loaded saved = store.save(target, snapshot);
            synchronized (lifecycleLock) {
                if (!closed.get() && generation.get() == token) recent.record(saved.path());
            }
            return saved;
        }, saved -> {
            document.saved(saved);
            refreshTitle();
            finish(token, result, true);
        });
    }

    private <T> void submit(long token, CompletableFuture<Boolean> result, Callable<T> operation,
            Consumer<T> success) {
        try {
            submitter.submit(operation, value -> {
                if (!current(token, result)) return;
                success.accept(value);
            }, failure -> {
                if (!current(token, result)) return;
                fail(token, result, feedbackFor(failure));
            });
        } catch (RuntimeException failure) {
            fail(token, result, GENERIC_FEEDBACK);
        }
    }

    public CompletionStage<CloseGuardOutcome> guardClose(
            Supplier<CompletionStage<CloseGuardOutcome>> proceed) {
        requireFx("guardClose");
        Objects.requireNonNull(proceed, "proceed");
        if (closed.get() || busy || !installed) {
            return CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
        }
        if (!document.dirty(editor.getText())) return invokeGuard(proceed);

        final CloseDecision decision;
        try {
            decision = closeDecisionProvider.apply(owner.get());
        } catch (RuntimeException failure) {
            report(GENERIC_FEEDBACK);
            return CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
        }
        if (decision == null || decision == CloseDecision.CANCEL) {
            return CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
        }
        if (decision == CloseDecision.DISCARD) return invokeGuard(proceed);
        return save().thenCompose(saved -> {
            if (!Boolean.TRUE.equals(saved) || closed.get() || document.dirty(editor.getText())) {
                return CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
            }
            return invokeGuard(proceed);
        });
    }

    private CompletionStage<CloseGuardOutcome> invokeGuard(
            Supplier<CompletionStage<CloseGuardOutcome>> proceed) {
        try {
            CompletionStage<CloseGuardOutcome> stage = proceed.get();
            return stage == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("close guard returned null"))
                    : stage;
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public ReadOnlyBooleanProperty busyProperty() {
        return busyProperty.getReadOnlyProperty();
    }

    public boolean isBusy() {
        return busy;
    }

    /** FX-only listener cleanup, intentionally separate from thread-safe resource invalidation. */
    public void detachUi() {
        requireFx("detachUi");
        if (!listenerAttached) return;
        editor.textProperty().removeListener(textListener);
        listenerAttached = false;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            generation.incrementAndGet();
            busy = false;
        }
        CompletableFuture<Boolean> exposed = pending;
        if (exposed != null) exposed.complete(false);
    }

    private boolean readyForRequest() {
        if (closed.get() || !installed) return false;
        if (!busy) return true;
        report(BUSY_FEEDBACK);
        return false;
    }

    private CompletableFuture<Boolean> beginOperation() {
        if (busy || closed.get()) {
            if (busy) report(BUSY_FEEDBACK);
            return CompletableFuture.completedFuture(false);
        }
        busy = true;
        busyProperty.set(true);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        pending = result;
        return result;
    }

    private boolean current(long token, CompletableFuture<Boolean> result) {
        return !closed.get() && generation.get() == token && pending == result && !result.isDone();
    }

    private void finish(long token, CompletableFuture<Boolean> result, boolean value) {
        if (!current(token, result)) return;
        pending = null;
        busy = false;
        busyProperty.set(false);
        result.complete(value);
    }

    private void fail(long token, CompletableFuture<Boolean> result, String message) {
        if (!current(token, result)) return;
        report(message);
        finish(token, result, false);
    }

    private void refreshTitle() {
        if (closed.get() || document == null) return;
        titleConsumer.accept(document.title(fallbackTitle, editor.getText()));
    }

    private void report(String message) {
        if (closed.get()) return;
        try {
            feedback.accept(message);
        } catch (RuntimeException ignored) {
            // Fixed feedback is best effort and cannot change the persistence result.
        }
    }

    private static String feedbackFor(Throwable failure) {
        if (failure instanceof SqlScriptFileStore.Failure storeFailure) {
            return switch (storeFailure.code()) {
                case CHANGED -> CHANGED_FEEDBACK;
                case TOO_LARGE -> TOO_LARGE_FEEDBACK;
                case INVALID_TARGET -> INVALID_TARGET_FEEDBACK;
                case BUSY -> TARGET_BUSY_FEEDBACK;
                case READ, INVALID_UTF8, WRITE, PUBLISH, CLEANUP -> GENERIC_FEEDBACK;
            };
        }
        return GENERIC_FEEDBACK;
    }

    private static void requireFx(String operation) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(operation + " must run on the FX Application Thread");
        }
    }

    private static final class ScopeSubmitter implements Submitter {
        private final FxTaskScope scope;

        private ScopeSubmitter(FxTaskScope scope) {
            this.scope = Objects.requireNonNull(scope, "scope");
        }

        @Override
        public <T> void submit(Callable<T> operation, Consumer<? super T> success,
                Consumer<? super Throwable> failure) {
            scope.submit(operation, success, failure);
        }
    }
}
