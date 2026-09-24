package com.datacube.migration;

import com.datacube.core.MigrationLogger;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Target catalog statistics only. This does not compare source and target data. */
public class PgVerifier {
    public record Statistics(long tablesAndViews, Long estimatedLiveRows, long sequences, long routines) { }

    private final MigrationLogger logger;
    private final MigrationCancellation cancellation;
    private final MigrationConnections connections;

    public PgVerifier(MigrationLogger logger) { this(logger, new MigrationCancellation()); }

    public PgVerifier(MigrationLogger logger, MigrationCancellation cancellation) {
        this(logger, cancellation, MigrationConnections::connect);
    }

    public PgVerifier(MigrationLogger logger, MigrationCancellation cancellation, MigrationConnections connections) {
        this.logger = Objects.requireNonNull(logger);
        this.cancellation = Objects.requireNonNull(cancellation);
        this.connections = Objects.requireNonNull(connections);
    }

    public Statistics verify(String pgUrl, String pgUser, String pgPass, String schema) throws SQLException {
        if (schema == null || schema.isBlank()) throw new IllegalArgumentException("Schema is required");
        cancellation.checkCancelled();
        logger.logSection("目标端统计（非迁移一致性验证）");
        Connection conn = null;
        try {
            conn = cancellation.register(connections.open(pgUrl, pgUser, pgPass));
            conn.setReadOnly(true);
            long tables = count(conn, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?", schema);
            Long rows = scalar(conn, "SELECT SUM(n_live_tup) FROM pg_stat_user_tables WHERE schemaname = ?", schema);
            long sequences = count(conn, "SELECT COUNT(*) FROM information_schema.sequences WHERE sequence_schema = ?", schema);
            long routines = count(conn, "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = ?", schema);
            cancellation.checkCancelled();
            Statistics stats = new Statistics(tables, rows, sequences, routines);
            Map<String, Object> display = new LinkedHashMap<>();
            display.put("可见表/视图数量", tables);
            display.put("估算活跃行数（可过期）", rows == null ? "未知 / 尚无统计" : rows);
            display.put("可见序列数量", sequences);
            display.put("可见例程数量", routines);
            display.put("覆盖范围", "仅目标端可见对象；未读取源端，不能证明迁移数据正确");
            logger.logSummary("目标端统计", display);
            return stats;
        } finally {
            cancellation.release(conn);
        }
    }

    private long count(Connection conn, String sql, String schema) throws SQLException {
        Long value = scalar(conn, sql, schema);
        if (value == null) throw new SQLException("Missing catalog count", "DC001");
        return value;
    }

    private Long scalar(Connection conn, String sql, String schema) throws SQLException {
        cancellation.checkCancelled();
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setQueryTimeout(30);
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing catalog result", "DC001");
                long value = result.getLong(1);
                Long answer = result.wasNull() ? null : value;
                cancellation.checkCancelled();
                return answer;
            }
        }
    }
}
