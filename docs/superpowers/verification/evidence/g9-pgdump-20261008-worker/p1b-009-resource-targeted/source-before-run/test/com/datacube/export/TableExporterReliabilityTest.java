package com.datacube.export;

import com.datacube.service.TableExportMocks;
import com.datacube.spi.model.TableRef;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class TableExporterReliabilityTest {
    @TempDir Path root;
    private static final TableRef TABLE = new TableRef("synthetic", "items");
    enum Failure { CONNECT, FIRST, MIDDLE, ENCODE, CLOSE, PUBLISH, CANCEL }
    static Stream<Arguments> failures() {
        return Arrays.stream(ExportFormat.values()).flatMap(format -> Stream.of(true, false).flatMap(old ->
                Arrays.stream(Failure.values()).map(failure -> Arguments.of(format, old, failure))));
    }
    private TableExporter.Request request(Path target, ExportFormat format) throws Exception {
        return new TableExporter.Request("synthetic", TABLE, ExportContent.DATA, format,
                SafeResultFilePublisher.capture(target));
    }
    private void noTemporaryFiles() throws IOException {
        try (var entries = Files.list(root)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".datacube-export-")));
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void dialectFailureBeforeWriterOwnershipNeverOpensOutputOrPublishes(boolean fatal) throws Exception {
        var source = new TableExportMocks();
        RuntimeException ordinary = new IllegalStateException("synthetic dialect failure");
        Error error = new AssertionError("synthetic dialect fatal");
        source.beforeDialect = () -> { if (fatal) throw error; throw ordinary; };
        Path target = Files.writeString(root.resolve("destination"), "old bytes");
        Path neighbor = Files.writeString(root.resolve("neighbor"), "untouched neighbor");
        var operation = new ResultExportOperation();
        var opens = new AtomicInteger(); var moves = new AtomicInteger();
        var opened = new AtomicReference<OutputStream>();
        var publisher = new SafeResultFilePublisher((temporary, destination) -> {
            moves.incrementAndGet();
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }, Files::deleteIfExists, ignored -> {});
        try {
            org.junit.jupiter.api.function.Executable attempt = () -> TableExporter.export(
                    source.manager, request(target, ExportFormat.SQL), operation, publisher,
                    (config, password, table, content, temporary) -> fail("No pg_dump"), temporary -> {
                        opens.incrementAndGet();
                        OutputStream stream = Files.newOutputStream(temporary);
                        opened.set(stream); return stream;
                    });
            Throwable observed = fatal ? assertThrows(Error.class, attempt) : assertThrows(Exception.class, attempt);
            if (fatal) assertSame(error, observed);
            else assertEquals(SafeResultFilePublisher.Stage.WRITE, ((SafeResultFilePublisher.Failure) observed).stage());
            assertEquals(0, opens.get(), "Provider preparation must finish before output stream ownership starts");
            assertEquals(0, moves.get()); assertFalse(operation.published());
            assertEquals("old bytes", Files.readString(target));
            assertEquals("untouched neighbor", Files.readString(neighbor)); noTemporaryFiles();
        } finally {
            // The pre-fix RED opens a real stream; release only that test-owned handle after recording the failure.
            if (opened.get() != null) opened.get().close();
        }
    }
    @ParameterizedTest @MethodSource("failures")
    void failureOnlyChangesOwnedTemporary(ExportFormat format, boolean old, Failure failure) throws Exception {
        var source = new TableExportMocks();
        Path target = root.resolve("destination");
        byte[] before = "old bytes".getBytes(StandardCharsets.UTF_8);
        if (old) Files.write(target, before);
        Path neighbor = Files.writeString(root.resolve("neighbor"), "neighbor bytes");
        var operation = new ResultExportOperation();
        switch (failure) {
            case CONNECT -> source.failure = TableExportMocks.Failure.CONNECT;
            case FIRST -> source.failure = TableExportMocks.Failure.FIRST;
            case MIDDLE -> source.failure = TableExportMocks.Failure.MIDDLE;
            case ENCODE -> source.value = new Object() {
                @Override public String toString() { throw new IllegalArgumentException("synthetic sensitive value"); }
            };
            case CANCEL -> source.beforePage = () -> { if (source.pages.get() == 1) operation.cancel(); };
            default -> {}
        }
        var closeCalls = new AtomicInteger();
        var moverCalls = new AtomicInteger();
        var publisher = new SafeResultFilePublisher((temporary, destination) -> {
            moverCalls.incrementAndGet();
            if (failure == Failure.PUBLISH) throw new AtomicMoveNotSupportedException("synthetic", "synthetic", "fixed");
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }, Files::deleteIfExists, path -> fail("Unexpected cleanup failure"));
        Exception error = assertThrows(Exception.class, () -> TableExporter.export(source.manager,
                request(target, format), operation, publisher, (config, password, table, content, temporary) -> {
                    assertNotEquals(target, temporary.toPath());
                    if (failure == Failure.CONNECT || failure == Failure.FIRST)
                        throw new IOException("synthetic sensitive setup");
                    Files.writeString(temporary.toPath(), "partial synthetic output");
                    if (failure == Failure.CANCEL) operation.cancel();
                    else if (failure != Failure.PUBLISH) throw new IOException("synthetic sensitive output");
                }, temporary -> new FilterOutputStream(Files.newOutputStream(temporary)) {
                    @Override public void close() throws IOException {
                        closeCalls.incrementAndGet();
                        super.close();
                        if (failure == Failure.CLOSE) throw new IOException("synthetic sensitive close");
                    }
                }));
        assertFalse(operation.published());
        assertEquals(failure == Failure.PUBLISH ? 1 : 0, moverCalls.get());
        if (failure == Failure.CANCEL) assertInstanceOf(CancellationException.class, error);
        assertFalse(error.getMessage().contains("sensitive"));
        if (old) assertArrayEquals(before, Files.readAllBytes(target));
        else assertFalse(Files.exists(target));
        assertEquals("neighbor bytes", Files.readString(neighbor));
        if (format != ExportFormat.PG_DUMP && failure == Failure.CLOSE) assertEquals(1, closeCalls.get());
        noTemporaryFiles();
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void successfulRealSerializationIsPublishedAndLateCancelIsRejected(ExportFormat format) throws Exception {
        var source = new TableExportMocks();
        Path target = Files.writeString(root.resolve("destination"), "old bytes");
        Path neighbor = Files.writeString(root.resolve("neighbor"), "keep");
        var operation = new ResultExportOperation();
        Path published = TableExportTestJobs.localDump(source.manager, request(target, format), operation, () -> {});
        assertTrue(operation.published());
        assertFalse(operation.cancel());
        assertTrue(Files.isSameFile(target, published));
        if (format == ExportFormat.XLSX) {
            try (ZipFile zip = new ZipFile(target.toFile())) {
                String xml = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(xml.contains("complete value")); assertTrue(xml.contains("value"));
                assertTrue(xml.contains("<row r=\"2\">"));
            }
        } else if (format == ExportFormat.SQL) {
            String sql = Files.readString(target);
            assertTrue(sql.contains("INSERT INTO \"synthetic\".\"items\" (\"value\")"));
            assertTrue(sql.contains("'complete value'"));
        } else assertEquals("synthetic pg_dump output", Files.readString(target));
        assertEquals("keep", Files.readString(neighbor));
        noTemporaryFiles();
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void cancelledBeforeBeginAcquiresNoResourceAndCreatesNoNewFile(ExportFormat format) throws Exception {
        var source = new TableExportMocks();
        Path target = root.resolve("destination");
        var operation = new ResultExportOperation();
        assertTrue(operation.cancel());
        assertThrows(CancellationException.class, () -> TableExportTestJobs.localDump(source.manager,
                request(target, format), operation, () -> fail("No dump may start")));
        assertEquals(0, source.opens.get());
        assertFalse(Files.exists(target)); noTemporaryFiles();
    }

    @ParameterizedTest @EnumSource(ExportFormat.class)
    void targetReplacementBeforeBeginCannotAcquireConnectionOrWrite(ExportFormat format) throws Exception {
        var source = new TableExportMocks();
        Path target = Files.writeString(root.resolve("destination"), "old bytes");
        var request = request(target, format);
        Files.delete(target); Files.writeString(target, "external replacement");
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () ->
                TableExportTestJobs.localDump(source.manager, request, new ResultExportOperation(), () -> fail("No dump")));
        assertEquals(SafeResultFilePublisher.Stage.TARGET_CHANGED, failure.stage());
        assertEquals(0, source.opens.get()); assertEquals("external replacement", Files.readString(target));
        noTemporaryFiles();
    }

    @Test void cancellationWhileActualRowSourceIsBlockedPreventsPublicationAfterItReturns() throws Exception {
        var source = new TableExportMocks();
        Path target = Files.writeString(root.resolve("destination"), "old bytes");
        var operation = new ResultExportOperation();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        source.beforePage = () -> {
            if (source.pages.get() == 1) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            }
        };
        var worker = new FutureTask<Void>(() -> {
            assertThrows(CancellationException.class, () -> TableExporter.export(source.manager,
                    request(target, ExportFormat.SQL), operation)); return null;
        });
        Thread.ofVirtual().start(worker);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(operation.cancel());
            assertFalse(worker.isDone(), "Intent is not physical row-source completion");
            assertEquals("old bytes", Files.readString(target));
            release.countDown(); worker.get(5, TimeUnit.SECONDS);
            assertEquals("old bytes", Files.readString(target)); noTemporaryFiles();
        } finally { release.countDown(); worker.get(5, TimeUnit.SECONDS); }
    }
}
