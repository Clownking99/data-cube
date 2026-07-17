package com.datacube.migration;

import com.datacube.core.MigrationLogger;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class PgVerifier {

    private final MigrationLogger logger;

    public PgVerifier(MigrationLogger logger) {
        this.logger = logger;
    }

    public void verify(String pgUrl, String pgUser, String pgPass, String schema) throws Exception {
        logger.logSection("验证：" + schema);

        try (Connection conn = DriverManager.getConnection(pgUrl + "?currentSchema=" + schema, pgUser, pgPass)) {
            Statement s = conn.createStatement();

            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + schema + "'");
            rs.next(); int tables = rs.getInt(1);

            rs = s.executeQuery("SELECT SUM(n_live_tup) FROM pg_stat_user_tables WHERE schemaname='" + schema + "'");
            rs.next(); long rows = rs.getLong(1);

            rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.sequences WHERE sequence_schema='" + schema + "'");
            rs.next(); int seqs = rs.getInt(1);

            rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='" + schema + "'");
            rs.next(); int funcs = rs.getInt(1);

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("表数量", tables);
            stats.put("总行数", (int) rows);
            stats.put("序列数量", seqs);
            stats.put("函数数量", funcs);
            logger.logSummary("验证结果", stats);

            rs = s.executeQuery("SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE schemaname='" + schema + "' ORDER BY n_live_tup DESC LIMIT 10");
            System.out.println("  数据量 TOP 10:");
            logger.logToFile("数据量 TOP 10:");
            while (rs.next()) {
                String name = rs.getString(1);
                long cnt = rs.getLong(2);
                System.out.println("    " + String.format("%-40s", name) + String.format("%,d", cnt) + " 行");
                logger.logToFile("  " + name + ": " + cnt + " 行");
            }
            System.out.println();
        }
    }
}
