package datacube.build;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class IcoGeneratorTest {
    @TempDir Path directory;
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private final Path assets = Path.of(System.getProperty("brand.assetDir"));
    private Path standard() { return assets.resolve("icon-mist-violet.png"); }
    private Path compact() { return assets.resolve("icon-mist-violet-compact.png"); }

    @Test
    void icoContainsExactlyTheRuntimePngsWithValidDirectoryEntries() throws Exception {
        Path pngDir = directory.resolve("pngs");
        Path ico = directory.resolve("nested/DataCube.ico");
        IcoGenerator.writePngs(standard(), compact(), pngDir);
        IcoGenerator.write(standard(), compact(), ico);
        byte[] bytes = Files.readAllBytes(ico);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, buffer.getShort());
        assertEquals(1, buffer.getShort());
        assertEquals(SIZES.length, buffer.getShort());
        int nextOffset = 6 + SIZES.length * 16;
        for (int size : SIZES) {
            int encoded = size == 256 ? 0 : size;
            assertEquals(encoded, Byte.toUnsignedInt(buffer.get()));
            assertEquals(encoded, Byte.toUnsignedInt(buffer.get()));
            assertEquals(0, buffer.get()); assertEquals(0, buffer.get());
            assertEquals(1, buffer.getShort()); assertEquals(32, buffer.getShort());
            int length = buffer.getInt();
            int offset = buffer.getInt();
            assertEquals(nextOffset, offset);
            assertTrue(length > 0 && offset + length <= bytes.length);
            byte[] png = Arrays.copyOfRange(bytes, offset, offset + length);
            assertArrayEquals(Files.readAllBytes(pngDir.resolve("icon-" + size + ".png")), png);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(size, image.getWidth()); assertEquals(size, image.getHeight());
            assertEquals(0, image.getRGB(0, 0) >>> 24);
            assertEquals(0, image.getRGB(size - 1, size - 1) >>> 24);
            assertTrue((image.getRGB(size / 2, size / 2) >>> 24) > 240, "empty icon at " + size);
            nextOffset += length;
        }
        assertEquals(bytes.length, nextOffset, "no unreferenced data after ICO images");
        assertEquals(512, ImageIO.read(pngDir.resolve("mark-standard.png").toFile()).getWidth());
        assertEquals(256, ImageIO.read(pngDir.resolve("mark-compact.png").toFile()).getWidth());
    }

    @Test
    void compactThresholdAndStandardMaskPreserveDifferentSourceColors() throws Exception {
        Path standard = fixture("standard.png", 512, 512, 0xffc05050, false);
        Path compact = fixture("compact.png", 512, 512, 0xff5060c0, true);
        IcoGenerator.writePngs(standard, compact, directory.resolve("pngs"));
        for (int size : SIZES) {
            BufferedImage image = ImageIO.read(directory.resolve("pngs/icon-" + size + ".png").toFile());
            assertEquals(size <= 32 ? 0xff5060c0 : 0xffc05050, image.getRGB(size / 2, size / 2));
            if (size >= 48) {
                assertEquals(0, image.getRGB(0, size / 2) >>> 24, "presentation background must be removed");
                assertTrue((image.getRGB(size / 2, size / 8) >>> 24) > 240, "tile must retain its top face");
            }
        }
    }

    @Test
    void changedInputReplacesExistingOutputsDeterministically() throws Exception {
        Path source = fixture("changing.png", 512, 512, 0xff905070, false);
        Path small = fixture("small.png", 512, 512, 0xff8070b0, true);
        Path out = directory.resolve("icon.ico");
        IcoGenerator.write(source, small, out); byte[] first = Files.readAllBytes(out);
        IcoGenerator.write(source, small, out); assertArrayEquals(first, Files.readAllBytes(out));
        fixture("changing.png", 512, 512, 0xff308090, false);
        IcoGenerator.write(source, small, out);
        assertFalse(Arrays.equals(first, Files.readAllBytes(out)), "updated artwork must change the icon");
    }

    @Test
    void opaqueCompactMasterIsRejectedBeforeWritingOutputs() throws Exception {
        Path opaque = fixture("opaque.png", 512, 512, 0xffa0a0a0, false);
        Path out = directory.resolve("bad.ico");
        assertThrows(IOException.class, () -> IcoGenerator.write(standard(), opaque, out));
        assertFalse(Files.exists(out));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "text", "small", "rectangular"})
    void invalidMastersFailWithoutCreatingOutput(String type) throws Exception {
        Path source = directory.resolve("invalid.png");
        switch (type) {
            case "text" -> Files.writeString(source, "not a PNG");
            case "small" -> fixture("invalid.png", 256, 256, 0xff808080, false);
            case "rectangular" -> fixture("invalid.png", 512, 600, 0xff808080, false);
            default -> { }
        }
        Path out = directory.resolve("bad.ico");
        assertThrows(IOException.class, () -> IcoGenerator.write(source, compact(), out));
        assertFalse(Files.exists(out));
    }

    private Path fixture(String name, int width, int height, int color, boolean transparentCorner) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height]; Arrays.fill(pixels, color);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        if (transparentCorner) image.setRGB(0, 0, 0);
        Path file = directory.resolve(name); ImageIO.write(image, "png", file.toFile()); return file;
    }
}
