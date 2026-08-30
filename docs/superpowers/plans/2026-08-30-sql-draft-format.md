# SQL Draft Format Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver P1.1, an exact, bounded SQL draft value and versioned binary codec, independently testable without files or databases.

**Architecture:** A public immutable value owns identity validation and redacted diagnostics. A package-private codec owns a strict byte contract, rejects invalid/unbounded input, and has no external side effects. Persistence, scheduling, and UI each have their own subsequent plan; this foundation is not user-visible recovery.

**Tech Stack:** Java25, JUnit Jupiter5.11.3, Gradle9.2.0; JDK byte streams and strict UTF-8 only.

## Global Constraints

- Java25 / JavaFX25 / JUnit Jupiter5.11.3；不增加第三方依赖，不改 JDBC、历史文件或导出语义。
- 仅使用合成文本、临时目录与替身网关；不读取、不修改、不暂存、不清理 `.testagent/`。
- 不新增网络、遥测、AI、数据库自动请求、密码存储或结果/事务持久化；不推送、打 tag、安装或发布。
- SQL 保留空白、换行和 Unicode 原文；不按 SQL 去重、不截断；编码/容量超限必须显式失败并保留已有版本。
- 每草稿 SQL 最多1MiB UTF-8；每个可空元数据字符串最多4096 UTF-8字节。
- 单个草稿文件上限为1MiB+4×4096+64字节；在读取/分配前限制长度。
- This task changes only the three files named below. Worktree: `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`; no main merge until complete P1 acceptance.

---

### Task 1: Exact SQL draft value and strict format

**Files:**
- Create: `src/com/datacube/config/SqlDraft.java` — immutable identity and safe diagnostic string.
- Create: `src/com/datacube/config/SqlDraftCodec.java` — byte serialization only.
- Test: `test/com/datacube/config/SqlDraftCodecTest.java` — independent wire fixtures, boundaries and corruption rejection.

**Interfaces:**
- Consumes: existing `com.datacube.spi.model.DbType` constants `POSTGRESQL`, `ORACLE`, `REDIS`.
- Produces: public record `SqlDraft(UUID id, long modifiedAt, String connectionId, DbType connectionType, String connectionName, String schema, String sql)`.
- Produces: package-private static `SqlDraftCodec.encode(SqlDraft): byte[] throws IOException`, `decode(byte[]): SqlDraft throws IOException`; constants `MAX_SQL_BYTES`, `MAX_METADATA_BYTES`, `MAX_FILE_BYTES` available to same-package store.
- ID and SQL nonnull, timestamp nonnegative; ID/type both absent or both present, connection ID nonblank, Redis not accepted. Display name/schema may be independently null/empty and never select a database.
- Wire order: magic int `0x44434452`, version int1, UUID long+long, timestamp long, five length-prefixed UTF-8 fields in order connection ID/type enum name/display name/schema/SQL. Nullable fields use length-1, SQL never null. No trailing bytes accepted.
- Size limits are bytes, not Java chars. Reject malformed UTF-8/unpaired surrogates, unknown magic/version/type, invalid lengths, truncated data and trailing bytes. Invalid input uses sanitized IOException; record validation messages and toString never include SQL or metadata.
- No filesystem, session, provider, network, logging, or crypto dependency in these classes.

- [ ] **Step 1: Establish compilable RED baseline and write complete tests.**

Add the following intentionally behaviorless stubs (record accessors are generated). They exist only so RED failures measure behavior rather than missing symbols; replace them in Step3.

`src/com/datacube/config/SqlDraft.java`:

```java
package com.datacube.config;

import com.datacube.spi.model.DbType;
import java.util.UUID;

public record SqlDraft(UUID id, long modifiedAt, String connectionId,
                       DbType connectionType, String connectionName,
                       String schema, String sql) { }
```

`src/com/datacube/config/SqlDraftCodec.java`:

```java
package com.datacube.config;

import java.io.IOException;

final class SqlDraftCodec {
    static final int MAX_SQL_BYTES = 1024 * 1024;
    static final int MAX_METADATA_BYTES = 4096;
    static final int MAX_FILE_BYTES = MAX_SQL_BYTES + 4 * MAX_METADATA_BYTES + 64;
    private SqlDraftCodec() { }
    static byte[] encode(SqlDraft draft) throws IOException { return new byte[0]; }
    static SqlDraft decode(byte[] bytes) throws IOException { return null; }
}
```

`test/com/datacube/config/SqlDraftCodecTest.java`:

```java
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
import java.util.Arrays;
import java.util.UUID;

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
```

- [ ] **Step 2: Run RED.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftCodecTest --rerun-tasks --no-daemon --console=plain
```

Expected exit1: assertions for exact bytes, decoded fields and exception rejection fail against stubs. Compilation must succeed. Capture actual failing counts and representative output before any Step3 implementation; no invented RED evidence.

- [ ] **Step 3: Replace the stubs with complete implementation.**

`src/com/datacube/config/SqlDraft.java`:

```java
package com.datacube.config;

import com.datacube.spi.model.DbType;
import java.util.UUID;

/** Exact local editor text and a saved connection identity, never credentials. */
public record SqlDraft(UUID id, long modifiedAt, String connectionId,
                       DbType connectionType, String connectionName,
                       String schema, String sql) {
    public SqlDraft {
        if (id == null || modifiedAt < 0 || sql == null) {
            throw new IllegalArgumentException("Invalid SQL draft value");
        }
        if ((connectionId == null) != (connectionType == null)
                || (connectionId != null && connectionId.isBlank())
                || (connectionType != null && connectionType != DbType.POSTGRESQL
                    && connectionType != DbType.ORACLE)) {
            throw new IllegalArgumentException("Invalid SQL draft identity");
        }
    }

    @Override
    public String toString() {
        return "SqlDraft[id=" + id + ", modifiedAt=" + modifiedAt + ", sqlChars=" + sql.length() + "]";
    }
}
```

`src/com/datacube/config/SqlDraftCodec.java`:

```java
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
```

- [ ] **Step 4: Verify focused GREEN then full fresh regression.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftCodecTest --rerun-tasks --no-daemon --console=plain
```

Expected exit0, all codec tests executed with no failures/skips. Then run the complete suite once with actual JavaFX enabled and restore the environment:

```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally {
    $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions
}
exit $draftTestExit
```

Expected exit0; baseline is138 suites/1216 tests/0 failures/3 live-service skips. Report actual new XML totals and distinct skips, not an inferred new count. Existing compiler note in `SqlEditorResultFilterContractTest` is baseline evidence, not a new warning fix mandate. No coverage percentage claim without measurement.

- [ ] **Step 5: Self-review, commit exact files, and report evidence.**

```powershell
git diff --check
git add -- src/com/datacube/config/SqlDraft.java src/com/datacube/config/SqlDraftCodec.java test/com/datacube/config/SqlDraftCodecTest.java
git commit -m "feat: define exact bounded SQL draft format"
```

Report actual RED/GREEN command/output, full suite totals, file list, commit, deviations and concerns. Map each interface requirement to a named test. Do not claim file persistence or restore UI is implemented. Controller performs fresh task review and continues P1.2 storage.

## Plan self-review

P1.1 coverage: exact independent wire fixture; nullable/empty/Unicode identity; before/at/after SQL and each user metadata byte limit; maximum simultaneous fields; invalid headers, every truncation, trailing data, null and oversized input, malicious lengths; invalid identity/enum/SQL; malformed UTF-8/UTF-16; redacted diagnostics. No external resources or dependency changes. P1.2-P1.5 remain separate planned subsystems in the design, not completed by this task.
