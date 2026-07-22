package com.datacube.fx;

import com.datacube.update.AppVersion;
import com.datacube.update.UpdateService;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * “关于”对话框：品牌立方体 + 字标 + 简介 + 数据库标签 + Slogan，
 * 展示当前版本、项目仓库链接，并提供手动“检查更新”入口。
 */
final class AboutDialog {

    private static final String PROJECT_URL = "https://github.com/Clownking99/data-cube";

    private AboutDialog() {
    }

    static void show(UpdateService svc, Window owner, ThemeManager themeManager) {
        Stage dialog = new Stage();
        if (owner != null) {
            dialog.initOwner(owner);
            dialog.initModality(Modality.WINDOW_MODAL);
        }
        dialog.setTitle("关于 DataCube");
        dialog.setResizable(false);
        BrandLogo.applyIcons(dialog);

        // 头部：立方体 + datacube / 数据魔方
        VBox nameBox = new VBox(1, BrandLogo.wordmark(20), BrandLogo.subtitle(11));
        nameBox.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(14, BrandLogo.cube(44), nameBox);
        header.setAlignment(Pos.CENTER_LEFT);

        Label version = new Label("版本 " + AppVersion.current());
        version.setStyle("-fx-text-fill: -brand-fg-muted;");

        Label desc = new Label(
                "DataCube 是一款面向数据库开发者与管理员的专业工具，"
                + "目前支持 PostgreSQL 与 Oracle，涵盖对象浏览、SQL 编辑、"
                + "表设计、数据编辑与库间迁移。");
        desc.setWrapText(true);
        desc.setStyle("-fx-text-fill: -brand-fg-dim;");

        FlowPane tags = new FlowPane(6, 6, tag("PostgreSQL"), tag("Oracle"), tag("SQL 编辑"), tag("数据迁移"));

        Text slogan = new Text(BrandLogo.SLOGAN_CN + "  ·  " + BrandLogo.SLOGAN_EN);
        slogan.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, 12));
        slogan.getStyleClass().add("brand-slogan");

        Hyperlink repo = new Hyperlink("项目主页");
        repo.setStyle("-fx-text-fill: -brand-accent;");
        repo.setOnAction(e -> UpdateUI.openUrl(PROJECT_URL));

        Button check = new Button("检查更新");
        check.setOnAction(e -> UpdateUI.checkManually(svc, dialog));
        Button close = new Button("关闭");
        close.setOnAction(e -> dialog.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox buttons = new HBox(8, repo, spacer, check, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);
        divider.setStyle("-fx-background-color: -brand-border;");

        VBox box = new VBox(14, header, version, desc, tags, divider, slogan, buttons);
        box.setPadding(new Insets(22, 24, 20, 24));
        box.setPrefWidth(420);
        box.getStyleClass().add("about-card");

        Scene scene = new Scene(box);
        if (themeManager != null) themeManager.applyTo(scene);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private static Label tag(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("tag-chip");
        return l;
    }
}
