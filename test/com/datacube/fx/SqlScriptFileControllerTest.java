package com.datacube.fx;

import com.datacube.config.RecentSqlFiles;
import com.datacube.fx.SqlScriptFileController.CloseDecision;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.SqlScriptFileStore;
import javafx.application.Platform;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptFileControllerTest {
    @TempDir Path directory;

    @Test
    void composedStoreFailuresKeepFixedFeedbackAndNeverExposeArtifactPaths() throws Exception {
        Path temporary = directory.resolve("private-temporary.sql");
        Path recovery = directory.resolve("private-recovery.sql");
        Constructor<SqlScriptFileStore.Failure> constructor = SqlScriptFileStore.Failure.class
                .getDeclaredConstructor(SqlScriptFileStore.FailureCode.class, Path.class, Path.class);
        constructor.setAccessible(true);
        SqlScriptFileStore.Failure changed = constructor.newInstance(
                SqlScriptFileStore.FailureCode.CHANGED, temporary, recovery);
        SqlScriptFileStore.Failure retained = constructor.newInstance(
                SqlScriptFileStore.FailureCode.RECOVERY, temporary, recovery);

        String changedFeedback = SqlScriptFileController.feedbackFor(changed);
        String recoveryFeedback = SqlScriptFileController.feedbackFor(retained);

        assertEquals("文件已被外部修改，未覆盖磁盘内容。", changedFeedback);
        assertEquals("SQL 文件保存失败，磁盘内容未被替换。", recoveryFeedback);
        for (String feedback : List.of(changedFeedback, recoveryFeedback)) {
            assertFalse(feedback.contains(temporary.toString()));
            assertFalse(feedback.contains(recovery.toString()));
        }
    }

    @Test
    void installsLoadedOrCurrentUnboundTextAndTracksExactDirtyRevert() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path file = Files.writeString(directory.resolve("loaded.sql"), "select '甲';\n  ");
        SqlScriptFileStore.Loaded loaded = store.load(file);
        try (Fixture fixture = new Fixture("discarded", loaded, store, recent("loaded-recent"))) {
            assertEquals("select '甲';\n  ", fixture.text());
            assertEquals("loaded.sql", fixture.title());

            fixture.edit("select '甲';\n");
            assertEquals("loaded.sql*", fixture.title());
            fixture.edit("select '甲';\n  ");
            assertEquals("loaded.sql", fixture.title());
        }

        try (Fixture fixture = fixture("select 1\n", null)) {
            assertEquals("新建 SQL", fixture.title());
            fixture.edit("select 1\n ");
            assertEquals("新建 SQL*", fixture.title());
            fixture.edit("select 1\n");
            assertEquals("新建 SQL", fixture.title());
        }
    }

    @Test
    void savesLoadedMixedSeparatorsExactlyWhileShowingNormalizedEditorText() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        String physical = "one\r\ntwo\rthree\nfour\r\n";
        Path file = Files.writeString(directory.resolve("mixed.sql"), physical);
        try (Fixture fixture = new Fixture("ignored", store.load(file), store, recent("mixed-recent"))) {
            assertEquals("one\ntwo\nthree\nfour\n", fixture.text());
            assertTrue(fixture.settle(fixture.save()));
            assertEquals(physical, Files.readString(file));
            fixture.edit("one!\ntwo\nthree\nfour\n");
            assertTrue(fixture.settle(fixture.save()));
            assertEquals("one!\r\ntwo\rthree\nfour\r\n", Files.readString(file));
        }
    }

    @Test
    void positionalRichTextChangeUpdatesOnlyTheEditedPhysicalSegment() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        String physical = "one\r\ntwo\rthree\nfour\r\n";
        Path file = Files.writeString(directory.resolve("positional-mixed.sql"), physical);
        try (Fixture fixture = new Fixture("ignored", store.load(file), store, recent("positional-recent"))) {
            fixture.fx(() -> fixture.editor.replaceText(3, 3, "!"));

            assertTrue(fixture.settle(fixture.save()));
            assertEquals("one!\r\ntwo\rthree\nfour\r\n", Files.readString(file));
        }
    }

    @Test
    void positionalDeletionThatJoinsBareCrAndLfSavesAndReloadsAsTwoLogicalLineBreaks() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path file = Files.writeString(directory.resolve("joined-crlf.sql"), "a\rb\nc");
        try (Fixture fixture = new Fixture("ignored", store.load(file), store, recent("joined-crlf-recent"))) {
            fixture.fx(() -> fixture.editor.replaceText(2, 3, ""));

            assertEquals("a\n\nc", fixture.text());
            assertTrue(fixture.settle(fixture.save()));
            assertEquals("a\r\r\nc", Files.readString(file));
            assertEquals("a\n\nc", new SqlScriptDocument(store.load(file).text()).normalizedText());
        }
    }

    @Test
    void firstSaveNormalSaveAndSaveAsPublishExactSnapshotsAndRebind() throws Exception {
        try (Fixture fixture = fixture("select 1", null)) {
            Path first = directory.resolve("first.sql");
            fixture.chosen.set(first);
            fixture.edit("select 'first';\n");
            assertTrue(fixture.settle(fixture.save()));
            assertEquals("select 'first';\n", Files.readString(first));
            assertEquals("first.sql", fixture.title());
            assertEquals(List.of(first.toAbsolutePath().normalize()), fixture.recent.recent());

            fixture.edit("select 'second';\n  ");
            fixture.chosen.set(directory.resolve("must-not-be-used.sql"));
            assertTrue(fixture.settle(fixture.save()));
            assertEquals("select 'second';\n  ", Files.readString(first));
            assertFalse(Files.exists(fixture.chosen.get()));

            Path second = directory.resolve("second.sql");
            fixture.chosen.set(second);
            fixture.edit("select 'save-as';");
            assertTrue(fixture.settle(fixture.saveAs()));
            assertEquals("select 'save-as';", Files.readString(second));
            assertEquals("second.sql", fixture.title());
            assertEquals(second.toAbsolutePath().normalize(), fixture.recent.recent().getFirst());
        }
    }

    @Test
    void sameTargetSaveAsReusesConflictTokenAndDifferentExistingTargetNeedsConsent() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path current = Files.writeString(directory.resolve("current.sql"), "old");
        try (Fixture fixture = new Fixture("ignored", store.load(current), store, recent("same-recent"))) {
            fixture.edit("ours");
            Files.writeString(current, "external secret");
            fixture.chosen.set(current);

            assertFalse(fixture.settle(fixture.saveAs()));
            assertEquals("external secret", Files.readString(current));
            assertEquals("current.sql*", fixture.title());
            assertEquals(0, fixture.confirmations.get());
            assertSanitizedConflict(fixture.feedback.getLast(), current);
        }

        Path original = Files.writeString(directory.resolve("original.sql"), "original");
        Path existing = Files.writeString(directory.resolve("existing.sql"), "keep");
        try (Fixture fixture = new Fixture("ignored", store.load(original), store, recent("deny-recent"))) {
            fixture.edit("replacement");
            fixture.chosen.set(existing);
            fixture.overwrite.set(false);

            assertFalse(fixture.settle(fixture.saveAs()));
            assertEquals(1, fixture.confirmations.get());
            assertEquals("keep", Files.readString(existing));
            assertEquals("original.sql*", fixture.title());
            assertTrue(fixture.recent.recent().isEmpty());
        }
    }

    @Test
    void canonicalAliasOfCurrentTargetStillUsesTheDocumentsConflictToken() throws Exception {
        Path current = Files.writeString(directory.resolve("canonical.sql"), "old");
        Path alias = directory.resolve("alias");
        try {
            Files.createSymbolicLink(alias, directory);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic links unavailable for this account");
        }
        SqlScriptFileStore store = new SqlScriptFileStore();
        try (Fixture fixture = new Fixture("ignored", store.load(current), store,
                recent("alias-recent"))) {
            fixture.edit("ours");
            Files.writeString(current, "external alias change");
            fixture.chosen.set(alias.resolve("canonical.sql"));

            assertFalse(fixture.settle(fixture.saveAs()));
            assertEquals("external alias change", Files.readString(current));
            assertEquals(0, fixture.confirmations.get());
            assertSanitizedConflict(fixture.feedback.getLast(), current);
        }
    }

    @Test
    void overlappingRequestIsRejectedAndEditDuringSaveKeepsNewerTextDirty() throws Exception {
        try (Fixture fixture = fixture("baseline", null)) {
            Path target = directory.resolve("busy.sql");
            fixture.chosen.set(target);
            fixture.edit("published snapshot");
            CompletionStage<Boolean> first = fixture.save();
            CompletionStage<Boolean> second = fixture.saveAs();

            assertTrue(fixture.busy());
            assertFalse(second.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.submitter.workerCount());
            assertTrue(fixture.feedback.getLast().contains("进行中"));

            fixture.edit("published snapshot plus later edit");
            assertTrue(fixture.settle(first));
            assertEquals("published snapshot", Files.readString(target));
            assertEquals("busy.sql*", fixture.title());
            assertFalse(fixture.busy());
        }
    }

    @Test
    void invalidTargetTooLargeAndRecentFailureUseFixedSanitizedFeedback() throws Exception {
        try (Fixture fixture = fixture("secret SQL", null)) {
            Path invalid = directory.resolve("missing-parent").resolve("secret-name.sql");
            fixture.chosen.set(invalid);
            assertFalse(fixture.settle(fixture.save()));
            assertTrue(fixture.feedback.getLast().contains("位置"));
            assertFalse(fixture.feedback.getLast().contains("secret"));
            assertFalse(fixture.feedback.getLast().contains(invalid.toString()));

            fixture.edit("x".repeat((int) SqlScriptFileStore.MAX_BYTES + 1));
            fixture.chosen.set(directory.resolve("too-large.sql"));
            assertFalse(fixture.settle(fixture.save()));
            assertTrue(fixture.feedback.getLast().contains("8 MiB"));
            assertFalse(fixture.feedback.getLast().contains("secret SQL"));
        }

        RecentSqlFiles brokenRecent = new RecentSqlFiles(
                directory.resolve("missing-recent-parent").resolve("recent.txt"));
        try (Fixture fixture = new Fixture("publish anyway", null,
                new SqlScriptFileStore(), brokenRecent)) {
            Path target = directory.resolve("recent-best-effort.sql");
            fixture.chosen.set(target);
            assertTrue(fixture.settle(fixture.save()));
            assertEquals("publish anyway", Files.readString(target));
            assertTrue(fixture.feedback.isEmpty());
        }
    }

    @Test
    void closeInvalidatesQueuedCompletionWithoutClosingSharedScopeOrPublishingUiState() throws Exception {
        try (Fixture fixture = fixture("snapshot", null)) {
            fixture.chosen.set(directory.resolve("stale.sql"));
            int titlesBefore = fixture.titles.size();
            CompletionStage<Boolean> pending = fixture.save();
            fixture.submitter.runWorker();

            fixture.fx(fixture.controller::close);
            assertFalse(pending.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertFalse(fixture.scope.isClosed());
            fixture.fx(fixture.submitter::drainFx);

            assertEquals(titlesBefore, fixture.titles.size());
            assertTrue(fixture.feedback.isEmpty());
            assertTrue(fixture.recent.recent().isEmpty());
            assertFalse(Files.exists(directory.resolve("stale.sql")));
        }
    }

    @Test
    void cleanDiscardAndCancelCloseInvokeExistingGuardAtMostOnce() throws Exception {
        try (Fixture clean = fixture("clean", null)) {
            AtomicInteger calls = new AtomicInteger();
            assertEquals(CloseGuardOutcome.APPROVED,
                    clean.guard(calls, CloseGuardOutcome.APPROVED));
            assertEquals(1, calls.get());
        }

        try (Fixture dirty = fixture("baseline", null)) {
            dirty.edit("dirty");
            AtomicInteger calls = new AtomicInteger();
            dirty.decision.set(CloseDecision.CANCEL);
            assertEquals(CloseGuardOutcome.REJECTED,
                    dirty.guard(calls, CloseGuardOutcome.APPROVED));
            assertEquals(0, calls.get());

            dirty.decision.set(CloseDecision.DISCARD);
            assertEquals(CloseGuardOutcome.APPROVED,
                    dirty.guard(calls, CloseGuardOutcome.APPROVED));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void saveCloseProceedsOnlyAfterSuccessfulStillCurrentBaseline() throws Exception {
        try (Fixture fixture = fixture("baseline", null)) {
            fixture.edit("dirty snapshot");
            fixture.chosen.set(directory.resolve("close-save.sql"));
            fixture.decision.set(CloseDecision.SAVE);
            AtomicInteger calls = new AtomicInteger();
            CompletionStage<CloseGuardOutcome> close = fixture.guardStage(calls,
                    CloseGuardOutcome.APPROVED);
            assertEquals(CloseGuardOutcome.APPROVED, fixture.settleClose(close));
            assertEquals(1, calls.get());
        }

        SqlScriptFileStore store = new SqlScriptFileStore();
        Path conflicted = Files.writeString(directory.resolve("close-conflict.sql"), "old");
        try (Fixture fixture = new Fixture("ignored", store.load(conflicted), store,
                recent("close-conflict-recent"))) {
            fixture.edit("ours");
            Files.writeString(conflicted, "external");
            fixture.decision.set(CloseDecision.SAVE);
            AtomicInteger calls = new AtomicInteger();
            assertEquals(CloseGuardOutcome.REJECTED,
                    fixture.settleClose(fixture.guardStage(calls, CloseGuardOutcome.APPROVED)));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void editDuringSaveCloseAndDecisionFailureRejectWithoutStartingExistingCleanup() throws Exception {
        try (Fixture fixture = fixture("baseline", null)) {
            fixture.edit("save snapshot");
            fixture.chosen.set(directory.resolve("edit-close.sql"));
            fixture.decision.set(CloseDecision.SAVE);
            AtomicInteger calls = new AtomicInteger();
            CompletionStage<CloseGuardOutcome> close = fixture.guardStage(calls,
                    CloseGuardOutcome.APPROVED);
            fixture.submitter.runWorker();
            fixture.edit("newer edit");
            assertEquals(CloseGuardOutcome.REJECTED, fixture.settleClose(close));
            assertEquals(0, calls.get());
        }

        try (Fixture fixture = fixture("baseline", null)) {
            fixture.edit("dirty");
            fixture.throwDecision = true;
            AtomicInteger calls = new AtomicInteger();
            assertEquals(CloseGuardOutcome.REJECTED,
                    fixture.guard(calls, CloseGuardOutcome.APPROVED));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void closeBetweenPendingPublicationAndSubmitAdmissionSettlesFalseWithoutSideEffects()
            throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path target = Files.writeString(directory.resolve("admission-race.sql"), "old");
        CountDownLatch exposed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable barrier = () -> {
            exposed.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS), "admission barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        try (Fixture fixture = new Fixture("ignored", store.load(target), store,
                recent("admission-race-recent"), barrier)) {
            fixture.edit("must not publish");
            int titleCount = fixture.titles.size();
            int feedbackCount = fixture.feedback.size();
            CompletableFuture<CompletionStage<Boolean>> returned = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    returned.complete(fixture.controller.save());
                } catch (Throwable failure) {
                    returned.completeExceptionally(failure);
                }
            });
            assertTrue(exposed.await(5, TimeUnit.SECONDS));

            Thread closer = Thread.ofVirtual().start(fixture.controller::close);
            closer.join();
            assertFalse(fixture.controller.isBusy());
            release.countDown();

            CompletionStage<Boolean> operation = returned.get(5, TimeUnit.SECONDS);
            assertFalse(operation.toCompletableFuture().get(5, TimeUnit.SECONDS));
            fixture.fx(() -> { });
            assertFalse(fixture.controller.busyProperty().get());
            assertEquals(0, fixture.submitter.submissions.get());
            assertEquals(0, fixture.submitter.workerCount());
            assertEquals("old", Files.readString(target));
            assertTrue(fixture.recent.recent().isEmpty());
            assertEquals(titleCount, fixture.titles.size());
            assertEquals(feedbackCount, fixture.feedback.size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void throwingTitleConsumerAfterPublicationSettlesTrueAndRemainsRetryable() throws Exception {
        try (Fixture fixture = fixture("baseline", null)) {
            Path target = directory.resolve("title-callback.sql");
            fixture.chosen.set(target);
            fixture.edit("first durable snapshot");
            fixture.throwTitle = true;

            assertTrue(fixture.settle(fixture.save()));
            assertEquals("first durable snapshot", Files.readString(target));
            assertFalse(fixture.busy());
            assertFalse(FxUiTestSupport.call(() -> fixture.controller.busyProperty().get()));
            String fixed = fixture.feedback.getLast();
            assertTrue(fixed.contains("失败"));
            assertFalse(fixed.contains("private title detail"));
            assertFalse(fixed.contains("first durable snapshot"));
            assertFalse(fixed.contains(target.toString()));

            fixture.throwTitle = false;
            fixture.edit("second durable snapshot");
            assertTrue(fixture.settle(fixture.save()));
            assertEquals("second durable snapshot", Files.readString(target));

            int feedbackBeforeListener = fixture.feedback.size();
            fixture.throwTitle = true;
            fixture.edit("listener must survive");
            assertEquals(feedbackBeforeListener + 1, fixture.feedback.size());
            assertFalse(fixture.feedback.getLast().contains("private title detail"));
        }
    }

    @Test
    void closeBeforeCallbackSettlementWinsWithoutApplyingStaleSuccessState() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path target = Files.writeString(directory.resolve("callback-race.sql"), "old baseline");
        CountDownLatch settlementEntered = new CountDownLatch(1);
        CountDownLatch releaseSettlement = new CountDownLatch(1);
        Runnable settlementBarrier = () -> {
            settlementEntered.countDown();
            try {
                assertTrue(releaseSettlement.await(5, TimeUnit.SECONDS),
                        "callback settlement barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        try (Fixture fixture = new Fixture("ignored", store.load(target), store,
                recent("callback-race-recent"), () -> { }, settlementBarrier)) {
            fixture.edit("durably published but not settled");
            int titleCount = fixture.titles.size();
            int feedbackCount = fixture.feedback.size();
            CompletionStage<Boolean> operation = fixture.save();
            fixture.submitter.runWorker();
            int recentCountBeforeClose = fixture.recent.recent().size();
            CompletableFuture<Void> callback = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    fixture.submitter.drainFx();
                    callback.complete(null);
                } catch (Throwable failure) {
                    callback.completeExceptionally(failure);
                }
            });
            assertTrue(settlementEntered.await(5, TimeUnit.SECONDS));

            Thread closer = Thread.ofVirtual().start(fixture.controller::close);
            closer.join();
            releaseSettlement.countDown();
            callback.get(5, TimeUnit.SECONDS);

            assertFalse(operation.toCompletableFuture().get(5, TimeUnit.SECONDS));
            fixture.fx(() -> { });
            assertFalse(fixture.controller.isBusy());
            assertFalse(fixture.controller.busyProperty().get());
            assertTrue(fixture.documentDirty("durably published but not settled"));
            assertEquals(titleCount, fixture.titles.size());
            assertEquals(feedbackCount, fixture.feedback.size());
            assertEquals(recentCountBeforeClose, fixture.recent.recent().size());
            assertEquals("durably published but not settled", Files.readString(target));
        } finally {
            releaseSettlement.countDown();
        }
    }

    private RecentSqlFiles recent(String name) {
        return new RecentSqlFiles(directory.resolve(name + ".txt"));
    }

    private Fixture fixture(String text, SqlScriptFileStore.Loaded loaded) throws Exception {
        return new Fixture(text, loaded, new SqlScriptFileStore(), recent("recent-" + System.nanoTime()));
    }

    private static void assertSanitizedConflict(String message, Path target) {
        assertTrue(message.contains("外部"));
        assertFalse(message.contains("secret"));
        assertFalse(message.contains(target.toString()));
    }

    private final class Fixture implements AutoCloseable {
        final FxTaskRunner runner = new FxTaskRunner();
        final FxTaskScope scope;
        final ControlledSubmitter submitter = new ControlledSubmitter();
        final AtomicReference<Path> chosen = new AtomicReference<>();
        final AtomicReference<Boolean> overwrite = new AtomicReference<>(true);
        final AtomicReference<CloseDecision> decision = new AtomicReference<>(CloseDecision.CANCEL);
        final AtomicInteger confirmations = new AtomicInteger();
        final List<String> titles = new ArrayList<>();
        final List<String> feedback = new ArrayList<>();
        final RecentSqlFiles recent;
        final CodeArea editor;
        final SqlScriptFileController controller;
        boolean throwDecision;
        volatile boolean throwTitle;

        Fixture(String text, SqlScriptFileStore.Loaded loaded, SqlScriptFileStore store,
                RecentSqlFiles recent) throws Exception {
            this(text, loaded, store, recent, () -> { }, () -> { });
        }

        Fixture(String text, SqlScriptFileStore.Loaded loaded, SqlScriptFileStore store,
                RecentSqlFiles recent, Runnable beforeSubmit) throws Exception {
            this(text, loaded, store, recent, beforeSubmit, () -> { });
        }

        Fixture(String text, SqlScriptFileStore.Loaded loaded, SqlScriptFileStore store,
                RecentSqlFiles recent, Runnable beforeSubmit, Runnable beforeSettlement)
                throws Exception {
            this.recent = recent;
            editor = FxUiTestSupport.call(() -> new CodeArea(text));
            scope = runner.scope();
            controller = FxUiTestSupport.call(() -> new SqlScriptFileController(
                    editor, store, recent, scope, () -> null, title -> {
                        if (throwTitle) throw new IllegalStateException("private title detail");
                        titles.add(title);
                    }, "新建 SQL",
                    ignored -> chosen.get(), (ignored, path) -> {
                        confirmations.incrementAndGet();
                        return overwrite.get();
                    }, ignored -> {
                        if (throwDecision) throw new IllegalStateException("private decision");
                        return decision.get();
                    }, feedback::add, submitter, beforeSubmit, beforeSettlement));
            fx(() -> controller.install(loaded));
        }

        CompletionStage<Boolean> save() throws Exception {
            return FxUiTestSupport.call(controller::save);
        }

        CompletionStage<Boolean> saveAs() throws Exception {
            return FxUiTestSupport.call(controller::saveAs);
        }

        boolean settle(CompletionStage<Boolean> stage) throws Exception {
            settleWorkers(stage.toCompletableFuture());
            return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        CompletionStage<CloseGuardOutcome> guardStage(AtomicInteger calls,
                CloseGuardOutcome result) throws Exception {
            return FxUiTestSupport.call(() -> controller.guardClose(() -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(result);
            }));
        }

        CloseGuardOutcome guard(AtomicInteger calls, CloseGuardOutcome result) throws Exception {
            CompletionStage<CloseGuardOutcome> stage = guardStage(calls, result);
            return settleClose(stage);
        }

        CloseGuardOutcome settleClose(CompletionStage<CloseGuardOutcome> stage) throws Exception {
            settleWorkers(stage.toCompletableFuture());
            return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        private void settleWorkers(CompletableFuture<?> stage) throws Exception {
            for (int i = 0; i < 12 && !stage.isDone(); i++) {
                if (submitter.hasWorker()) submitter.runWorker();
                fx(submitter::drainFx);
            }
            assertTrue(stage.isDone(), "operation did not settle");
        }

        void edit(String text) throws Exception {
            fx(() -> editor.replaceText(text));
        }

        String text() throws Exception {
            return FxUiTestSupport.call(editor::getText);
        }

        String title() {
            return titles.getLast();
        }

        boolean busy() throws Exception {
            return FxUiTestSupport.call(controller::isBusy);
        }

        boolean documentDirty(String text) throws Exception {
            java.lang.reflect.Field field = SqlScriptFileController.class.getDeclaredField("document");
            field.setAccessible(true);
            return ((SqlScriptDocument) field.get(controller)).dirty(text);
        }

        void fx(Runnable action) throws Exception {
            FxUiTestSupport.call(() -> {
                action.run();
                return null;
            });
        }

        @Override
        public void close() throws Exception {
            fx(controller::close);
            assertFalse(scope.isClosed(), "controller must not own the shared scope");
            scope.close();
            runner.close();
        }
    }

    private static final class ControlledSubmitter implements SqlScriptFileController.Submitter {
        private final Queue<Runnable> workers = new ArrayDeque<>();
        private final Queue<Runnable> fx = new ArrayDeque<>();
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public <T> void submit(Callable<T> operation, Consumer<? super T> success,
                Consumer<? super Throwable> failure) {
            assertTrue(Platform.isFxApplicationThread(), "submission must be initiated on FX");
            submissions.incrementAndGet();
            workers.add(() -> {
                assertFalse(Platform.isFxApplicationThread(), "file I/O must run off FX");
                try {
                    T value = operation.call();
                    fx.add(() -> success.accept(value));
                } catch (Throwable error) {
                    fx.add(() -> failure.accept(error));
                }
            });
        }

        int workerCount() {
            return workers.size();
        }

        boolean hasWorker() {
            return !workers.isEmpty();
        }

        void runWorker() throws Exception {
            Runnable next = workers.remove();
            Thread worker = Thread.ofVirtual().start(next);
            worker.join();
        }

        void drainFx() {
            assertTrue(Platform.isFxApplicationThread());
            Runnable next;
            while ((next = fx.poll()) != null) next.run();
        }
    }
}
