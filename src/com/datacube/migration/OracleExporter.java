package com.datacube.migration;

import com.datacube.cli.ConsoleLogger;
import com.datacube.core.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class OracleExporter {

    private static final String BASE_DIR = "pg_migration";
    private static final int FETCH_SIZE = 2000;
    private static final int TABLE_TIMEOUT_SEC = 600;
    private static final int MAX_RETRY = 2;

    private final MigrationLogger logger;
    private int maxConcurrency = 20;
    private boolean convertBool = false;
    private Map<String, Map<String, String>> columnCommentsCache = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

    public OracleExporter(MigrationLogger logger) {
        this.logger = logger;
    }

    public void setMaxConcurrency(int concurrency) {
        if (concurrency < 1) concurrency = 1;
        if (concurrency > 100) concurrency = 100;
        this.maxConcurrency = concurrency;
    }
    public void setConvertBool(boolean convert) { this.convertBool = convert; }

    /** 请求取消导出（幂等，调用后下个检查点会中断） */
    public void cancel() { cancelled.set(true); }

    /** 重置取消标志（在重新调用 exportDDL/exportData 前需调用） */
    public void resetCancel() { cancelled.set(false); }

    public boolean isCancelled() { return cancelled.get(); }

    // ==================== DDL 导出 ====================

    public void exportDDL(Connection conn, String owner, String pgSchema) throws SQLException, IOException {
        cancelled.set(false);
        logger.logSection("导出 DDL：" + owner + " → " + pgSchema);

        String outputDir = BASE_DIR + "/" + pgSchema;
        new File(outputDir).mkdirs();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("序列",          exportSequences(conn, owner, outputDir));
        stats.put("表",            exportTables(conn, owner, outputDir));
        stats.put("索引",          exportIndexes(conn, owner, outputDir));
        stats.put("约束",          exportConstraints(conn, owner, outputDir));
        stats.put("存储过程/函数", exportFunctions(conn, owner, outputDir));
        stats.put("包",            exportPackages(conn, owner, outputDir));
        stats.put("触发器",        exportTriggers(conn, owner, outputDir));

        if (cancelled.get()) {
            logger.logWarn("DDL 导出已被取消");
        } else {
            logger.logOk("脚本已输出到 " + outputDir + "/");
        }
    }

    // ==================== 序列 ====================

    private int exportSequences(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/01_sequences.sql"));
        SqlUtils.header(w, "序列");

        String sql = "SELECT SEQUENCE_NAME, MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG, CACHE_SIZE " +
                "FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = ? ORDER BY SEQUENCE_NAME";

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    String name = rs.getString("SEQUENCE_NAME").toLowerCase();
                    w.println("CREATE SEQUENCE IF NOT EXISTS " + name);
                    w.println("    INCREMENT BY " + rs.getString("INCREMENT_BY"));
                    w.println("    MINVALUE " + rs.getString("MIN_VALUE"));
                    w.println("    MAXVALUE " + rs.getString("MAX_VALUE"));
                    w.println("    START WITH 1");
                    if ("Y".equals(rs.getString("CYCLE_FLAG"))) w.println("    CYCLE");
                    w.println("    CACHE " + rs.getString("CACHE_SIZE") + ";");
                    w.println();
                    w.println("CREATE OR REPLACE FUNCTION " + name + "_nextval()");
                    w.println("RETURNS BIGINT AS $$");
                    w.println("BEGIN");
                    w.println("    RETURN NEXTVAL('" + name + "');");
                    w.println("END;");
                    w.println("$$ LANGUAGE plpgsql;");
                    w.println();
                }
            }
        }
        w.close();
        logger.logOk("序列: " + count + " 个");
        return count;
    }

    // ==================== 表 ====================

    private int exportTables(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/02_tables.sql"));
        SqlUtils.header(w, "表结构");

        Map<String, String> tableComments = getTableComments(conn, owner);
        Map<String, Map<String, String>> colComments = getColumnComments(conn, owner);

        String sql = "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = ? " +
                "AND TABLE_NAME NOT LIKE 'MLOG$_%' ESCAPE '\\' " +
                "AND TABLE_NAME NOT LIKE 'RUPD$_%' ESCAPE '\\' " +
                "AND TABLE_NAME NOT LIKE 'DR$%' ESCAPE '\\' " +
                "ORDER BY TABLE_NAME";

        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
            }
        }

        int total = tables.size();
        for (int i = 0; i < total; i++) {
            writeTable(conn, owner, tables.get(i), w, tableComments, colComments);
            logger.logProgress("导出表结构", i + 1, total);
        }
        w.close();
        logger.logOk("表: " + total + " 个");
        return total;
    }

    private void writeTable(Connection conn, String owner, String table, PrintWriter w,
                            Map<String, String> tableComments,
                            Map<String, Map<String, String>> colComments) throws SQLException {
        String tc = tableComments.get(table);
        if (tc != null) w.println("-- " + tc);

        w.println("CREATE TABLE IF NOT EXISTS " + table.toLowerCase() + " (");

        String sql = "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, " +
                "NULLABLE, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";

        List<String> cols = new ArrayList<>();
        Map<String, String> cc = colComments.getOrDefault(table, new HashMap<>());

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName  = rs.getString("COLUMN_NAME");
                    String type     = rs.getString("DATA_TYPE");
                    int len         = rs.getInt("DATA_LENGTH");
                    int prec        = rs.getInt("DATA_PRECISION");
                    int scale       = rs.getInt("DATA_SCALE");
                    String nullable = rs.getString("NULLABLE");
                    String defVal   = rs.getString("DATA_DEFAULT");

                    StringBuilder col = new StringBuilder("    " + colName.toLowerCase() + " "
                            + TypeConverter.convertType(type, len, prec, scale));
                    if ("N".equals(nullable)) col.append(" NOT NULL");
                    if (defVal != null && !defVal.trim().isEmpty())
                        col.append(" DEFAULT ").append(TypeConverter.convertDefault(defVal.trim()));

                    String comment = cc.get(colName);
                    if (comment != null) col.append(" /* ").append(TypeConverter.escapeComment(comment)).append(" */");

                    cols.add(col.toString());
                }
            }
        }

        w.println(String.join(",\n", cols));
        w.println(");");
        w.println();

        if (tc != null) w.println("COMMENT ON TABLE " + table.toLowerCase() + " IS '" + SqlUtils.escapeSql(tc) + "';");
        for (Map.Entry<String, String> e : cc.entrySet()) {
            w.println("COMMENT ON COLUMN " + table.toLowerCase() + "." + e.getKey().toLowerCase()
                    + " IS '" + SqlUtils.escapeSql(e.getValue()) + "';");
        }
        w.println();
    }

    // ==================== 索引 ====================

    private int exportIndexes(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/03_indexes.sql"));
        SqlUtils.header(w, "索引");

        String sql = "SELECT INDEX_NAME, TABLE_NAME, UNIQUENESS FROM ALL_INDEXES " +
                "WHERE OWNER = ? ORDER BY INDEX_NAME";

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    String idx = rs.getString("INDEX_NAME").toLowerCase();
                    String tbl = rs.getString("TABLE_NAME").toLowerCase();
                    String uni = rs.getString("UNIQUENESS");
                    List<String> cols = indexColumns(conn, owner, rs.getString("INDEX_NAME"));

                    w.println(("UNIQUE".equals(uni) ? "CREATE UNIQUE INDEX IF NOT EXISTS " : "CREATE INDEX IF NOT EXISTS ")
                            + idx + " ON " + tbl + " (" + SqlUtils.joinLower(cols) + ");");
                    w.println();
                }
            }
        }
        w.close();
        logger.logOk("索引: " + count + " 个");
        return count;
    }

    // ==================== 约束 ====================

    private int exportConstraints(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/04_constraints.sql"));
        SqlUtils.header(w, "约束");

        String sql = "SELECT CONSTRAINT_NAME, TABLE_NAME, CONSTRAINT_TYPE FROM ALL_CONSTRAINTS " +
                "WHERE OWNER = ? AND CONSTRAINT_TYPE IN ('P','U') ORDER BY CONSTRAINT_NAME";

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    String cname = rs.getString("CONSTRAINT_NAME").toLowerCase();
                    String tname = rs.getString("TABLE_NAME").toLowerCase();
                    String type  = rs.getString("CONSTRAINT_TYPE");
                    List<String> cols = constraintColumns(conn, owner, rs.getString("CONSTRAINT_NAME"));

                    w.println("ALTER TABLE " + tname);
                    w.println("    ADD CONSTRAINT " + cname + " " +
                            ("P".equals(type) ? "PRIMARY KEY" : "UNIQUE") + " (" + SqlUtils.joinLower(cols) + ");");
                    w.println();
                }
            }
        }
        w.close();
        logger.logOk("约束: " + count + " 个");
        return count;
    }

    // ==================== 函数 ====================

    private int exportFunctions(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/05_functions.sql"));
        SqlUtils.header(w, "存储过程/函数");

        String sql = "SELECT OBJECT_NAME, OBJECT_TYPE FROM ALL_OBJECTS " +
                "WHERE OWNER = ? AND OBJECT_TYPE IN ('PROCEDURE','FUNCTION') AND STATUS = 'VALID' ORDER BY OBJECT_NAME";

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    writeSource(conn, owner, rs.getString("OBJECT_NAME"), rs.getString("OBJECT_TYPE"), w);
                }
            }
        }
        w.close();
        logger.logOk("存储过程/函数: " + count + " 个");
        return count;
    }

    // ==================== 包 ====================

    private int exportPackages(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/06_packages.sql"));
        SqlUtils.header(w, "包");

        String sql = "SELECT OBJECT_NAME FROM ALL_OBJECTS " +
                "WHERE OWNER = ? AND OBJECT_TYPE = 'PACKAGE' AND STATUS = 'VALID' ORDER BY OBJECT_NAME";

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    writePackage(conn, owner, rs.getString("OBJECT_NAME"), w);
                }
            }
        }
        w.close();
        logger.logOk("包: " + count + " 个");
        return count;
    }

    // ==================== 触发器 ====================

    private int exportTriggers(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/07_triggers.sql"));
        SqlUtils.header(w, "触发器");

        String sql = "SELECT TRIGGER_NAME, TABLE_NAME, TRIGGER_TYPE, TRIGGERING_EVENT FROM ALL_TRIGGERS " +
                "WHERE OWNER = ? AND STATUS = 'ENABLED' ORDER BY TRIGGER_NAME";

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    String tname  = rs.getString("TRIGGER_NAME").toLowerCase();
                    String tbl    = rs.getString("TABLE_NAME").toLowerCase();
                    String timing = rs.getString("TRIGGER_TYPE").contains("BEFORE") ? "BEFORE" : "AFTER";
                    String event  = rs.getString("TRIGGERING_EVENT");

                    w.println("-- " + tname + " -> " + tbl);
                    w.println("CREATE OR REPLACE FUNCTION " + tname + "_func() RETURNS TRIGGER AS $$");
                    w.println("BEGIN");
                    w.println("    -- TODO: 转换 :NEW -> NEW, :OLD -> OLD");
                    w.println("    RETURN NEW;");
                    w.println("END;");
                    w.println("$$ LANGUAGE plpgsql;");
                    w.println();
                    w.println("CREATE TRIGGER " + tname);
                    w.println("    " + timing + " " + event + " ON " + tbl);
                    w.println("    FOR EACH ROW EXECUTE FUNCTION " + tname + "_func();");
                    w.println();
                }
            }
        }
        w.close();
        logger.logOk("触发器: " + count + " 个");
        return count;
    }

    // ==================== 数据导出（虚拟线程） ====================

    public void exportData(Connection conn, String oraUrl, String oraUser, String oraPass, String pgSchema) throws SQLException, IOException {
        cancelled.set(false);
        logger.logSection("导出数据：" + oraUser + "（虚拟线程, 并发上限 " + maxConcurrency + ", 超时 " + TABLE_TIMEOUT_SEC + "s/表）");

        String dataDir = BASE_DIR + "/" + pgSchema.toLowerCase() + "/data";
        new File(dataDir).mkdirs();

        columnCommentsCache = getColumnComments(conn, oraUser);

        String sql = "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = ? " +
                "AND TABLE_NAME NOT LIKE 'MLOG$_%' ESCAPE '\\' " +
                "AND TABLE_NAME NOT LIKE 'RUPD$_%' ESCAPE '\\' " +
                "AND TABLE_NAME NOT LIKE 'DR$%' ESCAPE '\\' " +
                "ORDER BY TABLE_NAME";

        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, oraUser);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
            }
        }

        int total = tables.size();
        logger.logInfo("共 " + total + " 张表，启动虚拟线程并行导出...");

        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger empty = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        AtomicLong totalRows = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicInteger done = new AtomicInteger(0);

        Semaphore semaphore = new Semaphore(maxConcurrency);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();

        for (String table : tables) {
            if (cancelled.get()) break;
            futures.add(pool.submit(() -> {
                semaphore.acquireUninterruptibly();
                try {
                    if (cancelled.get()) return;
                    boolean success = false;
                    for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                        if (cancelled.get()) break;
                        try (Connection threadConn = DriverManager.getConnection(oraUrl, oraUser, oraPass)) {
                            synchronized (logger) {
                                logger.logInfo(">> 导出: " + table + (attempt > 1 ? " (重试 " + attempt + ")" : ""));
                            }

                            long[] result = exportTableData(threadConn, oraUser, table, dataDir);
                            if (result[0] > 0) {
                                ok.incrementAndGet();
                                totalRows.addAndGet(result[0]);
                                totalBytes.addAndGet(result[1]);
                                synchronized (logger) {
                                    logger.logOk(table + ": " + result[0] + " 行, " + ConsoleLogger.formatBytes(result[1]));
                                }
                            } else {
                                empty.incrementAndGet();
                            }
                            success = true;
                            break;
                        } catch (Exception e) {
                            String msg = e.getMessage() != null ? e.getMessage() : "unknown";
                            logger.logToFile("[ERR]   " + table + " (attempt " + attempt + "): " + msg);
                            if (attempt < MAX_RETRY) {
                                synchronized (logger) {
                                    logger.logWarn(table + " 失败，重试中...");
                                }
                            }
                        }
                    }
                    if (!success) {
                        fail.incrementAndGet();
                        synchronized (logger) {
                            logger.logErr(table + ": " + MAX_RETRY + " 次尝试均失败，跳过");
                        }
                    }
                } finally {
                    semaphore.release();
                }

                int d = done.incrementAndGet();
                logger.logProgress("导出进度", d, total);
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(TABLE_TIMEOUT_SEC * 2L, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        pool.shutdown();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("成功", ok.get());
        stats.put("空表", empty.get());
        stats.put("失败", fail.get());
        stats.put("总行数", (int) totalRows.get());
        stats.put("总大小", ConsoleLogger.formatBytes(totalBytes.get()));
        logger.logSummary("数据导出统计", stats);
    }

    private long[] exportTableData(Connection conn, String owner, String table, String dataDir) throws SQLException, IOException {
        List<ColumnInfo> columns = getColumns(conn, owner, table);
        if (columns.isEmpty()) return new long[]{0, 0};

        String fileName = dataDir + "/" + table.toLowerCase() + ".sql";

        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.setFetchSize(FETCH_SIZE);

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + owner + "." + table)) {
                if (!rs.next()) return new long[]{0, 0};

                long count = 0;
                long bytes = 0;
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileName), 1024 * 1024)) {
                    do {
                        count++;
                        String line = SqlUtils.insertSql(table, columns, rs, convertBool, columnCommentsCache);
                        bw.write(line);
                        bw.newLine();
                        bytes += line.length() + 1;

                        if (count % 5000 == 0) {
                            bw.write("COMMIT;\n");
                            bytes += 8;
                        }
                    } while (rs.next());

                    bw.write("COMMIT;\n");
                    bytes += 8;
                }

                return new long[]{count, bytes};
            }
        }
    }

    // ==================== 元数据查询 ====================

    private Map<String, String> getTableComments(Connection conn, String owner) throws SQLException {
        Map<String, String> m = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND COMMENTS IS NOT NULL")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) m.put(rs.getString(1), rs.getString(2));
            }
        }
        return m;
    }

    private Map<String, Map<String, String>> getColumnComments(Connection conn, String owner) throws SQLException {
        Map<String, Map<String, String>> m = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME, COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = ? AND COMMENTS IS NOT NULL")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.computeIfAbsent(rs.getString("TABLE_NAME"), k -> new HashMap<>())
                            .put(rs.getString("COLUMN_NAME"), rs.getString("COMMENTS"));
                }
            }
        }
        return m;
    }

    private List<String> indexColumns(Connection conn, String owner, String idx) throws SQLException {
        return colList(conn, "SELECT COLUMN_NAME FROM ALL_IND_COLUMNS WHERE INDEX_OWNER=? AND INDEX_NAME=? ORDER BY COLUMN_POSITION", owner, idx);
    }

    private List<String> constraintColumns(Connection conn, String owner, String cname) throws SQLException {
        return colList(conn, "SELECT COLUMN_NAME FROM ALL_CONS_COLUMNS WHERE OWNER=? AND CONSTRAINT_NAME=? ORDER BY POSITION", owner, cname);
    }

    private List<String> colList(Connection conn, String sql, String p1, String p2) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p1); ps.setString(2, p2);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(rs.getString(1)); }
        }
        return list;
    }

    private List<ColumnInfo> getColumns(Connection conn, String owner, String table) throws SQLException {
        List<ColumnInfo> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, DATA_PRECISION, DATA_SCALE FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID")) {
            ps.setString(1, owner); ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo c = new ColumnInfo();
                    c.table = table;
                    c.name = rs.getString("COLUMN_NAME");
                    c.type = rs.getString("DATA_TYPE");
                    c.precision = rs.getInt("DATA_PRECISION");
                    c.scale = rs.getInt("DATA_SCALE");
                    list.add(c);
                }
            }
        }
        return list;
    }

    private void writeSource(Connection conn, String owner, String name, String type, PrintWriter w) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement("SELECT TEXT FROM ALL_SOURCE WHERE OWNER=? AND NAME=? AND TYPE=? ORDER BY LINE")) {
            ps.setString(1, owner); ps.setString(2, name); ps.setString(3, type);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) sb.append(rs.getString("TEXT")); }
        }
        w.println("-- " + type + ": " + name);
        w.println("/*");
        w.println(sb);
        w.println("*/");
        w.println("-- TODO: 转换为 PL/pgSQL");
        w.println();
    }

    private void writePackage(Connection conn, String owner, String name, PrintWriter w) throws SQLException {
        StringBuilder spec = new StringBuilder(), body = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement("SELECT TEXT FROM ALL_SOURCE WHERE OWNER=? AND NAME=? AND TYPE=? ORDER BY LINE")) {
            ps.setString(1, owner); ps.setString(2, name);
            ps.setString(3, "PACKAGE");      try (ResultSet rs = ps.executeQuery()) { while (rs.next()) spec.append(rs.getString("TEXT")); }
            ps.setString(3, "PACKAGE BODY"); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) body.append(rs.getString("TEXT")); }
        }
        w.println("-- 包: " + name);
        w.println("-- 声明:\n/*\n" + spec + "\n*/");
        w.println("-- 包体:\n/*\n" + body + "\n*/");
        w.println("-- TODO: 拆分为独立函数，前缀: " + name.toLowerCase() + "_");
        w.println();
    }
}
