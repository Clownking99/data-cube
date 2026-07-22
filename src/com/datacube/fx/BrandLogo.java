package com.datacube.fx;

import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 品牌视觉工厂：datacube「数据魔方」紫蓝渐变立方体 + 字标。
 *
 * <p>纯矢量复刻（{@link Polygon} + {@link LinearGradient}），与 {@code datacube-brand-assets/}
 * 的 SVG 逐面渐变一致，任意分辨率清晰，不依赖外部位图或字体。窗口/任务栏图标由
 * {@link #cube(double)} 节点 {@link javafx.scene.Node#snapshot} 成位图注入 {@link Stage#getIcons()}。
 */
public final class BrandLogo {

    // ── 品牌色板（brand-guide.html）──
    static final Color PURPLE_LIGHT  = Color.web("#9B8CFF");
    static final Color PURPLE        = Color.web("#6C5CE7");
    static final Color PURPLE_DEEP   = Color.web("#5241CC");
    static final Color BLUE          = Color.web("#0984E3");
    static final Color BLUE_DEEP     = Color.web("#0659A8");
    static final Color ACCENT        = Color.web("#00D2D3");
    static final Color BG_DEEP       = Color.web("#0A0A12");
    static final Color BG            = Color.web("#0E0E14");
    static final Color SURFACE       = Color.web("#151520");
    static final Color BORDER        = Color.web("#252538");
    static final Color FG            = Color.web("#E8E8ED");
    static final Color FG_DIM        = Color.web("#A8A8B8");
    static final Color FG_MUTED      = Color.web("#6B6B80");
    static final Color FG_DEEP_MUTED = Color.web("#505068");

    static final String SLOGAN_CN = "每一面，皆是数据新维度";
    static final String SLOGAN_EN = "Every Face, A New Dimension of Data";

    private BrandLogo() {
    }

    /**
     * 立方体图标：SVG viewBox 0..100 等比缩放到 {@code size}×{@code size}（含原始留白）。
     * 返回不含背景的 {@link Group}，可直接置于任意深/浅背景。
     */
    static Group cube(double size) {
        double s = size / 100.0;

        Polygon top = face(s, 50, 14, 83, 33, 50, 52, 17, 33);
        top.setFill(grad(0.5, 0, 0.5, 1, PURPLE_LIGHT, PURPLE));
        Polygon left = face(s, 17, 33, 50, 52, 50, 88, 17, 69);
        left.setFill(grad(1, 0, 0, 1, PURPLE_DEEP, BLUE_DEEP));
        Polygon right = face(s, 83, 33, 50, 52, 50, 88, 83, 69);
        right.setFill(grad(0, 0, 1, 1, PURPLE, BLUE));

        Group g = new Group(top, left, right);
        g.getChildren().addAll(
                edge(s, 50, 52, 50, 88, 0.10),
                edge(s, 50, 52, 17, 33, 0.06),
                edge(s, 50, 52, 83, 33, 0.06));
        return g;
    }

    /** 立方体渲染为透明底位图（用于 {@link Stage#getIcons()}）。须在 FX 线程调用。 */
    static Image icon(double px) {
        Group g = cube(px);
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        int dim = (int) Math.ceil(px);
        WritableImage img = new WritableImage(dim, dim);
        g.snapshot(sp, img);
        return img;
    }

    /** 窗口图标尺寸集（与 buildSrc IcoGenerator/打包 .ico 一致）。 */
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256};

    /**
     * 为窗口注入多尺寸品牌图标（任务栏/标题栏/Alt-Tab 各取所需尺寸）。
     *
     * <p>优先用构建期生成的 PNG 资源（{@code /com/datacube/fx/icon-<size>.png}，
     * 与打包 .ico 同一 Java2D 渲染），资源缺失时回退到矢量快照。
     */
    public static void applyIcons(Stage stage) {
        List<Image> icons = new ArrayList<>();
        for (int s : ICON_SIZES) {
            Image img = loadIconResource(s);
            if (img != null && !img.isError()) {
                icons.add(img);
            }
        }
        if (icons.isEmpty()) {
            for (int s : ICON_SIZES) {
                icons.add(icon(s));
            }
        }
        stage.getIcons().setAll(icons);
    }

    /** 从 classpath 加载构建期生成的图标 PNG（缺失返回 {@code null}）。 */
    private static Image loadIconResource(int size) {
        try (InputStream in = BrandLogo.class.getResourceAsStream("icon-" + size + ".png")) {
            return in == null ? null : new Image(in);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 英文字标 {@code datacube}（品牌小写锁定形态）。
     *
     * <p>不在代码里 {@code setFill}，而是挂样式类 {@code brand-wordmark}，
     * 由主题样式表提供颜色（随明暗主题变色）；无主题的独立场景
     * （如启动闪屏）需自行 {@code setFill}。
     */
    static Text wordmark(double fontSize) {
        Text t = new Text("datacube");
        t.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, fontSize));
        t.getStyleClass().add("brand-wordmark");
        return t;
    }

    /** 中文副标 {@code 数据魔方}（颜色同 {@link #wordmark(double)} 由主题提供）。 */
    static Text subtitle(double fontSize) {
        Text t = new Text("数据魔方");
        t.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, fontSize));
        t.getStyleClass().add("brand-subtitle");
        return t;
    }

    // ── 内部构造 ──

    private static Polygon face(double s, double... viewBoxPts) {
        Polygon p = new Polygon();
        for (double v : viewBoxPts) {
            p.getPoints().add(v * s);
        }
        return p;
    }

    private static Line edge(double s, double x1, double y1, double x2, double y2, double opacity) {
        Line l = new Line(x1 * s, y1 * s, x2 * s, y2 * s);
        l.setStroke(new Color(1, 1, 1, opacity));
        l.setStrokeWidth(Math.max(0.5, 0.5 * s));
        return l;
    }

    /** 比例渐变（映射到各面包围盒，与 SVG {@code objectBoundingBox} 语义一致）。 */
    private static LinearGradient grad(double sx, double sy, double ex, double ey, Color a, Color b) {
        return new LinearGradient(sx, sy, ex, ey, true, CycleMethod.NO_CYCLE,
                new Stop(0, a), new Stop(1, b));
    }
}
