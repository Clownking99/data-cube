# Atomic Connection Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make connection configuration persistence atomic, recoverable, and cross-platform without changing its public API or persisting runtime-only properties.

**Architecture:** `ConnectionStore` serializes the complete snapshot in memory, writes a unique temporary file beside the target, copies a structurally valid old file to `.bak`, and replaces the target with `ATOMIC_MOVE` plus a `REPLACE_EXISTING` fallback. Loading parses the primary strictly enough to detect whole-file corruption, skips only malformed individual entries, and reads `.bak` without overwriting the damaged primary.

**Tech Stack:** Java 25 NIO, JUnit 5, Gradle 9.2, CodeGraph.

## Global Constraints

- Work directly on `main`; every completed task is a separate commit.
- Windows is primary, but all persistence code uses cross-platform Java NIO APIs.
- Keep the public constructors and `loadAll`/`saveAll` signatures compatible.
- Never persist `ConnConfig.props`; it can contain runtime-only `__plainPassword`.
- Preserve the current flat JSON schema and encrypted-password field.
- Keep `.testagent/**` local and untracked.
- Use `apply_patch` for edits and CodeGraph before indexed-source exploration.

---

### Task 1: Strict Round-Trip Parsing

**Files:**
- Create: `test/com/datacube/config/ConnectionStoreTest.java`
- Modify: `src/com/datacube/config/ConnectionStore.java:141-216`

**Interfaces:**
- Consumes: `ConnectionStore(Path)`, `saveAll(List<ConnConfig>)`, `loadAll()`.
- Produces: strict top-level array validation and string-aware object-boundary detection.

- [x] **Step 1: Write failing round-trip tests**

Create `ConnectionStoreTest` with:

```java
package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionStoreTest {

    @TempDir Path tempDir;

    @Test
    void roundTripsUnicodeEscapesAndRedisConfiguration() {
        Path file = tempDir.resolve("connections.json");
        ConnectionStore store = new ConnectionStore(file);
        ConnConfig redis = new ConnConfig("redis-一", "缓存 } \" \\ 换行\n名称", DbType.REDIS,
                "redis.local", 6380, "5", "用户", "enc\\密文\n", Map.of());

        store.saveAll(List.of(redis));

        assertEquals(List.of(redis), store.loadAll());
    }

    @Test
    void roundTripsEmptyList() {
        ConnectionStore store = new ConnectionStore(tempDir.resolve("connections.json"));

        store.saveAll(List.of());

        assertEquals(List.of(), store.loadAll());
    }
}
```

- [x] **Step 2: Run the focused test and observe the brace-in-string failure**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.config.ConnectionStoreTest
```

Expected: `roundTripsUnicodeEscapesAndRedisConfiguration` fails because the old parser treats `}` inside the name as the end of the object.

- [x] **Step 3: Make top-level and object parsing structural**

Replace `parseArrayOfObjects`, add `skipWhitespace` and `closingBrace`, and make `closingQuote` throw on an unterminated string:

```java
private static List<Map<String, String>> parseArrayOfObjects(String text) {
    String json = text.strip();
    if (json.length() < 2 || json.charAt(0) != '[' || json.charAt(json.length() - 1) != ']') {
        throw new IllegalArgumentException("连接配置必须是完整 JSON 数组");
    }
    List<Map<String, String>> result = new ArrayList<>();
    int i = skipWhitespace(json, 1);
    if (json.charAt(i) == ']') {
        if (i == json.length() - 1) return result;
        throw new IllegalArgumentException("连接配置数组后存在多余内容");
    }
    while (i < json.length() - 1) {
        if (json.charAt(i) != '{') throw new IllegalArgumentException("连接条目必须是 JSON 对象");
        int end = closingBrace(json, i + 1);
        result.add(parseObject(json.substring(i + 1, end)));
        i = skipWhitespace(json, end + 1);
        if (json.charAt(i) == ']') {
            if (i == json.length() - 1) return result;
            throw new IllegalArgumentException("连接配置数组后存在多余内容");
        }
        if (json.charAt(i) != ',') throw new IllegalArgumentException("连接条目之间缺少逗号");
        i = skipWhitespace(json, i + 1);
    }
    throw new IllegalArgumentException("连接配置数组未闭合");
}

private static int skipWhitespace(String text, int from) {
    int i = from;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
    return i;
}

