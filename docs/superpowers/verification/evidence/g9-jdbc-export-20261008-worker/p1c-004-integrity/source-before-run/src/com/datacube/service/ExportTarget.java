package com.datacube.service;

import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.model.ConnConfig;
import java.util.Objects;

/** Immutable relational read-export identity, independent of write admission. */
public final class ExportTarget {
    private final ConnectionManager authority;
    private final ConnConfig config;
    private final DatabaseProvider provider;
    private final long version;
    ExportTarget(ConnectionManager authority, ConnConfig config, DatabaseProvider provider, long version) {
        this.authority=authority; this.config=Objects.requireNonNull(config); this.provider=Objects.requireNonNull(provider); this.version=version;
    }
    public ConnConfig config(){return config;}
    public DatabaseProvider provider(){return provider;}
    public long version(){return version;}
    public boolean belongsTo(ConnectionManager manager){return authority==manager;}
    public boolean current(){return authority.exportCurrent(this);}
    public AutoCloseable whenChanged(Runnable intent){return authority.subscribeExport(this,intent);}
    @Override public String toString(){return "ExportTarget[redacted]";}
}
