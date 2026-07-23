package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.AppSettings.Theme;

import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

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
    private boolean windowHookInstalled;

    /** 原生标题栏定位重试次数：JavaFX 设置原生窗口标题相对 show 略有延迟，首次 FindWindowW 常失败。 */
    private static final int TITLE_BAR_RETRIES = 12;

    public ThemeManager(AppSettings settings) {
        this.settings = settings;
        // 主题变更 → 重刷所有已注册场景 + 当前打开的二级窗口（FX 线程）
        settings.themeProperty().addListener((obs, o, n) -> onThemeChanged());
    }

    private void onThemeChanged() {
        // 先切换进程级应用模式：此后新建窗口默认即为对应明暗，避免弹窗打开时的标题栏闪烁
        NativeTitleBar.setAppDarkMode(settings.getTheme() == Theme.DARK);
        scenes.forEach(sc -> applySheets(sc.getStylesheets()));
        // 同步刷新当前打开的二级窗口（对话框/更新等）：样式表 + 原生标题栏配色
        for (Window w : Window.getWindows()) {
            if (!isThemableStage(w)) continue;
            Stage stage = (Stage) w;
            Scene sc = stage.getScene();
            if (sc != null && !scenes.contains(sc)) applySheets(sc.getStylesheets());
            applyTitleBar(stage, TITLE_BAR_RETRIES, -1);
        }
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

    /**
     * 安装全局窗口钩子：此后任何新出现的二级窗口（关于/设置/导出/更新/Alert 等）
     * 自动套用当前主题样式，并让 Windows 原生标题栏跟随明暗。应在 FX 线程、主窗口创建后调用一次。
     *
     * <p>仅处理带系统标题栏的常规窗口（{@code DECORATED}/{@code UTILITY}）；
     * 透明闪屏、Tooltip、右键菜单等 Popup 一律跳过，避免破坏其自定义外观。
     */
    public void installWindowHook() {
        if (windowHookInstalled) return;
        windowHookInstalled = true;
        // 进程级首选应用模式：让此后新建的二级窗口标题栏“出生即为当前明暗”，消除打开瞬间的闪烁
        NativeTitleBar.setAppDarkMode(settings.getTheme() == Theme.DARK);
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) {
                for (Window w : c.getAddedSubList()) decorateWindow(w);
            }
        });
        // 已存在的窗口（如主窗口）也处理一遍
        for (Window w : Window.getWindows()) decorateWindow(w);
    }

    private void decorateWindow(Window w) {
        if (!isThemableStage(w)) return;
        Stage stage = (Stage) w;
        Scene sc = stage.getScene();
        if (sc != null && !scenes.contains(sc)) {
            applySheets(sc.getStylesheets());
        } else if (sc == null) {
            stage.sceneProperty().addListener((o, old, ns) -> {
                if (ns != null && !scenes.contains(ns)) applySheets(ns.getStylesheets());
            });
        }
        // 是否为主窗口（其场景已注册）：主窗口不需隐藏（启动时被闪屏遮住），二级弹窗需隐藏防闪烁
        boolean isPrimary = sc != null && scenes.contains(sc);
        double revealOpacity = stage.getOpacity();
        // 二级弹窗：先隐形（opacity=0），在隐形状态下套好深色标题栏再显形，
        // 避免看到“浅色→深色”的标题栏闪动；主窗口保持原样。
        Runnable start = () -> {
            if (!isPrimary) stage.setOpacity(0);
            applyTitleBar(stage, TITLE_BAR_RETRIES, isPrimary ? -1 : revealOpacity);
        };
        if (stage.isShowing()) start.run();
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> start.run());
    }

    /**
     * 应用原生标题栏配色；若窗口句柄尚未就绪（FindWindowW 失败）则稍后重试。
     *
     * @param revealOpacity ≥ 0 时，完成（成功或重试耗尽）后将窗口不透明度恢复为此值（用于“隐形→显形”）；
     *                      &lt; 0 表示不改动不透明度（如主窗口或主题实时切换）。
     */
    private void applyTitleBar(Stage stage, int attemptsLeft, double revealOpacity) {
        if (!stage.isShowing()) return;
        boolean ok = NativeTitleBar.apply(stage.getTitle(), settings.getTheme() == Theme.DARK);
        if (ok || attemptsLeft <= 0) {
            if (revealOpacity >= 0) stage.setOpacity(revealOpacity);
            return;
        }
        PauseTransition pause = new PauseTransition(Duration.millis(12));
        pause.setOnFinished(e -> applyTitleBar(stage, attemptsLeft - 1, revealOpacity));
        pause.play();
    }

    /** 仅带系统标题栏的常规窗口可套用主题；排除闪屏(TRANSPARENT)、Popup 等。 */
    private static boolean isThemableStage(Window w) {
        if (!(w instanceof Stage stage)) return false;
        StageStyle style = stage.getStyle();
        return style == StageStyle.DECORATED || style == StageStyle.UTILITY;
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
