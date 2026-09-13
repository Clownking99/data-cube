package datacube.build;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** 从雾紫折页母版生成 PNG 与 ICO；Java2D 仅用于构建期，不进入应用模块。 */
public final class IcoGenerator {
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private IcoGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: IcoGenerator <standard.png> <compact.png> <output.ico>");
        }
        write(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
    }

    /** 两种输出走同一渲染路径，避免进程图标与 JavaFX 窗口图标漂移。 */
    public static void write(Path standard, Path compact, Path out) throws IOException {
        Sources sources = load(standard, compact);
        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) pngs.add(toPng(render(sources, size)));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, assembleIco(pngs));
    }

    public static void writePngs(Path standard, Path compact, Path dir) throws IOException {
        Sources sources = load(standard, compact);
        Files.createDirectories(dir);
        for (int size : SIZES) {
            Files.write(dir.resolve("icon-" + size + ".png"), toPng(render(sources, size)));
        }
        // 界面内按逻辑尺寸选择造型，较高分辨率素材用于高 DPI 显示。
        Files.write(dir.resolve("mark-standard.png"), toPng(scale(sources.standard(), 512)));
        Files.write(dir.resolve("mark-compact.png"), toPng(scale(sources.compact(), 256)));
    }

    private static Sources load(Path standard, Path compact) throws IOException {
        BufferedImage large = readMaster(standard);
        BufferedImage small = readMaster(compact);
        if (!small.getColorModel().hasAlpha() || (small.getRGB(0, 0) >>> 24) != 0) {
            throw new IOException("Compact icon must have a transparent exterior: " + compact);
        }
        return new Sources(appTile(large), small);
    }

    private static BufferedImage readMaster(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null || image.getWidth() != image.getHeight() || image.getWidth() < 512) {
            throw new IOException("Icon master must be a square image of at least 512 pixels: " + path);
        }
        return image;
    }

    /**
     * 标准母版保留设计稿，构建时套用统一应用圆角遮罩，
     * 排除外侧灰色展示背景，边缘保持真正透明。
     */
    private static BufferedImage appTile(BufferedImage source) {
        int size = source.getWidth();
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = graphics(result);
        try {
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(size * .07, size * .07,
                    size * .86, size * .86, size * .24, size * .24));
            g.setComposite(AlphaComposite.SrcIn);
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    private static BufferedImage render(Sources sources, int size) {
        return scale(size <= 32 ? sources.compact() : sources.standard(), size);
    }

    /** 逐级缩小，预乘 alpha 避免透明边缘产生黑边。 */
    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage current = source;
        while (current.getWidth() > size) {
            int next = Math.max(size, current.getWidth() / 2);
            BufferedImage target = new BufferedImage(next, next, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = graphics(target);
            try {
                g.setComposite(AlphaComposite.Src);
                g.drawImage(current, 0, 0, next, next, null);
            } finally {
                g.dispose();
            }
            current = target;
        }
        return current;
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("PNG writer is unavailable");
        return out.toByteArray();
    }

    private static byte[] assembleIco(List<byte[]> pngs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLE16(out, 0);
        writeLE16(out, 1);
        writeLE16(out, SIZES.length);
        int offset = 6 + SIZES.length * 16;
        for (int i = 0; i < SIZES.length; i++) {
            int size = SIZES[i];
            out.write(size == 256 ? 0 : size);
            out.write(size == 256 ? 0 : size);
            out.write(0);
            out.write(0);
            writeLE16(out, 1);
            writeLE16(out, 32);
            writeLE32(out, pngs.get(i).length);
            writeLE32(out, offset);
            offset += pngs.get(i).length;
        }
        for (byte[] png : pngs) out.writeBytes(png);
        return out.toByteArray();
    }

    private static void writeLE16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeLE32(ByteArrayOutputStream out, int value) {
        writeLE16(out, value);
        writeLE16(out, value >>> 16);
    }

    private record Sources(BufferedImage standard, BufferedImage compact) {}
}
