package com.datacube.export;

import com.datacube.spi.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class PgDumpRunnerArgumentsTest {
    @TempDir Path root;
    private static ConnConfig config(String host, String database, String user) {
        return new ConnConfig("synthetic", "synthetic", DbType.POSTGRESQL, host, 1234, database, user, "", Map.of());
    }
    // Independent grammar reader: parse the complete supplied conninfo and reject unconsumed/injected fields.
    static Map<String,String> parse(String text) {
        var result = new LinkedHashMap<String,String>(); int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i == text.length()) break;
            int beginning = i;
            while (i < text.length() && text.charAt(i) != '=') i++;
            assertTrue(i < text.length()); String key = text.substring(beginning, i++);
            assertEquals('\'', text.charAt(i++)); var value = new StringBuilder(); boolean ended = false;
            while (i < text.length()) {
                char character = text.charAt(i++);
                if (character == '\'') { ended = true; break; }
                if (character == '\\') { assertTrue(i < text.length()); character = text.charAt(i++); }
                value.append(character);
            }
            assertTrue(ended); assertNull(result.put(key, value.toString()));
            if (i < text.length()) assertTrue(Character.isWhitespace(text.charAt(i)));
        }
        return result;
    }
    @ParameterizedTest @ValueSource(strings = {"dbname=neighbor host=other", "postgresql://other/neighbor", "postgres://x",
            "a'b", "a\\b", "a\"b", "space name", "合成数据"})
    void allConnectionFieldsRemainSingleLiteralValues(String database) throws Exception {
        var cfg = config("synthetic='x'\\host", database, "synthetic user'\\\"");
        String secret = "SYNTHETIC_ARGUMENT_SECRET";
        try (var prepared = PgDumpRunner.prepare(cfg, secret, new TableRef("S.*\" pace", "T?.\"Mixed"), ExportContent.DATA, root.resolve("owned.tmp"))) {
            var command = prepared.builder.command();
            var parsed = parse(command.get(command.indexOf("--dbname") + 1));
            assertAll("independent bindings",
                    () -> assertEquals(cfg.host(), parsed.get("host")),
                    () -> assertEquals("1234", parsed.get("port")),
                    () -> assertEquals(database, parsed.get("dbname")),
                    () -> assertEquals(cfg.username(), parsed.get("user")),
                    () -> assertEquals(prepared.passfile.toString(), parsed.get("passfile")),
                    () -> assertFalse(parsed.containsKey("password")),
                    () -> assertFalse(command.toString().contains(secret)),
                    () -> assertEquals("\"S.*\"\" pace\".\"T?.\"\"Mixed\"", command.get(command.indexOf("--table") + 1)),
                    () -> assertTrue(command.contains("--format=plain")),
                    () -> assertTrue(command.contains("--no-password")),
                    () -> assertEquals(root.resolve("owned.tmp").toAbsolutePath().toString(), command.get(command.indexOf("-f") + 1)),
                    () -> assertEquals("!gss,!sspi", parsed.get("require_auth")),
                    () -> assertEquals("disable", parsed.get("gssencmode")));
        }
    }
    @ParameterizedTest @EnumSource(ExportContent.class)
    void contentMappingAndIsolatedEnvironmentAreIndependentlyObservable(ExportContent content) throws Exception {
        var prepared = PgDumpRunner.prepare(config("synthetic.invalid", "synthetic", "synthetic"), "",
                new TableRef("synthetic", "items"), content, root.resolve("owned.tmp"));
        Path owned = prepared.root;
        try (prepared) {
            var env = prepared.builder.environment(); var parsed = parse(prepared.builder.command().get(4));
            assertAll("environment and content",
                    () -> assertEquals(content == ExportContent.STRUCTURE, prepared.builder.command().contains("--schema-only")),
                    () -> assertEquals(content == ExportContent.DATA, prepared.builder.command().contains("--data-only")),
                    () -> assertEquals(prepared.passfile.toString(), env.get("PGPASSFILE")),
                    () -> assertEquals(prepared.service.toString(), env.get("PGSERVICEFILE")),
                    () -> assertEquals(0, Files.size(prepared.passfile)),
                    () -> assertEquals(0, Files.size(prepared.service)),
                    () -> assertTrue(env.containsKey("PGPASSWORD") && env.get("PGPASSWORD").isEmpty()),
                    () -> assertEquals(owned.toFile(), prepared.builder.directory()),
                    () -> assertTrue(env.keySet().stream().noneMatch(key -> Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "PGHOST", "PGDATABASE", "PGUSER", "PGSERVICE", "PGOPTIONS", "PGSSLCERT", "PGSSLKEY", "PGKRBSRVNAME").contains(key))));
            for (String name : List.of("HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "PGSYSCONFDIR"))
                assertEquals(owned.toString(), env.get(name));
            for (String name : List.of("sslcert", "sslkey", "sslrootcert", "sslcrl")) {
                Path path = Path.of(parsed.get(name)); assertEquals(owned, path.getParent()); assertFalse(Files.exists(path));
            }
        }
        assertFalse(prepared.builder.environment().containsKey("PGPASSWORD")); assertFalse(Files.exists(owned));
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "a\u0000b"})
    void missingOrUnrepresentableFieldsAreRejectedBeforeScratchOrStart(String value) throws Exception {
        for (var cfg : List.of(config(value, "synthetic", "synthetic"), config("synthetic", value, "synthetic"), config("synthetic", "synthetic", value)))
            assertEquals(PgDumpRunner.Reason.PREPARE, assertThrows(PgDumpRunner.Failure.class, () -> PgDumpRunner.prepare(
                    cfg, "", new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned.tmp"))).reason());
        try (var entries = Files.list(root)) { assertEquals(0, entries.count()); }
    }
    @ParameterizedTest @ValueSource(strings = {"a,b", "/socket", "@socket", "C:\\socket"})
    void hostCannotSelectMultipleOrImplicitLocalTargets(String host) {
        assertThrows(PgDumpRunner.Failure.class, () -> PgDumpRunner.prepare(config(host, "synthetic", "synthetic"), "",
                new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned.tmp")));
    }
    @Test void schemaIsRequiredAndCannotFallBackToSearchPath() {
        for (String schema : Arrays.asList(null, "", "a\0b")) assertThrows(PgDumpRunner.Failure.class, () -> PgDumpRunner.prepare(
                config("synthetic", "synthetic", "synthetic"), "", new TableRef(schema, "items"), ExportContent.DATA, root.resolve("owned.tmp")));
    }
    @ParameterizedTest @ValueSource(strings = {"empty.pgpass", "empty.pg_service.conf"})
    void replacedScratchFileAndNeighborArePreserved(String name) throws Exception {
        var prepared = PgDumpRunner.prepare(config("synthetic", "synthetic", "synthetic"), "", new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned.tmp"));
        Path neighbor = Files.writeString(root.resolve("neighbor"), "unchanged");
        Path file = prepared.root.resolve(name); Path saved = root.resolve("saved-empty");
        Files.move(file, saved); Files.writeString(file, "foreign replacement");
        assertThrows(java.io.IOException.class, prepared::close);
        assertEquals("foreign replacement", Files.readString(file)); assertEquals(0, Files.size(saved));
        assertEquals("unchanged", Files.readString(neighbor));
    }
    @Test void changedScratchParentAndMetadataFailureCannotAuthorizeDeletion() throws Exception {
        var prepared = PgDumpRunner.prepare(config("synthetic", "synthetic", "synthetic"), "", new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned.tmp"));
        Path saved = root.resolve("saved-parent"); Files.move(prepared.root, saved); Files.createDirectory(prepared.root);
        Files.writeString(prepared.passfile, "foreign passfile"); Files.writeString(prepared.service, "foreign service");
        assertThrows(java.io.IOException.class, prepared::close);
        assertEquals("foreign passfile", Files.readString(prepared.passfile)); assertEquals("foreign service", Files.readString(prepared.service));
        assertEquals(0, Files.size(saved.resolve("empty.pgpass")));
        var second = PgDumpRunner.prepare(config("synthetic", "synthetic", "synthetic"), "", new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned-2.tmp"));
        second.attributes = path -> { throw new AccessDeniedException("synthetic metadata denied"); };
        assertThrows(AccessDeniedException.class, second::close);
        assertTrue(Files.exists(second.passfile)); assertTrue(Files.exists(second.service));
    }
    @Test void prepareFatalIsPreservedWhenCleanupMetadataAlsoFails() {
        var calls = new AtomicInteger(); Error fatal = new AssertionError("synthetic preparation fatal");
        Error observed = assertThrows(Error.class, () -> PgDumpRunner.prepare(config("synthetic", "synthetic", "synthetic"), "",
                new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned.tmp"), path -> {
                    if (calls.incrementAndGet() == 1) throw fatal;
                    throw new IllegalStateException("synthetic cleanup metadata failure");
                }));
        assertSame(fatal, observed); assertEquals(1, fatal.getSuppressed().length);
        var cleanup = (SafeResultFilePublisher.Failure) fatal.getSuppressed()[0];
        assertEquals(SafeResultFilePublisher.Stage.CLEANUP, cleanup.stage()); assertTrue(Files.isDirectory(cleanup.temporaryPath()));
    }
    @Test void failedInitialRootIdentityCannotDeleteAnEmptyForeignReplacement() throws Exception {
        Path neighbor = Files.writeString(root.resolve("neighbor"), "neighbor bytes");
        var replacement = new java.util.concurrent.atomic.AtomicReference<Path>();
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        assertThrows(java.io.IOException.class, () -> PgDumpRunner.prepare(config("synthetic", "synthetic", "synthetic"), "",
                new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned.tmp"), path -> {
                    if (first.compareAndSet(true, false)) {
                        replacement.set(path); Files.move(path, root.resolve("relocated")); Files.createDirectory(path);
                        throw new AccessDeniedException("synthetic initial identity failure");
                    }
                    return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                }));
        assertTrue(Files.isDirectory(replacement.get()), "Unproved empty replacement root must survive");
        assertEquals("neighbor bytes", Files.readString(neighbor)); assertTrue(Files.isDirectory(root.resolve("relocated")));
    }
    @Test void confirmedMissingScratchRootNeedsNoDeletionOrCleanupFailure() throws Exception {
        var prepared = PgDumpRunner.prepare(config("synthetic", "synthetic", "synthetic"), "",
                new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned.tmp"));
        Files.delete(prepared.passfile); Files.delete(prepared.service); Files.delete(prepared.root);
        assertDoesNotThrow(prepared::close);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void secondCreationFailureUsesOnlyPreviouslyRecordedOwnership(boolean replaceRoot) throws Exception {
        Path neighbor = Files.writeString(root.resolve("neighbor"), "neighbor bytes");
        var scratch = new java.util.concurrent.atomic.AtomicReference<Path>(); var created = new AtomicInteger();
        var failure = assertThrows(java.io.IOException.class, () -> PgDumpRunner.prepare(config("synthetic", "synthetic", "synthetic"), "",
                new TableRef("synthetic", "items"), ExportContent.DATA, root.resolve("owned.tmp"),
                path -> Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), path -> {
                    scratch.set(path.getParent());
                    if (created.incrementAndGet() == 2) {
                        if (replaceRoot) { Files.move(path.getParent(), root.resolve("relocated")); Files.createDirectory(path.getParent()); }
                        throw new java.io.IOException("Synthetic second file creation failure");
                    }
                    Files.write(path, new byte[0], StandardOpenOption.CREATE_NEW);
                }));
        assertEquals("neighbor bytes", Files.readString(neighbor));
        if (replaceRoot) {
            assertTrue(Files.isDirectory(scratch.get()), "Empty foreign root preserved");
            assertEquals(0, Files.size(root.resolve("relocated/empty.pgpass")));
            assertEquals(SafeResultFilePublisher.Stage.CLEANUP, ((SafeResultFilePublisher.Failure) failure).stage());
        } else { assertFalse(Files.exists(scratch.get())); assertInstanceOf(PgDumpRunner.Failure.class, failure); }
    }
}
