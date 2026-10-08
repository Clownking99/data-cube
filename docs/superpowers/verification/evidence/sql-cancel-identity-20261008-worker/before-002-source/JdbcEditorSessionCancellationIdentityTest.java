package com.datacube.service;

import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Service-level ownership/publication fault probes; full provider/UI coverage lives in AppShellSqlCancelIdentityTest. */
class JdbcEditorSessionCancellationIdentityTest {
    @ParameterizedTest @ValueSource(strings={"SCRIPT","EXPLAIN","PREPARED","COMMIT_REUSE"})
    void acceptedCancellationBeforeControlPublicationNeverDispatchesJdbc(String entry) throws Exception {
        var jdbc=new Jdbc();var runner=new Runner();var armed=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var session=new JdbcEditorSession("synthetic",safety(),jdbc::open,runner,()-> {if(armed.get()){entered.countDown();await(release);}})) {
            if(entry.equals("COMMIT_REUSE")){session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);session.executeScript("UPDATE synthetic SET value = 1 WHERE id = 1",null,10,null,false);}
            int opens=jdbc.opens.get(),calls=runner.calls.get();var handle=session.newExecutionHandle();var result=new AtomicReference<QueryResult>();var error=new AtomicReference<Throwable>();armed.set(true);
            Thread worker=Thread.startVirtualThread(()-> {try{result.set(session.executeCancellable(handle,()->execute(session,entry)));}catch(Throwable failure){error.set(failure);}});
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(session.snapshot().running());
                assertEquals(JdbcEditorSession.CancelOutcome.CANCELLED,session.captureCancellation(handle).call());
                release.countDown();join(worker);assertNull(error.get());assertEquals(QueryResult.FailureKind.CANCELLED,result.get().failureKind);
                assertEquals(calls,runner.calls.get());assertEquals(opens,jdbc.opens.get());assertEquals(0,jdbc.closes.get());assertEquals(0,jdbc.commits.get());
                assertFalse(session.snapshot().running());assertFalse(session.snapshot().cancelling());
                assertThrows(IllegalStateException.class,()->session.executeCancellable(handle,()->null));
                armed.set(false);assertEquals(QueryResult.Kind.UPDATE,session.executeCancellable(session.newExecutionHandle(),()->execute(session,"SCRIPT")).kind);
            }finally {release.countDown();join(worker);}
        }
    }

    @Test void finishedRequestCannotTouchANewerActiveControlOrConnection() throws Exception {
        var jdbc=new Jdbc();var runner=new Runner();try(var session=new JdbcEditorSession("synthetic",safety(),jdbc::open,runner)) {
            var empty=session.captureCancellation();var old=session.newExecutionHandle();session.executeCancellable(old,()->execute(session,"SCRIPT"));var oldRequest=session.captureCancellation(old);runner.block=true;
            var error=new AtomicReference<Throwable>();Thread worker=Thread.startVirtualThread(()-> {try{session.executeCancellable(session.newExecutionHandle(),()->execute(session,"SCRIPT"));}catch(Throwable failure){error.set(failure);}});
            try {
                assertTrue(runner.entered.await(5,TimeUnit.SECONDS));SqlExecutionControl active=runner.control.get();
                assertEquals(JdbcEditorSession.CancelOutcome.NOTHING_RUNNING,empty.call());assertEquals(JdbcEditorSession.CancelOutcome.NOTHING_RUNNING,oldRequest.call());assertEquals(JdbcEditorSession.CancelOutcome.NOTHING_RUNNING,oldRequest.call());
                assertFalse(active.cancellationRequested());assertEquals(0,runner.cancelCalls.get());assertEquals(0,jdbc.closes.get());assertTrue(session.snapshot().running());assertFalse(session.snapshot().cancelling());
                runner.release.countDown();join(worker);assertNull(error.get());
            }finally {runner.release.countDown();join(worker);}
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void preOperationExceptionAlwaysClearsContextAndInvalidatesTheHandle(boolean fatal) throws Exception {
        var jdbc=new Jdbc();var runner=new Runner();try(var session=new JdbcEditorSession("synthetic",safety(),jdbc::open,runner)) {
            var handle=session.newExecutionHandle();
            if(fatal)assertThrows(AssertionError.class,()->session.executeCancellable(handle,()-> {throw new AssertionError("synthetic pre-operation Error");}));
            else assertThrows(IllegalStateException.class,()->session.executeCancellable(handle,()-> {throw new IllegalStateException("synthetic pre-operation failure");}));
            assertEquals(JdbcEditorSession.CancelOutcome.NOTHING_RUNNING,session.captureCancellation(handle).call());assertThrows(IllegalStateException.class,()->session.executeCancellable(handle,()->null));
            assertEquals(QueryResult.Kind.UPDATE,session.executeCancellable(session.newExecutionHandle(),()->execute(session,"SCRIPT")).kind);
        }
    }

    @Test void foreignNestedAndAbandonedHandlesCannotBeRebound() throws Exception {
        var jdbc=new Jdbc();var runner=new Runner();try(var session=new JdbcEditorSession("synthetic",safety(),jdbc::open,runner);var other=new JdbcEditorSession("other",safety(),jdbc::open,runner)) {
            var handle=session.newExecutionHandle();assertThrows(IllegalArgumentException.class,()->other.captureCancellation(handle));assertThrows(IllegalArgumentException.class,()->other.executeCancellable(handle,()->null));assertThrows(IllegalArgumentException.class,()->other.abandonExecution(handle));
            var nested=session.newExecutionHandle();session.executeCancellable(handle,()-> {assertThrows(IllegalStateException.class,()->session.executeCancellable(nested,()->null));return execute(session,"SCRIPT");});
            assertEquals(QueryResult.Kind.UPDATE,session.executeCancellable(nested,()->execute(session,"SCRIPT")).kind);
            var rejected=session.newExecutionHandle();var request=session.captureCancellation(rejected);session.abandonExecution(rejected);assertEquals(JdbcEditorSession.CancelOutcome.NOTHING_RUNNING,request.call());assertThrows(IllegalStateException.class,()->session.executeCancellable(rejected,()->null));
            assertEquals(2,runner.calls.get());assertEquals(0,runner.cancelCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void physicalCancelRetainsOwnershipThroughFinishInterruptionAndRepeatedRequest(boolean lateFailure) throws Exception {
        var jdbc=new Jdbc();var runner=new Runner();runner.block=true;runner.blockCancel=true;runner.lateCancelFailure=lateFailure;
        try(var session=new JdbcEditorSession("synthetic",safety(),jdbc::open,runner)) {
            var handle=session.newExecutionHandle();var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
            Thread worker=Thread.startVirtualThread(()-> {try{session.executeCancellable(handle,()->execute(session,"SCRIPT"));}catch(Throwable error){failure.set(error);}finally{interrupted.set(Thread.currentThread().isInterrupted());}});
            var cancelFailure=new AtomicReference<Throwable>();var cancelOutcome=new AtomicReference<JdbcEditorSession.CancelOutcome>();
            Thread cancel=null,next=null;
            try {
                assertTrue(runner.entered.await(5,TimeUnit.SECONDS));var request=session.captureCancellation(handle);cancel=Thread.startVirtualThread(()-> {try{cancelOutcome.set(request.call());}catch(Throwable error){cancelFailure.set(error);}});assertTrue(runner.cancelEntered.await(5,TimeUnit.SECONDS));
                runner.release.countDown();assertTrue(runner.statementClosed.await(5,TimeUnit.SECONDS));awaitFinishWaiting(worker);worker.interrupt();
                assertTrue(session.snapshot().running());assertTrue(session.snapshot().cancelling());assertEquals(JdbcEditorSession.CancelOutcome.CANCELLED,request.call());assertEquals(1,runner.cancelCalls.get());
                var nextStarting=new CountDownLatch(1);next=Thread.startVirtualThread(()-> {nextStarting.countDown();try{execute(session,"SCRIPT");}catch(Throwable error){failure.set(error);}});assertTrue(nextStarting.await(5,TimeUnit.SECONDS));awaitWaiting(next);assertEquals(1,runner.calls.get());assertEquals(0,jdbc.closes.get());
                runner.cancelRelease.countDown();join(cancel);join(worker);join(next);assertNull(cancelFailure.get(),"physical cancel task has no uncaught failure");assertEquals(lateFailure ? JdbcEditorSession.CancelOutcome.CONNECTION_CLOSED : JdbcEditorSession.CancelOutcome.CANCELLED,cancelOutcome.get());assertNull(failure.get());assertTrue(interrupted.get());assertEquals(2,runner.calls.get());assertEquals(lateFailure ? 1 : 0,jdbc.closes.get());assertEquals(lateFailure ? 2 : 1,jdbc.opens.get());assertFalse(session.snapshot().running());assertFalse(session.snapshot().cancelling());
            }finally {runner.release.countDown();runner.cancelRelease.countDown();join(worker);if(cancel!=null)join(cancel);if(next!=null)join(next);}
        }
    }

    static QueryResult execute(JdbcEditorSession session,String entry) throws Exception {
        return switch(entry) {
            case "EXPLAIN" -> session.explain("SELECT synthetic",null,false);
            case "PREPARED" -> session.executePrepared("SELECT synthetic WHERE id = ?",List.of(new SqlParameter(Types.INTEGER,1)),null,10);
            case "COMMIT_REUSE" -> session.executeScript("COMMIT",null,10,null,false).outcomes().getFirst().result();
            default -> session.executeScript("UPDATE synthetic SET value = 1 WHERE id = 1",null,10,null,false).outcomes().getFirst().result();
        };
    }
    static ConnectionSafetyOptions safety(){return new ConnectionSafetyOptions(ConnectionEnvironment.TEST,false,30);}
    static void await(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
    static void join(Thread worker) throws InterruptedException {assertTrue(worker.join(Duration.ofSeconds(3)),"bounded actual task thread join");}
    static void awaitFinishWaiting(Thread worker){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<deadline){if(worker.getState()==Thread.State.WAITING && Arrays.stream(worker.getStackTrace()).anyMatch(e->e.getMethodName().equals("finishOperation")&&e.getClassName().equals(JdbcEditorSession.class.getName())))return;Thread.onSpinWait();}fail("physical finish waiting observation");}
    static void awaitWaiting(Thread worker){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<deadline){if(worker.getState()==Thread.State.WAITING)return;Thread.onSpinWait();}fail("next operation waits for retained singleFlight");}
    static final class Jdbc {
        final AtomicInteger opens=new AtomicInteger(),closes=new AtomicInteger(),commits=new AtomicInteger();
        Connection open(){opens.incrementAndGet();var closed=new AtomicBoolean();return proxy(Connection.class,(p,m,a)->switch(m.getName()) {
            case "close" -> {assertTrue(closed.compareAndSet(false,true));closes.incrementAndGet();yield null;}
            case "isClosed" -> closed.get();case "isValid" -> !closed.get();case "commit" -> {commits.incrementAndGet();yield null;}
            case "rollback","setAutoCommit","setReadOnly" -> null;default -> throw new AssertionError(m.getName());
        });}
    }
    static final class Runner implements SqlRunner {
        final AtomicInteger calls=new AtomicInteger(),cancelCalls=new AtomicInteger();final AtomicReference<SqlExecutionControl> control=new AtomicReference<>();
        final AtomicBoolean blockClaimed=new AtomicBoolean();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),cancelEntered=new CountDownLatch(1),cancelRelease=new CountDownLatch(1),statementClosed=new CountDownLatch(1);
        volatile boolean block,blockCancel,lateCancelFailure;
        QueryResult run(SqlExecutionOptions options) {
            calls.incrementAndGet();control.set(options.control());if(!block || !blockClaimed.compareAndSet(false,true))return QueryResult.update(1,1);
            Statement statement=proxy(Statement.class,(p,m,a)->switch(m.getName()) {
                case "cancel" -> {cancelCalls.incrementAndGet();cancelEntered.countDown();if(blockCancel)await(cancelRelease);if(lateCancelFailure)throw new SQLException("synthetic late cancel error");yield null;}
                case "close" -> {statementClosed.countDown();yield null;}default -> null;
            });
            SqlExecutionControl.Activation activation=null;
            try{activation=options.control().activate(statement,0);entered.countDown();await(release);return QueryResult.update(1,1);}
            catch(SQLException error){return QueryResult.error(error.getMessage(),1);}
            finally{options.control().release(activation);try{statement.close();}catch(SQLException error){throw new AssertionError(error);}}
        }
        public QueryResult execute(Connection c,String sql,String schema,SqlExecutionOptions options){return run(options);}
        public List<ScriptOutcome> executeScript(Connection c,String sql,String schema,SqlExecutionOptions options,ScriptErrorPolicy policy){return List.of(new ScriptOutcome(1,sql,run(options)));}
        public QueryResult explain(Connection c,String sql,String schema,boolean analyze,SqlExecutionOptions options){return run(options);}
        public QueryResult executePrepared(Connection c,String sql,List<SqlParameter> parameters,String schema,SqlExecutionOptions options){return run(options);}
    }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)-> {if(m.getDeclaringClass()==Object.class)return switch(m.getName()){case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);case "toString"->"synthetic "+type.getSimpleName();default->throw new AssertionError(m.getName());};return handler.invoke(p,m,a);});}
}
