package net.minecraft.world.level;

import org.bukkit.World;

public class Level {

    private final World world;
    public Level(World world) {
        this.world = world;
    }

    public World getWorld() {
        return this.world;
    }
}
