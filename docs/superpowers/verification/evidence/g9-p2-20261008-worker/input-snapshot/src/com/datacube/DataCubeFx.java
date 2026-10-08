package com.datacube;

import com.datacube.fx.AppShell;
import com.datacube.fx.BrandLogo;
import com.datacube.fx.WindowShutdownController;
import com.datacube.fx.SplashScreen;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import javafx.util.Duration;

public class DataCubeFx extends Application {

    @Override
    public void start(Stage primaryStage) {
        SplashScreen splash = null;
        try {
            // 品牌启动闪屏：主窗口就绪前短暂呈现
            splash = new SplashScreen();
            splash.show();

            AppShell appShell = new AppShell();

            var shutdownWindow = new WindowShutdownController(primaryStage, appShell.getRoot(),
                    appShell::isRunning, appShell::shutdownAsync);
            Scene scene = new Scene(shutdownWindow.getRoot(), 1200, 800);
            appShell.getThemeManager().register(scene);
            // 全局窗口钩子：二级弹窗（关于/设置/导出/更新/Alert）自动跟随主题（含原生标题栏）
            appShell.getThemeManager().installWindowHook();
            primaryStage.setTitle("DataCube 数据库管理工具");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);
            BrandLogo.applyIcons(primaryStage);

            // 闪屏最短展示后淡出，再显示主窗口并触发后台更新自检（失败不打扰用户）
            final SplashScreen splashRef = splash;
            PauseTransition hold = new PauseTransition(Duration.millis(1100));
            hold.setOnFinished(ev -> {
                primaryStage.show();
                com.datacube.update.UpdateStartup.acknowledge(getParameters().getRaw(),
                        com.datacube.update.AppVersion.current(),
                        com.datacube.update.InstallMode.appDir().orElse(null));
                // 窗口显示后再让原生标题栏跟随明暗主题（按标题定位窗口句柄）
                appShell.enableNativeTitleBarTheming(primaryStage.getTitle());
                if (splashRef != null) {
                    splashRef.fadeAndClose(null);
                }
                appShell.checkForUpdatesOnStartup();
            });
            hold.play();
        } catch (Exception e) {
            if (splash != null) {
                try { splash.close(); } catch (Exception ignored) {}
            }
            // UI 初始化失败的兜底提示
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR,
                        "GUI 启动失败: " + e.getMessage() + "\n\n请检查 JavaFX 模块是否正确配置。",
                        ButtonType.OK);
                alert.setHeaderText(null);
                alert.showAndWait();
            } catch (Exception ignored) {}
            throw e;
        }
    }
}
