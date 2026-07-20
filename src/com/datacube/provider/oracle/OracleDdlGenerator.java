package com.datacube.provider.oracle;

import com.datacube.spi.DdlGenerator;
import com.datacube.spi.model.RoutineRef;
import com.datacube.spi.model.TableRef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Oracle DDL 生成器：统一用 {@code DBMS_METADATA.GET_DDL} 取回对象的 CREATE 语句。
 *
 * <p>表/视图/序列直接按类型取；函数/过程需先探测 {@code OBJECT_TYPE}。返回值为 CLOB。
 */
public final class OracleDdlGenerator implements DdlGenerator {

    private final Connection conn;

    public OracleDdlGenerator(Connection conn) {
        this.conn = conn;
    }

    @Override
    public String tableDdl(TableRef t) throws SQLException {
        return getDdl("TABLE", t.name(), t.schema(), "-- 表 DDL 不可用: " + t.qualified());
    }

    @Override
    public String viewDdl(TableRef t) throws SQLException {
        return getDdl("VIEW", t.name(), t.schema(), "-- 视图定义不可用: " + t.qualified());
    }

    @Override
    public String routineDdl(RoutineRef r) throws SQLException {
        String type = objectType(r.schema(), r.name());
        if (type == null) return "-- 对象定义不可用: " + r.qualified();
        // DBMS_METADATA 类型名：FUNCTION / PROCEDURE / PACKAGE
        return getDdl(type, r.name(), r.schema(), "-- 对象定义不可用: " + r.qualified());
    }

    @Override
    public String sequenceDdl(String schema, String name) throws SQLException {
        return getDdl("SEQUENCE", name, schema, "-- 序列定义不可用: " + name);
    }

    /** 探测对象类型（用于函数/过程/包的 GET_DDL 类型名）。 */
    private String objectType(String schema, String name) throws SQLException {
        String sql = "SELECT OBJECT_TYPE FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? "
                + "AND OBJECT_TYPE IN ('FUNCTION','PROCEDURE','PACKAGE') "
                + "ORDER BY OBJECT_TYPE FETCH FIRST 1 ROWS ONLY";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private String getDdl(String objectType, String name, String schema, String fallback) throws SQLException {
        String sql = "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, objectType);
            ps.setString(2, name);
            ps.setString(3, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String ddl = rs.getString(1);
                    if (ddl != null && !ddl.isBlank()) return ddl.strip();
                }
            }
        } catch (SQLException e) {
            return fallback + "\n-- " + e.getMessage();
        }
        return fallback;
    }
}
