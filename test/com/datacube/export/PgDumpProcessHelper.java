package com.datacube.export;

import java.nio.file.*;
import java.util.List;

/** Local synthetic process only. No PostgreSQL client, connection, inherited business data or native input. */
public final class PgDumpProcessHelper {
    static String javaExecutable() {
        return javaExecutable(Path.of(System.getProperty("java.home")),
                System.getProperty("os.name").startsWith("Windows")).toString();
    }
    static Path javaExecutable(Path javaHome, boolean windows) {
        return javaHome.resolve("bin").resolve(windows ? "java.exe" : "java");
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0]; Path out = Path.of(args[1]); Path control = Path.of(args[2]);
        Files.writeString(out, "synthetic dump bytes");
        Files.writeString(control.resolve("ready-" + mode), Long.toString(ProcessHandle.current().pid()));
        switch (mode) {
            case "hang", "child" -> await(control, "release-" + mode);
            case "flood", "stream" -> {
                byte[] buffer = new byte[8192]; java.util.Arrays.fill(buffer, (byte)'x');
                byte[] marker = "SYNTHETIC_PGDUMP_SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                System.arraycopy(marker, 0, buffer, 0, marker.length);
                for (int i = 0; mode.equals("stream") ? !Files.exists(control.resolve("release-stream")) : i < 1024; i++) {
                    System.out.write(buffer); System.err.write(buffer);
                }
                System.out.flush(); System.err.flush();
            }
            case "fail" -> { System.err.print("SYNTHETIC_PGDUMP_SECRET"); System.err.flush(); System.exit(7); }
            case "parent", "tree", "branch" -> {
                String java = javaExecutable();
                Process child = new ProcessBuilder(List.of(java, "-XX:-UsePerfData", "-cp", System.getProperty("java.class.path"),
                        PgDumpProcessHelper.class.getName(), mode.equals("tree") ? "branch" : "child", out.toString(), control.toString()))
                        .inheritIO().start();
                publishPid(control.resolve(mode.equals("branch") ? "grandchild-pid" : "child-pid"), child.pid());
                await(control, "release-" + mode);
            }
            case "empty" -> {}
            default -> throw new IllegalArgumentException("Unknown synthetic mode");
        }
    }
    static Path pidReadyFile(Path pidFile) {
        return pidFile.resolveSibling(pidFile.getFileName() + ".ready");
    }
    static void publishPid(Path pidFile, long pid) throws java.io.IOException {
        if (pid <= 0) throw new IllegalArgumentException("Synthetic PID must be positive");
        Files.writeString(pidFile, Long.toString(pid), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        // writeString has closed the payload before the existence-only completion event is published.
        Files.createFile(pidReadyFile(pidFile));
    }
    private static void await(Path root, String name) throws Exception {
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            root.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            while (!Files.exists(root.resolve(name))) watcher.take().reset();
        }
    }
}
