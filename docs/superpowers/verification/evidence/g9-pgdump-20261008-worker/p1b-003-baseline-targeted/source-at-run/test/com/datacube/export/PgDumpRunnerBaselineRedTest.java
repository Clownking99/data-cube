package com.datacube.export;

import com.datacube.spi.model.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PgDumpRunnerBaselineRedTest {
    @TempDir Path root;
    static ConnConfig config() {
        return new ConnConfig("synthetic", "synthetic", DbType.POSTGRESQL, "synthetic.invalid", 1,
                "dbname=other host=neighbor", "synthetic", "", Map.of());
    }
    static Process helper(ProcessBuilder supplied, String mode, Path output, Path control) throws java.io.IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classes;
        try { classes = Path.of(PgDumpProcessHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch (java.net.URISyntaxException error) { throw new java.io.IOException("Synthetic helper path", error); }
        var builder = new ProcessBuilder(java, "-XX:-UsePerfData", "-Duser.home=" + control, "-cp", classes,
                PgDumpProcessHelper.class.getName(), mode, output.toString(), control.toString());
        builder.environment().clear(); builder.environment().putAll(supplied.environment());
        return builder.start();
    }
    @ParameterizedTest @ValueSource(strings = {"hang", "flood"})
    void totalDeadlineMustIncludeSilentOrUnbrokenOutputDrain(String mode) throws Exception {
        Path output = root.resolve("owned.tmp"); var process = new AtomicReference<Process>();
        var started = new CountDownLatch(1);
        var worker = new FutureTask<Void>(() -> {
            PgDumpRunner.run(config(), "", new TableRef("synthetic", "items"), ExportContent.DATA,
                    output.toFile(), builder -> {
                        Process child = helper(builder, mode, output, root); process.set(child); started.countDown(); return child;
                    }, Duration.ofMillis(50)); return null;
        });
        Thread.ofVirtual().start(worker);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> worker.get(1500, TimeUnit.MILLISECONDS));
            assertFalse(process.get().isAlive(), "Deadline must physically settle the owned root");
        } finally {
            Process child = process.get();
            if (child != null) {
                child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                child.getOutputStream().close(); child.getInputStream().close(); child.getErrorStream().close();
                System.out.println("RED_HELPER pid=" + child.pid() + " alive=" + child.isAlive() + " mode=" + mode);
            }
            try { worker.get(5, TimeUnit.SECONDS); } catch (ExecutionException expected) {}
        }
    }
    @Test void argvAndEnvironmentMustBindLiteralDatabaseAndExcludeInheritedJvmSettings() throws Exception {
        Path output = root.resolve("owned.tmp"); var argv = new AtomicReference<List<String>>();
        var environment = new AtomicReference<Map<String,String>>();
        PgDumpRunner.run(config(), "", new TableRef("schema.*", "Mixed?Table"), ExportContent.DATA,
                output.toFile(), builder -> {
                    argv.set(List.copyOf(builder.command())); environment.set(Map.copyOf(builder.environment()));
                    return helper(builder, "empty", output, root);
                }, Duration.ofSeconds(2));
        assertTrue(argv.get().contains("--format=plain"));
        assertTrue(argv.get().contains("--no-password"));
        assertFalse(environment.get().containsKey("JAVA_TOOL_OPTIONS"));
    }
    @Test void nonzeroOutputContentMustNeverBecomeReportedExceptionText() throws Exception {
        Path output = root.resolve("owned.tmp");
        Exception failure = assertThrows(Exception.class, () -> PgDumpRunner.run(config(), "", new TableRef("synthetic", "items"),
                ExportContent.DATA, output.toFile(), builder -> helper(builder, "fail", output, root), Duration.ofSeconds(2)));
        assertFalse(failure.getMessage().contains("SYNTHETIC_PGDUMP_SECRET"));
    }
}
