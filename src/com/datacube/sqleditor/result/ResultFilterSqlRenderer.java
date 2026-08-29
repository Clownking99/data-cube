package com.datacube.sqleditor.result;

import com.datacube.spi.model.ResultColumn;

import java.util.List;

/** Renders a filtered wrapper query for one database dialect. */
@FunctionalInterface
public interface ResultFilterSqlRenderer {
    RenderedFilterQuery render(
            String originalSql, List<ResultColumn> columns, List<FilterCondition> conditions);
}
