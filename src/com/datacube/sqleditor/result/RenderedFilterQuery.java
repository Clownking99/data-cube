package com.datacube.sqleditor.result;

import com.datacube.spi.SqlParameter;

import java.util.List;
import java.util.Objects;

/** Provider SQL and its left-to-right JDBC bind parameters. */
public record RenderedFilterQuery(String sql, List<SqlParameter> parameters) {
    public RenderedFilterQuery {
        sql = Objects.requireNonNull(sql, "sql");
        parameters = List.copyOf(parameters);
    }
}
