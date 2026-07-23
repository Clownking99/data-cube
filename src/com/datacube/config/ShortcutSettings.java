package com.datacube.config;

import javafx.scene.input.KeyCombination;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 快捷键设置持久化：读写 {@code ~/.datacube/shortcuts.properties}。
 *
 * <p>仅持久化“与默认不同”的覆盖值（组合键以 {@link KeyCombination#getName()} 存储，
 * 用 {@link KeyCombination#keyCombination(String)} 解析，可无损往返）。
 * 与 {@link AppSettings} 同风格无第三方依赖；缺失/损坏时回退默认，不阻断启动。
 *
 * <p>消费端在按键事件里调用 {@link #get(ShortcutAction)} 并用 {@code match(event)}
 * 实时判定，故设置更改后即时生效，无需重新注册监听。
 */
public final class ShortcutSettings {

    private static final Logger LOG = Logger.getLogger(ShortcutSettings.class.getName());

    private final Path file;
    private final Map<ShortcutAction, KeyCombination> overrides = new EnumMap<>(ShortcutAction.class);

    public ShortcutSettings() {
        this(Path.of(System.getProperty("user.home"), ".datacube", "shortcuts.properties"));
    }

    public ShortcutSettings(Path file) {
        this.file = file;
        load();
    }

    /** 指定动作的当前组合键：有覆盖值用覆盖值，否则用出厂默认。 */
    public synchronized KeyCombination get(ShortcutAction action) {
        KeyCombination c = overrides.get(action);
        return c != null ? c : action.defaultCombo();
    }

    /** 是否为非默认（已被用户改绑）。 */
    public synchronized boolean isCustomized(ShortcutAction action) {
        return overrides.containsKey(action);
    }

    /**
     * 用给定映射整体替换当前绑定：与默认相同的项不落库，其余作为覆盖值保存。
     * {@code null} 值表示恢复该动作为默认。变更后立即写回（best-effort）。
     */
    public synchronized void apply(Map<ShortcutAction, KeyCombination> bindings) {
        overrides.clear();
        for (ShortcutAction a : ShortcutAction.values()) {
            KeyCombination c = bindings.get(a);
            if (c != null && !c.getName().equals(a.defaultCombo().getName())) {
                overrides.put(a, c);
            }
        }
        save();
    }

    // ---------- 持久化 ----------

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            LOG.warning("读取快捷键设置失败，使用默认值: " + e.getMessage());
            return;
        }
        for (ShortcutAction a : ShortcutAction.values()) {
            String name = p.getProperty(a.persistKey());
            if (name == null || name.isBlank()) continue;
            try {
                KeyCombination c = KeyCombination.keyCombination(name.trim());
                if (!c.getName().equals(a.defaultCombo().getName())) {
                    overrides.put(a, c);
                }
            } catch (RuntimeException bad) {
                LOG.warning("跳过无法解析的快捷键 [" + a.persistKey() + "=" + name + "]: " + bad.getMessage());
            }
        }
    }

    private void save() {
        Properties p = new Properties();
        for (Map.Entry<ShortcutAction, KeyCombination> e : overrides.entrySet()) {
            p.setProperty(e.getKey().persistKey(), e.getValue().getName());
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "DataCube keyboard shortcuts (overrides only)");
            }
        } catch (IOException e) {
            LOG.warning("写入快捷键设置失败: " + e.getMessage());
        }
    }
}
