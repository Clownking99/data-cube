package com.datacube.fx;

import com.datacube.provider.oracle.OracleSqlDialect;
import com.datacube.provider.oracle.OracleSqlRunner;
import com.datacube.provider.postgres.PgSqlDialect;
import com.datacube.provider.postgres.PgSqlRunner;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC-only synthetic physical ownership probe; dialect and SQL runner remain production classes. */
final class ShellSqlJdbcProbe {
    static final String FIRST="UPDATE synthetic_items SET value = 101 WHERE id = 1";
    static final String BLOCKED="UPDATE synthetic_items SET value = 202 WHERE id = 2";
    static final String THIRD="UPDATE synthetic_items SET value = 303 WHERE id = 3";
    static final String QUEUED="UPDATE synthetic_items SET value = 404 WHERE id = 4";
    final CountDownLatch entered=new CountDownLatch(1),cancelled=new CountDownLatch(1),release=new CountDownLatch(1);
    final CountDownLatch rollbackEntered=new CountDownLatch(1),rollbackRelease=new CountDownLatch(1);
    final AtomicInteger opens=new AtomicInteger(),commits=new AtomicInteger(),rollbacks=new AtomicInteger(),closes=new AtomicInteger();
    final AtomicInteger globalCloses=new AtomicInteger(),pendingWrites=new AtomicInteger(),cancelCalls=new AtomicInteger();
    final List<String> executed=new CopyOnWriteArrayList<>(),trace=new CopyOnWriteArrayList<>();
    final List<AtomicInteger> statementCloses=new CopyOnWriteArrayList<>();
    final AtomicReference<Thread> executionThread=new AtomicReference<>();
    volatile boolean autoCommit=true,closed;
    final SqlRunner runner;
    final SqlDialect dialect;
    ShellSqlJdbcProbe(DbType type) {
        dialect=type==DbType.ORACLE ? new OracleSqlDialect() : new PgSqlDialect();
        runner=type==DbType.ORACLE ? new OracleSqlRunner(dialect) : new PgSqlRunner(dialect);
    }
    DatabaseProvider provider(DbType type) {
        var factory=new ConnectionFactory() {
            public void ensureDriverLoaded() {}
            public String test(ConnConfig ignored) {throw new AssertionError("no live database test");}
            public Connection open(ConnConfig cfg) {assertEquals("synthetic.invalid",cfg.host());return open();}
            private Connection open() {return ShellSqlJdbcProbe.this.open();}
        };
        return proxy(DatabaseProvider.class,(p,m,a)->switch(m.getName()) {
            case "type" -> type;
            case "connectionFactory" -> factory;
            case "dialect" -> dialect;
            case "sqlRunner" -> runner;
            case "metadataReader" -> proxy(MetadataReader.class,(mp,mm,ma)-> {
                if(List.class.isAssignableFrom(mm.getReturnType()))return List.of();
                throw new AssertionError("unexpected metadata method: "+mm.getName());
            });
            case "resultFilterSqlRenderer","schemaDiffCapability" -> Optional.empty();
            default -> throw new AssertionError("unexpected provider method: "+m.getName());
        });
    }
    Connection open() {
        assertEquals(1,opens.incrementAndGet(),"SQL editor owns one dedicated JDBC connection");trace.add("session-open");
        return proxy(Connection.class,(p,m,a)->switch(m.getName()) {
            case "setReadOnly" -> {assertFalse((boolean)a[0]);trace.add("readOnly:false");yield null;}
            case "setAutoCommit" -> {autoCommit=(boolean)a[0];trace.add("autoCommit:"+a[0]);yield null;}
            case "getAutoCommit" -> autoCommit;
            case "createStatement" -> statement();
            case "commit" -> {commits.incrementAndGet();trace.add("COMMIT");throw new AssertionError("implicit commit is forbidden");}
            case "rollback" -> {
                assertEquals(1,rollbacks.incrementAndGet());trace.add("rollback-enter");rollbackEntered.countDown();
                assertTrue(rollbackRelease.await(5,TimeUnit.SECONDS),"bounded synthetic rollback observation barrier");
                trace.add("rollback-complete");pendingWrites.set(0);yield null;
            }
            case "close" -> {assertEquals(1,closes.incrementAndGet());closed=true;trace.add("session-close");yield null;}
            case "isClosed" -> closed;
            case "isValid" -> !closed;
            default -> throw new AssertionError("unexpected session JDBC method: "+m.getName());
        });
    }
    Connection globalCachedCloseOnly() {
        return proxy(Connection.class,(p,m,a)-> {
            if(m.getName().equals("close")){assertEquals(1,globalCloses.incrementAndGet());trace.add("global-close");return null;}
            throw new AssertionError("synthetic cached resource permits close only: "+m.getName());
        });
    }
    private Statement statement() {
        var count=new AtomicInteger();statementCloses.add(count);var sql=new AtomicReference<String>();
        return proxy(Statement.class,(p,m,a)->switch(m.getName()) {
            case "setQueryTimeout","setMaxRows","setLargeMaxRows" -> {trace.add(m.getName()+":"+a[0]);yield null;}
            case "execute" -> {
                assertFalse(closed);assertFalse(autoCommit,"all synthetic DML must remain uncommitted");
                String text=((String)a[0]).trim();sql.set(text);executed.add(text);trace.add("execute:"+text);
                assertTrue(text.equals(FIRST)||text.equals(BLOCKED),"cancelled/queued SQL must never start: "+text);
                pendingWrites.incrementAndGet();
                if(text.equals(BLOCKED)) {
                    executionThread.set(Thread.currentThread());entered.countDown();
                    assertTrue(release.await(25,TimeUnit.SECONDS),"bounded synthetic execute barrier");
                    assertEquals(1,cancelCalls.get(),"cancel does not physically end this mock JDBC call");
                    trace.add("execute-return:73");
                }else trace.add("execute-return:1");
                yield false;
            }
            case "getUpdateCount" -> {int value=BLOCKED.equals(sql.get()) ? 73 : 1;trace.add("updateCount:"+value);yield value;}
            case "cancel" -> {assertEquals(BLOCKED,sql.get());assertEquals(1,cancelCalls.incrementAndGet());trace.add("statement-cancel");cancelled.countDown();yield null;}
            case "close" -> {assertEquals(1,count.incrementAndGet());trace.add("statement-close:"+sql.get());yield null;}
            case "isClosed" -> count.get()!=0;
            default -> throw new AssertionError("unexpected statement JDBC method: "+m.getName());
        });
    }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)-> {
            if(m.getDeclaringClass()==Object.class)return switch(m.getName()) {
                case "toString" -> "synthetic "+type.getSimpleName();case "hashCode" -> System.identityHashCode(p);case "equals" -> p==a[0];default -> throw new AssertionError(m.getName());
            };
            return handler.invoke(p,m,a);
        });
    }
}
