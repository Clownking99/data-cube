package com.datacube.service;

import com.datacube.config.DraftTestCipher;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.SqlRunner;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** Provider and session-construction counters; every network path is rejected. */
public final class DraftConnectionProbe {
    public final AtomicInteger providers = new AtomicInteger();
    public final AtomicInteger sessions = new AtomicInteger();
    public final AtomicInteger metadata = new AtomicInteger();
    public final AtomicInteger network = new AtomicInteger();
    public final ConnectionManager manager;

    public DraftConnectionProbe() {
        SqlRunner runner = (SqlRunner) Proxy.newProxyInstance(SqlRunner.class.getClassLoader(),
                new Class<?>[]{SqlRunner.class}, (proxy, method, args) -> {
                    throw new AssertionError("No SQL execution in offline factory tests");
                });
        DatabaseProvider provider = (DatabaseProvider) Proxy.newProxyInstance(
                DatabaseProvider.class.getClassLoader(), new Class<?>[]{DatabaseProvider.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sqlRunner")) {
                        sessions.incrementAndGet();
                        return runner;
                    }
                    if (method.getName().equals("dialect") || method.getName().equals("metadataReader")) {
                        metadata.incrementAndGet();
                        throw new IllegalStateException("Synthetic metadata access rejected");
                    }
                    if (method.getName().equals("connectionFactory")) {
                        network.incrementAndGet();
                        throw new IllegalStateException("Synthetic network access rejected");
                    }
                    throw new AssertionError("Unexpected provider method: " + method.getName());
                });
        manager = new ConnectionManager(DraftTestCipher.create(), type -> {
            providers.incrementAndGet();
            return provider;
        });
    }
}
