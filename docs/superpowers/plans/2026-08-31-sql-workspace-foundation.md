# SQL Workspace Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 P2.1 的有界清单格式与纯恢复排序，供后续存储/UI 使用，不改变当前应用行为。

**Architecture:** 不可变清单只引用 P1 草稿 UUID；包内 codec 转换精确版本化字节；公开纯解析器从已验证草稿列表产生有序恢复项与缺失列表。磁盘、设置、退出与 UI 由后续独立计划接入。

**Tech Stack:** Java 25、JavaFX 25、JUnit Jupiter 5.11.3、Gradle wrapper 9.2.0；无新依赖。

## Global Constraints

- Java 25、JavaFX 25、JUnit Jupiter 5.11.3、Gradle wrapper 9.2.0；不添加依赖。
- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 工作区清单只含草稿 UUID、顺序、选中项、时间、光标/选择锚点；不复制 SQL、连接身份、Schema、凭据或结果集。连接身份与 Schema 由 P1 草稿提供。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- P2 完整验收和整分支审查通过才本地合并 main；基础模块完成不等于用户入口完成。
- 本计划仅实现设计文档“P2.1 精确数据契约”；不修改 P1 存储/调度/编辑器、不加磁盘写入、偏好或 UI。

---

## Execution context

Worktree: `D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery`，branch `codex/sql-workspace-recovery`，起点 `7710ecb526d10a22e3fbff65367c50b04e44ed9d`。基线 session 65868：`test --no-daemon --console=plain` exit 0 / 46 秒，150 suites、1373 total、1370 passed、3 原有 live skipped、0 failures/errors。既有 `SqlEditorResultFilterContractTest` unchecked 编译提示及 scoped `JAVA_TOOL_OPTIONS` 提示不是新增噪声，不删除旧测试来消除它们。

主代理保有规格、路线图、ledger 和验收文档；实现代理只改下列 5 个 source/test 文件，最后精确暂存提交。先行为 RED 后 GREEN；缺类编译失败不是行为 RED。允许先写以下无行为骨架使测试编译，再记录真实断言失败；骨架不提交、不作完成证据。

### Task 1: Bounded workspace manifest and pure recovery resolution

**Files:**
- Create: `src/com/datacube/config/SqlWorkspace.java`
- Create: `src/com/datacube/config/SqlWorkspaceCodec.java`
- Create: `src/com/datacube/config/SqlWorkspaceRecovery.java`
- Test: `test/com/datacube/config/SqlWorkspaceCodecTest.java`
- Test: `test/com/datacube/config/SqlWorkspaceRecoveryTest.java`

**Interfaces:**
- Consumes: existing `SqlDraft(UUID id, long modifiedAt, String connectionId, DbType connectionType, String connectionName, String schema, String sql)` with redacted `toString()`; no service calls.
- Produces: `public record SqlWorkspace(long capturedAt, List<Entry> entries, UUID selectedDraftId)` and nested `public record Entry(UUID draftId, int anchor, int caret)`; constants `MAX_ENTRIES=100`, `MAX_POSITION=1048576`.
- Produces: package-private `SqlWorkspaceCodec.encode(SqlWorkspace):byte[]`, `decode(byte[]):SqlWorkspace`, both `throws IOException`; `MAX_FILE_BYTES=2424`; nested `Code { CORRUPT, UNSUPPORTED_VERSION }`, `Failure extends IOException` with `Code code()`.
- Produces: `public static SqlWorkspaceRecovery.Resolution resolve(SqlWorkspace workspace, List<SqlDraft> drafts)`; output records `Resolution(List<ResolvedTab> tabs, UUID selectedDraftId, List<UUID> missingDraftIds)`, `ResolvedTab(SqlDraft draft, int anchor, int caret)`.
- Wire: big-endian magic `0x44435753`, version `1`, long time, int count, int selectedIndex (-1 or member index); then UUID most/least long, int anchor, int caret per entry. Header 24 bytes, each entry 24, max 2424.
- Manifest validation: nonnegative time, 0..100 entries, no null list/entry/UUID or duplicate IDs, selected UUID null or member, both positions 0..1048576. Immutable defensive list copy.
- Decode validation: pre-allocation byte bound/count/length, invalid header/semantic fields/truncation/trailing bytes rejected. Known magic with non-1 version => UNSUPPORTED_VERSION; all other bad format => CORRUPT. Fixed error `Invalid SQL workspace format: <CODE>` and no cause.
- Resolution: input drafts 0..100, no null or duplicate UUID; lookup strictly by UUID, preserve available order and exact SqlDraft objects/fields; collect absent UUIDs in manifest order. Preserve selected UUID if available, else first available when an originally selected item is missing; null selection stays null. Positions unchanged here: future FX caller clamps to actual displayed length. Result lists immutable; no mutation/I/O/network/FX.

