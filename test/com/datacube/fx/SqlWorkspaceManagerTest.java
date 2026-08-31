package com.datacube.fx;

import com.datacube.config.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceManagerTest {
    @TempDir Path directory;

    @Test void activitySaveFailureIsVisibleAndExplicitRetryPersistsLatestLayout() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("select checkpoint");
            f.base.save(new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 1, 1)), draft.id()));
            f.open(); f.ready();
            f.base.fx(() -> f.button("restore").fire()); f.ready();
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.SAVED);
            Path manifest = f.base.root.resolve("drafts/workspace.bin");
            byte[] original = Files.readAllBytes(manifest);
            f.failMethod.set("saveWorkspace");
            f.base.fx(() -> {
                f.base.editor(draft.id()).selectRange(2, 6);
                f.base.workspace.activity(); f.base.now = 10000; f.base.workspace.pulse();
            });
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.FAILED);
            f.base.fx(() -> {
                f.pane.refreshView();
                Label health = (Label) f.pane.getNode().lookup("#workspace-manager-activity-status");
                assertNotNull(health, "ordinary save failure needs visible layout-save feedback");
                assertTrue(health.isVisible());
                assertTrue(health.getText().contains("未保存"));
                assertTrue(health.getText().contains("已有恢复点保留"));
                assertNotNull(f.button("retry-save"));
                assertFalse(f.button("retry-save").isDisabled());
            });
            int writes = f.writes.get();
            f.base.fx(() -> {
                for (int i = 0; i < 10; i++) { f.base.now += 10000; f.base.workspace.pulse(); f.pane.refreshView(); }
                f.button("refresh").fire();
            });
            f.ready();
            assertEquals(writes, f.writes.get());
            assertArrayEquals(original, Files.readAllBytes(manifest));
            f.failMethod.set(null);
            f.base.fx(() -> { f.base.editor(draft.id()).selectRange(4, 9); f.button("retry-save").fire(); });
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.SAVED);
            assertEquals(List.of(new SqlWorkspace.Entry(draft.id(), 4, 9)), f.base.snapshot().workspace().entries());
            f.base.fx(() -> assertEquals("select checkpoint", f.base.editor(draft.id()).getText()));
            f.base.offline();
        }
    }

    @Test void activityRetryWaitsForPublicationAndFailedRetryPreservesRecoveryPoint() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.failedSave();
            Path manifest = f.base.root.resolve("drafts/workspace.bin");
            byte[] original = Files.readAllBytes(manifest);
            int before = f.writes.get();
            f.blockMethod.set("saveWorkspace");
            f.base.fx(() -> { f.button("retry-save").fire(); f.button("retry-save").fire(); });
            assertTrue(f.entered.await(5, TimeUnit.SECONDS));
            f.base.fx(() -> {
                f.pane.refreshView();
                assertEquals(SqlWorkspaceActivity.Status.PENDING, f.base.workspace.owner().status());
                assertTrue(f.health().contains("待保存"), f.health());
                assertFalse(f.health().contains("已保存"));
                assertTrue(f.button("retry-save").isDisabled());
                f.button("retry-save").getOnAction().handle(new javafx.event.ActionEvent());
            });
            assertEquals(before + 1, f.writes.get());
            assertArrayEquals(original, Files.readAllBytes(manifest));
            f.release.countDown();
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.FAILED);
            f.base.fx(() -> {
                for (int i = 0; i < 10; i++) { f.base.now += 10000; f.base.workspace.pulse(); f.pane.refreshView(); }
                assertTrue(f.health().contains("未保存"));
                assertTrue(f.health().contains("已有恢复点保留"));
                assertFalse(f.health().contains("synthetic"));
                assertFalse(f.button("retry-save").isDisabled());
            });
            assertEquals(before + 1, f.writes.get());
            assertArrayEquals(original, Files.readAllBytes(manifest));
            f.failMethod.set(null);
            f.base.fx(() -> { f.base.editor(draft.id()).selectRange(9, 3); f.button("retry-save").fire(); });
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.SAVED);
            assertEquals(List.of(new SqlWorkspace.Entry(draft.id(), 9, 3)), f.base.snapshot().workspace().entries());
            assertEquals(before + 2, f.writes.get());
            f.base.fx(() -> { f.pane.refreshView(); assertTrue(f.health().contains("已保存")); });
            f.base.offline();
        }
    }

    @Test void activityReadFailureCanRetryAfterRepairWithoutAutomaticLoop() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("select checkpoint");
            f.base.save(new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 1, 1)), draft.id()));
            f.open(); f.ready();
            f.failMethod.set("workspaceSnapshot");
            f.base.fx(() -> assertTrue(f.base.single.restore(draft)));
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.FAILED);
            int reads = f.reads.get(), writes = f.writes.get();
            f.base.fx(() -> {
                for (int i = 0; i < 10; i++) { f.base.now += 10000; f.base.workspace.pulse(); f.pane.refreshView(); }
                assertTrue(f.health().contains("未保存"));
                assertFalse(f.button("retry-save").isDisabled());
            });
            assertEquals(reads, f.reads.get()); assertEquals(writes, f.writes.get());
            f.failMethod.set(null);
            f.base.fx(() -> { f.base.editor(draft.id()).selectRange(3, 8); f.button("retry-save").fire(); });
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.SAVED);
            assertEquals(List.of(new SqlWorkspace.Entry(draft.id(), 3, 8)), f.base.snapshot().workspace().entries());
            assertEquals(writes + 1, f.writes.get()); f.base.offline();
        }
    }

    @Test void closedManagerIgnoresRetryPublicationAndCannotSubmitAgain() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.failedSave(); f.failMethod.set(null); f.blockMethod.set("saveWorkspace");
            f.base.fx(() -> f.button("retry-save").fire());
            assertTrue(f.entered.await(5, TimeUnit.SECONDS));
            String pending = f.base.call(f::health);
            f.base.fx(() -> {
                assertTrue(pending.contains("待保存"));
                f.pane.close();
                assertTrue(f.button("retry-save").isDisabled());
            });
            int writes = f.writes.get();
            f.release.countDown();
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.SAVED);
            f.base.fx(() -> {
                f.pane.refreshView();
                f.button("retry-save").getOnAction().handle(new javafx.event.ActionEvent());
                assertEquals(pending, f.health(), "closed pane must ignore the real completion");
                assertTrue(f.button("retry-save").isDisabled());
            });
            assertEquals(writes, f.writes.get());
        }
    }

    @Test void activityCaptureFailureRetryCapturesCurrentInstalledPositions() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.failedSave();
            assertThrows(IllegalStateException.class, f.base.workspace::canRetrySave);
            assertThrows(IllegalStateException.class, f.base.workspace::retrySave);
            f.failMethod.set(null);
            f.base.fx(() -> {
                f.base.workspace.owner().captureFailed();
                f.base.editor(draft.id()).selectRange(10, 4);
                f.pane.refreshView();
                assertTrue(f.health().contains("未保存"));
                f.button("retry-save").fire();
            });
            f.base.await(() -> f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.SAVED);
            assertEquals(List.of(new SqlWorkspace.Entry(draft.id(), 10, 4)), f.base.snapshot().workspace().entries());
            f.base.fx(() -> assertEquals("select checkpoint", f.base.editor(draft.id()).getText()));
            f.base.offline();
        }
    }

    @Test void activityRetryRejectsRealOwnerCloseWhileFinalValidationIsPending() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.failedSave(); f.failMethod.set(null); f.blockMethod.set("snapshot");
            var closing = f.base.tabs.closeAllManagedTabsMandatory().toCompletableFuture();
            assertTrue(f.entered.await(5, TimeUnit.SECONDS));
            int writes = f.writes.get();
            f.base.fx(() -> {
                assertFalse(closing.isDone());
                f.pane.refreshView();
                assertTrue(f.health().contains("已冻结"));
                assertFalse(f.base.workspace.canRetrySave());
                assertTrue(f.button("retry-save").isDisabled());
                f.button("retry-save").getOnAction().handle(new javafx.event.ActionEvent());
                f.base.workspace.retrySave();
                assertEquals(SqlWorkspaceActivity.Status.FROZEN, f.base.workspace.owner().status());
            });
            assertEquals(writes, f.writes.get());
            f.release.countDown();
            assertEquals(TabCloseOutcome.COMPLETED, closing.get(5, TimeUnit.SECONDS));
            assertEquals(writes + 1, f.writes.get(), "only existing final lifecycle publication is accepted");
        }
    }

    @Test void activityRetryRejectsStructurallyUnavailableRuntimeWithoutClearingFailure() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.failedSave();
            f.failMethod.set(null);
            f.structuralSaveFailure.set(true);
            f.base.fx(() -> f.button("retry-save").fire());
            f.base.await(() -> f.base.owner.runtime().mode() == SqlDraftCoordinator.Mode.UNAVAILABLE
                    && f.base.workspace.owner().status() == SqlWorkspaceActivity.Status.FAILED);
            int writes = f.writes.get();
            f.base.fx(() -> {
                f.pane.refreshView();
                assertTrue(f.health().contains("不可用"));
                assertTrue(f.health().contains("检查本机目录后重启"));
                assertFalse(f.health().contains("可重试"));
                assertTrue(f.button("retry-save").isDisabled());
                f.button("retry-save").getOnAction().handle(new javafx.event.ActionEvent());
                f.base.workspace.retrySave();
                assertEquals(SqlWorkspaceActivity.Status.FAILED, f.base.workspace.owner().status());
            });
            assertEquals(writes, f.writes.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"draft-disabled", "draft-paused", "workspace-disabled", "session-paused",
            "runtime-closed", "frozen", "recovering", "adapter-closed", "manager-closed", "busy"})
    void activityRetryRejectsLifecycleAndManagementGuards(String guard) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.failedSave();
            f.failMethod.set(null);
            switch (guard) {
                case "draft-disabled", "draft-paused" -> {
                    if (guard.equals("draft-paused")) f.failMethod.set("setEnabled");
                    f.base.call(() -> f.base.owner.runtime().setEnabled(false)).get(5, TimeUnit.SECONDS);
                }
                case "workspace-disabled", "session-paused" -> {
                    if (guard.equals("session-paused")) f.failMethod.set("setWorkspaceEnabled");
                    try { f.base.call(() -> f.base.workspace.owner().setWorkspaceEnabled(false)).get(5, TimeUnit.SECONDS); }
                    catch (ExecutionException expected) { assertEquals("session-paused", guard); }
                }
                case "runtime-closed" -> f.base.call(() -> f.base.owner.runtime().shutdown()).get(5, TimeUnit.SECONDS);
                case "frozen" -> f.base.fx(() -> f.base.workspace.owner().freezeForExit(f.base.workspace.capture()));
                case "recovering" -> f.base.fx(() -> assertTrue(f.base.workspace.beginRecovery()));
                case "adapter-closed" -> f.base.fx(f.base.workspace::close);
                case "manager-closed" -> f.base.fx(f.pane::close);
                case "busy" -> {
                    f.blockMethod.set("snapshot");
                    f.base.call(() -> f.base.owner.runtime().refresh());
                    assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                }
            }
            int writes = f.writes.get();
            f.base.fx(() -> {
                f.pane.refreshView();
                var before = f.base.workspace.owner().status();
                assertTrue(f.button("retry-save").isDisabled(), guard);
                f.button("retry-save").fire();
                f.button("retry-save").getOnAction().handle(new javafx.event.ActionEvent());
                assertEquals(before, f.base.workspace.owner().status(), "rejection must not clear the latch: " + guard);
                if (!guard.equals("manager-closed")) {
                    f.base.workspace.retrySave();
                    assertEquals(before, f.base.workspace.owner().status());
                }
                if (guard.startsWith("draft-")) assertTrue(f.health().contains("不记录新的布局"), f.health());
                if (guard.equals("runtime-closed")) assertTrue(f.health().contains("已关闭"), f.health());
                if (guard.equals("frozen")) assertTrue(f.health().contains("已冻结"), f.health());
            });
            assertEquals(writes, f.writes.get());
            f.release.countDown();
            if (guard.equals("recovering")) f.base.fx(() -> f.base.workspace.endRecovery(false));
        }
    }

    @Test void initialReadShowsRealSnapshotCountsAndCreatesEditorsOnlyAfterExplicitRestore() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft a = f.base.seed("select alpha"), b = f.base.seed("select beta");
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(b.id(), 8, 2),
                    new SqlWorkspace.Entry(UUID.randomUUID(), 0, 0), new SqlWorkspace.Entry(a.id(), 7, 1)), b.id());
            f.base.save(saved); f.open(); f.ready();
            f.base.fx(() -> {
                assertTrue(f.status().contains("共 3"), f.status());
                assertTrue(f.status().contains("可用 2"), f.status());
                assertTrue(f.status().contains("缺失 1"), f.status());
                assertTrue(f.base.tabPane().getTabs().isEmpty());
                assertEquals(0, f.base.created.size());
                f.button("restore").fire(); f.button("restore").fire();
            });
            f.ready();
            f.base.fx(() -> {
                assertEquals(2, f.base.created.size());
                assertEquals(List.of(f.base.tab(b.id()), f.base.tab(a.id())), List.copyOf(f.base.tabPane().getTabs()));
                assertEquals("select beta", f.base.editor(b.id()).getText());
                assertEquals(8, f.base.editor(b.id()).getAnchor());
                assertSame(f.base.tab(b.id()), f.base.tabPane().getSelectionModel().getSelectedItem());
                assertEquals("已打开 2，已定位 0，缺失 1，失败 0", f.notice());
            });
        }
    }

    @Test void dialogComposesWorkspaceAndDraftControlsWithOneDisposedSubscriptionAndLiveWriter() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("checkpoint");
            f.base.fx(() -> SqlDraftManagerTest.respondToDialog(
                    () -> SqlDraftManagerDialog.show(f.base.owner, null, null, f.base.single::restore, f.base.batch), dialog -> {
                        assertNotNull(dialog.lookup("#workspace-manager"));
                        assertNotNull(dialog.lookup("#draft-manager-list"));
                        assertEquals(1, ((Set<?>) SqlWorkspaceRecoveryTabsTest.get(f.base.owner, "observers")).size());
                    }));
            f.base.fx(() -> assertEquals(0, ((Set<?>) SqlWorkspaceRecoveryTabsTest.get(f.base.owner, "observers")).size()));
            f.base.await(() -> !f.base.owner.runtime().managementPending());
            f.base.fx(() -> {
                assertTrue(f.base.single.restore(draft));
                f.base.editor(draft.id()).replaceText("writer still available");
            });
            var binding = f.base.call(() -> f.base.owner.installedBinding(f.base.owner.installedContent(draft.id())));
            f.base.call(() -> ((SqlDraftCoordinator.Handle) SqlWorkspaceRecoveryTabsTest.get(binding, "handle")).flush()).get(5, TimeUnit.SECONDS);
            assertEquals("writer still available", f.base.call(() -> f.base.owner.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot().drafts().getFirst().sql());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "empty", "corrupt", "unsupported", "unreadable", "failed"})
    void snapshotProblemsHaveFixedMessagesNoAutomaticLoopAndExplicitRetry(String kind) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("checkpoint");
            Path manifest = f.base.root.resolve("drafts/workspace.bin");
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 8, 1)), draft.id());
            if (kind.equals("empty")) f.base.save(new SqlWorkspace(0, List.of(), null));
            if (kind.equals("corrupt")) Files.write(manifest, new byte[] {1, 2, 3});
            if (kind.equals("unsupported")) {
                f.base.save(saved);
                byte[] bytes = Files.readAllBytes(manifest);
                java.nio.ByteBuffer.wrap(bytes).putInt(4, 99); Files.write(manifest, bytes);
            }
            if (kind.equals("unreadable")) Files.write(manifest, new byte[1024 * 1024]);
            if (kind.equals("failed")) f.failMethod.set("workspaceSnapshot");
            f.open(); f.ready();
            String expected = switch (kind) {
                case "absent" -> "没有保存的工作区";
                case "empty" -> "工作区为空";
                case "corrupt" -> "工作区清单已损坏";
                case "unsupported" -> "工作区清单版本不受支持";
                case "unreadable" -> "工作区清单无法读取";
                default -> "工作区读取失败";
            };
            f.base.fx(() -> {
                assertTrue((f.status() + f.notice()).contains(expected), f.status() + f.notice());
                assertTrue(f.button("restore").isDisabled());
                assertEquals(0, f.base.created.size());
                int reads = f.reads.get();
                for (int i = 0; i < 10; i++) f.pane.refreshView();
                assertEquals(reads, f.reads.get(), "rendering a failure never loops reads");
            });
            if (Files.exists(manifest)) Files.delete(manifest);
            f.failMethod.set(null); f.base.save(saved);
            f.base.fx(() -> f.button("refresh").fire()); f.ready();
            f.base.fx(() -> {
                assertFalse(f.button("restore").isDisabled());
                assertTrue(f.status().contains("可用 1"));
                f.button("restore").fire();
            });
            f.ready();
            f.base.fx(() -> assertEquals("checkpoint", f.base.editor(draft.id()).getText()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"clear", "disable", "delete", "close"})
    void lateReadAfterGenerationChangeOrClosedPaneNeverCreatesEditors(String mutation) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("checkpoint");
            f.base.save(new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 8, 1)), draft.id()));
            f.open(); f.ready();
            f.blockMethod.set("workspaceSnapshot");
            f.base.fx(() -> { f.button("restore").fire(); f.button("restore").fire(); });
            assertTrue(f.entered.await(5, TimeUnit.SECONDS));
            String prior = f.base.call(f::status);
            CompletableFuture<?> change = f.base.call(() -> switch (mutation) {
                case "clear" -> f.base.owner.runtime().clearWorkspace();
                case "disable" -> f.base.owner.runtime().setWorkspaceEnabled(false);
                case "delete" -> f.base.owner.runtime().delete(draft.id());
                default -> { f.pane.close(); yield CompletableFuture.completedFuture(null); }
            });
            f.release.countDown(); change.get(5, TimeUnit.SECONDS);
            f.base.call(() -> f.base.owner.runtime().workspaceSnapshot()).get(5, TimeUnit.SECONDS);
            f.base.fx(() -> {
                f.pane.refreshView();
                assertEquals(0, f.base.created.size()); assertTrue(f.base.tabPane().getTabs().isEmpty());
                if (mutation.equals("close")) assertEquals(prior, f.status(), "closed view must ignore late result");
                else { assertTrue(f.notice().contains("已取消")); assertTrue(f.button("restore").isDisabled()); }
            });
        }
    }

    @Test void oldRestoreCompletionCannotAffectNewPendingRefresh() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("checkpoint");
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 8, 1)), draft.id());
            f.base.save(saved); f.open(); f.ready();
            Path manifest = f.base.root.resolve("drafts/workspace.bin");
            byte[] durableManifest = Files.readAllBytes(manifest);
            try (CompletionGate gate = new CompletionGate(f)) {
                CompletableFuture<Runnable> oldResult = gate.holdWorkspaceResult();
                f.base.fx(() -> {
                    assertEquals(0, f.base.created.size());
                    assertTrue(f.base.tabPane().getTabs().isEmpty());
                    f.button("restore").fire();
                });
                Runnable oldDelivery = oldResult.get(5, TimeUnit.SECONDS);
                long generation = f.base.call(() -> f.base.owner.runtime().workspaceGeneration());
                f.base.call(() -> f.base.owner.runtime().setWorkspaceEnabled(false)).get(5, TimeUnit.SECONDS);
                f.base.fx(() -> {
                    assertTrue(f.base.owner.runtime().workspaceGeneration() > generation);
                    f.pane.refreshView();
                    assertTrue(f.notice().contains("已取消"));
                    assertFalse(f.button("refresh").isDisabled());
                });
                CompletableFuture<Runnable> newResult = gate.holdWorkspaceResult();
                f.base.fx(() -> f.button("refresh").fire());
                Runnable newDelivery = newResult.get(5, TimeUnit.SECONDS);
                String pendingStatus = f.base.call(f::status);
                f.base.fx(() -> {
                    assertTrue(pendingStatus.contains("处理中"), pendingStatus);
                    assertTrue(f.button("refresh").isDisabled());
                    assertTrue(f.button("restore").isDisabled());
                    assertEquals("", f.notice());
                });
                gate.deliver(oldDelivery);
                // Delivery queues the manager callback; this next FX task is its FIFO barrier.
                f.base.fx(() -> {
                    assertEquals(0, f.base.created.size(), "old restore must not run an editor factory");
                    assertTrue(f.base.tabPane().getTabs().isEmpty());
                    assertEquals(pendingStatus, f.status(), "old completion must leave the newer refresh pending");
                    assertTrue(f.button("refresh").isDisabled());
                    assertTrue(f.button("restore").isDisabled());
                    assertEquals("", f.notice(), "old restore counts must not replace the newer request notice");
                });
                gate.deliver(newDelivery); f.ready();
                f.base.fx(() -> {
                    assertEquals("共 1，可用 1，缺失 0 · 工作区记录已关闭，已有工作区仍可恢复", f.status());
                    assertEquals("", f.notice());
                    for (String button : List.of("refresh", "restore", "toggle", "clear"))
                        assertFalse(f.button(button).isDisabled(), button);
                    assertEquals("开启记录 SQL 工作区", f.button("toggle").getText());
                    assertEquals(0, f.base.created.size());
                    assertTrue(f.base.tabPane().getTabs().isEmpty());
                });
                var persisted = f.base.snapshot();
                assertFalse(persisted.recordingEnabled());
                assertEquals(saved, persisted.workspace());
                assertArrayEquals(durableManifest, Files.readAllBytes(manifest));
                f.base.offline();
            }
        }
    }

    /** Holds only scheduling, never backend data or manager state. */
    static final class CompletionGate implements AutoCloseable {
        final Fixture fixture;
        final Field ui = SqlDraftCoordinator.class.getDeclaredField("ui");
        final Executor original;
        final List<Runnable> held = new ArrayList<>();
        CompletableFuture<Runnable> next;
        int deliveriesBeforeWorkspace;

        CompletionGate(Fixture fixture) throws Exception {
            this.fixture = fixture;
            ui.setAccessible(true);
            original = fixture.base.call(() -> (Executor) ui.get(fixture.base.owner.runtime()));
            fixture.base.call(() -> { ui.set(fixture.base.owner.runtime(), (Executor) this::execute); return null; });
        }
        synchronized CompletableFuture<Runnable> holdWorkspaceResult() {
            assertNull(next, "only one workspace result may be armed at a time");
            // A manager load first posts refresh's management state, then its workspace result.
            deliveriesBeforeWorkspace = 1;
            return next = new CompletableFuture<>();
        }
        synchronized void execute(Runnable action) {
            if (next != null && deliveriesBeforeWorkspace-- == 0) {
                held.add(action);
                CompletableFuture<Runnable> captured = next; next = null;
                captured.complete(action);
            } else original.execute(action);
        }
        void deliver(Runnable action) throws Exception {
            fixture.base.fx(() -> {
                synchronized (this) { assertTrue(held.remove(action), "completion delivered once"); }
                action.run();
            });
        }
        public void close() throws Exception {
            fixture.base.call(() -> {
                // In failure paths invalidate callbacks before draining every captured result.
                fixture.pane.close();
                ui.set(fixture.base.owner.runtime(), original);
                synchronized (this) {
                    next = null;
                    for (Runnable action : held) action.run();
                    held.clear();
                }
                return null;
            });
            fixture.base.fx(() -> {});
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disabledRecordingOrDraftProtectionStillRestoresOldLayoutWithoutNewWrites(boolean draftsOff) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("checkpoint");
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 8, 1)), draft.id());
            f.base.save(saved);
            if (draftsOff) f.base.call(() -> f.base.owner.runtime().setEnabled(false)).get(5, TimeUnit.SECONDS);
            else f.base.call(() -> f.base.owner.runtime().setWorkspaceEnabled(false)).get(5, TimeUnit.SECONDS);
            f.base.await(() -> !f.base.owner.runtime().managementPending());
            f.open(); f.ready();
            int writes = f.writes.get();
            f.base.fx(() -> {
                assertTrue(f.status().contains(draftsOff ? "不会记录新的布局" : "工作区记录已关闭"), f.status());
                f.button("restore").fire();
            });
            f.ready();
            f.base.fx(() -> { assertEquals("checkpoint", f.base.editor(draft.id()).getText()); f.base.now = 10000; f.base.workspace.pulse(); });
            assertEquals(saved, f.base.snapshot().workspace()); assertEquals(writes, f.writes.get());
        }
    }

    @Test void invalidPreferenceIsNeverDisplayedAsEnabledAndCannotToggle() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            Files.write(f.base.root.resolve("drafts/workspace-preferences.bin"), new byte[] {1});
            f.open(); f.ready();
            f.base.fx(() -> {
                assertTrue(f.status().contains("偏好不可确认")); assertFalse(f.status().contains("记录已开启"));
                assertTrue(f.button("toggle").isDisabled());
            });
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disabledDraftsCloseButPausedDraftGuardStillCancelsWithoutWorkspacePublicationOrDecision(boolean pause) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("checkpoint");
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 8, 1)), draft.id());
            f.base.save(saved);
            if (pause) f.failMethod.set("setEnabled");
            var changed = f.base.call(() -> f.base.owner.runtime().setEnabled(false)).get(5, TimeUnit.SECONDS);
            assertEquals(!pause, changed.succeeded());
            f.base.await(() -> !f.base.owner.runtime().managementPending());
            f.base.fx(() -> assertEquals(pause ? SqlDraftCoordinator.Mode.PAUSED : SqlDraftCoordinator.Mode.DISABLED, f.base.owner.runtime().mode()));
            f.open(); f.ready();
            f.base.fx(() -> f.button("restore").fire()); f.ready();
            int writes = f.writes.get();
            assertEquals(pause ? TabCloseOutcome.CANCELLED : TabCloseOutcome.COMPLETED,
                    f.base.tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(saved, f.base.snapshot().workspace());
            assertEquals(writes, f.writes.get()); assertEquals(0, f.base.decisions.get());
            f.base.fx(() -> {
                assertEquals(pause ? 1 : 0, f.base.tabPane().getTabs().size());
                if (pause) assertEquals("checkpoint", f.base.editor(draft.id()).getText());
            });
        }
    }

    @Test void initializationDefersExactlyOneReadUntilRuntimeReady() throws Exception {
        AtomicReference<SqlDraftUi> owner = new AtomicReference<>();
        AtomicReference<SqlWorkspaceManagerPane> pane = new AtomicReference<>();
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        try {
            FxUiTestSupport.call(() -> {
                ContentTabPane tabs = new ContentTabPane();
                SqlDraftUi drafts = new SqlDraftUi(directory.resolve("initializing"), tabs); owner.set(drafts);
                var recovery = new SqlWorkspaceRecoveryTabs(tabs, drafts,
                        new SqlDraftRecoveryTabs(tabs, drafts, ignored -> { throw new AssertionError("must not construct"); }, ignored -> {}));
                pane.set(new SqlWorkspaceManagerPane(drafts, recovery));
                assertTrue(((Label) pane.get().getNode().lookup("#workspace-manager-status")).getText().contains("初始化中"));
                assertTrue(((Button) pane.get().getNode().lookup("#workspace-manager-restore")).isDisabled());
                subscription.set(drafts.observe(() -> {
                    pane.get().refreshView();
                    if (((Label) pane.get().getNode().lookup("#workspace-manager-status")).getText().contains("没有保存的工作区")) loaded.countDown();
                }));
                return null;
            });
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                Object applied = owner.get().runtime().lastManagementResult();
                for (int i = 0; i < 10; i++) pane.get().refreshView();
                assertSame(applied, owner.get().runtime().lastManagementResult());
                assertFalse(owner.get().runtime().managementPending()); return null;
            });
        } finally {
            FxUiTestSupport.call(() -> { if (pane.get() != null) pane.get().close(); if (subscription.get() != null) subscription.get().close(); return null; });
            if (owner.get() != null) owner.get().closeFromBackground();
        }
    }

    @Test void ordinaryReadFailureLabelsPriorCountsAndRequiresExplicitRetry() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("checkpoint");
            var saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 8, 1)), draft.id());
            f.base.save(saved); f.open(); f.ready();
            f.failMethod.set("workspaceSnapshot");
            f.base.fx(() -> f.button("refresh").fire()); f.ready();
            f.base.fx(() -> {
                assertTrue(f.status().contains("上次读取的恢复点（需刷新）"));
                assertTrue(f.status().contains("可用 1"));
                assertTrue(f.button("restore").isDisabled());
                assertTrue(f.notice().contains("读取失败"));
            });
            f.failMethod.set(null);
            assertEquals(saved, f.base.snapshot().workspace());
            f.base.fx(() -> f.button("refresh").fire()); f.ready();
            f.base.fx(() -> { assertFalse(f.button("restore").isDisabled()); assertFalse(f.status().contains("需刷新")); });
        }
    }

    @Test void clearingAlreadyEmptyManifestIsSuccessfulOperation() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.base.save(new SqlWorkspace(0, List.of(), null)); f.open(); f.ready();
            f.base.fx(() -> SqlDraftManagerTest.respondToDialog(f.button("clear")::fire, dialog ->
                    dialog.getButtonTypes().stream().filter(type -> type != ButtonType.CANCEL).findFirst()
                            .ifPresent(type -> ((Button) dialog.lookupButton(type)).fire())));
            f.ready();
            f.base.fx(() -> { assertTrue(f.notice().contains("已清空")); assertTrue(f.status().contains("工作区为空")); });
            assertEquals(List.of(), f.base.snapshot().workspace().entries());
        }
    }

    @Test void stalePreferenceCannotExecuteToggleUntilExplicitRefresh() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.open(); f.ready();
            f.base.call(() -> f.base.owner.runtime().setWorkspaceEnabled(false)).get(5, TimeUnit.SECONDS);
            f.base.fx(() -> {
                f.pane.refreshView();
                assertTrue(f.button("toggle").isDisabled(), "old enabled snapshot cannot choose an opposite intent");
                f.button("toggle").fire();
            });
            assertFalse(f.base.snapshot().recordingEnabled());
            f.base.fx(() -> f.button("refresh").fire()); f.ready();
            f.base.fx(() -> {
                assertEquals("开启记录 SQL 工作区", f.button("toggle").getText());
                assertFalse(f.button("toggle").isDisabled());
            });
        }
    }

    @Test void callbackFailureShowsFixedNoticeAndKeepsPreviousManifestForRetry() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            UUID missing = UUID.randomUUID();
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(missing, 8, 1)), missing);
            f.base.save(saved); f.open(); f.ready();
            SingleSelectionModel<Tab> original = f.base.call(() -> f.base.tabPane().getSelectionModel());
            try {
                f.base.fx(() -> {
                    f.base.tabPane().setSelectionModel(new SingleSelectionModel<>() {
                        protected Tab getModelItem(int index) { return null; }
                        protected int getItemCount() { return 0; }
                        public void clearSelection() { throw new IllegalStateException("synthetic private selection failure"); }
                    });
                    f.button("restore").fire();
                });
                f.ready();
                f.base.fx(() -> {
                    assertEquals("工作区恢复未完成，已有恢复点保留；请刷新后重试。", f.notice());
                    assertEquals(0, f.base.created.size());
                });
                assertEquals(saved, f.base.snapshot().workspace());
            } finally { f.base.fx(() -> f.base.tabPane().setSelectionModel(original)); }
        }
    }

    @Test void corruptManifestClearRemainsProtectedAndSingleDraftRestoreStillWorks() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("still recoverable");
            byte[] corrupt = {1, 2, 3}; Path manifest = f.base.root.resolve("drafts/workspace.bin");
            Files.write(manifest, corrupt); f.open(); f.ready();
            f.base.fx(() -> {
                assertTrue(f.button("clear").isDisabled()); f.button("clear").fire();
                assertTrue(f.base.single.restore(draft));
                assertEquals("still recoverable", f.base.editor(draft.id()).getText());
            });
            assertArrayEquals(corrupt, Files.readAllBytes(manifest));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void toggleWaitsForPersistenceAndFailedDisableStaysSessionPausedUntilExplicitEnable(boolean failDisable) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.open(); f.ready();
            f.blockMethod.set("setWorkspaceEnabled");
            if (failDisable) f.failMethod.set("setWorkspaceEnabled");
            f.base.fx(() -> f.button("toggle").fire());
            assertTrue(f.entered.await(5, TimeUnit.SECONDS));
            f.base.fx(() -> {
                assertTrue(f.button("toggle").isDisabled());
                assertEquals("正在保存工作区设置…", f.notice());
                assertFalse(f.notice().contains("已关闭"));
            });
            f.release.countDown(); f.ready();
            assertEquals(failDisable, f.base.snapshot().recordingEnabled());
            f.base.fx(() -> {
                assertTrue(f.status().contains(failDisable ? "本次已暂停" : "工作区记录已关闭"), f.status());
                f.button("refresh").fire();
            });
            f.ready();
            f.base.fx(() -> assertTrue(f.status().contains(failDisable ? "本次已暂停" : "工作区记录已关闭")));
            f.failMethod.set(null);
            f.base.fx(() -> f.button("toggle").fire()); f.ready();
            assertTrue(f.base.snapshot().recordingEnabled());
            f.base.fx(() -> {
                assertTrue(f.status().contains("工作区记录已开启"));
                assertFalse(f.status().contains("本次已暂停"));
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "success", "failure"})
    void clearOnlyChangesManifestAfterConfirmationAndFailureRetainsRecoveryCount(String decision) throws Exception {
        try (Fixture f = new Fixture(directory)) {
            SqlDraft draft = f.base.seed("checkpoint");
            SqlWorkspace saved = new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 8, 1)), draft.id());
            f.base.save(saved); f.open(); f.ready();
            f.base.fx(() -> assertTrue(f.base.single.restore(draft)));
            if (decision.equals("failure")) f.failMethod.set("clearWorkspace");
            f.base.fx(() -> SqlDraftManagerTest.respondToDialog(f.button("clear")::fire, dialog -> {
                assertTrue(((Button) dialog.lookupButton(ButtonType.CANCEL)).isDefaultButton());
                assertTrue(dialog.getContentText().contains("不删除 SQL 草稿"));
                if (!decision.equals("cancel")) dialog.getButtonTypes().stream()
                        .filter(type -> type != ButtonType.CANCEL).findFirst()
                        .ifPresent(type -> ((Button) dialog.lookupButton(type)).fire());
            }));
            f.ready();
            assertEquals(decision.equals("success") ? List.of() : saved.entries(), f.base.snapshot().workspace().entries());
            f.base.fx(() -> {
                assertEquals("checkpoint", f.base.editor(draft.id()).getText());
                assertSame(f.base.tab(draft.id()), f.base.tabPane().getSelectionModel().getSelectedItem());
                if (decision.equals("failure")) {
                    assertTrue(f.notice().contains("未完成")); assertTrue(f.status().contains("可用 1"));
                } else if (decision.equals("success")) assertTrue(f.status().contains("工作区为空"));
            });
            assertEquals(1, f.base.call(() -> f.base.owner.runtime().refresh()).get(5, TimeUnit.SECONDS).snapshot().drafts().size());
        }
    }

    static final class Fixture implements AutoCloseable {
        final SqlWorkspaceRecoveryTabsTest.Fixture base;
        SqlWorkspaceManagerPane pane;
        AutoCloseable subscription;
        final AtomicReference<String> failMethod = new AtomicReference<>(), blockMethod = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        final AtomicBoolean structuralSaveFailure = new AtomicBoolean();
        Fixture(Path directory) throws Exception {
            base = new SqlWorkspaceRecoveryTabsTest.Fixture(directory);
            base.fx(() -> {
                try {
                    Field field = SqlDraftCoordinator.class.getDeclaredField("backend"); field.setAccessible(true);
                    Object actual = field.get(base.owner.runtime());
                    field.set(base.owner.runtime(), Proxy.newProxyInstance(actual.getClass().getClassLoader(),
                            new Class<?>[] {field.getType()}, (proxy, method, args) -> {
                                if (method.getName().equals("workspaceSnapshot")) reads.incrementAndGet();
                                if (method.getName().equals("saveWorkspace")) writes.incrementAndGet();
                                boolean gated = method.getName().equals(blockMethod.getAndUpdate(value -> method.getName().equals(value) ? null : value));
                                boolean snapshotGate = gated && method.getName().equals("workspaceSnapshot");
                                method.setAccessible(true);
                                Object snapshot = null;
                                if (snapshotGate) {
                                    try { snapshot = method.invoke(actual, args); }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                }
                                if (gated) {
                                    entered.countDown();
                                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fault gate timeout");
                                }
                                if (method.getName().equals(failMethod.get())) throw new java.io.IOException("synthetic private failure");
                                if (method.getName().equals("saveWorkspace") && structuralSaveFailure.get())
                                    throw new IllegalStateException("synthetic structural backend failure");
                                if (snapshotGate) return snapshot;
                                try { return method.invoke(actual, args); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            }));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        }
        void open() throws Exception {
            base.fx(() -> {
                pane = new SqlWorkspaceManagerPane(base.owner, base.batch);
                subscription = base.owner.observe(pane::refreshView);
                pane.refreshView();
            });
        }
        Button button(String suffix) { return (Button) pane.getNode().lookup("#workspace-manager-" + suffix); }
        String health() { return ((Label) pane.getNode().lookup("#workspace-manager-activity-status")).getText(); }
        SqlDraft failedSave() throws Exception {
            SqlDraft draft = base.seed("select checkpoint");
            base.save(new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(draft.id(), 1, 1)), draft.id()));
            open(); ready(); base.fx(() -> button("restore").fire()); ready();
            base.await(() -> base.workspace.owner().status() == SqlWorkspaceActivity.Status.SAVED);
            failMethod.set("saveWorkspace");
            base.fx(() -> {
                base.editor(draft.id()).selectRange(2, 6);
                base.workspace.activity(); base.now = 10000; base.workspace.pulse();
            });
            base.await(() -> base.workspace.owner().status() == SqlWorkspaceActivity.Status.FAILED);
            base.fx(pane::refreshView);
            return draft;
        }
        String status() { return ((Label) pane.getNode().lookup("#workspace-manager-status")).getText(); }
        String notice() { return ((Label) pane.getNode().lookup("#workspace-manager-notice")).getText(); }
        void ready() throws Exception { base.await(() -> !button("refresh").isDisabled()); }
        public void close() throws Exception {
            release.countDown(); failMethod.set(null);
            base.fx(() -> {
                if (pane != null) pane.close();
                if (subscription != null) try { subscription.close(); } catch (Exception failure) { throw new AssertionError(failure); }
            });
            base.close();
        }
    }
}
