package com.datacube.fx;

import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** 实际 AppShell + 实际品牌资源；仅允许独立空配置，不发起启动更新检查。 */
public final class BrandIconDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        if (!Path.of(System.getProperty("user.home")).getFileName().toString().equals("brand-desktop-profile")) {
            throw new IllegalStateException("An isolated brand-desktop-profile is required");
        }
        AppShell shell = new AppShell();
        BorderPane root = new BorderPane(shell.getRoot());
        VBox comparison = new VBox(12, sampleRow(false), sampleRow(true));
        comparison.setPadding(new Insets(10)); root.setBottom(comparison);
        Scene scene = new Scene(root, 1160, 880);
        shell.getThemeManager().register(scene);
        shell.getThemeManager().installWindowHook();
        stage.setTitle("DataCube — 雾紫图标验收（隔离配置）");
        stage.setScene(scene); BrandLogo.applyIcons(stage);
        stage.setOnCloseRequest(event -> {
            event.consume(); root.setDisable(true);
            shell.shutdownAsync().whenComplete((result, error) -> Platform.runLater(() -> {
                if (error != null) throw new IllegalStateException(error);
                stage.setOnCloseRequest(null); stage.close();
            }));
        });
        stage.show(); shell.enableNativeTitleBarTheming(stage.getTitle());
    }

    private HBox sampleRow(boolean dark) {
        HBox row = new HBox(18); row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(12));
        row.setStyle("-fx-background-color: " + (dark ? "#151520;" : "#f3f3f6;"));
        Label title = new Label(dark ? "深色背景" : "浅色背景");
        title.setStyle("-fx-text-fill: " + (dark ? "white;" : "#252538;")); row.getChildren().add(title);
        for (int size : new int[]{16, 24, 32, 48, 64, 128}) {
            Image image = new Image(BrandLogo.class.getResource("icon-" + size + ".png").toExternalForm());
            ImageView view = new ImageView(image); view.setFitWidth(size); view.setFitHeight(size);
            Label label = new Label(size + " px"); label.setStyle("-fx-text-fill: " + (dark ? "white;" : "#252538;"));
            VBox cell = new VBox(6, view, label); cell.setAlignment(Pos.CENTER);
            row.getChildren().add(cell);
        }
        return row;
    }
}
