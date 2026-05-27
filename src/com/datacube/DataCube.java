package com.datacube;

import com.datacube.cli.ConsoleLogger;
import com.datacube.cli.ConsolePrompter;
import com.datacube.source.OracleExporter;
import com.datacube.target.PgImporter;
import com.datacube.target.PgVerifier;

import java.sql.*;

public class DataCube {

    public static void main(String[] args) {
        try {
            printBanner();

            ConsoleLogger logger = new ConsoleLogger();
            ConsolePrompter prompter = new ConsolePrompter();
            logger.openLog();

            // Oracle
            logger.logSection("第一步：Oracle 数据库连接信息");
            String oraUrl  = prompter.prompt("Oracle JDBC URL",  "jdbc:oracle:thin:@127.0.0.1:1521/orcl", "格式: jdbc:oracle:thin:@IP:端口/服务名");
            String oraUser = prompter.prompt("Oracle 用户名",     "scott", "将导出该用户下的所有对象").toUpperCase();
            String oraPass = prompter.prompt("Oracle 密码",       "", "");

            // PostgreSQL
            logger.logSection("第二步：PostgreSQL 数据库连接信息");
            String pgUrl    = prompter.prompt("PostgreSQL JDBC URL", "jdbc:postgresql://127.0.0.1:5432/postgres", "格式: jdbc:postgresql://IP:端口/数据库名");
            String pgUser   = prompter.prompt("PostgreSQL 用户名",   "postgres", "");
            String pgPass   = prompter.prompt("PostgreSQL 密码",     "", "");
            String pgSchema = prompter.prompt("PostgreSQL Schema",   oraUser.toLowerCase(), "Oracle 用户 " + oraUser + " 的对象将导入到此 schema");

            // 导出配置
            logger.logSection("第三步：导出配置");
            int exportThreads = 4;
            String threadsStr = prompter.prompt("并行线程数", "4", "建议 2-8，网络不稳定可设为 1");
            try { exportThreads = Integer.parseInt(threadsStr); } catch (Exception e) { exportThreads = 4; }
            if (exportThreads < 1) exportThreads = 1;

            String boolStr = prompter.prompt("是否自动转换布尔值(0/1→TRUE/FALSE)", "n", "仅当字段注释包含\"是否/true/false\"等关键词时转换 (y/n)");
            boolean convertBool = "y".equalsIgnoreCase(boolStr) || "yes".equalsIgnoreCase(boolStr);
            if (convertBool) logger.logInfo("布尔转换: 开启（仅注释含\"是否/true/false\"的 NUMBER(1,0) 字段）");
            else logger.logInfo("布尔转换: 关闭（0/1 保持原值）");

            logger.logInfo("Oracle 用户: " + oraUser + " → PG Schema: " + pgSchema);
            logger.logInfo("并行线程: " + exportThreads);

            // 加载驱动
            Class.forName("oracle.jdbc.driver.OracleDriver");
            Class.forName("org.postgresql.Driver");

            // 测试连接
            logger.logSection("测试连接");
            Connection oraConn;
            try {
                oraConn = DriverManager.getConnection(oraUrl, oraUser, oraPass);
                logger.logOk("Oracle 连接成功 (" + oraUrl + ")");
            } catch (SQLException e) {
                logger.logErr("Oracle 连接失败: " + e.getMessage());
                logger.logToFile(ConsoleLogger.stackTrace(e));
                logger.closeLog();
                return;
            }

            try {
                Connection pgConn = DriverManager.getConnection(pgUrl, pgUser, pgPass);
                logger.logOk("PostgreSQL 连接成功 (" + pgUrl + ")");

                boolean schemaExists = false;
                try (PreparedStatement ps = pgConn.prepareStatement(
                        "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
                    ps.setString(1, pgSchema);
                    try (ResultSet rs = ps.executeQuery()) { schemaExists = rs.next(); }
                }

                if (schemaExists) {
                    logger.logOk("Schema \"" + pgSchema + "\" 已存在");
                } else {
                    logger.logInfo("Schema \"" + pgSchema + "\" 不存在，正在创建...");
                    try (Statement stmt = pgConn.createStatement()) {
                        stmt.execute("CREATE SCHEMA " + pgSchema);
                        logger.logOk("Schema \"" + pgSchema + "\" 创建成功");
                    } catch (SQLException e) {
                        logger.logErr("创建 Schema 失败: " + e.getMessage());
                        logger.logToFile(ConsoleLogger.stackTrace(e));
                        pgConn.close();
                        oraConn.close();
                        logger.closeLog();
                        return;
                    }
                }

                pgConn.createStatement().execute("SET search_path TO " + pgSchema);
                pgConn.close();
            } catch (SQLException e) {
                logger.logErr("PostgreSQL 连接失败: " + e.getMessage());
                logger.logToFile(ConsoleLogger.stackTrace(e));
                oraConn.close();
                logger.closeLog();
                return;
            }

            // 初始化模块（注入 logger）
            OracleExporter exporter = new OracleExporter(logger);
            exporter.setExportThreads(exportThreads);
            exporter.setConvertBool(convertBool);

            PgImporter importer = new PgImporter(logger);
            PgVerifier verifier = new PgVerifier(logger);

            // 主菜单
            while (true) {
                System.out.println();
                logger.logLine();
                System.out.println("  功能菜单");
                logger.logLine();
                System.out.println("  1. 导出 DDL（表/序列/索引/约束/函数）");
                System.out.println("  2. 导出数据（全量，并行 " + exportThreads + " 线程）");
                System.out.println("  3. 导入到 PostgreSQL（完整模式 - 先清空再导入）");
                System.out.println("  4. 导入到 PostgreSQL（增量模式 - 仅补充缺失）");
                System.out.println("  5. 一键全部（导出DDL + 导出数据 + 增量导入）");
                System.out.println("  6. 验证导入结果");
                System.out.println("  0. 退出");
                logger.logLine();

                String choice = prompter.prompt("请选择", "5", "");

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
                        logger.logOk("再见!");
                        logger.closeLog();
                        return;
                    default:
                        logger.logWarn("无效选择，请重试");
                }
            }
        } catch (Exception e) {
            System.err.println("  [ERR] 致命错误: " + e.getMessage());
            e.printStackTrace();
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
