package com.datacube.service;

import com.datacube.spi.model.DbType;

/** Synthetic JDBC boundary shared with FX safety integration tests. */
public final class WriteSafetyFxProbe implements AutoCloseable {
    private final RelationalWriteSafetyTest.Fixture fixture;
    public WriteSafetyFxProbe(DbType type, boolean readOnly, String environment) {
        fixture = new RelationalWriteSafetyTest.Fixture(type, readOnly, environment);
    }
    public ConnectionManager manager() { return fixture.manager; }
    public int writes() { return fixture.writes.get(); }
    public int opens() { return fixture.opens.get(); }
    @Override public void close() { fixture.manager.closeAll(); }
}