private static int closingBrace(String text, int from) {
    boolean quoted = false;
    boolean escaped = false;
    for (int i = from; i < text.length(); i++) {
        char ch = text.charAt(i);
        if (escaped) {
            escaped = false;
        } else if (quoted && ch == '\\') {
            escaped = true;
        } else if (ch == '"') {
            quoted = !quoted;
        } else if (!quoted && ch == '}') {
            return i;
        }
    }
    throw new IllegalArgumentException("连接条目对象未闭合");
}

private static int closingQuote(String text, int from) {
    for (int i = from; i < text.length(); i++) {
        if (text.charAt(i) == '\\') { i++; continue; }
        if (text.charAt(i) == '"') return i;
    }
    throw new IllegalArgumentException("字符串未闭合");
}
```

- [x] **Step 4: Run focused tests and commit**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.config.ConnectionStoreTest
git add -- src/com/datacube/config/ConnectionStore.java test/com/datacube/config/ConnectionStoreTest.java
git diff --cached --check
git commit -m "fix: 严格解析连接配置结构"
```

Expected: both tests pass and the commit contains only parser code and its tests.

---

### Task 2: Atomic Replacement and Failure Preservation

**Files:**
- Modify: `test/com/datacube/config/ConnectionStoreTest.java`
- Modify: `src/com/datacube/config/ConnectionStore.java:24-76`

**Interfaces:**
- Produces: package-private `ConnectionStore.PathMover`, `ConnectionStore.SnapshotWriter`, injectable constructors, unique sibling temp files, `.bak`, atomic fallback, and cleanup.

- [x] **Step 1: Add failing atomic-save tests**

Add tests named:

```java
@Test
void secondSaveCopiesPreviousValidSnapshotToBackup() {
    Path file = tempDir.resolve("connections.json");
    ConnectionStore store = new ConnectionStore(file);
    ConnConfig first = config("first", DbType.POSTGRESQL);
    ConnConfig second = config("second", DbType.REDIS);
    store.saveAll(List.of(first));

    store.saveAll(List.of(second));

    assertEquals(List.of(first), new ConnectionStore(tempDir.resolve("connections.json.bak")).loadAll());
    assertEquals(List.of(second), store.loadAll());
}

@Test
void replacementFailurePreservesPrimaryAndCleansTemporaryFile() throws Exception {
    Path file = tempDir.resolve("connections.json");
    ConnectionStore original = new ConnectionStore(file);
    ConnConfig first = config("first", DbType.POSTGRESQL);
    original.saveAll(List.of(first));
    String before = Files.readString(file);
    ConnectionStore failing = new ConnectionStore(file,
            (source, target, options) -> { throw new IOException("replace blocked"); });

    assertThrows(IllegalStateException.class,
            () -> failing.saveAll(List.of(config("second", DbType.REDIS))));

    assertEquals(before, Files.readString(file));
    assertEquals(List.of(first), original.loadAll());
    assertEquals(0, temporaryFiles(file));
}

@Test
void writeFailurePreservesPrimaryAndCleansTemporaryFile() throws Exception {
    Path file = tempDir.resolve("connections.json");
    ConnectionStore original = new ConnectionStore(file);
    ConnConfig first = config("first", DbType.POSTGRESQL);
    original.saveAll(List.of(first));
    String before = Files.readString(file);
    ConnectionStore failing = new ConnectionStore(file,
            (source, target, options) -> Files.move(source, target, options),
            (target, json) -> { throw new IOException("write blocked"); });

    assertThrows(IllegalStateException.class,
            () -> failing.saveAll(List.of(config("second", DbType.REDIS))));

    assertEquals(before, Files.readString(file));
    assertEquals(0, temporaryFiles(file));
}

@Test
void fallsBackWhenAtomicMoveIsUnsupported() {
    Path file = tempDir.resolve("connections.json");
    AtomicInteger moves = new AtomicInteger();
    ConnectionStore store = new ConnectionStore(file, (source, target, options) -> {
        if (moves.getAndIncrement() == 0) {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
        }
        Files.move(source, target, options);
    });

    store.saveAll(List.of(config("redis", DbType.REDIS)));

    assertEquals(2, moves.get());
    assertEquals("redis", store.loadAll().getFirst().id());
}
```

Add these helpers:

