package com.datacube.config;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 应用级设置持久化：读写 {@code ~/.datacube/settings.properties}。
 *
 * <p>与 {@link ConnectionStore} 同风格，无第三方依赖（{@link Properties}）。
 * 文件缺失或损坏时回退默认值，不阻断启动。属性变更时自动写回（best-effort）。
 */
public final class AppSettings {

    private static final Logger LOG = Logger.getLogger(AppSettings.class.getName());

    /** SQL 结果表头字段注释显示模式。 */
    public enum CommentMode {
        /** 不显示注释，仅字段名。 */
        OFF,
        /** 悬停时以 Tooltip 显示注释。 */
        HOVER,
        /** 表头固定两行展示注释。 */
        INLINE
    }

    private static final String KEY_COMMENT_MODE = "sql.result.commentMode";

    private final Path file;
    private final ObjectProperty<CommentMode> commentMode =
            new SimpleObjectProperty<>(CommentMode.HOVER);

    public AppSettings() {
        this(Path.of(System.getProperty("user.home"), ".datacube", "settings.properties"));
    }

    public AppSettings(Path file) {
        this.file = file;
        load();
        // 首次注册后再监听，避免 load() 触发多余写回
        commentMode.addListener((obs, o, n) -> save());
    }

    public ObjectProperty<CommentMode> commentModeProperty() {
        return commentMode;
    }

    public CommentMode getCommentMode() {
        return commentMode.get();
    }

    public void setCommentMode(CommentMode mode) {
        if (mode != null) commentMode.set(mode);
    }

    // ---------- 持久化 ----------

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            LOG.warning("读取应用设置失败，使用默认值: " + e.getMessage());
            return;
        }
        String mode = p.getProperty(KEY_COMMENT_MODE);
        if (mode != null) {
            try {
                commentMode.set(CommentMode.valueOf(mode.trim()));
            } catch (IllegalArgumentException ignored) {
                // 未知值，保留默认
            }
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty(KEY_COMMENT_MODE, commentMode.get().name());
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "DataCube application settings");
            }
        } catch (IOException e) {
            LOG.warning("写入应用设置失败: " + e.getMessage());
        }
    }
}
