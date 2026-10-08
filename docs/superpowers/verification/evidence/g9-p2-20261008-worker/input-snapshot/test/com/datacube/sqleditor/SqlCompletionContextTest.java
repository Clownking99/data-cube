package com.datacube.sqleditor;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlCompletionContextTest {
    private SqlCompletionContext.Relation at(String sql, String marker, String qualifier) {
        return SqlCompletionContext.resolve(sql, sql.indexOf(marker) + marker.length(), qualifier, false, "default_schema");
    }
    @Test void preservesExplicitSchemaAndQuotedCaseAndEscapes() {
        var source = at("select a. from \"Mixed Schema\".\"T\"\"Name\" a", "a.", "a");
        assertEquals("Mixed Schema", source.schema()); assertEquals("T\"Name", source.table());
        assertTrue(source.physical());
        assertEquals("a", at("select x. from A.T x", "x.", "x").schema());
        assertEquals("\"T\"\"Name\"", SqlCompletionContext.identifier("T\"Name", false));
    }
    @Test void nestedScopesShadowAndDoNotLeakFromSiblingsOrOtherStatements() {
        String sql = "select x.outercol, (select x.innercol from inner_schema.inner_table x) from outer_schema.outer_table x";
        assertEquals("inner_table", at(sql, "x.inner", "x").table());
        assertEquals("outer_table", at(sql, "x.outer", "x").table());
        assertEquals("outer_table", at("select (select x.c from s.local l) from s.outer_table x", "x.c", "x").table());
        assertFalse(at("select 1 from wrong.table x; select x.c from s.right_table y", "x.c", "x").physical());
        assertFalse(at("select x.c from s.one x join s.two x on true", "x.c", "x").physical());
        assertFalse(at("select x.c from s.one \"X\"", "x.c", "x").physical());
    }
    @Test void ctesAndDerivedTablesUseOnlyProvableOutputNames() {
        var cte = at("with q(\"Named\", amount) as (select 1, 2) select q. from q", "q.", "q");
        assertEquals(List.of("\"Named\"", "\"amount\""), cte.columns());
        assertFalse(cte.physical());
        var inferred = at("with q as (select a.id, count(*) AS total from s.t a) select q. from q", "q.", "q");
        assertEquals(List.of("\"id\"", "\"total\""), inferred.columns());
        assertEquals(List.of("\"alias\""), at("select d. from (select 1 as alias) d", "d.", "d").columns());
        assertTrue(at("with q as (select *) select q. from q", "q.", "q").columns().isEmpty());
        assertTrue(at("select d. from (select 1) d", "d.", "d").columns().isEmpty());
    }
    @Test void commentsStringsAndUnsupportedSourcesNeverManufactureTables() {
        assertFalse(at("select x.c /* from secrets.hidden x */ from s.real r", "x.c", "x").physical());
        assertFalse(at("select x.c from f() x", "x.c", "x").physical());
        assertFalse(SqlCompletionContext.resolve("select x.c from t x", 9, "x", false, null).physical());
        assertEquals("Explicit", SqlCompletionContext.resolve("select 1", 5, "\"Explicit\".\"Table\"", false, null).schema());
        assertFalse(at("select x.c from s.first x union select x.c from s.second x", "x.c", "x").physical());
    }
    @Test void unaliasedLiteralsNeverInventOutputColumnNames() {
        for (String literal : List.of("NULL", "TRUE", "FALSE")) {
            assertTrue(at("with q as (select " + literal + ") select q. from q", "q.", "q").columns().isEmpty(), literal);
            assertEquals(List.of("\"value\""), at("with q as (select " + literal + " as value) select q. from q", "q.", "q").columns());
        }
        assertEquals(List.of("\"NULL\""), at("with q as (select \"NULL\" from s.t) select q. from q", "q.", "q").columns());
    }
    @Test void unfinishedQuotedSourceCannotBeTruncatedIntoAnotherTable() {
        assertFalse(at("select \"table\". from s.\"tableX", "\"table\".", "\"table\"").physical());
        assertFalse(at("select \"a\". from s.t as \"aX", "\"a\".", "\"a\"").physical());
        assertEquals("tableX", at("select \"tableX\". from s.\"tableX\"", "\"tableX\".", "\"tableX\"").table());
    }
    @Test void replacementRangeIncludesQuotedPrefixAndWholeQualifiedName() {
        String text = "select \"Schema\".\"Table\".\"Co";
        var input = SqlCompletionContext.input(text, text.length(), false);
        assertTrue(input.allowed()); assertEquals("\"Co", input.prefix());
        assertEquals("\"Schema\".\"Table\"", input.qualifier());
        assertEquals(text.lastIndexOf("\"Co"), input.start());
        for (String sql : List.of("select 'a.", "select $$a.", "select 1 -- a.", "select /* a.", "select q'[a."))
            assertFalse(SqlCompletionContext.input(sql, sql.length(), sql.contains("q'")).allowed(), sql);
    }
    @Test void oracleFoldingRespectsQuotedIdentifiers() {
        var relation = SqlCompletionContext.resolve("select a.c from \"Mixed\".orders a", 9, "a", true, "APP");
        assertEquals("Mixed", relation.schema()); assertEquals("ORDERS", relation.table());
    }
    @Test void cteBodiesAndNonLateralDerivedTablesCannotSeeOuterFromAliases() {
        assertFalse(at("with q as (select x.c) select * from s.t x", "x.c", "x").physical());
        assertFalse(at("select * from s.t x, (select x.c) d", "x.c", "x").physical());
        assertEquals("t", at("select * from s.t x, lateral (select x.c) d", "x.c", "x").table());
        assertTrue(at("with earlier as (select f.c from future f), future as (select 1 as future_col) select 1", "f.c", "f").columns().isEmpty());
        assertTrue(at("with q as (select 1 as n union select 2 as wrong) select q. from q", "q.", "q").columns().isEmpty());
    }
}
