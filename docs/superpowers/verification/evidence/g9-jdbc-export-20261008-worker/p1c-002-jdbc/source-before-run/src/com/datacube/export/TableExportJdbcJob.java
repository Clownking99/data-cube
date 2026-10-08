package com.datacube.export;

import com.datacube.service.ConnectionManager;
import com.datacube.service.ExportTarget;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.DbType;
import java.io.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;

/** One read-export's dedicated JDBC, value resources, writer and physical cancellation workers. */
final class TableExportJdbcJob {
    @FunctionalInterface interface Checked<T>{T run() throws Exception;}
    @FunctionalInterface interface Parser<T>{T read(ResultSet rows) throws Exception;}
    record Policy(Duration timeout,Duration grace,Duration observation,LongSupplier clock,Consumer<Receipt> observer){
        Policy{Objects.requireNonNull(clock);Objects.requireNonNull(observer);
            if(timeout.isZero()||timeout.isNegative()||grace.isNegative()||observation.isNegative())throw new IllegalArgumentException("Invalid export budget");
            timeout.toNanos();grace.toNanos();observation.toNanos();}
        static Policy defaults(){return new Policy(Duration.ofMinutes(10),Duration.ofSeconds(1),Duration.ofSeconds(5),System::nanoTime,ignored->{});}
        @Override public String toString(){return "TableExportJdbcPolicy";}
    }
    record Receipt(boolean openerPending,boolean mainFinished,int unresolvedResources,int livingWorkers,
                   boolean activeStatement,boolean cleanupPending,TableExportFailure.Kind failure,boolean physicallySettled){}
    final ResultExportOperation operation;
    final ExportTarget source;
    final Policy policy;
    final long beginning;
    final SqlExecutionControl control=new SqlExecutionControl();
    final List<Lease> resources=new CopyOnWriteArrayList<>();
    final List<Thread> workers=new CopyOnWriteArrayList<>();
    final AtomicReference<TableExportFailure.Kind> failure=new AtomicReference<>();
    final AtomicReference<Error> fatal=new AtomicReference<>();
    final Thread watchdog;
    volatile Connection connection;
    volatile Lease connectionLease;
    volatile boolean openerPending=true,mainFinished,cleanupPending,stopped,transactionStarted;
    volatile long stopAt;
    boolean cancelStarted,fallbackStarted;
    TableExportJdbcJob(ExportTarget source,ResultExportOperation operation,Policy policy){
        this.source=source;this.operation=operation;this.policy=policy;beginning=policy.clock().getAsLong();
        watchdog=Thread.ofVirtual().name("DataCube-table-export-watchdog").unstarted(this::supervise);
        operation.requireBeforePublication(()->{
            if(expired()){operation.timedOut();throw new TableExportFailure(TableExportFailure.Kind.TIMEOUT);}
        });
    }
    boolean expired(){return policy.clock().getAsLong()-beginning>=policy.timeout().toNanos();}
    void check(){
        operation.check();
        if(!source.current())operation.invalidateTarget();
        if(expired())operation.timedOut();
        operation.check();
        if(failure.get()!=null)throw new TableExportFailure(failure.get());
    }
    void stop(){
        if(!stopped){stopAt=policy.clock().getAsLong();stopped=true;}
        control.requestCancellation();LockSupport.unpark(watchdog);
    }
    void fail(TableExportFailure.Kind kind){failure.compareAndSet(null,kind);operation.cancel();stop();}
    void startWorker(String name,Runnable work){
        Thread worker=Thread.ofVirtual().name("DataCube-table-export-"+name).unstarted(()->{
            try{work.run();}catch(Error error){fatal.compareAndSet(null,error);fail(TableExportFailure.Kind.CLEANUP);}
            catch(RuntimeException error){fail(TableExportFailure.Kind.CLEANUP);}finally{LockSupport.unpark(watchdog);}
        });workers.add(worker);worker.start();
    }
    /** Only successful release/status or a successfully released JDBC parent can resolve this lease. */
    final class Lease {
        final String name;
        final Checked<Void> close;
        final Checked<Boolean> status;
        final Lease parent;
        final Runnable onResolved;
        final AtomicInteger state=new AtomicInteger(); // open, closing, resolved, failed
        boolean finalAttempt; // watchdog only
        Lease(String name,Checked<Void> close,Checked<Boolean> status,Lease parent,Runnable onResolved){
            this.name=name;this.close=close;this.status=status;this.parent=parent;this.onResolved=onResolved;resources.add(this);
        }
        boolean resolved(){
            if(state.get()==2)return true;
            // An in-flight close is still an owned call even if its JDBC parent has closed.
            if(state.get()!=1&&parent!=null&&parent.resolved()){resolve();return true;}
            return false;
        }
        void resolve(){state.set(2);resources.remove(this);onResolved.run();LockSupport.unpark(watchdog);}
        void closeFirst(){if(state.compareAndSet(0,1))closeAcquired();}
        void closeAcquired(){
            boolean released=false;
            try{close.run();released=true;}
            catch(Error error){fatal.compareAndSet(null,error);fail(TableExportFailure.Kind.CLEANUP);}
            catch(Exception error){
                fail(TableExportFailure.Kind.CLEANUP);
                if(status!=null)try{released=status.run();}catch(Error fatalError){fatal.compareAndSet(null,fatalError);}
                    catch(Exception unknown){/* Failed status never proves release. */}
            }finally{if(released)resolve();else state.set(3);LockSupport.unpark(watchdog);}
        }
        void finalClose(){
            if(resolved())return;
            if(!finalAttempt&&state.compareAndSet(3,1)){finalAttempt=true;startWorker(name+"-final-close",this::closeAcquired);}
        }
    }
    Lease own(String name,Checked<Void> close,Checked<Boolean> status,Lease parent,Runnable onResolved){
        return new Lease(name,close,status,parent,onResolved);
    }
    Lease ownValue(String name,Checked<Void> close){return own(name,close,null,null,()->{});}
    void closeValue(Lease resource){resource.closeFirst();if(fatal.get()!=null)return;
        if(!resource.resolved())throw new TableExportFailure(TableExportFailure.Kind.CLEANUP);check();}
    <T>T run(ConnectionManager manager,Checked<T> body)throws Exception{
        T result=null;Throwable primary=null;
        try{
            watchdog.start();check();
            connection=manager.openDedicated(source.config(),source.provider());
            Connection owned=Objects.requireNonNull(connection);
            connectionLease=own("connection",()->{owned.close();return null;},owned::isClosed,null,()->{});
            openerPending=false;check();
            owned.setReadOnly(true);check();owned.setAutoCommit(false);transactionStarted=true;check();
            if(source.config().type()==DbType.POSTGRESQL){owned.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);check();}
            else if(source.config().type()==DbType.ORACLE)executeSetup("SET TRANSACTION READ ONLY");
            else throw new TableExportFailure(TableExportFailure.Kind.SOURCE);
            result=body.run();check();
        }catch(Error error){fatal.compareAndSet(null,error);primary=error;operation.cancel();stop();}
        catch(Throwable error){primary=error;operation.cancel();stop();}
        finally{
            openerPending=false;
            // Values/file output/rs/stmt precede rollback and the dedicated connection close.
            var remaining=new ArrayList<>(resources);Collections.reverse(remaining);
            for(Lease lease:remaining)if(lease!=connectionLease)lease.closeFirst();
            if(connection!=null&&!connectionLease.resolved()){
                try{if(transactionStarted)connection.rollback();}
                catch(Error error){fatal.compareAndSet(null,error);fail(TableExportFailure.Kind.CLEANUP);}
                catch(Exception error){fail(TableExportFailure.Kind.CLEANUP);}
                connectionLease.closeFirst();
            }
            mainFinished=true;LockSupport.unpark(watchdog);
            boolean interrupted=false;
            while(watchdog.isAlive())try{watchdog.join();}catch(InterruptedException stop){interrupted=true;operation.cancel();stop();}
            try{observe(true);}catch(Error error){fatal.compareAndSet(null,error);}
            if(interrupted)Thread.currentThread().interrupt();
        }
        if(fatal.get()!=null){
            if(failure.get()!=null)fatal.get().addSuppressed(new TableExportFailure(failure.get()));
            throw fatal.get();
        }
        if(failure.get()!=null)throw new TableExportFailure(failure.get());
        if(primary instanceof TableExportFailure safe)throw safe;
        if(primary instanceof CancellationException cancelled){operation.check();throw cancelled;}
        if(primary instanceof SQLException)throw new TableExportFailure(TableExportFailure.Kind.SOURCE);
        if(primary instanceof Exception error)throw error;
        operation.check();return result;
    }
    void executeSetup(String sql)throws Exception{
        check();Statement statement=connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
        var activation=new AtomicReference<SqlExecutionControl.Activation>();
        Lease lease=own("setup-statement",()->{statement.close();return null;},statement::isClosed,connectionLease,()->control.release(activation.get()));
        try{activation.set(control.activate(statement,remainingSeconds()));check();control.ensureNotCancelled(activation.get());
            statement.execute(sql);check();}
        finally{lease.closeFirst();}
        check();
    }
    int remainingSeconds(){long left=policy.timeout().toNanos()-(policy.clock().getAsLong()-beginning);return (int)Math.max(1,Math.min(Integer.MAX_VALUE,(left+999_999_999L)/1_000_000_000L));}
    final class Cursor implements AutoCloseable {
        final Statement statement;
        final ResultSet rows;
        final Lease stmtLease,rowsLease;
        Cursor(Statement statement,ResultSet rows,Lease stmtLease){
            this.statement=statement;this.rows=rows;this.stmtLease=stmtLease;
            rowsLease=own("result-set",()->{rows.close();return null;},rows::isClosed,stmtLease,()->{});
        }
        public void close(){rowsLease.closeFirst();stmtLease.closeFirst();check();}
    }
    Cursor openCursor(String sql,List<String> bindings)throws Exception{
        check();Statement statement=bindings==null
                ?connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)
                :connection.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
        var activation=new AtomicReference<SqlExecutionControl.Activation>();
        Lease lease=own("statement",()->{statement.close();return null;},statement::isClosed,connectionLease,()->control.release(activation.get()));
        try{
            statement.setFetchSize(16);check();activation.set(control.activate(statement,remainingSeconds()));check();
            if(bindings!=null){for(int i=0;i<bindings.size();i++){((PreparedStatement)statement).setString(i+1,bindings.get(i));check();}}
            control.ensureNotCancelled(activation.get());check();
            ResultSet rows=bindings==null?statement.executeQuery(sql):((PreparedStatement)statement).executeQuery();
            Cursor cursor=new Cursor(statement,Objects.requireNonNull(rows),lease);check();return cursor;
        }catch(Throwable error){lease.closeFirst();throw error;}
    }
    <T>T query(String sql,List<String> bindings,Parser<T> parser)throws Exception{
        try(Cursor cursor=openCursor(sql,bindings)){return parser.read(cursor.rows);}
    }
    OutputStream output(OutputStream raw){
        Lease lease=ownValue("writer-output",()->{raw.close();return null;});
        return new FilterOutputStream(raw){
            @Override public void write(byte[] bytes,int off,int len)throws IOException{out.write(bytes,off,len);}
            @Override public void close()throws IOException{
                try{flush();}catch(IOException error){fail(TableExportFailure.Kind.CLEANUP);}
                lease.closeFirst();if(!lease.resolved())throw new IOException("Export output release unresolved");
                if(failure.get()==TableExportFailure.Kind.CLEANUP)throw new IOException("Export output release failed");
            }
        };
    }
    int unresolved(){return (int)resources.stream().filter(lease->!lease.resolved()).count();}
    int livingWorkers(){return (int)workers.stream().filter(Thread::isAlive).count();}
    void supervise(){
        while(true){
            try{
                long now=policy.clock().getAsLong();
                if(expired())operation.timedOut();
                if(operation.cancelled()||failure.get()!=null)stop();
                if(stopped&&!cancelStarted){cancelStarted=true;startWorker("statement-cancel",()->{
                    try{control.cancel();}catch(SQLException error){/* Owned connection fallback below still applies. */}
                });}
                Lease owned=connectionLease;
                if(stopped&&owned!=null&&!fallbackStarted&&now-stopAt>=policy.grace().toNanos()){
                    fallbackStarted=true;startWorker("connection-fallback",()->{owned.closeFirst();});
                }
                if(mainFinished){for(Lease lease:resources)lease.finalClose();}
                if(stopped&&!cleanupPending&&now-stopAt>=policy.observation().toNanos()){
                    cleanupPending=true;operation.markCleanupPending();
                }
                if(mainFinished&&unresolved()==0&&livingWorkers()==0)return;
                observe(false);
            }catch(Error error){fatal.compareAndSet(null,error);fail(TableExportFailure.Kind.CLEANUP);}
            catch(RuntimeException error){fail(TableExportFailure.Kind.CLEANUP);}
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if(Thread.interrupted()){operation.cancel();stop();}
        }
    }
    void observe(boolean settled){
        try{policy.observer().accept(new Receipt(openerPending,mainFinished,unresolved(),livingWorkers(),control.hasActiveStatement(),cleanupPending,failure.get(),
                settled&&mainFinished&&unresolved()==0&&livingWorkers()==0));}
        catch(RuntimeException ignored){/* Observer diagnostics never alter resources. */}
    }
}
