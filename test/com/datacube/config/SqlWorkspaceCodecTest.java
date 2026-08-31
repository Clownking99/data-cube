package com.datacube.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceCodecTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    @Test void encodesExactBytesAndDecodesIndependentFixture() throws Exception {
        SqlWorkspace value = new SqlWorkspace(1234, List.of(
                new SqlWorkspace.Entry(B, 7, 2), new SqlWorkspace.Entry(A, 0, 1048576)), A);
        byte[] fixture = wire(1234, 1, new long[][]{{2, 7, 2}, {1, 0, 1048576}});
        assertArrayEquals(fixture, SqlWorkspaceCodec.encode(value));
        assertEquals(value, SqlWorkspaceCodec.decode(fixture));
        assertEquals(72, fixture.length);
    }

    @Test void preservesEmptyWorkspaceAndNoSelectedSqlTab() throws Exception {
        assertEquals(new SqlWorkspace(0, List.of(), null),
                SqlWorkspaceCodec.decode(wire(0, -1, new long[0][])));
        SqlWorkspace noSelection = new SqlWorkspace(7, List.of(new SqlWorkspace.Entry(A, 0, 0)), null);
        assertArrayEquals(wire(7, -1, new long[][]{{1, 0, 0}}), SqlWorkspaceCodec.encode(noSelection));
        assertNull(SqlWorkspaceCodec.decode(wire(7, -1, new long[][]{{1, 0, 0}})).selectedDraftId());
        assertArrayEquals(wire(0, -1, new long[0][]), SqlWorkspaceCodec.encode(new SqlWorkspace(0, List.of(), null)));
    }

    @Test void retainsEveryUuidBitAndMaximumTimestamp() throws Exception {
        UUID id = UUID.fromString("fedcba98-7654-3210-8123-456789abcdef");
        byte[] fixture = wire(Long.MAX_VALUE, 0, new long[][]{{id.getLeastSignificantBits(), 1, 1}});
        ByteBuffer.wrap(fixture).putLong(24, id.getMostSignificantBits());
        SqlWorkspace value = new SqlWorkspace(Long.MAX_VALUE, List.of(new SqlWorkspace.Entry(id, 1, 1)), id);
        assertArrayEquals(fixture, SqlWorkspaceCodec.encode(value));
        assertEquals(value, SqlWorkspaceCodec.decode(fixture));
    }

    @ParameterizedTest @ValueSource(ints = {99, 100, 101})
    void entryCountBoundaryIsEnforcedByValueAndDecoder(int count) throws Exception {
        List<SqlWorkspace.Entry> entries = new ArrayList<>();
        long[][] fields = new long[count][3];
        for (int i = 0; i < count; i++) {
            entries.add(new SqlWorkspace.Entry(new UUID(0, i + 1), i, i + 1));
            fields[i] = new long[]{i + 1, i, i + 1};
        }
        if (count <= 100) {
            SqlWorkspace value = new SqlWorkspace(0, entries, null);
            assertEquals(value, SqlWorkspaceCodec.decode(wire(0, -1, fields)));
            assertArrayEquals(wire(0, -1, fields), SqlWorkspaceCodec.encode(value));
        } else {
            assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace(0, entries, null));
            corrupt(wire(0, -1, fields));
        }
        assertEquals(2424, SqlWorkspaceCodec.MAX_FILE_BYTES);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1048575, 1048576, 1048577})
    void positionBoundariesApplyToAnchorAndCaret(int position) throws Exception {
        for (boolean anchor : new boolean[]{true, false}) {
            int left = anchor ? position : 0;
            int right = anchor ? 0 : position;
            byte[] bytes = wire(0, 0, new long[][]{{1, left, right}});
            if (position >= 0 && position <= 1048576) {
                SqlWorkspace value = new SqlWorkspace(0, List.of(new SqlWorkspace.Entry(A, left, right)), A);
                assertArrayEquals(bytes, SqlWorkspaceCodec.encode(value));
                assertEquals(value, SqlWorkspaceCodec.decode(bytes));
            } else {
                assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace.Entry(A, left, right));
                corrupt(bytes);
            }
        }
    }

    @Test void rejectsNullsDuplicatesNegativeTimeAndForeignSelection() {
        SqlWorkspace.Entry entry = new SqlWorkspace.Entry(A, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace.Entry(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace(-1, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace(0, Arrays.asList(entry, null), null));
        assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace(0, List.of(entry, entry), null));
        assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace(0, List.of(entry), C));
        assertThrows(IllegalArgumentException.class, () -> new SqlWorkspace(0, List.of(), A));
    }

    @Test void freezesCallerListAndDecodedList() throws Exception {
        List<SqlWorkspace.Entry> source = new ArrayList<>(List.of(new SqlWorkspace.Entry(A, 3, 2)));
        SqlWorkspace value = new SqlWorkspace(8, source, A);
        source.clear();
        assertEquals(List.of(new SqlWorkspace.Entry(A, 3, 2)), value.entries());
        assertThrows(UnsupportedOperationException.class, () -> value.entries().clear());
        SqlWorkspace decoded = SqlWorkspaceCodec.decode(wire(8, 0, new long[][]{{1, 3, 2}}));
        assertThrows(UnsupportedOperationException.class, () -> decoded.entries().clear());
    }

    @Test void rejectsEveryTruncationTrailingBytesAndNullPayload() {
        byte[] fixture = wire(9, 0, new long[][]{{1, 3, 2}, {2, 0, 1}});
        for (int i = 0; i < fixture.length; i++) corrupt(Arrays.copyOf(fixture, i));
        corrupt(Arrays.copyOf(fixture, fixture.length + 1));
        corrupt(new byte[2425]);
        corrupt(null);
        SqlWorkspaceCodec.Failure error = assertThrows(SqlWorkspaceCodec.Failure.class,
                () -> SqlWorkspaceCodec.encode(null));
        assertEquals(SqlWorkspaceCodec.Code.CORRUPT, error.code());
    }

    @ParameterizedTest @ValueSource(ints = {-2147483648, -1, 101, 2147483647})
    void rejectsInvalidCountBeforeAllocation(int count) {
        byte[] fixture = wire(0, -1, new long[0][]);
        ByteBuffer.wrap(fixture).putInt(16, count);
        corrupt(fixture);
    }

    @ParameterizedTest @ValueSource(ints = {-2, 1, 2147483647})
    void rejectsSelectionOutsideEntries(int selection) {
        corrupt(wire(0, selection, new long[][]{{1, 0, 0}}));
    }

    @Test void rejectsDuplicateWireIdsNegativeTimeAndInvalidMagic() {
        corrupt(wire(0, -1, new long[][]{{1, 0, 0}, {1, 2, 1}}));
        corrupt(wire(-1, -1, new long[0][]));
        corrupt(wire(0, 0, new long[0][]));
        byte[] invalidMagic = wire(0, -1, new long[0][]);
        ByteBuffer.wrap(invalidMagic).putInt(0, 0);
        corrupt(invalidMagic);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 2, 2147483647})
    void distinguishesUnsupportedVersionsWithoutEchoingPayload(int version) {
        byte[] bytes = wire(123, 0, new long[][]{{1, 9, 8}});
        ByteBuffer.wrap(bytes).putInt(4, version);
        SqlWorkspaceCodec.Failure error = assertThrows(SqlWorkspaceCodec.Failure.class,
                () -> SqlWorkspaceCodec.decode(bytes));
        assertEquals(SqlWorkspaceCodec.Code.UNSUPPORTED_VERSION, error.code());
        assertEquals("Invalid SQL workspace format: UNSUPPORTED_VERSION", error.getMessage());
        assertNull(error.getCause());
    }

    private static void corrupt(byte[] bytes) {
        SqlWorkspaceCodec.Failure error = assertThrows(SqlWorkspaceCodec.Failure.class,
                () -> SqlWorkspaceCodec.decode(bytes));
        assertEquals(SqlWorkspaceCodec.Code.CORRUPT, error.code());
        assertEquals("Invalid SQL workspace format: CORRUPT", error.getMessage());
        assertNull(error.getCause());
    }

    // Independent v1 fixture: literal format, no production codec/constants.
    private static byte[] wire(long at, int selected, long[][] fields) {
        ByteBuffer bytes = ByteBuffer.allocate(24 + fields.length * 24);
        bytes.putInt(0x44435753).putInt(1).putLong(at).putInt(fields.length).putInt(selected);
        for (long[] item : fields) bytes.putLong(0).putLong(item[0]).putInt((int) item[1]).putInt((int) item[2]);
        return bytes.array();
    }
}

