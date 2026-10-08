package com.datacube.export;

import java.nio.file.*;
import java.util.List;

/** Local synthetic process only. No PostgreSQL client, connection, inherited business data or native input. */
public final class PgDumpProcessHelper {
    public static void main(String[] args) throws Exception {
        String mode = args[0]; Path out = Path.of(args[1]); Path control = Path.of(args[2]);
        Files.writeString(out, "synthetic dump bytes");
        Files.writeString(control.resolve("ready-" + mode), Long.toString(ProcessHandle.current().pid()));
        switch (mode) {
            case "hang", "child" -> await(control, "release-" + mode);
            case "flood" -> {
                byte[] buffer = new byte[8192]; java.util.Arrays.fill(buffer, (byte)'x');
                for (int i = 0; i < 1024; i++) { System.out.write(buffer); System.err.write(buffer); }
                System.out.flush(); System.err.flush();
            }
            case "fail" -> { System.err.print("SYNTHETIC_PGDUMP_SECRET"); System.err.flush(); System.exit(7); }
            case "parent" -> {
                String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
                Process child = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
                        PgDumpProcessHelper.class.getName(), "child", out.toString(), control.toString()))
                        .inheritIO().start();
                Files.writeString(control.resolve("child-pid"), Long.toString(child.pid()));
                await(control, "release-parent");
            }
            case "empty" -> {}
            default -> throw new IllegalArgumentException("Unknown synthetic mode");
        }
    }
    private static void await(Path root, String name) throws Exception {
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            root.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            while (!Files.exists(root.resolve(name))) watcher.take().reset();
        }
    }
}
