package com.datacube.provider.oracle;

import com.datacube.spi.SqlParameter;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.RenderedFilterQuery;
import com.datacube.sqleditor.result.ResultFilterSqlRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Oracle wrapper-query renderer for result filters. */
public final class OracleResultFilterSqlRenderer implements ResultFilterSqlRenderer {
    private static final String ALIAS = "dc_filter";
    private final OracleSqlDialect dialect = new OracleSqlDialect();

    @Override
    public RenderedFilterQuery render(
            String originalSql, List<ResultColumn> columns, List<FilterCondition> conditions) {
        Objects.requireNonNull(originalSql, "originalSql");
        List<ResultColumn> metadata = List.copyOf(columns);
        List<FilterCondition> filters = List.copyOf(conditions);
        List<SqlParameter> parameters = new ArrayList<>();
        String quotedAlias = dialect.quoteIdentifier(ALIAS);
        String where = foldPredicates(metadata, filters, quotedAlias, parameters);
        String sql = "SELECT * FROM (" + originalSql + ") " + quotedAlias;
        if (!where.isEmpty()) sql += " WHERE " + where;
        return new RenderedFilterQuery(sql, parameters);
    }

    private String foldPredicates(
            List<ResultColumn> columns, List<FilterCondition> conditions,
            String quotedAlias, List<SqlParameter> parameters) {
        String expression = "";
        for (FilterCondition condition : conditions) {
            ResultColumn column = column(columns, condition.columnIndex());
            String predicate = predicate(quotedAlias + "." + dialect.quoteIdentifier(column.label()),
                    column, condition, parameters);
            if (expression.isEmpty()) {
                expression = predicate;
            } else {
                expression = "(" + expression + " " + condition.connector() + " " + predicate + ")";
            }
        }
        return expression;
    }

    private static ResultColumn column(List<ResultColumn> columns, int index) {
        if (index >= columns.size()) throw new IllegalArgumentException("筛选列不存在");
        ResultColumn column = columns.get(index);
        if (column.label().isBlank()) throw new IllegalArgumentException("筛选列名不能为空");
        return column;
    }

    private static String predicate(
            String reference, ResultColumn column, FilterCondition condition,
            List<SqlParameter> parameters) {
        return switch (condition.operator()) {
            case IS_NULL -> reference + " IS NULL";
            case IS_NOT_NULL -> reference + " IS NOT NULL";
            case EQ -> bind(reference, "=", column, condition.value(), parameters);
            case NE -> bind(reference, "<>", column, condition.value(), parameters);
            case GT -> bind(reference, ">", column, condition.value(), parameters);
            case GTE -> bind(reference, ">=", column, condition.value(), parameters);
            case LT -> bind(reference, "<", column, condition.value(), parameters);
            case LTE -> bind(reference, "<=", column, condition.value(), parameters);
            case CONTAINS -> pattern(reference, column, "%" + escaped(condition.value()) + "%", parameters);
            case STARTS_WITH -> pattern(reference, column, escaped(condition.value()) + "%", parameters);
            case ENDS_WITH -> pattern(reference, column, "%" + escaped(condition.value()), parameters);
        };
    }

    private static String bind(
            String reference, String operator, ResultColumn column, Object value,
            List<SqlParameter> parameters) {
        parameters.add(new SqlParameter(column.jdbcType(), value));
        return reference + " " + operator + " ?";
    }

    private static String pattern(
            String reference, ResultColumn column, String value, List<SqlParameter> parameters) {
        parameters.add(new SqlParameter(column.jdbcType(), value));
        return reference + " LIKE ? ESCAPE '\\'";
    }

    private static String escaped(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("文本筛选值类型无效");
        }
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
