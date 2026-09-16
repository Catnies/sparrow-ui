package net.minecraft.world.entity;

import java.util.UUID;

public class Entity {

    private boolean removed;
    private final UUID uuid = UUID.randomUUID();
    public final boolean isRemoved() {
        return this.removed;
    }

    public void setRemoved() {
        this.removed = true;
    }

    public UUID getUUID() {
        return this.uuid;
    }
}
