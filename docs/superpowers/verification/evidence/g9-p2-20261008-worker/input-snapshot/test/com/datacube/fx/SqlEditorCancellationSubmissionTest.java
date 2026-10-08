package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.service.JdbcEditorSession;
import com.datacube.spi.model.DbType;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Only this pane's real queue runner is substituted with an independently closed runner. */
class SqlEditorCancellationSubmissionTest {
    @TempDir Path directory;
    @BeforeAll static void preloadFxInRunnerOwnedHome() throws Exception {AppShellWorkspaceShutdownTest.preloadFxNatives();}

    @Test void rejectedUnstartedQueueOperationInvalidatesItsExecutionHandleEvenWithCallbacksSuppressed() throws Exception {
        try(var f=new AppShellSqlCancelIdentityTest.Fixture(DbType.POSTGRESQL,directory,false)) {
            var original=(FxTaskRunner)AppShellSqlCancelIdentityTest.field(f.queue,"runner");
            var rejected=new FxTaskRunner();rejected.close();
            var operations=new AtomicInteger();var callbacks=new AtomicInteger();var reused=new AtomicInteger();
            try {
                JdbcEditorSession.ExecutionHandle handle=FxUiTestSupport.call(()-> {
                    f.queue.suppressCallbacks();AppShellSqlCancelIdentityTest.set(f.queue,"runner",rejected);
                    Method submit=SqlEditorPane.class.getDeclaredMethod("submitSessionOperation",SerialSessionOperationQueue.OperationKind.class,Callable.class,Consumer.class,Consumer.class);submit.setAccessible(true);
                    try {submit.invoke(f.pane,SerialSessionOperationQueue.OperationKind.EXECUTE,(Callable<Integer>)operations::incrementAndGet,(Consumer<Integer>)ignored->callbacks.incrementAndGet(),(Consumer<Throwable>)ignored->callbacks.incrementAndGet());}
                    catch(InvocationTargetException error){if(error.getCause() instanceof Exception cause)throw cause;if(error.getCause() instanceof Error cause)throw cause;throw error;}
                    return (JdbcEditorSession.ExecutionHandle)AppShellSqlCancelIdentityTest.field(f.pane,"executionHandle");
                });
                assertNotNull(handle);assertTrue(((AtomicBoolean)AppShellSqlCancelIdentityTest.field(rejected,"closed")).get());
                System.out.println("CANCEL_SUBMISSION_REJECTION_ACTUAL queue="+f.queue.snapshot()+" idle="+f.queue.idle().toCompletableFuture().isDone()+" operations="+operations+" callbacks="+callbacks+" retainedHandle="+handle+" originalRunnerClosed="+AppShellSqlCancelIdentityTest.field(original,"closed")+" fixtureCleanupNotStarted=true");
                assertTrue(f.queue.idle().toCompletableFuture().isDone());assertNull(f.queue.snapshot().currentKind());assertEquals(0,operations.get());assertEquals(0,callbacks.get());
                assertThrows(IllegalStateException.class,()->f.session.executeCancellable(handle,()-> {reused.incrementAndGet();System.out.println("CANCEL_SUBMISSION_REJECTION_ACTUAL oldHandleWasReused=true");return null;}),"a physically unstarted rejected invocation is terminal even without its UI failure callback");
                assertEquals(0,reused.get());
            }finally {
                FxUiTestSupport.call(()-> {
                    AppShellSqlCancelIdentityTest.set(f.queue,"runner",original);f.queue.reopen();
                    Method refresh=SqlEditorPane.class.getDeclaredMethod("refreshOperationControls");refresh.setAccessible(true);refresh.invoke(f.pane);
                    assertSame(original,AppShellSqlCancelIdentityTest.field(f.queue,"runner"));assertFalse(((AtomicBoolean)AppShellSqlCancelIdentityTest.field(original,"closed")).get());return null;
                });
            }
        }
    }
}
