package com.datacube;

import java.sql.*;

public class DataCube {

    public static void main(String[] args) {
        try {
            printBanner();
            ConsoleLogger.openLog();

            // Oracle
            ConsoleLogger.logSection("第一步：Oracle 数据库连接信息");
            String oraUrl  = ConsoleLogger.prompt("Oracle JDBC URL",  "jdbc:oracle:thin:@127.0.0.1:1521/orcl", "格式: jdbc:oracle:thin:@IP:端口/服务名");
            String oraUser = ConsoleLogger.prompt("Oracle 用户名",     "scott", "将导出该用户下的所有对象").toUpperCase();
            String oraPass = ConsoleLogger.prompt("Oracle 密码",       "", "");

            // PostgreSQL
            ConsoleLogger.logSection("第二步：PostgreSQL 数据库连接信息");
            String pgUrl    = ConsoleLogger.prompt("PostgreSQL JDBC URL", "jdbc:postgresql://127.0.0.1:5432/postgres", "格式: jdbc:postgresql://IP:端口/数据库名");
            String pgUser   = ConsoleLogger.prompt("PostgreSQL 用户名",   "postgres", "");
            String pgPass   = ConsoleLogger.prompt("PostgreSQL 密码",     "", "");
            String pgSchema = ConsoleLogger.prompt("PostgreSQL Schema",   oraUser.toLowerCase(), "Oracle 用户 " + oraUser + " 的对象将导入到此 schema");

            // 导出配置
            ConsoleLogger.logSection("第三步：导出配置");
            int exportThreads = 4;
            String threadsStr = ConsoleLogger.prompt("并行线程数", "4", "建议 2-8，网络不稳定可设为 1");
            try { exportThreads = Integer.parseInt(threadsStr); } catch (Exception e) { exportThreads = 4; }
            if (exportThreads < 1) exportThreads = 1;

            String boolStr = ConsoleLogger.prompt("是否自动转换布尔值(0/1→TRUE/FALSE)", "n", "仅当字段注释包含\"是否/true/false\"等关键词时转换 (y/n)");
            boolean convertBool = "y".equalsIgnoreCase(boolStr) || "yes".equalsIgnoreCase(boolStr);
            if (convertBool) ConsoleLogger.logInfo("布尔转换: 开启（仅注释含\"是否/true/false\"的 NUMBER(1,0) 字段）");
            else ConsoleLogger.logInfo("布尔转换: 关闭（0/1 保持原值）");

            ConsoleLogger.logInfo("Oracle 用户: " + oraUser + " → PG Schema: " + pgSchema);
            ConsoleLogger.logInfo("并行线程: " + exportThreads);

            // 加载驱动
            Class.forName("oracle.jdbc.driver.OracleDriver");
            Class.forName("org.postgresql.Driver");

            // 测试连接
            ConsoleLogger.logSection("测试连接");
            Connection oraConn;
            try {
                oraConn = DriverManager.getConnection(oraUrl, oraUser, oraPass);
                ConsoleLogger.logOk("Oracle 连接成功 (" + oraUrl + ")");
            } catch (SQLException e) {
                ConsoleLogger.logErr("Oracle 连接失败: " + e.getMessage());
                ConsoleLogger.logToFile(ConsoleLogger.stackTrace(e));
                ConsoleLogger.closeLog();
                return;
            }

            try {
                Connection pgConn = DriverManager.getConnection(pgUrl, pgUser, pgPass);
                ConsoleLogger.logOk("PostgreSQL 连接成功 (" + pgUrl + ")");

                boolean schemaExists = false;
                try (PreparedStatement ps = pgConn.prepareStatement(
                        "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
                    ps.setString(1, pgSchema);
                    try (ResultSet rs = ps.executeQuery()) { schemaExists = rs.next(); }
                }

                if (schemaExists) {
                    ConsoleLogger.logOk("Schema \"" + pgSchema + "\" 已存在");
                } else {
                    ConsoleLogger.logInfo("Schema \"" + pgSchema + "\" 不存在，正在创建...");
                    try (Statement stmt = pgConn.createStatement()) {
                        stmt.execute("CREATE SCHEMA " + pgSchema);
                        ConsoleLogger.logOk("Schema \"" + pgSchema + "\" 创建成功");
                    } catch (SQLException e) {
                        ConsoleLogger.logErr("创建 Schema 失败: " + e.getMessage());
                        ConsoleLogger.logToFile(ConsoleLogger.stackTrace(e));
                        pgConn.close();
                        oraConn.close();
                        ConsoleLogger.closeLog();
                        return;
                    }
                }

                pgConn.createStatement().execute("SET search_path TO " + pgSchema);
                pgConn.close();
            } catch (SQLException e) {
                ConsoleLogger.logErr("PostgreSQL 连接失败: " + e.getMessage());
                ConsoleLogger.logToFile(ConsoleLogger.stackTrace(e));
                oraConn.close();
                ConsoleLogger.closeLog();
                return;
            }

            // 初始化模块
            OracleExporter exporter = new OracleExporter();
            exporter.setExportThreads(exportThreads);
            exporter.setConvertBool(convertBool);

            PgImporter importer = new PgImporter();
            PgVerifier verifier = new PgVerifier();

            // 主菜单
            while (true) {
                System.out.println();
                ConsoleLogger.logLine();
                System.out.println("  功能菜单");
                ConsoleLogger.logLine();
                System.out.println("  1. 导出 DDL（表/序列/索引/约束/函数）");
                System.out.println("  2. 导出数据（全量，并行 " + exportThreads + " 线程）");
                System.out.println("  3. 导入到 PostgreSQL（完整模式 - 先清空再导入）");
                System.out.println("  4. 导入到 PostgreSQL（增量模式 - 仅补充缺失）");
                System.out.println("  5. 一键全部（导出DDL + 导出数据 + 增量导入）");
                System.out.println("  6. 验证导入结果");
                System.out.println("  0. 退出");
                ConsoleLogger.logLine();

                String choice = ConsoleLogger.prompt("请选择", "5", "");

                switch (choice) {
                    case "1": exporter.exportDDL(oraConn, oraUser, pgSchema); break;
                    case "2": exporter.exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema); break;
                    case "3": importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, false); break;
                    case "4": importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, true); break;
                    case "5":
                        exporter.exportDDL(oraConn, oraUser, pgSchema);
                        exporter.exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema);
                        importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, true);
                        verifier.verify(pgUrl, pgUser, pgPass, pgSchema);
                        break;
                    case "6": verifier.verify(pgUrl, pgUser, pgPass, pgSchema); break;
                    case "0":
                        oraConn.close();
                        ConsoleLogger.logOk("再见!");
                        ConsoleLogger.closeLog();
                        return;
                    default:
                        ConsoleLogger.logWarn("无效选择，请重试");
                }
            }
        } catch (Exception e) {
            ConsoleLogger.logErr("致命错误: " + e.getMessage());
            ConsoleLogger.logToFile(ConsoleLogger.stackTrace(e));
            System.err.println("  详细信息已写入日志文件");
            ConsoleLogger.closeLog();
        }
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════╗");
        System.out.println("  ║            DataCube 迁移工具 v3.0             ║");
        System.out.println("  ╠═══════════════════════════════════════════════╣");
        System.out.println("  ║  导出: DDL + 全量数据（并行导出）             ║");
        System.out.println("  ║  导入: 完整模式 / 增量模式                    ║");
        System.out.println("  ║  兼容: NVARCHAR2/NCLOB/BLOB/NUMBER/SYSDATE    ║");
        System.out.println("  ╚═══════════════════════════════════════════════╝");
    }
}
