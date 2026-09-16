package net.minecraft.world;

import net.minecraft.world.item.ItemStack;

public interface Container extends Clearable {

    int getContainerSize();
    int getMaxStackSize();
    ItemStack getItem(int slot);
    void setItem(int slot, ItemStack item);

    default boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    default ItemStack removeItem(int slot, int amount) {
        throw new UnsupportedOperationException();
    }

    default ItemStack removeItemNoUpdate(int slot) {
        throw new UnsupportedOperationException();
    }

    default void setChanged() {
        throw new UnsupportedOperationException();
    }

    default boolean stillValid(net.minecraft.world.entity.player.Player player) {
        throw new UnsupportedOperationException();
    }

    default java.util.List<ItemStack> getContents() {
        throw new UnsupportedOperationException();
    }

    default void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        throw new UnsupportedOperationException();
    }

    default void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        throw new UnsupportedOperationException();
    }

    default java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        throw new UnsupportedOperationException();
    }

    default org.bukkit.inventory.InventoryHolder getOwner() {
        throw new UnsupportedOperationException();
    }

    default void setMaxStackSize(int maxStackSize) {
        throw new UnsupportedOperationException();
    }

    default org.bukkit.Location getLocation() {
        throw new UnsupportedOperationException();
    }

}
