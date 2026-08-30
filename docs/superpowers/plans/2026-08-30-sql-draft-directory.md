# SQL Draft Directory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the P1.2 filesystem boundary: one live writer, bounded reads/scans, and failure-safe publication in an injected local directory.

**Architecture:** Package-private SqlDraftDirectory owns the OS file lock and filesystem operations; the subsequent SqlDraftStore owns document validation, capacity, expiry and preferences. This task has no default user-home path, UI, background worker, or database dependency. Separating policy from I/O permits deterministic failure tests without production failure switches.

**Tech Stack:** Java25 NIO FileChannel/FileLock/Files, JUnit Jupiter5.11.3, Gradle9.2.0.

## Global Constraints

- Java25 / JavaFX25 / JUnit Jupiter5.11.3；不增加第三方依赖，不改 JDBC、历史文件或导出语义。
- 仅使用合成文本、临时目录与替身网关；不读取、不修改、不暂存、不清理 `.testagent/`。
- 不新增网络、遥测、AI、数据库自动请求、密码存储或结果/事务持久化；不推送、打 tag、安装或发布。
- SQL 保留空白、换行和 Unicode 原文；不按 SQL 去重、不截断；编码/容量超限必须显式失败并保留已有版本。
- 单个草稿文件上限为1MiB+4×4096+64字节；在读取/分配前限制长度。
- 使用应用级单写者 `FileLock`，同一配置目录的第二实例可以继续编辑，但草稿功能明确不可用，不偷锁、不并行覆盖、不自动重试写入。锁文件不删除。
- 更新采用同目录独占临时文件→写入并force→原子替换，不支持原子移动则失败，不降级为覆盖写。失败临时文件尽力清除，无法清除必须报告。
- Directory enumeration is nonrecursive, at most512 direct entries including lock/temporary/unrelated entries. Entry513 fails without deleting anything. Content reads use NOFOLLOW_LINKS and a byte budget; only canonical UUID.draft and preferences.bin may be read/published/deleted through this API.
- Work exclusively in `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`; only the two source/test files below belong to the implementer.

---

### Task 1: Locked and bounded draft directory I/O

**Files:**
- Create: `src/com/datacube/config/SqlDraftDirectory.java` — filesystem boundary, no retention or document policy.
- Test: `test/com/datacube/config/SqlDraftDirectoryTest.java` — real isolated temporary files plus narrow failure seams.

**Interfaces:**
- Consumes: same-package `SqlDraftCodec.MAX_FILE_BYTES` from P1.1.
- Produces: package-private `SqlDraftDirectory implements AutoCloseable`, `static open(Path)`, overload `open(Path, Writer, Mover, Cleaner)` for deterministic test fault injection; `entries(): List<String>`, `read(String,int): byte[]` (null only absent), `publish(String,byte[])`, `delete(String)`, `close()`, all IOException.
- Directory parent must already exist; resolve trusted parent once, create only the named child if absent, reject symbolic/non-directory child. Keep root identity and lock-file identity; recheck before operations. This is defense against accidental replacement, not a sandbox against a hostile same-user process that can rename directories between checks.
- Publish checks the old target identity/size/timestamps again after writing the temporary file; a changed target is not overwritten. File bytes are forced before rename. No directory-fsync/power-loss guarantee.
- Failure exposes only a stage enum, no raw exception cause, SQL, connection metadata or full paths. Cleanup failure supersedes original stage because leftover SQL bytes require visible handling. Failed temporary artifacts are not automatically swept at startup by this layer.
- Reject noncanonical case aliases before reading, publishing or deleting: a preexisting uppercase UUID filename or `Preferences.bin` is not implicitly adopted/overwritten through the canonical lowercase name on Windows. Detect aliases from bounded direct entry names on all platforms.
- Preference parsing, unknown/corrupt same-ID preservation, 100 drafts/32MiB, 7-day expiry, clear barriers, availability UI and close/drain ordering are SqlDraftStore/coordinator work, not responsibilities of this I/O task.

- [x] **Step 1: Create compilable stubs and complete tests.**

