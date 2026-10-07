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
    @Test void emptyLibraryInputsKeepTheirGuidanceVisibleInBothThemesAndFocusStates() throws Exception {
        try (var f = new Fixture(new Repository(), "")) {
            f.idle();
            FxUiTestSupport.call(() -> {
                var root = f.view.dialog().getDialogPane();
                for (String theme : List.of("dark", "light")) {
                    root.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                            ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                    for (TextInputControl input : List.of(f.text("filter"), f.text("name"), f.text("group"), f.sql())) {
                        for (boolean focused : List.of(false, true)) {
                            input.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), focused);
                            root.applyCss(); root.layout();
                            var prompt = input.lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance)
                                    .map(javafx.scene.text.Text.class::cast).filter(t -> input.getPromptText().equals(t.getText())).findFirst().orElseThrow();
                            assertTrue(prompt.isVisible(), input.getId());
                            assertEquals(javafx.scene.paint.Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"),
                                    prompt.getFill(), input.getId() + " / " + theme + " / focused=" + focused);
                        }
                    }
                }
                return null;
            });
        }
    }
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
    @Test void committedNewSaveWithFailedRefreshCannotCreateAnotherPersistedUuid(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var directory=temp.resolve("synthetic-favorites");var local=SqlFavoritesDialog.local(directory);
        var failReads=new AtomicBoolean();var writes=new AtomicInteger();
        var repository=new SqlFavoritesDialog.Repository() {
            public SqlFavoriteStore.Snapshot load() throws Exception {
                if(failReads.get()) throw new java.io.IOException("private synthetic read diagnostic");return local.load();
            }
            public void save(SqlFavorite value,SqlFavorite expected) throws Exception {local.save(value,expected);writes.incrementAndGet();failReads.set(true);}
            public void delete(SqlFavorite expected) throws Exception {local.delete(expected);}
            public SqlFavorite recover(UUID id,long now) throws Exception {return local.recover(id,now);}
        };
        try(var f=new Fixture(repository,"select 'synthetic committed';")) {
            f.idle();FxUiTestSupport.call(() -> {f.text("name").setText("committed new");f.button("save").fire();return null;});f.idle();
            var first=local.load().favorites().getFirst();var path=directory.resolve(first.id()+".favorite");var bytes=java.nio.file.Files.readAllBytes(path);
            assertEquals("select 'synthetic committed';",first.sql());assertEquals(1,writes.get());
            FxUiTestSupport.call(() -> {f.button("save").fire();return null;});f.idle();
            assertEquals(1,local.load().favorites().size(),"completed save followed by read failure must not create a second UUID");
            assertEquals(1,writes.get());assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(path));
            FxUiTestSupport.call(() -> {
                assertEquals(first.name(),f.text("name").getText());assertEquals(first.sql(),f.sql().getText());
                assertTrue(f.status().getText().contains("已保存"));assertTrue(f.status().getText().contains("重新读取"));
                assertFalse(f.status().getText().contains("private"));assertTrue(f.button("save").isDisabled());assertTrue(f.button("open").isDisabled());return null;
            });
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"new","edit","delete","recover"})
    void completedWritesStayFrozenAcrossFailedRereadAndResumeFromFreshSnapshot(String operation) throws Exception {
        var repo=new Repository();var original=favorite("original","group");
        if(operation.equals("recover")) repo.recoverable.add(original);else if(!operation.equals("new")) repo.values.add(original);
        try(var f=new Fixture(repo,"")) {
            f.idle();FxUiTestSupport.call(() -> {
                f.view.confirmDiscard=() -> {throw new AssertionError("completed operation is not dirty");};
                if(!operation.equals("new")) f.list().getSelectionModel().selectFirst();
                repo.failLoad=true;
                switch(operation) {
                    case "new" -> {f.text("name").setText("new saved");f.sql().setText("select 'new committed';");f.button("save").fire();}
                    case "edit" -> {f.sql().setText("select 'edited committed';");f.button("save").fire();}
                    case "delete" -> {f.view.confirmDelete=value -> {assertEquals(original,value);return true;};f.button("delete").fire();}
                    default -> f.button("recover").fire();
                } return null;
            });f.idle();
            var completed=operation.equals("delete") ? original : repo.values.getFirst();
            var notice=FxUiTestSupport.call(() -> {
                assertPending(f,completed,operation);var message=f.status().getText();
                for(String id:List.of("save","delete","recover","open","new","discard")) {
                    var button=f.button(id);assertTrue(button.isDisabled(),id);
                    // Deliberate handler invocation verifies the state guard separately from disabled controls.
                    button.getOnAction().handle(new javafx.event.ActionEvent(button,button));
                }
                f.text("filter").setText("does not match committed item");
                assertEquals(completed.sql(),f.sql().getText());assertTrue(f.view.dialog().isShowing());assertNull(f.view.dialog().getResult());
                f.button("reload").fire();return message;
            });f.idle();
            FxUiTestSupport.call(() -> {assertPending(f,completed,operation);assertEquals(notice,f.status().getText());return null;});
            assertEquals(operation.equals("new") || operation.equals("edit") ? 1 : 0,repo.saves.get());
            assertEquals(operation.equals("delete") ? 1 : 0,repo.deletes.get());assertEquals(operation.equals("recover") ? 1 : 0,repo.recovers.get());
            assertEquals(3,repo.reads.get(),"only explicit reread repeats the read, never the completed write");
            repo.failLoad=false;
            var fresh=operation.equals("delete") ? null : new SqlFavorite(completed.id(),"fresh external name","fresh",completed.sql()+" -- fresh snapshot",completed.modifiedAt()+1);
            if(fresh!=null) {repo.values.clear();repo.values.add(fresh);}
            FxUiTestSupport.call(() -> {f.button("reload").fire();return null;});f.idle();
            FxUiTestSupport.call(() -> {
                assertFalse(f.list().isDisabled());assertFalse(f.button("new").isDisabled());
                if(fresh==null) {assertTrue(f.list().getItems().isEmpty());assertEquals("",f.sql().getText());assertTrue(f.button("open").isDisabled());}
                else {assertEquals(fresh.name(),f.text("name").getText());assertEquals(fresh.sql(),f.sql().getText());f.button("open").fire();assertEquals(fresh,f.view.dialog().getResult());}
                return null;
            });assertEquals(4,repo.reads.get());
            if(operation.equals("recover")) assertEquals(List.of(original),repo.recoverable);
        }
    }
    @Test void successfulRereadDoesNotRecreateACompletedItemMissingFromFreshSnapshot() throws Exception {
        var repo=new Repository();
        try(var f=new Fixture(repo,"")) {
            f.idle();FxUiTestSupport.call(() -> {f.text("name").setText("saved");f.sql().setText("select 'saved';");repo.failLoad=true;f.button("save").fire();return null;});f.idle();
            repo.values.clear();repo.failLoad=false;FxUiTestSupport.call(() -> {f.button("reload").fire();return null;});f.idle();
            FxUiTestSupport.call(() -> {assertTrue(f.list().getItems().isEmpty());assertEquals("",f.sql().getText());assertTrue(f.button("open").isDisabled());return null;});
            assertEquals(1,repo.saves.get());assertTrue(repo.values.isEmpty());
        }
    }
    @Test void rejectedRereadPreservesCompletedNoticeAndDoesNotWriteAgain() throws Exception {
        var repo=new Repository();
        try(var f=new Fixture(repo,"")) {
            f.idle();FxUiTestSupport.call(() -> {f.text("name").setText("saved");f.sql().setText("select 'saved';");repo.failLoad=true;f.button("save").fire();return null;});f.idle();
            var notice=FxUiTestSupport.call(() -> f.status().getText());f.runner.close();
            FxUiTestSupport.call(() -> {f.button("reload").fire();assertEquals(notice,f.status().getText());assertPending(f,repo.values.getFirst(),"new");return null;});
            assertEquals(2,repo.reads.get());assertEquals(1,repo.saves.get());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"new","edit","delete","recover"})
    void completedOperationCanCloseWithoutDiscardPrompt(String operation) throws Exception {
        var repo=new Repository();var original=favorite("original","g");
        if(operation.equals("recover"))repo.recoverable.add(original);else if(!operation.equals("new"))repo.values.add(original);
        try(var f=new Fixture(repo,"")) {
            f.idle();FxUiTestSupport.call(() -> {
                f.view.confirmDiscard=() -> {throw new AssertionError("completed outcome must not prompt to discard");};repo.failLoad=true;
                if(!operation.equals("new"))f.list().getSelectionModel().selectFirst();
                switch(operation) {
                    case "new" -> {f.text("name").setText("saved");f.sql().setText("select 1;");f.button("save").fire();}
                    case "edit" -> {f.sql().setText("select 2;");f.button("save").fire();}
                    case "delete" -> {f.view.confirmDelete=v -> true;f.button("delete").fire();}
                    default -> f.button("recover").fire();
                }return null;
            });f.idle();
            FxUiTestSupport.call(() -> {((Button)f.view.dialog().getDialogPane().lookupButton(ButtonType.CANCEL)).fire();assertFalse(f.view.dialog().isShowing());assertNull(f.view.dialog().getResult());assertEquals("",f.sql().getText());return null;});
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"new","edit","delete","recover"})
    void genuineWriteFailurePreservesDraftAndAllowsOnlyExplicitRetry(String operation) throws Exception {
        var repo=new Repository();var original=favorite("original","g");
        if(operation.equals("recover"))repo.recoverable.add(original);else if(!operation.equals("new"))repo.values.add(original);
        try(var f=new Fixture(repo,"")) {
            f.idle();var discards=new AtomicInteger();
            FxUiTestSupport.call(() -> {
                f.view.confirmDiscard=() -> {discards.incrementAndGet();return false;};f.view.confirmDelete=v -> true;
                if(!operation.equals("new"))f.list().getSelectionModel().selectFirst();
                if(operation.equals("new")) {f.text("name").setText("pending new");f.sql().setText("select 'pending';");}
                if(operation.equals("edit"))f.sql().setText("select 'pending';");
                repo.failSave=true;repo.failDelete=true;repo.failRecover=true;
                f.button(operation.equals("delete") ? "delete" : operation.equals("recover") ? "recover" : "save").fire();return null;
            });f.idle();
            FxUiTestSupport.call(() -> {
                assertEquals(operation.equals("new") || operation.equals("edit") ? "select 'pending';" : original.sql(),f.sql().getText());
                assertFalse(f.status().getText().contains("secret"));assertFalse(f.status().getText().contains("已保存"));
                assertFalse(f.status().getText().contains("已删除"));assertFalse(f.status().getText().contains("已从副本恢复"));
                var action=f.button(operation.equals("delete") ? "delete" : operation.equals("recover") ? "recover" : "save");assertFalse(action.isDisabled());
                if(operation.equals("new") || operation.equals("edit")) {((Button)f.view.dialog().getDialogPane().lookupButton(ButtonType.CANCEL)).fire();assertTrue(f.view.dialog().isShowing());assertEquals(1,discards.get());}
                assertEquals(1,repo.reads.get(),"a write failure cannot automatically reload or retry");
                repo.failSave=false;repo.failDelete=false;repo.failRecover=false;action.fire();return null;
            });f.idle();
            assertEquals(operation.equals("new") || operation.equals("edit") ? 2 : 0,repo.saves.get());
            assertEquals(operation.equals("delete") ? 2 : 0,repo.deletes.get());assertEquals(operation.equals("recover") ? 2 : 0,repo.recovers.get());
            assertEquals(2,repo.reads.get());
            if(operation.equals("edit"))assertEquals(original,repo.expected.get());
            if(operation.equals("delete"))assertTrue(repo.values.isEmpty());else assertEquals(1,repo.values.size());
        }
    }
    @Test void thrownWriteDoesNotClaimNoPartialMutationAndExplicitReadCanReconcileIt() throws Exception {
        var repo=new Repository();
        var partial=new SqlFavoritesDialog.Repository() {
            public SqlFavoriteStore.Snapshot load() throws Exception {return repo.load();}
            public void save(SqlFavorite value,SqlFavorite expected) throws Exception {repo.save(value,expected);throw new java.io.IOException("secret post-write resource close failure");}
            public void delete(SqlFavorite expected) throws Exception {repo.delete(expected);}
            public SqlFavorite recover(UUID id,long now) throws Exception {return repo.recover(id,now);}
        };
        try(var f=new Fixture(partial,"")) {
            f.idle();FxUiTestSupport.call(() -> {f.text("name").setText("uncertain");f.sql().setText("select 'synthetic uncertain';");f.button("save").fire();return null;});f.idle();
            assertEquals(1,repo.values.size());
            FxUiTestSupport.call(() -> {
                assertEquals("select 'synthetic uncertain';",f.sql().getText());assertFalse(f.button("save").isDisabled());
                assertFalse(f.status().getText().contains("尚未保存"));assertFalse(f.status().getText().contains("已保存"));
                f.view.confirmDiscard=() -> false;f.button("reload").fire();assertEquals(1,repo.reads.get());
                f.view.confirmDiscard=() -> true;f.button("reload").fire();return null;
            });f.idle();
            FxUiTestSupport.call(() -> {f.list().getSelectionModel().selectFirst();f.button("open").fire();assertEquals(repo.values.getFirst(),f.view.dialog().getResult());return null;});
            assertEquals(1,repo.saves.get());assertEquals(2,repo.reads.get());
        }
    }
    @Test void realRecoveredCopyWithFailedRefreshPreservesDamagedOriginalAndBackup(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var directory=temp.resolve("synthetic-protected-favorites");var original=favorite("protected source","g");
        try(var store=SqlFavoriteStore.open(directory)) {
            store.save(original,null);store.save(new SqlFavorite(original.id(),"newer","g","select 'newer';",2),original);
        }
        var primary=directory.resolve(original.id()+".favorite");var backup=directory.resolve(original.id()+".favorite-backup");
        var corrupt=java.nio.file.Files.readAllBytes(primary);corrupt[corrupt.length-1]^=1;java.nio.file.Files.write(primary,corrupt);
        var backupBytes=java.nio.file.Files.readAllBytes(backup);var local=SqlFavoritesDialog.local(directory);var readsFail=new AtomicBoolean();var recoveries=new AtomicInteger();
        var repository=new SqlFavoritesDialog.Repository() {
            public SqlFavoriteStore.Snapshot load() throws Exception {if(readsFail.get())throw new java.io.IOException("secret synthetic recovery refresh failure");return local.load();}
            public void save(SqlFavorite value,SqlFavorite expected) throws Exception {local.save(value,expected);}
            public void delete(SqlFavorite expected) throws Exception {local.delete(expected);}
            public SqlFavorite recover(UUID id,long now) throws Exception {var restored=local.recover(id,now);recoveries.incrementAndGet();readsFail.set(true);return restored;}
        };
        try(var f=new Fixture(repository,"")) {
            f.idle();FxUiTestSupport.call(() -> {f.list().getSelectionModel().selectFirst();f.button("recover").fire();return null;});f.idle();
            var restored=local.load().favorites().getFirst();assertNotEquals(original.id(),restored.id());assertEquals(original.sql(),restored.sql());
            var restoredPath=directory.resolve(restored.id()+".favorite");var restoredBytes=java.nio.file.Files.readAllBytes(restoredPath);
            FxUiTestSupport.call(() -> {assertPending(f,restored,"recover");f.button("recover").getOnAction().handle(new javafx.event.ActionEvent());f.button("reload").fire();return null;});f.idle();
            assertEquals(1,recoveries.get());assertEquals(List.of(restored),local.load().favorites());
            assertArrayEquals(corrupt,java.nio.file.Files.readAllBytes(primary));assertArrayEquals(backupBytes,java.nio.file.Files.readAllBytes(backup));
            assertArrayEquals(restoredBytes,java.nio.file.Files.readAllBytes(restoredPath));assertEquals(List.of(original),local.load().recoverable());
            readsFail.set(false);FxUiTestSupport.call(() -> {f.button("reload").fire();return null;});f.idle();
            FxUiTestSupport.call(() -> {f.button("open").fire();assertEquals(restored,f.view.dialog().getResult());return null;});
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void ownerCloseSuppressesLatePostWriteRefreshWithoutRepeatingTheCommittedSave(boolean readFails) throws Exception {
        var values=new CopyOnWriteArrayList<SqlFavorite>();var writes=new AtomicInteger();var reads=new AtomicInteger();
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var returned=new CountDownLatch(1);var interrupted=new AtomicBoolean();var readThread=new AtomicReference<Thread>();
        var repository=new SqlFavoritesDialog.Repository() {
            public SqlFavoriteStore.Snapshot load() throws Exception {
                reads.incrementAndGet();if(writes.get()>0) {
                    readThread.set(Thread.currentThread());started.countDown();try {
                        while(true) {try {if(release.await(5,TimeUnit.SECONDS))break;throw new AssertionError("synthetic release timeout");}catch(InterruptedException canceled){interrupted.set(true);}}
                        if(readFails)throw new java.io.IOException("secret late refresh diagnostic");
                    } finally {returned.countDown();}
                }return new SqlFavoriteStore.Snapshot(values,List.of(),0,true);
            }
            public void save(SqlFavorite value,SqlFavorite expected) {writes.incrementAndGet();values.add(value);}
            public void delete(SqlFavorite expected) {throw new AssertionError("unexpected delete");}
            public SqlFavorite recover(UUID id,long now) {throw new AssertionError("unexpected recover");}
        };
        var owner=FxUiTestSupport.call(() -> {var stage=new javafx.stage.Stage();stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.VBox(),300,200));stage.show();return stage;});
        try(var f=new Fixture(repository,"",owner,new FxTaskRunner(),true)) {
            f.idle();FxUiTestSupport.call(() -> {f.text("name").setText("committed before close");f.sql().setText("select 'owner synthetic';");f.button("save").fire();return null;});
            assertTrue(started.await(5,TimeUnit.SECONDS));assertEquals(1,writes.get());assertEquals(1,values.size());
            var before=FxUiTestSupport.call(() -> {
                ((Button)f.view.dialog().getDialogPane().lookupButton(ButtonType.CANCEL)).fire();assertTrue(f.view.dialog().isShowing(),"ordinary busy close is rejected");
                owner.close();assertFalse(f.view.dialog().isShowing());return f.status().getText();
            });
            assertTrue(f.modalExited.await(5,TimeUnit.SECONDS),"production ownership wrapper must exit after owner hides");
            awaitLayoutPulses();FxUiTestSupport.call(() -> {assertEquals("",f.sql().getText());return null;});
            release.countDown();assertTrue(returned.await(5,TimeUnit.SECONDS));
            readThread.get().join(5000);assertFalse(readThread.get().isAlive(),"owned post-write task must complete before testing late UI publication");
            FxUiTestSupport.call(() -> {
                assertEquals(before,f.status().getText());assertTrue(f.list().getItems().isEmpty());assertEquals("",f.sql().getText());
                for(String id:List.of("save","delete","recover","reload","open"))f.button(id).getOnAction().handle(new javafx.event.ActionEvent());
                assertNull(f.view.dialog().getResult());return null;
            });assertTrue(interrupted.get());assertEquals(1,writes.get());assertEquals(2,reads.get());assertEquals(1,values.size());
        } finally {release.countDown();FxUiTestSupport.call(() -> {owner.close();return null;});}
    }
    @Test void interruptedPostWriteReadRetainsCompletedOutcomeAndWorkerInterruptSignal() throws Exception {
        var signals=new LinkedBlockingQueue<Boolean>();
        var executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),
                Thread.ofPlatform().daemon(true).name("synthetic-favorites-interrupt").factory()) {
            @Override protected void afterExecute(Runnable task,Throwable failure) {signals.add(Thread.currentThread().isInterrupted());}
        };
        var constructor=FxTaskRunner.class.getDeclaredConstructor(ExecutorService.class,java.time.Duration.class);constructor.setAccessible(true);
        var runner=constructor.newInstance(executor,java.time.Duration.ofSeconds(1));var repo=new Repository();
        var repository=new SqlFavoritesDialog.Repository() {
            public SqlFavoriteStore.Snapshot load() throws Exception {if(repo.saves.get()>0)throw new InterruptedException("synthetic post-write interruption");return repo.load();}
            public void save(SqlFavorite value,SqlFavorite expected) throws Exception {repo.save(value,expected);}
            public void delete(SqlFavorite expected) throws Exception {repo.delete(expected);}
            public SqlFavorite recover(UUID id,long now) throws Exception {return repo.recover(id,now);}
        };
        try(var f=new Fixture(repository,"",null,runner)) {
            f.idle();assertEquals(Boolean.FALSE,signals.poll(5,TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {f.text("name").setText("saved before interrupt");f.sql().setText("select 'synthetic interrupted';");f.button("save").fire();return null;});f.idle();
            assertEquals(Boolean.TRUE,signals.poll(5,TimeUnit.SECONDS),"post-write InterruptedException must restore the worker signal");
            assertEquals(1,repo.saves.get());FxUiTestSupport.call(() -> {assertPending(f,repo.values.getFirst(),"new");return null;});
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"light,480","dark,480","light,640","dark,640"})
    void compactShownStagesKeepFocusedControlsAndBottomGuidanceReachable(String theme,int width) throws Exception {
        var repo=new Repository();repo.values.add(favorite("synthetic row","group"));
        try(var f=new Fixture(repo,"")) {
            f.idle();compact(f,theme,width);awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.view.dialog().getDialogPane();pane.applyCss();pane.layout();
                var stage=pane.getScene().getWindow();
                assertEquals(width,stage.getWidth(),1);assertEquals(480,stage.getHeight(),1);
                System.out.println("COMPACT geometry theme="+theme+" stage="+stage.getWidth()+"x"+stage.getHeight()
                        +" scene="+pane.getScene().getWidth()+"x"+pane.getScene().getHeight());
                var checks=new ArrayList<org.junit.jupiter.api.function.Executable>();
                for(javafx.scene.Node node:List.of(f.text("filter"),f.list(),f.text("name"),f.text("group"),f.sql(),
                        f.button("new"),f.button("reload")))checks.add(() -> {node.requestFocus();pane.layout();assertReachable(f,node);});
                checks.add(() -> {var scroll=f.scroll();if(scroll!=null){scroll.setVvalue(1);pane.layout();}});
                checks.add(() -> assertReachable(f,f.status()));
                var privacy=pane.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(label -> label.getText().startsWith("收藏在本机以明文保存")).findFirst().orElseThrow();
                checks.add(() -> assertReachable(f,privacy));
                checks.add(() -> assertInScene(pane,pane.lookupButton(ButtonType.CANCEL)));
                assertAll(checks);assertEquals(1,repo.reads.get());assertNull(f.view.dialog().getResult());return null;
            });
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"light,480","dark,480","light,640","dark,640"})
    void compactFocusFindAndEditorScrollingPreserveManualPositionAndSelection(String theme,int width) throws Exception {
        var repo=new Repository();repo.values.add(favorite("synthetic","group"));var scrollState=new double[2];var preScrollText=new AtomicReference<String>();
        try(var f=new Fixture(repo,"")) {
            f.idle();compact(f,theme,width);awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.view.dialog().getDialogPane();f.text("filter").setText("synthetic");f.text("filter").requestFocus();
                pane.layout();assertReachable(f,f.text("filter"));f.scroll().setVvalue(1);pane.layout();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertEquals(1,f.scroll().getVvalue(),"manual scroll must not pull the still-focused filter back");
                assertSame(f.text("filter"),f.text("filter").getScene().getFocusOwner());
                find(f.text("filter"));f.view.dialog().getDialogPane().layout();assertReachable(f,f.text("filter"));
                assertEquals("synthetic",f.text("filter").getSelectedText());
                f.text("filter").fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",
                        javafx.scene.input.KeyCode.TAB,false,false,false,false));
                assertSame(f.list(),f.list().getScene().getFocusOwner());f.view.dialog().getDialogPane().layout();assertReachable(f,f.list());
                f.list().getSelectionModel().selectFirst();f.sql().requestFocus();
                f.sql().setText("select '"+"x".repeat(12000)+"';\n"+"select 'synthetic line';\n".repeat(180));
                f.sql().selectRange(12020,12044);f.sql().setScrollTop(160);
                f.view.dialog().getDialogPane().layout();assertReachable(f,f.sql());assertTrue(f.text("filter").isDisabled());return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.view.dialog().getDialogPane();var selection=f.sql().getSelection();
                String text=f.sql().getText();double internal=f.sql().getScrollTop(),external=f.scroll().getVvalue();
                assertTrue(internal>0,"long SQL really scrolls internally");find(f.sql());
                assertSame(f.sql(),pane.getScene().getFocusOwner());assertEquals(selection,f.sql().getSelection());
                assertEquals(text,f.sql().getText());assertEquals(internal,f.sql().getScrollTop(),1);assertEquals(external,f.scroll().getVvalue(),0.001);
                scrollState[0]=internal;scrollState[1]=external;preScrollText.set(text);
                f.sql().lookup(".content").fireEvent(new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
                        0,0,0,0,false,false,false,false,false,false,0,-40,0,-40,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE,0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.LINES,-3,0,null));
                assertEquals(selection,f.sql().getSelection());return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.view.dialog().getDialogPane();double external=scrollState[1];
                assertTrue(f.sql().getScrollTop()>scrollState[0],"synthetic scroll is handled inside TextArea");
                assertEquals(external,f.scroll().getVvalue(),0.001,"editor scroll must not also scroll the outer content");
                assertSame(f.sql(),pane.getScene().getFocusOwner());assertEquals(preScrollText.get(),f.sql().getText());
                assertEquals(new IndexRange(12020,12044),f.sql().getSelection());f.sql().replaceSelection("synthetic edit");
                assertTrue(f.sql().getText().contains("synthetic edit"));
                assertEquals(external,f.scroll().getVvalue(),0.001);
                ((javafx.stage.Stage)pane.getScene().getWindow()).setWidth(width==480 ? 640 : 480);return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.view.dialog().getDialogPane();pane.layout();assertSame(f.sql(),pane.getScene().getFocusOwner());assertReachable(f,f.sql());
                assertTrue(f.sql().getWidth()<=f.scroll().getViewportBounds().getWidth());
                assertEquals(12034,f.sql().getCaretPosition());assertEquals(new IndexRange(12034,12034),f.sql().getSelection());
                f.button("discard").requestFocus();pane.layout();assertReachable(f,f.button("discard"));
                f.scroll().setVvalue(0);pane.layout();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertEquals(0,f.scroll().getVvalue(),"manual scroll without focus/layout change is stable");
                f.view.dialog().getDialogPane().lookupButton(ButtonType.CANCEL).requestFocus();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {assertEquals(0,f.scroll().getVvalue(),"footer focus must not move scrolling content");
                assertEquals(0,repo.saves.get());assertEquals(1,repo.reads.get());return null;});
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"light,480,save","dark,480,save","light,640,save","dark,640,save",
            "light,480,delete","dark,480,delete","light,640,delete","dark,640,delete",
            "light,480,recover","dark,480,recover","light,640,recover","dark,640,recover"})
    void compactFailuresCompletedWritesAndRereadRemainReachable(String theme,int width,String operation) throws Exception {
        var repo=new Repository();var original=favorite("synthetic original","group");
        repo.values.add(original);if(operation.equals("recover"))repo.recoverable.add(favorite("synthetic recovery","backup"));
        try(var f=new Fixture(repo,"")) {
            f.idle();compact(f,theme,width);awaitLayoutPulses();
            var confirmations=new AtomicInteger();
            FxUiTestSupport.call(() -> {
                var pane=f.view.dialog().getDialogPane();f.list().requestFocus();pane.layout();assertReachable(f,f.list());
                f.list().getSelectionModel().select(operation.equals("recover") ? 1 : 0);
                var cell=f.list().lookupAll(".list-cell").stream().filter(ListCell.class::isInstance).map(ListCell.class::cast)
                        .filter(c -> !c.isEmpty() && c.isSelected()).findFirst().orElseThrow();
                var row=cell.localToScene(cell.getBoundsInLocal());var list=f.list().localToScene(f.list().getBoundsInLocal());
                assertTrue(row.getMinY()>=list.getMinY() && row.getMaxY()<=list.getMaxY(),"selected row is actually inside list");
                assertEquals(1,repo.reads.get());
                if(operation.equals("save")) {
                    fireReachable(f,"new");assertSame(f.text("name"),pane.getScene().getFocusOwner());pane.layout();assertReachable(f,f.text("name"));
                    f.text("name").setText("synthetic draft");f.text("group").setText("synthetic group");f.sql().setText("select 'synthetic draft';");
                    f.view.confirmDiscard=() -> {confirmations.incrementAndGet();return false;};fireReachable(f,"discard");
                    assertEquals("synthetic draft",f.text("name").getText());assertEquals(1,confirmations.get());
                    f.view.confirmDiscard=() -> {confirmations.incrementAndGet();return true;};fireReachable(f,"discard");
                    assertEquals("",f.sql().getText());assertEquals(2,confirmations.get());
                    f.text("name").setText("synthetic saved");f.sql().setText("select 'synthetic saved';");repo.failSave=true;
                } else if(operation.equals("delete")) {
                    f.view.confirmDelete=v -> {confirmations.incrementAndGet();return false;};fireReachable(f,"delete");
                    assertEquals(0,repo.deletes.get());assertEquals(1,confirmations.get());
                    f.view.confirmDelete=v -> {confirmations.incrementAndGet();return true;};repo.failDelete=true;
                } else {assertTrue(f.sql().isDisabled());assertTrue(f.button("open").isDisabled());repo.failRecover=true;}
                fireReachable(f,operation.equals("recover") ? "recover" : operation);return null;
            });
            f.idle();awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertTrue(f.status().getText().contains("读取或写入未完成"));assertFalse(f.status().getText().contains("secret"));
                bottomReachable(f);assertInScene(f.view.dialog().getDialogPane(),f.view.dialog().getDialogPane().lookupButton(ButtonType.CANCEL));
                assertEquals(1,operation.equals("save") ? repo.saves.get() : operation.equals("delete") ? repo.deletes.get() : repo.recovers.get());
                repo.failSave=false;repo.failDelete=false;repo.failRecover=false;repo.failLoad=true;
                fireReachable(f,operation.equals("recover") ? "recover" : operation);return null;
            });
            f.idle();awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertPending(f,operation.equals("delete") ? original : repo.values.getLast(),operation);
                bottomReachable(f);if(width==480)assertTrue(f.status().getHeight()>20,"completed notice is wrapped at 480 width");
                fireReachable(f,"reload");return null;
            });
            f.idle();awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertPending(f,operation.equals("delete") ? original : repo.values.getLast(),operation);bottomReachable(f);
                assertEquals(2,operation.equals("save") ? repo.saves.get() : operation.equals("delete") ? repo.deletes.get() : repo.recovers.get());
                repo.failLoad=false;fireReachable(f,"reload");return null;
            });
            f.idle();awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertTrue(f.status().getText().startsWith("已读取"));bottomReachable(f);
                assertFalse(f.list().isDisabled());f.list().requestFocus();f.view.dialog().getDialogPane().layout();assertReachable(f,f.list());
                if(operation.equals("delete")) {
                    assertTrue(f.list().getItems().isEmpty());assertEquals(3,confirmations.get());
                    var cancel=(Button)f.view.dialog().getDialogPane().lookupButton(ButtonType.CANCEL);assertInScene(f.view.dialog().getDialogPane(),cancel);cancel.fire();
                    assertNull(f.view.dialog().getResult());
                } else {var saved=repo.values.getLast();fireReachable(f,"open");assertEquals(saved,f.view.dialog().getResult());}
                assertFalse(f.view.dialog().isShowing());assertEquals(4,repo.reads.get());return null;
            });
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={480,640})
    void compactFirstReadAndProtectedStatusAllowRereadWithoutMutation(int width) throws Exception {
        var repo=new Repository();repo.block=true;var value=favorite("synthetic protected","backup");
        repo.values.add(value);repo.recoverable.add(value);
        try(var f=new Fixture(repo,"x".repeat(SqlFavoriteStore.MAX_SQL_BYTES+1))) {
            assertTrue(repo.started.await(5,TimeUnit.SECONDS));compact(f,"dark",width);awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertTrue(f.sql().isDisabled());assertTrue(f.button("reload").isDisabled());bottomReachable(f);
                assertTrue(f.status().getText().contains("正在处理"));
                var pane=f.view.dialog().getDialogPane();assertInScene(pane,pane.lookupButton(ButtonType.CANCEL));
                ((Button)pane.lookupButton(ButtonType.CANCEL)).fire();assertTrue(f.view.dialog().isShowing(),"busy close is rejected");return null;
            });
            repo.block=false;repo.release.countDown();f.idle();awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertTrue(f.status().getText().contains("超过收藏上限"));assertTrue(f.status().getText().contains("受保护"));bottomReachable(f);
                f.list().requestFocus();f.view.dialog().getDialogPane().layout();assertReachable(f,f.list());f.list().getSelectionModel().selectFirst();
                assertTrue(f.button("save").isDisabled());assertTrue(f.button("delete").isDisabled());
                fireReachable(f,"reload");return null;
            });f.idle();awaitLayoutPulses();
            FxUiTestSupport.call(() -> {bottomReachable(f);assertEquals(2,repo.reads.get());assertEquals(0,repo.saves.get());
                assertEquals(0,repo.deletes.get());assertEquals(0,repo.recovers.get());return null;});
        } finally {repo.release.countDown();}
    }
    @Test void wideResizeKeepsEditorFocusAndClosedQueuedLayoutCannotRestoreContent() throws Exception {
        var repo=new Repository();repo.values.add(favorite("synthetic","group"));
        try(var f=new Fixture(repo,"")) {
            f.idle();compact(f,"light",480);awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                f.list().getSelectionModel().selectFirst();f.sql().requestFocus();var pane=f.view.dialog().getDialogPane();pane.layout();assertReachable(f,f.sql());
                var stage=(javafx.stage.Stage)pane.getScene().getWindow();stage.setWidth(960);stage.setHeight(760);return null;
            });awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.view.dialog().getDialogPane();pane.layout();assertEquals(960,pane.getScene().getWindow().getWidth(),1);
                assertEquals(760,pane.getScene().getWindow().getHeight(),1);assertSame(f.sql(),pane.getScene().getFocusOwner());
                for(javafx.scene.Node node:List.of(f.text("filter"),f.list(),f.text("name"),f.text("group"),f.sql(),f.status(),
                        pane.lookup("#favorites-privacy")))assertReachable(f,node);
                assertTrue(((javafx.scene.layout.Region)f.scroll().getContent()).getHeight()<=f.scroll().getViewportBounds().getHeight()+1);
                assertInScene(pane,pane.lookupButton(ButtonType.CANCEL));
                // Force a real content-height listener to enqueue reveal while a body control owns focus.
                double before=((javafx.scene.layout.Region)f.scroll().getContent()).getHeight();
                f.status().setText("合成长状态，用于关闭后布局回调验证。".repeat(40));pane.applyCss();pane.layout();f.scroll().layout();
                assertTrue(((javafx.scene.layout.Region)f.scroll().getContent()).getHeight()>before,"height change actually enqueues reveal");
                f.view.close();f.scroll().setVvalue(0.5);f.view.dialog().close();return null;
            });awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                assertFalse(f.view.dialog().isShowing());assertEquals(0.5,f.scroll().getVvalue(),0.001,"queued reveal must stop after close");
                assertEquals("",f.sql().getText());assertEquals("",f.text("name").getText());assertTrue(f.list().getItems().isEmpty());
                for(String id:List.of("save","delete","recover","new","reload","open"))f.button(id).getOnAction().handle(new javafx.event.ActionEvent());
                assertEquals(1,repo.reads.get());assertEquals(0,repo.saves.get());assertEquals(0,repo.deletes.get());assertEquals(0,repo.recovers.get());return null;
            });
        }
    }
    private static void find(javafx.scene.Node source) {
        source.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.F,false,true,false,false));
    }
    private static void fireReachable(Fixture f,String id) {
        var button=f.button(id);assertFalse(button.isDisabled(),id);button.requestFocus();f.view.dialog().getDialogPane().layout();
        assertReachable(f,button);button.fire();
    }
    private static void bottomReachable(Fixture f) {
        f.scroll().setVvalue(1);f.view.dialog().getDialogPane().layout();assertReachable(f,f.status());
        assertReachable(f,f.view.dialog().getDialogPane().lookup("#favorites-privacy"));
    }
    private static void compact(Fixture f,String theme,int width) throws Exception {
        FxUiTestSupport.call(() -> {
            var pane=f.view.dialog().getDialogPane();pane.getScene().getStylesheets().setAll(
                    ThemeManager.class.getResource("theme-base.css").toExternalForm(),ThemeManager.class.getResource("theme-"+theme+".css").toExternalForm());
            var stage=(javafx.stage.Stage)pane.getScene().getWindow();stage.setWidth(width);stage.setHeight(480);return null;
        });
    }
    private static void assertReachable(Fixture f,javafx.scene.Node node) {
        var pane=f.view.dialog().getDialogPane();var scroll=f.scroll();
        var viewport=scroll==null ? pane : scroll.lookup(".viewport");
        var visible=scroll==null ? new javafx.geometry.BoundingBox(0,0,pane.getScene().getWidth(),pane.getScene().getHeight())
                : viewport.localToScene(viewport.getBoundsInLocal());
        var bounds=node.localToScene(node.getBoundsInLocal());
        var screen=node.localToScreen(node.getBoundsInLocal());
        System.out.println("COMPACT node="+node.getId()+" bounds="+bounds+" viewport="+visible+" screen="+screen);
        assertTrue(bounds.getMinX()>=visible.getMinX()-1 && bounds.getMaxX()<=visible.getMaxX()+1
                && bounds.getMinY()>=visible.getMinY()-1 && bounds.getMaxY()<=visible.getMaxY()+1,
                node.getId()+" must be reachable: "+bounds+" visible "+visible);
        assertInScene(pane,viewport);
    }
    private static void assertInScene(DialogPane pane,javafx.scene.Node node) {
        var bounds=node.localToScene(node.getBoundsInLocal());
        assertTrue(bounds.getMinX()>=-1 && bounds.getMaxX()<=pane.getScene().getWidth()+1
                && bounds.getMinY()>=-1 && bounds.getMaxY()<=pane.getScene().getHeight()+1,"outside actual scene: "+bounds);
    }
    private static void awaitLayoutPulses() throws Exception {
        var ready=new CountDownLatch(1);
        FxUiTestSupport.call(() -> {new javafx.animation.AnimationTimer() {
            int frames;
            @Override public void handle(long now) {if(++frames>=3){stop();ready.countDown();}}
        }.start();return null;});
        assertTrue(ready.await(5,TimeUnit.SECONDS));
    }
    private static void assertPending(Fixture f,SqlFavorite value,String operation) {
        assertEquals(value.name(),f.text("name").getText());assertEquals(value.group(),f.text("group").getText());assertEquals(value.sql(),f.sql().getText());
        assertTrue(f.sql().isDisabled());assertTrue(f.list().isDisabled());assertTrue(f.list().getItems().isEmpty());
        for(String id:List.of("save","delete","recover","open","new","discard"))assertTrue(f.button(id).isDisabled(),id);
        assertFalse(f.button("reload").isDisabled());assertFalse(f.status().getText().contains("secret"));
        assertTrue(f.status().getText().contains(operation.equals("delete") ? "已删除" : operation.equals("recover") ? "已从副本恢复" : "已保存"));
        assertTrue(f.status().getText().contains("重新读取"));
    }
    private static final class Repository implements SqlFavoritesDialog.Repository {
        final List<SqlFavorite> values=new CopyOnWriteArrayList<>(),recoverable=new CopyOnWriteArrayList<>();
        final AtomicInteger saves=new AtomicInteger(),deletes=new AtomicInteger(),recovers=new AtomicInteger(),reads=new AtomicInteger();
        final AtomicReference<SqlFavorite> expected=new AtomicReference<>();
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),ended=new CountDownLatch(1);
        boolean failSave,failDelete,failRecover,failLoad,block;
        public SqlFavoriteStore.Snapshot load() throws Exception {
            assertFalse(javafx.application.Platform.isFxApplicationThread()); reads.incrementAndGet();
            if(failLoad) throw new java.io.IOException("secret synthetic refresh diagnostic"); started.countDown();
            if(block) try { while(true) { try { if(release.await(5,TimeUnit.SECONDS)) break; throw new AssertionError("release timed out"); } catch(InterruptedException ignored) {} } } finally { ended.countDown(); }
            return new SqlFavoriteStore.Snapshot(values,recoverable,recoverable.size(),true);
        }
        public void save(SqlFavorite value,SqlFavorite old) throws Exception {
            assertFalse(javafx.application.Platform.isFxApplicationThread()); saves.incrementAndGet();
            if(failSave) throw new java.io.IOException("secret SQL diagnostic");
            expected.set(old); if(old!=null) assertTrue(values.remove(old)); values.add(value);
        }
        public void delete(SqlFavorite value) throws Exception { assertFalse(javafx.application.Platform.isFxApplicationThread()); deletes.incrementAndGet(); if(failDelete) throw new java.io.IOException("secret synthetic delete diagnostic"); assertTrue(values.remove(value)); }
        public SqlFavorite recover(UUID id,long now) throws Exception {
            assertFalse(javafx.application.Platform.isFxApplicationThread()); recovers.incrementAndGet(); if(failRecover) throw new java.io.IOException("secret synthetic recover diagnostic");
            var old=recoverable.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
            var copy=new SqlFavorite(UUID.randomUUID(),old.name(),old.group(),old.sql(),now); values.add(copy); return copy;
        }
    }
    private static final class Fixture implements AutoCloseable {
        final FxTaskRunner runner; final SqlFavoritesDialog view;
        final CountDownLatch modalExited=new CountDownLatch(1);
        Fixture(SqlFavoritesDialog.Repository repo,String seed) throws Exception { this(repo,seed,null,new FxTaskRunner()); }
        Fixture(SqlFavoritesDialog.Repository repo,String seed,javafx.stage.Window owner,FxTaskRunner runner) throws Exception {this(repo,seed,owner,runner,false);}
        Fixture(SqlFavoritesDialog.Repository repo,String seed,javafx.stage.Window owner,FxTaskRunner runner,boolean modal) throws Exception {
            this.runner=runner; view=FxUiTestSupport.call(() -> {
            var d=new SqlFavoritesDialog(repo,owner,runner,seed);
            if(modal) javafx.application.Platform.runLater(() -> {
                // Match the production show(...) ownership wrapper, including its exit cleanup.
                try(d) {d.dialog().showAndWait();} finally {modalExited.countDown();}
            }); else d.dialog().show();return d;
        }); }
        Button button(String id) { return (Button)view.dialog().getDialogPane().lookup("#favorites-"+id); }
        TextField text(String id) { return (TextField)view.dialog().getDialogPane().lookup("#favorites-"+id); }
        TextArea sql() { return (TextArea)view.dialog().getDialogPane().lookup("#favorites-sql"); }
        ListView<?> list() { return (ListView<?>)view.dialog().getDialogPane().lookup("#favorites-list"); }
        ScrollPane scroll() { return (ScrollPane)view.dialog().getDialogPane().lookup("#favorites-scroll"); }
        Label status() { return (Label)view.dialog().getDialogPane().lookup("#favorites-status"); }
        void idle() throws Exception {
            CountDownLatch ready=new CountDownLatch(1);
            FxUiTestSupport.call(() -> { if(!button("reload").isDisabled()) ready.countDown(); else button("reload").disabledProperty().addListener((o,b,a) -> { if(!a) ready.countDown(); }); return null; });
            assertTrue(ready.await(5,TimeUnit.SECONDS)); FxUiTestSupport.call(() -> null);
        }
        @Override public void close() throws Exception { FxUiTestSupport.call(() -> { view.confirmDiscard=() -> true; view.close(); view.dialog().setOnCloseRequest(null); view.dialog().close(); return null; }); runner.close(); }
    }
}
