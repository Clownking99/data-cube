package com.datacube.spi.model;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Types;
import java.util.List;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.junit.jupiter.api.Test;

class QueryResultMetadataTest {
    @Test
    void queryWithMetadataPreservesLabelsTypesNullsAndTruncation() {
        ResultColumn id = new ResultColumn(0, "ID", Types.BIGINT, "BIGINT");
        ResultColumn note = new ResultColumn(1, "NOTE", Types.VARCHAR, "VARCHAR");

        QueryResult result = QueryResult.queryWithMetadata(
                List.of(id, note), List.of(java.util.Arrays.asList(7L, null)), 12, true);

        assertEquals(List.of("ID", "NOTE"), result.columns);
        assertEquals(List.of(id, note), result.resultColumns);
        assertNull(result.rows.getFirst().get(1));
        assertTrue(result.truncated);
        assertEquals(12, result.elapsedMillis);

        QueryResult commented = result.withColumnComments(List.of("identifier", "note"));
        assertEquals(result.resultColumns, commented.resultColumns);
        assertTrue(commented.truncated);
    }

    @Test
    void legacyFactoryCreatesUnknownMetadataWithoutClaimingTruncation() {
        QueryResult result = QueryResult.query(
                List.of("VALUE"), List.of(List.of("x")), 3);

        assertEquals(Types.OTHER, result.resultColumns.getFirst().jdbcType());
        assertFalse(result.truncated);
    }

    @Test
    void resultSetReaderMarksTruncatedOnlyAfterObservingAnExtraRow() throws Exception {
        QueryResult limited = QueryResult.fromResultSet(resultSetWithRows(3), 1, 2);
        QueryResult exact = QueryResult.fromResultSet(resultSetWithRows(2), 1, 2);

        assertEquals(2, limited.rows.size());
        assertTrue(limited.truncated);
        assertEquals(2, exact.rows.size());
        assertFalse(exact.truncated);
    }

    private static CachedRowSet resultSetWithRows(int count) throws java.sql.SQLException {
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(1);
        metadata.setColumnName(1, "ID");
        metadata.setColumnLabel(1, "ID");
        metadata.setColumnType(1, Types.INTEGER);
        metadata.setColumnTypeName(1, "INTEGER");
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        rows.setMetaData(metadata);
        for (int value = 1; value <= count; value++) {
            rows.moveToInsertRow();
            rows.updateInt(1, value);
            rows.insertRow();
            rows.moveToCurrentRow();
        }
        rows.beforeFirst();
        return rows;
    }
}
