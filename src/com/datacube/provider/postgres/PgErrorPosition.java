package com.datacube.provider.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import org.postgresql.util.PSQLException;

/** PostgreSQL protocol P, never internal p/q, context strings, or source-code line numbers. */
final class PgErrorPosition {
    private PgErrorPosition() {}
    static int originalPosition(SQLException error, Connection connection, String sql) {
        if (!(error instanceof PSQLException pg) || pg.getServerErrorMessage() == null) return 0;
        int position = pg.getServerErrorMessage().getPosition();
        if (position <= 0) return 0;
        // JDBC escape processing can rewrite offsets. No mapping is safer than a guessed one.
        try { if (!sql.equals(connection.nativeSQL(sql))) return 0; }
        catch (SQLException unsupported) { return 0; }
        return position <= sql.codePointCount(0, sql.length()) + 1 ? position : 0;
    }
}
