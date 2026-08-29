package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeSelectEligibilityTest {
    private static final QueryResult UNIQUE_RESULT = result("id", "name");

    @ParameterizedTest
    @ValueSource(strings = {
            "update users set active = false",
            "select 1; select 2",
            "with q as (select 1) select * from q",
            "select id from a union select id from b",
            "select id from a intersect select id from b",
            "select id from a except select id from b",
            "select id from a minus select id from b",
            "select * into copied_users from users",
            "select * from jobs for update",
            "select * from jobs for no key update",
            "select * from jobs for share",
            "select * from jobs for key share"
    })
    void rejectsSqlThatCannotBeProvedSafeToWrap(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        assertFalse(result.eligible());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void ignoresPostgresLexicalDecoysAndNestedSetOperations() {
        String sql = "  select 'union; for update' as text, \"WITH\" "
                + "from (select 1 union select 2) nested "
                + "/* outer /* SELECT INTO */ still comment */ "
                + "where $$FOR UPDATE; UNION$$ = $tag$FOR SHARE$tag$ -- SELECT INTO\n;  ";

        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        assertTrue(result.eligible(), result.reason());
        assertEquals(sql.trim().substring(0, sql.trim().length() - 1).trim(), result.normalizedSql());
        assertEquals("", result.reason());
    }

    @Test
    void ignoresOracleAlternativeQuotedText() {
        String sql = "select q'[FOR UPDATE; UNION]' text, nq'{SELECT INTO}' name from dual;";

        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, true, UNIQUE_RESULT);

        assertTrue(result.eligible(), result.reason());
        assertEquals("select q'[FOR UPDATE; UNION]' text, nq'{SELECT INTO}' name from dual",
                result.normalizedSql());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select 'unterminated",
            "select \"unterminated",
            "select /* unterminated",
            "select $tag$unterminated",
            "select (1",
            "select 1)"
    })
    void rejectsMalformedOrStructurallyUncertainSql(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        assertFalse(result.eligible());
        assertFalse(result.reason().isBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select nextval('order_sequence')",
            "select setval('order_sequence', 7)",
            "select pg_advisory_lock(7)",
            "select pg_advisory_xact_lock(7)",
            "select pg_try_advisory_lock(7)",
            "select pg_advisory_lock_shared(7)",
            "select pg_try_advisory_xact_lock_shared(7)",
            "select pg_advisory_unlock(7)",
            "select pg_advisory_unlock_all()",
            "select coalesce(nextval('order_sequence'), 0)",
            "select (select pg_advisory_lock(7))",
            "select pg_catalog.\"nextval\"('order_sequence')",
            "select order_sequence.nextval from dual",
            "select dbms_lock.request(7) from dual"
    })
    void rejectsKnownSelectSideEffects(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, sql.contains("dual"), UNIQUE_RESULT);

        assertFalse(result.eligible());
        assertFalse(result.reason().isBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select U&\"nextv\\0061l\"('order_sequence')",
            "select pg_catalog.U&\"setv\\0061l\"('order_sequence', 7)",
            "select coalesce(u&\"pg_\\0061dvisory_lock\"(7), 0)",
            "select (select U&\"PG_\\0061DVISORY_LOCK\"(7))",
            "select U&\"ordinary_name\" from users"
    })
    void rejectsPostgresUnicodeQuotedIdentifiersWithoutAttemptingToDecodeThem(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        assertFalse(result.eligible());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void rejectsWhitespaceAndControlOracleAlternativeQuoteDelimitersInBothScans() {
        for (String prefix : List.of("q", "nq")) {
            for (String delimiter : List.of(" ", "\t", "\r", "\n", "\u0001")) {
                String sql = "select " + prefix + "'" + delimiter
                        + "FOR UPDATE" + delimiter + "' value from dual";

                assertThrows(IllegalArgumentException.class,
                        () -> TopLevelSqlTokens.scan(sql, true));
                assertThrows(IllegalArgumentException.class,
                        () -> TopLevelSqlTokens.containsKnownSideEffectInvocation(sql, true));
                assertFalse(SafeSelectEligibility.check(sql, true, UNIQUE_RESULT).eligible());
            }
        }
    }

    @Test
    void acceptsPairedAndSingleCharacterOracleAlternativeQuoteDelimiters() {
        for (String literal : List.of(
                "q'[FOR UPDATE]'", "q'{UNION}'", "q'(SELECT INTO)'",
                "q'<WITH>'", "q'!FOR SHARE!'", "nq'#MINUS#'")) {
            SafeSelectEligibility.Result result = SafeSelectEligibility.check(
                    "select " + literal + " value from dual", true, UNIQUE_RESULT);

            assertTrue(result.eligible(), literal + ": " + result.reason());
        }
    }

    @Test
    void rejectsUnclosedOracleAlternativeQuote() {
        SafeSelectEligibility.Result result = SafeSelectEligibility.check(
                "select q'[FOR UPDATE' from dual", true, UNIQUE_RESULT);

        assertFalse(result.eligible());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void rejectsBlankOrDuplicateResultLabels() {
        assertFalse(SafeSelectEligibility.check("select 1", false, result("")).eligible());
        assertFalse(SafeSelectEligibility.check("select 1, 2", false, result("id", "id")).eligible());
        assertTrue(SafeSelectEligibility.check("select 1, 2", false, result("id", "ID")).eligible());
    }

    private static QueryResult result(String... labels) {
        List<ResultColumn> columns = java.util.stream.IntStream.range(0, labels.length)
                .mapToObj(index -> new ResultColumn(index, labels[index], Types.VARCHAR, "VARCHAR"))
                .toList();
        return QueryResult.queryWithMetadata(columns, List.of(), 0, false);
    }
}
