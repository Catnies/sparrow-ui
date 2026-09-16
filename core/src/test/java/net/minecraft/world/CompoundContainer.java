package net.minecraft.world;

import net.minecraft.world.item.ItemStack;

public class CompoundContainer implements Container {

    public final Container container1;
    public final Container container2;
    public CompoundContainer(Container container1, Container container2) {
        this.container1 = container1;
        this.container2 = container2;
    }

    @Override
    public int getContainerSize() {
        return this.container1.getContainerSize() + this.container2.getContainerSize();
    }

    @Override
    public int getMaxStackSize() {
        return this.container1.getMaxStackSize();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot < this.container1.getContainerSize()
                ? this.container1.getItem(slot)
                : this.container2.getItem(slot - this.container1.getContainerSize());
    }

    @Override
    public void setItem(int slot, ItemStack item) {
        if (slot < this.container1.getContainerSize()) {
            this.container1.setItem(slot, item);
            return;
        }
        this.container2.setItem(slot - this.container1.getContainerSize(), item);
    }
}
