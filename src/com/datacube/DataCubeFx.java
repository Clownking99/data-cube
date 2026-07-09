package com.datacube;

import com.datacube.fx.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

public class DataCubeFx extends Application {

    @Override
    public void start(Stage primaryStage) {
        MainController controller;
        try {
            controller = new MainController();
            VBox root = controller.createUI();

            ScrollPane scrollPane = new ScrollPane(root);
            scrollPane.setFitToWidth(true);
            scrollPane.setFitToHeight(true);

            Scene scene = new Scene(scrollPane, 950, 780);
            primaryStage.setTitle("DataCube 迁移工具");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(700);
            primaryStage.setMinHeight(560);

            // 窗口关闭事件：任务进行中提示确认
            primaryStage.setOnCloseRequest((WindowEvent e) -> {
                if (controller.isRunning()) {
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                            "任务正在执行中，强制关闭可能导致数据不完整。\n确定关闭？",
                            ButtonType.YES, ButtonType.NO);
                    alert.setHeaderText(null);
                    alert.showAndWait();
                    if (alert.getResult() != ButtonType.YES) {
                        e.consume();
                        return;
                    }
                    // 用户确认：调用取消逻辑并清理资源
                    Platform.runLater(() -> {
                        controller.shutdown();
                    });
                } else {
                    controller.shutdown();
                }
            });

            primaryStage.show();
        } catch (Exception e) {
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
