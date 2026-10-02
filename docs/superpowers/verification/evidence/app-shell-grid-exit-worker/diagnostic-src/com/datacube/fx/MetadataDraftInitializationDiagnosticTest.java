package com.datacube.fx;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.spi.model.*;
import javafx.application.Platform;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** External bounded diagnosis reuses the original private Metadata fixture, not a substitute shell. */
class MetadataDraftInitializationDiagnosticTest {
 @org.junit.jupiter.api.BeforeAll static void initializeNativeCacheInWorkerProfile() throws Exception {
  FxUiTestSupport.call(()->{
   var label=new javafx.scene.control.Label("synthetic diagnostic font/effect initialization");
   label.setEffect(new javafx.scene.effect.DropShadow());
   new javafx.scene.Scene(label,200,50);label.applyCss();
   // A synthetic node snapshot initializes font/effect DLLs in the worker profile, before @TempDir user.home.
   // It is neither captured desktop content nor native interaction acceptance evidence.
   label.snapshot(null,null);return null;
  });
 }
 @TempDir Path directory;
 @Test void originalFixtureRequiresCompletedInitializationBeforeMandatoryShutdown() throws Exception {
  Class<?> type=Class.forName("com.datacube.fx.MetadataSearchShellRoutingTest$Fixture");
  var constructor=type.getDeclaredConstructor(Path.class,TableInfo.Kind.class,DbType.class,String.class);constructor.setAccessible(true);
  Object fixture=constructor.newInstance(directory,TableInfo.Kind.TABLE,DbType.POSTGRESQL,"direct");
  AppShell shell=(AppShell)field(fixture,"shell");
  SqlDraftUi owner=FxUiTestSupport.call(()->((LazyValue<SqlDraftUi>)field(shell,"sqlDrafts")).get());
  FxUiTestSupport.call(()->owner.runtime().shutdown()).get(5,TimeUnit.SECONDS);
  ExecutorService writer=Executors.newSingleThreadExecutor();
  CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
  boolean completed=false;
  try {
   FxUiTestSupport.call(()->{
    var runtime=new SqlDraftCoordinator(directory.resolve("controlled-drafts"),command->writer.execute(()->{
     entered.countDown();
     try {if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("controlled initializer release deadline");}
     catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}
     command.run();
    }),Platform::runLater,Platform::isFxApplicationThread,()->System.nanoTime()/1000000,System::currentTimeMillis);
    set(owner,"runtime",runtime);owner.workspace().close();
    set(owner,"workspace",new SqlWorkspaceUi(owner,(ContentTabPane)field(shell,"contentTabs"),()->System.nanoTime()/1000000,null));
    return null;
   });
   assertTrue(entered.await(2,TimeUnit.SECONDS));
   invoke(fixture,"searchAndChoose",new Class<?>[]{SchemaMetadataSearchDialog.Action.class},SchemaMetadataSearchDialog.Action.SELECT);
   CompletableFuture<Void> flush=FxUiTestSupport.call(()->{
    assertEquals(SqlDraftCoordinator.Mode.INITIALIZING,owner.runtime().mode());assertTrue(owner.runtime().managementPending());
    TabPane tabs=(TabPane)field(fixture,"tabs");
    SqlEditorPane pane=tabs.getTabs().getFirst().getContent().getProperties().values().stream().filter(SqlEditorPane.class::isInstance).map(SqlEditorPane.class::cast).findFirst().orElseThrow();
    var handle=(SqlDraftCoordinator.Handle)field(field(pane,"draftBinding"),"handle");
    System.out.println("DIAGNOSTIC mode="+owner.runtime().mode()+" managementPending="+owner.runtime().managementPending());
    return handle.flush();
   });
   var failure=assertThrows(ExecutionException.class,()->flush.get(2,TimeUnit.SECONDS));
   assertEquals(SqlDraftCoordinator.FailureReason.INITIALIZING,((SqlDraftCoordinator.Failure)failure.getCause()).reason());
   System.out.println("DIAGNOSTIC flushFailure="+failure.getCause());
   if(Boolean.getBoolean("datacube.diagnostic.afterBarrier")){release.countDown();awaitReady(owner);barrier(owner);}
   invoke(fixture,"close",new Class<?>[]{});
   completed=true;System.out.println("DIAGNOSTIC originalFixture.close=COMPLETED afterBarrier=true");
  } finally {
   release.countDown();
   try {
    if(!completed){awaitReady(owner);barrier(owner);invoke(fixture,"close",new Class<?>[]{});System.out.println("DIAGNOSTIC cleanupAfterControlledRelease=COMPLETED");}
   } finally {writer.shutdown();assertTrue(writer.awaitTermination(5,TimeUnit.SECONDS));}
  }
 }
 static void barrier(SqlDraftUi owner)throws Exception{
  var refresh=FxUiTestSupport.call(()->owner.runtime().refresh());assertTrue(refresh.get(5,TimeUnit.SECONDS).succeeded());
  FxUiTestSupport.call(()->{assertEquals(SqlDraftCoordinator.Mode.ENABLED,owner.runtime().mode());assertFalse(owner.runtime().managementPending());System.out.println("DIAGNOSTIC barrier mode=ENABLED managementPending=false");return null;});
 }
 static void awaitReady(SqlDraftUi owner)throws Exception{
  var ready=new CompletableFuture<Void>();
  AutoCloseable observe=FxUiTestSupport.call(()->{
   Runnable check=()->{if(owner.runtime().mode()!=SqlDraftCoordinator.Mode.INITIALIZING)ready.complete(null);};
   var registration=owner.observe(check);check.run();return registration;
  });
  try{ready.get(5,TimeUnit.SECONDS);}finally{FxUiTestSupport.call(()->{observe.close();return null;});}
 }
 static Object field(Object owner,String name)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 static void set(Object owner,String name,Object value)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
 static Object invoke(Object owner,String name,Class<?>[] types,Object...args)throws Exception{
  var method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
  try{return method.invoke(owner,args);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Error error)throw error;if(failed.getCause() instanceof Exception exception)throw exception;throw failed;}
 }
}
