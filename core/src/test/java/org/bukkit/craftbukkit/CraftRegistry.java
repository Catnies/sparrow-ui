package org.bukkit.craftbukkit;

import net.minecraft.resources.ResourceKey;
import org.bukkit.Keyed;

public final class CraftRegistry {
    public static final Object ACCESS = new Object();

    public static Object getMinecraftRegistry() {
        return ACCESS;
    }

    public static Object getMinecraftRegistry(ResourceKey key) {
        return key;
    }

    public static Object bukkitToMinecraft(Keyed value) {
        return value;
    }

    public static Object bukkitToMinecraftHolder(Keyed value) {
        return new Lookup(value, null);
    }

    public static Object bukkitToMinecraftHolder(Keyed value, ResourceKey key) {
        return new Lookup(value, key);
    }

    public record Lookup(Keyed value, ResourceKey key) {
    }
}
