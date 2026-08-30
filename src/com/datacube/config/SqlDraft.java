package com.datacube.config;

import com.datacube.spi.model.DbType;
import java.util.UUID;

/** Exact local editor text and a saved connection identity, never credentials. */
public record SqlDraft(UUID id, long modifiedAt, String connectionId,
                       DbType connectionType, String connectionName,
                       String schema, String sql) {
    public SqlDraft {
        if (id == null || modifiedAt < 0 || sql == null) {
            throw new IllegalArgumentException("Invalid SQL draft value");
        }
        if ((connectionId == null) != (connectionType == null)
                || (connectionId != null && connectionId.isBlank())
                || (connectionType != null && connectionType != DbType.POSTGRESQL
                    && connectionType != DbType.ORACLE)) {
            throw new IllegalArgumentException("Invalid SQL draft identity");
        }
    }

    @Override
    public String toString() {
        return "SqlDraft[id=" + id + ", modifiedAt=" + modifiedAt + ", sqlChars=" + sql.length() + "]";
    }
}
