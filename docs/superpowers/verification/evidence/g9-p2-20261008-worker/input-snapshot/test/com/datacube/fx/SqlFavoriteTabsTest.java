package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.*;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javafx.scene.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlFavoriteTabsTest {
    @TempDir Path directory;
    @Test void favoriteIsAnIndependentManagedOfflineScriptWithExplicitConnectionAndDraftLifecycle() throws Exception {
        var probe=new DraftConnectionProbe(); var runner=new FxTaskRunner(); var panes=new ArrayList<SqlEditorPane>();
        var bound=new AtomicInteger(); var installed=new AtomicInteger(); var texts=new ArrayList<String>();
        var favorite=new SqlFavorite(UUID.randomUUID(),"synthetic","group","select '中😀';",1);
        var target=new ConnConfig("synthetic","same-name",DbType.POSTGRESQL,"example.invalid",1,"db","","",Map.of());
        probe.manager.register(target);
        try {
            FxUiTestSupport.call(() -> {
                var tabs=new ContentTabPane(); new Scene((Parent)tabs.getNode(),900,800); var registry=new SqlFileTabRegistry();
                java.util.function.BooleanSupplier open=() -> SqlFavoriteTabs.open(tabs,favorite,() -> {
                    var pane=SqlEditorPane.openSqlFile(new SessionContext(),probe.manager,new ObjectTreeService(probe.manager),
                            new AppSettings(directory.resolve("settings")),(id,ref) -> fail("no navigation"),
                            new SqlHistoryStore(directory.resolve("history")),new ShortcutSettings(directory.resolve("shortcuts")),runner);
                    // Create control skins before inspecting an off-scene tab's controls.
                    var setupScene = new Scene((Parent) pane.getNode());
                    pane.getNode().applyCss(); setupScene.setRoot(new Group());
                    panes.add(pane); return pane;
                },() -> List.of(target),new SqlScriptFileStore(),new RecentSqlFiles(directory.resolve("recent")),
                        new AppShell.SqlFileDraftLifecycle() {
                            public void bind(SqlEditorPane pane) { bound.incrementAndGet(); texts.add(SqlEditorPane.favoriteText(pane.getNode())); }
                            public void installed(Node node) { installed.incrementAndGet(); }
                        },registry);
                assertTrue(open.getAsBoolean()); assertTrue(open.getAsBoolean());
                assertEquals(2,((TabPane)tabs.getNode()).getTabs().size()); assertEquals(List.of(favorite.sql(),favorite.sql()),texts);
                var pane=panes.getFirst();
                assertTrue(pane.getNode().lookup("#sql-execute").isDisabled());
                assertNotNull(pane.getNode().lookup("#sql-file-connection"));
                assertFalse(pane.getNode().lookup("#sql-file-save-as").isDisabled());
                pane.setSqlText("select 2;");
                assertEquals("select 2;",SqlEditorPane.favoriteText(pane.getNode()));
                assertEquals(favorite.sql(),SqlEditorPane.favoriteText(panes.getLast().getNode()));
                assertTrue(pane.chooseFileConnection(target));
                assertEquals("SQL - 收藏 - synthetic*",((TabPane)tabs.getNode()).getTabs().getFirst().getText());
                return null;
            });
            assertEquals(2,bound.get()); assertEquals(2,installed.get());
            assertFalse(Files.exists(directory.resolve("history"))); assertFalse(Files.exists(directory.resolve("recent")));
            assertEquals(0,probe.providers.get()+probe.sessions.get()+probe.metadata.get()+probe.network.get());
            panes.getFirst().closeResources();
            FxUiTestSupport.call(() -> { assertNull(SqlEditorPane.favoriteText(panes.getFirst().getNode())); return null; });
        } finally {
            panes.forEach(SqlEditorPane::closeResources);
            FxUiTestSupport.call(() -> { panes.forEach(SqlEditorPane::finalizeCloseOnFx); return null; });
            runner.close(); probe.manager.closeAll();
        }
    }
}