Use this compile-only stub before Step2; no live file behavior exists before RED.

`src/com/datacube/config/SqlDraftDirectory.java`:

```java
package com.datacube.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class SqlDraftDirectory implements AutoCloseable {
    enum Stage { OPEN, BUSY, CLOSED, UNSAFE, SCAN_LIMIT, READ, WRITE, PUBLISH, CLEANUP, DELETE, CLOSE }
    static final class Failure extends IOException {
        private final Stage stage;
        Failure(Stage stage) { super("SQL draft I/O failed: " + stage); this.stage = stage; }
        Stage stage() { return stage; }
    }
    @FunctionalInterface interface Writer { void write(Path path, byte[] bytes) throws IOException; }
    @FunctionalInterface interface Mover { void move(Path source, Path target) throws IOException; }
    @FunctionalInterface interface Cleaner { void delete(Path path) throws IOException; }
    static SqlDraftDirectory open(Path requested) throws IOException { return null; }
    static SqlDraftDirectory open(Path requested, Writer writer, Mover mover, Cleaner cleaner) throws IOException { return null; }
    List<String> entries() throws IOException { return List.of(); }
    byte[] read(String name, int limit) throws IOException { return null; }
    void publish(String name, byte[] bytes) throws IOException { }
    void delete(String name) throws IOException { }
    @Override public void close() throws IOException { }
    static void writeForced(Path path, byte[] bytes) throws IOException { }
    static void moveAtomic(Path source, Path target) throws IOException { }
}
```

`test/com/datacube/config/SqlDraftDirectoryTest.java`:

