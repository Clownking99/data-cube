package com.datacube.config;

import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftCodecTest {
    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final long MODIFIED = 1788000000000L;
    private static final int SQL_LIMIT = 1024 * 1024;

    @Test
    void writesExactVersionOneBytesAndReadsIndependentFixture() throws Exception {
        String sql = " \r\nselect '中文😀', '\u0000';\n\t ";
        SqlDraft value = new SqlDraft(ID, MODIFIED, "saved-id", DbType.ORACLE,
                "Synthetic connection", "  schema  ", sql);
        byte[] expected = wire("saved-id", "ORACLE", "Synthetic connection", "  schema  ", sql);
        assertArrayEquals(expected, SqlDraftCodec.encode(value));
        assertEquals(value, SqlDraftCodec.decode(expected));
    }

    @Test
    void distinguishesNullMetadataEmptyMetadataAndEmptySql() throws Exception {
        SqlDraft empty = new SqlDraft(ID, 0, null, null, null, "", "");
        byte[] encoded = SqlDraftCodec.encode(empty);
        byte[] expected = wireAt(0, null, null, null, "", "");
        assertArrayEquals(expected, encoded);
        SqlDraft decoded = SqlDraftCodec.decode(expected);
        assertNull(decoded.connectionId());
        assertNull(decoded.connectionType());
        assertNull(decoded.connectionName());
        assertEquals("", decoded.schema());
        assertEquals("", decoded.sql());
        assertEquals(ID, decoded.id());
        assertEquals(0, decoded.modifiedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POSTGRESQL", "ORACLE"})
    void retainsIdentityAcrossSupportedTypesWithoutNameMatching(String type) throws Exception {
        SqlDraft value = new SqlDraft(ID, MODIFIED, "stable-id", DbType.valueOf(type),
                null, null, "\u2003\t\n");
        assertArrayEquals(wire("stable-id", type, null, null, "\u2003\t\n"), SqlDraftCodec.encode(value));
        assertEquals(value, SqlDraftCodec.decode(wire("stable-id", type, null, null, "\u2003\t\n")));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void sqlByteLimitRejectsOnlyAboveBoundary(int delta) throws Exception {
        String sql = "😀".repeat(SQL_LIMIT / 4 - 1) + "x".repeat(4 + delta);
        SqlDraft value = new SqlDraft(ID, MODIFIED, null, null, null, null, sql);
        byte[] fixture = wire(null, null, null, null, sql);
        if (delta <= 0) {
            assertArrayEquals(fixture, SqlDraftCodec.encode(value));
            assertEquals(sql, SqlDraftCodec.decode(fixture).sql());
            assertEquals(SQL_LIMIT + delta, sql.getBytes(StandardCharsets.UTF_8).length);
        } else {
            assertThrows(IOException.class, () -> SqlDraftCodec.encode(value));
            assertThrows(IOException.class, () -> SqlDraftCodec.decode(fixture));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void everyMetadataFieldUsesUtf8ByteLimit(int delta) throws Exception {
        String metadata = "界".repeat(1365) + "x".repeat(1 + delta);
        for (int slot : new int[]{0, 2, 3}) {
            String id = slot == 0 ? metadata : "id";
            String name = slot == 2 ? metadata : null;
            String schema = slot == 3 ? metadata : null;
            SqlDraft value = new SqlDraft(ID, MODIFIED, id, DbType.POSTGRESQL, name, schema, "select 1");
            byte[] fixture = wire(id, "POSTGRESQL", name, schema, "select 1");
            if (delta <= 0) {
                assertArrayEquals(fixture, SqlDraftCodec.encode(value));
                assertEquals(value, SqlDraftCodec.decode(fixture));
            } else {
                assertThrows(IOException.class, () -> SqlDraftCodec.encode(value));
                assertThrows(IOException.class, () -> SqlDraftCodec.decode(fixture));
            }
        }
    }

    @Test
    void maximumCombinedPayloadIsAcceptedAndWholeFileLimitIsBounded() throws Exception {
        SqlDraft value = new SqlDraft(ID, MODIFIED, "i".repeat(4096), DbType.POSTGRESQL,
                "n".repeat(4096), "s".repeat(4096), "x".repeat(SQL_LIMIT));
        byte[] fixture = wire(value.connectionId(), "POSTGRESQL", value.connectionName(), value.schema(), value.sql());
        assertArrayEquals(fixture, SqlDraftCodec.encode(value));
        assertEquals(value, SqlDraftCodec.decode(fixture));
        assertEquals(SQL_LIMIT + 4 * 4096 + 64, SqlDraftCodec.MAX_FILE_BYTES);
        assertThrows(IOException.class, () -> SqlDraftCodec.decode(new byte[SqlDraftCodec.MAX_FILE_BYTES + 1]));
    }

    @Test
    void rejectsBadHeadersEveryTruncationAndTrailingData() throws Exception {
        byte[] valid = wire("id", "ORACLE", "name", "schema", "select 1");
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> SqlDraftCodec.decode(truncated), "length=" + length);
        }
        for (int offset : new int[]{0, 4}) {
            byte[] changed = valid.clone();
            ByteBuffer.wrap(changed).putInt(offset, offset == 0 ? 0 : 2);
            assertThrows(IOException.class, () -> SqlDraftCodec.decode(changed));
        }
        assertThrows(IOException.class, () -> SqlDraftCodec.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IOException.class, () -> SqlDraftCodec.decode(null));
        assertThrows(IOException.class, () -> SqlDraftCodec.encode(null));
        assertArrayEquals(wire("id", "ORACLE", "name", "schema", "select 1"), valid);
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, -2147483648, 4097, 2147483647})
    void rejectsInvalidLengthsBeforeReadingPayload(int length) throws Exception {
        byte[] bytes = wire(null, null, null, null, "");
        ByteBuffer.wrap(bytes).putInt(32, length);
        assertThrows(IOException.class, () -> SqlDraftCodec.decode(bytes));
    }

    @Test
    void rejectsInvalidIdentityTypeAndNullSqlOnWire() throws Exception {
        String[][] invalid = {
                {"id", null, null, null, "select 1"},
                {null, "ORACLE", null, null, "select 1"},
                {" ", "ORACLE", null, null, "select 1"},
                {"id", "REDIS", null, null, "select 1"},
                {"id", "NEW_DB", null, null, "select 1"},
                {"id", "x".repeat(4097), null, null, "select 1"},
                {null, null, null, null, null}
        };
        for (String[] fields : invalid) {
            assertThrows(IOException.class, () -> SqlDraftCodec.decode(wire(fields[0], fields[1], fields[2], fields[3], fields[4])));
        }
        assertThrows(IOException.class, () -> SqlDraftCodec.decode(wireAt(-1, null, null, null, null, "x")));
    }

    @Test
    void rejectsMalformedUtf8AndUnpairedSurrogatesWithoutSubstitution() throws Exception {
        byte[] malformedSql = wire(null, null, null, null, "ab");
        malformedSql[malformedSql.length - 2] = (byte) 0xc3;
        malformedSql[malformedSql.length - 1] = 0x28;
        assertThrows(IOException.class, () -> SqlDraftCodec.decode(malformedSql));
        byte[] malformedId = wire("a", "ORACLE", null, null, "select 1");
        malformedId[36] = (byte) 0xff;
        assertThrows(IOException.class, () -> SqlDraftCodec.decode(malformedId));
        for (String invalid : new String[]{"\ud800", "\udc00", "secret\ud800text"}) {
            assertThrows(IOException.class, () -> SqlDraftCodec.encode(new SqlDraft(ID, MODIFIED, null, null, null, null, invalid)));
            assertThrows(IOException.class, () -> SqlDraftCodec.encode(new SqlDraft(ID, MODIFIED, "id", DbType.ORACLE, invalid, null, "ok")));
            assertThrows(IOException.class, () -> SqlDraftCodec.encode(new SqlDraft(ID, MODIFIED, invalid, DbType.ORACLE, null, null, "ok")));
            assertThrows(IOException.class, () -> SqlDraftCodec.encode(new SqlDraft(ID, MODIFIED, null, null, null, invalid, "ok")));
        }
    }

    @Test
    void valueValidationAndDiagnosticsNeverExposePrivateText() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new SqlDraft(null, MODIFIED, null, null, null, null, "secret"));
        assertThrows(IllegalArgumentException.class, () -> new SqlDraft(ID, -1, null, null, null, null, "secret"));
        assertThrows(IllegalArgumentException.class, () -> new SqlDraft(ID, MODIFIED, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SqlDraft(ID, MODIFIED, "id", null, null, null, "secret"));
        assertThrows(IllegalArgumentException.class, () -> new SqlDraft(ID, MODIFIED, null, DbType.ORACLE, null, null, "secret"));
        assertThrows(IllegalArgumentException.class, () -> new SqlDraft(ID, MODIFIED, " ", DbType.ORACLE, null, null, "secret"));
        assertThrows(IllegalArgumentException.class, () -> new SqlDraft(ID, MODIFIED, "id", DbType.REDIS, null, null, "secret"));
        SqlDraft value = new SqlDraft(ID, MODIFIED, "private-id", DbType.ORACLE, "private-name", "private-schema", "private-sql");
        assertEquals("SqlDraft[id=" + ID + ", modifiedAt=" + MODIFIED + ", sqlChars=11]", value.toString());
        IOException error = assertThrows(IOException.class,
                () -> SqlDraftCodec.decode(wire("private-id", "private-unknown-type", null, null, "private-sql")));
        assertEquals("Invalid SQL draft format", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void oversizedTextIsRejectedBeforeAllocatingEncodingBuffer() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String mainClasses = Path.of(SqlDraft.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String testClasses = Path.of(EncodingBudgetProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(java.toString(), "-Xmx48m", "-cp",
                mainClasses + System.getProperty("path.separator") + testClasses,
                EncodingBudgetProbe.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "synthetic encoding probe timed out");
            assertEquals(0, process.exitValue(), "42=fixture allocation failed, 43=oversize accepted, 44=encoding allocated before limit check");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class EncodingBudgetProbe {
        public static void main(String[] args) {
            String oversized;
            try { oversized = "x".repeat(32 * 1024 * 1024); }
            catch (OutOfMemoryError fixtureFailure) { System.exit(42); return; }
            try {
                SqlDraftCodec.encode(new SqlDraft(new UUID(0, 1), 0, null, null, null, null, oversized));
                System.exit(43);
            } catch (IOException expected) { System.exit(0); }
            catch (OutOfMemoryError allocationFailure) { System.exit(44); }
        }
    }

    private static byte[] wire(String id, String type, String name, String schema, String sql) throws IOException {
        return wireAt(MODIFIED, id, type, name, schema, sql);
    }

    private static byte[] wireAt(long modified, String id, String type, String name, String schema, String sql) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x44434452);
            out.writeInt(1);
            out.writeLong(ID.getMostSignificantBits());
            out.writeLong(ID.getLeastSignificantBits());
            out.writeLong(modified);
            for (String text : new String[]{id, type, name, schema, sql}) {
                if (text == null) out.writeInt(-1);
                else {
                    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(utf8.length);
                    out.write(utf8);
                }
            }
        }
        return bytes.toByteArray();
    }
}
