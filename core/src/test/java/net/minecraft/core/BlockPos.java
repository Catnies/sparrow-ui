package net.minecraft.core;

public class BlockPos {

    private final long packed;
    public BlockPos(long packed) {
        this.packed = packed;
    }

    public long asLong() {
        return this.packed;
    }
}
