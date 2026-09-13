package com.datacube.fx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * 雾紫数据折页 + 字标。窗口、工具栏、关于页与闪屏均使用构建期生成的品牌素材，
 * 与安装包 ICO 同源；不再维护另一套手绘立方体或旧图标回退。
 */
public final class BrandLogo {
    // 保留界面文字/背景色，图标换色不改变应用主题。
    static final Color BG_DEEP = Color.web("#0A0A12");
    static final Color BORDER = Color.web("#252538");
    static final Color FG = Color.web("#E8E8ED");
    static final Color FG_DIM = Color.web("#A8A8B8");
    static final Color FG_DEEP_MUTED = Color.web("#505068");

    static final String SLOGAN_CN = "每一面，皆是数据新维度";
    static final String SLOGAN_EN = "Every Face, A New Dimension of Data";
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private BrandLogo() {}

    private static final class Images {
        static final Image COMPACT = load("mark-compact.png");
        static final Image STANDARD = load("mark-standard.png");
        static final List<Image> WINDOWS = loadWindowIcons();
    }

    /** 逻辑尺寸决定造型，高分辨率原图交给 JavaFX 按显示缩放比采样。 */
    static Group mark(double size) {
        if (!Double.isFinite(size) || size <= 0) {
            throw new IllegalArgumentException("Brand mark size must be positive and finite");
        }
        ImageView view = new ImageView(size <= 32 ? Images.COMPACT : Images.STANDARD);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setMouseTransparent(true);
        return new Group(view);
    }

    /** 每个窗口独立设置尺寸集，图像本身安全共享。重复调用不会累积图标。 */
    public static void applyIcons(Stage stage) {
        stage.getIcons().setAll(Images.WINDOWS);
    }

    private static List<Image> loadWindowIcons() {
        List<Image> images = new ArrayList<>();
        for (int size : ICON_SIZES) images.add(load("icon-" + size + ".png"));
        return List.copyOf(images);
    }

    private static Image load(String name) {
        try (InputStream stream = BrandLogo.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing brand resource: " + name);
            Image image = new Image(stream);
            if (image.isError()) throw new IllegalStateException("Invalid brand resource: " + name, image.getException());
            return image;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read brand resource: " + name, e);
        }
    }

    /** 字标跟随应用主题，不在此处强制颜色。 */
    static Text wordmark(double fontSize) {
        Text text = new Text("datacube");
        text.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, fontSize));
        text.getStyleClass().add("brand-wordmark");
        return text;
    }

    static Text subtitle(double fontSize) {
        Text text = new Text("数据魔方");
        text.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, fontSize));
        text.getStyleClass().add("brand-subtitle");
        return text;
    }
}
