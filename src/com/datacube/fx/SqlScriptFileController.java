package com.datacube.fx;

import com.datacube.config.RecentSqlFiles;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    private final Runnable beforeSubmit;
    private final Runnable beforeSettlement;
    private final SqlFileTabRegistry registry;
    private final SqlFileTabRegistry.Owner registryOwner;
    private final ReadOnlyBooleanWrapper busyProperty = new ReadOnlyBooleanWrapper();
    private final Object lifecycleLock = new Object();
    private SqlScriptDocument document;
    private Runnable unsubscribeTextChanges = () -> { };
    private final java.util.function.Consumer<Object> textListener = this::plainTextChanged;

    private boolean installed;
    private boolean listenerAttached;
    private volatile boolean closed;
    private long generation;
    private volatile boolean busy;
    private CompletableFuture<Boolean> pending;

    SqlScriptFileController(CodeArea editor, SqlScriptFileStore store, RecentSqlFiles recent,
            FxTaskScope tasks, Supplier<Window> owner, Consumer<String> titleConsumer,
            String fallbackTitle, Function<Window, Path> savePathChooser,
            BiPredicate<Window, Path> overwriteConfirmer,
            Function<Window, CloseDecision> closeDecisionProvider, Consumer<String> feedback) {
        this(editor, store, recent, tasks, owner, titleConsumer, fallbackTitle, savePathChooser,
                overwriteConfirmer, closeDecisionProvider, feedback, new ScopeSubmitter(tasks),
                () -> { }, () -> { });
    }

    SqlScriptFileController(CodeArea editor, SqlScriptFileStore store, RecentSqlFiles recent,
            FxTaskScope tasks, Supplier<Window> owner, Consumer<String> titleConsumer,
            String fallbackTitle, Function<Window, Path> savePathChooser,
            BiPredicate<Window, Path> overwriteConfirmer,
            Function<Window, CloseDecision> closeDecisionProvider, Consumer<String> feedback,
            SqlFileTabRegistry registry, SqlFileTabRegistry.Owner registryOwner) {
        this(editor, store, recent, tasks, owner, titleConsumer, fallbackTitle, savePathChooser,
                overwriteConfirmer, closeDecisionProvider, feedback, new ScopeSubmitter(tasks),
                () -> { }, () -> { }, registry, registryOwner);
    }

    SqlScriptFileController(CodeArea editor, SqlScriptFileStore store, RecentSqlFiles recent,
            FxTaskScope tasks, Supplier<Window> owner, Consumer<String> titleConsumer,
            String fallbackTitle, Function<Window, Path> savePathChooser,
            BiPredicate<Window, Path> overwriteConfirmer,
            Function<Window, CloseDecision> closeDecisionProvider, Consumer<String> feedback,
            Submitter submitter) {
        this(editor, store, recent, tasks, owner, titleConsumer, fallbackTitle, savePathChooser,
                overwriteConfirmer, closeDecisionProvider, feedback, submitter,
                () -> { }, () -> { });
    }

    SqlScriptFileController(CodeArea editor, SqlScriptFileStore store, RecentSqlFiles recent,
            FxTaskScope tasks, Supplier<Window> owner, Consumer<String> titleConsumer,
            String fallbackTitle, Function<Window, Path> savePathChooser,
            BiPredicate<Window, Path> overwriteConfirmer,
            Function<Window, CloseDecision> closeDecisionProvider, Consumer<String> feedback,
            Submitter submitter, Runnable beforeSubmit) {
        this(editor, store, recent, tasks, owner, titleConsumer, fallbackTitle, savePathChooser,
                overwriteConfirmer, closeDecisionProvider, feedback, submitter,
                beforeSubmit, () -> { });
    }

    SqlScriptFileController(CodeArea editor, SqlScriptFileStore store, RecentSqlFiles recent,
            FxTaskScope tasks, Supplier<Window> owner, Consumer<String> titleConsumer,
            String fallbackTitle, Function<Window, Path> savePathChooser,
            BiPredicate<Window, Path> overwriteConfirmer,
            Function<Window, CloseDecision> closeDecisionProvider, Consumer<String> feedback,
            Submitter submitter, Runnable beforeSubmit, Runnable beforeSettlement) {
        this(editor, store, recent, tasks, owner, titleConsumer, fallbackTitle, savePathChooser,
                overwriteConfirmer, closeDecisionProvider, feedback, submitter, beforeSubmit,
                beforeSettlement, null, null);
    }

    SqlScriptFileController(CodeArea editor, SqlScriptFileStore store, RecentSqlFiles recent,
            FxTaskScope tasks, Supplier<Window> owner, Consumer<String> titleConsumer,
            String fallbackTitle, Function<Window, Path> savePathChooser,
            BiPredicate<Window, Path> overwriteConfirmer,
            Function<Window, CloseDecision> closeDecisionProvider, Consumer<String> feedback,
            Submitter submitter, Runnable beforeSubmit, Runnable beforeSettlement,
            SqlFileTabRegistry registry, SqlFileTabRegistry.Owner registryOwner) {
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
        this.beforeSubmit = Objects.requireNonNull(beforeSubmit, "beforeSubmit");
        this.beforeSettlement = Objects.requireNonNull(beforeSettlement, "beforeSettlement");
        if ((registry == null) != (registryOwner == null)) {
            throw new IllegalArgumentException("registry and owner must be supplied together");
        }
        this.registry = registry;
        this.registryOwner = registryOwner;
    }

    public void install(SqlScriptFileStore.Loaded initial) {
        requireFx("install");
        if (closed) throw new IllegalStateException("SQL file controller is closed");
        if (installed) throw new IllegalStateException("SQL file controller is already installed");
        if (initial == null) {
            document = new SqlScriptDocument(editor.getText());
        } else {
            document = new SqlScriptDocument();
            document.attach(initial);
            editor.replaceText(document.normalizedText());
        }
        subscribeToPlainTextChanges();
        listenerAttached = true;
        installed = true;
        refreshTitle();
    }

    public CompletionStage<Boolean> save() {
        requireFx("save");
        Operation operation = beginOperation();
        if (operation == null) return CompletableFuture.completedFuture(false);
        if (document.target() == null) return startSaveAs(operation);
        submitSave(document.target(), document.physicalText(), operation);
        return operation.result();
    }

    public CompletionStage<Boolean> saveAs() {
        requireFx("saveAs");
        Operation operation = beginOperation();
        if (operation == null) return CompletableFuture.completedFuture(false);
        return startSaveAs(operation);
    }

    private CompletionStage<Boolean> startSaveAs(Operation operation) {
        final Path chosen;
        try {
            chosen = savePathChooser.apply(owner.get());
        } catch (RuntimeException failure) {
            fail(operation, INVALID_TARGET_FEEDBACK);
            return operation.result();
        }
        if (chosen == null) {
            finish(operation, false);
            return operation.result();
        }

        String snapshot = document.physicalText();
        submit(operation, () -> store.capture(chosen), target -> {
            if (document.path() != null && document.path().equals(target.path())) {
                submitSave(document.target(), snapshot, operation);
                return;
            }
            if (registry != null) {
                SqlFileTabRegistry.Claim claim = registry.claim(registryOwner, target.path());
                if (claim == SqlFileTabRegistry.Claim.COLLISION) {
                    finish(operation, false);
                    return;
                }
                operation.claimedPath = target.path();
            }
            if (target.exists()) {
                final boolean confirmed;
                try {
                    confirmed = overwriteConfirmer.test(owner.get(), target.path());
                } catch (RuntimeException failure) {
                    fail(operation, GENERIC_FEEDBACK);
                    return;
                }
                if (!confirmed) {
                    finish(operation, false);
                    return;
                }
            }
            submitSave(target, snapshot, operation);
        });
        return operation.result();
    }

    private void submitSave(SqlScriptFileStore.Target target, String snapshot, Operation operation) {
        submit(operation, () -> {
            SqlScriptFileStore.Loaded saved = store.save(target, snapshot);
            synchronized (lifecycleLock) {
                if (currentLocked(operation)) recent.record(operation.recentAdmission, saved.path());
            }
            return saved;
        }, saved -> {
            if (registry != null && operation.claimedPath != null) {
                registry.commit(registryOwner, saved.path());
                operation.claimedPath = null;
            }
            document.saved(saved);
            refreshTitle();
            finish(operation, true);
        });
    }

    private <T> void submit(Operation admission, Callable<T> operation,
            Consumer<T> success) {
        try {
            beforeSubmit.run();
        } catch (RuntimeException failure) {
            fail(admission, GENERIC_FEEDBACK);
            return;
        }
        RuntimeException rejected = null;
        synchronized (lifecycleLock) {
            if (!currentLocked(admission)) return;
            try {
                submitter.submit(operation,
                        value -> settleSuccess(admission, value, success),
                        failure -> settleFailure(admission, failure));
            } catch (RuntimeException failure) {
                rejected = failure;
            }
        }
        if (rejected != null) fail(admission, GENERIC_FEEDBACK);
    }

    public CompletionStage<CloseGuardOutcome> guardClose(
            Supplier<CompletionStage<CloseGuardOutcome>> proceed) {
        requireFx("guardClose");
        Objects.requireNonNull(proceed, "proceed");
        if (unavailableForClose()) {
            return CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
        }
        if (!document.dirty()) return invokeGuard(proceed);

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
            if (!Boolean.TRUE.equals(saved) || closed || document.dirty()) {
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
        Throwable first = null;
        try {
            if (listenerAttached) {
                try {
                    unsubscribeTextChanges.run();
                } finally {
                    unsubscribeTextChanges = () -> { };
                    listenerAttached = false;
                }
            }
        } catch (Throwable failure) {
            first = failure;
        } finally {
            try {
                if (registry != null) registry.release(registryOwner);
            } catch (Throwable releaseFailure) {
                if (first == null) first = releaseFailure;
                else first.addSuppressed(releaseFailure);
            }
        }
        if (first instanceof RuntimeException runtime) throw runtime;
        if (first instanceof Error error) throw error;
        if (first != null) throw new IllegalStateException("Unable to detach SQL file UI", first);
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            generation++;
            busy = false;
            CompletableFuture<Boolean> exposed = pending;
            pending = null;
            if (exposed != null) exposed.complete(false);
        }
        clearBusyPropertyAfterClose();
    }

    private Operation beginOperation() {
        Operation operation;
        boolean rejectedBusy;
        synchronized (lifecycleLock) {
            if (closed || !installed) return null;
            rejectedBusy = busy;
            if (rejectedBusy) {
                operation = null;
            } else {
                busy = true;
                CompletableFuture<Boolean> result = new CompletableFuture<>();
                pending = result;
                operation = new Operation(generation, result, recent.recordAdmission());
            }
        }
        if (rejectedBusy) {
            report(BUSY_FEEDBACK);
            return null;
        }
        busyProperty.set(true);
        return operation;
    }

    private boolean currentLocked(Operation operation) {
        return !closed && generation == operation.generation()
                && pending == operation.result() && !operation.result().isDone();
    }

    private void finish(Operation operation, boolean value) {
        synchronized (lifecycleLock) {
            finishLocked(operation, value);
        }
    }

    private void fail(Operation operation, String message) {
        synchronized (lifecycleLock) {
            failLocked(operation, message);
        }
    }

    private <T> void settleSuccess(Operation operation, T value, Consumer<T> success) {
        if (!beforeSettlement(operation)) return;
        synchronized (lifecycleLock) {
            if (!currentLocked(operation)) return;
            try {
                success.accept(value);
            } catch (RuntimeException collaboratorFailure) {
                failLocked(operation, GENERIC_FEEDBACK);
            }
        }
    }

    private void settleFailure(Operation operation, Throwable failure) {
        if (!beforeSettlement(operation)) return;
        synchronized (lifecycleLock) {
            failLocked(operation, feedbackFor(failure));
        }
    }

    private boolean beforeSettlement(Operation operation) {
        try {
            beforeSettlement.run();
            return true;
        } catch (RuntimeException seamFailure) {
            fail(operation, GENERIC_FEEDBACK);
            return false;
        }
    }

    private void failLocked(Operation operation, String message) {
        if (!currentLocked(operation)) return;
        report(message);
        finishLocked(operation, false);
    }

    private void finishLocked(Operation operation, boolean value) {
        if (!currentLocked(operation)) return;
        if (!value && registry != null && operation.claimedPath != null) {
            registry.rollback(registryOwner, operation.claimedPath);
            operation.claimedPath = null;
        }
        pending = null;
        busy = false;
        try {
            busyProperty.set(false);
        } finally {
            operation.result().complete(value);
        }
    }

    private void refreshTitle() {
        boolean failed = false;
        synchronized (lifecycleLock) {
            if (closed || document == null) return;
            try {
                titleConsumer.accept(document.title(fallbackTitle));
            } catch (RuntimeException collaboratorFailure) {
                failed = true;
            }
        }
        if (failed) report(GENERIC_FEEDBACK);
    }

    /**
     * RichTextFX keeps ReactFX on the class path for the modular packaged application. Use its
     * public stream contract reflectively so module-info stays aligned with the merged runtime.
     */
    private void subscribeToPlainTextChanges() {
        try {
            Object stream = CodeArea.class.getMethod("plainTextChanges").invoke(editor);
            Method subscribe = stream.getClass().getMethod("subscribe", java.util.function.Consumer.class);
            Object subscription = subscribe.invoke(stream, textListener);
            Method unsubscribe = Class.forName("org.reactfx.Subscription").getMethod("unsubscribe");
            unsubscribeTextChanges = () -> {
                try {
                    unsubscribe.invoke(subscription);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Unable to detach SQL text change stream", failure);
                }
            };
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to subscribe to SQL text change stream", failure);
        }
    }

    private void plainTextChanged(Object change) {
        try {
            Class<?> type = change.getClass();
            int position = (Integer) type.getMethod("getPosition").invoke(change);
            String removed = (String) type.getMethod("getRemoved").invoke(change);
            String inserted = (String) type.getMethod("getInserted").invoke(change);
            document.editorTextChanged(position, removed, inserted);
            refreshTitle();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to read SQL text change", failure);
        }
    }

    private void report(String message) {
        synchronized (lifecycleLock) {
            if (closed) return;
            try {
                feedback.accept(message);
            } catch (RuntimeException ignored) {
                // Fixed feedback is best effort and cannot change the persistence result.
            }
        }
    }

    private boolean unavailableForClose() {
        synchronized (lifecycleLock) {
            return closed || busy || !installed;
        }
    }

    private void clearBusyPropertyAfterClose() {
        Runnable clear = () -> {
            try {
                busyProperty.set(false);
            } catch (RuntimeException ignored) {
                // UI listeners cannot reactivate the thread-safe lifecycle state.
            }
        };
        if (Platform.isFxApplicationThread()) {
            clear.run();
            return;
        }
        try {
            Platform.runLater(clear);
        } catch (IllegalStateException toolkitStopped) {
            // The UI is already unavailable; the thread-safe busy state is still false.
        }
    }

    static String feedbackFor(Throwable failure) {
        if (failure instanceof SqlScriptFileStore.Failure storeFailure) {
            return switch (storeFailure.code()) {
                case CHANGED -> CHANGED_FEEDBACK;
                case TOO_LARGE -> TOO_LARGE_FEEDBACK;
                case INVALID_TARGET -> INVALID_TARGET_FEEDBACK;
                case BUSY -> TARGET_BUSY_FEEDBACK;
                case READ, INVALID_UTF8, WRITE, PUBLISH, CLEANUP, RECOVERY -> GENERIC_FEEDBACK;
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

    private static final class Operation {
        private final long generation;
        private final CompletableFuture<Boolean> result;
        private final RecentSqlFiles.RecordAdmission recentAdmission;
        private Path claimedPath;

        private Operation(long generation, CompletableFuture<Boolean> result,
                RecentSqlFiles.RecordAdmission recentAdmission) {
            this.generation = generation;
            this.result = result;
            this.recentAdmission = recentAdmission;
        }

        long generation() { return generation; }
        CompletableFuture<Boolean> result() { return result; }
    }
}
