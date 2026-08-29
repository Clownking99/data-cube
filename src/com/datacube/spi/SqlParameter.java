package com.datacube.spi;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** A typed JDBC parameter whose diagnostic representation never exposes its value. */
public record SqlParameter(int jdbcType, Object value) {
    public void bind(PreparedStatement statement, int index) throws SQLException {
        if (value == null) {
            statement.setNull(index, jdbcType);
        } else {
            statement.setObject(index, value, jdbcType);
        }
    }

    @Override
    public String toString() {
        return "SqlParameter[jdbcType=" + jdbcType + ", value=<redacted>]";
    }
}
