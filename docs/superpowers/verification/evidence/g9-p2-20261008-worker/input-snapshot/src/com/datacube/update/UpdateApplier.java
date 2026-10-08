package com.datacube.update;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Prepares a verified, unique update and hands it to a bounded external helper. */
public final class UpdateApplier {
    @FunctionalInterface public interface ProgressListener { void onProgress(long bytesRead, long total); }
    @FunctionalInterface interface Launcher { void start(List<String> command) throws Exception; }
    private final UpdateManifest manifest;
    private final UpdateDownloads downloads;
    private final Launcher launcher;
    public UpdateApplier() {
        this(UpdateManifest.bundled(), UpdateDownloads.http(), command -> new ProcessBuilder(command).start());
    }
    UpdateApplier(UpdateManifest manifest, UpdateDownloads downloads, Launcher launcher) {
        this.manifest = manifest; this.downloads = downloads; this.launcher = launcher;
    }
    boolean configured() { return manifest.configured(); }
    public Path tempFile(String fileName) throws java.io.IOException {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,120}")
                || fileName.equals(".") || fileName.equals("..")) throw new IllegalArgumentException("临时文件名无效");
        return Files.createTempDirectory("datacube-update-").resolve(fileName);
    }
    Prepared prepare(ReleaseInfo info, String currentVersion, InstallMode mode, Path appDir,
                     UpdateCancellation control, ProgressListener progress) throws Exception {
        if (!configured() || !info.verificationAvailable()) throw UpdateManifest.rejected();
        control.check();
        String base = UpdateManifest.baseUrl(info.version());
        byte[] bytes = downloads.bytes(base + "datacube-update.manifest", UpdateManifest.MAX_MANIFEST_BYTES, control);
        byte[] signature = downloads.bytes(base + "datacube-update.manifest.sig", 64, control);
        var verified = manifest.verify(info, currentVersion, bytes, signature);
        Path target = UpdatePaths.canonicalExisting(UpdatePaths.image(appDir));
        if (mode == InstallMode.UNKNOWN) throw UpdateManifest.rejected();
        Path workspace = mode == InstallMode.PORTABLE
                ? Files.createTempDirectory(target.getParent(), ".datacube-update-")
                : Files.createTempDirectory("datacube-update-");
        workspace = UpdatePaths.canonicalExisting(workspace);
        String token = UUID.randomUUID().toString().replace("-", "");
        Files.writeString(workspace.resolve("owner"), token, StandardOpenOption.CREATE_NEW);
        Path packageFile = workspace.resolve(verified.asset(mode).name());
        try {
            downloads.asset(base + verified.asset(mode).name(), packageFile, verified.asset(mode), control, progress);
            if (mode == InstallMode.PORTABLE) PortableArchive.extract(packageFile, workspace.resolve("new"), control);
            control.check();
            Path helper = workspace.resolve("update-helper.ps1");
            try (var script = UpdateApplier.class.getResourceAsStream("/com/datacube/update/update-helper.ps1")) {
                if (script == null) throw new IllegalStateException("更新辅助程序不可用");
                Files.copy(script, helper);
            }
            String plan = "{\"format\":1,\"mode\":" + UpdateStartup.quote(mode.name())
                    + ",\"version\":" + UpdateStartup.quote(verified.version)
                    + ",\"token\":" + UpdateStartup.quote(token)
                    + ",\"workspace\":" + UpdateStartup.quote(workspace.toString())
                    + ",\"appDir\":" + UpdateStartup.quote(target.toString())
                    + ",\"asset\":" + UpdateStartup.quote(packageFile.toString())
                    + ",\"sha256\":" + UpdateStartup.quote(verified.asset(mode).sha256())
                    + ",\"pid\":" + ProcessHandle.current().pid()
                    + ",\"pidStart\":" + ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() + "}";
            Path planPath = workspace.resolve("plan.json");
            Files.writeString(planPath, plan, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(workspace.resolve("state.json"), "{\"state\":\"PREPARED\"}", StandardOpenOption.CREATE_NEW);
            return new Prepared(this, verified, mode, target, workspace, packageFile, planPath);
        } catch (Exception failure) {
            Files.writeString(workspace.resolve("state.json"), "{\"state\":\"FAILED_OR_CANCELLED_BEFORE_HANDOFF\"}",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            throw failure;
        }
    }
    void launch(Prepared prepared, UpdateCancellation control) throws Exception {
        if (prepared.owner != this || !prepared.launched.compareAndSet(false, true)) throw new IllegalStateException("更新请求不属于当前实例或已交接");
        UpdatePaths.image(prepared.appDir);
        UpdatePaths.noLinks(prepared.workspace);
        UpdateDownloads.verifyFile(prepared.asset, prepared.verified.asset(prepared.mode));
        if (prepared.mode == InstallMode.PORTABLE) UpdatePaths.image(prepared.workspace.resolve("new/DataCube"));
        String powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        List<String> command = List.of(powershell, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-File", prepared.workspace.resolve("update-helper.ps1").toString(),
                "-PlanPath", prepared.plan.toString());
        control.handoff(() -> launcher.start(command));
    }
    static final class Prepared {
        private final UpdateApplier owner;
        private final UpdateManifest.Verified verified;
        final InstallMode mode;
        final Path appDir, workspace, asset, plan;
        private final java.util.concurrent.atomic.AtomicBoolean launched = new java.util.concurrent.atomic.AtomicBoolean();
        private Prepared(UpdateApplier owner, UpdateManifest.Verified verified, InstallMode mode,
                         Path appDir, Path workspace, Path asset, Path plan) {
            this.owner = owner; this.verified = verified; this.mode = mode;
            this.appDir = appDir; this.workspace = workspace; this.asset = asset; this.plan = plan;
        }
    }
}
