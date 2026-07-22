package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.AppSettings.Theme;

import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

import java.util.ArrayList;
import java.util.List;

/**
 * 明暗主题管理器：负责把 {@code theme-base.css} + 当前主题样式表应用到场景，
 * 并在 {@link AppSettings#themeProperty()} 变化时热切换所有已注册场景。
 *
 * <p>样式表以查找色（looked-up color）驱动：主题文件在 {@code .root} 定义
 * {@code -brand-*}/{@code -status-*}/{@code -code-*} 等变量与 Modena 基础色，
 * 结构规则集中在 {@code theme-base.css}，切换主题只需替换主题文件即可整体变色。
 *
 * <p>主窗口场景用 {@link #register(Scene)} 长期跟踪（随切换实时更新）；
 * 模态对话框用 {@link #applyTo(Scene)} / {@link #applyTo(DialogPane)} 一次性应用当前主题。
 */
public final class ThemeManager {

    private static final String BASE  = resource("theme-base.css");
    private static final String DARK  = resource("theme-dark.css");
    private static final String LIGHT = resource("theme-light.css");

    private final AppSettings settings;
    private final List<Scene> scenes = new ArrayList<>();

    public ThemeManager(AppSettings settings) {
        this.settings = settings;
        // 主题变更 → 重刷所有已注册场景（FX 线程）
        settings.themeProperty().addListener((obs, o, n) ->
                scenes.forEach(sc -> applySheets(sc.getStylesheets())));
    }

    /** 注册并应用主题到长期存在的场景（如主窗口），后续切换会实时更新。 */
    public void register(Scene scene) {
        if (scene == null) return;
        if (!scenes.contains(scene)) scenes.add(scene);
        applySheets(scene.getStylesheets());
    }

    /** 从跟踪列表移除场景（如临时窗口关闭时）。 */
    public void unregister(Scene scene) {
        scenes.remove(scene);
    }

    /** 一次性把当前主题应用到场景（不跟踪，适合短生命周期的模态窗口）。 */
    public void applyTo(Scene scene) {
        if (scene != null) applySheets(scene.getStylesheets());
    }

    /** 一次性把当前主题应用到对话框面板。 */
    public void applyTo(DialogPane pane) {
        if (pane != null) applySheets(pane.getStylesheets());
    }

    /** 在亮/暗之间切换（写回设置并触发持久化与实时刷新）。 */
    public void toggle() {
        settings.setTheme(settings.getTheme() == Theme.DARK ? Theme.LIGHT : Theme.DARK);
    }

    private void applySheets(ObservableList<String> sheets) {
        sheets.removeAll(BASE, DARK, LIGHT);
        sheets.add(BASE);
        sheets.add(settings.getTheme() == Theme.DARK ? DARK : LIGHT);
    }

    private static String resource(String name) {
        return ThemeManager.class.getResource("/com/datacube/fx/" + name).toExternalForm();
    }
}
