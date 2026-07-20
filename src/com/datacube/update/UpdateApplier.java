package com.datacube.update;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 下载更新资产并按运行形态应用：
 * <ul>
 *   <li>安装版：下载 setup.exe → 启动它（WiX 同 UpgradeCode 原地升级）→ 应用退出；</li>
 *   <li>绿色版：下载 portable.zip → 生成辅助脚本（等本进程退出后备份换入并重启）→ 应用退出。</li>
 * </ul>
 * 进程自替换的 Windows 限制由"先退出、外部接力替换"规避。
 */
public final class UpdateApplier {

    /** 下载进度回调；{@code total} 为 -1 表示服务端未提供 Content-Length。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesRead, long total);
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 流式下载到目标文件，按 Content-Length 回调进度；下载完成校验大小一致，
     * 不一致则删档并抛异常。
     */
    public void download(String url, Path dest, ProgressListener listener) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "DataCube-Updater")
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("下载失败，状态 " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("content-length").orElse(-1);

        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (listener != null) listener.onProgress(read, total);
            }
        }
        if (total >= 0 && Files.size(dest) != total) {
            Files.deleteIfExists(dest);
            throw new IllegalStateException("下载不完整（大小与预期不符）");
        }
    }

    /** 目标临时文件路径（系统临时目录）。 */
    public Path tempFile(String fileName) {
        return Path.of(System.getProperty("java.io.tmpdir"), fileName);
    }

    /** 启动安装包并请求应用退出（由调用方在 UI 线程执行退出）。 */
    public void launchInstaller(Path setupExe) throws Exception {
        new ProcessBuilder(setupExe.toAbsolutePath().toString())
                .inheritIO()
                .start();
    }

    /**
     * 绿色版自替换：生成辅助脚本并以独立进程启动，随后调用方退出应用。
     *
     * @param zip    已下载的 portable.zip
     * @param appDir 当前 app-image 根目录（jpackage 启动器所在目录）
     */
    public void launchPortableUpdate(Path zip, Path appDir) throws Exception {
        long pid = ProcessHandle.current().pid();
        Path staging = tempFile("datacube-update-staging");
        Path script = tempFile("datacube-update.cmd");

        String exe = appDir.resolve("DataCube.exe").toString();
        String cmd = buildPortableScript(pid, zip.toString(), appDir.toString(),
                staging.toString(), exe);
        Files.writeString(script, cmd, StandardCharsets.US_ASCII);

        // start 以独立控制台启动脚本，与 JVM 解绑；JVM 退出后脚本继续执行替换。
        new ProcessBuilder("cmd.exe", "/c", "start", "", "/min",
                "cmd.exe", "/c", script.toAbsolutePath().toString())
                .start();
    }

    /** 生成绿色版自替换批处理脚本（备份换入，崩溃可回滚）。 */
    private String buildPortableScript(long pid, String zip, String appDir,
                                       String staging, String exe) {
        String bak = appDir + ".bak";
        String newDir = staging + "\\DataCube";
        return String.join("\r\n",
                "@echo off",
                "setlocal",
                "set \"PID=" + pid + "\"",
                "set \"ZIP=" + zip + "\"",
                "set \"APPDIR=" + appDir + "\"",
                "set \"STAGING=" + staging + "\"",
                "set \"BAKDIR=" + bak + "\"",
                "set \"NEWDIR=" + newDir + "\"",
                "set \"EXE=" + exe + "\"",
                "",
                "rem 1. 等待主进程退出",
                ":waitloop",
                "tasklist /FI \"PID eq %PID%\" 2>nul | find \"%PID%\" >nul",
                "if not errorlevel 1 (",
                "    timeout /t 1 /nobreak >nul",
                "    goto waitloop",
                ")",
                "",
                "rem 2. 解压到 staging",
                "if exist \"%STAGING%\" rmdir /s /q \"%STAGING%\"",
                "mkdir \"%STAGING%\"",
                "powershell -NoProfile -Command \"Expand-Archive -Path '%ZIP%' -DestinationPath '%STAGING%' -Force\"",
                "if errorlevel 1 goto fail",
                "if not exist \"%NEWDIR%\" goto fail",
                "",
                "rem 3. 备份换入（失败回滚）",
                "if exist \"%BAKDIR%\" rmdir /s /q \"%BAKDIR%\"",
                "move \"%APPDIR%\" \"%BAKDIR%\"",
                "if errorlevel 1 goto fail",
                "move \"%NEWDIR%\" \"%APPDIR%\"",
                "if errorlevel 1 goto rollback",
                "rmdir /s /q \"%BAKDIR%\"",
                "",
                "rem 4. 重启 + 清理",
                "start \"\" \"%EXE%\"",
                "rmdir /s /q \"%STAGING%\" 2>nul",
                "del \"%ZIP%\" 2>nul",
                "goto end",
                "",
                ":rollback",
                "if exist \"%APPDIR%\" rmdir /s /q \"%APPDIR%\"",
                "move \"%BAKDIR%\" \"%APPDIR%\"",
                "",
                ":fail",
                "start \"\" \"%EXE%\"",
                "",
                ":end",
                "del \"%~f0\"",
                "");
    }
}
