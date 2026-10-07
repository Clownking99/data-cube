package com.datacube.fx;

import com.datacube.provider.oracle.OracleSqlDialect;
import com.datacube.provider.oracle.OracleSqlRunner;
import com.datacube.provider.postgres.PgSqlDialect;
import com.datacube.provider.postgres.PgSqlRunner;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC-only synthetic transaction probe; production dialect, runner and session remain unchanged. */
final class ShellSqlTransactionJdbcProbe {
    enum Action { COMMIT, ROLLBACK }
    static final String FIRST="UPDATE synthetic_items SET value = 101 WHERE id = 1";
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    final AtomicInteger opens=new AtomicInteger(),commits=new AtomicInteger(),rollbacks=new AtomicInteger(),closes=new AtomicInteger();
    final AtomicInteger globalCloses=new AtomicInteger(),pendingWrites=new AtomicInteger(),committedWrites=new AtomicInteger(),cancelCalls=new AtomicInteger();
    final List<String> executed=new CopyOnWriteArrayList<>(),trace=new CopyOnWriteArrayList<>();
    final List<AtomicInteger> statementCloses=new CopyOnWriteArrayList<>();
    final AtomicReference<Thread> executionThread=new AtomicReference<>();
    volatile Action action=Action.COMMIT;
    volatile boolean fail=true,effectBeforeFailure,autoCommit=true,closed;
    final SqlRunner runner;
    final SqlDialect dialect;
    ShellSqlTransactionJdbcProbe(DbType type) {
        dialect=type==DbType.ORACLE ? new OracleSqlDialect() : new PgSqlDialect();
        runner=type==DbType.ORACLE ? new OracleSqlRunner(dialect) : new PgSqlRunner(dialect);
    }
    DatabaseProvider provider(DbType type) {
        var factory=new ConnectionFactory() {
            public void ensureDriverLoaded() {}
            public String test(ConnConfig ignored) {throw new AssertionError("no live database test");}
            public Connection open(ConnConfig cfg) {assertEquals("synthetic.invalid",cfg.host());return ShellSqlTransactionJdbcProbe.this.open();}
        };
        return ShellSqlJdbcProbe.proxy(DatabaseProvider.class,(p,m,a)->switch(m.getName()) {
            case "type" -> type;
            case "connectionFactory" -> factory;
            case "dialect" -> dialect;
            case "sqlRunner" -> runner;
            case "metadataReader" -> ShellSqlJdbcProbe.proxy(MetadataReader.class,(mp,mm,ma)-> {
                if(List.class.isAssignableFrom(mm.getReturnType()))return List.of();
                throw new AssertionError("unexpected metadata method: "+mm.getName());
            });
            case "resultFilterSqlRenderer","schemaDiffCapability" -> Optional.empty();
            default -> throw new AssertionError("unexpected provider method: "+m.getName());
        });
    }
    private Connection open() {
        assertEquals(1,opens.incrementAndGet());trace.add("session-open");
        return ShellSqlJdbcProbe.proxy(Connection.class,(p,m,a)->switch(m.getName()) {
            case "setReadOnly" -> {assertFalse((boolean)a[0]);trace.add("readOnly:false");yield null;}
            case "setAutoCommit" -> {autoCommit=(boolean)a[0];trace.add("autoCommit:"+a[0]);yield null;}
            case "getAutoCommit" -> autoCommit;
            case "createStatement" -> statement();
            case "commit" -> {transaction(Action.COMMIT,commits);yield null;}
            case "rollback" -> {transaction(Action.ROLLBACK,rollbacks);yield null;}
            case "close" -> {assertEquals(1,closes.incrementAndGet());closed=true;trace.add("session-close");yield null;}
            case "isClosed" -> closed;
            case "isValid" -> !closed;
            default -> throw new AssertionError("unexpected session JDBC method: "+m.getName());
        });
    }
    private void transaction(Action actual,AtomicInteger counter) throws Exception {
        int count=counter.incrementAndGet();executionThread.set(Thread.currentThread());trace.add(actual+"-enter:"+count);
        if(actual==action && count==1) {
            entered.countDown();assertTrue(release.await(25,TimeUnit.SECONDS),"bounded fixture physical transaction barrier");
            assertFalse(Thread.currentThread().isInterrupted(),"non-cancellable transaction must not be interrupted");
            if(fail) {
                if(effectBeforeFailure){committedWrites.addAndGet(pendingWrites.getAndSet(0));trace.add("synthetic-commit-effect");}
                trace.add(actual+"-throw:synthetic");throw new SQLException("synthetic explicit "+actual+" failed");
            }
        }
        if(actual==Action.COMMIT)committedWrites.addAndGet(pendingWrites.getAndSet(0));else pendingWrites.set(0);
        trace.add(actual+"-return:"+count);
    }
    Connection globalCachedCloseOnly() {
        return ShellSqlJdbcProbe.proxy(Connection.class,(p,m,a)-> {
            if(m.getName().equals("close")){assertEquals(1,globalCloses.incrementAndGet());trace.add("global-close");return null;}
            throw new AssertionError("synthetic cached resource permits close only: "+m.getName());
        });
    }
    private Statement statement() {
        var closes=new AtomicInteger();statementCloses.add(closes);
        return ShellSqlJdbcProbe.proxy(Statement.class,(p,m,a)->switch(m.getName()) {
            case "setQueryTimeout","setMaxRows","setLargeMaxRows" -> null;
            case "execute" -> {assertFalse(closed);assertFalse(autoCommit);assertEquals(FIRST,((String)a[0]).trim());executed.add(FIRST);pendingWrites.incrementAndGet();trace.add("execute-dml");yield false;}
            case "getUpdateCount" -> 1;
            case "cancel" -> {cancelCalls.incrementAndGet();throw new AssertionError("transaction operation must never cancel a Statement");}
            case "close" -> {assertEquals(1,closes.incrementAndGet());trace.add("statement-close");yield null;}
            case "isClosed" -> closes.get()!=0;
            default -> throw new AssertionError("unexpected statement JDBC method: "+m.getName());
        });
    }
}
