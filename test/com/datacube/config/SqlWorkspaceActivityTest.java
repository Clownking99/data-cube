package com.datacube.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavioral contract for P2.3b activity capture; implementation follows this RED suite. */
class SqlWorkspaceActivityTest {
    @TempDir Path temp;
    private static final UUID A = new UUID(0, 1);
    private static final long WALL = 1_788_000_000_000L;

    @Test void untouchedSessionAndExitPreservePreviousLayout() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.ready();
            SqlWorkspace previous = new SqlWorkspace(10, List.of(new SqlWorkspace.Entry(A, 7, 2)), A);
            fixture.store.saveWorkspace(previous);
            SqlWorkspaceActivity activity = new SqlWorkspaceActivity(fixture.runtime, () -> 0);

            activity.pulse();
            var frozen = activity.freezeForExit();
            fixture.cycle();
            frozen.join();

            assertEquals(previous, fixture.store.workspaceSnapshot().workspace());
            assertFalse(fixture.events.contains("workspace"), "untouched exit must not publish a layout");
        }
    }

    @Test void changedLayoutPublishesAtIdleDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.ready();
            long[] now = {0};
            SqlWorkspace expected = new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, 11, 3)), A);
            SqlWorkspaceActivity activity = new SqlWorkspaceActivity(fixture.runtime, () -> now[0]);

            activity.activity(expected);
            now[0] = 1_000;
            activity.pulse();
            fixture.cycle();

            assertEquals(expected, fixture.store.workspaceSnapshot().workspace());
            assertEquals(List.of("workspace"), fixture.events);
            assertEquals(SqlWorkspaceActivity.Status.SAVED, activity.status());
        }
    }

    @Test void continuousActivityIsCoalescedWithBoundedDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.ready();
            long[] now = {0};
            SqlWorkspaceActivity activity = new SqlWorkspaceActivity(fixture.runtime, () -> now[0]);
            SqlWorkspace latest = null;
            for (long tick = 0; tick < 10_000; tick += 900) {
                now[0] = tick;
                latest = new SqlWorkspace(tick, List.of(new SqlWorkspace.Entry(A, (int) tick, 0)), A);
                activity.activity(latest);
                activity.pulse();
            }

            now[0] = 9_999;
            activity.pulse();
            fixture.cycle();
            assertFalse(fixture.events.contains("workspace"), "continuous input must remain coalesced before max deadline");

            now[0] = 10_000;
            activity.pulse();
            fixture.cycle();
            assertEquals(latest, fixture.store.workspaceSnapshot().workspace());
            assertEquals(List.of("workspace"), fixture.events);
        }
    }

    @Test void managementInvalidatesUnsubmittedCandidate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.ready();
            long[] now = {0};
            SqlWorkspace previous = new SqlWorkspace(8, List.of(new SqlWorkspace.Entry(A, 1, 0)), A);
            fixture.store.saveWorkspace(previous);
            SqlWorkspaceActivity activity = new SqlWorkspaceActivity(fixture.runtime, () -> now[0]);

            activity.activity(new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, 4, 2)), A));
            var cleared = fixture.runtime.clearWorkspace();
            fixture.cycle();
            cleared.join();
            now[0] = 1_000;
            activity.pulse();
            fixture.cycle();

            assertEquals(new SqlWorkspace(0, List.of(), null), fixture.store.workspaceSnapshot().workspace());
            assertFalse(fixture.events.contains("workspace"), "pre-management candidate must not be retagged");
        }
    }

    @Test void ordinaryFailureRequiresExplicitRetry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.ready();
            long[] now = {0};
            SqlWorkspace expected = new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, 4, 2)), A);
            SqlWorkspaceActivity activity = new SqlWorkspaceActivity(fixture.runtime, () -> now[0]);
            fixture.workspaceFailure = new IOException("synthetic write failure");
            activity.activity(expected);
            now[0] = 1_000;
            activity.pulse();
            fixture.cycle();
            assertEquals(SqlWorkspaceActivity.Status.FAILED, activity.status());

            now[0] = 2_000;
            expected = new SqlWorkspace(2_000, List.of(new SqlWorkspace.Entry(A, 7, 1)), A);
            activity.activity(expected);
            now[0] = 4_000;
            activity.pulse();
            fixture.cycle();
            assertEquals(1, fixture.events.size(), "failure must latch rather than retry on a timer pulse");

            fixture.workspaceFailure = null;
            activity.retry().join();
            activity.pulse();
            fixture.cycle();
            assertEquals(expected, fixture.store.workspaceSnapshot().workspace());
            assertEquals(2, fixture.events.size());
        }
    }

    @Test void timestampOnlyObservationDoesNotWriteAgain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); long[] now = {0};
            var owner = new SqlWorkspaceActivity(f.runtime, () -> now[0]);
            var first = new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, 5, 2)), A);
            owner.activity(first); now[0] = 1000; owner.pulse(); f.cycle();
            owner.activity(new SqlWorkspace(2000, first.entries(), A));
            now[0] = 4000; owner.pulse(); f.cycle();
            assertEquals(first, f.store.workspaceSnapshot().workspace());
            assertEquals(1, f.events.size());
        }
    }

    @Test void failedDisableRemainsPausedUntilExplicitSuccessfulEnable() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); long[] now = {0};
            var owner = new SqlWorkspaceActivity(f.runtime, () -> now[0]);
            var old = new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, 1, 0)), A);
            f.store.saveWorkspace(old);
            owner.activity(new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(A, 8, 2)), A));
            f.preferenceFailure = new IOException("PRIVATE_PATH");
            var disable = owner.setWorkspaceEnabled(false); f.cycle();
            assertEquals(true, disable.isCompletedExceptionally());
            assertEquals(true, f.store.workspaceSnapshot().recordingEnabled());
            owner.activity(new SqlWorkspace(2, List.of(new SqlWorkspace.Entry(A, 9, 3)), A));
            now[0] = 3000; owner.pulse(); f.cycle();
            assertEquals(old, f.store.workspaceSnapshot().workspace());
            assertEquals(SqlWorkspaceActivity.Status.SESSION_PAUSED, owner.status());
            f.preferenceFailure = null;
            var enabled = owner.setWorkspaceEnabled(true); f.cycle(); enabled.join();
            now[0] = 5000; owner.pulse(); f.cycle();
            assertEquals(old, f.store.workspaceSnapshot().workspace(), "enable never revives old capture");
            var latest = new SqlWorkspace(5, List.of(new SqlWorkspace.Entry(A, 10, 4)), A);
            owner.activity(latest); now[0] = 6000; owner.pulse(); f.cycle();
            assertEquals(latest, f.store.workspaceSnapshot().workspace());
        }
    }

    @Test void runtimeBusyIsBackpressureNotFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); long[] now = {0};
            var owner = new SqlWorkspaceActivity(f.runtime, () -> now[0]);
            var layout = new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, 3, 2)), A);
            owner.activity(layout); f.cycle();
            var refresh = f.runtime.refresh();
            now[0] = 1000; owner.pulse();
            assertFalse(owner.status() == SqlWorkspaceActivity.Status.FAILED);
            f.cycle(); refresh.join(); owner.pulse(); f.cycle();
            assertEquals(layout, f.store.workspaceSnapshot().workspace());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"clear", "delete", "draftOff", "draftOn", "workspaceClear", "workspaceOff", "workspaceOn", "failedClear", "failedWorkspaceOff"})
    void everyAcceptedManagementInvalidatesOldCapture(String operation) throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); long[] now = {0};
            var old = new SqlWorkspace(10, List.of(new SqlWorkspace.Entry(A, 2, 0)), A);
            f.store.saveWorkspace(old);
            var owner = new SqlWorkspaceActivity(f.runtime, () -> now[0]);
            owner.activity(new SqlWorkspace(11, List.of(new SqlWorkspace.Entry(A, 8, 6)), A)); f.cycle();
            if (operation.equals("failedClear")) f.managementFailure = new IOException("failure");
            if (operation.equals("failedWorkspaceOff")) f.preferenceFailure = new IOException("failure");
            switch (operation) {
                case "clear", "failedClear" -> f.runtime.clear();
                case "delete" -> f.runtime.delete(A);
                case "draftOff" -> f.runtime.setEnabled(false);
                case "draftOn" -> f.runtime.setEnabled(true);
                case "workspaceClear" -> f.runtime.clearWorkspace();
                case "workspaceOff", "failedWorkspaceOff" -> f.runtime.setWorkspaceEnabled(false);
                case "workspaceOn" -> f.runtime.setWorkspaceEnabled(true);
            }
            f.cycle(); now[0] = 10000; owner.pulse(); f.cycle();
            assertEquals(operation.equals("workspaceClear") ? new SqlWorkspace(0, List.of(), null) : old,
                    f.store.workspaceSnapshot().workspace());
            assertTrueNoWorkspaceWrites(f);
        }
    }

    @Test void staleAcceptedWriteCompletionCannotReleaseFailedDisablePause() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); long[] now={0};
            var owner = new SqlWorkspaceActivity(f.runtime, () -> now[0]);
            owner.activity(new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, 1, 0)), A)); f.cycle();
            now[0]=1000; owner.pulse();
            f.preferenceFailure = new IOException("failure");
            owner.setWorkspaceEnabled(false); f.cycle();
            assertEquals(SqlWorkspaceActivity.Status.SESSION_PAUSED, owner.status());
            owner.activity(new SqlWorkspace(1, List.of(new SqlWorkspace.Entry(A, 9, 2)), A));
            now[0]=3000; owner.pulse(); f.cycle();
            assertTrueNoWorkspaceWrites(f);
            assertEquals(SqlWorkspaceActivity.Status.SESSION_PAUSED, owner.status());
        }
    }

    private static void assertTrueNoWorkspaceWrites(Fixture f) { assertEquals(List.of(), f.events); }

    @Test void successfulDisableReportsDisabledAndPreservesLayout() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready(); var previous=new SqlWorkspace(0,List.of(new SqlWorkspace.Entry(A,4,1)),A);
            f.store.saveWorkspace(previous);
            var owner=new SqlWorkspaceActivity(f.runtime,()->10000);
            var result=owner.setWorkspaceEnabled(false); f.cycle(); result.join();
            assertEquals(SqlWorkspaceActivity.Status.DISABLED,owner.status());
            owner.activity(new SqlWorkspace(1,List.of(),null)); owner.pulse(); f.cycle();
            assertEquals(previous,f.store.workspaceSnapshot().workspace()); assertTrueNoWorkspaceWrites(f);
        }
    }

    @Test void firstActivityReadsDisabledPreferenceWithoutTryingPublication() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready(); f.store.setWorkspaceEnabled(false);
            var owner=new SqlWorkspaceActivity(f.runtime,()->10000);
            owner.activity(new SqlWorkspace(0,List.of(new SqlWorkspace.Entry(A,4,1)),A)); f.cycle();
            assertEquals(SqlWorkspaceActivity.Status.DISABLED,owner.status());
            owner.pulse(); f.cycle(); assertTrueNoWorkspaceWrites(f);
        }
    }

    @Test void failedSnapshotIsNotTreatedAsEmptyEnabledLayoutAndRequiresRetry() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready(); f.readFailure=new IOException("PRIVATE_SQL_PATH");
            long[] now={0}; var owner=new SqlWorkspaceActivity(f.runtime,()->now[0]);
            var value=new SqlWorkspace(0,List.of(new SqlWorkspace.Entry(A,4,1)),A);
            owner.activity(value); f.cycle();
            assertEquals(SqlWorkspaceActivity.Status.FAILED,owner.status());
            f.readFailure=null; owner.activity(value); now[0]=2000; owner.pulse(); f.cycle();
            assertTrueNoWorkspaceWrites(f);
            assertFalse(owner.statusText().contains("PRIVATE"));
            owner.retry(); owner.pulse(); f.cycle();
            assertEquals(value,f.store.workspaceSnapshot().workspace());
        }
    }

    @Test void corruptPreferenceProtectsExistingLayout() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready(); var previous=new SqlWorkspace(0,List.of(new SqlWorkspace.Entry(A,4,1)),A);
            f.store.saveWorkspace(previous);
            java.nio.file.Files.write(f.path.resolve("workspace-preferences.bin"),new byte[]{1,2,3});
            var owner=new SqlWorkspaceActivity(f.runtime,()->10000);
            owner.activity(new SqlWorkspace(1,List.of(),null)); f.cycle(); owner.pulse(); f.cycle();
            assertEquals(SqlWorkspaceActivity.Status.FAILED,owner.status());
            assertEquals(SqlWorkspaceStore.FailureCode.PREFERENCE_CORRUPT,owner.failureCode());
            assertEquals(previous,f.store.workspaceSnapshot().workspace()); assertTrueNoWorkspaceWrites(f);
        }
    }

    @Test void lateReadCompletionCannotOverwritePauseOrNewGenerationCandidate() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready(); long[] now={0}; var owner=new SqlWorkspaceActivity(f.runtime,()->now[0]);
            owner.activity(new SqlWorkspace(0,List.of(new SqlWorkspace.Entry(A,1,0)),A));
            f.diskOnly(); // old enabled=true snapshot is waiting for UI delivery
            f.preferenceFailure=new IOException("failure"); owner.setWorkspaceEnabled(false);
            f.cycle();
            assertEquals(SqlWorkspaceActivity.Status.SESSION_PAUSED,owner.status());
            now[0]=2000;owner.pulse();f.cycle();assertTrueNoWorkspaceWrites(f);
        }
    }

    @Test void explicitSameLayoutActivityReleasesFrozenStatusWithoutTimestampWrite() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready();long[] now={0};var owner=new SqlWorkspaceActivity(f.runtime,()->now[0]);
            var layout=new SqlWorkspace(0,List.of(new SqlWorkspace.Entry(A,4,2)),A);
            owner.activity(layout);f.cycle();now[0]=1000;owner.pulse();f.cycle();
            owner.freezeForExit(layout);
            owner.activity(new SqlWorkspace(1,layout.entries(),A));
            assertFalse(owner.status()==SqlWorkspaceActivity.Status.FROZEN);
            now[0]=3000;owner.pulse();f.cycle();assertEquals(1,f.events.size());
        }
    }

    @Test void callerCancelledFrozenPublicationStillPersistsAndSettlesInternalAdmission() throws Exception {
        try(Fixture f=new Fixture()) {
            f.ready();long[] now={0};var owner=new SqlWorkspaceActivity(f.runtime,()->now[0]);
            var first=new SqlWorkspace(0,List.of(new SqlWorkspace.Entry(A,4,2)),A);
            var last=new SqlWorkspace(1,List.of(new SqlWorkspace.Entry(A,9,2)),A);
            owner.activity(first);f.cycle();now[0]=1000;owner.pulse();
            var frozen=owner.freezeForExit(last);
            var caller=owner.saveFrozen(frozen,last);caller.cancel(false);f.cycle();
            assertEquals(last,f.store.workspaceSnapshot().workspace());
            assertEquals(2,f.events.size());
        }
    }

    @Test void busyKeepsLatestCandidateAndDoesNotLoseCompletion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.ready();
            long[] now = {0};
            SqlWorkspace first = new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, 1, 0)), A);
            SqlWorkspace latest = new SqlWorkspace(1_001, List.of(new SqlWorkspace.Entry(A, 9, 5)), A);
            SqlWorkspaceActivity activity = new SqlWorkspaceActivity(fixture.runtime, () -> now[0]);

            activity.activity(first);
            fixture.cycle(); // settle the lazy preference/layout read before holding the first write
            now[0] = 1_000;
            activity.pulse();
            activity.activity(latest);
            fixture.cycle();
            assertEquals(first, fixture.store.workspaceSnapshot().workspace());

            now[0] = 2_001;
            activity.pulse();
            fixture.cycle();
            assertEquals(latest, fixture.store.workspaceSnapshot().workspace());
            assertEquals(2, fixture.events.size());
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path path = temp.resolve("drafts");
        final ArrayDeque<Runnable> diskTasks = new ArrayDeque<>();
        final ArrayDeque<Runnable> uiTasks = new ArrayDeque<>();
        final List<String> events = new java.util.ArrayList<>();
        boolean onUi = true;
        IOException workspaceFailure;
        IOException preferenceFailure;
        IOException managementFailure;
        IOException readFailure;
        SqlDraftStore store;
        final SqlDraftCoordinator runtime = new SqlDraftCoordinator(() -> {
            assertFalse(onUi, "store access must use writer executor");
            store = SqlDraftStore.open(path);
            return new SqlDraftCoordinator.Backend() {
                public void save(SqlDraft value) throws IOException { store.save(value); }
                public SqlDraftStore.Snapshot snapshot() throws IOException { return store.snapshot(); }
                public void setEnabled(boolean enabled) throws IOException { store.setEnabled(enabled); }
                public void clear() throws IOException {
                    if (managementFailure != null) throw managementFailure;
                    store.clearRecoverable();
                }
                public void delete(UUID id) throws IOException { store.delete(id); }
                public void prune(long now, Set<UUID> ids) throws IOException { store.pruneExpired(now, ids); }
                public void close() throws IOException { store.close(); }
                public SqlWorkspaceStore.Snapshot workspaceSnapshot() throws IOException {
                    if(readFailure!=null) throw readFailure;
                    return store.workspaceSnapshot();
                }
                public void saveWorkspace(SqlWorkspace value) throws IOException {
                    events.add("workspace");
                    if (workspaceFailure != null) throw workspaceFailure;
                    store.saveWorkspace(value);
                }
                public void setWorkspaceEnabled(boolean enabled) throws IOException {
                    if (preferenceFailure != null) throw preferenceFailure;
                    store.setWorkspaceEnabled(enabled);
                }
                public boolean clearWorkspace() throws IOException { return store.clearWorkspace(); }
            };
        }, diskTasks::add, uiTasks::add, () -> onUi, () -> 0, () -> WALL);

        void ready() { cycle(); }
        void diskOnly() {
            onUi=false;
            try {while(!diskTasks.isEmpty())diskTasks.remove().run();}
            finally{onUi=true;}
        }
        void cycle() {
            do {
            onUi = false;
            try { while (!diskTasks.isEmpty()) diskTasks.remove().run(); }
            finally { onUi = true; }
            while (!uiTasks.isEmpty()) uiTasks.remove().run();
            } while (!diskTasks.isEmpty() || !uiTasks.isEmpty());
        }
        @Override public void close() throws Exception {
            var closed = runtime.shutdown();
            cycle();
            closed.join();
        }
    }
}
