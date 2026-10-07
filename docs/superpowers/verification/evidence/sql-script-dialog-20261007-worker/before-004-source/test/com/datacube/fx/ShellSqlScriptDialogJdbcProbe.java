package com.datacube.fx;

import com.datacube.provider.oracle.*;
import com.datacube.provider.postgres.*;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC-only probe for the production script error policy; all SQL/profile data are synthetic. */
final class ShellSqlScriptDialogJdbcProbe {
    static final String FIRST="SELECT synthetic_failure";
    static final String SECOND="UPDATE synthetic_items SET value = 202 WHERE id = 1";
    static final String THIRD="UPDATE synthetic_items SET value = 303 WHERE id = 2";
    static final String SCRIPT=FIRST+";\n"+SECOND+";";
    final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),connectionClosed=new CountDownLatch(1);
    final AtomicInteger opens=new AtomicInteger(),closes=new AtomicInteger(),globalCloses=new AtomicInteger(),statementCloses=new AtomicInteger(),cancelCalls=new AtomicInteger(),commits=new AtomicInteger(),rollbacks=new AtomicInteger();
    final List<String> executed=new CopyOnWriteArrayList<>(),trace=new CopyOnWriteArrayList<>();
    final AtomicReference<Thread> executionThread=new AtomicReference<>();
    final Set<String> errors=ConcurrentHashMap.newKeySet();
    final CountDownLatch closeEntered=new CountDownLatch(1),closeRelease=new CountDownLatch(1);
    final AtomicReference<Thread> cancelThread=new AtomicReference<>();
    volatile boolean blockClose;
    volatile int updateCount=1;
    final SqlRunner runner;
    final SqlDialect dialect;
    ShellSqlScriptDialogJdbcProbe(DbType type) {
        errors.add(FIRST);
        dialect=type==DbType.ORACLE ? new OracleSqlDialect() : new PgSqlDialect();
        runner=type==DbType.ORACLE ? new OracleSqlRunner(dialect) : new PgSqlRunner(dialect);
    }
    DatabaseProvider provider(DbType type) {
        var factory=new ConnectionFactory() {
            public void ensureDriverLoaded() {}
            public String test(ConnConfig ignored){throw new AssertionError("no live connection test");}
            public Connection open(ConnConfig config){assertEquals("synthetic.invalid",config.host());return ShellSqlScriptDialogJdbcProbe.this.open();}
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
        int opened=opens.incrementAndGet();var closed=new AtomicBoolean();var autoCommit=new AtomicBoolean(true);trace.add("session-open:"+opened);
        return ShellSqlJdbcProbe.proxy(Connection.class,(p,m,a)->switch(m.getName()) {
            case "setReadOnly" -> {assertFalse((boolean)a[0]);yield null;}
            case "setAutoCommit" -> {autoCommit.set((boolean)a[0]);trace.add("autoCommit:"+a[0]);yield null;}
            case "getAutoCommit" -> autoCommit.get();
            case "createStatement" -> statement(closed,autoCommit,opened);
            case "commit" -> {commits.incrementAndGet();trace.add("commit");yield null;}
            case "rollback" -> {rollbacks.incrementAndGet();trace.add("rollback");yield null;}
            case "close" -> {
                assertTrue(closed.compareAndSet(false,true),"each connection handle closes exactly once");int count=closes.incrementAndGet();trace.add("session-close-enter:owner="+opened);if(blockClose && count==1){cancelThread.set(Thread.currentThread());closeEntered.countDown();assertTrue(closeRelease.await(10,TimeUnit.SECONDS),"fixture releases physical cancel close");}
                trace.add("session-close:"+count+":owner="+opened);connectionClosed.countDown();yield null;
            }
            case "isClosed" -> closed.get();
            case "isValid" -> !closed.get();
            default -> throw new AssertionError("unexpected connection method: "+m.getName());
        });
    }
    Connection globalCachedCloseOnly() {
        return ShellSqlJdbcProbe.proxy(Connection.class,(p,m,a)-> {
            if(m.getName().equals("close")){assertEquals(1,globalCloses.incrementAndGet());trace.add("global-close");return null;}
            throw new AssertionError("cached synthetic resource permits close only");
        });
    }
    private Statement statement(AtomicBoolean connectionClosed,AtomicBoolean autoCommit,int owner) {
        var statementClosed=new AtomicBoolean();
        return ShellSqlJdbcProbe.proxy(Statement.class,(p,m,a)->switch(m.getName()) {
            case "setQueryTimeout","setMaxRows","setLargeMaxRows" -> null;
            case "execute" -> {
                assertFalse(connectionClosed.get());assertTrue(autoCommit.get());String sql=((String)a[0]).trim();executed.add(sql);executionThread.set(Thread.currentThread());trace.add("execute:"+sql);
                if(sql.equals(FIRST)){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS),"fixture must release the synthetic first failure");}
                if(errors.contains(sql)){trace.add("sql-throw:"+sql);throw new SQLException("synthetic SQL failure: "+sql);}
                assertTrue(sql.equals(SECOND)||sql.equals(THIRD));yield false;
            }
            case "getUpdateCount" -> updateCount;
            case "cancel" -> {cancelCalls.incrementAndGet();trace.add("statement-cancel");yield null;}
            case "close" -> {assertTrue(statementClosed.compareAndSet(false,true));statementCloses.incrementAndGet();trace.add("statement-close:owner="+owner);yield null;}
            case "isClosed" -> statementClosed.get();
            default -> throw new AssertionError("unexpected statement method: "+m.getName());
        });
    }
}
