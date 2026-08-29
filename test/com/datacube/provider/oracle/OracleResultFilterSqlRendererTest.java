package com.datacube.provider.oracle;

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

class OracleResultFilterSqlRendererTest {
    private static final List<ResultColumn> COLUMNS = List.of(
            new ResultColumn(0, "ID", Types.INTEGER, "NUMBER"),
            new ResultColumn(1, "NAME", Types.VARCHAR, "VARCHAR2"),
            new ResultColumn(2, "ODD\"LABEL", Types.VARCHAR, "VARCHAR2"));

    @Test
    void omitsAsBeforeDerivedTableAliasAndKeepsParametersOutOfSql() {
        RenderedFilterQuery query = new OracleResultFilterSqlRenderer().render(
                "select ID, NAME from USERS", COLUMNS,
                List.of(new FilterCondition(0, FilterConnector.AND, FilterOperator.GT, 10),
                        new FilterCondition(1, FilterConnector.OR, FilterOperator.CONTAINS, "a%_")));

        assertEquals("SELECT * FROM (\nselect ID, NAME from USERS\n) \"dc_filter\" "
                        + "WHERE (\"dc_filter\".\"ID\" > ? OR \"dc_filter\".\"NAME\" LIKE ? ESCAPE '\\')",
                query.sql());
        assertEquals(List.of(
                new SqlParameter(Types.INTEGER, 10),
                new SqlParameter(Types.VARCHAR, "%a\\%\\_%")), query.parameters());
        assertTrue(query.sql().chars().filter(character -> character == '?').count() == 2);
        SqlParameter pattern = query.parameters().get(1);
        assertEquals("SqlParameter[jdbcType=" + Types.VARCHAR + ", value=<redacted>]",
                pattern.toString());
        assertFalse(query.toString().contains(String.valueOf(pattern.value())));
        assertThrows(UnsupportedOperationException.class, query.parameters()::clear);
    }

    @Test
    void foldsThreeConditionsStrictlyFromLeftToRight() {
        RenderedFilterQuery query = new OracleResultFilterSqlRenderer().render(
                "select ID, NAME from USERS", COLUMNS, List.of(
                        condition(0, FilterConnector.AND, FilterOperator.GT, 10),
                        condition(1, FilterConnector.OR, FilterOperator.CONTAINS, "a"),
                        condition(2, FilterConnector.AND, FilterOperator.IS_NOT_NULL, null)));

        assertEquals("SELECT * FROM (\nselect ID, NAME from USERS\n) \"dc_filter\" "
                        + "WHERE ((\"dc_filter\".\"ID\" > ? OR \"dc_filter\".\"NAME\" LIKE ? ESCAPE '\\') "
                        + "AND \"dc_filter\".\"ODD\"\"LABEL\" IS NOT NULL)",
                query.sql());
        assertEquals(List.of(new SqlParameter(Types.INTEGER, 10),
                new SqlParameter(Types.VARCHAR, "%a%")), query.parameters());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void terminalLineCommentCannotConsumeWrapperSuffix(String lineEnding) {
        String original = "select ID from USERS" + lineEnding + "-- terminal comment";

        RenderedFilterQuery query = new OracleResultFilterSqlRenderer().render(
                original, COLUMNS,
                List.of(condition(0, FilterConnector.AND, FilterOperator.EQ, 7)));

        assertEquals("SELECT * FROM (\n" + original + "\n) \"dc_filter\" "
                + "WHERE \"dc_filter\".\"ID\" = ?", query.sql());
        assertEquals(List.of(new SqlParameter(Types.INTEGER, 7)), query.parameters());
    }

    @Test
    void providerAdvertisesRendererCapability() {
        assertTrue(new OracleProvider().resultFilterSqlRenderer().orElseThrow()
                instanceof OracleResultFilterSqlRenderer);
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
