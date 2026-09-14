package com.datacube.fx;

import java.util.EnumMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;

/** Tab-local visibility only: retains both live nodes, never reloads their content. FX-thread confined. */
final class SqlPanelLayout implements AutoCloseable {
    enum Mode {
        SPLIT("上下分屏", "分屏", "split"), EDITOR("仅看 SQL", "SQL", "editor"), RESULTS("仅看结果", "结果", "results");
        final String label, shortLabel, id;
        Mode(String label, String shortLabel, String id) { this.label = label; this.shortLabel = shortLabel; this.id = id; }
    }

    private final Node editor, results;
    private final BooleanSupplier allowed;
    private final Runnable beforeHideEditor;
    private final SplitPane split = new SplitPane();
    private final MenuButton menu = new MenuButton();
    private final EnumMap<Mode, RadioMenuItem> items = new EnumMap<>(Mode.class);
    private Mode mode = Mode.SPLIT;
    private double divider = 0.38;
    private boolean closed;
    private Scene pendingScene;
    private Runnable pendingRestore;
    private long restoreGeneration;
    private final ChangeListener<Scene> sceneChanged = (value, before, after) -> cancelRestore();

    SqlPanelLayout(Node editor, Node results, BooleanSupplier allowed, Runnable beforeHideEditor) {
        this.editor = Objects.requireNonNull(editor);
        this.results = Objects.requireNonNull(results);
        this.allowed = Objects.requireNonNull(allowed);
        this.beforeHideEditor = Objects.requireNonNull(beforeHideEditor);
        split.setId("sql-panel-split");
        split.setOrientation(Orientation.VERTICAL);
        split.getItems().addAll(editor, results);
        split.setDividerPositions(divider);
        split.sceneProperty().addListener(sceneChanged);
        menu.setId("sql-layout");
        menu.setAccessibleText("SQL 与结果布局");
        menu.setTooltip(new Tooltip("切换当前标签的显示区域；保留 SQL 和结果，不重新查询。返回分屏会恢复分隔位置。"));
        ToggleGroup group = new ToggleGroup();
        for (Mode value : Mode.values()) {
            RadioMenuItem item = new RadioMenuItem(value.label);
            item.setId("sql-layout-" + value.id);
            item.setToggleGroup(group);
            item.setOnAction(event -> select(value));
            items.put(value, item);
            menu.getItems().add(item);
        }
        menu.setOnShowing(event -> syncMenu());
        syncMenu();
    }

    SplitPane node() { return split; }
    MenuButton menu() { return menu; }
    Mode mode() { return mode; }

    boolean revealEditor() {
        if (!canChange()) return false;
        return mode != Mode.RESULTS || select(Mode.SPLIT);
    }

    boolean select(Mode next) {
        Objects.requireNonNull(next);
        if (!canChange()) { syncMenu(); return false; }
        if (next == mode) { syncMenu(); return true; }
        // A second action before the pulse must retain the intended divider, not its transient skin value.
        if (mode == Mode.SPLIT && pendingRestore == null) divider = split.getDividerPositions()[0];
        cancelRestore();
        if (next == Mode.RESULTS) beforeHideEditor.run();
        switch (next) {
            case SPLIT -> {
                split.getItems().setAll(editor, results);
                split.setDividerPositions(divider);
            }
            case EDITOR -> split.getItems().setAll(editor);
            case RESULTS -> split.getItems().setAll(results);
        }
        mode = next;
        syncMenu();
        if (next == Mode.SPLIT) restoreAfterLayout();
        return true;
    }

    private void restoreAfterLayout() {
        Scene scene = split.getScene();
        if (scene == null) return;
        // SplitPaneSkin may normalize newly reattached content on the next layout pulse.
        // Reapply once afterwards; never keep forcing the position against later user drags.
        pendingScene = scene;
        long generation = restoreGeneration;
        pendingRestore = () -> {
            if (generation != restoreGeneration) return;
            cancelRestore();
            if (mode == Mode.SPLIT && split.getScene() == scene && canChange()) split.setDividerPositions(divider);
        };
        scene.addPostLayoutPulseListener(pendingRestore);
        Platform.requestNextPulse();
    }

    private void cancelRestore() {
        restoreGeneration++;
        if (pendingRestore != null) pendingScene.removePostLayoutPulseListener(pendingRestore);
        pendingRestore = null;
        pendingScene = null;
    }

    private boolean canChange() { return !closed && !menu.isDisabled() && !split.isDisabled() && allowed.getAsBoolean(); }

    private void syncMenu() {
        boolean disabled = !canChange();
        items.forEach((value, item) -> { item.setSelected(value == mode); item.setDisable(disabled); });
        menu.setText("布局：" + mode.shortLabel);
    }

    @Override public void close() {
        closed = true;
        cancelRestore();
        split.sceneProperty().removeListener(sceneChanged);
        menu.hide();
        menu.setDisable(true);
        syncMenu();
    }
}