```java
package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SqlDraftDirectoryTest {
    @TempDir Path temp;
    private static final String NAME = "00112233-4455-6677-8899-aabbccddeeff.draft";
    private static final byte[] OLD = {1, 2, 3};
    private static final byte[] NEW = {4, 5, 6, 7};

    @Test void publishesReopensReadsAndKeepsLockFile() throws Exception {
        Path root = temp.resolve("drafts");
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            assertNull(directory.read(NAME, 10));
            directory.publish(NAME, OLD);
            directory.publish(NAME, NEW);
            directory.publish("preferences.bin", new byte[]{1});
            assertArrayEquals(NEW, Files.readAllBytes(root.resolve(NAME)));
            assertArrayEquals(NEW, directory.read(NAME, 4));
            assertEquals(3, directory.entries().size());
        }
        assertTrue(Files.isRegularFile(root.resolve(".writer.lock")));
        try (SqlDraftDirectory reopened = SqlDraftDirectory.open(root)) {
            assertArrayEquals(NEW, reopened.read(NAME, 4));
            reopened.delete(NAME);
            reopened.delete(NAME);
            assertNull(reopened.read(NAME, 4));
            assertArrayEquals(new byte[]{1}, reopened.read("preferences.bin", 8));
        }
    }

    @Test void secondWriterFailsWithoutBreakingFirstAndCloseIsIdempotent() throws Exception {
        Path root = temp.resolve("drafts");
        SqlDraftDirectory first = SqlDraftDirectory.open(root);
        assertNotNull(first);
        try {
            assertStage(SqlDraftDirectory.Stage.BUSY, () -> SqlDraftDirectory.open(root));
            first.publish(NAME, OLD);
            assertArrayEquals(OLD, first.read(NAME, 3));
        } finally { first.close(); }
        first.close();
        assertStage(SqlDraftDirectory.Stage.CLOSED, first::entries);
        assertStage(SqlDraftDirectory.Stage.CLOSED, () -> first.publish(NAME, NEW));
        try (SqlDraftDirectory second = SqlDraftDirectory.open(root)) {
            assertArrayEquals(OLD, second.read(NAME, 3));
        }
    }

    @Test void operatingSystemLockRejectsAnotherProcessUntilClose() throws Exception {
        Path root = temp.resolve("drafts");
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            assertEquals(23, probeLock(root.resolve(".writer.lock")));
            directory.publish(NAME, OLD);
            assertArrayEquals(OLD, directory.read(NAME, 3));
        }
        assertEquals(0, probeLock(root.resolve(".writer.lock")));
    }

    private static int probeLock(Path lockPath) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classes = Path.of(LockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(java.toString(), "-cp", classes, LockProbe.class.getName(), lockPath.toString()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "synthetic lock probe timed out");
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class LockProbe {
        public static void main(String[] args) throws IOException {
            int exit;
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                exit = lock == null ? 23 : 0;
            }
            System.exit(exit);
        }
    }

    @Test void unsupportedAtomicMoveKeepsOldFileAndCleansTemporary() throws Exception {
        Path root = temp.resolve("drafts");
        seed(root);
        AtomicInteger calls = new AtomicInteger();
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root, SqlDraftDirectory::writeForced,
                (source, target) -> { calls.incrementAndGet(); throw new AtomicMoveNotSupportedException("synthetic", "synthetic", "test"); }, Files::deleteIfExists)) {
            assertStage(SqlDraftDirectory.Stage.PUBLISH, () -> directory.publish(NAME, NEW));
            assertEquals(1, calls.get());
            assertArrayEquals(OLD, directory.read(NAME, 3));
            assertEquals(2, directory.entries().size());
        }
    }

    @Test void failedWritePreservesOldFileAndCleanupFailureIsVisible() throws Exception {
        Path root = temp.resolve("drafts");
        seed(root);
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root,
                (path, bytes) -> { Files.write(path, new byte[]{9}); throw new IOException("private-sql"); },
                SqlDraftDirectory::moveAtomic, Files::deleteIfExists)) {
            assertStage(SqlDraftDirectory.Stage.WRITE, () -> directory.publish(NAME, NEW));
            assertArrayEquals(OLD, directory.read(NAME, 3));
            assertEquals(2, directory.entries().size());
        }
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root,
                (path, bytes) -> { throw new IOException("private-sql"); }, SqlDraftDirectory::moveAtomic,
                path -> { throw new IOException("private-path"); })) {
            SqlDraftDirectory.Failure failure = assertStage(SqlDraftDirectory.Stage.CLEANUP, () -> directory.publish(NAME, NEW));
            assertNull(failure.getCause());
            assertEquals("SQL draft I/O failed: CLEANUP", failure.getMessage());
            assertArrayEquals(OLD, directory.read(NAME, 3));
            assertEquals(3, directory.entries().size());
        }
    }

    @Test void changedTargetIsNotOverwrittenAfterTemporaryWrite() throws Exception {
        Path root = temp.resolve("drafts");
        seed(root);
        byte[] external = {8, 8, 8, 8, 8, 8};
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root,
                (path, bytes) -> { SqlDraftDirectory.writeForced(path, bytes); Files.write(root.resolve(NAME), external); },
                SqlDraftDirectory::moveAtomic, Files::deleteIfExists)) {
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.publish(NAME, NEW));
            assertArrayEquals(external, directory.read(NAME, 6));
            assertEquals(2, directory.entries().size());
        }
    }

    @Test void readAndPublishRejectOversizeWithoutTruncating() throws Exception {
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(temp.resolve("drafts"))) {
            assertNotNull(directory);
            directory.publish(NAME, NEW);
            assertStage(SqlDraftDirectory.Stage.READ, () -> directory.read(NAME, 3));
            assertArrayEquals(NEW, directory.read(NAME, 4));
            assertStage(SqlDraftDirectory.Stage.WRITE, () -> directory.publish(NAME, new byte[SqlDraftCodec.MAX_FILE_BYTES + 1]));
            assertArrayEquals(NEW, directory.read(NAME, 4));
        }
    }

    @Test void scanHasExactBoundAndDoesNotDeleteUnknownFiles() throws Exception {
        Path root = temp.resolve("drafts");
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            for (int i = 0; i < 511; i++) Files.createFile(root.resolve("unrelated-" + i));
            assertEquals(512, directory.entries().size());
            Files.createFile(root.resolve("entry-513"));
            assertStage(SqlDraftDirectory.Stage.SCAN_LIMIT, directory::entries);
            assertTrue(Files.exists(root.resolve("entry-513")));
            assertTrue(Files.exists(root.resolve("unrelated-0")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "child/file", "child\\file", ".writer.lock", "not-a-uuid.draft", "1-1-1-1-1.draft"})
    void rejectsNamesOutsideOwnedFiles(String name) throws Exception {
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(temp.resolve("drafts"))) {
            assertNotNull(directory);
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.publish(name, NEW));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.read(name, 10));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.delete(name));
            assertEquals(1, directory.entries().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"00112233-4455-6677-8899-AABBCCDDEEFF.draft", "Preferences.bin"})
    void rejectsCaseAliasesWithoutOverwritingOrDeletingExistingBytes(String alias) throws Exception {
        Path root = temp.resolve("drafts");
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            Files.write(root.resolve(alias), OLD);
            String canonical = alias.equals("Preferences.bin") ? "preferences.bin" : NAME;
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.read(canonical, 10));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.publish(canonical, NEW));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.delete(canonical));
            assertArrayEquals(OLD, Files.readAllBytes(root.resolve(alias)));
            assertEquals(2, directory.entries().size());
        }
    }

    @Test void rejectsSymlinksWithoutReadingWritingOrDeletingTheirTargets() throws Exception {
        Path root = temp.resolve("drafts");
        Path outside = temp.resolve("outside");
        Files.write(outside, OLD);
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            Path link = root.resolve(NAME);
            boolean supported = true;
            try { Files.createSymbolicLink(link, outside); }
            catch (UnsupportedOperationException | FileSystemException unavailable) { supported = false; }
            assumeTrue(supported, "symbolic links unavailable on this test host");
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.read(NAME, 10));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.publish(NAME, NEW));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.delete(NAME));
            assertArrayEquals(OLD, Files.readAllBytes(outside));
            assertTrue(Files.isSymbolicLink(link));
        }
        Path directoryLink = temp.resolve("directory-link");
        Files.createSymbolicLink(directoryLink, root);
        assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> SqlDraftDirectory.open(directoryLink));
    }

    private static void seed(Path root) throws IOException {
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            directory.publish(NAME, OLD);
        }
    }

    private static SqlDraftDirectory.Failure assertStage(SqlDraftDirectory.Stage stage,
            org.junit.jupiter.api.function.Executable operation) {
        SqlDraftDirectory.Failure failure = assertThrows(SqlDraftDirectory.Failure.class, operation);
        assertEquals(stage, failure.stage());
        assertNull(failure.getCause());
        return failure;
    }
}
```

