package com.datacube.provider.postgres;

import com.datacube.spi.SqlParameter;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.RenderedFilterQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgResultFilterSqlRendererTest {
    private static final List<ResultColumn> COLUMNS = List.of(
            new ResultColumn(0, "id", Types.INTEGER, "int4"),
            new ResultColumn(1, "name", Types.VARCHAR, "varchar"),
            new ResultColumn(2, "odd\"label", Types.VARCHAR, "varchar"));

    @Test
    void bindsValuesAndPreservesLeftToRightParentheses() {
        RenderedFilterQuery query = new PgResultFilterSqlRenderer().render(
                "select id, name from users",
                COLUMNS,
                List.of(new FilterCondition(0, FilterConnector.AND, FilterOperator.GT, 10),
                        new FilterCondition(1, FilterConnector.OR, FilterOperator.CONTAINS, "a%_\\")));

        assertEquals("SELECT * FROM (\nselect id, name from users\n) AS \"dc_filter\" "
                        + "WHERE (\"dc_filter\".\"id\" OPERATOR(pg_catalog.>) ? OR "
                        + "\"dc_filter\".\"name\" OPERATOR(pg_catalog.~~) ?)",
                query.sql());
        assertEquals(List.of(
                new SqlParameter(Types.INTEGER, 10),
                new SqlParameter(Types.VARCHAR, "%a\\%\\_\\\\%")), query.parameters());
        SqlParameter pattern = query.parameters().get(1);
        assertEquals("SqlParameter[jdbcType=" + Types.VARCHAR + ", value=<redacted>]",
                pattern.toString());
        assertFalse(query.toString().contains(String.valueOf(pattern.value())));
        assertThrows(UnsupportedOperationException.class, query.parameters()::clear);
    }

    @Test
    void foldsThreeConditionsStrictlyFromLeftToRight() {
        RenderedFilterQuery query = new PgResultFilterSqlRenderer().render(
                "select id, name from users", COLUMNS, List.of(
                        condition(0, FilterConnector.AND, FilterOperator.GT, 10),
                        condition(1, FilterConnector.OR, FilterOperator.CONTAINS, "a"),
                        condition(2, FilterConnector.AND, FilterOperator.IS_NOT_NULL, null)));

        assertEquals("SELECT * FROM (\nselect id, name from users\n) AS \"dc_filter\" "
                        + "WHERE ((\"dc_filter\".\"id\" OPERATOR(pg_catalog.>) ? OR "
                        + "\"dc_filter\".\"name\" OPERATOR(pg_catalog.~~) ?) "
                        + "AND \"dc_filter\".\"odd\"\"label\" IS NOT NULL)",
                query.sql());
        assertEquals(List.of(new SqlParameter(Types.INTEGER, 10),
                new SqlParameter(Types.VARCHAR, "%a%")), query.parameters());
    }

    @Test
    void rendersEveryOperatorAndBindsInConditionOrder() {
        RenderedFilterQuery query = new PgResultFilterSqlRenderer().render(
                "select * from users", COLUMNS, List.of(
                        condition(0, FilterConnector.AND, FilterOperator.EQ, 1),
                        condition(0, FilterConnector.AND, FilterOperator.NE, 2),
                        condition(1, FilterConnector.OR, FilterOperator.STARTS_WITH, "a"),
                        condition(1, FilterConnector.AND, FilterOperator.ENDS_WITH, "z"),
                        condition(0, FilterConnector.AND, FilterOperator.GTE, 3),
                        condition(0, FilterConnector.AND, FilterOperator.LT, 4),
                        condition(0, FilterConnector.AND, FilterOperator.LTE, 5),
                        condition(1, FilterConnector.AND, FilterOperator.IS_NULL, null),
                        condition(2, FilterConnector.OR, FilterOperator.IS_NOT_NULL, null)));

        for (String operator : List.of("=", "<>", "~~", ">=", "<", "<=")) {
            assertTrue(query.sql().contains("OPERATOR(pg_catalog." + operator + ") ?"),
                    operator);
        }
        assertFalse(query.sql().contains(" LIKE "));
        assertFalse(query.sql().contains(" ESCAPE "));
        assertTrue(query.sql().contains("IS NULL"));
        assertTrue(query.sql().contains("\"odd\"\"label\" IS NOT NULL"));
        assertEquals(List.of(1, 2, "a%", "%z", 3, 4, 5),
                query.parameters().stream().map(SqlParameter::value).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void terminalLineCommentCannotConsumeWrapperSuffix(String lineEnding) {
        String original = "select id from users" + lineEnding + "-- terminal comment";

        RenderedFilterQuery query = new PgResultFilterSqlRenderer().render(
                original, COLUMNS,
                List.of(condition(0, FilterConnector.AND, FilterOperator.EQ, 7)));

        assertEquals("SELECT * FROM (\n" + original + "\n) AS \"dc_filter\" "
                + "WHERE \"dc_filter\".\"id\" OPERATOR(pg_catalog.=) ?", query.sql());
        assertEquals(List.of(new SqlParameter(Types.INTEGER, 7)), query.parameters());
    }

    @Test
    void rejectsUnknownColumnWithoutDisclosingItsValue() {
        String secret = "secret-filter-value";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new PgResultFilterSqlRenderer().render("select id from users", COLUMNS,
                        List.of(condition(9, FilterConnector.AND, FilterOperator.EQ, secret))));

        assertEquals("筛选列已不存在，无法执行数据库筛选；本地筛选仍可使用",
                failure.getMessage());
        assertFalse(failure.getMessage().contains(secret));
    }

    @Test
    void rejectsNonStringPatternValuesWithoutCallingTheirToString() {
        String secret = "secret-from-to-string";
        Object unsafe = new Object() {
            @Override
            public String toString() {
                return secret;
            }
        };

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new PgResultFilterSqlRenderer().render("select name from users", COLUMNS,
                        List.of(condition(1, FilterConnector.AND, FilterOperator.CONTAINS, unsafe))));

        assertFalse(failure.getMessage().contains(secret));
    }

    @Test
    void providerAdvertisesRendererCapability() {
        assertTrue(new PostgresProvider().resultFilterSqlRenderer().orElseThrow()
                instanceof PgResultFilterSqlRenderer);
    }

    @Test
    void sqlParameterBindsTypedValuesAndNulls() throws Exception {
        List<List<Object>> calls = new ArrayList<>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setObject") || method.getName().equals("setNull")) {
                        calls.add(List.of(method.getName(), arguments[0],
                                arguments.length == 3 ? arguments[1] : "<null>",
                                arguments[arguments.length - 1]));
                    }
                    return null;
                });

        new SqlParameter(Types.INTEGER, 42).bind(statement, 3);
        new SqlParameter(Types.VARCHAR, null).bind(statement, 4);

        assertEquals(List.of(
                List.of("setObject", 3, 42, Types.INTEGER),
                List.of("setNull", 4, "<null>", Types.VARCHAR)), calls);
    }

    private static FilterCondition condition(
            int column, FilterConnector connector, FilterOperator operator, Object value) {
        return new FilterCondition(column, connector, operator, value);
    }
}
