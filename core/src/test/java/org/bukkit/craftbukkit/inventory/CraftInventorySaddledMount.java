package org.bukkit.craftbukkit.inventory;

import net.minecraft.world.Container;
import org.bukkit.inventory.ItemStack;

public class CraftInventorySaddledMount extends CraftInventory {

    private static final int SLOT_SADDLE = 0;
    private static final int SLOT_BODY_ARMOR = 1;
    private static final int SLOT_INVENTORY_START = 2;
    private final Container bodyArmorInventory;
    private final Container saddleInventory;
    public CraftInventorySaddledMount(Container mainInventory, Container bodyArmorInventory, Container saddleInventory) {
        super(mainInventory);
        this.bodyArmorInventory = bodyArmorInventory;
        this.saddleInventory = saddleInventory;
    }

    public Container getMainInventory() {
        return this.getInventory();
    }

    public Container getArmorInventory() {
        return this.bodyArmorInventory;
    }

    public Container getSaddleInventory() {
        return this.saddleInventory;
    }

    @Override
    public int getSize() {
        return this.getMainInventory().getContainerSize()
                + this.getArmorInventory().getContainerSize()
                + this.getSaddleInventory().getContainerSize();
    }

    @Override
    public ItemStack getItem(int index) {
        return switch (index) {
            case SLOT_SADDLE -> asBukkit(this.getSaddleInventory().getItem(0));
            case SLOT_BODY_ARMOR -> asBukkit(this.getArmorInventory().getItem(0));
            default -> asBukkit(this.getMainInventory().getItem(index - SLOT_INVENTORY_START));
        };
    }

    @Override
    public void setItem(int index, ItemStack item) {
        switch (index) {
            case SLOT_SADDLE -> this.getSaddleInventory().setItem(0, asNms(item));
            case SLOT_BODY_ARMOR -> this.getArmorInventory().setItem(0, asNms(item));
            default -> this.getMainInventory().setItem(index - SLOT_INVENTORY_START, asNms(item));
        }
    }

    @Override
    public ItemStack[] getContents() {
        ItemStack[] contents = new ItemStack[this.getSize()];
        for (int index = 0; index < contents.length; index++) {
            contents[index] = this.getItem(index);
        }
        return contents;
    }
}
