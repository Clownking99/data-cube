package com.datacube.fx;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BrandLogoTest {
    @Test
    void marksUseCompactThrough32AndStandardAboveWithIndependentNodes() throws Exception {
        FxUiTestSupport.call(() -> {
            ImageView small = view(20);
            ImageView boundary = view(32);
            ImageView above = view(32.01);
            ImageView about = view(44);
            ImageView splash = view(84);
            assertEquals(256, small.getImage().getWidth());
            assertEquals(512, above.getImage().getWidth());
            assertSame(small.getImage(), boundary.getImage());
            assertSame(above.getImage(), about.getImage());
            assertSame(above.getImage(), splash.getImage());
            assertNotSame(small.getImage(), above.getImage());
            assertNotSame(small, view(20));
            assertEquals(20, small.getFitWidth()); assertEquals(20, small.getFitHeight());
            assertEquals(44, about.getFitWidth()); assertEquals(84, splash.getFitHeight());
            assertTrue(small.isPreserveRatio()); assertTrue(small.isSmooth());
            assertTrue(small.isMouseTransparent());
            return null;
        });
    }

    @Test
    void applyIconsInstallsAllSevenTransparentSizesWithoutDuplicates() throws Exception {
        FxUiTestSupport.call(() -> {
            Stage first = new Stage(); Stage second = new Stage();
            try {
                BrandLogo.applyIcons(first); BrandLogo.applyIcons(first); BrandLogo.applyIcons(second);
                int[] sizes = {16, 24, 32, 48, 64, 128, 256};
                assertEquals(sizes.length, first.getIcons().size());
                for (int i = 0; i < sizes.length; i++) {
                    Image image = first.getIcons().get(i);
                    assertEquals(sizes[i], image.getWidth()); assertEquals(sizes[i], image.getHeight());
                    assertSame(image, second.getIcons().get(i));
                    assertEquals(0, image.getPixelReader().getArgb(0, 0) >>> 24);
                    assertTrue(image.getPixelReader().getColor(sizes[i] / 2, sizes[i] / 2).getOpacity() > .9);
                }
                first.getIcons().clear();
                assertEquals(7, second.getIcons().size());
                BrandLogo.applyIcons(first); assertEquals(7, first.getIcons().size());
            } finally { first.close(); second.close(); }
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidMarkSizeIsRejected(double size) {
        assertThrows(IllegalArgumentException.class, () -> BrandLogo.mark(size));
    }

    @Test
    void artworkIsVioletAndKeepsLightCellsAndTransparentExterior() throws Exception {
        FxUiTestSupport.call(() -> {
            Image small = view(20).getImage();
            Image large = view(84).getImage();
            var face = large.getPixelReader().getColor(130, 160);
            assertTrue(face.getBlue() > face.getGreen() + .1, "not green or the previous blue cube");
            assertTrue(face.getRed() > face.getGreen() + .05, "violet has a red component");
            var cell = large.getPixelReader().getColor(150, 230);
            assertTrue(cell.getRed() > .8 && cell.getGreen() > .8 && cell.getBlue() > .85);
            assertEquals(0, large.getPixelReader().getArgb(0, 256) >>> 24);
            assertEquals(0, small.getPixelReader().getArgb(0, 128) >>> 24);
            assertTrue(large.getPixelReader().getColor(65, 256).getOpacity() > .99, "standard tile is retained");
            return null;
        });
    }

    private ImageView view(double size) {
        Group group = BrandLogo.mark(size);
        assertEquals(1, group.getChildren().size());
        return assertInstanceOf(ImageView.class, group.getChildren().getFirst());
    }
}
