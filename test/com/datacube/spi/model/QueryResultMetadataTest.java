package com.datacube.spi.model;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
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

    @Test
    void resultSetReaderPreservesJdbcMetadataTimestampAndBinaryValues() throws Exception {
        Timestamp timestamp = Timestamp.valueOf("2026-08-29 12:34:56.123456789");
        byte[] binary = new byte[65];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) i;
        CachedRowSet rows = resultSetWithTypedRow(timestamp, binary);

        QueryResult result = QueryResult.fromResultSet(rows, 9, 0);

        assertEquals(List.of(
                new ResultColumn(0, "event_time", Types.TIMESTAMP, "TIMESTAMP"),
                new ResultColumn(1, "payload", Types.VARBINARY, "VARBINARY")), result.resultColumns);
        assertEquals(1, result.rows.size());
        assertInstanceOf(Timestamp.class, result.rows.getFirst().get(0));
        assertEquals(timestamp, result.rows.getFirst().get(0));
        assertInstanceOf(byte[].class, result.rows.getFirst().get(1));
        assertArrayEquals(binary, (byte[]) result.rows.getFirst().get(1));
        assertEquals(1, QueryResult.fromResultSet(resultSetWithRows(1), 0, -1).rows.size());
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

    private static CachedRowSet resultSetWithTypedRow(Timestamp timestamp, byte[] binary)
            throws java.sql.SQLException {
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(2);
        metadata.setColumnName(1, "EVENT_TIME");
        metadata.setColumnLabel(1, "event_time");
        metadata.setColumnType(1, Types.TIMESTAMP);
        metadata.setColumnTypeName(1, "TIMESTAMP");
        metadata.setColumnName(2, "PAYLOAD");
        metadata.setColumnLabel(2, "payload");
        metadata.setColumnType(2, Types.VARBINARY);
        metadata.setColumnTypeName(2, "VARBINARY");
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        rows.setMetaData(metadata);
        rows.moveToInsertRow();
        rows.updateTimestamp(1, timestamp);
        rows.updateBytes(2, binary);
        rows.insertRow();
        rows.moveToCurrentRow();
        rows.beforeFirst();
        return rows;
    }
}
