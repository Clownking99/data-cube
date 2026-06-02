package com.datacube;

import com.datacube.fx.MainController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class DataCubeFx extends Application {

    @Override
    public void start(Stage primaryStage) {
        MainController controller = new MainController();
        VBox root = controller.createUI();

        ScrollPane scrollPane = new ScrollPane(root);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);

        Scene scene = new Scene(scrollPane, 700, 750);
        primaryStage.setTitle("DataCube 迁移工具");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(600);
        primaryStage.setMinHeight(500);
        primaryStage.show();
    }
}
