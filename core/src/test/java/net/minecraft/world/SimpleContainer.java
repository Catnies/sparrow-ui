package net.minecraft.world;

import net.minecraft.world.item.ItemStack;
import org.bukkit.inventory.InventoryHolder;
import java.util.Arrays;

public class SimpleContainer implements Container {

    private final ItemStack[] items;
    private final InventoryHolder owner;
    public SimpleContainer(int size) {
        this(size, null);
    }

    public SimpleContainer(int size, InventoryHolder owner) {
        this.items = new ItemStack[size];
        this.owner = owner;
        Arrays.fill(this.items, ItemStack.EMPTY);
    }

    public InventoryHolder getOwner() {
        return this.owner;
    }

    @Override
    public int getContainerSize() {
        return this.items.length;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items[slot];
    }

    @Override
    public void setItem(int slot, ItemStack item) {
        this.items[slot] = item == null ? ItemStack.EMPTY : item;
    }

    @Override
    public void clearContent() {
        Arrays.fill(this.items, ItemStack.EMPTY);
    }
}
