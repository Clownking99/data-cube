package com.datacube.spi.model;

import static org.junit.jupiter.api.Assertions.*;

import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.LocalResultFilter;
import com.datacube.sqleditor.result.ResultValueFormatter;
import java.io.StringReader;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Timestamp;
import java.sql.Types;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import javax.sql.rowset.serial.SerialBlob;
import oracle.sql.BFILE;
import oracle.sql.BOOLEAN;
import oracle.sql.RAW;
import oracle.sql.TIMESTAMPLTZ;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgArray;
import org.postgresql.util.PGobject;
import org.junit.jupiter.api.Test;
import oracle.sql.LargeTextDatum;

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
    void resultSetReaderPreservesMetadataAndDetachesMutableTimestampAndBinaryValues() throws Exception {
        Timestamp timestamp = Timestamp.valueOf("2026-08-29 12:34:56.123456789");
        byte[] binary = new byte[65];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) i;
        CachedRowSet rows = resultSetWithTypedRow(timestamp, binary);

        QueryResult result = QueryResult.fromResultSet(rows, 9, 0);

        assertEquals(List.of(
                new ResultColumn(0, "event_time", Types.TIMESTAMP, "TIMESTAMP"),
                new ResultColumn(1, "payload", Types.VARBINARY, "VARBINARY")), result.resultColumns);
        assertEquals(1, result.rows.size());
        assertInstanceOf(LocalDateTime.class, result.rows.getFirst().get(0));
        assertEquals(timestamp.toLocalDateTime(), result.rows.getFirst().get(0));
        assertFalse(result.rows.getFirst().get(1) instanceof byte[]);
        assertEquals(binaryPreview(binary), ResultValueFormatter.format(result.rows.getFirst().get(1)));
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
        assertEquals(0, jsonGetStringCalls.get());
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
        assertEquals(0, getStringCalls.get());
    }

    @Test
    void resultSetReaderDetachesNonJsonPgObjectsWithoutUsingObjectIdentity() throws Exception {
        for (String typeName : List.of("jsonpath", "jsonb ", " json")) {
            PGobject driverValue = pgObject(typeName, "{\"kind\":\"near\"}");
            String originalValue = driverValue.getValue();
            AtomicInteger getObjectCalls = new AtomicInteger();
            AtomicInteger getStringCalls = new AtomicInteger();

            QueryResult result = QueryResult.fromResultSet(singleOtherTypeResultSet(
                    typeName, driverValue, driverValue.getValue(),
                    getObjectCalls, getStringCalls), 1, 0);

            assertEquals(originalValue, result.rows.getFirst().getFirst(), typeName);
            assertInstanceOf(String.class, result.rows.getFirst().getFirst(), typeName);
            driverValue.setValue("changed after ResultSet read");
            assertEquals(originalValue, result.rows.getFirst().getFirst(), typeName);
            assertEquals(typeName, result.resultColumns.getFirst().jdbcTypeName());
            assertEquals(1, getObjectCalls.get(), typeName);
            assertEquals(0, getStringCalls.get(), typeName);
        }
    }

    @Test
    void oversizedProviderTextUsesBoundedPreviewAndFullContentFingerprint() throws Exception {
        String sharedPrefix = "x".repeat(2_000);
        String firstText = sharedPrefix + "A";
        String secondText = sharedPrefix + "B";
        AtomicInteger getObjectCalls = new AtomicInteger();
        AtomicInteger getStringCalls = new AtomicInteger();
        AtomicInteger getCharacterStreamCalls = new AtomicInteger();

        QueryResult json = QueryResult.fromResultSet(singleOtherTypeResultSet(
                "jsonb", null, firstText, getObjectCalls, getStringCalls,
                getCharacterStreamCalls), 1, 0);
        QueryResult differentJsonTail = QueryResult.fromResultSet(singleOtherTypeResultSet(
                "jsonb", null, secondText, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger()), 1, 0);
        QueryResult pgObject = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "value", Types.OTHER, "jsonpath")),
                List.of(List.of(pgObject("jsonpath", firstText))), 1, false);
        QueryResult differentPgObjectTail = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "value", Types.OTHER, "jsonpath")),
                List.of(List.of(pgObject("jsonpath", secondText))), 1, false);
        QueryResult oracleAccessor = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "value", Types.OTHER, "LARGE_TEXT")),
                List.of(List.of(new LargeTextDatum(firstText))), 1, false);
        QueryResult differentOracleTail = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "value", Types.OTHER, "LARGE_TEXT")),
                List.of(List.of(new LargeTextDatum(secondText))), 1, false);

        Object jsonValue = json.rows.getFirst().getFirst();
        assertBoundedTextSnapshot(jsonValue, firstText.length());
        assertBoundedTextSnapshot(pgObject.rows.getFirst().getFirst(), firstText.length());
        assertBoundedTextSnapshot(oracleAccessor.rows.getFirst().getFirst(), firstText.length());
        assertNotEquals(jsonValue, differentJsonTail.rows.getFirst().getFirst(),
                "same-length text with the same preview must retain distinct content fingerprints");
        assertNotEquals(pgObject.rows.getFirst().getFirst(),
                differentPgObjectTail.rows.getFirst().getFirst());
        assertNotEquals(oracleAccessor.rows.getFirst().getFirst(),
                differentOracleTail.rows.getFirst().getFirst());
        assertEquals(0, getObjectCalls.get());
        assertEquals(0, getStringCalls.get(), "oversized JSON must not be materialized with getString");
        assertEquals(1, getCharacterStreamCalls.get());
    }

    @Test
    void oversizedSqlXmlStreamsBoundedTextAndStillFreesTheLocator() throws Exception {
        String xmlText = "<root>" + "z".repeat(2_000) + "</root>";
        String differentTail = xmlText.substring(0, xmlText.length() - 1) + "!";
        AtomicInteger getStringCalls = new AtomicInteger();
        AtomicInteger getCharacterStreamCalls = new AtomicInteger();
        AtomicBoolean freed = new AtomicBoolean();
        SQLXML xml = proxy(SQLXML.class, (method, args) -> switch (method.getName()) {
            case "getString" -> {
                getStringCalls.incrementAndGet();
                yield xmlText;
            }
            case "getCharacterStream" -> {
                getCharacterStreamCalls.incrementAndGet();
                yield new StringReader(xmlText);
            }
            case "free" -> {
                freed.set(true);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });

        QueryResult result = QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("document", Types.SQLXML, "SQLXML", xml))), 1, 0);
        QueryResult differentResult = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "document", Types.SQLXML, "SQLXML")),
                List.of(List.of(sqlXml(differentTail))), 1, false);

        assertTrue(freed.get());
        assertEquals(0, getStringCalls.get());
        assertEquals(1, getCharacterStreamCalls.get());
        assertBoundedTextSnapshot(result.rows.getFirst().getFirst(), xmlText.length());
        assertNotEquals(result.rows.getFirst().getFirst(), differentResult.rows.getFirst().getFirst());
    }

    @Test
    void oversizedJdbcArraysAndStructsRetainBoundedFingerprintedSnapshots() throws Exception {
        Object[] first = new Object[1_000];
        Object[] second = new Object[1_000];
        java.util.Arrays.fill(first, "same");
        java.util.Arrays.fill(second, "same");
        String nestedLargeText = "nested".repeat(500);
        first[0] = nestedLargeText;
        second[0] = nestedLargeText;
        first[999] = "tail-A";
        second[999] = "tail-B";
        AtomicBoolean arrayResultSetClosed = new AtomicBoolean();
        AtomicBoolean arrayFreed = new AtomicBoolean();
        AtomicInteger getArrayCalls = new AtomicInteger();
        java.sql.Array jdbcArray = streamingArray(first, arrayResultSetClosed, arrayFreed, getArrayCalls);

        QueryResult arrays = QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("values", Types.ARRAY, "TEXT_ARRAY", jdbcArray))), 1, 0);
        QueryResult differentTail = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "values", Types.ARRAY, "TEXT_ARRAY")),
                List.of(List.of(second)), 1, false);
        java.sql.Struct struct = proxy(java.sql.Struct.class, (method, args) -> switch (method.getName()) {
            case "getSQLTypeName" -> "LARGE_STRUCT";
            case "getAttributes" -> first;
            default -> defaultValue(method.getReturnType());
        });
        QueryResult structs = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "value", Types.STRUCT, "LARGE_STRUCT")),
                List.of(List.of(struct)), 1, false);
        java.sql.Struct differentStructTail = proxy(java.sql.Struct.class, (method, args) -> switch (method.getName()) {
            case "getSQLTypeName" -> "LARGE_STRUCT";
            case "getAttributes" -> second;
            default -> defaultValue(method.getReturnType());
        });
        QueryResult differentStructs = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "value", Types.STRUCT, "LARGE_STRUCT")),
                List.of(List.of(differentStructTail)), 1, false);

        Object arrayValue = arrays.rows.getFirst().getFirst();
        assertBoundedAggregateSnapshot(arrayValue, 1_000);
        assertBoundedTextSnapshot(aggregatePreview(arrayValue).getFirst(), nestedLargeText.length());
        assertNotEquals(arrayValue, differentTail.rows.getFirst().getFirst(),
                "same-prefix arrays must retain a fingerprint of omitted elements");
        Object structValue = structs.rows.getFirst().getFirst();
        assertTrue(ResultValueFormatter.format(structValue).startsWith("LARGE_STRUCT["));
        assertTrue(ResultValueFormatter.format(structValue).contains("...(1000 elements)"));
        assertNotEquals(structValue, differentStructs.rows.getFirst().getFirst());
        assertTrue(arrayResultSetClosed.get(), "array element ResultSet must close before release");
        assertTrue(arrayFreed.get(), "array locator must always be freed");
        assertEquals(0, getArrayCalls.get(), "JDBC Array must never materialize through getArray");
    }

    @Test
    void emptyProviderValuesKeepTheirOrdinaryRepresentations() throws Exception {
        AtomicBoolean arrayResultSetClosed = new AtomicBoolean();
        AtomicBoolean arrayFreed = new AtomicBoolean();
        QueryResult json = QueryResult.fromResultSet(singleOtherTypeResultSet(
                "json", null, "", new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger()), 1, 0);
        QueryResult values = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "pg", Types.OTHER, "jsonpath"),
                new ResultColumn(1, "xml", Types.SQLXML, "SQLXML"),
                new ResultColumn(2, "oracle", Types.OTHER, "LARGE_TEXT"),
                new ResultColumn(3, "struct", Types.STRUCT, "EMPTY_STRUCT")), List.of(List.of(
                pgObject("jsonpath", ""), sqlXml(""), new LargeTextDatum(""),
                proxy(java.sql.Struct.class, (method, args) -> switch (method.getName()) {
                    case "getSQLTypeName" -> "EMPTY_STRUCT";
                    case "getAttributes" -> new Object[0];
                    default -> defaultValue(method.getReturnType());
                }))), 1, false);
        QueryResult array = QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("array", Types.ARRAY, "EMPTY_ARRAY", streamingArray(new Object[0],
                        arrayResultSetClosed, arrayFreed, new AtomicInteger())))), 1, 0);

        assertEquals("", json.rows.getFirst().getFirst());
        assertEquals(List.of("", "", "", "EMPTY_STRUCT[]"),
                values.rows.getFirst().stream().map(ResultValueFormatter::format).toList());
        assertEquals("[]", ResultValueFormatter.format(array.rows.getFirst().getFirst()));
        assertTrue(arrayResultSetClosed.get());
        assertTrue(arrayFreed.get());
    }

    @Test
    void providerValuesAtThePreviewLimitsRemainComplete() throws Exception {
        String exactText = "t".repeat(500);
        Object[] exactElements = new Object[128];
        java.util.Arrays.fill(exactElements, "value");
        AtomicBoolean arrayResultSetClosed = new AtomicBoolean();
        AtomicBoolean arrayFreed = new AtomicBoolean();
        QueryResult json = QueryResult.fromResultSet(singleOtherTypeResultSet(
                "jsonb", null, exactText, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger()), 1, 0);
        QueryResult textProviders = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "pg", Types.OTHER, "jsonpath"),
                new ResultColumn(1, "xml", Types.SQLXML, "SQLXML"),
                new ResultColumn(2, "oracle", Types.OTHER, "LARGE_TEXT")), List.of(List.of(
                pgObject("jsonpath", exactText), sqlXml(exactText),
                new LargeTextDatum(exactText))), 1, false);
        QueryResult array = QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("array", Types.ARRAY, "TEXT_ARRAY", streamingArray(exactElements,
                        arrayResultSetClosed, arrayFreed, new AtomicInteger())))), 1, 0);
        java.sql.Struct struct = proxy(java.sql.Struct.class, (method, args) -> switch (method.getName()) {
            case "getSQLTypeName" -> "EXACT_STRUCT";
            case "getAttributes" -> exactElements;
            default -> defaultValue(method.getReturnType());
        });
        QueryResult structResult = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "value", Types.STRUCT, "EXACT_STRUCT")),
                List.of(List.of(struct)), 1, false);

        assertEquals(exactText, json.rows.getFirst().getFirst());
        assertInstanceOf(String.class, json.rows.getFirst().getFirst());
        assertEquals(List.of(exactText, exactText, exactText),
                textProviders.rows.getFirst().stream().map(ResultValueFormatter::format).toList());
        assertEquals(128, aggregatePreview(array.rows.getFirst().getFirst()).size());
        assertFalse(ResultValueFormatter.format(array.rows.getFirst().getFirst()).contains("...("));
        assertFalse(ResultValueFormatter.format(structResult.rows.getFirst().getFirst()).contains("...("));
        assertTrue(arrayResultSetClosed.get());
        assertTrue(arrayFreed.get());
    }

    @Test
    void resultSetReaderMaterializesJdbcValuesAndFreesResourceBackedObjects() throws Exception {
        AtomicBoolean arrayFreed = new AtomicBoolean();
        AtomicBoolean arrayResultSetClosed = new AtomicBoolean();
        AtomicBoolean xmlFreed = new AtomicBoolean();
        java.sql.Array array = streamingArray(new Object[]{"alpha", new int[]{1, 2}},
                arrayResultSetClosed, arrayFreed, new AtomicInteger());
        SQLXML xml = proxy(SQLXML.class, (method, args) -> switch (method.getName()) {
            case "getCharacterStream" -> new StringReader("<root/>");
            case "free" -> {
                xmlFreed.set(true);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        java.sql.Struct struct = proxy(java.sql.Struct.class, (method, args) -> switch (method.getName()) {
            case "getSQLTypeName" -> "POINT_T";
            case "getAttributes" -> new Object[]{3, 4};
            default -> defaultValue(method.getReturnType());
        });
        java.sql.RowId rowId = proxy(java.sql.RowId.class, (method, args) -> switch (method.getName()) {
            case "getBytes" -> new byte[]{1, 2};
            default -> defaultValue(method.getReturnType());
        });
        java.sql.Ref ref = proxy(java.sql.Ref.class, (method, args) -> switch (method.getName()) {
            case "getBaseTypeName" -> "PERSON_T";
            case "getObject" -> "Ada";
            default -> defaultValue(method.getReturnType());
        });

        QueryResult result = QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("numbers", Types.ARRAY, "_int4", array),
                new JdbcCell("document", Types.SQLXML, "XML", xml),
                new JdbcCell("point", Types.STRUCT, "POINT_T", struct),
                new JdbcCell("row_id", Types.ROWID, "ROWID", rowId),
                new JdbcCell("owner", Types.REF, "PERSON_T", ref))), 2, 0);

        assertTrue(arrayFreed.get());
        assertTrue(arrayResultSetClosed.get());
        assertTrue(xmlFreed.get());
        assertEquals(List.of("[alpha, [1, 2]]", "<root/>", "POINT_T[3, 4]", "0102", "REF PERSON_T(Ada)"),
                result.rows.getFirst().stream().map(ResultValueFormatter::format).toList());
        assertTrue(result.rows.getFirst().stream().noneMatch(value -> value instanceof java.sql.Array
                || value instanceof SQLXML || value instanceof java.sql.Struct
                || value instanceof java.sql.RowId || value instanceof java.sql.Ref));
    }

    @Test
    void primaryReadFailureIsPreservedAndEveryResourceCleanupStillRuns() throws Exception {
        assertPrimaryFailureStillFrees(Clob.class, Types.CLOB, "CLOB", "getSubString");
        assertPrimaryFailureStillFrees(java.sql.Blob.class, Types.BLOB, "BLOB", "getBinaryStream");
        assertPrimaryFailureStillFrees(java.sql.Array.class, Types.ARRAY, "ARRAY", "getResultSet");
        assertPrimaryFailureStillFrees(SQLXML.class, Types.SQLXML, "SQLXML", "getCharacterStream");
    }

    @Test
    void resourceInterfacesTakePrecedenceOverGenericCharSequences() throws Exception {
        AtomicBoolean freed = new AtomicBoolean();
        Object xmlText = Proxy.newProxyInstance(QueryResultMetadataTest.class.getClassLoader(),
                new Class<?>[]{SQLXML.class, CharSequence.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCharacterStream" -> new StringReader("<dual/>");
                    case "free" -> {
                        freed.set(true);
                        yield null;
                    }
                    case "toString" -> "identity-like fallback";
                    case "length" -> 22;
                    case "charAt" -> 'x';
                    case "subSequence" -> "identity-like fallback";
                    default -> defaultValue(method.getReturnType());
                });

        QueryResult result = QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("document", Types.SQLXML, "SQLXML", xmlText))), 1, 0);

        assertTrue(freed.get());
        assertEquals("<dual/>", result.rows.getFirst().getFirst());
    }

    @Test
    void binaryStorageIsBoundedAndBlobEqualityIncludesContentBeyondThePreview() throws Exception {
        byte[] large = new byte[1_000_000];
        for (int index = 0; index < large.length; index++) large[index] = (byte) index;
        QueryResult largeResult = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "BIN", Types.VARBINARY, "VARBINARY")),
                List.of(List.of(large)), 1, false);
        Object frozenLarge = largeResult.rows.getFirst().getFirst();
        java.lang.reflect.Field retainedBytes = ImmutableResultValue.class.getDeclaredField("bytes");
        retainedBytes.setAccessible(true);
        assertTrue(((byte[]) retainedBytes.get(frozenLarge)).length <= 64,
                "immutable binary state must retain only the bounded preview");

        byte[] first = new byte[65];
        byte[] differentTail = first.clone();
        first[64] = 1;
        differentTail[64] = 2;
        QueryResult blobs = QueryResult.queryWithMetadata(List.of(
                new ResultColumn(0, "BIN", Types.BLOB, "BLOB")), List.of(
                List.of(new SerialBlob(first)), List.of(new SerialBlob(differentTail))), 1, false);

        assertNotEquals(blobs.rows.get(0).getFirst(), blobs.rows.get(1).getFirst(),
                "same-length BLOBs with the same preview must retain distinct content fingerprints");
        assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(blobs, "", List.of(
                new FilterCondition(0, FilterConnector.AND, FilterOperator.EQ, first))));
    }

    @Test
    void cyclicAndExcessivelyNestedArraysAreRejectedDeterministically() {
        ResultColumn arrayColumn = new ResultColumn(0, "VALUE", Types.ARRAY, "ARRAY");
        Object[] cyclic = new Object[1];
        cyclic[0] = cyclic;

        IllegalArgumentException cycleFailure = assertThrows(IllegalArgumentException.class,
                () -> QueryResult.queryWithMetadata(List.of(arrayColumn),
                        List.of(List.of(cyclic)), 1, false));
        assertTrue(cycleFailure.getMessage().contains("循环"));

        Object nested = "leaf";
        for (int depth = 0; depth < 65; depth++) nested = new Object[]{nested};
        Object tooDeep = nested;
        IllegalArgumentException depthFailure = assertThrows(IllegalArgumentException.class,
                () -> QueryResult.queryWithMetadata(List.of(arrayColumn),
                        List.of(List.of(tooDeep)), 1, false));
        assertTrue(depthFailure.getMessage().contains("嵌套"));
    }

    @Test
    void realPgArrayIsMaterializedBeforeItsDriverConnectionIsReleased() throws Exception {
        AtomicBoolean freed = new AtomicBoolean();
        PgArray array = pgIntArray(freed, "{1,2,3}");

        QueryResult result = QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("values", Types.ARRAY, "_int4", array))), 1, 0);

        assertTrue(freed.get());
        assertEquals("[1, 2, 3]", ResultValueFormatter.format(result.rows.getFirst().getFirst()));
        assertFalse(result.rows.getFirst().getFirst() instanceof java.sql.Array);
    }

    @Test
    void oracleDatumsDetachWithoutKeepingMutableDriverStateOrRequiringAConnection() throws Exception {
        RAW raw = new RAW(new byte[]{10, 11});
        BOOLEAN bool = new BOOLEAN(true);
        TIMESTAMPLTZ localTimestamp = new TIMESTAMPLTZ(
                new byte[]{120, 126, 8, 29, 11, 12, 13});
        OffsetDateTime localTimestampValue = OffsetDateTime.parse("2026-08-29T10:11:12+08:00");
        QueryResult result = assertDoesNotThrow(() -> QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("raw_value", Types.OTHER, "RAW", raw),
                new JdbcCell("boolean_value", Types.OTHER, "BOOLEAN", bool),
                new JdbcCell("local_timestamp", Types.TIMESTAMP_WITH_TIMEZONE,
                        "TIMESTAMP WITH LOCAL TIME ZONE", localTimestamp)),
                Map.of(3, localTimestampValue)), 1, 0));

        raw.setBytes(new byte[]{12, 13});
        bool.setBytes(new byte[]{0});
        localTimestamp.setBytes(new byte[]{1, 2, 3});

        assertEquals(List.of("0A0B", "true", "2026-08-29 10:11:12+08:00"),
                result.rows.getFirst().stream().map(ResultValueFormatter::format).toList());
        assertTrue(result.rows.getFirst().stream()
                .noneMatch(value -> value.getClass().getName().startsWith("oracle.")));
        assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(result, "", List.of(
                new FilterCondition(2, FilterConnector.AND, FilterOperator.EQ,
                        OffsetDateTime.parse("2026-08-29T02:11:12Z")))));
        assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(result, "", List.of(
                new FilterCondition(2, FilterConnector.AND, FilterOperator.GT,
                        localTimestampValue.minusSeconds(1)))));
        assertEquals(List.of(0), LocalResultFilter.visibleRowIndexes(result, "", List.of(
                new FilterCondition(2, FilterConnector.AND, FilterOperator.LT,
                        localTimestampValue.plusSeconds(1)))));
    }

    @Test
    @SuppressWarnings("deprecation")
    void oracleBfileDetachesItsLogicalNameWithoutRetainingTheLocator() throws Exception {
        String[] logicalName = {"MEDIA_DIR", "payload.bin"};
        BFILE locator = new BFILE(oracleConnectionStub()) {
            @Override
            public String getDirAlias() {
                return logicalName[0];
            }

            @Override
            public String getName() {
                return logicalName[1];
            }
        };

        QueryResult result = assertDoesNotThrow(() -> QueryResult.fromResultSet(singleRowResultSet(List.of(
                new JdbcCell("attachment", Types.OTHER, "BFILE", locator))), 1, 0));
        logicalName[0] = "CHANGED_DIR";
        logicalName[1] = "changed.bin";

        Object frozen = result.rows.getFirst().getFirst();
        assertEquals("BFILE[MEDIA_DIR, payload.bin]", ResultValueFormatter.format(frozen));
        assertFalse(frozen.getClass().getName().startsWith("oracle."));
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
                    case "getCharacterStream" -> {
                        int index = (int) args[0];
                        yield index == 1 ? new StringReader(jsonText) : new StringReader(uuid.toString());
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
        return singleOtherTypeResultSet(typeName, objectValue, stringValue,
                getObjectCalls, getStringCalls, new AtomicInteger());
    }

    private static ResultSet singleOtherTypeResultSet(
            String typeName, Object objectValue, String stringValue,
            AtomicInteger getObjectCalls, AtomicInteger getStringCalls,
            AtomicInteger getCharacterStreamCalls) {
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
                    case "getCharacterStream" -> {
                        getCharacterStreamCalls.incrementAndGet();
                        yield stringValue == null ? null : new StringReader(stringValue);
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static java.sql.Array streamingArray(
            Object[] values, AtomicBoolean resultSetClosed, AtomicBoolean freed,
            AtomicInteger getArrayCalls) {
        AtomicInteger position = new AtomicInteger(-1);
        ResultSet elements = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> position.incrementAndGet() < values.length;
                    case "getObject" -> (int) args[0] == 1
                            ? (long) position.get() + 1 : values[position.get()];
                    case "close" -> {
                        resultSetClosed.set(true);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
        return proxy(java.sql.Array.class, (method, args) -> switch (method.getName()) {
            case "getResultSet" -> elements;
            case "getArray" -> {
                getArrayCalls.incrementAndGet();
                yield values;
            }
            case "free" -> {
                if (!resultSetClosed.get()) {
                    throw new AssertionError("array ResultSet must close before Array.free");
                }
                freed.set(true);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static SQLXML sqlXml(String value) {
        return proxy(SQLXML.class, (method, args) -> switch (method.getName()) {
            case "getCharacterStream" -> new StringReader(value);
            case "free" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static void assertBoundedTextSnapshot(Object value, int totalCharacters) throws Exception {
        assertInstanceOf(ImmutableResultValue.class, value);
        java.lang.reflect.Field text = ImmutableResultValue.class.getDeclaredField("text");
        text.setAccessible(true);
        assertTrue(((String) text.get(value)).length() <= 500);
        assertTrue(ResultValueFormatter.format(value).endsWith("...(" + totalCharacters + " chars)"));
    }

    private static void assertBoundedAggregateSnapshot(Object value, int totalElements) throws Exception {
        assertInstanceOf(ImmutableResultValue.class, value);
        assertTrue(aggregatePreview(value).size() <= 128);
        assertTrue(ResultValueFormatter.format(value).contains("...(" + totalElements + " elements)"));
    }

    private static List<?> aggregatePreview(Object value) throws Exception {
        java.lang.reflect.Field values = ImmutableResultValue.class.getDeclaredField("values");
        values.setAccessible(true);
        return (List<?>) values.get(value);
    }

    private static ResultSet singleRowResultSet(List<JdbcCell> cells) {
        return singleRowResultSet(cells, Map.of());
    }

    private static ResultSet singleRowResultSet(List<JdbcCell> cells, Map<Integer, Object> typedValues) {
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSetMetaData.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> cells.size();
                    case "getColumnLabel" -> cells.get((int) args[0] - 1).label();
                    case "getColumnType" -> cells.get((int) args[0] - 1).jdbcType();
                    case "getColumnTypeName" -> cells.get((int) args[0] - 1).jdbcTypeName();
                    default -> defaultValue(method.getReturnType());
                });
        AtomicBoolean beforeRow = new AtomicBoolean(true);
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> beforeRow.getAndSet(false);
                    case "getObject" -> args.length == 2
                            ? typedValues.get((int) args[0]) : cells.get((int) args[0] - 1).value();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static oracle.jdbc.internal.OracleConnection oracleConnectionStub() {
        oracle.jdbc.internal.Monitor.CloseableLock lock =
                oracle.jdbc.internal.Monitor.newDefaultLock();
        return (oracle.jdbc.internal.OracleConnection) Proxy.newProxyInstance(
                QueryResultMetadataTest.class.getClassLoader(),
                new Class<?>[]{oracle.jdbc.internal.OracleConnection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("physicalConnectionWithin")) return proxy;
                    if (method.getName().equals("getMonitorLock")) return lock;
                    if (method.isDefault()) {
                        return java.lang.reflect.InvocationHandler.invokeDefault(
                                proxy, method, args == null ? new Object[0] : args);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static void assertPrimaryFailureStillFrees(
            Class<?> resourceType, int jdbcType, String typeName, String failingMethod) {
        AtomicBoolean freed = new AtomicBoolean();
        String primaryMessage = "primary " + typeName;
        String cleanupMessage = "cleanup " + typeName;
        Object resource = Proxy.newProxyInstance(QueryResultMetadataTest.class.getClassLoader(),
                new Class<?>[]{resourceType}, (proxy, method, args) -> {
                    if (method.getName().equals("length")) return 10L;
                    if (method.getName().equals(failingMethod)) throw new SQLException(primaryMessage);
                    if (method.getName().equals("free")) {
                        freed.set(true);
                        throw new SQLException(cleanupMessage);
                    }
                    return defaultValue(method.getReturnType());
                });

        SQLException failure = assertThrows(SQLException.class, () -> QueryResult.fromResultSet(
                singleRowResultSet(List.of(new JdbcCell("value", jdbcType, typeName, resource))), 1, 0));

        assertEquals(primaryMessage, failure.getMessage());
        assertTrue(freed.get(), typeName + " must be freed after a read failure");
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(cleanupMessage, failure.getSuppressed()[0].getMessage());
    }

    private static PgArray pgIntArray(AtomicBoolean freed, String literal) throws SQLException {
        TypeInfo typeInfo = proxy(TypeInfo.class, (method, args) -> switch (method.getName()) {
            case "getPGArrayElement" -> 23;
            case "getArrayDelimiter" -> ',';
            case "getPGType" -> "int4";
            case "getSQLType" -> Types.INTEGER;
            case "getJavaClass" -> Integer.class.getName();
            default -> defaultValue(method.getReturnType());
        });
        BaseConnection connection = proxy(BaseConnection.class, (method, args) ->
                method.getName().equals("getTypeInfo") ? typeInfo : defaultValue(method.getReturnType()));
        return new PgArray(connection, 1007, literal) {
            @Override
            public ResultSet getResultSet() {
                AtomicInteger position = new AtomicInteger();
                return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                        new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "next" -> position.incrementAndGet() <= 3;
                            case "getObject" -> (int) args[0] == 1
                                    ? (long) position.get() : position.get();
                            case "close" -> null;
                            default -> defaultValue(method.getReturnType());
                        });
            }

            @Override
            public void free() throws SQLException {
                freed.set(true);
                super.free();
            }
        };
    }

    private static String binaryPreview(byte[] value) {
        StringBuilder preview = new StringBuilder();
        int displayed = Math.min(64, value.length);
        for (int index = 0; index < displayed; index++) {
            preview.append(String.format("%02x", value[index]));
        }
        if (value.length > displayed) preview.append("...(").append(value.length).append(" bytes)");
        return preview.toString();
    }

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return type.cast(Proxy.newProxyInstance(QueryResultMetadataTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> handler.invoke(method, args)));
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }

    private record JdbcCell(String label, int jdbcType, String jdbcTypeName, Object value) {
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
