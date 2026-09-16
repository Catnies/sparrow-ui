package org.bukkit.craftbukkit.inventory;

public final class CraftItemStack {

    private CraftItemStack() {
    }

    public static net.minecraft.world.item.ItemStack unwrap(org.bukkit.inventory.ItemStack item) {
        return net.minecraft.world.item.ItemStack.wrap(item);
    }

    public static org.bukkit.inventory.ItemStack asCraftMirror(net.minecraft.world.item.ItemStack item) {
        return item.getBukkitStack();
    }
}
