package com.datacube.fx;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * 启动闪屏：无边框深色圆角卡片，展示品牌立方体 + 字标 + Slogan。
 *
 * <p>在主窗口就绪前短暂呈现，随后淡出关闭（见 {@link DataCubeFx}）。
 */
public final class SplashScreen {

    private final Stage stage = new Stage();
    private final StackPane root;

    public SplashScreen() {
        stage.initStyle(StageStyle.TRANSPARENT);

        Text word = BrandLogo.wordmark(30);
        Text cn = BrandLogo.subtitle(14);
        cn.setFill(BrandLogo.FG_DIM);

        Region divider = new Region();
        divider.setPrefSize(64, 1);
        divider.setMaxSize(64, 1);
        divider.setBackground(new Background(new BackgroundFill(BrandLogo.BORDER, null, null)));
        VBox.setMargin(divider, new Insets(10, 0, 10, 0));

        Text slogan = new Text(BrandLogo.SLOGAN_CN);
        slogan.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, 12));
        slogan.setFill(BrandLogo.FG_DEEP_MUTED);

        VBox card = new VBox(6, BrandLogo.cube(84), word, cn, divider, slogan);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(48, 56, 44, 56));
        card.setBackground(new Background(new BackgroundFill(
                BrandLogo.BG_DEEP, new CornerRadii(14), null)));
        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.web("#000000", 0.55));
        shadow.setRadius(28);
        shadow.setOffsetY(6);
        card.setEffect(shadow);

        root = new StackPane(card);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, Color.TRANSPARENT);
        stage.setScene(scene);
        BrandLogo.applyIcons(stage);
        stage.centerOnScreen();
    }

    public void show() {
        stage.show();
        stage.centerOnScreen();
    }

    /** 淡出后关闭闪屏，结束时执行 {@code after}（可空）。 */
    public void fadeAndClose(Runnable after) {
        FadeTransition fade = new FadeTransition(Duration.millis(260), root);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            stage.close();
            if (after != null) {
                after.run();
            }
        });
        fade.play();
    }

    public void close() {
        stage.close();
    }
}
