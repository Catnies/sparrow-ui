package org.bukkit.craftbukkit.inventory;

import net.minecraft.world.Container;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;

public class CraftInventory extends InventoryMock {

    private final Container container;
    public CraftInventory(Container container) {
        super(null, container.getContainerSize(), InventoryType.CHEST);
        this.container = container;
    }

    public Container getInventory() {
        return this.container;
    }

    protected static ItemStack asBukkit(net.minecraft.world.item.ItemStack item) {
        return item == net.minecraft.world.item.ItemStack.EMPTY ? null : item.getBukkitStack();
    }

    protected static net.minecraft.world.item.ItemStack asNms(ItemStack item) {
        return item == null || item.isEmpty()
                ? net.minecraft.world.item.ItemStack.EMPTY
                : net.minecraft.world.item.ItemStack.wrap(item.clone());
    }

    @Override
    public org.bukkit.Location getLocation() {
        return null;
    }

    @Override
    public ItemStack getItem(int slot) {
        return asBukkit(this.container.getItem(slot));
    }

    @Override
    public void setItem(int slot, ItemStack item) {
        this.container.setItem(slot, asNms(item));
    }

    @Override
    public ItemStack[] getContents() {
        ItemStack[] contents = new ItemStack[this.container.getContainerSize()];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = this.getItem(slot);
        }
        return contents;
    }
}