```java
private static ConnConfig config(String id, DbType type) {
    return new ConnConfig(id, id, type, "localhost", type.defaultPort(), "0", "user", "encrypted", Map.of());
}

private static long temporaryFiles(Path file) throws IOException {
    String prefix = file.getFileName() + ".";
    try (var paths = Files.list(file.getParent())) {
        return paths.filter(path -> {
            String name = path.getFileName().toString();
            return name.startsWith(prefix) && name.endsWith(".tmp");
        }).count();
    }
}
```

Add imports for `IOException`, `AtomicMoveNotSupportedException`, `Files`, `AtomicInteger`, `assertThrows`, and the existing assertions.

- [x] **Step 2: Run focused tests and observe missing persistence seams**

Run the focused class. Expected: test compilation fails because `PathMover`, `SnapshotWriter`, and the injected constructors do not exist.

- [x] **Step 3: Implement atomic save**

Add a package-private nested functional interface and constructor:

```java
@FunctionalInterface
interface PathMover {
    void move(Path source, Path target, CopyOption... options) throws IOException;
}

@FunctionalInterface
interface SnapshotWriter {
    void write(Path target, String json) throws IOException;
}

private final PathMover mover;
private final SnapshotWriter writer;

public ConnectionStore(Path file) {
    this(file,
            (source, target, options) -> Files.move(source, target, options),
            (target, json) -> Files.writeString(target, json, StandardCharsets.UTF_8));
}

ConnectionStore(Path file, PathMover mover) {
    this(file, mover, (target, json) -> Files.writeString(target, json, StandardCharsets.UTF_8));
}

ConnectionStore(Path file, PathMover mover, SnapshotWriter writer) {
    this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
    this.mover = Objects.requireNonNull(mover, "mover");
    this.writer = Objects.requireNonNull(writer, "writer");
}
```

Add imports for `AtomicMoveNotSupportedException`, `CopyOption`, `StandardCopyOption`, and `Objects`. Replace `saveAll` and add the helpers below:

```java
public synchronized void saveAll(List<ConnConfig> configs) {
    String json = serialize(List.copyOf(Objects.requireNonNull(configs, "configs")));
    Path parent = file.getParent();
    Path temp = null;
    try {
        Files.createDirectories(parent);
        temp = Files.createTempFile(parent, file.getFileName() + ".", ".tmp");
        writer.write(temp, json);
        if (Files.exists(file) && isStructurallyValid(file)) {
            Files.copy(file, backupFile(), StandardCopyOption.REPLACE_EXISTING);
        }
        replace(temp);
        temp = null;
    } catch (IOException e) {
        throw new IllegalStateException("写入连接配置失败: " + e.getMessage(), e);
    } finally {
        if (temp != null) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                LOG.fine("清理连接配置临时文件失败: " + cleanup.getMessage());
            }
        }
    }
}

private static String serialize(List<ConnConfig> configs) {
    StringBuilder sb = new StringBuilder("[\n");
    for (int i = 0; i < configs.size(); i++) {
        if (i > 0) sb.append(",\n");
        sb.append(toJson(configs.get(i)));
    }
    return sb.append("\n]\n").toString();
}

private void replace(Path temp) throws IOException {
    try {
        mover.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
        mover.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}

private Path backupFile() {
    return file.resolveSibling(file.getFileName() + ".bak");
}

private static boolean isStructurallyValid(Path candidate) {
    try {
        parseArrayOfObjects(Files.readString(candidate, StandardCharsets.UTF_8));
        return true;
    } catch (IOException | RuntimeException invalid) {
        LOG.warning("旧连接配置损坏，不覆盖现有备份: " + invalid.getMessage());
        return false;
    }
}
```

The three-argument constructor normalizes the path so temp, primary, and backup always share an absolute parent.

- [x] **Step 4: Run focused tests and commit**

Run the focused class, then commit `ConnectionStore.java` and `ConnectionStoreTest.java` as:

```text
fix: 原子保存连接配置并保留备份
```

Expected: all six tests pass.

---

### Task 3: Backup Recovery and Phase Verification