- [ ] **Step 1: Add exact behavior tests and compile-only stubs**

`test/com/datacube/config/SqlWorkspaceCodecTest.java`:

```java
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
```

`test/com/datacube/config/SqlWorkspaceRecoveryTest.java`:

```java
package com.datacube.config;

import com.datacube.spi.model.DbType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceRecoveryTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);

    @Test void resolvesByIdInWorkspaceOrderWithoutChangingTextOrPositions() {
        SqlDraft a = draft(A, " \r\nselect '😀';\r\n\t ");
        SqlDraft b = draft(B, "");
        SqlWorkspace workspace = new SqlWorkspace(40, List.of(entry(B), new SqlWorkspace.Entry(A, 999, 2)), A);
        var result = SqlWorkspaceRecovery.resolve(workspace, List.of(a, draft(C, "ignored"), b));
        assertEquals(List.of(B, A), result.tabs().stream().map(tab -> tab.draft().id()).toList());
        assertEquals(List.of(), result.missingDraftIds());
        assertEquals(A, result.selectedDraftId());
        assertSame(a, result.tabs().get(1).draft());
        assertEquals(" \r\nselect '😀';\r\n\t ", result.tabs().get(1).draft().sql());
        assertEquals("", result.tabs().get(0).draft().sql());
        assertEquals("private-connection", result.tabs().get(1).draft().connectionId());
        assertEquals(DbType.ORACLE, result.tabs().get(1).draft().connectionType());
        assertEquals("same-name", result.tabs().get(1).draft().connectionName());
        assertEquals(" private-schema ", result.tabs().get(1).draft().schema());
        assertEquals(10, result.tabs().get(1).draft().modifiedAt());
        assertEquals(999, result.tabs().get(1).anchor());
        assertEquals(2, result.tabs().get(1).caret());
        assertFalse(result.toString().contains("select"));
        assertFalse(result.toString().contains("private-schema"));
        assertFalse(result.toString().contains("private-connection"));
    }

    @Test void missingSelectionFallsBackToFirstAvailableWithoutNameSubstitution() {
        var result = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0,
                List.of(entry(C), entry(B), entry(A), entry(D)), C), List.of(draft(A, "a"), draft(B, "b")));
        assertEquals(List.of(C, D), result.missingDraftIds());
        assertEquals(List.of(B, A), result.tabs().stream().map(tab -> tab.draft().id()).toList());
        assertEquals(B, result.selectedDraftId());
    }

    @Test void retainsNullSelectionWhenSelectedPageWasNotSql() {
        var result = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0, List.of(entry(A)), null), List.of(draft(A, "a")));
        assertEquals(1, result.tabs().size());
        assertNull(result.selectedDraftId());
    }

    @Test void allMissingAndEmptyWorkspacesProduceNoTabs() {
        var missing = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0, List.of(entry(A), entry(B)), B), List.of());
        assertEquals(List.of(A, B), missing.missingDraftIds());
        assertEquals(List.of(), missing.tabs());
        assertNull(missing.selectedDraftId());
        var empty = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0, List.of(), null), List.of(draft(A, "unused")));
        assertEquals(List.of(), empty.tabs());
        assertEquals(List.of(), empty.missingDraftIds());
        assertNull(empty.selectedDraftId());
    }

    @Test void resultListsAreImmutableAndDetachedFromCallerList() {
        List<SqlDraft> source = new ArrayList<>(List.of(draft(A, "a")));
        var result = SqlWorkspaceRecovery.resolve(new SqlWorkspace(0, List.of(entry(A), entry(B)), A), source);
        source.clear();
        assertEquals(A, result.tabs().getFirst().draft().id());
        assertEquals(List.of(B), result.missingDraftIds());
        assertThrows(UnsupportedOperationException.class, () -> result.tabs().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.missingDraftIds().clear());
    }

    @Test void rejectsInvalidCandidateSnapshotsWithFixedDiagnostics() {
        SqlWorkspace empty = new SqlWorkspace(0, List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> SqlWorkspaceRecovery.resolve(null, List.of()));
        for (List<SqlDraft> bad : Arrays.asList(null, Arrays.asList(draft(A, "secret"), null),
                List.of(draft(A, "secret"), draft(A, "other")))) {
            var error = assertThrows(IllegalArgumentException.class, () -> SqlWorkspaceRecovery.resolve(empty, bad));
            assertEquals("Invalid SQL workspace draft snapshot", error.getMessage());
            assertNull(error.getCause());
        }
        List<SqlDraft> candidates = new ArrayList<>();
        for (int i = 0; i < 101; i++) candidates.add(draft(new UUID(0, i), "synthetic"));
        assertThrows(IllegalArgumentException.class, () -> SqlWorkspaceRecovery.resolve(empty, candidates));
        assertEquals(List.of(), SqlWorkspaceRecovery.resolve(empty, candidates.subList(0, 100)).tabs());
    }

    private static SqlWorkspace.Entry entry(UUID id) { return new SqlWorkspace.Entry(id, 0, 0); }
    private static SqlDraft draft(UUID id, String sql) {
        return new SqlDraft(id, 10, "private-connection", DbType.ORACLE, "same-name", " private-schema ", sql);
    }
}
```

