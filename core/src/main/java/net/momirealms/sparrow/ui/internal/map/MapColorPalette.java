package net.momirealms.sparrow.ui.internal.map;

import net.momirealms.sparrow.ui.proxy.minecraft.world.level.material.MapColorProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;

@ApiStatus.Internal
public final class MapColorPalette {
    private static volatile MapColorProfile profile;

    private MapColorPalette() {
    }

    public static synchronized void initialize() {
        if (profile != null) {
            return;
        }

        int[] colors = new int[MapColorProfile.COLOR_COUNT];
        MapColorProxy proxy = MapColorProxy.INSTANCE;
        for (int id = 0; id < colors.length; id++) {
            colors[id] = proxy.getColorFromPackedId(id);
        }
        profile = MapColorProfile.load(colors);
    }

    public static byte @NotNull [] imageToBytes(@NotNull BufferedImage image) {
        MapColorProfile current = profile;
        if (current == null) {
            throw new IllegalStateException("Map color palette is not initialized");
        }
        return current.imageToBytes(image);
    }
}
