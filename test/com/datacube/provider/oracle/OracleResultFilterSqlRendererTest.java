package com.datacube.provider.oracle;

import com.datacube.spi.SqlParameter;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.RenderedFilterQuery;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleResultFilterSqlRendererTest {
    private static final List<ResultColumn> COLUMNS = List.of(
            new ResultColumn(0, "ID", Types.INTEGER, "NUMBER"),
            new ResultColumn(1, "NAME", Types.VARCHAR, "VARCHAR2"));

    @Test
    void omitsAsBeforeDerivedTableAliasAndKeepsParametersOutOfSql() {
        RenderedFilterQuery query = new OracleResultFilterSqlRenderer().render(
                "select ID, NAME from USERS", COLUMNS,
                List.of(new FilterCondition(0, FilterConnector.AND, FilterOperator.GT, 10),
                        new FilterCondition(1, FilterConnector.OR, FilterOperator.CONTAINS, "a%_")));

        assertEquals("SELECT * FROM (select ID, NAME from USERS) \"dc_filter\" "
                        + "WHERE (\"dc_filter\".\"ID\" > ? OR \"dc_filter\".\"NAME\" LIKE ? ESCAPE '\\')",
                query.sql());
        assertEquals(List.of(
                new SqlParameter(Types.INTEGER, 10),
                new SqlParameter(Types.VARCHAR, "%a\\%\\_%")), query.parameters());
        assertTrue(query.sql().chars().filter(character -> character == '?').count() == 2);
    }

    @Test
    void providerAdvertisesRendererCapability() {
        assertTrue(new OracleProvider().resultFilterSqlRenderer().orElseThrow()
                instanceof OracleResultFilterSqlRenderer);
    }
}