- [x] **Step 2: Observe RED with compile success.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftDirectoryTest --rerun-tasks --no-daemon --console=plain
```

Expected exit1 against the null/no-op I/O stubs. Record actual failures before implementing file access. Do not count a compilation error as RED.

- [x] **Step 3: Implement the complete directory boundary.**

`src/com/datacube/config/SqlDraftDirectory.java`:

```java
package com.datacube.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Local draft filesystem boundary. Caller supplies a trusted existing parent. */
final class SqlDraftDirectory implements AutoCloseable {
    enum Stage { OPEN, BUSY, CLOSED, UNSAFE, SCAN_LIMIT, READ, WRITE, PUBLISH, CLEANUP, DELETE, CLOSE }
    static final class Failure extends IOException {
        private final Stage stage;
        Failure(Stage stage) { super("SQL draft I/O failed: " + stage); this.stage = stage; }
        Stage stage() { return stage; }
    }
    @FunctionalInterface interface Writer { void write(Path path, byte[] bytes) throws IOException; }
    @FunctionalInterface interface Mover { void move(Path source, Path target) throws IOException; }
    @FunctionalInterface interface Cleaner { void delete(Path path) throws IOException; }
    private record Identity(Object key, FileTime created) { }
    private record Stamp(Identity identity, long size, FileTime modified) { }
    private static final Set<Path> LIVE = ConcurrentHashMap.newKeySet();
    private final Path root;
    private final Identity rootIdentity;
    private final Identity lockIdentity;
    private final FileChannel channel;
    private final FileLock lock;
    private final Writer writer;
    private final Mover mover;
    private final Cleaner cleaner;
    private boolean closed;

