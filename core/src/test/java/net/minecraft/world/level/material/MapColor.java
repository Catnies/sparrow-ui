package net.minecraft.world.level.material;

public final class MapColor {

    private MapColor() {
    }

    public static int getColorFromPackedId(int packedId) {
        return 0xFF000000 | packedId & 0xFF;
    }
}