Compile-only starting skeletons (no behavior yet):

```java
// SqlWorkspace.java
package com.datacube.config;
import java.util.List;
import java.util.UUID;
public record SqlWorkspace(long capturedAt, List<Entry> entries, UUID selectedDraftId) {
    public static final int MAX_ENTRIES = 100;
    public static final int MAX_POSITION = 1048576;
    public record Entry(UUID draftId, int anchor, int caret) { }
}
```

```java
// SqlWorkspaceCodec.java
package com.datacube.config;
import java.io.IOException;
final class SqlWorkspaceCodec {
    static final int MAX_FILE_BYTES = 2424;
    enum Code { CORRUPT, UNSUPPORTED_VERSION }
    static final class Failure extends IOException {
        private final Code code;
        Failure(Code code) { super("Invalid SQL workspace format: " + code); this.code = code; }
        Code code() { return code; }
    }
    static byte[] encode(SqlWorkspace value) throws IOException { return new byte[0]; }
    static SqlWorkspace decode(byte[] bytes) throws IOException { throw new Failure(Code.CORRUPT); }
}
```

```java
// SqlWorkspaceRecovery.java
package com.datacube.config;
import java.util.List;
import java.util.UUID;
public final class SqlWorkspaceRecovery {
    public record ResolvedTab(SqlDraft draft, int anchor, int caret) { }
    public record Resolution(List<ResolvedTab> tabs, UUID selectedDraftId, List<UUID> missingDraftIds) { }
    public static Resolution resolve(SqlWorkspace workspace, List<SqlDraft> drafts) {
        return new Resolution(List.of(), null, List.of());
    }
}
```

- [ ] **Step 2: Run behavior RED and record before GREEN**

Run in this worktree:

```powershell
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
./gradlew.bat test --tests com.datacube.config.SqlWorkspaceCodecTest --tests com.datacube.config.SqlWorkspaceRecoveryTest --no-daemon --console=plain
```

Expected exit 1, assertion failures for empty encoded bytes, missing validation, missing resolved IDs/selection. Do not claim compiler/import errors as RED. Record failing XML cases before replacing skeletons; report RED to controller, then proceed with GREEN (no human confirmation).

- [ ] **Step 3: Replace skeletons with implementation**

`src/com/datacube/config/SqlWorkspace.java`:

```java
package com.datacube.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Layout references only. SQL and connection context remain in their draft checkpoints. */
public record SqlWorkspace(long capturedAt, List<Entry> entries, UUID selectedDraftId) {
    public static final int MAX_ENTRIES = 100;
    public static final int MAX_POSITION = 1024 * 1024;

    public SqlWorkspace {
        if (capturedAt < 0 || entries == null || entries.size() > MAX_ENTRIES) throw invalid();
        Set<UUID> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (entry == null || !ids.add(entry.draftId())) throw invalid();
        }
        if (selectedDraftId != null && !ids.contains(selectedDraftId)) throw invalid();
        entries = List.copyOf(entries);
    }

    /** UTF-16 offsets in the editor, not SQL byte offsets; a selection may be reversed. */
    public record Entry(UUID draftId, int anchor, int caret) {
        public Entry {
            if (draftId == null || anchor < 0 || caret < 0
                    || anchor > MAX_POSITION || caret > MAX_POSITION) throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid SQL workspace value");
    }
}
```

