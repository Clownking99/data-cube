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

/** Synthetic JDBC handles retain independent owner/state counters; production PG/Oracle runners are unchanged. */
final class ShellSqlCancelIdentityJdbcProbe {
    static final String FIRST="UPDATE synthetic_items SET value = 73 WHERE id = 1";
    static final String SECOND="UPDATE synthetic_items SET value = 97 WHERE id = 2";
    final CountDownLatch firstEntered=new CountDownLatch(1),firstRelease=new CountDownLatch(1),firstClosed=new CountDownLatch(1);
    final CountDownLatch secondEntered=new CountDownLatch(1),secondRelease=new CountDownLatch(1);
    final CountDownLatch cancelEntered=new CountDownLatch(1),cancelRelease=new CountDownLatch(1);
    final AtomicReference<Thread> firstThread=new AtomicReference<>(),secondThread=new AtomicReference<>(),cancelThread=new AtomicReference<>();
    final List<ConnectionHandle> connections=new CopyOnWriteArrayList<>();
    final List<StatementHandle> statements=new CopyOnWriteArrayList<>();
    final List<String> executed=new CopyOnWriteArrayList<>(),trace=new CopyOnWriteArrayList<>();
    final AtomicInteger globalCloses=new AtomicInteger();
    final SqlRunner runner;final SqlDialect dialect;final boolean lateCancelFailure;
    ShellSqlCancelIdentityJdbcProbe(DbType type,boolean lateCancelFailure) {
        this.lateCancelFailure=lateCancelFailure;
        dialect=type==DbType.ORACLE ? new OracleSqlDialect() : new PgSqlDialect();
        runner=type==DbType.ORACLE ? new OracleSqlRunner(dialect) : new PgSqlRunner(dialect);
    }
    DatabaseProvider provider(DbType type) {
        var factory=new ConnectionFactory() {
            public void ensureDriverLoaded() {}
            public String test(ConnConfig ignored){throw new AssertionError("no live connection test");}
            public Connection open(ConnConfig config){assertEquals("synthetic.invalid",config.host());return ShellSqlCancelIdentityJdbcProbe.this.open();}
        };
        return ShellSqlJdbcProbe.proxy(DatabaseProvider.class,(p,m,a)->switch(m.getName()) {
            case "type" -> type;case "connectionFactory" -> factory;case "dialect" -> dialect;case "sqlRunner" -> runner;
            case "metadataReader" -> ShellSqlJdbcProbe.proxy(MetadataReader.class,(mp,mm,ma)-> {
                if(List.class.isAssignableFrom(mm.getReturnType()))return List.of();
                throw new AssertionError("unexpected metadata method: "+mm.getName());
            });
            case "resultFilterSqlRenderer","schemaDiffCapability" -> Optional.empty();
            default -> throw new AssertionError("unexpected provider method: "+m.getName());
        });
    }
    Connection open() {
        var owner=new ConnectionHandle(connections.size()+1);connections.add(owner);trace.add("connection-open:"+owner.id);
        owner.jdbc=ShellSqlJdbcProbe.proxy(Connection.class,(p,m,a)->switch(m.getName()) {
            case "setReadOnly" -> {assertFalse((boolean)a[0]);yield null;}
            case "setAutoCommit" -> {owner.autoCommit.set((boolean)a[0]);yield null;}
            case "getAutoCommit" -> owner.autoCommit.get();
            case "createStatement" -> statement(owner);
            case "commit","rollback" -> throw new AssertionError("AUTO_COMMIT fixture must not explicitly commit/rollback");
            case "close" -> {assertTrue(owner.closed.compareAndSet(false,true),"connection owner closes once: "+owner.id);assertEquals(1,owner.closes.incrementAndGet());trace.add("connection-close:"+owner.id);yield null;}
            case "isClosed" -> owner.closed.get();case "isValid" -> !owner.closed.get();
            default -> throw new AssertionError("unexpected connection method: "+m.getName());
        });return owner.jdbc;
    }
    Statement statement(ConnectionHandle owner) {
        var handle=new StatementHandle(statements.size()+1,owner);statements.add(handle);
        handle.jdbc=ShellSqlJdbcProbe.proxy(Statement.class,(p,m,a)->switch(m.getName()) {
            case "setQueryTimeout","setMaxRows","setLargeMaxRows" -> null;
            case "execute" -> {
                assertFalse(owner.closed.get());assertTrue(owner.autoCommit.get());String sql=((String)a[0]).trim();handle.sql=sql;executed.add(sql);trace.add("execute-enter:statement="+handle.id+":connection="+owner.id+":"+sql);
                if(FIRST.equals(sql)){firstThread.set(Thread.currentThread());firstEntered.countDown();assertTrue(firstRelease.await(10,TimeUnit.SECONDS),"bounded first JDBC execute");}
                else {assertEquals(SECOND,sql);secondThread.set(Thread.currentThread());secondEntered.countDown();assertTrue(secondRelease.await(10,TimeUnit.SECONDS),"bounded second JDBC execute");}
                trace.add("execute-return:statement="+handle.id);yield false;
            }
            case "getUpdateCount" -> FIRST.equals(handle.sql) ? 73 : 97;
            case "cancel" -> {
                assertEquals(1,handle.cancels.incrementAndGet(),"cancel delivered once per Statement owner");trace.add("cancel-enter:statement="+handle.id+":connection="+owner.id);
                if(lateCancelFailure && FIRST.equals(handle.sql)) {
                    cancelThread.set(Thread.currentThread());cancelEntered.countDown();assertTrue(cancelRelease.await(10,TimeUnit.SECONDS),"bounded old physical cancel");trace.add("cancel-late-throw:statement="+handle.id);throw new SQLException("synthetic late old cancellation failure");
                }
                trace.add("cancel-return:statement="+handle.id);yield null;
            }
            case "close" -> {assertTrue(handle.closed.compareAndSet(false,true),"Statement owner closes once: "+handle.id);assertEquals(1,handle.closes.incrementAndGet());trace.add("statement-close:"+handle.id);if(FIRST.equals(handle.sql))firstClosed.countDown();yield null;}
            case "isClosed" -> handle.closed.get();default -> throw new AssertionError("unexpected Statement method: "+m.getName());
        });return handle.jdbc;
    }
    Connection globalCachedCloseOnly() {
        return ShellSqlJdbcProbe.proxy(Connection.class,(p,m,a)-> {if(m.getName().equals("close")){assertEquals(1,globalCloses.incrementAndGet());trace.add("global-close");return null;}throw new AssertionError("global synthetic resource permits close only");});
    }
    StatementHandle second() {return statements.stream().filter(s->SECOND.equals(s.sql)).findFirst().orElseThrow();}
    void releaseAll(){firstRelease.countDown();secondRelease.countDown();cancelRelease.countDown();}
    String handles(){return "connections="+connections.stream().map(c->c.id+":closed="+c.closed+":closes="+c.closes).toList()+" statements="+statements.stream().map(s->s.id+":owner="+s.owner.id+":sql="+s.sql+":cancel="+s.cancels+":close="+s.closes).toList();}
    static final class ConnectionHandle {final int id;final AtomicBoolean closed=new AtomicBoolean(),autoCommit=new AtomicBoolean(true);final AtomicInteger closes=new AtomicInteger();Connection jdbc;ConnectionHandle(int id){this.id=id;}}
    static final class StatementHandle {final int id;final ConnectionHandle owner;final AtomicBoolean closed=new AtomicBoolean();final AtomicInteger cancels=new AtomicInteger(),closes=new AtomicInteger();volatile String sql;Statement jdbc;StatementHandle(int id,ConnectionHandle owner){this.id=id;this.owner=owner;}}
}
