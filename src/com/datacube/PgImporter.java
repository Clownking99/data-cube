package com.datacube;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class PgImporter {

    private static final String BASE_DIR = "pg_migration";

    public void importToPg(String pgUrl, String pgUser, String pgPass, String owner, String schema, boolean incremental) throws Exception {
        ConsoleLogger.startTimer();
        String mode = incremental ? "增量模式" : "完整模式";
        ConsoleLogger.logSection("导入到 PostgreSQL：" + owner + " → " + schema + "（" + mode + "）");

        String basePath = BASE_DIR + "/" + schema.toLowerCase();

        try (Connection conn = DriverManager.getConnection(pgUrl, pgUser, pgPass)) {
            ensureSchema(conn, schema);

            Statement stmt = conn.createStatement();
            stmt.execute("SET search_path TO " + schema);

            Set<String> existingTables = new HashSet<>();
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + schema + "'");
            while (rs.next()) existingTables.add(rs.getString(1).toLowerCase());

            int beforeTableCount = existingTables.size();

            if (incremental) {
                ConsoleLogger.logInfo("[1/7] 建表（增量: 仅创建缺失表）...");
                execSqlFileIncremental(conn, basePath + "/02_tables.sql", existingTables);
            } else {
                ConsoleLogger.logInfo("[1/7] 建表（完整模式）...");
                execSqlFile(conn, basePath + "/02_tables.sql");
            }

            ConsoleLogger.logInfo("[2/7] 检测并修复缺失表...");
            int fixed = fixMissing(conn, schema, basePath + "/02_tables.sql");
            if (fixed > 0) ConsoleLogger.logOk("修复了 " + fixed + " 个缺失表");
            else ConsoleLogger.logOk("无缺失表");

            ConsoleLogger.logInfo("[3/7] 建序列...");
            execSqlFile(conn, basePath + "/01_sequences.sql");

            ConsoleLogger.logInfo("[4/7] 建索引...");
            execSqlFile(conn, basePath + "/03_indexes.sql");

            ConsoleLogger.logInfo("[5/7] 导入数据...");
            importData(conn, basePath + "/data", incremental);

            ConsoleLogger.logInfo("[6/7] 建约束（主键/唯一）...");
            execSqlFile(conn, basePath + "/04_constraints.sql");

            ConsoleLogger.logInfo("[7/7] 建触发器...");
            execSqlFile(conn, basePath + "/07_triggers.sql");

            rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema + "'");
            rs.next();
            int afterTableCount = rs.getInt(1);

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("导入前表数", beforeTableCount);
            stats.put("导入后表数", afterTableCount);
            stats.put("新增表数", afterTableCount - beforeTableCount);
            ConsoleLogger.logSummary("导入统计 (" + ConsoleLogger.elapsed() + ")", stats);
        }
    }

    private void ensureSchema(Connection conn, String schema) throws SQLException {
        boolean schemaExists = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) { schemaExists = rs.next(); }
        }
        if (!schemaExists) {
            ConsoleLogger.logInfo("Schema \"" + schema + "\" 不存在，正在创建...");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA " + schema);
                ConsoleLogger.logOk("Schema \"" + schema + "\" 创建成功");
            } catch (SQLException e) {
                ConsoleLogger.logErr("创建 Schema 失败: " + e.getMessage());
                ConsoleLogger.logToFile(ConsoleLogger.stackTrace(e));
                throw e;
            }
        }
    }

    private void importData(Connection conn, String dataDir, boolean incremental) throws Exception {
        File dir = new File(dataDir);
        if (!dir.exists()) { ConsoleLogger.logWarn("无数据目录: " + dataDir); return; }

        File[] files = dir.listFiles((d, n) -> n.endsWith(".sql"));
        if (files == null || files.length == 0) { ConsoleLogger.logWarn("无数据文件"); return; }

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
                ConsoleLogger.logErr("导入失败: " + tableName + " - " + e.getMessage());
                ConsoleLogger.logToFile("[ERR]   " + files[i].getName() + ": " + e.getMessage());
            }
            ConsoleLogger.logProgress("导入数据", i + 1, total);
        }

        ConsoleLogger.logOk("数据导入完成: " + ok + " 个表处理, " + fail + " 个失败, " + skip + " 个跳过(已有数据), 共 " + totalRows + " 行");
        if (!failedTables.isEmpty()) {
            ConsoleLogger.logWarn("失败的表 (" + failedTables.size() + "): " + String.join(", ", failedTables));
        }
    }

    private long execDataFile(Connection conn, File file, boolean incremental) throws Exception {
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
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"), 1024 * 1024);
             Statement stmt = conn.createStatement()) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("--")) continue;
                if (line.endsWith(";")) line = line.substring(0, line.length() - 1).trim();
                if (line.isEmpty() || !line.toUpperCase().startsWith("INSERT")) continue;
                try { stmt.execute(line); rows++; }
                catch (SQLException e) {
                    errCount++;
                    lastErr = e.getMessage();
                    if (errCount <= 3) ConsoleLogger.logToFile("[ERR]   " + tableName + ": " + e.getMessage());
                }
            }
        }
        if (rows == 0 && errCount > 0) {
            throw new SQLException(tableName + ": 全部 " + errCount + " 条 INSERT 失败, 最后错误: " + lastErr);
        }
        return rows;
    }

    private int fixMissing(Connection conn, String schema, String scriptPath) throws Exception {
        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            ConsoleLogger.logWarn("DDL 文件不存在: " + scriptPath);
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

        ConsoleLogger.logWarn("发现 " + missing.size() + " 个缺失表: " + String.join(", ", missing));

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
                    if (missing.contains(curTable)) {
                        cur = new StringBuilder();
                        cur.append(line).append("\n");
                        continue;
                    }
                }
            }
            if (cur != null) {
                cur.append(line).append("\n");
                if (t.equals(");")) {
                    ddlMap.put(curTable, cur.toString());
                    cur = null;
                    curTable = null;
                }
            }
        }

        int fixed = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String table : missing) {
                String ddl = ddlMap.get(table);
                if (ddl == null) { ConsoleLogger.logWarn("未找到DDL: " + table); continue; }
                try {
                    stmt.execute(fixDDL(ddl));
                    fixed++;
                    ConsoleLogger.logOk("修复: " + table);
                } catch (SQLException e) {
                    ConsoleLogger.logErr("修复失败: " + table + " - " + e.getMessage().substring(0, Math.min(60, e.getMessage().length())));
                }
            }
        }
        return fixed;
    }

    private void execSqlFile(Connection conn, String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) return;
        String sql = new String(Files.readAllBytes(file.toPath()), "UTF-8");
        List<String> stmts = SqlUtils.splitSql(sql);
        int ok = 0, err = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String s : stmts) {
                s = s.trim();
                if (s.isEmpty() || s.startsWith("--")) continue;
                try { stmt.execute(s); ok++; }
                catch (SQLException e) { if (!e.getMessage().contains("already exists")) err++; }
            }
        }
        ConsoleLogger.logOk("执行 " + ok + " 条语句" + (err > 0 ? ", " + err + " 条错误/跳过" : ""));
    }

    private void execSqlFileIncremental(Connection conn, String path, Set<String> existingTables) throws Exception {
        File file = new File(path);
        if (!file.exists()) return;
        String sql = new String(Files.readAllBytes(file.toPath()), "UTF-8");
        List<String> stmts = SqlUtils.splitSql(sql);
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
                catch (SQLException e) {
                    if (!e.getMessage().contains("already exists"))
                        ConsoleLogger.logErr(e.getMessage().substring(0, Math.min(60, e.getMessage().length())));
                }
            }
        }
        ConsoleLogger.logOk("新建 " + created + " 个对象" + (skipped > 0 ? ", 跳过 " + skipped + " 个已存在" : ""));
    }

    private String fixDDL(String ddl) {
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
}
