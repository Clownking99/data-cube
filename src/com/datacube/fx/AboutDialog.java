package com.datacube.fx;

import com.datacube.update.AppVersion;
import com.datacube.update.UpdateService;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * “关于”对话框：展示当前版本、项目仓库链接，并提供手动“检查更新”入口。
 */
final class AboutDialog {

    private static final String PROJECT_URL = "https://github.com/Clownking99/data-cube";

    private AboutDialog() {
    }

    static void show(UpdateService svc, Window owner) {
        Stage dialog = new Stage();
        if (owner != null) {
            dialog.initOwner(owner);
            dialog.initModality(Modality.WINDOW_MODAL);
        }
        dialog.setTitle("关于 DataCube");
        dialog.setResizable(false);

        Label name = new Label("DataCube 数据库管理工具");
        name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2e3440;");

        Label version = new Label("版本 " + AppVersion.current());
        version.setStyle("-fx-text-fill: #666;");

        Hyperlink repo = new Hyperlink("项目主页");
        repo.setOnAction(e -> UpdateUI.openUrl(PROJECT_URL));

        Button check = new Button("检查更新");
        check.setOnAction(e -> UpdateUI.checkManually(svc, dialog));

        Button close = new Button("关闭");
        close.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(8, check, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(10, name, version, repo, buttons);
        box.setPadding(new Insets(18));
        box.setPrefWidth(360);
        dialog.setScene(new Scene(box));
        dialog.showAndWait();
    }
}
