package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 快捷键设置面板：集中承担“说明 + 设置 + 冲突检测”三项职责。
 *
 * <p>上半区为可改绑的动作类快捷键（{@link ShortcutAction}）：每行含说明、当前组合键的
 * 录制按钮（点击后按下新组合键即录入，Esc 取消）与“恢复默认”。任一改动会实时重算冲突：
 * 若两个可改动作绑定了相同组合键，则红色高亮并在底部给出提示；{@link #hasConflict()}
 * 供对话框在保存前拦截。
 *
 * <p>下半区为情境/鼠标类快捷键（Ctrl+点击、树内键入检索、数据表格 Enter/Delete/Esc、
 * 补全弹窗导航等），由 UI 固定处理、无法改绑，仅只读展示说明。
 *
 * <p>编辑结果暂存于内部 {@code pending} 映射，仅在调用 {@link #apply()} 时写回
 * {@link ShortcutSettings} 持久化；消费端用 {@code match(event)} 实时判定，故保存后即时生效。
 */
final class ShortcutSettingsPane {

    /** 情境/鼠标类快捷键（固定不可改）：{分组, 组合键, 说明}，仅只读展示。 */
    private static final String[][] INFO = {
            {"SQL 编辑器", "Ctrl + 单击表名", "打开该表的表设计器"},
            {"连接树", "直接键入字母", "在可见节点中增量检索定位"},
            {"连接树", "Esc", "清除检索输入"},
            {"数据表格", "Enter", "提交当前单元格编辑"},
            {"数据表格", "Delete", "删除选中行"},
            {"数据表格", "Esc", "取消当前编辑"},
            {"补全弹窗", "↑ / ↓", "在候选项间移动"},
            {"补全弹窗", "Enter / Tab", "采用选中候选"},
            {"补全弹窗", "Esc", "关闭补全弹窗"},
    };

    private final ShortcutSettings shortcuts;
    private final Map<ShortcutAction, KeyCombination> pending = new EnumMap<>(ShortcutAction.class);
    private final Map<ShortcutAction, Button> recorders = new EnumMap<>(ShortcutAction.class);
    private final Label conflictLabel = new Label();
    private final VBox root = new VBox(10);

    ShortcutSettingsPane(ShortcutSettings shortcuts) {
        this.shortcuts = shortcuts;
        for (ShortcutAction a : ShortcutAction.values()) {
            pending.put(a, shortcuts.get(a));
        }
        build();
        refresh();
    }

    Node getNode() {
        return root;
    }

    /** 当前是否存在冲突（两个可改动作绑定了相同组合键）。 */
    boolean hasConflict() {
        return !conflictNames().isEmpty();
    }

    /** 将编辑结果写回存储（应在无冲突时调用）。 */
    void apply() {
        shortcuts.apply(pending);
    }

    // ---------- 构建 ----------

    private void build() {
        GridPane editable = new GridPane();
        editable.setHgap(10);
        editable.setVgap(8);
        editable.setPadding(new Insets(6, 2, 6, 2));
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(120);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(150);
        c1.setHgrow(Priority.ALWAYS);
        editable.getColumnConstraints().addAll(c0, c1, new ColumnConstraints());

        int row = 0;
        for (ShortcutAction a : ShortcutAction.values()) {
            Label label = new Label(a.category() + " · " + a.label());
            Button recorder = new Button();
            recorder.setPrefWidth(180);
            recorder.setMaxWidth(Double.MAX_VALUE);
            installRecorder(recorder, a);
            recorders.put(a, recorder);

            Button reset = new Button("恢复默认");
            reset.setOnAction(e -> {
                pending.put(a, a.defaultCombo());
                refresh();
            });

            editable.add(label, 0, row);
            editable.add(recorder, 1, row);
            editable.add(reset, 2, row);
            row++;
        }

        conflictLabel.setWrapText(true);
        conflictLabel.setStyle("-fx-text-fill: #e53935; -fx-font-size: 12px;");

        Label editHint = new Label("点击组合键按钮后按下新的组合键即录入（Esc 取消）。");
        editHint.setStyle("-fx-text-fill: #888; -fx-font-size: 12px;");
        editHint.setWrapText(true);

        // 只读说明区
        GridPane info = new GridPane();
        info.setHgap(10);
        info.setVgap(6);
        info.setPadding(new Insets(6, 2, 2, 2));
        int r = 0;
        for (String[] item : INFO) {
            Label cat = new Label(item[0]);
            cat.setStyle("-fx-text-fill: #888;");
            Label combo = new Label(item[1]);
            combo.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace;");
            Label desc = new Label(item[2]);
            info.add(cat, 0, r);
            info.add(combo, 1, r);
            info.add(desc, 2, r);
            r++;
        }
        Label infoTitle = new Label("以下为固定快捷键（随情境/鼠标触发，不可改绑）：");
        infoTitle.setStyle("-fx-font-weight: bold;");

        root.getChildren().addAll(editHint, editable, conflictLabel,
                new Separator(), infoTitle, info);
    }

    /** 分隔线（避免额外 import 冲突，直接内联一个细横条）。 */
    private static final class Separator extends Region {
        Separator() {
            setPrefHeight(1);
            setMinHeight(1);
            setStyle("-fx-background-color: derive(-fx-base, -12%);");
            VBox.setMargin(this, new Insets(4, 0, 4, 0));
        }
    }

    /** 为录制按钮装配“点击进入录制 → 按键写入 pending”的交互。 */
    private void installRecorder(Button btn, ShortcutAction action) {
        final boolean[] capturing = {false};
        btn.setOnAction(e -> {
            capturing[0] = true;
            btn.setText("按下组合键…（Esc 取消）");
        });
        btn.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!capturing[0]) return;
            e.consume();
            KeyCode code = e.getCode();
            if (code == KeyCode.ESCAPE) {
                capturing[0] = false;
                refresh();
                return;
            }
            if (code == null || code == KeyCode.UNDEFINED || code.isModifierKey()) {
                return; // 仅按下修饰键时继续等待主键
            }
            KeyCombination combo = comboOf(e, code);
            if (combo != null) {
                pending.put(action, combo);
                capturing[0] = false;
                refresh();
            }
        });
    }

    /** 由按键事件构造组合键（保留当前按下的修饰键）。 */
    private static KeyCombination comboOf(KeyEvent e, KeyCode code) {
        List<KeyCombination.Modifier> mods = new ArrayList<>();
        if (e.isControlDown()) mods.add(KeyCombination.CONTROL_DOWN);
        if (e.isShiftDown()) mods.add(KeyCombination.SHIFT_DOWN);
        if (e.isAltDown()) mods.add(KeyCombination.ALT_DOWN);
        if (e.isMetaDown()) mods.add(KeyCombination.META_DOWN);
        return new KeyCodeCombination(code, mods.toArray(new KeyCombination.Modifier[0]));
    }

    // ---------- 冲突检测与刷新 ----------

    /** 参与冲突的动作集合（组合键名出现两次及以上者）。 */
    private java.util.Set<ShortcutAction> conflictNames() {
        Map<String, List<ShortcutAction>> byName = new HashMap<>();
        for (ShortcutAction a : ShortcutAction.values()) {
            byName.computeIfAbsent(pending.get(a).getName(), k -> new ArrayList<>()).add(a);
        }
        java.util.Set<ShortcutAction> conflicts = new java.util.HashSet<>();
        for (List<ShortcutAction> group : byName.values()) {
            if (group.size() > 1) conflicts.addAll(group);
        }
        return conflicts;
    }

    /** 依据 pending 重刷所有录制按钮文案、冲突高亮与提示。 */
    private void refresh() {
        java.util.Set<ShortcutAction> conflicts = conflictNames();
        for (ShortcutAction a : ShortcutAction.values()) {
            Button btn = recorders.get(a);
            btn.setText(pending.get(a).getDisplayText());
            if (conflicts.contains(a)) {
                btn.setStyle("-fx-border-color: #e53935; -fx-border-width: 1.5;");
            } else {
                btn.setStyle(null);
            }
        }
        if (conflicts.isEmpty()) {
            conflictLabel.setText("");
            conflictLabel.setManaged(false);
            conflictLabel.setVisible(false);
        } else {
            List<String> labels = new ArrayList<>();
            for (ShortcutAction a : conflicts) labels.add(a.label());
            conflictLabel.setManaged(true);
            conflictLabel.setVisible(true);
            conflictLabel.setText("⚠ 快捷键冲突：" + String.join("、", labels) + " 绑定了相同组合键，请调整后再保存。");
        }
    }
}
