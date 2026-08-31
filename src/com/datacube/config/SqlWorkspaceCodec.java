package com.datacube.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded v1 bytes, without encryption or authenticity guarantees. */
final class SqlWorkspaceCodec {
    private static final int MAGIC = 0x44435753;
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 24;
    private static final int ENTRY_BYTES = 24;
    static final int MAX_FILE_BYTES = HEADER_BYTES + SqlWorkspace.MAX_ENTRIES * ENTRY_BYTES;

    enum Code { CORRUPT, UNSUPPORTED_VERSION }
    static final class Failure extends IOException {
        private final Code code;
        Failure(Code code) { super("Invalid SQL workspace format: " + code); this.code = code; }
        Code code() { return code; }
    }

    private SqlWorkspaceCodec() { }

    static byte[] encode(SqlWorkspace value) throws IOException {
        if (value == null) throw corrupt();
        ByteBuffer bytes = ByteBuffer.allocate(HEADER_BYTES + value.entries().size() * ENTRY_BYTES);
        int selected = -1;
        for (int i = 0; i < value.entries().size(); i++) {
            if (value.entries().get(i).draftId().equals(value.selectedDraftId())) selected = i;
        }
        bytes.putInt(MAGIC).putInt(VERSION).putLong(value.capturedAt())
                .putInt(value.entries().size()).putInt(selected);
        for (SqlWorkspace.Entry entry : value.entries()) {
            bytes.putLong(entry.draftId().getMostSignificantBits())
                    .putLong(entry.draftId().getLeastSignificantBits()).putInt(entry.anchor()).putInt(entry.caret());
        }
        return bytes.array();
    }

    static SqlWorkspace decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < HEADER_BYTES || bytes.length > MAX_FILE_BYTES) throw corrupt();
        ByteBuffer input = ByteBuffer.wrap(bytes);
        if (input.getInt() != MAGIC) throw corrupt();
        if (input.getInt() != VERSION) throw new Failure(Code.UNSUPPORTED_VERSION);
        long at = input.getLong();
        int count = input.getInt();
        int selected = input.getInt();
        if (count < 0 || count > SqlWorkspace.MAX_ENTRIES
                || bytes.length != HEADER_BYTES + count * ENTRY_BYTES
                || selected < -1 || selected >= count) throw corrupt();
        try {
            List<SqlWorkspace.Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(input.getLong(), input.getLong());
                entries.add(new SqlWorkspace.Entry(id, input.getInt(), input.getInt()));
            }
            return new SqlWorkspace(at, entries, selected == -1 ? null : entries.get(selected).draftId());
        } catch (IllegalArgumentException invalid) {
            throw corrupt();
        }
    }

    private static Failure corrupt() { return new Failure(Code.CORRUPT); }
}

