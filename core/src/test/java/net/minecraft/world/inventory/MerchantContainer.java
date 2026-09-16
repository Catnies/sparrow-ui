package net.minecraft.world.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

public class MerchantContainer implements Container {

    private final Object merchant;
    private final SimpleContainer contents = new SimpleContainer(3);
    public MerchantContainer(Object merchant) {
        this.merchant = merchant;
    }

    @Override
    public int getContainerSize() {
        return this.contents.getContainerSize();
    }

    @Override
    public int getMaxStackSize() {
        return this.contents.getMaxStackSize();
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.contents.getItem(slot);
    }

    @Override
    public void setItem(int slot, ItemStack item) {
        this.contents.setItem(slot, item);
    }
}