**Files:**
- Modify: `test/com/datacube/config/ConnectionStoreTest.java`
- Modify: `src/com/datacube/config/ConnectionStore.java:36-59`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-04-atomic-connection-store.md`

**Interfaces:**
- Produces: primary-then-backup `loadAll()` behavior without silent repair of the primary.

- [x] **Step 1: Add failing backup recovery tests**

Add tests:

```java
@Test
void loadsBackupWhenPrimaryStructureIsCorruptWithoutOverwritingPrimary() throws Exception {
    Path file = tempDir.resolve("connections.json");
    ConnectionStore store = new ConnectionStore(file);
    ConnConfig first = config("first", DbType.POSTGRESQL);
    store.saveAll(List.of(first));
    store.saveAll(List.of(config("second", DbType.REDIS)));
    String corrupt = "[] trailing ]";
    Files.writeString(file, corrupt);

    assertEquals(List.of(first), store.loadAll());
    assertEquals(corrupt, Files.readString(file));
}

@Test
void savingOverCorruptPrimaryKeepsLastValidBackup() throws Exception {
    Path file = tempDir.resolve("connections.json");
    Path backup = tempDir.resolve("connections.json.bak");
    ConnectionStore store = new ConnectionStore(file);
    ConnConfig first = config("first", DbType.POSTGRESQL);
    store.saveAll(List.of(first));
    store.saveAll(List.of(config("second", DbType.REDIS)));
    Files.writeString(file, "corrupt");

    store.saveAll(List.of(config("third", DbType.ORACLE)));

    assertEquals(List.of(first), new ConnectionStore(backup).loadAll());
    assertEquals("third", store.loadAll().getFirst().id());
}

@Test
void skipsMalformedEntryAndKeepsValidSibling() throws Exception {
    Path file = tempDir.resolve("connections.json");
    Files.writeString(file, """
            [
              {"name":"bad","type":"REDIS","port":6379},
              {"id":"ok","name":"ok","type":"REDIS","host":"localhost","port":6379,"database":"0","username":"","encryptedPassword":""}
            ]
            """);

    List<ConnConfig> loaded = new ConnectionStore(file).loadAll();

    assertEquals(1, loaded.size());
    assertEquals("ok", loaded.getFirst().id());
}
```

- [x] **Step 2: Run focused tests and observe empty results for corrupt primary**

Run the focused class. Expected: `loadsBackupWhenPrimaryStructureIsCorruptWithoutOverwritingPrimary` fails because the current loader returns an empty list instead of trying `.bak`.

- [x] **Step 3: Implement backup-aware loading**

Replace `loadAll` and add `load(Path)`:

```java
public synchronized List<ConnConfig> loadAll() {
    if (!Files.exists(file)) return new ArrayList<>();
    try {
        return load(file);
    } catch (IOException | RuntimeException primaryFailure) {
        LOG.warning("读取主连接配置失败，尝试备份: " + primaryFailure.getMessage());
    }
    Path backup = backupFile();
    if (!Files.exists(backup)) return new ArrayList<>();
    try {
        return load(backup);
    } catch (IOException | RuntimeException backupFailure) {
        LOG.warning("读取连接配置备份失败: " + backupFailure.getMessage());
        return new ArrayList<>();
    }
}

private static List<ConnConfig> load(Path source) throws IOException {
    String text = Files.readString(source, StandardCharsets.UTF_8);
    List<ConnConfig> out = new ArrayList<>();
    for (Map<String, String> obj : parseArrayOfObjects(text)) {
        try {
            out.add(fromMap(obj));
        } catch (RuntimeException badEntry) {
            LOG.warning("跳过损坏的连接条目: " + badEntry.getMessage());
        }
    }
    return out;
}
```

This only reads the backup; it never writes or moves either file during recovery.

- [x] **Step 4: Document reliability and run full verification**

Add this README note after the local build section:

```markdown
### 连接配置可靠性

连接配置先写入同目录唯一临时文件，再使用原子替换更新主文件；有效旧版本保留为
`connections.json.bak`。若主文件结构损坏，启动时仅从备份读取，不会静默覆盖损坏文件。
```

Then run:

```powershell
.\gradlew.bat clean test
.\gradlew.bat jlink
git diff --check
codegraph sync .
codegraph status
```

Expected: all tests pass with only the opt-in live Redis test skipped, jlink succeeds, and CodeGraph is current.

- [x] **Step 5: Mark this plan complete and commit**

Mark every checkbox `[x]`, then commit the recovery code, tests, README, and this plan as:

```text
fix: 损坏连接配置自动读取备份
```

## Phase Completion Evidence

Record the three commit IDs, every RED/GREEN observation, focused test names, full test totals, jlink result, primary-preservation checks, temp-file cleanup, backup recovery behavior, and CodeGraph status before starting credential protection.
