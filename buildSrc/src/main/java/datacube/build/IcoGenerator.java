package datacube.build;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * 构建期图标生成器：用 Java2D 复刻 datacube「数据魔方」紫蓝渐变立方体，
 * 输出多尺寸 PNG 内嵌式 Windows {@code .ico}（供 jpackage 作为进程/安装包图标）。
 *
 * <p>几何、色板、逐面渐变与运行时 {@code com.datacube.fx.BrandLogo} 一致
 * （SVG viewBox 0..100）。仅在构建 JVM 上运行，不进入 app 模块。
 */
public final class IcoGenerator {

    // ── 品牌色板（与 BrandLogo 一致）──
    private static final Color PURPLE_LIGHT = Color.decode("#9B8CFF");
    private static final Color PURPLE       = Color.decode("#6C5CE7");
    private static final Color PURPLE_DEEP  = Color.decode("#5241CC");
    private static final Color BLUE         = Color.decode("#0984E3");
    private static final Color BLUE_DEEP    = Color.decode("#0659A8");

    /** Windows 图标常用尺寸集（含 256 大图）。 */
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private IcoGenerator() {
    }

    /** 命令行入口：{@code java IcoGenerator <输出.ico路径>}。 */
    public static void main(String[] args) throws IOException {
        Path out = Paths.get(args.length > 0 ? args[0] : "packaging/DataCube.ico");
        write(out);
        System.out.println("Generated icon: " + out.toAbsolutePath());
    }

    /** 生成多尺寸 .ico 到 {@code out}（自动创建父目录）。 */
    public static void write(Path out) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : SIZES) {
            pngs.add(toPng(render(s)));
        }
        byte[] ico = assembleIco(SIZES, pngs);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, ico);
    }

    /** 渲染指定尺寸的透明底立方体位图。 */
    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double s = size / 100.0;

        // 顶面（竖直渐变）
        face(g, s, PURPLE_LIGHT, PURPLE, 0.5, 0, 0.5, 1, 50, 14, 83, 33, 50, 52, 17, 33);
        // 左面
        face(g, s, PURPLE_DEEP, BLUE_DEEP, 1, 0, 0, 1, 17, 33, 50, 52, 50, 88, 17, 69);
        // 右面
        face(g, s, PURPLE, BLUE, 0, 0, 1, 1, 83, 33, 50, 52, 50, 88, 83, 69);

        // 三条自中心 (50,52) 的白色低透明棱线
        edge(g, s, 50, 52, 50, 88, 0.10);
        edge(g, s, 50, 52, 17, 33, 0.06);
        edge(g, s, 50, 52, 83, 33, 0.06);

        g.dispose();
        return img;
    }

    /** 填充单个面：比例渐变映射到该面包围盒（与 SVG objectBoundingBox 语义一致）。 */
    private static void face(Graphics2D g, double s, Color a, Color b,
                             double sx, double sy, double ex, double ey, double... pts) {
        Path2D path = new Path2D.Double();
        path.moveTo(pts[0] * s, pts[1] * s);
        for (int i = 2; i < pts.length; i += 2) {
            path.lineTo(pts[i] * s, pts[i + 1] * s);
        }
        path.closePath();

        Rectangle2D bb = path.getBounds2D();
        float x1 = (float) (bb.getX() + sx * bb.getWidth());
        float y1 = (float) (bb.getY() + sy * bb.getHeight());
        float x2 = (float) (bb.getX() + ex * bb.getWidth());
        float y2 = (float) (bb.getY() + ey * bb.getHeight());
        if (x1 == x2 && y1 == y2) {
            x2 += 0.01f; // GradientPaint 要求两点不重合
        }
        g.setPaint(new GradientPaint(x1, y1, a, x2, y2, b));
        g.fill(path);
    }

    private static void edge(Graphics2D g, double s, double x1, double y1, double x2, double y2, double opacity) {
        g.setColor(new Color(1f, 1f, 1f, (float) opacity));
        g.setStroke(new BasicStroke((float) Math.max(0.5, 0.5 * s)));
        g.draw(new Line2D.Double(x1 * s, y1 * s, x2 * s, y2 * s));
    }

    private static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** 组装 PNG 内嵌式 .ico（Vista+ 支持；现代 Windows 全尺寸接受 PNG 压缩图标）。 */
    private static byte[] assembleIco(int[] sizes, List<byte[]> pngs) {
        int n = sizes.length;
        int headerSize = 6 + n * 16;

        int[] offsets = new int[n];
        int cur = headerSize;
        for (int i = 0; i < n; i++) {
            offsets[i] = cur;
            cur += pngs.get(i).length;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // ICONDIR
        writeLE16(out, 0);   // reserved
        writeLE16(out, 1);   // type = icon
        writeLE16(out, n);   // image count
        // ICONDIRENTRY[]
        for (int i = 0; i < n; i++) {
            int sz = sizes[i];
            out.write(sz >= 256 ? 0 : sz);   // width（256 记为 0）
            out.write(sz >= 256 ? 0 : sz);   // height
            out.write(0);                    // palette color count
            out.write(0);                    // reserved
            writeLE16(out, 1);               // color planes
            writeLE16(out, 32);              // bits per pixel
            writeLE32(out, pngs.get(i).length);
            writeLE32(out, offsets[i]);
        }
        // 图像数据
        for (byte[] p : pngs) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static void writeLE16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeLE32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}
