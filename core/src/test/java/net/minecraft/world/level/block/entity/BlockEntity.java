package net.minecraft.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public class BlockEntity {

    private boolean removed;
    private Level level;
    private BlockPos worldPosition = new BlockPos(0L);
    public boolean isRemoved() {
        return this.removed;
    }

    public void setRemoved() {
        this.removed = true;
    }

    public Level getLevel() {
        return this.level;
    }

    public BlockPos getBlockPos() {
        return this.worldPosition;
    }

    public void placeAt(Level level, long packedPos) {
        this.level = level;
        this.worldPosition = new BlockPos(packedPos);
    }
}
