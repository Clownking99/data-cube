package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.function.Function;

/** Display/storage intent only: no credentials and no ownership of a live connection. */
record SqlDraftRecoveryIntent(String connectionId, DbType connectionType, String connectionName) {
    static SqlDraftRecoveryIntent from(ConnConfig config) {
        return config == null ? new SqlDraftRecoveryIntent(null, null, null)
                : new SqlDraftRecoveryIntent(config.id(), config.type(), config.name());
    }

    ConnConfig resolve(Function<String, ConnConfig> lookup) {
        if (connectionId == null || connectionType == null) return null;
        ConnConfig config = lookup.apply(connectionId);
        return config != null && connectionId.equals(config.id()) && connectionType == config.type()
                && config.type() != DbType.REDIS ? config : null;
    }

    @Override public String toString() { return "SqlDraftRecoveryIntent"; }
}
