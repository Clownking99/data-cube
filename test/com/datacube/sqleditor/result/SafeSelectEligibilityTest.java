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
    private static final String POSTGRES_SUBSET_REASON =
            "该 PostgreSQL SELECT 超出可证明安全的无 FROM 基础字面量子集；本地筛选仍可使用";
    private static final String ORACLE_SUBSET_REASON =
            "该 Oracle SELECT 超出可证明安全的 SYS.DUAL 通配符子集；本地筛选仍可使用";

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

        TopLevelSqlTokens.Analysis analysis = TopLevelSqlTokens.analyze(
                sql.trim().substring(0, sql.trim().length() - 1).trim(), false);
        assertFalse(analysis.unprovenCallable());
        assertFalse(analysis.unsafeStructure());
        assertFalse(result.eligible());
        assertEquals(POSTGRES_SUBSET_REASON, result.reason());
    }

    @Test
    void ignoresOracleAlternativeQuotedTextWhileRejectingTheAmbiguousProjectionShape() {
        String sql = "select q'[FOR UPDATE; UNION]' text, nq'{SELECT INTO}' name from dual;";

        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, true, UNIQUE_RESULT);

        assertEquals(List.of("SELECT", "TEXT", "NAME", "FROM", "DUAL"),
                TopLevelSqlTokens.scan(sql, true));
        assertFalse(result.eligible());
        assertEquals(ORACLE_SUBSET_REASON, result.reason());
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
            "select arbitrary_udf(id) from users",
            "select app.arbitrary_udf(id) from users",
            "select \"arbitrary_udf\"(id) from users",
            "select app.\"arbitrary_udf\"(id) from users",
            "select \"危险调用\"(id) from users",
            "select 危险调用(id) from users",
            "select app.values() from users",
            "select by(1) from users",
            "select join(1) from users",
            "select on(1) from users",
            "select as(1) from users",
            "select values(1) from users",
            "select lower(name) from users",
            "select count(*) from users",
            "select coalesce(name, '') from users",
            "select arbitrary_udf /* trivia */ (id) from users",
            "select \"app\".\"arbitrary_udf\" /* trivia */ (id) from users",
            "select (select nested_udf(id) from users) as computed",
            "select * from (select nested_udf(id) from users) nested",
            "select set_config('search_path', 'public', false)",
            "select pg_notify('channel', 'payload')",
            "select dblink('service=remote', 'select 1')",
            "select pg_read_file('/tmp/data')",
            "select utl_http.request('https://example.invalid') from dual"
    })
    void rejectsEveryUnprovenCallableAtEveryNestingDepth(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, sql.contains("dual"), UNIQUE_RESULT);

        assertFalse(result.eligible(), sql);
        assertEquals("该 SELECT 包含无法证明安全的调用", result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select payload ## 1 from events",
            "select 1::dangerous_type",
            "select payload @@@ 1 from events",
            "select 1 || 2 from events"
    })
    void rejectsUnenumeratedPostgresOperatorAndCastSyntax(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        assertFalse(result.eligible(), sql);
        assertEquals("该 SELECT 结构不能安全包装", result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from (with q as (select 1) select * from q) nested",
            "select (with q as (select 1) select * from q) as nested",
            "select * from (select * from jobs for update) nested",
            "select * from (select * from jobs for no key update) nested",
            "select * from (select * from jobs for share) nested",
            "select * from (select * from jobs for key share) nested",
            "select * from (select * from jobs for update skip locked) nested",
            "select * from (select * from jobs for /* lock */ update) nested",
            "select * from (select * from jobs for /*x*/ no key /*x*/ update of jobs skip locked) nested",
            "select * from (select * into copied_jobs from jobs) nested",
            "select * from (select * from jobs lock in share mode) nested",
            "select * from (with /* cte */ q as (select 1) select * from q) nested",
            "select * from (/*x*/ with recursive q as (select 1) select * from q) nested"
    })
    void rejectsNestedCtesAndLockingClauses(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        assertFalse(result.eligible(), sql);
        assertEquals("该 SELECT 结构不能安全包装", result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select order_sequence.currval from dual",
            "select \"ORDER_SEQUENCE\".\"CURRVAL\" from dual"
    })
    void rejectsSequenceStateReferencesWithoutParentheses(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, true, UNIQUE_RESULT);

        assertFalse(result.eligible(), sql);
        assertEquals("该 SELECT 包含无法证明安全的调用", result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select side_effecting_zero_arg from dual",
            "select app.side_effecting_zero_arg from dual",
            "select app.pkg.side_effecting_zero_arg from dual",
            "select \"SIDE_EFFECTING_ZERO_ARG\" from dual",
            "select \"APP\".\"PKG\".\"SIDE_EFFECTING_ZERO_ARG\" from dual"
    })
    void rejectsAmbiguousOracleBareFunctionReferences(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, true, UNIQUE_RESULT);

        assertFalse(result.eligible(), sql);
        assertEquals(ORACLE_SUBSET_REASON, result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from SYS.DUAL",
            "select * from SYS.\"DUAL\"",
            "select * from \"SYS\".DUAL",
            "select * from \"SYS\".\"DUAL\"",
            "select DUAL.* from SYS.DUAL",
            "select d.* from SYS.DUAL d",
            "select \"D\".* from \"SYS\".\"DUAL\" \"D\""
    })
    void acceptsOnlyTheTrustedOracleSysDualWildcardSubset(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, true, UNIQUE_RESULT);

        assertTrue(result.eligible(), sql + ": " + result.reason());
        assertEquals(sql, result.normalizedSql());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from dual",
            "select d.* from DUAL d",
            "select * from \"DUAL\"",
            "select * from CURRENT_SCHEMA.DUAL",
            "select * from \"APP\".\"DUAL\"",
            "select * from \"sys\".\"DUAL\"",
            "select * from \"SYS\".\"dual\"",
            "select * from \"Sys\".\"Dual\"",
            "select * from users",
            "select users.* from users",
            "select u.* from app.users u",
            "select * from remote_synonym",
            "select v.* from app.side_effecting_view v",
            "select * from SYS.OTHER_OBJECT"
    })
    void rejectsEveryUnprovenOracleRelationViewSynonymOrPolicyShape(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, true, UNIQUE_RESULT);

        assertFalse(result.eligible(), sql);
        assertEquals(ORACLE_SUBSET_REASON, result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select 1",
            "select 1 AS id",
            "select (1) AS id",
            "select 'Ada' AS name",
            "select true AS active",
            "select false AS inactive, null AS missing",
            "select ((12345)) AS value",
            "select $$FROM views; dangerous_type 'payload'; payload + 1$$ AS text"
    })
    void acceptsOnlyNativePostgresLiteralProjectionsWithoutFrom(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        assertTrue(result.eligible(), sql + ": " + result.reason());
        assertEquals(sql, result.normalizedSql());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select * from people",
            "select id from app.people",
            "select * from security_barrier_view",
            "select t.* from people t",
            "select dangerous_type 'payload'",
            "select app.dangerous_type 'payload'",
            "select \"dangerous_type\" 'payload'",
            "select payload + 1 from events",
            "select id = 1 from events",
            "select id from events where id in (1, 2)",
            "select E'payload' AS text",
            "select date '2026-08-30' AS value"
    })
    void rejectsPostgresRelationsTypeInputRoutinesAndOverloadableExpressions(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        assertFalse(result.eligible(), sql);
        assertEquals(POSTGRES_SUBSET_REASON, result.reason());
    }

    @Test
    void rejectsOracleDatabaseLinkReferences() {
        SafeSelectEligibility.Result result = SafeSelectEligibility.check(
                "select id from remote_users@production_link", true, UNIQUE_RESULT);

        assertFalse(result.eligible());
        assertEquals("该 SELECT 结构不能安全包装", result.reason());
    }

    @Test
    void rejectsNestedOracleLockVariants() {
        SafeSelectEligibility.Result result = SafeSelectEligibility.check(
                "select * from (select * from jobs for update of id wait 1) nested",
                true, UNIQUE_RESULT);

        assertFalse(result.eligible());
        assertEquals("该 SELECT 结构不能安全包装", result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select id, (score + bonus) total from users where (active = true or active is null)",
            "select id, (score) grouped_score from users",
            "select nested.id from (select id from users where id in (1, 2)) nested",
            "select (select id from settings where settings.owner_id = users.id) setting_id from users",
            "select id from users where exists (select 1 from roles where roles.user_id = users.id)",
            "select id from users where id = any (select user_id from roles)",
            "select id from users where id = all (select user_id from roles)",
            "select id from users where id = some (select user_id from roles)",
            "select department from users group by (department)",
            "select id from users order by (id)",
            "select distinct(id) from users"
    })
    void recognizesPlainRelationalGrammarButRejectsItsUnprovenDatabaseObjects(String sql) {
        SafeSelectEligibility.Result result =
                SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

        TopLevelSqlTokens.Analysis analysis = TopLevelSqlTokens.analyze(sql, false);
        assertFalse(analysis.unprovenCallable(), sql);
        assertFalse(analysis.unsafeStructure(), sql);
        assertFalse(result.eligible(), sql);
        assertEquals(POSTGRES_SUBSET_REASON, result.reason());
    }

    @Test
    void rejectsUnsupportedControlCharactersInEveryLexicalRegion() {
        for (char control : new char[]{'\u0000', '\u0001'}) {
            for (String sql : List.of(
                    "select 1" + control + " from users",
                    "select 'literal" + control + "'",
                    "select E'escaped\\" + control + "still'",
                    "select \"identifier" + control + "\" from users",
                    "select 1 /* comment" + control + " */")) {
                SafeSelectEligibility.Result result =
                        SafeSelectEligibility.check(sql, false, UNIQUE_RESULT);

                assertFalse(result.eligible(), Integer.toString(control));
                assertEquals("SQL 结构不能安全识别", result.reason());
            }
        }
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
                assertFalse(SafeSelectEligibility.check(sql, true, UNIQUE_RESULT).eligible());
            }
        }
    }

    @Test
    void recognizesPairedAndSingleCharacterOracleQuotesWithoutAdmittingTheirProjectionShape() {
        for (String literal : List.of(
                "q'[FOR UPDATE]'", "q'{UNION}'", "q'(SELECT INTO)'",
                "q'<WITH>'", "q'!FOR SHARE!'", "nq'#MINUS#'")) {
            SafeSelectEligibility.Result result = SafeSelectEligibility.check(
                    "select " + literal + " value from dual", true, UNIQUE_RESULT);

            assertEquals(List.of("SELECT", "VALUE", "FROM", "DUAL"),
                    TopLevelSqlTokens.scan(
                            "select " + literal + " value from dual", true));
            assertFalse(result.eligible(), literal);
            assertEquals(ORACLE_SUBSET_REASON, result.reason());
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
