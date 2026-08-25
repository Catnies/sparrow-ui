package net.momirealms.sparrow.ui.window.map;

import net.momirealms.sparrow.ui.proxy.minecraft.world.level.material.MapColorProxy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;

@ApiStatus.Internal
public final class MapColorPalette {
    private static volatile MapColorProfile profile;

    private MapColorPalette() {
    }

    // 先读取运行中服务端的实际地图色, 再装配构建期生成的候选索引.
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

    // profile 完整构造后才由 volatile 字段发布, 转换线程不会看到半初始化数据.
    public static byte @NotNull [] imageToBytes(@NotNull BufferedImage image) {
        MapColorProfile current = profile;
        if (current == null) {
            throw new IllegalStateException("Map color palette is not initialized");
        }
        return current.imageToBytes(image);
    }
}