    private SqlDraftDirectory(Path root, Identity rootIdentity, Identity lockIdentity,
            FileChannel channel, FileLock lock, Writer writer, Mover mover, Cleaner cleaner) {
        this.root = root; this.rootIdentity = rootIdentity; this.lockIdentity = lockIdentity;
        this.channel = channel; this.lock = lock; this.writer = writer; this.mover = mover; this.cleaner = cleaner;
    }

    static SqlDraftDirectory open(Path requested) throws IOException {
        return open(requested, SqlDraftDirectory::writeForced, SqlDraftDirectory::moveAtomic, Files::deleteIfExists);
    }

    static SqlDraftDirectory open(Path requested, Writer writer, Mover mover, Cleaner cleaner) throws IOException {
        FileChannel channel = null;
        FileLock lock = null;
        Path claimedRoot = null;
        try {
            Objects.requireNonNull(writer); Objects.requireNonNull(mover); Objects.requireNonNull(cleaner);
            Path absolute = requested.toAbsolutePath().normalize();
            if (absolute.getParent() == null || absolute.getFileName() == null) throw new Failure(Stage.UNSAFE);
            Path root = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            try { Files.createDirectory(root); } catch (FileAlreadyExistsException exists) { }
            BasicFileAttributes directory = attributes(root);
            if (!directory.isDirectory() || directory.isSymbolicLink()) throw new Failure(Stage.UNSAFE);
            // Avoid opening/closing another descriptor for a same-JVM live lock.
            if (!LIVE.add(root)) throw new Failure(Stage.BUSY);
            claimedRoot = root;
            Path lockPath = root.resolve(".writer.lock");
            stamp(lockPath);
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new Failure(Stage.BUSY); }
            if (lock == null) throw new Failure(Stage.BUSY);
            Stamp lockStamp = stamp(lockPath);
            if (lockStamp == null) throw new Failure(Stage.UNSAFE);
            SqlDraftDirectory result = new SqlDraftDirectory(root, identity(directory), lockStamp.identity(),
                    channel, lock, writer, mover, cleaner);
            result.check();
            return result;
        } catch (IOException | RuntimeException error) {
            if (lock != null) try { lock.release(); } catch (IOException ignored) { }
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            if (claimedRoot != null) LIVE.remove(claimedRoot);
            if (error instanceof Failure failure) throw failure;
            throw new Failure(Stage.OPEN);
        }
    }

    synchronized List<String> entries() throws IOException {
        check();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            List<String> names = new ArrayList<>();
            for (Path path : stream) {
                if (names.size() == 512) throw new Failure(Stage.SCAN_LIMIT);
                names.add(path.getFileName().toString());
            }
            return List.copyOf(names);
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(Stage.READ); }
    }

    synchronized byte[] read(String name, int limit) throws IOException {
        check();
        Path path = target(name);
        if (limit < 0 || limit > SqlDraftCodec.MAX_FILE_BYTES) throw new Failure(Stage.READ);
        try {
            Stamp before = stamp(path);
            if (before == null) return null;
            if (before.size() > limit) throw new Failure(Stage.READ);
            try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ByteBuffer buffer = ByteBuffer.allocate(Math.min(8192, limit + 1));
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > limit) throw new Failure(Stage.READ);
                    bytes.write(buffer.array(), 0, count);
                    buffer.clear();
                }
                check();
                if (!Objects.equals(before, stamp(path))) throw new Failure(Stage.UNSAFE);
                return bytes.toByteArray();
            }
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(Stage.READ); }
    }

    synchronized void publish(String name, byte[] bytes) throws IOException {
        check();
        Path path = target(name);
        if (bytes == null || bytes.length > SqlDraftCodec.MAX_FILE_BYTES) throw new Failure(Stage.WRITE);
        Path temporary = null;
        boolean published = false;
        Stage stage = Stage.WRITE;
        try {
            Stamp before = stamp(path);
            temporary = Files.createTempFile(root, ".draft-", ".tmp");
            writer.write(temporary, bytes);
            stage = Stage.PUBLISH;
            check();
            if (!Objects.equals(before, stamp(path))) throw new Failure(Stage.UNSAFE);
            Stamp ready = stamp(temporary);
            if (ready == null || ready.size() != bytes.length) throw new Failure(Stage.UNSAFE);
            mover.move(temporary, path);
            published = true;
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(stage); }
        finally {
            if (temporary != null && !published) {
                try { cleaner.delete(temporary); }
                catch (IOException | RuntimeException error) { throw new Failure(Stage.CLEANUP); }
            }
        }
    }

    synchronized void delete(String name) throws IOException {
        check();
        Path path = target(name);
        try {
            if (stamp(path) != null) Files.delete(path);
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(Stage.DELETE); }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        boolean failed = false;
        try { lock.release(); } catch (IOException error) { failed = true; }
        try { channel.close(); } catch (IOException error) { failed = true; }
        LIVE.remove(root);
        if (failed) throw new Failure(Stage.CLOSE);
    }

    static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel output = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }

    static void moveAtomic(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void check() throws IOException {
        if (closed || !lock.isValid()) throw new Failure(Stage.CLOSED);
        try {
            BasicFileAttributes directory = attributes(root);
            Stamp lockStamp = stamp(root.resolve(".writer.lock"));
            if (!directory.isDirectory() || !rootIdentity.equals(identity(directory))
                    || !root.toRealPath().equals(root) || lockStamp == null
                    || !lockIdentity.equals(lockStamp.identity())) throw new Failure(Stage.UNSAFE);
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(Stage.UNSAFE); }
    }

    private Path target(String name) throws IOException {
        boolean allowed = "preferences.bin".equals(name);
        if (name != null && name.endsWith(".draft")) {
            String id = name.substring(0, name.length() - 6);
            try { allowed = UUID.fromString(id).toString().equals(id); }
            catch (IllegalArgumentException invalid) { }
        }
        if (!allowed) throw new Failure(Stage.UNSAFE);
        for (String existing : entries()) {
            if (existing.equalsIgnoreCase(name) && !existing.equals(name)) throw new Failure(Stage.UNSAFE);
        }
        return root.resolve(name);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static Identity identity(BasicFileAttributes value) {
        return new Identity(value.fileKey(), value.creationTime());
    }

    private static Stamp stamp(Path path) throws IOException {
        try {
            BasicFileAttributes value = attributes(path);
            if (!value.isRegularFile() || value.isSymbolicLink()) throw new Failure(Stage.UNSAFE);
            return new Stamp(identity(value), value.size(), value.lastModifiedTime());
        } catch (NoSuchFileException absent) { return null; }
    }
}
```

- [x] **Step 4: Focused GREEN and one full regression.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftDirectoryTest --tests com.datacube.config.SqlDraftCodecTest --rerun-tasks --no-daemon --console=plain
```

Expected exit0. Report symlink assumption honestly if host lacks support. Then fresh full nonheadless suite:

```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
exit $draftTestExit
```

Expected exit0; report actual XML counts and named skips. Existing unchecked JavaFX test compiler note may still appear; preserve unrelated tests. No coverage percentage without measurement.

- [x] **Step 5: Self-review, commit exact files, and report.**

```powershell
git diff --check
git add -- src/com/datacube/config/SqlDraftDirectory.java test/com/datacube/config/SqlDraftDirectoryTest.java
git commit -m "feat: add locked failure-safe SQL draft directory"
```

Report observed RED/GREEN and full command/output, Requirement | Evidence test mapping, skipped checks, files, commit and concerns. This is filesystem infrastructure, not completed auto-save/recovery. Controller reviews task then implements store policy.

## Self-review and remaining policy

The tests prove write/reopen/delete, actual lock conflict/release, atomic-move rejection and cleanup, changed-target protection, bound enforcement, safe names and symlinks. Single-writer methods are synchronized; application work dispatch remains coordinator scope. Root identity checking is best effort across platform filesystem APIs, explicitly not a hostile local-process sandbox. No changes to existing history/export helpers; no dangling method/type references beyond the completed P1.1 codec.
