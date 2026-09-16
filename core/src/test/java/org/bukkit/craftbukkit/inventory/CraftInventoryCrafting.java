package org.bukkit.craftbukkit.inventory;

import net.minecraft.world.Container;
import org.bukkit.inventory.ItemStack;

public class CraftInventoryCrafting extends CraftInventory {

    private final Container resultInventory;
    public CraftInventoryCrafting(Container matrixInventory, Container resultInventory) {
        super(matrixInventory);
        this.resultInventory = resultInventory;
    }

    public Container getResultInventory() {
        return this.resultInventory;
    }

    public Container getMatrixInventory() {
        return this.getInventory();
    }

    @Override
    public int getSize() {
        return this.getResultInventory().getContainerSize() + this.getMatrixInventory().getContainerSize();
    }

    @Override
    public ItemStack getItem(int index) {
        int resultSize = this.getResultInventory().getContainerSize();
        return index < resultSize
                ? asBukkit(this.getResultInventory().getItem(index))
                : asBukkit(this.getMatrixInventory().getItem(index - resultSize));
    }

    @Override
    public void setItem(int index, ItemStack item) {
        int resultSize = this.getResultInventory().getContainerSize();
        if (index < resultSize) {
            this.getResultInventory().setItem(index, asNms(item));
            return;
        }
        this.getMatrixInventory().setItem(index - resultSize, asNms(item));
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
