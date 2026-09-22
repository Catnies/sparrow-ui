package org.bukkit.craftbukkit.inventory;

public final class CraftItemStack extends org.bukkit.inventory.ItemStack {
    public static int copyCalls;
    public static int unwrapCalls;
    private final net.minecraft.world.item.ItemStack handle;

    public CraftItemStack(net.minecraft.world.item.ItemStack handle) {
        super(org.bukkit.Material.AIR, 0);
        this.handle = handle;
    }

    public static net.minecraft.world.item.ItemStack unwrap(org.bukkit.inventory.ItemStack item) {
        unwrapCalls++;
        if (item instanceof CraftItemStack craft) {
            return craft.handle == null ? net.minecraft.world.item.ItemStack.EMPTY : craft.handle;
        }
        return net.minecraft.world.item.ItemStack.wrap(item);
    }

    public static net.minecraft.world.item.ItemStack asNMSCopy(org.bukkit.inventory.ItemStack item) {
        copyCalls++;
        return net.minecraft.world.item.ItemStack.wrap(item.clone());
    }

    public static org.bukkit.inventory.ItemStack asCraftMirror(net.minecraft.world.item.ItemStack item) {
        return item.getBukkitStack();
    }
}
