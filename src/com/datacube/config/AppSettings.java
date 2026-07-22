package com.datacube.config;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
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

    /** 界面明暗主题。 */
    public enum Theme {
        /** 亮色主题（近 Modena 默认 + 品牌强调色）。 */
        LIGHT,
        /** 暗色主题（品牌深色调）。 */
        DARK
    }

    private static final String KEY_COMMENT_MODE = "sql.result.commentMode";
    private static final String KEY_MAX_RESULT_ROWS = "sql.result.maxRows";
    private static final String KEY_MAX_HEAP_MB = "jvm.maxHeapMb";
    private static final String KEY_THEME = "ui.theme";

    /** 结果集默认最大保留行数（0 表示不限制）。 */
    public static final int DEFAULT_MAX_RESULT_ROWS = 2000;
    /** 应用最大堆（MB），写入启动器配置下次启动生效。 */
    public static final int DEFAULT_MAX_HEAP_MB = 512;

    private final Path file;
    private final ObjectProperty<CommentMode> commentMode =
            new SimpleObjectProperty<>(CommentMode.HOVER);
    private final IntegerProperty maxResultRows =
            new SimpleIntegerProperty(DEFAULT_MAX_RESULT_ROWS);
    private final IntegerProperty maxHeapMb =
            new SimpleIntegerProperty(DEFAULT_MAX_HEAP_MB);
    private final ObjectProperty<Theme> theme =
            new SimpleObjectProperty<>(Theme.DARK);

    public AppSettings() {
        this(Path.of(System.getProperty("user.home"), ".datacube", "settings.properties"));
    }

    public AppSettings(Path file) {
        this.file = file;
        load();
        // 首次注册后再监听，避免 load() 触发多余写回
        commentMode.addListener((obs, o, n) -> save());
        maxResultRows.addListener((obs, o, n) -> save());
        maxHeapMb.addListener((obs, o, n) -> save());
        theme.addListener((obs, o, n) -> save());
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

    public IntegerProperty maxResultRowsProperty() {
        return maxResultRows;
    }

    public int getMaxResultRows() {
        return maxResultRows.get();
    }

    public void setMaxResultRows(int rows) {
        maxResultRows.set(Math.max(0, rows));
    }

    public ObjectProperty<Theme> themeProperty() {
        return theme;
    }

    public Theme getTheme() {
        return theme.get();
    }

    public void setTheme(Theme t) {
        if (t != null) theme.set(t);
    }

    public IntegerProperty maxHeapMbProperty() {
        return maxHeapMb;
    }

    public int getMaxHeapMb() {
        return maxHeapMb.get();
    }

    public void setMaxHeapMb(int mb) {
        // 下限 128MB，避免误设过小导致无法启动
        maxHeapMb.set(Math.max(128, mb));
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
        maxResultRows.set(parseInt(p.getProperty(KEY_MAX_RESULT_ROWS), DEFAULT_MAX_RESULT_ROWS));
        maxHeapMb.set(parseInt(p.getProperty(KEY_MAX_HEAP_MB), DEFAULT_MAX_HEAP_MB));
        String themeName = p.getProperty(KEY_THEME);
        if (themeName != null) {
            try {
                theme.set(Theme.valueOf(themeName.trim()));
            } catch (IllegalArgumentException ignored) {
                // 未知值，保留默认
            }
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty(KEY_COMMENT_MODE, commentMode.get().name());
        p.setProperty(KEY_MAX_RESULT_ROWS, Integer.toString(maxResultRows.get()));
        p.setProperty(KEY_MAX_HEAP_MB, Integer.toString(maxHeapMb.get()));
        p.setProperty(KEY_THEME, theme.get().name());
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
