package com.datacube.sqleditor.result;

import com.datacube.spi.SqlResultBudget;
import com.datacube.spi.model.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PinnedResultStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T08:00:00Z"), ZoneOffset.UTC);
    private static final PinnedResultStore.Source SOURCE = new PinnedResultStore.Source("T", "S", "Q", false);
    private static final ResultColumn COLUMN = new ResultColumn(0, "v", Types.VARCHAR, "V");

    @Test void occurrencesAreIndependentAndCapacityReclaimsOnlyExplicitlyRemovedEntries() {
        var store = new PinnedResultStore(SqlResultBudget.DEFAULT, CLOCK);
        var mutable = new ArrayList<Object>(List.of("before"));
        var result = QueryResult.queryWithMetadata(List.of(COLUMN), List.of(mutable), 1, false);
        var a = store.pin(result, SOURCE); var b = store.pin(result, SOURCE); var c = store.pin(result, SOURCE);
        mutable.set(0, "after");
        assertNotSame(a, b); assertNotEquals(a.id(), b.id());
        assertEquals(CLOCK.instant(), a.pinnedAt()); assertEquals(List.of("before"), a.result().rows.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> a.result().rows.getFirst().set(0, "bad"));
        assertThrows(IllegalStateException.class, () -> store.pin(result, SOURCE));
        assertEquals(List.of(a, b, c), store.entries());
        assertTrue(store.remove(b)); assertFalse(store.remove(b));
        var d = store.pin(result, SOURCE);
        assertTrue(d.id() > c.id()); assertEquals(List.of(a, c, d), store.entries());
        assertEquals(new PinnedResultStore.Usage(3, 3, 3, 33), store.usage());
        store.clear(); assertEquals(new PinnedResultStore.Usage(0, 0, 0, 0), store.usage());
        assertFalse(store.contains(a)); assertFalse(store.remove(a));
        assertEquals(1, store.pin(result, SOURCE).result().rows.size());
        store.close(); assertTrue(store.entries().isEmpty());
        assertThrows(IllegalStateException.class, () -> store.pin(result, SOURCE));
    }

    @Test void aggregateTextBudgetIncludesSourceMetadataCommentsNumbersAndPreviewPrefixes() {
        // Each result: source=3, metadata=2, cell=4; exactly two consume 18 units.
        var store = new PinnedResultStore(new SqlResultBudget.Limits(3, 4, 4, 1, 18, 10), CLOCK);
        var result = query("1234"); var a = store.pin(result, SOURCE); var b = store.pin(result, SOURCE);
        assertEquals(18, store.usage().textUnits());
        assertThrows(IllegalStateException.class, () -> store.pin(query(""), SOURCE));
        assertEquals(List.of(a, b), store.entries()); store.remove(a);
        assertThrows(IllegalStateException.class, () -> store.pin(result.withColumnComments(List.of("x")), SOURCE));
        assertEquals(List.of(b), store.entries());
        store.clear(); store.pin(query(new BigDecimal("12.3")), SOURCE); store.pin(query(new ResultValuePreview("1234")), SOURCE);
        assertEquals(18, store.usage().textUnits());
        assertThrows(IllegalStateException.class, () -> store.pin(query(null), SOURCE));
    }

    @ParameterizedTest @ValueSource(strings = {"rows", "cells", "columns", "text", "sql", "schema", "target", "comment", "type", "label", "notice", "decimal", "integer", "preview", "aggregate", "shape"})
    void overBudgetOrUnknownValuesRejectAtomicallyWithoutEvicting(String kind) {
        var store = new PinnedResultStore(SqlResultBudget.DEFAULT, CLOCK);
        var old = store.pin(query("old"), SOURCE); var usage = store.usage();
        QueryResult result = query("safe"); var source = SOURCE;
        switch (kind) {
            case "rows" -> result = QueryResult.queryWithMetadata(List.of(COLUMN), java.util.Collections.nCopies(50_001, List.of(1)), 1, false);
            case "cells" -> result = QueryResult.query(List.of("a", "b", "c", "d", "e", "f"), java.util.Collections.nCopies(42_000, List.of(1, 2, 3, 4, 5, 6)), 1);
            case "columns" -> result = QueryResult.query(java.util.Collections.nCopies(257, "c"), List.of(), 1);
            case "text" -> result = query("x".repeat(4097));
            case "sql" -> source = new PinnedResultStore.Source("T", "S", "s".repeat(16_385), false);
            case "schema" -> source = new PinnedResultStore.Source("T", "s".repeat(513), "Q", false);
            case "target" -> source = new PinnedResultStore.Source("t".repeat(513), "S", "Q", false);
            case "comment" -> result = result.withColumnComments(List.of("c".repeat(513)));
            case "type" -> result = QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "v", Types.VARCHAR, "t".repeat(129))), List.of(), 1, false);
            case "label" -> result = QueryResult.query(List.of("c".repeat(513)), List.of(), 1);
            case "notice" -> result = result.withRetentionNotice("n".repeat(4097));
            case "decimal" -> result = query(new BigDecimal("1".repeat(4097)));
            case "integer" -> result = query(BigInteger.ONE.shiftLeft(100_000));
            case "preview" -> result = query(new ResultValuePreview("p".repeat(4097)));
            case "aggregate" -> result = query(new int[]{1, 2});
            case "shape" -> result = QueryResult.query(List.of("a", "b"), List.of(List.of(1)), 1);
        }
        var candidate = result; var candidateSource = source;
        assertThrows(RuntimeException.class, () -> store.pin(candidate, candidateSource));
        assertEquals(List.of(old), store.entries()); assertEquals(usage, store.usage());
    }

    @ParameterizedTest @ValueSource(strings = {"rows", "cells", "columns"})
    void sharedShapeBudgetAcceptsExactLimitAndRejectsNextUnit(String boundary) {
        var limits = switch (boundary) {
            case "rows" -> new SqlResultBudget.Limits(3, 2, 20, 5, 100, 10);
            case "cells" -> new SqlResultBudget.Limits(3, 20, 2, 5, 100, 10);
            default -> new SqlResultBudget.Limits(3, 20, 20, 1, 100, 10);
        };
        var store = new PinnedResultStore(limits, CLOCK);
        store.pin(query(null), SOURCE);
        if (!boundary.equals("columns")) {
            store.pin(query(null), SOURCE);
            assertThrows(IllegalStateException.class, () -> store.pin(query(null), SOURCE));
            assertEquals(2, store.usage().results());
        } else {
            assertThrows(IllegalStateException.class, () -> store.pin(QueryResult.query(List.of("a", "b"), List.of(), 1), SOURCE));
            assertEquals(1, store.usage().results());
        }
    }

    @Test void emptyNullUnicodePartialAndDatabaseFilteredSourcesStayExplicitAndInert() {
        var store = new PinnedResultStore();
        var partial = QueryResult.queryWithMetadata(List.of(COLUMN), List.of(Arrays.asList((Object) null), List.of(""), List.of("\uD83D\uDE00"), List.of(new ResultValuePreview("part"))), 3, true)
                .withColumnComments(List.of("comment")).withRetentionNotice("SQL 仅保留前缀");
        var source = new PinnedResultStore.Source("synthetic-target", "source_schema", "select secret", true);
        var entry = store.pin(partial, source);
        assertSame(partial, entry.result()); assertTrue(entry.result().truncated);
        assertEquals("SQL 仅保留前缀", entry.result().retentionNotice);
        assertNull(entry.result().rows.getFirst().getFirst());
        assertEquals("\uD83D\uDE00", entry.result().rows.get(2).getFirst());
        assertTrue(entry.source().databaseFiltered()); assertEquals("source_schema", entry.source().schema());
        assertFalse(source.toString().contains("secret")); assertFalse(entry.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> store.pin(QueryResult.update(1, 1), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> store.pin(QueryResult.error("e", 1), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> store.pin(null, SOURCE));
        assertEquals(1, store.pin(QueryResult.query(List.of("empty"), List.of(), 1), SOURCE).result().columns.size());
    }

    private static QueryResult query(Object value) {
        return QueryResult.queryWithMetadata(List.of(COLUMN), List.of(Arrays.asList(value)), 1, false);
    }
}