`src/com/datacube/config/SqlWorkspaceCodec.java`:

```java
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
```

`src/com/datacube/config/SqlWorkspaceRecovery.java`:

```java
package com.datacube.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure layout-to-checkpoint resolution. No files, database calls or UI construction. */
public final class SqlWorkspaceRecovery {
    private SqlWorkspaceRecovery() { }

    public record ResolvedTab(SqlDraft draft, int anchor, int caret) { }
    public record Resolution(List<ResolvedTab> tabs, UUID selectedDraftId, List<UUID> missingDraftIds) {
        public Resolution {
            tabs = List.copyOf(tabs);
            missingDraftIds = List.copyOf(missingDraftIds);
        }
    }

    public static Resolution resolve(SqlWorkspace workspace, List<SqlDraft> drafts) {
        if (workspace == null || drafts == null || drafts.size() > SqlWorkspace.MAX_ENTRIES) throw invalid();
        Map<UUID, SqlDraft> byId = new HashMap<>();
        for (SqlDraft draft : drafts) {
            if (draft == null || byId.putIfAbsent(draft.id(), draft) != null) throw invalid();
        }
        List<ResolvedTab> tabs = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        UUID selected = null;
        for (SqlWorkspace.Entry entry : workspace.entries()) {
            SqlDraft draft = byId.get(entry.draftId());
            if (draft == null) {
                missing.add(entry.draftId());
            } else {
                tabs.add(new ResolvedTab(draft, entry.anchor(), entry.caret()));
                if (entry.draftId().equals(workspace.selectedDraftId())) selected = entry.draftId();
            }
        }
        if (selected == null && workspace.selectedDraftId() != null && !tabs.isEmpty()) {
            selected = tabs.getFirst().draft().id();
        }
        return new Resolution(tabs, selected, missing);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid SQL workspace draft snapshot");
    }
}
```

- [ ] **Step 4: Focused GREEN, then one full regression**

Run the focused command from Step 2; expected exit 0, all cases pass, zero skips in these two suites. Then run once:

```powershell
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$workspacePreviousJavaOptions=$env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
    ./gradlew.bat test --rerun-tasks --no-daemon --console=plain
} finally { $env:JAVA_TOOL_OPTIONS=$workspacePreviousJavaOptions }
```

Expected exit 0, no new failures/skips; calculate actual XML counts rather than copying baseline counts. If a fixture has an import/type defect, repair that defect and rerun RED before behavior implementation. Do not suppress failure or weaken an assertion to match broken production behavior.

- [ ] **Step 5: Self-review, commit exact files, report evidence**

Review assertions against each interface/edge above. Record exact test names for every behavior, RED command/output, GREEN command/output, full suite totals and baseline warnings. No coverage percentage claim without a measured report.

```powershell
git diff --check
git add src/com/datacube/config/SqlWorkspace.java src/com/datacube/config/SqlWorkspaceCodec.java src/com/datacube/config/SqlWorkspaceRecovery.java test/com/datacube/config/SqlWorkspaceCodecTest.java test/com/datacube/config/SqlWorkspaceRecoveryTest.java
git commit -m "feat: add bounded SQL workspace manifest and recovery resolution"
```

Controller then generates review-package using the frozen pre-dispatch BASE and obtains independent spec+quality review. No P2 integration/main merge on this foundation-only task. Output a Requirement | Evidence table with exact tests in the report. A full test run does not prove disk persistence, restart, close/exit or desktop behavior: those remain later gates.

## Self-review of plan

P2.1 exact contract covered by Task 1 value/codec/resolve tests; P2.2–P2.5 remain separate implementation scopes, not missing steps disguised as complete here. Interfaces match all code blocks. Byte count 24+100*24=2424. Selection -1 remains valid with zero entries; invalid positive index rejected. Cases include 99/100/101, both position boundaries, every byte truncation, raw CRLF/emoji, duplicate/missing IDs and non-SQL selected state. No placeholder implementation in GREEN; compile-only RED skeletons are explicitly temporary.
