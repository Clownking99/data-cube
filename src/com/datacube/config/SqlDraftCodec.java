package com.datacube.config;

import com.datacube.spi.model.DbType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bounded version-one bytes. Encoding is not encryption or authentication. */
final class SqlDraftCodec {
    static final int MAX_SQL_BYTES = 1024 * 1024;
    static final int MAX_METADATA_BYTES = 4096;
    static final int MAX_FILE_BYTES = MAX_SQL_BYTES + 4 * MAX_METADATA_BYTES + 64;
    private static final int MAGIC = 0x44434452;
    private static final int VERSION = 1;

    private SqlDraftCodec() { }

    static byte[] encode(SqlDraft draft) throws IOException {
        if (draft == null) throw invalid();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(draft.id().getMostSignificantBits());
                out.writeLong(draft.id().getLeastSignificantBits());
                out.writeLong(draft.modifiedAt());
                writeText(out, draft.connectionId(), MAX_METADATA_BYTES, true);
                writeText(out, draft.connectionType() == null ? null : draft.connectionType().name(), MAX_METADATA_BYTES, true);
                writeText(out, draft.connectionName(), MAX_METADATA_BYTES, true);
                writeText(out, draft.schema(), MAX_METADATA_BYTES, true);
                writeText(out, draft.sql(), MAX_SQL_BYTES, false);
            }
            if (bytes.size() > MAX_FILE_BYTES) throw invalid();
            return bytes.toByteArray();
        } catch (IOException | IllegalArgumentException error) {
            // Never attach a parser cause which could contain user-provided metadata.
            throw invalid();
        }
    }

    static SqlDraft decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_FILE_BYTES) throw invalid();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw invalid();
            UUID id = new UUID(in.readLong(), in.readLong());
            long modifiedAt = in.readLong();
            String connectionId = readText(in, MAX_METADATA_BYTES, true);
            String type = readText(in, MAX_METADATA_BYTES, true);
            String name = readText(in, MAX_METADATA_BYTES, true);
            String schema = readText(in, MAX_METADATA_BYTES, true);
            String sql = readText(in, MAX_SQL_BYTES, false);
            if (in.available() != 0) throw invalid();
            return new SqlDraft(id, modifiedAt, connectionId,
                    type == null ? null : DbType.valueOf(type), name, schema, sql);
        } catch (IOException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    private static void writeText(DataOutputStream out, String text, int limit, boolean nullable) throws IOException {
        if (text == null) {
            if (!nullable) throw invalid();
            out.writeInt(-1);
            return;
        }
        // Valid UTF-8 uses at least as many bytes as UTF-16 code units.
        if (text.length() > limit) throw invalid();
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        int length = encoded.remaining();
        if (length > limit) throw invalid();
        byte[] utf8 = new byte[length];
        encoded.get(utf8);
        out.writeInt(length);
        out.write(utf8);
    }

    private static String readText(DataInputStream in, int limit, boolean nullable) throws IOException {
        int length = in.readInt();
        if (length == -1 && nullable) return null;
        if (length < 0 || length > limit || length > in.available()) throw invalid();
        byte[] utf8 = new byte[length];
        in.readFully(utf8);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(utf8)).toString();
    }

    private static IOException invalid() {
        return new IOException("Invalid SQL draft format");
    }
}
