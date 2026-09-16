package net.momirealms.sparrow.ui.window.map;

import net.momirealms.sparrow.ui.window.map.MapColorProfile;
import org.bukkit.map.MapPalette;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapColorProfileTest {

    private static final String EXPECTED_CACHE_MD5 = "E88EDD068D12D39934B40E8B6B124C83";

    @Test
    void bundledProfileMatchesPaperCacheForEveryRgb() throws NoSuchAlgorithmException {
        MapColorProfile profile = MapColorProfile.load(MapColorProfileTest.paperColors());
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] row = new byte[256];
        for (int red = 0; red < 256; red++) {
            for (int green = 0; green < 256; green++) {
                for (int blue = 0; blue < 256; blue++) {
                    row[blue] = profile.match(red, green, blue);
                }
                digest.update(row);
            }
        }

        assertEquals(EXPECTED_CACHE_MD5, HexFormat.of().withUpperCase().formatHex(digest.digest()));
    }

    @Test
    void imageConversionPreservesAlphaThresholdAndRowOrder() {
        MapColorProfile profile = MapColorProfile.load(MapColorProfileTest.paperColors());
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x7FFF0000);
        image.setRGB(1, 0, 0x80FF0000);
        image.setRGB(0, 1, 0xFFFFFFFF);
        image.setRGB(1, 1, 0xFF000000);

        assertArrayEquals(
                new byte[]{0, profile.match(255, 0, 0), profile.match(255, 255, 255), profile.match(0, 0, 0)},
                profile.imageToBytes(image)
        );
    }

    @Test
    void bundledProfileStaysBelowOneHundredTwentyKilobytes() throws IOException {
        try (InputStream input = MapColorProfile.class.getResourceAsStream("/map-color-profile.bin")) {
            assertNotNull(input);
            assertTrue(input.readAllBytes().length < 120_000);
        }
    }

    @SuppressWarnings("removal")
    private static int[] paperColors() {
        int[] colors = new int[MapColorProfile.COLOR_COUNT];
        for (int id = 0; id < colors.length; id++) {
            colors[id] = id < 248 ? MapPalette.getColor((byte) id).getRGB() : 0;
        }
        return colors;
    }
}
