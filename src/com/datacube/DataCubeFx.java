package com.datacube;

import com.datacube.fx.AppShell;
import com.datacube.fx.BrandLogo;
import com.datacube.fx.SplashScreen;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
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

            Scene scene = new Scene(appShell.getRoot(), 1200, 800);
            primaryStage.setTitle("DataCube 数据库管理工具");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(900);
            primaryStage.setMinHeight(600);
            BrandLogo.applyIcons(primaryStage);

            // 窗口关闭事件：迁移任务进行中提示确认
            primaryStage.setOnCloseRequest((WindowEvent e) -> {
                if (appShell.isRunning()) {
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                            "迁移任务正在执行中，强制关闭可能导致数据不完整。\n确定关闭？",
                            ButtonType.YES, ButtonType.NO);
                    alert.setHeaderText(null);
                    alert.showAndWait();
                    if (alert.getResult() != ButtonType.YES) {
                        e.consume();
                        return;
                    }
                    // 用户确认：调用取消逻辑并清理资源
                    Platform.runLater(() -> {
                        appShell.shutdown();
                    });
                } else {
                    appShell.shutdown();
                }
            });

            // 闪屏最短展示后淡出，再显示主窗口并触发后台更新自检（失败不打扰用户）
            final SplashScreen splashRef = splash;
            PauseTransition hold = new PauseTransition(Duration.millis(1100));
            hold.setOnFinished(ev -> {
                primaryStage.show();
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
