package com.datacube.config;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * 应用可自定义的快捷键动作清单：作为“说明 + 设置”的唯一数据源。
 *
 * <p>每个动作含持久化键、分组、说明文案与默认组合键。用户覆盖值由
 * {@link ShortcutSettings} 持久化；消费端在按键事件里用
 * {@link KeyCombination#match} 实时判定，改绑即时生效，无需重新注册。
 *
 * <p>仅收录可重新绑定的“动作类”快捷键；情境/鼠标类（Ctrl+点击、树内键入检索、
 * 数据表格 Enter/Delete/Esc 等）由 UI 固定处理，仅在设置面板中只读展示说明。
 */
public enum ShortcutAction {

    SQL_EXECUTE("sql.execute", "SQL 编辑器", "执行 SQL",
            new KeyCodeCombination(KeyCode.F5)),
    SQL_COMPLETE("sql.complete", "SQL 编辑器", "触发自动补全",
            new KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN)),
    SQL_LINE_COMMENT("sql.lineComment", "SQL 编辑器", "行注释切换",
            new KeyCodeCombination(KeyCode.SLASH, KeyCombination.CONTROL_DOWN)),
    SQL_BLOCK_COMMENT("sql.blockComment", "SQL 编辑器", "块注释切换",
            new KeyCodeCombination(KeyCode.SLASH, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN)),
    SQL_HISTORY("sql.history", "全局", "找回近期 SQL",
            new KeyCodeCombination(KeyCode.H, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));

    private final String persistKey;
    private final String category;
    private final String label;
    private final KeyCombination defaultCombo;

    ShortcutAction(String persistKey, String category, String label, KeyCombination defaultCombo) {
        this.persistKey = persistKey;
        this.category = category;
        this.label = label;
        this.defaultCombo = defaultCombo;
    }

    /** 持久化键（写入 {@code shortcuts.properties}）。 */
    public String persistKey() {
        return persistKey;
    }

    /** 分组名（用于设置面板归类展示）。 */
    public String category() {
        return category;
    }

    /** 说明文案（用于设置面板展示动作含义）。 */
    public String label() {
        return label;
    }

    /** 出厂默认组合键。 */
    public KeyCombination defaultCombo() {
        return defaultCombo;
    }
}
