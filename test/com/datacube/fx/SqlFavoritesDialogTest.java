package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlFavoritesDialogTest {
    @Test void localRepositoryWorksBeforeAnyOtherFeatureCreatesTheProfileDirectory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var directory=temp.resolve("fresh-profile/.datacube/sql-favorites");
        java.nio.file.Files.createDirectory(temp.resolve("fresh-profile"));
        var repository=SqlFavoritesDialog.local(directory);
        assertTrue(repository.load().favorites().isEmpty());
        var value=favorite("fresh","local"); repository.save(value,null);
        assertEquals(List.of(value),repository.load().favorites());
        assertFalse(java.nio.file.Files.exists(temp.resolve("fresh-profile/.datacube/connections.json")));
        assertFalse(java.nio.file.Files.exists(temp.resolve("fresh-profile/.datacube/sql-history.json")));
    }
    private static SqlFavorite favorite(String name,String group) {
        return new SqlFavorite(UUID.randomUUID(),name,group,"select 'synthetic 中😀';",1);
    }
    @Test void saveEditFilterAndOpenUseStoredIdentityWithoutExecutingAnything() throws Exception {
        var repo=new Repository(); repo.values.add(favorite("original","alpha"));
        try(var f=new Fixture(repo,"select 2;")) {
            f.idle();
            FxUiTestSupport.call(() -> {
                assertTrue(f.list().isDisabled()); assertTrue(f.button("save").isDisabled());
                f.text("name").setText("新收藏"); f.text("group").setText("beta"); f.button("save").fire(); return null;
            }); f.idle();
            var saved=repo.values.stream().filter(v -> v.name().equals("新收藏")).findFirst().orElseThrow();
            assertEquals("select 2;",saved.sql());
            FxUiTestSupport.call(() -> {
                assertFalse(f.list().isDisabled()); assertFalse(f.button("open").isDisabled());
                f.text("group").setText("new group"); f.button("save").fire(); return null;
            }); f.idle();
            assertEquals(saved.id(),repo.expected.get().id()); assertEquals(2,repo.values.size());
            FxUiTestSupport.call(() -> {
                f.text("filter").setText("NEW GROUP"); assertEquals(1,f.list().getItems().size());
                f.list().getSelectionModel().selectFirst(); f.button("open").fire();
                assertEquals(saved.id(),f.view.dialog().getResult().id());
                assertEquals("new group",f.view.dialog().getResult().group()); assertFalse(f.view.dialog().isShowing()); return null;
            });
        }
    }
    @Test void discardSeedAndExplicitDeleteCancellationPreserveLibrary() throws Exception {
        var repo=new Repository(); var original=favorite("first","g"); repo.values.add(original);
        try(var f=new Fixture(repo,"select 99;")) {
            f.idle();
            FxUiTestSupport.call(() -> {
                f.view.confirmDiscard=() -> false; f.button("discard").fire(); assertEquals("select 99;",f.sql().getText());
                f.view.confirmDiscard=() -> true; f.button("discard").fire(); assertEquals("",f.sql().getText()); assertFalse(f.list().isDisabled());
                f.list().getSelectionModel().selectFirst(); f.view.confirmDelete=v -> false; f.button("delete").fire();
                assertEquals(0,repo.deletes.get()); f.view.confirmDelete=v -> { assertEquals(original,v); return true; }; f.button("delete").fire(); return null;
            }); f.idle(); assertEquals(1,repo.deletes.get()); assertTrue(repo.values.isEmpty());
        }
    }
    @Test void writeFailurePreservesEditingAndDoesNotExposeDiagnosticSql() throws Exception {
        var repo=new Repository(); repo.failSave=true;
        try(var f=new Fixture(repo,"select 2;")) {
            f.idle();
            FxUiTestSupport.call(() -> { f.text("name").setText("pending"); f.button("save").fire(); return null; }); f.idle();
            FxUiTestSupport.call(() -> {
                assertEquals("select 2;",f.sql().getText()); assertEquals("pending",f.text("name").getText());
                assertFalse(f.status().getText().contains("secret")); assertFalse(f.button("save").isDisabled());
                f.view.confirmDiscard=() -> false; ((Button)f.view.dialog().getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
                assertTrue(f.view.dialog().isShowing()); return null;
            }); assertTrue(repo.values.isEmpty());
        }
    }
    @Test void recoverCopiesValidBackupAndCannotOpenOrEditItDirectly() throws Exception {
        var repo=new Repository(); var damaged=favorite("backup","protected"); repo.recoverable.add(damaged);
        try(var f=new Fixture(repo,"")) {
            f.idle();
            FxUiTestSupport.call(() -> {
                f.list().getSelectionModel().selectFirst();
                assertTrue(f.sql().isDisabled()); assertTrue(f.button("open").isDisabled()); assertTrue(f.button("delete").isDisabled());
                f.button("recover").fire(); return null;
            }); f.idle();
            assertEquals(1,repo.values.size()); assertNotEquals(damaged.id(),repo.values.getFirst().id());
            assertEquals(List.of(damaged),repo.recoverable); assertEquals(damaged.sql(),repo.values.getFirst().sql());
        }
    }
    @Test void pendingLoadAndClosedOwnerRejectLateUiPublicationAndActions() throws Exception {
        var repo=new Repository(); repo.block=true;
        try(var f=new Fixture(repo,"")) {
            assertTrue(repo.started.await(5,TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                assertTrue(f.button("reload").isDisabled()); ((Button)f.view.dialog().getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
                assertTrue(f.view.dialog().isShowing()); f.view.close(); f.button("save").fire(); f.button("delete").fire(); return null;
            });
            repo.release.countDown(); assertTrue(repo.ended.await(5,TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { assertTrue(f.list().getItems().isEmpty()); assertTrue(f.sql().getText().isEmpty()); assertTrue(f.button("save").isDisabled()); return null; });
            assertEquals(0,repo.saves.get()+repo.deletes.get());
        } finally { repo.release.countDown(); }
    }
    @Test void oversizedCurrentScriptIsExplicitlyNotImportedRatherThanSilentlyTruncated() throws Exception {
        var repo=new Repository();
        try(var f=new Fixture(repo,"x".repeat(SqlFavoriteStore.MAX_SQL_BYTES+1))) {
            f.idle(); FxUiTestSupport.call(() -> {
                assertTrue(f.status().getText().contains("超过收藏上限，未带入")); assertEquals("",f.sql().getText());
                assertTrue(f.button("save").isDisabled()); return null;
            }); assertEquals(0,repo.saves.get());
        }
    }
    @Test void findShortcutFocusesOnlyLibraryFilterAndTabMovesToTheList() throws Exception {
        try(var f=new Fixture(new Repository(),"")) {
            f.idle(); FxUiTestSupport.call(() -> {
                f.text("name").requestFocus();
                f.text("name").fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "","",javafx.scene.input.KeyCode.F,false,true,false,false));
                assertSame(f.text("filter"),f.text("filter").getScene().getFocusOwner());
                f.text("filter").fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "","",javafx.scene.input.KeyCode.TAB,false,false,false,false));
                assertSame(f.list(),f.list().getScene().getFocusOwner());
                return null;
            });
        }
    }
    private static final class Repository implements SqlFavoritesDialog.Repository {
        final List<SqlFavorite> values=new CopyOnWriteArrayList<>(),recoverable=new CopyOnWriteArrayList<>();
        final AtomicInteger saves=new AtomicInteger(),deletes=new AtomicInteger();
        final AtomicReference<SqlFavorite> expected=new AtomicReference<>();
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),ended=new CountDownLatch(1);
        boolean failSave,block;
        public SqlFavoriteStore.Snapshot load() throws Exception {
            assertFalse(javafx.application.Platform.isFxApplicationThread()); started.countDown();
            if(block) try { while(true) { try { if(release.await(5,TimeUnit.SECONDS)) break; throw new AssertionError("release timed out"); } catch(InterruptedException ignored) {} } } finally { ended.countDown(); }
            return new SqlFavoriteStore.Snapshot(values,recoverable,recoverable.size(),true);
        }
        public void save(SqlFavorite value,SqlFavorite old) throws Exception {
            assertFalse(javafx.application.Platform.isFxApplicationThread()); saves.incrementAndGet();
            if(failSave) throw new java.io.IOException("secret SQL diagnostic");
            expected.set(old); if(old!=null) assertTrue(values.remove(old)); values.add(value);
        }
        public void delete(SqlFavorite value) { assertFalse(javafx.application.Platform.isFxApplicationThread()); deletes.incrementAndGet(); assertTrue(values.remove(value)); }
        public SqlFavorite recover(UUID id,long now) {
            var old=recoverable.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
            var copy=new SqlFavorite(UUID.randomUUID(),old.name(),old.group(),old.sql(),now); values.add(copy); return copy;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final FxTaskRunner runner=new FxTaskRunner(); final SqlFavoritesDialog view;
        Fixture(Repository repo,String seed) throws Exception { view=FxUiTestSupport.call(() -> {
            var d=new SqlFavoritesDialog(repo,null,runner,seed); d.dialog().show(); return d;
        }); }
        Button button(String id) { return (Button)view.dialog().getDialogPane().lookup("#favorites-"+id); }
        TextField text(String id) { return (TextField)view.dialog().getDialogPane().lookup("#favorites-"+id); }
        TextArea sql() { return (TextArea)view.dialog().getDialogPane().lookup("#favorites-sql"); }
        ListView<?> list() { return (ListView<?>)view.dialog().getDialogPane().lookup("#favorites-list"); }
        Label status() { return (Label)view.dialog().getDialogPane().lookup("#favorites-status"); }
        void idle() throws Exception {
            CountDownLatch ready=new CountDownLatch(1);
            FxUiTestSupport.call(() -> { if(!button("reload").isDisabled()) ready.countDown(); else button("reload").disabledProperty().addListener((o,b,a) -> { if(!a) ready.countDown(); }); return null; });
            assertTrue(ready.await(5,TimeUnit.SECONDS)); FxUiTestSupport.call(() -> null);
        }
        @Override public void close() throws Exception { FxUiTestSupport.call(() -> { view.confirmDiscard=() -> true; view.close(); view.dialog().setOnCloseRequest(null); view.dialog().close(); return null; }); runner.close(); }
    }
}
