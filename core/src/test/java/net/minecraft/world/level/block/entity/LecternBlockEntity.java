package net.minecraft.world.level.block.entity;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

public class LecternBlockEntity extends BlockEntity {

    public final Container bookAccess = new LecternInventory();
    public class LecternInventory implements Container {
        private final SimpleContainer contents = new SimpleContainer(1);
        public LecternBlockEntity getLectern() {
            return LecternBlockEntity.this;
        }
        @Override
        public int getContainerSize() {
            return this.contents.getContainerSize();
        }
        @Override
        public int getMaxStackSize() {
            return 1;
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
}
