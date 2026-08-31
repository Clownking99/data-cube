package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.config.CredentialCipher;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceStartPaneTest {
    @TempDir Path directory;

    @Test void recoveryEntryOnlyInvokesCallbackOnClickAndKeepsExistingTabPane() throws Exception {
        FxUiTestSupport.call(() -> {
            AtomicInteger recovered = new AtomicInteger();
            ContentTabPane tabs = new ContentTabPane();
            Parent root = (Parent) AppShell.startWorkspace(tabs, () -> fail("create"), () -> fail("focus"), recovered::incrementAndGet);
            Node original = tabs.getNode();
            Button recovery = (Button) root.lookup("#start-restore-workspace");
            assertNotNull(recovery);
            assertEquals("恢复 SQL 工作区…", recovery.getText());
            assertEquals(0, recovered.get());
            recovery.fire(); assertEquals(1, recovered.get());
            Tab opened = tabs.openTab("synthetic", new Group());
            assertSame(original, tabs.getNode());
            assertFalse(root.lookup("#workspace-start").isVisible());
            ((TabPane) original).getTabs().remove(opened);
            assertTrue(root.lookup("#workspace-start").isVisible());
            assertEquals(1, recovered.get());
            assertNull(new WorkspaceStartPane(() -> {}, () -> {}).lookup("#start-restore-workspace"));
            return null;
        });
    }

    @Test
    void emptyOpenAndCloseKeepTheSameTabNode() throws Exception {
        FxUiTestSupport.call(() -> {
            ContentTabPane tabs = new ContentTabPane();
            Parent root = (Parent) AppShell.startWorkspace(tabs, () -> {}, () -> {});
            Node start = root.lookup("#workspace-start");
            Node original = tabs.getNode();
            assertTrue(start.isVisible());
            assertTrue(start.isManaged());
            assertFalse(original.isVisible());
            assertFalse(original.isManaged());
            Tab first = tabs.openTab("SQL", new Group());
            Tab second = tabs.openTab("DDL", new Group());
            assertFalse(start.isVisible());
            assertFalse(start.isManaged());
            assertSame(original, tabs.getNode());
            assertTrue(original.isVisible());
            assertTrue(original.isManaged());
            ((TabPane) original).getTabs().remove(first);
            assertFalse(start.isVisible(), "one remaining tab still owns the workspace");
            ((TabPane) original).getTabs().remove(second);
            assertTrue(start.isVisible());
            assertTrue(start.isManaged());
            assertTrue(tabs.emptyProperty().get());
            assertTrue(root.getChildrenUnmodifiable().contains(original));
            return null;
        });
    }

    @Test
    void actionsDoNothingUntilClickedAndInvokeOnlyTheirCallback() throws Exception {
        FxUiTestSupport.call(() -> {
            AtomicInteger create = new AtomicInteger();
            AtomicInteger focus = new AtomicInteger();
            WorkspaceStartPane pane = new WorkspaceStartPane(create::incrementAndGet, focus::incrementAndGet);
            assertEquals(0, create.get());
            assertEquals(0, focus.get());
            ((Button) pane.lookup("#start-new-connection")).fire();
            assertEquals(1, create.get());
            assertEquals(0, focus.get());
            ((Button) pane.lookup("#start-select-connection")).fire();
            assertEquals(1, create.get());
            assertEquals(1, focus.get());
            return null;
        });
    }

    @Test
    void permanentTabKeepsTheStartPaneHidden() throws Exception {
        FxUiTestSupport.call(() -> {
            ContentTabPane tabs = new ContentTabPane();
            tabs.addPermanentTab("Migration", new Group());
            Parent root = (Parent) AppShell.startWorkspace(tabs, () -> {}, () -> {});
            assertFalse(root.lookup("#workspace-start").isVisible());
            Tab transientTab = tabs.openTab("SQL", new Group());
            ((TabPane) tabs.getNode()).getTabs().remove(transientTab);
            assertFalse(tabs.emptyProperty().get());
            assertFalse(root.lookup("#workspace-start").isVisible());
            return null;
        });
    }

    @Test
    void pendingManagedCloseKeepsContentUntilApproved() throws Exception {
        CompletableFuture<CloseGuardOutcome> decision = new CompletableFuture<>();
        AtomicInteger finalized = new AtomicInteger();
        CloseFixture fixture = FxUiTestSupport.call(() -> managedFixture(decision, finalized));
        FxUiTestSupport.call(() -> {
            assertFalse(fixture.closed().toCompletableFuture().isDone());
            assertFalse(fixture.tabs().emptyProperty().get());
            assertFalse(fixture.root().lookup("#workspace-start").isVisible());
            assertEquals(0, finalized.get());
            return null;
        });
        decision.complete(CloseGuardOutcome.APPROVED);
        fixture.closed().toCompletableFuture().get(5, TimeUnit.SECONDS);
        FxUiTestSupport.call(() -> {
            assertTrue(fixture.tabs().emptyProperty().get());
            assertTrue(fixture.root().lookup("#workspace-start").isVisible());
            assertEquals(1, finalized.get());
            return null;
        });
    }

    @Test
    void rejectedManagedCloseDoesNotReplaceTheContent() throws Exception {
        AtomicInteger finalized = new AtomicInteger();
        CloseFixture fixture = FxUiTestSupport.call(() -> managedFixture(
                CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED), finalized));
        fixture.closed().toCompletableFuture().get(5, TimeUnit.SECONDS);
        FxUiTestSupport.call(() -> {
            assertFalse(fixture.tabs().emptyProperty().get());
            assertFalse(fixture.root().lookup("#workspace-start").isVisible());
            assertTrue(fixture.tabs().getNode().isVisible());
            assertEquals(0, finalized.get());
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void focusConnectionsNeverSelectsExpandsOrConnects(boolean savedConnection) throws Exception {
        ConnectionStore store = new ConnectionStore(directory.resolve("connections.json"));
        if (savedConnection) store.saveAll(List.of(new ConnConfig("saved", "saved", DbType.POSTGRESQL,
                "example.invalid", 5432, "db", "user", "", Map.of())));
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                ConnectionManager manager = new ConnectionManager(new CredentialCipher());
                SessionContext session = new SessionContext();
                // No metadata/action collaborator: focusing must never invoke either.
                try (ConnectionTreePane pane = new ConnectionTreePane(store, manager, null, session, null, runner)) {
                    Scene scene = new Scene(new VBox(pane.getNode()));
                    TreeView<?> tree = (TreeView<?>) ((VBox) pane.getNode()).getChildren().getFirst();
                    pane.focusConnections();
                    assertSame(tree, scene.getFocusOwner());
                    assertNull(tree.getSelectionModel().getSelectedItem());
                    assertNull(session.getActiveConnection());
                    assertEquals(savedConnection ? 1 : 0, tree.getRoot().getChildren().size());
                    if (savedConnection) {
                        assertFalse(tree.getRoot().getChildren().getFirst().isExpanded());
                        assertFalse(manager.isConnected("saved"));
                    }
                }
                return null;
            });
        }
    }

    private static CloseFixture managedFixture(CompletionStage<CloseGuardOutcome> decision,
                                                AtomicInteger finalized) {
        ContentTabPane tabs = new ContentTabPane();
        Parent root = (Parent) AppShell.startWorkspace(tabs, () -> {}, () -> {});
        tabs.openManagedTab("protected", () -> new ContentTabPane.ManagedTabSpec(
                new Group(), () -> decision, finalized::incrementAndGet,
                () -> fail("installed tab must not abort")));
        return new CloseFixture(tabs, root, tabs.closeAllManagedTabs());
    }

    private record CloseFixture(ContentTabPane tabs, Parent root, CompletionStage<?> closed) {}
}
