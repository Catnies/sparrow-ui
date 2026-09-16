package org.bukkit.craftbukkit.inventory;

import net.minecraft.world.Container;
import org.bukkit.inventory.ItemStack;

public class CraftResultInventory extends CraftInventory {

    private final Container resultInventory;
    public CraftResultInventory(Container ingredientsInventory, Container resultInventory) {
        super(ingredientsInventory);
        this.resultInventory = resultInventory;
    }

    public Container getResultInventory() {
        return this.resultInventory;
    }

    public Container getIngredientsInventory() {
        return this.getInventory();
    }

    @Override
    public int getSize() {
        return this.getResultInventory().getContainerSize() + this.getIngredientsInventory().getContainerSize();
    }

    @Override
    public ItemStack getItem(int index) {
        int ingredientsSize = this.getIngredientsInventory().getContainerSize();
        return index < ingredientsSize
                ? asBukkit(this.getIngredientsInventory().getItem(index))
                : asBukkit(this.getResultInventory().getItem(index - ingredientsSize));
    }

    @Override
    public void setItem(int index, ItemStack item) {
        int ingredientsSize = this.getIngredientsInventory().getContainerSize();
        if (index < ingredientsSize) {
            this.getIngredientsInventory().setItem(index, asNms(item));
            return;
        }
        this.getResultInventory().setItem(index - ingredientsSize, asNms(item));
    }
}
