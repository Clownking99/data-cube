import com.datacube.service.*;

import com.datacube.config.CredentialCipher;
import com.datacube.provider.jdbc.JdbcDataEditor;

import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory provider shared by service and FX behavior tests. No live database or stored profile. */
public final class SolGridSaveBoundary implements AutoCloseable {
    public final SolRowJdbcBoundary jdbc = new SolRowJdbcBoundary();
    public final AtomicInteger resolutions = new AtomicInteger(), reads = new AtomicInteger();
    public final ConnectionManager manager;
    public final DataEditService edit;
    public final ConnConfig config;
    public volatile long offset;
    public volatile String filter;
    public volatile boolean failRead;
    public Runnable onOpen = () -> {};
    public SolGridSaveBoundary(DbType type, boolean readOnly, String environment) {
        config = new ConnConfig("target", "synthetic", type, "synthetic.invalid", 1,
                "synthetic", "synthetic", "", Map.of("readOnly", "" + readOnly, "environment", environment));
        ConnectionFactory factory = new ConnectionFactory() {
            public void ensureDriverLoaded() { }
            public String test(ConnConfig ignored) { throw new AssertionError("no live test"); }
            public Connection open(ConnConfig ignored) { Connection result = jdbc.open(); onOpen.run(); return result; }
        };
        DatabaseProvider provider = SolRowJdbcBoundary.proxy(DatabaseProvider.class, (p, m, a) -> switch (m.getName()) {
            case "type" -> type;
            case "connectionFactory" -> factory;
            case "dialect" -> jdbc.dialect;
            case "dataEditor" -> new JdbcDataEditor((Connection) a[0], jdbc.dialect);
            case "dataAccessor" -> SolRowJdbcBoundary.proxy(DataAccessor.class, (dp, dm, da) -> {
                if (!dm.getName().equals("page")) throw new AssertionError(dm.getName());
                reads.incrementAndGet(); offset = (long) da[1]; filter = (String) da[4];
                if (failRead) throw new java.sql.SQLException("synthetic read failed");
                return new PagedResult(List.of("id", "name"),
                        List.of(List.of(1, "before"), List.of(2, "second")), offset == 0);
            });
            default -> throw new AssertionError(m.getName());
        });
        try {
            var constructor=ConnectionManager.class.getDeclaredConstructor(CredentialCipher.class,java.util.function.Function.class);
            constructor.setAccessible(true);
            java.util.function.Function<DbType,DatabaseProvider> resolver=ignored->{resolutions.incrementAndGet();return provider;};
            manager=constructor.newInstance(new CredentialCipher(),resolver);
        }catch(Exception e){throw new RuntimeException(e);}
        manager.register(config);
        edit = new DataEditService(manager);
    }
    public void tighten() {
        manager.register(new ConnConfig(config.id(), config.name(), config.type(), config.host(), config.port(),
                config.database(), config.username(), "", Map.of("readOnly", "true", "environment", "PRODUCTION")));
    }
    @Override public void close() { manager.closeAll(); }
}
