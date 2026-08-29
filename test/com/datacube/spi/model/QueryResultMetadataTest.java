package com.datacube.spi.model;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.sql.Types;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.postgresql.util.PGobject;
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

    @Test
    void resultSetReaderExtractsPostgresJsonAsTextWithoutChangingOtherValues() throws Exception {
        String jsonText = "{\"name\":\"Ada\",\"active\":true}";
        PGobject driverJson = new PGobject();
        driverJson.setType("jsonb");
        driverJson.setValue(jsonText);
        UUID uuid = UUID.fromString("48b46e93-674d-49c4-8e97-50fcb72f99df");
        AtomicInteger jsonGetObjectCalls = new AtomicInteger();
        AtomicInteger jsonGetStringCalls = new AtomicInteger();
        AtomicInteger uuidGetObjectCalls = new AtomicInteger();
        AtomicInteger uuidGetStringCalls = new AtomicInteger();

        QueryResult result = QueryResult.fromResultSet(otherTypesResultSet(
                driverJson, jsonText, uuid,
                jsonGetObjectCalls, jsonGetStringCalls,
                uuidGetObjectCalls, uuidGetStringCalls), 6, 0);

        assertEquals(List.of(
                new ResultColumn(0, "document", Types.OTHER, "JsOnB"),
                new ResultColumn(1, "identifier", Types.OTHER, "uuid")), result.resultColumns);
        assertEquals(jsonText, result.rows.getFirst().getFirst());
        assertInstanceOf(String.class, result.rows.getFirst().getFirst());
        assertSame(uuid, result.rows.getFirst().get(1),
                "non-JSON OTHER values must preserve their JDBC object identity");
        assertEquals(0, jsonGetObjectCalls.get(),
                "the driver PGobject must never enter immutable result state");
        assertEquals(1, jsonGetStringCalls.get());
        assertEquals(1, uuidGetObjectCalls.get());
        assertEquals(0, uuidGetStringCalls.get());
    }

    @Test
    void resultSetReaderRecognizesTheExactJsonTypeName() throws Exception {
        PGobject driverJson = pgObject("json", "{\"kind\":\"exact\"}");
        AtomicInteger getObjectCalls = new AtomicInteger();
        AtomicInteger getStringCalls = new AtomicInteger();

        QueryResult result = QueryResult.fromResultSet(singleOtherTypeResultSet(
                "json", driverJson, driverJson.getValue(), getObjectCalls, getStringCalls), 1, 0);

        assertEquals(driverJson.getValue(), result.rows.getFirst().getFirst());
        assertInstanceOf(String.class, result.rows.getFirst().getFirst());
        assertEquals(0, getObjectCalls.get());
        assertEquals(1, getStringCalls.get());
    }

    @Test
    void resultSetReaderDoesNotTreatNearJsonTypeNamesAsJson() throws Exception {
        for (String typeName : List.of("jsonpath", "jsonb ", " json")) {
            PGobject driverValue = pgObject(typeName, "{\"kind\":\"near\"}");
            AtomicInteger getObjectCalls = new AtomicInteger();
            AtomicInteger getStringCalls = new AtomicInteger();

            QueryResult result = QueryResult.fromResultSet(singleOtherTypeResultSet(
                    typeName, driverValue, driverValue.getValue(),
                    getObjectCalls, getStringCalls), 1, 0);

            assertSame(driverValue, result.rows.getFirst().getFirst(), typeName);
            assertEquals(typeName, result.resultColumns.getFirst().jdbcTypeName());
            assertEquals(1, getObjectCalls.get(), typeName);
            assertEquals(0, getStringCalls.get(), typeName);
        }
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

    private static ResultSet otherTypesResultSet(
            PGobject driverJson, String jsonText, UUID uuid,
            AtomicInteger jsonGetObjectCalls, AtomicInteger jsonGetStringCalls,
            AtomicInteger uuidGetObjectCalls, AtomicInteger uuidGetStringCalls) {
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSetMetaData.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> 2;
                    case "getColumnLabel" -> (int) args[0] == 1 ? "document" : "identifier";
                    case "getColumnType" -> Types.OTHER;
                    case "getColumnTypeName" -> (int) args[0] == 1 ? "JsOnB" : "uuid";
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    default -> defaultValue(method.getReturnType());
                });
        AtomicBoolean beforeRow = new AtomicBoolean(true);
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> beforeRow.getAndSet(false);
                    case "getObject" -> {
                        int index = (int) args[0];
                        if (index == 1) {
                            jsonGetObjectCalls.incrementAndGet();
                            yield driverJson;
                        }
                        uuidGetObjectCalls.incrementAndGet();
                        yield uuid;
                    }
                    case "getString" -> {
                        int index = (int) args[0];
                        if (index == 1) {
                            jsonGetStringCalls.incrementAndGet();
                            yield jsonText;
                        }
                        uuidGetStringCalls.incrementAndGet();
                        yield uuid.toString();
                    }
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static PGobject pgObject(String typeName, String value) throws java.sql.SQLException {
        PGobject object = new PGobject();
        object.setType(typeName);
        object.setValue(value);
        return object;
    }

    private static ResultSet singleOtherTypeResultSet(
            String typeName, Object objectValue, String stringValue,
            AtomicInteger getObjectCalls, AtomicInteger getStringCalls) {
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSetMetaData.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> 1;
                    case "getColumnLabel" -> "value";
                    case "getColumnType" -> Types.OTHER;
                    case "getColumnTypeName" -> typeName;
                    default -> defaultValue(method.getReturnType());
                });
        AtomicBoolean beforeRow = new AtomicBoolean(true);
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> beforeRow.getAndSet(false);
                    case "getObject" -> {
                        getObjectCalls.incrementAndGet();
                        yield objectValue;
                    }
                    case "getString" -> {
                        getStringCalls.incrementAndGet();
                        yield stringValue;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
