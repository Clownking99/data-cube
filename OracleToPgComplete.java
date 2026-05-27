import java.sql.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.text.*;
import java.time.*;
import java.time.format.*;

public class OracleToPgComplete {

    private static final Scanner SCAN = new Scanner(System.in);
    private static final String BASE_DIR = "pg_migration";
    private static long startTime;
    private static PrintWriter logWriter;

    // 默认配置
    private static int EXPORT_THREADS = 4;
    private static final int FETCH_SIZE = 2000;
    // 单表导出超时（秒），超时后重试，再超时则跳过
    private static final int TABLE_TIMEOUT_SEC = 600; // 10分钟
    private static final int MAX_RETRY = 2;
    // 是否自动将 NUMBER(1,0) 的 0/1 转换为 TRUE/FALSE
    private static boolean convertBool = false;
    // 字段注释缓存（表名 -> 字段名 -> 注释），用于判断是否为布尔字段
    private static Map<String, Map<String, String>> columnCommentsCache = new HashMap<>();

    public static void main(String[] args) {
        try {
            printBanner();

            // 初始化日志文件
            String logFileName = "migration_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".log";
            logWriter = new PrintWriter(new FileWriter(logFileName), true);
            logInfo("日志文件: " + new File(logFileName).getAbsolutePath());

            // Oracle
            logSection("第一步：Oracle 数据库连接信息");
            String oraUrl  = prompt("Oracle JDBC URL",  "jdbc:oracle:thin:@127.0.0.1:1521/orcl", "格式: jdbc:oracle:thin:@IP:端口/服务名");
            String oraUser = prompt("Oracle 用户名",     "scott", "将导出该用户下的所有对象").toUpperCase();
            String oraPass = prompt("Oracle 密码",       "", "");

            // PostgreSQL
            logSection("第二步：PostgreSQL 数据库连接信息");
            String pgUrl    = prompt("PostgreSQL JDBC URL", "jdbc:postgresql://127.0.0.1:5432/postgres", "格式: jdbc:postgresql://IP:端口/数据库名");
            String pgUser   = prompt("PostgreSQL 用户名",   "postgres", "");
            String pgPass   = prompt("PostgreSQL 密码",     "", "");
            String pgSchema = prompt("PostgreSQL Schema",   oraUser.toLowerCase(), "Oracle 用户 " + oraUser + " 的对象将导入到此 schema");

            // 导出配置
            logSection("第三步：导出配置");
            String threadsStr = prompt("并行线程数", "4", "建议 2-8，网络不稳定可设为 1");
            try { EXPORT_THREADS = Integer.parseInt(threadsStr); } catch (Exception e) { EXPORT_THREADS = 4; }
            if (EXPORT_THREADS < 1) EXPORT_THREADS = 1;

            String boolStr = prompt("是否自动转换布尔值(0/1→TRUE/FALSE)", "n", "仅当字段注释包含\"是否/true/false\"等关键词时转换 (y/n)");
            convertBool = "y".equalsIgnoreCase(boolStr) || "yes".equalsIgnoreCase(boolStr);
            if (convertBool) logInfo("布尔转换: 开启（仅注释含\"是否/true/false\"的 NUMBER(1,0) 字段）");
            else logInfo("布尔转换: 关闭（0/1 保持原值）");

            logInfo("Oracle 用户: " + oraUser + " → PG Schema: " + pgSchema);
            logInfo("并行线程: " + EXPORT_THREADS + ", 单表超时: " + TABLE_TIMEOUT_SEC + "s, 重试: " + MAX_RETRY + " 次");

            // 加载驱动
            Class.forName("oracle.jdbc.driver.OracleDriver");
            Class.forName("org.postgresql.Driver");

            // 测试连接
            logSection("测试连接");
            Connection oraConn;
            try {
                oraConn = DriverManager.getConnection(oraUrl, oraUser, oraPass);
                logOk("Oracle 连接成功 (" + oraUrl + ")");
            } catch (SQLException e) {
                logErr("Oracle 连接失败: " + e.getMessage());
                logToFile(stackTrace(e));
                closeLog();
                return;
            }

            try {
                Connection pgConn = DriverManager.getConnection(pgUrl, pgUser, pgPass);
                logOk("PostgreSQL 连接成功 (" + pgUrl + ")");

                // 检查 schema 是否存在，不存在则创建
                boolean schemaExists = false;
                try (PreparedStatement ps = pgConn.prepareStatement(
                        "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
                    ps.setString(1, pgSchema);
                    try (ResultSet rs = ps.executeQuery()) { schemaExists = rs.next(); }
                }

                if (schemaExists) {
                    logOk("Schema \"" + pgSchema + "\" 已存在");
                } else {
                    logInfo("Schema \"" + pgSchema + "\" 不存在，正在创建...");
                    try (Statement stmt = pgConn.createStatement()) {
                        stmt.execute("CREATE SCHEMA " + pgSchema);
                        logOk("Schema \"" + pgSchema + "\" 创建成功");
                    } catch (SQLException e) {
                        logErr("创建 Schema 失败: " + e.getMessage());
                        logToFile(stackTrace(e));
                        pgConn.close();
                        oraConn.close();
                        closeLog();
                        return;
                    }
                }

                pgConn.createStatement().execute("SET search_path TO " + pgSchema);
                pgConn.close();
            } catch (SQLException e) {
                logErr("PostgreSQL 连接失败: " + e.getMessage());
                logToFile(stackTrace(e));
                oraConn.close();
                closeLog();
                return;
            }

            // 主菜单
            while (true) {
                System.out.println();
                logLine();
                System.out.println("  功能菜单");
                logLine();
                System.out.println("  1. 导出 DDL（表/序列/索引/约束/函数）");
                System.out.println("  2. 导出数据（全量，并行 " + EXPORT_THREADS + " 线程）");
                System.out.println("  3. 导入到 PostgreSQL（完整模式 - 先清空再导入）");
                System.out.println("  4. 导入到 PostgreSQL（增量模式 - 仅补充缺失）");
                System.out.println("  5. 一键全部（导出DDL + 导出数据 + 增量导入）");
                System.out.println("  6. 验证导入结果");
                System.out.println("  0. 退出");
                logLine();

                String choice = prompt("请选择", "5", "");

                switch (choice) {
                    case "1": exportDDL(oraConn, oraUser, pgSchema); break;
                    case "2": exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema); break;
                    case "3": importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, false); break;
                    case "4": importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, true); break;
                    case "5":
                        exportDDL(oraConn, oraUser, pgSchema);
                        exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema);
                        importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, true);
                        verifyImport(pgUrl, pgUser, pgPass, pgSchema);
                        break;
                    case "6": verifyImport(pgUrl, pgUser, pgPass, pgSchema); break;
                    case "0":
                        oraConn.close();
                        logOk("再见!");
                        closeLog();
                        return;
                    default:
                        logWarn("无效选择，请重试");
                }
            }
        } catch (Exception e) {
            logErr("致命错误: " + e.getMessage());
            logToFile(stackTrace(e));
            System.err.println("  详细信息已写入日志文件");
            closeLog();
        }
    }

    // ==================== 界面 ====================

    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════╗");
        System.out.println("  ║       Oracle → PostgreSQL 迁移工具 v2.4       ║");
        System.out.println("  ╠═══════════════════════════════════════════════╣");
        System.out.println("  ║  导出: DDL + 全量数据（并行导出）             ║");
        System.out.println("  ║  导入: 完整模式 / 增量模式                    ║");
        System.out.println("  ║  兼容: NVARCHAR2/NCLOB/BLOB/NUMBER/SYSDATE    ║");
        System.out.println("  ╚═══════════════════════════════════════════════╝");
    }

    private static String prompt(String label, String defaultVal, String hint) {
        if (!hint.isEmpty()) System.out.println("    (" + hint + ")");
        System.out.print("  " + label + (defaultVal.isEmpty() ? ": " : " [" + defaultVal + "]: "));
        String input = SCAN.nextLine().trim();
        return input.isEmpty() ? defaultVal : input;
    }

    // ==================== 日志 ====================

    private static String ts() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static void logToFile(String msg) {
        if (logWriter != null) logWriter.println("[" + ts() + "] " + msg);
    }

    private static void closeLog() {
        if (logWriter != null) { logWriter.close(); logWriter = null; }
    }

    private static void logSection(String msg) {
        System.out.println();
        logLine();
        System.out.println("  " + msg);
        logLine();
        logToFile("");
        logToFile("=== " + msg + " ===");
    }

    private static void logLine() {
        System.out.println("  ─────────────────────────────────────────────");
    }

    private static void logInfo(String msg) {
        System.out.println("  [" + ts() + "]  " + msg);
        logToFile("[INFO]  " + msg);
    }

    private static void logOk(String msg) {
        System.out.println("  [" + ts() + "]  [OK] " + msg);
        logToFile("[OK]    " + msg);
    }

    private static void logWarn(String msg) {
        System.out.println("  [" + ts() + "]  [!!] " + msg);
        logToFile("[WARN]  " + msg);
    }

    private static void logErr(String msg) {
        System.out.println("  [" + ts() + "]  [ERR] " + msg);
        logToFile("[ERR]   " + msg);
    }

    private static void logProgress(String current, int done, int total) {
        int pct = total > 0 ? done * 100 / total : 0;
        String bar = repeat("█", pct / 5) + repeat("░", 20 - pct / 5);
        System.out.print("\r  [" + ts() + "]  " + current + " [" + bar + "] " + pct + "% (" + done + "/" + total + ")");
        if (done == total) {
            System.out.println();
            logToFile("[INFO]  " + current + " " + pct + "% (" + done + "/" + total + ")");
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static void logSummary(String title, Map<String, Object> stats) {
        System.out.println();
        logLine();
        System.out.println("  " + title);
        logLine();
        logToFile("--- " + title + " ---");
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            String label = e.getKey();
            Object val = e.getValue();
            String icon;
            if (val instanceof Number && ((Number) val).longValue() > 0) icon = "  ✓";
            else if (val instanceof String) icon = "  ·";
            else icon = "  -";
            System.out.println(icon + " " + label + ": " + val);
            logToFile("  " + label + ": " + val);
        }
        logLine();
    }

    private static String stackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ==================== DDL 导出 ====================

    private static void exportDDL(Connection conn, String owner, String pgSchema) throws SQLException, IOException {
        startTime = System.currentTimeMillis();
        logSection("导出 DDL：" + owner + " → " + pgSchema);

        String outputDir = BASE_DIR + "/" + pgSchema;
        new File(outputDir).mkdirs();

        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("序列",         exportSequences(conn, owner, outputDir));
        stats.put("表",           exportTables(conn, owner, outputDir));
        stats.put("索引",         exportIndexes(conn, owner, outputDir));
        stats.put("约束",         exportConstraints(conn, owner, outputDir));
        stats.put("存储过程/函数", exportFunctions(conn, owner, outputDir));
        stats.put("包",           exportPackages(conn, owner, outputDir));
        stats.put("触发器",       exportTriggers(conn, owner, outputDir));

        logSummary("DDL 导出统计 (" + elapsed() + ")", stats);
        logOk("脚本已输出到 " + outputDir + "/");
    }

    // ==================== 序列 ====================

    private static int exportSequences(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/01_sequences.sql"));
        header(w, "序列");

        String sql = "SELECT SEQUENCE_NAME, MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG, CACHE_SIZE " +
                "FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = ? ORDER BY SEQUENCE_NAME";

        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    String name  = rs.getString("SEQUENCE_NAME").toLowerCase();
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
        logOk("序列: " + count + " 个");
        return count;
    }

    // ==================== 表 ====================

    private static int exportTables(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/02_tables.sql"));
        header(w, "表结构");

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
            logProgress("导出表结构", i + 1, total);
        }
        w.close();
        logOk("表: " + total + " 个");
        return total;
    }

    private static void writeTable(Connection conn, String owner, String table, PrintWriter w,
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

                    StringBuilder col = new StringBuilder("    " + colName.toLowerCase() + " " + convertType(type, len, prec, scale));
                    if ("N".equals(nullable)) col.append(" NOT NULL");
                    if (defVal != null && !defVal.trim().isEmpty()) col.append(" DEFAULT ").append(convertDefault(defVal.trim()));

                    String comment = cc.get(colName);
                    if (comment != null) col.append(" /* ").append(escapeComment(comment)).append(" */");

                    cols.add(col.toString());
                }
            }
        }

        w.println(String.join(",\n", cols));
        w.println(");");
        w.println();

        if (tc != null) w.println("COMMENT ON TABLE " + table.toLowerCase() + " IS '" + escapeSql(tc) + "';");
        for (Map.Entry<String, String> e : cc.entrySet()) {
            w.println("COMMENT ON COLUMN " + table.toLowerCase() + "." + e.getKey().toLowerCase() + " IS '" + escapeSql(e.getValue()) + "';");
        }
        w.println();
    }

    // ==================== 索引 ====================

    private static int exportIndexes(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/03_indexes.sql"));
        header(w, "索引");

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

                    w.println(("UNIQUE".equals(uni) ? "CREATE UNIQUE INDEX IF NOT EXISTS " : "CREATE INDEX IF NOT EXISTS ") +
                            idx + " ON " + tbl + " (" + joinLower(cols) + ");");
                    w.println();
                }
            }
        }
        w.close();
        logOk("索引: " + count + " 个");
        return count;
    }

    // ==================== 约束 ====================

    private static int exportConstraints(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/04_constraints.sql"));
        header(w, "约束");

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
                            ("P".equals(type) ? "PRIMARY KEY" : "UNIQUE") + " (" + joinLower(cols) + ");");
                    w.println();
                }
            }
        }
        w.close();
        logOk("约束: " + count + " 个");
        return count;
    }

    // ==================== 函数 ====================

    private static int exportFunctions(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/05_functions.sql"));
        header(w, "存储过程/函数");

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
        logOk("存储过程/函数: " + count + " 个");
        return count;
    }

    // ==================== 包 ====================

    private static int exportPackages(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/06_packages.sql"));
        header(w, "包");

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
        logOk("包: " + count + " 个");
        return count;
    }

    // ==================== 触发器 ====================

    private static int exportTriggers(Connection conn, String owner, String dir) throws SQLException, IOException {
        PrintWriter w = new PrintWriter(new FileWriter(dir + "/07_triggers.sql"));
        header(w, "触发器");

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
        logOk("触发器: " + count + " 个");
        return count;
    }

    // ==================== 数据导出（多线程） ====================

    private static void exportData(Connection conn, String oraUrl, String oraUser, String oraPass, String pgSchema) throws SQLException, IOException {
        startTime = System.currentTimeMillis();
        logSection("导出数据：" + oraUser + "（并行 " + EXPORT_THREADS + " 线程, 超时 " + TABLE_TIMEOUT_SEC + "s/表）");

        String dataDir = BASE_DIR + "/" + pgSchema.toLowerCase() + "/data";
        new File(dataDir).mkdirs();

        // 加载字段注释缓存（用于布尔转换判断）
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
        logInfo("共 " + total + " 张表，启动并行导出...");

        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger empty = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        AtomicLong totalRows = new AtomicLong(0);
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicInteger done = new AtomicInteger(0);

        // 正在导出的表名（用于实时显示）
        ConcurrentHashMap<String, String> activeTables = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(EXPORT_THREADS);
        List<Future<?>> futures = new ArrayList<>();

        for (String table : tables) {
            futures.add(pool.submit(() -> {
                String threadName = Thread.currentThread().getName();
                activeTables.put(threadName, table);

                boolean success = false;
                for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                    try (Connection threadConn = DriverManager.getConnection(oraUrl, oraUser, oraPass)) {
                        // 显示当前导出的表
                        synchronized (System.out) {
                            System.out.println();
                            logInfo(">> 导出: " + table + (attempt > 1 ? " (重试 " + attempt + ")" : ""));
                        }

                        long[] result = exportTableData(threadConn, oraUser, table, dataDir);
                        if (result[0] > 0) {
                            ok.incrementAndGet();
                            totalRows.addAndGet(result[0]);
                            totalBytes.addAndGet(result[1]);
                            synchronized (System.out) {
                                System.out.println();
                                logOk(table + ": " + result[0] + " 行, " + formatBytes(result[1]));
                            }
                        } else {
                            empty.incrementAndGet();
                        }
                        success = true;
                        break;
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage() : "unknown";
                        logToFile("[ERR]   " + table + " (attempt " + attempt + "): " + msg);
                        if (attempt < MAX_RETRY) {
                            synchronized (System.out) {
                                System.out.println();
                                logWarn(table + " 失败，" + (TABLE_TIMEOUT_SEC > 0 ? "超时或连接断开，" : "") + "重试中...");
                            }
                        }
                    }
                }
                if (!success) {
                    fail.incrementAndGet();
                    synchronized (System.out) {
                        System.out.println();
                        logErr(table + ": " + MAX_RETRY + " 次尝试均失败，跳过");
                    }
                }

                activeTables.remove(threadName);
                int d = done.incrementAndGet();
                logProgress("导出进度", d, total);
            }));
        }

        // 等待全部完成，带超时
        for (Future<?> f : futures) {
            try { f.get(TABLE_TIMEOUT_SEC * 2L, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        pool.shutdown();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("成功", ok.get());
        stats.put("空表", empty.get());
        stats.put("失败", fail.get());
        stats.put("总行数", (int) totalRows.get());
        stats.put("总大小", formatBytes(totalBytes.get()));
        logSummary("数据导出统计 (" + elapsed() + ")", stats);
    }

    /**
     * 导出单张表的数据，返回 [行数, 文件大小]
     * 使用流式读取（fetchSize）避免大表 OOM
     */
    private static long[] exportTableData(Connection conn, String owner, String table, String dataDir) throws SQLException, IOException {
        List<ColumnInfo> columns = getColumns(conn, owner, table);
        if (columns.isEmpty()) return new long[]{0, 0};

        String fileName = dataDir + "/" + table.toLowerCase() + ".sql";

        // 流式读取：setAutoCommit(false) + setFetchSize 让 Oracle 逐批返回
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
                        String line = insertSql(table, columns, rs);
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

    // ==================== 数据导入 ====================

    private static void importToPg(String pgUrl, String pgUser, String pgPass, String owner, String schema, boolean incremental) throws Exception {
        startTime = System.currentTimeMillis();
        String mode = incremental ? "增量模式" : "完整模式";
        logSection("导入到 PostgreSQL：" + owner + " → " + schema + "（" + mode + "）");

        String basePath = BASE_DIR + "/" + schema.toLowerCase();

        try (Connection conn = DriverManager.getConnection(pgUrl, pgUser, pgPass)) {
            // 确保 schema 存在
            boolean schemaExists = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) { schemaExists = rs.next(); }
            }
            if (!schemaExists) {
                logInfo("Schema \"" + schema + "\" 不存在，正在创建...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE SCHEMA " + schema);
                    logOk("Schema \"" + schema + "\" 创建成功");
                } catch (SQLException e) {
                    logErr("创建 Schema 失败: " + e.getMessage());
                    logToFile(stackTrace(e));
                    return;
                }
            }

            Statement stmt = conn.createStatement();
            stmt.execute("SET search_path TO " + schema);

            Set<String> existingTables = new HashSet<>();
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema + "'");
            while (rs.next()) existingTables.add(rs.getString(1).toLowerCase());

            int beforeTableCount = existingTables.size();

            if (incremental) {
                logInfo("[1/7] 建表（增量: 仅创建缺失表）...");
                execSqlFileIncremental(conn, basePath + "/02_tables.sql", existingTables);
            } else {
                logInfo("[1/7] 建表（完整模式）...");
                execSqlFile(conn, basePath + "/02_tables.sql");
            }

            logInfo("[2/7] 检测并修复缺失表...");
            int fixed = fixMissing(conn, schema, basePath + "/02_tables.sql");
            if (fixed > 0) logOk("修复了 " + fixed + " 个缺失表");
            else logOk("无缺失表");

            logInfo("[3/7] 建序列...");
            execSqlFile(conn, basePath + "/01_sequences.sql");

            logInfo("[4/7] 建索引...");
            execSqlFile(conn, basePath + "/03_indexes.sql");

            logInfo("[5/7] 导入数据...");
            importData(conn, basePath + "/data", incremental);

            logInfo("[6/7] 建约束（主键/唯一）...");
            execSqlFile(conn, basePath + "/04_constraints.sql");

            logInfo("[7/7] 建触发器...");
            execSqlFile(conn, basePath + "/07_triggers.sql");

            rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema + "'");
            rs.next();
            int afterTableCount = rs.getInt(1);

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("导入前表数", beforeTableCount);
            stats.put("导入后表数", afterTableCount);
            stats.put("新增表数", afterTableCount - beforeTableCount);
            logSummary("导入统计 (" + elapsed() + ")", stats);
        }
    }

    private static void importData(Connection conn, String dataDir, boolean incremental) throws Exception {
        File dir = new File(dataDir);
        if (!dir.exists()) { logWarn("无数据目录: " + dataDir); return; }

        File[] files = dir.listFiles((d, n) -> n.endsWith(".sql"));
        if (files == null || files.length == 0) { logWarn("无数据文件"); return; }

        int total = files.length;
        int ok = 0, fail = 0, skip = 0;
        long totalRows = 0;
        List<String> failedTables = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            try {
                long rows = execDataFile(conn, files[i], incremental);
                if (rows == 0 && incremental) skip++;
                totalRows += rows;
                ok++;
            } catch (Exception e) {
                fail++;
                String tableName = files[i].getName().replace(".sql", "");
                failedTables.add(tableName);
                logErr("导入失败: " + tableName + " - " + e.getMessage());
                logToFile("[ERR]   " + files[i].getName() + ": " + e.getMessage());
            }
            logProgress("导入数据", i + 1, total);
        }

        logOk("数据导入完成: " + ok + " 个表处理, " + fail + " 个失败, " + skip + " 个跳过(已有数据), 共 " + totalRows + " 行");
        if (!failedTables.isEmpty()) {
            logWarn("失败的表 (" + failedTables.size() + "): " + String.join(", ", failedTables));
        }
    }

    private static long execDataFile(Connection conn, File file, boolean incremental) throws Exception {
        String tableName = file.getName().replace(".sql", "");

        if (incremental) {
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                rs.next();
                if (rs.getInt(1) > 0) return 0;
            }
        }

        long rows = 0;
        int errCount = 0;
        String lastErr = null;
        // 流式逐行读取，避免大文件 OOM
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"), 1024 * 1024);
             Statement stmt = conn.createStatement()) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("--")) continue;
                // 去掉行尾的分号
                if (line.endsWith(";")) line = line.substring(0, line.length() - 1).trim();
                if (line.isEmpty() || !line.toUpperCase().startsWith("INSERT")) continue;
                try { stmt.execute(line); rows++; }
                catch (SQLException e) {
                    errCount++;
                    lastErr = e.getMessage();
                    if (errCount <= 3) logToFile("[ERR]   " + tableName + ": " + e.getMessage());
                }
            }
        }
        if (rows == 0 && errCount > 0) {
            throw new SQLException(tableName + ": 全部 " + errCount + " 条 INSERT 失败, 最后错误: " + lastErr);
        }
        return rows;
    }

    // ==================== 修复缺失表 ====================

    private static int fixMissing(Connection conn, String schema, String scriptPath) throws Exception {
        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            logWarn("DDL 文件不存在: " + scriptPath);
            return 0;
        }

        List<String> scriptTables = new ArrayList<>();
        for (String line : Files.readAllLines(scriptFile.toPath())) {
            line = line.trim();
            if (line.toUpperCase().startsWith("CREATE TABLE IF NOT EXISTS")) {
                String[] p = line.split("\\s+");
                if (p.length >= 6) scriptTables.add(p[5].toLowerCase().replace("(", "").trim());
            }
        }

        Set<String> dbTables = new HashSet<>();
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema + "'");
        while (rs.next()) dbTables.add(rs.getString(1).toLowerCase());

        Set<String> missing = new HashSet<>(scriptTables);
        missing.removeAll(dbTables);
        if (missing.isEmpty()) return 0;

        logWarn("发现 " + missing.size() + " 个缺失表: " + String.join(", ", missing));

        Map<String, String> ddlMap = new HashMap<>();
        List<String> lines = Files.readAllLines(scriptFile.toPath());
        StringBuilder cur = null;
        String curTable = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.toUpperCase().startsWith("CREATE TABLE IF NOT EXISTS")) {
                String[] p = t.split("\\s+");
                if (p.length >= 6) {
                    curTable = p[5].toLowerCase().replace("(", "").trim();
                    if (missing.contains(curTable)) { cur = new StringBuilder(); cur.append(line).append("\n"); continue; }
                }
            }
            if (cur != null) {
                cur.append(line).append("\n");
                if (t.equals(");")) { ddlMap.put(curTable, cur.toString()); cur = null; curTable = null; }
            }
        }

        int fixed = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String table : missing) {
                String ddl = ddlMap.get(table);
                if (ddl == null) { logWarn("未找到DDL: " + table); continue; }
                try { stmt.execute(fixDDL(ddl)); fixed++; logOk("修复: " + table); }
                catch (SQLException e) { logErr("修复失败: " + table + " - " + e.getMessage().substring(0, Math.min(60, e.getMessage().length()))); }
            }
        }
        return fixed;
    }

    // ==================== 验证 ====================

    private static void verifyImport(String pgUrl, String pgUser, String pgPass, String schema) throws Exception {
        startTime = System.currentTimeMillis();
        logSection("验证：" + schema);

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
            logSummary("验证结果 (" + elapsed() + ")", stats);

            rs = s.executeQuery("SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE schemaname='" + schema + "' ORDER BY n_live_tup DESC LIMIT 10");
            System.out.println("  数据量 TOP 10:");
            logToFile("数据量 TOP 10:");
            while (rs.next()) {
                String name = rs.getString(1);
                long cnt = rs.getLong(2);
                System.out.println("    " + String.format("%-40s", name) + String.format("%,d", cnt) + " 行");
                logToFile("  " + name + ": " + cnt + " 行");
            }
            System.out.println();
        }
    }

    // ==================== 工具方法 ====================

    private static void header(PrintWriter w, String title) {
        w.println("-- ============================================");
        w.println("-- Oracle → PostgreSQL 迁移脚本");
        w.println("-- " + title);
        w.println("-- 生成时间: " + new java.util.Date());
        w.println("-- ============================================");
        w.println();
    }

    private static String elapsed() {
        long ms = System.currentTimeMillis() - startTime;
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return (ms / 1000) + "s";
        return (ms / 60000) + "m " + ((ms % 60000) / 1000) + "s";
    }

    private static Map<String, String> getTableComments(Connection conn, String owner) throws SQLException {
        Map<String, String> m = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND COMMENTS IS NOT NULL")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) m.put(rs.getString(1), rs.getString(2)); }
        }
        return m;
    }

    private static Map<String, Map<String, String>> getColumnComments(Connection conn, String owner) throws SQLException {
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

    private static List<String> indexColumns(Connection conn, String owner, String idx) throws SQLException {
        return colList(conn, "SELECT COLUMN_NAME FROM ALL_IND_COLUMNS WHERE INDEX_OWNER=? AND INDEX_NAME=? ORDER BY COLUMN_POSITION", owner, idx);
    }

    private static List<String> constraintColumns(Connection conn, String owner, String cname) throws SQLException {
        return colList(conn, "SELECT COLUMN_NAME FROM ALL_CONS_COLUMNS WHERE OWNER=? AND CONSTRAINT_NAME=? ORDER BY POSITION", owner, cname);
    }

    private static List<String> colList(Connection conn, String sql, String p1, String p2) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p1); ps.setString(2, p2);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(rs.getString(1)); }
        }
        return list;
    }

    private static List<ColumnInfo> getColumns(Connection conn, String owner, String table) throws SQLException {
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

    private static void writeSource(Connection conn, String owner, String name, String type, PrintWriter w) throws SQLException {
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

    private static void writePackage(Connection conn, String owner, String name, PrintWriter w) throws SQLException {
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

    private static String convertType(String type, int len, int prec, int scale) {
        switch (type) {
            case "VARCHAR2": case "NVARCHAR2": return "VARCHAR(" + len + ")";
            case "CHAR": case "NCHAR": return "CHAR(" + len + ")";
            case "NUMBER":
                if (prec == 0 && scale == 0) return "NUMERIC";
                if (scale > 0) return "NUMERIC(" + prec + "," + scale + ")";
                return "NUMERIC(" + prec + ")";
            case "INTEGER": return "INTEGER";
            case "FLOAT": case "BINARY_FLOAT": case "BINARY_DOUBLE": return "DOUBLE PRECISION";
            case "DATE": case "TIMESTAMP": return "TIMESTAMP";
            case "TIMESTAMP WITH TIME ZONE": return "TIMESTAMPTZ";
            case "CLOB": case "NCLOB": case "LONG": return "TEXT";
            case "BLOB": case "RAW": case "LONG RAW": return "BYTEA";
            case "ROWID": return "VARCHAR(18)";
            case "XMLTYPE": return "XML";
            default: return type;
        }
    }

    private static String convertDefault(String v) {
        if ("SYSDATE".equalsIgnoreCase(v) || "SYSTIMESTAMP".equalsIgnoreCase(v)) return "CURRENT_TIMESTAMP";
        if (v.startsWith("SYS_GUID()")) return "gen_random_uuid()";
        return v;
    }

    private static String insertSql(String table, List<ColumnInfo> cols, ResultSet rs) throws SQLException {
        StringBuilder sb = new StringBuilder("INSERT INTO " + table.toLowerCase() + " (");
        for (int i = 0; i < cols.size(); i++) { if (i > 0) sb.append(", "); sb.append(cols.get(i).name.toLowerCase()); }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) { if (i > 0) sb.append(", "); sb.append(colVal(rs, cols.get(i))); }
        sb.append(");");
        return sb.toString();
    }

    private static String colVal(ResultSet rs, ColumnInfo col) throws SQLException {
        Object val = rs.getObject(col.name);
        if (rs.wasNull() || val == null) return "NULL";

        switch (col.type) {
            case "VARCHAR2": case "NVARCHAR2": case "CHAR": case "NCHAR":
                return escapeStr(val.toString());
            case "NUMBER":
                // 仅当用户开启布尔转换 且 字段注释包含布尔关键词时，才将 0/1 转换为 TRUE/FALSE
                if (col.precision == 1 && col.scale == 0 && convertBool && isBoolComment(col.table, col.name)) {
                    return ((Number) val).intValue() == 0 ? "FALSE" : "TRUE";
                }
                return val.toString();
            case "INTEGER": case "FLOAT": case "BINARY_FLOAT": case "BINARY_DOUBLE":
                return val.toString();
            case "DATE": case "TIMESTAMP": case "TIMESTAMP WITH TIME ZONE":
                Timestamp ts = rs.getTimestamp(col.name);
                return ts != null ? "'" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts) + "'" : "NULL";
            case "CLOB": case "NCLOB":
                String s = val.toString();
                if (s.length() > 50000) s = s.substring(0, 50000);
                return escapeStr(s);
            case "BLOB": case "RAW":
                if (val instanceof byte[]) {
                    byte[] b = (byte[]) val;
                    if (b.length > 10000) return "NULL";
                    StringBuilder h = new StringBuilder();
                    for (byte x : b) h.append(String.format("%02x", x));
                    return "'\\x" + h + "'::bytea";
                }
                return "NULL";
            default:
                return escapeStr(val.toString());
        }
    }

    private static String escapeStr(String s) {
        if (s == null) return "NULL";
        // 顺序很重要：先转义反斜杠，再转义单引号，避免双重转义
        return "'" + s.replace("\\", "\\\\").replace("'", "''").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\0", "") + "'";
    }

    private static String escapeSql(String s) { return s != null ? s.replace("\\", "\\\\").replace("'", "''") : ""; }
    private static String escapeComment(String s) {
        if (s == null) return "";
        return s.replace("*/", "* /").replace("/*", "/ *");
    }

    private static boolean isBoolComment(String table, String colName) {
        Map<String, String> cols = columnCommentsCache.get(table);
        if (cols == null) return false;
        String comment = cols.get(colName);
        if (comment == null) return false;
        String c = comment.toLowerCase();
        // 同时包含"是"和"否"（如"0否1是"、"0否 1是"、"0-否，1-是"、"是否"）
        if (comment.contains("是") && comment.contains("否")) return true;
        // 同时包含 true 和 false（大小写不敏感）
        if (c.contains("true") && c.contains("false")) return true;
        // 同时包含 yes 和 no（大小写不敏感）
        if (c.contains("yes") && c.contains("no")) return true;
        // y/n 简写（前后可能是空格、逗号等分隔符）
        if (c.contains("y/n") || c.contains("y\\n")) return true;
        return false;
    }

    private static String joinLower(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(", "); sb.append(list.get(i).toLowerCase()); }
        return sb.toString();
    }

    private static void execSqlFile(Connection conn, String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) return;
        String sql = new String(Files.readAllBytes(file.toPath()), "UTF-8");
        List<String> stmts = splitSql(sql);
        int ok = 0, err = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String s : stmts) {
                s = s.trim();
                if (s.isEmpty() || s.startsWith("--")) continue;
                try { stmt.execute(s); ok++; }
                catch (SQLException e) { if (!e.getMessage().contains("already exists")) err++; }
            }
        }
        logOk("执行 " + ok + " 条语句" + (err > 0 ? ", " + err + " 条错误/跳过" : ""));
    }

    private static void execSqlFileIncremental(Connection conn, String path, Set<String> existingTables) throws Exception {
        File file = new File(path);
        if (!file.exists()) return;
        String sql = new String(Files.readAllBytes(file.toPath()), "UTF-8");
        List<String> stmts = splitSql(sql);
        int created = 0, skipped = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String s : stmts) {
                s = s.trim();
                if (s.isEmpty() || s.startsWith("--")) continue;
                if (s.toUpperCase().startsWith("CREATE TABLE IF NOT EXISTS")) {
                    String[] parts = s.split("\\s+");
                    if (parts.length >= 6) {
                        String tableName = parts[5].toLowerCase().replace("(", "").trim();
                        if (existingTables.contains(tableName)) { skipped++; continue; }
                    }
                }
                try { stmt.execute(s); created++; }
                catch (SQLException e) { if (!e.getMessage().contains("already exists")) logErr(e.getMessage().substring(0, Math.min(60, e.getMessage().length()))); }
            }
        }
        logOk("新建 " + created + " 个对象" + (skipped > 0 ? ", 跳过 " + skipped + " 个已存在" : ""));
    }

    private static List<String> splitSql(String sql) {
        List<String> stmts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inDollar = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '$') {
                int j = i;
                while (j < sql.length() && sql.charAt(j) == '$') j++;
                int cnt = j - i;

                if (cnt >= 2) {
                    if (!inDollar) {
                        inDollar = true;
                        for (int k = 0; k < cnt; k++) cur.append('$');
                        i = j - 1; continue;
                    } else {
                        inDollar = false;
                        for (int k = 0; k < cnt; k++) cur.append('$');
                        i = j - 1; continue;
                    }
                }
            }

            if (inDollar) { cur.append(c); continue; }

            if (c == ';') {
                String s = cur.toString().trim();
                if (!s.isEmpty()) stmts.add(s);
                cur = new StringBuilder();
                continue;
            }
            cur.append(c);
        }

        String last = cur.toString().trim();
        if (!last.isEmpty()) stmts.add(last);
        return stmts;
    }

    private static String fixDDL(String ddl) {
        String f = ddl;
        f = f.replaceAll("(?i)\\bNVARCHAR2\\b", "VARCHAR");
        f = f.replaceAll("(?i)\\bVARCHAR2\\b", "VARCHAR");
        f = f.replaceAll("(?i)\\bNCLOB\\b", "TEXT");
        f = f.replaceAll("(?i)\\bCLOB\\b", "TEXT");
        f = f.replaceAll("(?i)\\bBLOB\\b", "BYTEA");
        f = f.replaceAll("(?i)\\bNUMBER\\b", "NUMERIC");
        f = f.replaceAll("(?i)\\bDATE\\b", "TIMESTAMP");
        f = f.replaceAll("(?i)\\bBINARY_FLOAT\\b", "DOUBLE PRECISION");
        f = f.replaceAll("(?i)\\bBINARY_DOUBLE\\b", "DOUBLE PRECISION");

        StringBuilder r = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < f.length(); i++) {
            char c = f.charAt(i);
            if (c == '\'') inQ = !inQ;
            if (inQ && c == ';') r.append(',');
            else r.append(c);
        }
        return r.toString();
    }

    static class ColumnInfo {
        String table, name, type;
        int precision, scale;
    }
}
